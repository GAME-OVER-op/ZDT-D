#!/usr/bin/env python3
import argparse
import socket
import struct
import threading
import select
import sys
import errno
import time
import logging
import signal
import json
import os
import functools
import base64
import hashlib
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer
try:
    from http.server import ThreadingHTTPServer as THServer
except Exception:
    THServer = HTTPServer
from typing import Optional, Tuple
from collections import deque

logger = logging.getLogger('t2s')
logger.addHandler(logging.NullHandler())

try:
    import h2.connection
    import h2.config
    import h2.events
    import h2.settings
    HTTP2_AVAILABLE = True
except ImportError:
    HTTP2_AVAILABLE = False
    logger.warning("HTTP/2 support disabled (h2 library not available)")

try:
    import requests
    DOH_AVAILABLE = True
except ImportError:
    DOH_AVAILABLE = False
    logger.warning("DNS-over-HTTPS support disabled (requests library not available)")

START_TIME = time.time()
SO_ORIGINAL_DST = 80
IP6T_SO_ORIGINAL_DST_FALLBACK = 80

_stats_lock = threading.Lock()
_stats = {
    'active_connections': 0,
    'total_connections': 0,
    'total_bytes_client_to_remote': 0,
    'total_bytes_remote_to_client': 0,
    'udp_sessions': 0,
    'bytes_udp_c2r': 0,
    'bytes_udp_r2c': 0,
    'errors': 0,
    'connection_timeouts': 0,
    'auth_failures': 0,
    'dns_failures': 0,
    'active_udp_sessions': 0,
    'web_requests_total': 0,
    'web_requests_errors': 0,
    'socks5_bypass_count': 0,
    'socks5_recovered_count': 0,
    'direct_connections': 0,
    'connections_http': 0,
    'connections_https': 0,
    'connections_dns': 0,
    'connections_other': 0,
    'bytes_http_c2r': 0,
    'bytes_http_r2c': 0,
    'bytes_https_c2r': 0,
    'bytes_https_r2c': 0,
    'bytes_dns_c2r': 0,
    'bytes_dns_r2c': 0,
    'bytes_other_c2r': 0,
    'bytes_other_r2c': 0,
    'http2_connections': 0,
    'doh_queries': 0,
    'enhanced_cache_hits': 0,
}

_conns_lock = threading.Lock()
_conns = {}

shutdown_event = threading.Event()
_reload_event = threading.Event()

_WEB_SOCKS_HOST = None
_WEB_SOCKS_PORT = None

_rate_limiter = None

_socks5_backends = []
_backend_idx = 0
_backend_lock = threading.Lock()
_backend_status = {}
_socks5_available = True
_socks5_lock = threading.Lock()
_socks5_last_check = 0
_socks5_check_interval = 10

_second_signal_forced = threading.Event()

_enhanced_dns = None
_http2_handler = None

def setup_logger(level=logging.INFO, logfile=None):
    fmt = '%(asctime)s [%(levelname)s] %(message)s'
    if logfile:
        logging.basicConfig(level=level, format=fmt, filename=logfile)
    else:
        logging.basicConfig(level=level, format=fmt)
    return logging.getLogger('t2s')

def recv_exact(sock: socket.socket, n: int, timeout: float = None) -> bytes:
    old = sock.gettimeout()
    try:
        sock.settimeout(timeout)
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("peer closed while reading exact bytes")
            buf.extend(chunk)
        return bytes(buf)
    finally:
        try:
            sock.settimeout(old)
        except Exception:
            pass

def parse_original_dst(raw: bytes) -> Tuple[str, int]:
    if not raw:
        raise RuntimeError("empty SO_ORIGINAL_DST")
    family = None
    if len(raw) >= 2:
        try:
            family = struct.unpack_from('!H', raw, 0)[0]
        except Exception:
            family = None
    if family == socket.AF_INET and len(raw) >= 16:
        port = struct.unpack_from('!H', raw, 2)[0]
        ip_bytes = raw[4:8]
        ip_str = socket.inet_ntoa(ip_bytes)
        return ip_str, port
    if family == socket.AF_INET6 and len(raw) >= 28:
        port = struct.unpack_from('!H', raw, 2)[0]
        addr_raw = raw[8:24]
        ip_str = socket.inet_ntop(socket.AF_INET6, addr_raw)
        return ip_str, port
    if len(raw) >= 16:
        try:
            port = struct.unpack_from('!H', raw, 2)[0]
            ip_bytes = raw[4:8]
            ip_str = socket.inet_ntoa(ip_bytes)
            return ip_str, port
        except Exception:
            pass
    raise RuntimeError(f"unsupported SO_ORIGINAL_DST raw length/family (len={len(raw)})")

def get_original_dst(conn: socket.socket) -> Tuple[str, int]:
    SOL_IP = getattr(socket, 'SOL_IP', socket.IPPROTO_IP)
    SOL_IPV6 = getattr(socket, 'SOL_IPV6', getattr(socket, 'IPPROTO_IPV6', None))
    for opt in ((SOL_IP, SO_ORIGINAL_DST), (SOL_IPV6, SO_ORIGINAL_DST), (SOL_IPV6, IP6T_SO_ORIGINAL_DST_FALLBACK)):
        if opt[0] is None:
            continue
        try:
            od = conn.getsockopt(opt[0], opt[1], 128)
            return parse_original_dst(od)
        except Exception:
            continue
    raise RuntimeError("SO_ORIGINAL_DST not available (socket not redirected or platform unsupported)")

def enable_tcp_keepalive(sock, keepidle=60, keepintvl=10, keepcnt=5):
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    except Exception:
        return
    try:
        if hasattr(socket, 'TCP_KEEPIDLE'):
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, keepidle)
        if hasattr(socket, 'TCP_KEEPINTVL'):
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, keepintvl)
        if hasattr(socket, 'TCP_KEEPCNT'):
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, keepcnt)
    except Exception:
        pass

class EnhancedDNSResolver:
    def __init__(self, cache_ttl=300, enable_doh=True, enable_doq=False):
        self.cache_ttl = cache_ttl
        self.enable_doh = enable_doh and DOH_AVAILABLE
        self.enable_doq = enable_doq
        self.doh_servers = [
            'https://cloudflare-dns.com/dns-query',
            'https://dns.google/dns-query'
        ]
        self.doq_servers = ['dns.adguard.com:8853']
        self._cache = {}
        self._lock = threading.Lock()
        
    def resolve_enhanced(self, hostname: str, qtype='A') -> str:
        now = time.time()
        cache_key = f"{hostname}:{qtype}"
        with self._lock:
            if cache_key in self._cache:
                ip, timestamp = self._cache[cache_key]
                if now - timestamp < self.cache_ttl:
                    return ip
                del self._cache[cache_key]
        try:
            ip = self._resolve_standard(hostname)
            if ip:
                with self._lock:
                    self._cache[cache_key] = (ip, now)
                return ip
        except Exception:
            pass
        if self.enable_doh:
            try:
                ip = self._resolve_doh(hostname, qtype)
                if ip:
                    with _stats_lock:
                        _stats['doh_queries'] += 1
                    with self._lock:
                        self._cache[cache_key] = (ip, now)
                    return ip
            except Exception as e:
                logger.debug(f"DoH resolution failed for {hostname}: {e}")
        try:
            ip = self._resolve_standard(hostname)
            if ip:
                with self._lock:
                    self._cache[cache_key] = (ip, now)
                return ip
        except Exception as e:
            logger.error(f"All DNS resolution methods failed for {hostname}: {e}")
            raise
            
    def _resolve_standard(self, hostname: str) -> str:
        infos = socket.getaddrinfo(hostname, None, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM)
        if not infos:
            raise socket.gaierror("no addrinfo")
        for af, socktype, proto, canon, sa in infos:
            if af == socket.AF_INET:
                return sa[0]
        return infos[0][4][0] if infos else None
        
    def _resolve_doh(self, hostname: str, qtype: str) -> str:
        if not DOH_AVAILABLE:
            return None
        for server in self.doh_servers:
            try:
                params = {'name': hostname, 'type': qtype}
                headers = {'Accept': 'application/dns-json'}
                response = requests.get(server, params=params, headers=headers, timeout=5)
                response.raise_for_status()
                data = response.json()
                for answer in data.get('Answer', []):
                    if answer.get('type') == 1:
                        return answer['data']
            except Exception as e:
                logger.debug(f"DoH server {server} failed: {e}")
                continue
        return None

class DNSCache:
    def __init__(self, ttl: int = 300, mode: str = 'memory', cache_dir: str = None):
        self.ttl = ttl
        self.mode = mode
        self._lock = threading.Lock()
        if self.mode == 'memory':
            self._cache = {}
        else:
            if not cache_dir:
                cache_dir = '/data/adb/modules/ZDT-D/cache/'
            os.makedirs(cache_dir, exist_ok=True)
            self.cache_dir = cache_dir
            self.db_file = os.path.join(self.cache_dir, 'dns_cache.json')
            try:
                with open(self.db_file, 'r') as f:
                    self._cache = json.load(f)
            except Exception:
                self._cache = {}

    def _persist(self):
        if self.mode == 'memory':
            return
        tmp = None
        try:
            fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
            with os.fdopen(fd, 'w') as f:
                json.dump(self._cache, f)
            os.replace(tmp, self.db_file)
        except Exception:
            try:
                if tmp and os.path.exists(tmp):
                    os.remove(tmp)
            except Exception:
                pass

    def resolve(self, hostname: str) -> str:
        now = time.time()
        with self._lock:
            item = self._cache.get(hostname)
            if item:
                ip, ts = item
                if now - ts < self.ttl:
                    return ip
                else:
                    self._cache.pop(hostname, None)
                    if self.mode != 'memory':
                        self._persist()
        infos = socket.getaddrinfo(hostname, None, family=socket.AF_UNSPEC, type=socket.SOCK_STREAM)
        if not infos:
            raise socket.gaierror("no addrinfo")
        for af, socktype, proto, canon, sa in infos:
            if af == socket.AF_INET:
                ip = sa[0]
                break
        else:
            ip = infos[0][4][0]
        with self._lock:
            self._cache[hostname] = (ip, now)
            if self.mode != 'memory':
                self._persist()
        return ip

    def invalidate(self, hostname: str):
        with self._lock:
            self._cache.pop(hostname, None)
            if self.mode != 'memory':
                self._persist()

class EnhancedHTTPCache:
    def __init__(self, cache_ttl=300, cache_max_size=1_000_000, mode='memory', cache_dir=None):
        self.cache_ttl = cache_ttl
        self.cache_max_size = cache_max_size
        self.mode = mode
        if self.mode == 'memory':
            self._cache = {}
            self._lock = threading.Lock()
            self.http2_cache = {}
        else:
            if not cache_dir:
                cache_dir = '/data/adb/modules/ZDT-D/cache/'
            os.makedirs(cache_dir, exist_ok=True)
            self.cache_dir = cache_dir
            self._meta_file = os.path.join(self.cache_dir, 'http_meta.json')
            self._lock = threading.Lock()
            try:
                with open(self._meta_file, 'r') as f:
                    self._meta = json.load(f)
            except Exception:
                self._meta = {}
            self.http2_meta_file = os.path.join(self.cache_dir, 'http2_meta.json')
            try:
                with open(self.http2_meta_file, 'r') as f:
                    self.http2_meta = json.load(f)
            except Exception:
                self.http2_meta = {}

    def _key_to_fname(self, host, path):
        key = host + '|' + path
        h = hashlib.sha256(key.encode('utf-8')).hexdigest()
        return os.path.join(self.cache_dir, h)

    def _persist_meta(self):
        try:
            fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
            with os.fdopen(fd, 'w') as f:
                json.dump(self._meta, f)
            os.replace(tmp, self._meta_file)
        except Exception:
            pass

    def _is_static_content_type(self, headers: dict) -> bool:
        ctype = headers.get('content-type', '')
        if not ctype:
            return False
        ctype = ctype.lower()
        if ctype.startswith('text/') or 'javascript' in ctype or 'css' in ctype or ctype.startswith('image/'):
            return True
        return False

    def get(self, host, path):
        now = time.time()
        if self.mode == 'memory':
            key = (host, path)
            with self._lock:
                item = self._cache.get(key)
                if not item:
                    return None
                raw, ts, ttl = item
                if now - ts > ttl:
                    self._cache.pop(key, None)
                    return None
                with _stats_lock:
                    _stats['enhanced_cache_hits'] += 1
                return raw
        else:
            fname = self._key_to_fname(host, path)
            with self._lock:
                meta = self._meta.get(os.path.basename(fname))
                if not meta:
                    return None
                ts = meta.get('ts', 0)
                ttl = meta.get('ttl', self.cache_ttl)
                if now - ts > ttl:
                    try:
                        if os.path.exists(fname):
                            os.remove(fname)
                    except Exception:
                        pass
                    self._meta.pop(os.path.basename(fname), None)
                    self._persist_meta()
                    return None
                try:
                    with open(fname, 'rb') as f:
                        with _stats_lock:
                            _stats['enhanced_cache_hits'] += 1
                        return f.read()
                except Exception:
                    self._meta.pop(os.path.basename(fname), None)
                    self._persist_meta()
                    return None

    def set(self, host, path, raw_response_bytes, headers):
        if len(raw_response_bytes) > self.cache_max_size:
            return
        if not self._is_static_content_type(headers):
            return
        if self.mode == 'memory':
            key = (host, path)
            with self._lock:
                self._cache[key] = (raw_response_bytes, time.time(), self.cache_ttl)
        else:
            fname = self._key_to_fname(host, path)
            bname = os.path.basename(fname)
            with self._lock:
                try:
                    fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
                    with os.fdopen(fd, 'wb') as f:
                        f.write(raw_response_bytes)
                    os.replace(tmp, fname)
                    self._meta[bname] = {'ts': time.time(), 'ttl': self.cache_ttl, 'headers': headers}
                    self._persist_meta()
                except Exception:
                    try:
                        if tmp and os.path.exists(tmp):
                            os.remove(tmp)
                    except Exception:
                        pass

    def get_http2(self, host, path, headers):
        if not HTTP2_AVAILABLE:
            return None
        cache_key = self._generate_http2_key(host, path, headers)
        now = time.time()
        if self.mode == 'memory':
            with self._lock:
                item = self.http2_cache.get(cache_key)
                if not item:
                    return None
                frames, ts, ttl = item
                if now - ts > ttl:
                    self.http2_cache.pop(cache_key, None)
                    return None
                with _stats_lock:
                    _stats['enhanced_cache_hits'] += 1
                return frames
        else:
            fname = os.path.join(self.cache_dir, f"http2_{cache_key}")
            with self._lock:
                meta = self.http2_meta.get(cache_key)
                if not meta:
                    return None
                ts = meta.get('ts', 0)
                ttl = meta.get('ttl', self.cache_ttl)
                if now - ts > ttl:
                    try:
                        if os.path.exists(fname):
                            os.remove(fname)
                    except Exception:
                        pass
                    self.http2_meta.pop(cache_key, None)
                    self._persist_http2_meta()
                    return None
                try:
                    with open(fname, 'rb') as f:
                        with _stats_lock:
                            _stats['enhanced_cache_hits'] += 1
                        return f.read()
                except Exception:
                    self.http2_meta.pop(cache_key, None)
                    self._persist_http2_meta()
                    return None

    def set_http2(self, host, path, headers, response_frames):
        if not HTTP2_AVAILABLE:
            return
        cache_key = self._generate_http2_key(host, path, headers)
        if len(response_frames) > self.cache_max_size:
            return
        if self.mode == 'memory':
            with self._lock:
                self.http2_cache[cache_key] = (response_frames, time.time(), self.cache_ttl)
        else:
            fname = os.path.join(self.cache_dir, f"http2_{cache_key}")
            with self._lock:
                try:
                    fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
                    with os.fdopen(fd, 'wb') as f:
                        f.write(response_frames)
                    os.replace(tmp, fname)
                    self.http2_meta[cache_key] = {
                        'ts': time.time(), 
                        'ttl': self.cache_ttl,
                        'host': host,
                        'path': path
                    }
                    self._persist_http2_meta()
                except Exception:
                    try:
                        if tmp and os.path.exists(tmp):
                            os.remove(tmp)
                    except Exception:
                        pass

    def _generate_http2_key(self, host, path, headers):
        header_str = json.dumps(sorted(headers.items()))
        return hashlib.md5(f"{host}{path}{header_str}".encode()).hexdigest()

    def _persist_http2_meta(self):
        try:
            fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
            with os.fdopen(fd, 'w') as f:
                json.dump(self.http2_meta, f)
            os.replace(tmp, self.http2_meta_file)
        except Exception:
            pass

    def invalidate(self, host, path=None):
        if self.mode == 'memory':
            with self._lock:
                if path is None:
                    for k in list(self._cache.keys()):
                        if k[0] == host:
                            self._cache.pop(k, None)
                    for k in list(self.http2_cache.keys()):
                        if host in k:
                            self.http2_cache.pop(k, None)
                else:
                    self._cache.pop((host, path), None)
                    cache_key_pattern = self._generate_http2_key(host, path, {})
                    for k in list(self.http2_cache.keys()):
                        if cache_key_pattern in k:
                            self.http2_cache.pop(k, None)
        else:
            with self._lock:
                if path is None:
                    remove = []
                    for k in list(self._meta.keys()):
                        remove.append(k)
                    for b in remove:
                        f = os.path.join(self.cache_dir, b)
                        try:
                            if os.path.exists(f):
                                os.remove(f)
                        except Exception:
                            pass
                        self._meta.pop(b, None)
                    self._persist_meta()
                    remove_http2 = []
                    for k, meta in self.http2_meta.items():
                        if meta.get('host') == host:
                            remove_http2.append(k)
                    for k in remove_http2:
                        f = os.path.join(self.cache_dir, f"http2_{k}")
                        try:
                            if os.path.exists(f):
                                os.remove(f)
                        except Exception:
                            pass
                        self.http2_meta.pop(k, None)
                    self._persist_http2_meta()
                else:
                    fname = self._key_to_fname(host, path)
                    bname = os.path.basename(fname)
                    try:
                        if os.path.exists(fname):
                            os.remove(fname)
                    except Exception:
                        pass
                    self._meta.pop(bname, None)
                    self._persist_meta()
                    cache_key = self._generate_http2_key(host, path, {})
                    fname_http2 = os.path.join(self.cache_dir, f"http2_{cache_key}")
                    try:
                        if os.path.exists(fname_http2):
                            os.remove(fname_http2)
                    except Exception:
                        pass
                    self.http2_meta.pop(cache_key, None)
                    self._persist_http2_meta()

class SimpleHTTPCache:
    def __init__(self, cache_ttl=300, cache_max_size=1_000_000, mode='memory', cache_dir=None):
        self.cache_ttl = cache_ttl
        self.cache_max_size = cache_max_size
        self.mode = mode
        if self.mode == 'memory':
            self._cache = {}
            self._lock = threading.Lock()
        else:
            if not cache_dir:
                cache_dir = '/data/adb/modules/ZDT-D/cache/'
            os.makedirs(cache_dir, exist_ok=True)
            self.cache_dir = cache_dir
            self._meta_file = os.path.join(self.cache_dir, 'http_meta.json')
            self._lock = threading.Lock()
            try:
                with open(self._meta_file, 'r') as f:
                    self._meta = json.load(f)
            except Exception:
                self._meta = {}

    def _key_to_fname(self, host, path):
        key = host + '|' + path
        h = hashlib.sha256(key.encode('utf-8')).hexdigest()
        return os.path.join(self.cache_dir, h)

    def _persist_meta(self):
        try:
            fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
            with os.fdopen(fd, 'w') as f:
                json.dump(self._meta, f)
            os.replace(tmp, self._meta_file)
        except Exception:
            pass

    def _is_static_content_type(self, headers: dict) -> bool:
        ctype = headers.get('content-type', '')
        if not ctype:
            return False
        ctype = ctype.lower()
        if ctype.startswith('text/') or 'javascript' in ctype or 'css' in ctype or ctype.startswith('image/'):
            return True
        return False

    def get(self, host, path):
        now = time.time()
        if self.mode == 'memory':
            key = (host, path)
            with self._lock:
                item = self._cache.get(key)
                if not item:
                    return None
                raw, ts, ttl = item
                if now - ts > ttl:
                    self._cache.pop(key, None)
                    return None
                return raw
        else:
            fname = self._key_to_fname(host, path)
            with self._lock:
                meta = self._meta.get(os.path.basename(fname))
                if not meta:
                    return None
                ts = meta.get('ts', 0)
                ttl = meta.get('ttl', self.cache_ttl)
                if now - ts > ttl:
                    try:
                        if os.path.exists(fname):
                            os.remove(fname)
                    except Exception:
                        pass
                    self._meta.pop(os.path.basename(fname), None)
                    self._persist_meta()
                    return None
                try:
                    with open(fname, 'rb') as f:
                        return f.read()
                except Exception:
                    self._meta.pop(os.path.basename(fname), None)
                    self._persist_meta()
                    return None

    def set(self, host, path, raw_response_bytes, headers):
        if len(raw_response_bytes) > self.cache_max_size:
            return
        if not self._is_static_content_type(headers):
            return
        if self.mode == 'memory':
            key = (host, path)
            with self._lock:
                self._cache[key] = (raw_response_bytes, time.time(), self.cache_ttl)
        else:
            fname = self._key_to_fname(host, path)
            bname = os.path.basename(fname)
            with self._lock:
                try:
                    fd, tmp = tempfile.mkstemp(dir=self.cache_dir)
                    with os.fdopen(fd, 'wb') as f:
                        f.write(raw_response_bytes)
                    os.replace(tmp, fname)
                    self._meta[bname] = {'ts': time.time(), 'ttl': self.cache_ttl, 'headers': headers}
                    self._persist_meta()
                except Exception:
                    try:
                        if tmp and os.path.exists(tmp):
                            os.remove(tmp)
                    except Exception:
                        pass

    def invalidate(self, host, path=None):
        if self.mode == 'memory':
            with self._lock:
                if path is None:
                    for k in list(self._cache.keys()):
                        if k[0] == host:
                            self._cache.pop(k, None)
                else:
                    self._cache.pop((host, path), None)
        else:
            with self._lock:
                if path is None:
                    remove = []
                    for k in list(self._meta.keys()):
                        remove.append(k)
                    for b in remove:
                        f = os.path.join(self.cache_dir, b)
                        try:
                            if os.path.exists(f):
                                os.remove(f)
                        except Exception:
                            pass
                        self._meta.pop(b, None)
                    self._persist_meta()
                else:
                    fname = self._key_to_fname(host, path)
                    bname = os.path.basename(fname)
                    try:
                        if os.path.exists(fname):
                            os.remove(fname)
                    except Exception:
                        pass
                    self._meta.pop(bname, None)
                    self._persist_meta()

dns_cache = None
http_cache = None

class RateLimiter:
    def __init__(self, max_per_minute: int = 0):
        self.max_per_minute = max_per_minute
        self.times = deque()
        self.lock = threading.Lock()
    def allow_connection(self) -> bool:
        if not self.max_per_minute or self.max_per_minute <= 0:
            return True
        now = time.time()
        cutoff = now - 60
        with self.lock:
            while self.times and self.times[0] < cutoff:
                self.times.popleft()
            if len(self.times) >= self.max_per_minute:
                return False
            self.times.append(now)
            return True

class HTTP2Handler:
    def __init__(self, client_socket, target_host, target_port, config):
        self.client_socket = client_socket
        self.target_host = target_host
        self.target_port = target_port
        self.config = config
        self.conn = None
        self.remote_socket = None
        
    def handle(self):
        if not HTTP2_AVAILABLE:
            logger.error("HTTP/2 support not available")
            return
        try:
            config = h2.config.H2Configuration(client_side=False)
            self.conn = h2.connection.H2Connection(config=config)
            self.conn.initiate_connection()
            self.client_socket.send(self.conn.data_to_send())
            self.remote_socket = socket.create_connection(
                (self.target_host, self.target_port), 
                timeout=self.config.get('connect_timeout', 10)
            )
            self._process_http2_traffic()
        except Exception as e:
            logger.error(f"HTTP/2 handling failed: {e}")
        finally:
            self.cleanup()
            
    def _process_http2_traffic(self):
        while True:
            try:
                data = self.client_socket.recv(65536)
                if not data:
                    break
                events = self.conn.receive_data(data)
                for event in events:
                    self._handle_http2_event(event)
                if self.conn.data_to_send():
                    self.remote_socket.send(self.conn.data_to_send())
            except socket.timeout:
                break
            except Exception as e:
                logger.error(f"HTTP/2 processing error: {e}")
                break
                
    def _handle_http2_event(self, event):
        if isinstance(event, h2.events.RequestReceived):
            self._handle_http2_request(event)
        elif isinstance(event, h2.events.DataReceived):
            self._handle_http2_data(event)
            
    def _handle_http2_request(self, event):
        logger.debug(f"HTTP/2 request received: {event}")
        pass
        
    def _handle_http2_data(self, event):
        logger.debug(f"HTTP/2 data received: {len(event.data)} bytes")
        pass
        
    def cleanup(self):
        try:
            if self.remote_socket:
                self.remote_socket.close()
        except Exception:
            pass

def check_socks5_health(socks_host, socks_port, socks_user=None, socks_pass=None, timeout=5):
    s = None
    try:
        if _enhanced_dns:
            ip = _enhanced_dns.resolve_enhanced(socks_host)
        else:
            ip = dns_cache.resolve(socks_host)
    except Exception:
        ip = socks_host
    try:
        s = socket.create_connection((ip, socks_port), timeout=timeout)
        methods = [0x00]
        if socks_user and socks_pass:
            methods.append(0x02)
        s.sendall(struct.pack("!BB", 0x05, len(methods)) + bytes(methods))
        data = s.recv(2)
        if len(data) < 2:
            return False
        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            return False
        if method == 0x02:
            if not socks_user or not socks_pass:
                return False
            ub = socks_user.encode('utf-8')
            pb = socks_pass.encode('utf-8')
            if len(ub) > 255 or len(pb) > 255:
                return False
            sub = struct.pack("!B", 0x01) + struct.pack("!B", len(ub)) + ub + struct.pack("!B", len(pb)) + pb
            s.sendall(sub)
            subresp = s.recv(2)
            if len(subresp) < 2:
                return False
            ver2, status = struct.unpack("!BB", subresp)
            if ver2 != 0x01 or status != 0x00:
                return False
        target_host = "1.1.1.1"
        target_port = 53
        req = struct.pack("!BBB", 0x05, 0x01, 0x00) + struct.pack("!B", 0x01) + socket.inet_aton(target_host) + struct.pack("!H", target_port)
        s.sendall(req)
        resp = s.recv(4)
        if len(resp) < 4:
            return False
        ver_r, rep, rsv, atyp_r = struct.unpack("!BBBB", resp)
        if ver_r != 0x05 or rep != 0x00:
            return False
        if atyp_r == 0x01:
            s.recv(4)
        elif atyp_r == 0x03:
            ln = s.recv(1)[0]
            s.recv(ln)
        elif atyp_r == 0x04:
            s.recv(16)
        s.recv(2)
        return True
    except Exception as e:
        logger.debug("SOCKS5 health check failed for %s:%s : %s", socks_host, socks_port, e)
        return False
    finally:
        try:
            if s:
                s.close()
        except Exception:
            pass

def socks5_health_monitor_all(backends, socks_user=None, socks_pass=None):
    global _socks5_available, _socks5_last_check
    while not shutdown_event.is_set():
        try:
            current_time = time.time()
            if current_time - _socks5_last_check >= _socks5_check_interval:
                any_healthy = False
                for host, port in backends:
                    try:
                        healthy = check_socks5_health(host, port, socks_user, socks_pass)
                    except Exception:
                        healthy = False
                    with _backend_lock:
                        _backend_status[(host, port)] = healthy
                    if healthy:
                        any_healthy = True
                with _socks5_lock:
                    old_status = _socks5_available
                    _socks5_available = any_healthy
                    _socks5_last_check = current_time
                    if not old_status and any_healthy:
                        logger.info("At least one SOCKS5 backend recovered")
                        with _stats_lock:
                            _stats['socks5_recovered_count'] += 1
                    elif old_status and not any_healthy:
                        logger.warning("All SOCKS5 backends unavailable, bypass enabled")
                        with _stats_lock:
                            _stats['socks5_bypass_count'] += 1
        except Exception as e:
            logger.exception("SOCKS5 health monitor error: %s", e)
        shutdown_event.wait(1)

def is_socks5_available():
    with _socks5_lock:
        return _socks5_available

def select_socks_backend():
    global _backend_idx
    with _backend_lock:
        n = len(_socks5_backends)
        if n == 0:
            return None, None
        start = _backend_idx % n
        for i in range(n):
            idx = (start + i) % n
            candidate = _socks5_backends[idx]
            if _backend_status.get(candidate, True):
                _backend_idx = (idx + 1) % n
                return candidate
        chosen = _socks5_backends[_backend_idx % n]
        _backend_idx = (_backend_idx + 1) % n
        return chosen

def connection_error_handler(func):
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        try:
            return func(*args, **kwargs)
        except socket.timeout:
            with _stats_lock:
                _stats['connection_timeouts'] = _stats.get('connection_timeouts', 0) + 1
                _stats['errors'] = _stats.get('errors', 0) + 1
            logger.warning("Timeout in %s", func.__name__)
            raise
        except socket.error as e:
            with _stats_lock:
                _stats['errors'] = _stats.get('errors', 0) + 1
            logger.error("Socket error in %s: %s", func.__name__, e)
            raise
        except Exception as e:
            with _stats_lock:
                _stats['errors'] = _stats.get('errors', 0) + 1
            logger.exception("Unexpected error in %s: %s", func.__name__, e)
            raise
    return wrapper

@connection_error_handler
def socks5_connect_via(sock: socket.socket, target_host, target_port, username=None, password=None, timeout=None):
    old_timeout = sock.gettimeout()
    try:
        sock.settimeout(timeout)
        methods = [0x00]
        if username is not None and password is not None:
            methods.append(0x02)
        sock.sendall(struct.pack("!BB", 0x05, len(methods)) + bytes(methods))
        data = sock.recv(2)
        if len(data) < 2:
            raise RuntimeError("short METHODS reply")
        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            raise RuntimeError(f"bad version {ver}")
        if method == 0xFF:
            raise RuntimeError("no acceptable methods")
        if method == 0x02:
            if username is None or password is None:
                raise RuntimeError("username/password required")
            ub = username.encode('utf-8')
            pb = password.encode('utf-8')
            if len(ub) > 255 or len(pb) > 255:
                raise RuntimeError("username/password too long")
            subreq = struct.pack("!B", 0x01) + struct.pack("!B", len(ub)) + ub + struct.pack("!B", len(pb)) + pb
            sock.sendall(subreq)
            subresp = sock.recv(2)
            if len(subresp) < 2:
                raise RuntimeError("short auth reply")
            ver2, status = struct.unpack("!BB", subresp)
            if ver2 != 0x01 or status != 0x00:
                with _stats_lock:
                    _stats['auth_failures'] = _stats.get('auth_failures', 0) + 1
                raise RuntimeError("auth failed")
        try:
            ipv4 = socket.inet_aton(target_host)
            atyp = 0x01
            addr_part = ipv4
        except Exception:
            atyp = 0x03
            host_b = target_host.encode('idna')
            if len(host_b) > 255:
                raise RuntimeError("hostname too long")
            addr_part = struct.pack("!B", len(host_b)) + host_b
        port_part = struct.pack("!H", int(target_port))
        req = struct.pack("!BBB", 0x05, 0x01, 0x00) + struct.pack("!B", atyp) + addr_part + port_part
        sock.sendall(req)
        resp = sock.recv(4)
        if len(resp) < 4:
            raise RuntimeError("short CONNECT reply")
        ver_r, rep, rsv, atyp_r = struct.unpack("!BBBB", resp)
        if ver_r != 0x05:
            raise RuntimeError(f"bad version {ver_r}")
        if rep != 0x00:
            try:
                remaining = sock.recv(4096)
            except Exception:
                remaining = b''
            raise RuntimeError(f"connect failed rep=0x{rep:02x} extra={remaining!r}")
        if atyp_r == 0x01:
            addr_raw = sock.recv(4)
            if len(addr_raw) < 4:
                raise RuntimeError("short BND.ADDR IPv4")
            bnd_addr = socket.inet_ntoa(addr_raw)
        elif atyp_r == 0x03:
            ln_b = sock.recv(1)
            if len(ln_b) < 1:
                raise RuntimeError("short BND.ADDR len")
            ln = struct.unpack("!B", ln_b)[0]
            addr_raw = sock.recv(ln)
            if len(addr_raw) < ln:
                raise RuntimeError("short BND.ADDR domain")
            bnd_addr = addr_raw.decode('idna')
        elif atyp_r == 0x04:
            addr_raw = sock.recv(16)
            if len(addr_raw) < 16:
                raise RuntimeError("short BND.ADDR IPv6")
            bnd_addr = socket.inet_ntop(socket.AF_INET6, addr_raw)
        else:
            raise RuntimeError("unknown ATYP in reply")
        port_raw = sock.recv(2)
        if len(port_raw) < 2:
            raise RuntimeError("short BND.PORT")
        bnd_port = struct.unpack("!H", port_raw)[0]
        return True
    finally:
        try:
            sock.settimeout(None)
        except Exception:
            pass
        try:
            sock.setblocking(True)
        except Exception:
            pass
        try:
            sock.settimeout(old_timeout)
        except Exception:
            pass

def direct_connect(target_host, target_port, timeout=None):
    return socket.create_connection((target_host, target_port), timeout=timeout)

def build_socks5_udp_packet(dst_host: str, dst_port: int, payload: bytes) -> bytes:
    rsv = b'\x00\x00'
    frag = b'\x00'
    try:
        ip4 = socket.inet_aton(dst_host)
        atyp = b'\x01'
        addr = ip4
    except Exception:
        hb = dst_host.encode('idna')
        if len(hb) > 255:
            raise RuntimeError("domain too long")
        atyp = b'\x03'
        addr = struct.pack('!B', len(hb)) + hb
    port = struct.pack('!H', int(dst_port))
    return rsv + frag + atyp + addr + port + payload

def parse_socks5_udp_packet(pkt: bytes):
    if len(pkt) < 4:
        raise RuntimeError("short UDP packet")
    atyp = pkt[3]
    idx = 4
    if atyp == 0x01:
        if len(pkt) < idx + 4 + 2:
            raise RuntimeError("short IPv4 in UDP packet")
        addr = socket.inet_ntoa(pkt[idx:idx+4])
        idx += 4
    elif atyp == 0x03:
        if len(pkt) < idx + 1:
            raise RuntimeError("short domain len")
        ln = pkt[idx]
        idx += 1
        if len(pkt) < idx + ln + 2:
            raise RuntimeError("short domain in UDP packet")
        addr = pkt[idx:idx+ln].decode('idna', errors='replace')
        idx += ln
    elif atyp == 0x04:
        if len(pkt) < idx + 16 + 2:
            raise RuntimeError("short IPv6 in UDP packet")
        addr = socket.inet_ntop(socket.AF_INET6, pkt[idx:idx+16])
        idx += 16
    else:
        raise RuntimeError(f"unknown ATYP {atyp}")
    port = struct.unpack('!H', pkt[idx:idx+2])[0]
    idx += 2
    data = pkt[idx:]
    return addr, port, data

@connection_error_handler
def socks5_udp_associate(tcp_sock: socket.socket, timeout: float = None):
    old = tcp_sock.gettimeout()
    try:
        tcp_sock.settimeout(timeout)
        req = struct.pack('!BBBB', 0x05, 0x03, 0x00, 0x01) + socket.inet_aton('0.0.0.0') + struct.pack('!H', 0)
        tcp_sock.sendall(req)
        hdr = tcp_sock.recv(4)
        if len(hdr) < 4:
            raise RuntimeError("short reply header")
        ver, rep, rsv, atyp = struct.unpack('!BBBB', hdr)
        if ver != 0x05:
            raise RuntimeError(f"bad version {ver}")
        if rep != 0x00:
            try:
                extra = tcp_sock.recv(4096)
            except Exception:
                extra = b''
            raise RuntimeError(f"udp assoc rep=0x{rep:02x} extra={extra!r}")
        if atyp == 0x01:
            addr_raw = tcp_sock.recv(4)
            if len(addr_raw) < 4:
                raise RuntimeError("short BND.ADDR IPv4")
            bnd_addr = socket.inet_ntoa(addr_raw)
        elif atyp == 0x03:
            ln_b = tcp_sock.recv(1)
            if len(ln_b) < 1:
                raise RuntimeError("short BND.ADDR len")
            ln = struct.unpack("!B", ln_b)[0]
            addr_raw = tcp_sock.recv(ln)
            if len(addr_raw) < ln:
                raise RuntimeError("short BND.ADDR domain")
            bnd_addr = addr_raw.decode('idna')
        elif atyp == 0x04:
            addr_raw = tcp_sock.recv(16)
            if len(addr_raw) < 16:
                raise RuntimeError("short BND.ADDR IPv6")
            bnd_addr = socket.inet_ntop(socket.AF_INET6, addr_raw)
        else:
            raise RuntimeError("unknown ATYP in UDP ASSOC reply")
        port_raw = tcp_sock.recv(2)
        if len(port_raw) < 2:
            raise RuntimeError("short BND.PORT")
        bnd_port = struct.unpack("!H", port_raw)[0]
        return bnd_addr, bnd_port
    finally:
        try:
            tcp_sock.settimeout(old)
        except Exception:
            pass

class UDPSession:
    def __init__(self, client_addr, socks_host, socks_port, socks_user, socks_pass, timeout, fixed_target):
        self.client_addr = client_addr
        self.socks_host = socks_host
        self.socks_port = socks_port
        self.socks_user = socks_user
        self.socks_pass = socks_pass
        self.timeout = timeout
        self.fixed_target = fixed_target
        self.tcp = None
        self.udp = None
        self.relay_addr = None
        self.last_activity = time.time()
        self.alive = False
        self.lock = threading.Lock()
        self.use_direct = False
    @connection_error_handler
    def start(self):
        if self.socks_host is None or self.socks_port is None:
            chosen = select_socks_backend()
            if chosen == (None, None):
                self.use_direct = True
            else:
                self.socks_host, self.socks_port = chosen
        if not is_socks5_available():
            self.use_direct = True
            self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp.bind(("0.0.0.0", 0))
            self.udp.setblocking(False)
            self.alive = True
            logger.debug("[%s] Created direct UDP session", self.client_addr)
            return
        try:
            if _enhanced_dns:
                socks_ip = _enhanced_dns.resolve_enhanced(self.socks_host)
            else:
                socks_ip = dns_cache.resolve(self.socks_host)
        except Exception:
            with _stats_lock:
                _stats['dns_failures'] = _stats.get('dns_failures', 0) + 1
            raise
        self.tcp = socket.create_connection((socks_ip, self.socks_port), timeout=self.timeout)
        methods = [0x00]
        if self.socks_user is not None and self.socks_pass is not None:
            methods.append(0x02)
        self.tcp.sendall(struct.pack("!BB", 0x05, len(methods)) + bytes(methods))
        data = self.tcp.recv(2)
        if len(data) < 2:
            raise RuntimeError("short greeting reply")
        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            raise RuntimeError("bad version in greeting")
        if method == 0x02:
            if self.socks_user is None or self.socks_pass is None:
                with _stats_lock:
                    _stats['auth_failures'] = _stats.get('auth_failures', 0) + 1
                raise RuntimeError("username required")
            ub = self.socks_user.encode('utf-8')
            pb = self.socks_pass.encode('utf-8')
            subreq = struct.pack("!B", 0x01) + struct.pack("!B", len(ub)) + ub + struct.pack("!B", len(pb)) + pb
            self.tcp.sendall(subreq)
            subresp = self.tcp.recv(2)
            if len(subresp) < 2:
                raise RuntimeError("short auth reply")
            ver2, status = struct.unpack("!BB", subresp)
            if ver2 != 0x01 or status != 0x00:
                with _stats_lock:
                    _stats['auth_failures'] = _stats.get('auth_failures', 0) + 1
                raise RuntimeError("auth failed")
        bnd_addr, bnd_port = socks5_udp_associate(self.tcp, timeout=self.timeout)
        self.relay_addr = (bnd_addr, bnd_port)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(("0.0.0.0", 0))
        self.udp.setblocking(False)
        self.alive = True
        logger.debug("[%s] Created UDP session -> relay %s:%d", self.client_addr, bnd_addr, bnd_port)

    def send_from_client(self, dst_host, dst_port, data):
        proto = classify_protocol(dst_port)
        with _stats_lock:
            if proto == 'http':
                _stats['bytes_http_c2r'] += len(data)
            elif proto == 'https':
                _stats['bytes_https_c2r'] += len(data)
            elif proto == 'dns':
                _stats['bytes_dns_c2r'] += len(data)
            else:
                _stats['bytes_other_c2r'] += len(data)

        if self.use_direct:
            with self.lock:
                if not self.alive:
                    raise RuntimeError("not alive")
                self.udp.sendto(data, (dst_host, dst_port))
                self.last_activity = time.time()
                with _stats_lock:
                    _stats['bytes_udp_c2r'] += len(data)
        else:
            pkt = build_socks5_udp_packet(dst_host, dst_port, data)
            with self.lock:
                if not self.alive:
                    raise RuntimeError("not alive")
                self.udp.sendto(pkt, self.relay_addr)
                self.last_activity = time.time()
                with _stats_lock:
                    _stats['bytes_udp_c2r'] += len(data)

    def recv_from_relay(self):
        try:
            pkt, addr = self.udp.recvfrom(65535)
        except BlockingIOError:
            return None
        except OSError as e:
            logger.debug("udp recvfrom error: %s", e)
            return None
        if self.use_direct:
            src_host, src_port = addr
            payload = pkt
            self.last_activity = time.time()
            with _stats_lock:
                _stats['bytes_udp_r2c'] += len(payload)
            return src_host, src_port, payload
        else:
            try:
                src_host, src_port, payload = parse_socks5_udp_packet(pkt)
            except Exception as e:
                logger.debug("failed to parse socks5 udp pkt: %s", e)
                return None
            self.last_activity = time.time()
            with _stats_lock:
                _stats['bytes_udp_r2c'] += len(payload)
            return src_host, src_port, payload

    def close(self):
        self.alive = False
        try:
            if self.tcp:
                self.tcp.close()
        except Exception:
            pass
        try:
            if self.udp:
                self.udp.close()
        except Exception:
            pass

def classify_protocol(port: int):
    try:
        p = int(port)
    except Exception:
        return 'other'
    if p == 80:
        return 'http'
    if p == 443:
        return 'https'
    if p == 53:
        return 'dns'
    return 'other'

def forward_loop(a: socket.socket, b: socket.socket, client_addr, cfg, stats, conn_id: str, protocol=None):
    buffer_size = cfg.get('buffer_size', 65536)
    idle_timeout = cfg.get('idle_timeout', 300)
    last_activity = time.time()
    try:
        a.setblocking(True)
        b.setblocking(True)
    except Exception:
        pass
    while True:
        timeout = 1.0
        try:
            r, _, _ = select.select([a, b], [], [], timeout)
        except OSError as e:
            if e.errno == errno.EINTR:
                continue
            logger.error("select error: %s", e)
            break
        if not r:
            if idle_timeout and (time.time() - last_activity) > idle_timeout:
                logger.info("[%s] idle timeout (%ds), closing", client_addr, idle_timeout)
                break
            continue
        for s in r:
            try:
                data = s.recv(buffer_size)
            except OSError as e:
                if e.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                    continue
                logger.debug("recv error on fd %s: %s", getattr(s, 'fileno', lambda: '?')(), e)
                return
            except Exception as e:
                logger.debug("recv exception on fd: %s", e)
                return
            if not data:
                logger.debug("[%s] peer closed fd", client_addr)
                return
            last_activity = time.time()
            dst = b if s is a else a
            direction = 'client->remote' if s is a else 'remote->client'
            logger.debug("[%s] chunk %d bytes %s", client_addr, len(data), direction)
            try:
                dst.sendall(data)
            except BrokenPipeError:
                logger.debug("[%s] BrokenPipe writing", client_addr)
                return
            except Exception as e:
                logger.debug("[%s] send error: %s", client_addr, e)
                return
            with _stats_lock:
                if s is a:
                    _stats['total_bytes_client_to_remote'] += len(data)
                    stats['bytes_client_to_remote'] += len(data)
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['bytes_c2r'] += len(data)
                    if protocol == 'http':
                        _stats['bytes_http_c2r'] += len(data)
                    elif protocol == 'https':
                        _stats['bytes_https_c2r'] += len(data)
                    elif protocol == 'dns':
                        _stats['bytes_dns_c2r'] += len(data)
                    else:
                        _stats['bytes_other_c2r'] += len(data)
                else:
                    _stats['total_bytes_remote_to_client'] += len(data)
                    stats['bytes_remote_to_client'] += len(data)
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['bytes_r2c'] += len(data)
                    if protocol == 'http':
                        _stats['bytes_http_r2c'] += len(data)
                    elif protocol == 'https':
                        _stats['bytes_https_r2c'] += len(data)
                    elif protocol == 'dns':
                        _stats['bytes_dns_r2c'] += len(data)
                    else:
                        _stats['bytes_other_r2c'] += len(data)

def graceful_close(sock: Optional[socket.socket]):
    if not sock:
        return
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    try:
        sock.close()
    except Exception:
        pass

def parse_http_request_headers(raw: bytes):
    try:
        s = raw.decode('iso-8859-1')
    except Exception:
        s = raw.decode('utf-8', errors='replace')
    parts = s.split('\r\n\r\n', 1)[0].split('\r\n')
    request_line = parts[0]
    method, path, version = request_line.split(' ', 2)
    headers = {}
    for line in parts[1:]:
        if not line:
            continue
        if ':' in line:
            k, v = line.split(':', 1)
            headers[k.strip().lower()] = v.strip()
    return method, path, version, headers

def parse_http_response_headers(raw: bytes):
    try:
        s = raw.decode('iso-8859-1')
    except Exception:
        s = raw.decode('utf-8', errors='replace')
    if '\r\n\r\n' not in s:
        return None, {}, 0
    header_part, _ = s.split('\r\n\r\n', 1)
    lines = header_part.split('\r\n')
    status_line = lines[0]
    try:
        version, status_code, _ = status_line.split(' ', 2)
        status_code = int(status_code)
    except Exception:
        status_code = 0
    headers = {}
    for line in lines[1:]:
        if ':' in line:
            k, v = line.split(':', 1)
            headers[k.strip().lower()] = v.strip()
    header_bytes_len = len((header_part + '\r\n\r\n').encode('iso-8859-1'))
    return status_code, headers, header_bytes_len

def http_cache_aware_exchange(client_sock, socks_sock, initial_request_bytes, host, path, cfg, conn_id, stats):
    try:
        method, path_parsed, version, req_headers = parse_http_request_headers(initial_request_bytes)
    except Exception:
        socks_sock.sendall(initial_request_bytes)
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
        return
    if method.upper() != 'GET':
        socks_sock.sendall(initial_request_bytes)
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
        return
    host_header = req_headers.get('host')
    if not host_header:
        socks_sock.sendall(initial_request_bytes)
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
        return
    cache_key_host = host_header
    cache_key_path = path_parsed
    cached = http_cache.get(cache_key_host, cache_key_path)
    if cached:
        try:
            client_sock.sendall(cached)
            with _stats_lock:
                _stats['connections_http'] += 1
                _stats['bytes_http_r2c'] += len(cached)
            logger.debug("[%s] HTTP cache HIT %s%s (%d bytes)", client_sock.getpeername(), cache_key_host, cache_key_path, len(cached))
        except Exception as e:
            logger.debug("Failed to send cached response: %s", e)
        return
    try:
        socks_sock.sendall(initial_request_bytes)
    except Exception:
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
        return
    resp_buf = bytearray()
    socks_sock.settimeout(cfg.get('connect_timeout', 10))
    try:
        while b'\r\n\r\n' not in resp_buf and len(resp_buf) < 65536:
            chunk = socks_sock.recv(4096)
            if not chunk:
                break
            resp_buf.extend(chunk)
    except socket.timeout:
        try:
            client_sock.sendall(bytes(resp_buf))
        except Exception:
            pass
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
        return
    except Exception:
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
        return
    status_code, resp_headers, header_len = parse_http_response_headers(bytes(resp_buf))
    body = bytes(resp_buf[header_len:])
    total_response = bytes(resp_buf)
    content_length = None
    if 'content-length' in resp_headers:
        try:
            content_length = int(resp_headers['content-length'])
        except Exception:
            content_length = None
    if content_length is not None:
        to_read = content_length - len(body)
        try:
            while to_read > 0:
                chunk = socks_sock.recv(min(65536, to_read))
                if not chunk:
                    break
                body += chunk
                total_response += chunk
                to_read -= len(chunk)
        except Exception:
            pass
    else:
        socks_sock.settimeout(0.5)
        try:
            while True:
                chunk = socks_sock.recv(65536)
                if not chunk:
                    break
                body += chunk
                total_response += chunk
                if len(total_response) > http_cache.cache_max_size:
                    break
        except Exception:
            pass
    try:
        client_sock.sendall(total_response)
    except Exception:
        pass
    if status_code == 200 and total_response and resp_headers:
        http_cache.set(cache_key_host, cache_key_path, total_response, resp_headers)
        logger.debug("Cached HTTP GET %s%s (%d bytes)", cache_key_host, cache_key_path, len(total_response))
    with _stats_lock:
        _stats['connections_http'] += 1
        _stats['bytes_http_r2c'] += len(total_response)
        _stats['bytes_http_c2r'] += len(initial_request_bytes)
    try:
        forward_loop(client_sock, socks_sock, client_sock.getpeername(), cfg, stats, conn_id, protocol='http')
    except Exception:
        pass

def handle_client_enhanced(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg):
    stats = {'bytes_client_to_remote': 0, 'bytes_remote_to_client': 0, 'start_time': time.time()}
    conn_id = f"{client_addr[0]}:{client_addr[1]}_{threading.get_ident()}"
    if cfg.get('enable_http2', False) and HTTP2_AVAILABLE:
        try:
            client_sock.settimeout(0.1)
            prefix = client_sock.recv(24, socket.MSG_PEEK)
            client_sock.settimeout(None)
            if prefix.startswith(b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n'):
                logger.info("[%s] Detected HTTP/2 connection", client_addr)
                with _stats_lock:
                    _stats['http2_connections'] += 1
                handler = HTTP2Handler(client_sock, fixed_target[0] if fixed_target else None, 
                                     fixed_target[1] if fixed_target else None, cfg)
                handler.handle()
                return
        except socket.timeout:
            pass
        except Exception:
            pass
    handle_client(client_sock, client_addr, socks_host, socks_port, 
                 socks_user, socks_pass, fixed_target, sem, cfg)

def handle_client(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg):
    stats = {'bytes_client_to_remote': 0, 'bytes_remote_to_client': 0, 'start_time': time.time()}
    conn_id = f"{client_addr[0]}:{client_addr[1]}_{threading.get_ident()}"
    with sem:
        with _stats_lock:
            _stats['active_connections'] += 1
            _stats['total_connections'] += 1
        with _conns_lock:
            _conns[conn_id] = {'client': f"{client_addr[0]}:{client_addr[1]}", 'start_time': stats['start_time'], 'bytes_c2r': 0, 'bytes_r2c': 0}
        socks_sock = None
        use_direct = False
        try:
            if fixed_target is not None:
                target_host, target_port = fixed_target
            else:
                try:
                    target_host, target_port = get_original_dst(client_sock)
                except Exception as e:
                    logger.warning("[%s] SO_ORIGINAL_DST failed: %s", client_addr, e)
                    with _stats_lock:
                        _stats['errors'] = _stats.get('errors', 0) + 1
                    graceful_close(client_sock)
                    return
            logger.info("[%s] -> target %s:%s", client_addr, target_host, target_port)
            try:
                client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except Exception:
                pass
            enable_tcp_keepalive(client_sock, cfg.get('keepidle'), cfg.get('keepintvl'), cfg.get('keepcnt'))
            proto = classify_protocol(target_port)
            with _stats_lock:
                if proto == 'http':
                    _stats['connections_http'] += 1
                elif proto == 'https':
                    _stats['connections_https'] += 1
                elif proto == 'dns':
                    _stats['connections_dns'] += 1
                else:
                    _stats['connections_other'] += 1
            if not is_socks5_available():
                use_direct = True
                with _stats_lock:
                    _stats['direct_connections'] += 1
                logger.info("[%s] Using direct connection (SOCKS5 unavailable)", client_addr)
            initial_request_bytes = None
            if proto == 'http':
                try:
                    client_sock.settimeout(0.5)
                    rv = bytearray()
                    while b'\r\n\r\n' not in rv and len(rv) < 8192:
                        chunk = client_sock.recv(4096)
                        if not chunk:
                            break
                        rv.extend(chunk)
                        if b'\r\n\r\n' in rv:
                            break
                    initial_request_bytes = bytes(rv)
                except socket.timeout:
                    initial_request_bytes = None
                except BlockingIOError:
                    initial_request_bytes = None
                except Exception:
                    initial_request_bytes = None
                finally:
                    try:
                        client_sock.settimeout(None)
                    except Exception:
                        pass
            if use_direct:
                max_retries = cfg.get('connect_retries', 3)
                backoff_base = cfg.get('retry_backoff', 0.5)
                last_err = None
                for attempt in range(1, max_retries + 1):
                    try:
                        socks_sock = direct_connect(target_host, target_port, timeout=cfg.get('connect_timeout'))
                        break
                    except Exception as e:
                        last_err = e
                        logger.warning("[%s] direct connect attempt %d/%d failed: %s", client_addr, attempt, max_retries, e)
                        if attempt < max_retries:
                            time.sleep(backoff_base * (2 ** (attempt - 1)))
                else:
                    logger.error("[%s] direct connect failed after %d attempts: %s", client_addr, max_retries, last_err)
                    with _stats_lock:
                        _stats['errors'] = _stats.get('errors', 0) + 1
                    graceful_close(client_sock)
                    return
            else:
                if socks_host is None or socks_port is None:
                    chosen = select_socks_backend()
                    if chosen == (None, None):
                        logger.error("[%s] No SOCKS backends configured", client_addr)
                        with _stats_lock:
                            _stats['errors'] = _stats.get('errors', 0) + 1
                        graceful_close(client_sock)
                        return
                    socks_host, socks_port = chosen
                max_retries = cfg.get('connect_retries', 3)
                backoff_base = cfg.get('retry_backoff', 0.5)
                last_err = None
                for attempt in range(1, max_retries + 1):
                    try:
                        if _enhanced_dns:
                            socks_ip = _enhanced_dns.resolve_enhanced(socks_host)
                        else:
                            socks_ip = dns_cache.resolve(socks_host)
                        socks_sock = socket.create_connection((socks_ip, socks_port), timeout=cfg.get('connect_timeout'))
                        break
                    except Exception as e:
                        last_err = e
                        logger.warning("[%s] attempt %d/%d to connect to SOCKS %s:%d failed: %s", client_addr, attempt, max_retries, socks_host, socks_port, e)
                        if attempt < max_retries:
                            time.sleep(backoff_base * (2 ** (attempt - 1)))
                else:
                    logger.error("[%s] unable to connect to upstream SOCKS after %d attempts: %s", client_addr, max_retries, last_err)
                    with _stats_lock:
                        _stats['errors'] = _stats.get('errors', 0) + 1
                    graceful_close(client_sock)
                    return
                try:
                    socks5_connect_via(socks_sock, target_host, target_port, username=socks_user, password=socks_pass, timeout=cfg.get('connect_timeout'))
                except Exception as e:
                    logger.error("[%s] SOCKS5 handshake/CONNECT failed: %s", client_addr, e)
                    with _stats_lock:
                        _stats['errors'] = _stats.get('errors', 0) + 1
                    graceful_close(socks_sock)
                    graceful_close(client_sock)
                    return
            try:
                if socks_sock:
                    socks_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except Exception:
                pass
            if socks_sock:
                enable_tcp_keepalive(socks_sock, cfg.get('keepidle'), cfg.get('keepintvl'), cfg.get('keepcnt'))
            if proto == 'http' and initial_request_bytes:
                try:
                    http_cache_aware_exchange(client_sock, socks_sock, initial_request_bytes, target_host, initial_request_bytes.decode(errors='replace').split(' ')[1], cfg, conn_id, stats)
                except Exception as e:
                    logger.exception("HTTP cache-aware exchange failed: %s", e)
                    try:
                        forward_loop(client_sock, socks_sock, client_addr, cfg, stats, conn_id, protocol=proto)
                    except Exception:
                        pass
            else:
                forward_loop(client_sock, socks_sock, client_addr, cfg, stats, conn_id, protocol=proto)
        except Exception as e:
            logger.exception("[%s] Unexpected error: %s", client_addr, e)
            with _stats_lock:
                _stats['errors'] = _stats.get('errors', 0) + 1
        finally:
            duration = time.time() - stats['start_time']
            graceful_close(client_sock)
            if socks_sock:
                graceful_close(socks_sock)
            with _stats_lock:
                _stats['active_connections'] -= 1
            with _conns_lock:
                _conns.pop(conn_id, None)
            connection_type = "direct" if use_direct else "SOCKS5"
            logger.info("[%s] closed (%s) — duration: %.2fs, c->r: %d bytes, r->c: %d bytes", client_addr, connection_type, duration, stats['bytes_client_to_remote'], stats['bytes_remote_to_client'])

def udp_server_loop(listen_addr: str, listen_port: int, socks_host: str, socks_port: int, socks_user: str, socks_pass: str, fixed_target, cfg):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((listen_addr, listen_port))
    sock.setblocking(False)
    logger.info("UDP listening on %s:%d", listen_addr, listen_port)
    sessions = {}
    sessions_lock = threading.Lock()
    session_timeout = cfg.get('udp_session_timeout', 60)
    def cleaner():
        while not shutdown_event.is_set():
            time.sleep(max(1, session_timeout // 2))
            now = time.time()
            to_remove = []
            with sessions_lock:
                for k, s in list(sessions.items()):
                    if (now - s.last_activity) > session_timeout:
                        to_remove.append(k)
                for k in to_remove:
                    s = sessions.pop(k)
                    try:
                        s.close()
                    except Exception:
                        pass
                    with _stats_lock:
                        _stats['udp_sessions'] -= 1
                        _stats['active_udp_sessions'] = max(0, _stats.get('active_udp_sessions', 0) - 1)
                    logger.info("UDP session %s timed out and closed", k)
    tclean = threading.Thread(target=cleaner, daemon=True)
    tclean.start()
    logger.info("UDP server ready (session timeout %ds)", session_timeout)
    while not shutdown_event.is_set():
        try:
            data, client_addr = sock.recvfrom(cfg.get('udp_buffer_size', 65536))
        except BlockingIOError:
            data = None
        except OSError as e:
            if e.errno in (errno.EAGAIN, errno.EINTR):
                data = None
            else:
                logger.exception("UDP recv error")
                break
        if data:
            if fixed_target is not None:
                dst_host, dst_port = fixed_target
            else:
                try:
                    od = sock.getsockopt(socket.SOL_IP, SO_ORIGINAL_DST, 128)
                    dst_host, dst_port = parse_original_dst(od)
                except Exception as e:
                    logger.warning("Cannot determine original dst for UDP packet from %s: %s — dropping.", client_addr, e)
                    with _stats_lock:
                        _stats['errors'] = _stats.get('errors', 0) + 1
                    continue
            proto = classify_protocol(dst_port)
            with _stats_lock:
                if proto == 'http':
                    _stats['bytes_http_c2r'] += len(data)
                elif proto == 'https':
                    _stats['bytes_https_c2r'] += len(data)
                elif proto == 'dns':
                    _stats['bytes_dns_c2r'] += len(data)
                else:
                    _stats['bytes_other_c2r'] += len(data)
            with sessions_lock:
                s = sessions.get(client_addr)
                if s is None or not s.alive:
                    try:
                        s = UDPSession(client_addr, socks_host, socks_port, socks_user, socks_pass, timeout=cfg.get('connect_timeout', 10), fixed_target=fixed_target)
                        s.start()
                        sessions[client_addr] = s
                        with _stats_lock:
                            _stats['udp_sessions'] += 1
                            _stats['active_udp_sessions'] = _stats.get('active_udp_sessions', 0) + 1
                        session_type = "direct" if s.use_direct else "SOCKS5"
                        logger.info("Created UDP session for %s -> %s (%s)", client_addr, f"{dst_host}:{dst_port}", session_type)
                    except Exception as e:
                        logger.error("Failed to create UDP session for %s: %s", client_addr, e)
                        with _stats_lock:
                            _stats['errors'] = _stats.get('errors', 0) + 1
                        continue
            try:
                s.send_from_client(dst_host, dst_port, data)
                logger.debug("UDP %s -> %s:%d (%d bytes) via %s", client_addr, dst_host, dst_port, len(data), "direct" if s.use_direct else "SOCKS5")
            except Exception as e:
                logger.debug("Failed to send from client %s via session: %s", client_addr, e)
        with sessions_lock:
            for client_k, s in list(sessions.items()):
                if not s.alive:
                    continue
                res = s.recv_from_relay()
                if res:
                    src_host, src_port, payload = res
                    try:
                        sock.sendto(payload, client_k)
                        logger.debug("UDP reply to %s from %s:%d (%d bytes) via %s", client_k, src_host, src_port, len(payload), "direct" if s.use_direct else "SOCKS5")
                    except Exception as e:
                        logger.debug("Failed sendto client %s: %s", client_k, e)
    logger.info("UDP server shutting down, closing sessions...")
    with sessions_lock:
        for s in sessions.values():
            try:
                s.close()
            except Exception:
                pass
    try:
        sock.close()
    except Exception:
        pass
    logger.info("UDP server stopped.")

def stats_reporter(interval=30):
    while not shutdown_event.wait(interval):
        try:
            with _stats_lock:
                ac = _stats['active_connections']
                tc = _stats['total_connections']
                bc2r = _stats['total_bytes_client_to_remote']
                br2c = _stats['total_bytes_remote_to_client']
                us = _stats['udp_sessions']
                u_c2r = _stats['bytes_udp_c2r']
                u_r2c = _stats['bytes_udp_r2c']
                errs = _stats.get('errors', 0)
                to = _stats.get('connection_timeouts', 0)
                bypass = _stats.get('socks5_bypass_count', 0)
                recovered = _stats.get('socks5_recovered_count', 0)
                direct = _stats.get('direct_connections', 0)
                http2_conns = _stats.get('http2_connections', 0)
                doh_queries = _stats.get('doh_queries', 0)
                cache_hits = _stats.get('enhanced_cache_hits', 0)
            socks5_status = "available" if is_socks5_available() else "unavailable"
            logger.info("STATS active=%s total_conn=%s tcp_bytes_c2r=%s tcp_bytes_r2c=%s udp_sessions=%s udp_c2r=%s udp_r2c=%s errors=%s timeouts=%s socks5_status=%s bypass_count=%s recovered_count=%s direct_connections=%s http2_connections=%s doh_queries=%s enhanced_cache_hits=%s", 
                       ac, tc, bc2r, br2c, us, u_c2r, u_r2c, errs, to, socks5_status, bypass, recovered, direct, http2_conns, doh_queries, cache_hits)
        except Exception:
            logger.exception("stats_reporter failed")

def export_prometheus_metrics():
    with _stats_lock:
        stats_snapshot = dict(_stats)
    metrics = []
    metrics.append('# HELP t2s_uptime_seconds Time since process start')
    metrics.append('# TYPE t2s_uptime_seconds gauge')
    metrics.append(f"t2s_uptime_seconds {time.time() - START_TIME}")
    metrics.append('# HELP t2s_socks5_available SOCKS5 server availability')
    metrics.append('# TYPE t2s_socks5_available gauge')
    metrics.append(f"t2s_socks5_available {1 if is_socks5_available() else 0}")
    for key, value in stats_snapshot.items():
        if isinstance(value, (int, float)):
            name = "t2s_" + key
            metrics.append(f"# HELP {name} auto-generated")
            metrics.append(f"# TYPE {name} gauge")
            metrics.append(f"{name} {value}")
    return "\n".join(metrics)

class EnhancedStatsHandler(BaseHTTPRequestHandler):
    server_version = "t2s-enhanced-stats/1.0"
    
    def _get_socks5_backends_status(self):
        status_info = []
        with _backend_lock:
            for backend in _socks5_backends:
                host, port = backend
                healthy = _backend_status.get(backend, False)
                last_check = _socks5_last_check
                status_info.append({
                    'host': host,
                    'port': port,
                    'healthy': healthy,
                    'last_check': last_check,
                    'response_time': self._get_backend_response_time(backend)
                })
        return status_info
        
    def _get_backend_response_time(self, backend):
        return "N/A"
        
    def _write_json(self, obj, code=200):
        b = json.dumps(obj, default=str).encode('utf-8')
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)
        
    def do_GET(self):
        web_user = os.getenv('WEB_UI_USER')
        web_pass = os.getenv('WEB_UI_PASS')
        if web_user and web_pass:
            auth = self.headers.get('Authorization')
            if not auth or not auth.startswith('Basic '):
                self.send_response(401)
                self.send_header('WWW-Authenticate', 'Basic realm="t2s"')
                self.end_headers()
                return
            encoded = auth.split(' ', 1)[1].strip()
            try:
                decoded = base64.b64decode(encoded).decode('utf-8')
            except Exception:
                self.send_response(401)
                self.send_header('WWW-Authenticate', 'Basic realm="t2s"')
                self.end_headers()
                return
            user, _, passwd = decoded.partition(':')
            if user != web_user or passwd != web_pass:
                self.send_response(403)
                self.end_headers()
                return
        with _stats_lock:
            _stats['web_requests_total'] = _stats.get('web_requests_total', 0) + 1
        if self.path == '/metrics':
            try:
                metrics = export_prometheus_metrics()
                body = metrics.encode('utf-8')
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; version=0.0.4")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except Exception:
                with _stats_lock:
                    _stats['web_requests_errors'] = _stats.get('web_requests_errors', 0) + 1
                self.send_response(500)
                self.end_headers()
            return
        if self.path == '/ready':
            ok = False
            try:
                with _backend_lock:
                    bkl = list(_socks5_backends)
                if not bkl:
                    ok = False
                else:
                    with _backend_lock:
                        ok = any(_backend_status.get(b, False) for b in bkl)
            except Exception:
                ok = False
            code = 200 if ok else 503
            self.send_response(code)
            self.end_headers()
            self.wfile.write(b'OK' if ok else b'NOT READY')
            return
        if self.path == '/health':
            healthy, details = self._health_check()
            code = 200 if healthy else 503
            self._write_json(details, code=code)
            return
        if self.path == '/debug/connections':
            with _conns_lock:
                conns_snapshot = dict(_conns)
            self._write_json(conns_snapshot, code=200)
            return
        if self.path == '/debug/socks5_backends':
            backends_status = self._get_socks5_backends_status()
            self._write_json(backends_status, code=200)
            return
        if self.path not in ('/', '/index.html'):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'Not found')
            return
        self._send_enhanced_html()
        
    def _send_enhanced_html(self):
        with _stats_lock:
            stats_snapshot = dict(_stats)
        with _conns_lock:
            conns_snapshot = dict(_conns)
        socks5_status = self._get_socks5_backends_status()
        html = []
        html.append("<html><head><meta charset='utf-8'><title>t2s enhanced stats</title>")
        html.append('<meta http-equiv="refresh" content="10">')
        html.append("<style>")
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }")
        html.append("table { border-collapse: collapse; width: 100%; }")
        html.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
        html.append("th { background-color: #f2f2f2; }")
        html.append(".stats-section { margin-bottom: 30px; }")
        html.append(".healthy { color: green; }")
        html.append(".unhealthy { color: red; }")
        html.append("</style>")
        html.append("</head><body>")
        html.append("<h1>Transparent->SOCKS5 — Enhanced Stats</h1>")
        html.append("<div class='stats-section'><h2>SOCKS5 Backends Status</h2>")
        html.append("<table><tr><th>Host</th><th>Port</th><th>Status</th><th>Last Check</th><th>Response Time</th></tr>")
        for backend in socks5_status:
            status_class = "healthy" if backend['healthy'] else "unhealthy"
            status_text = "HEALTHY" if backend['healthy'] else "UNHEALTHY"
            last_check = time.strftime('%Y-%m-%d %H:%M:%S', 
                                     time.localtime(backend['last_check']))
            html.append(f"<tr>")
            html.append(f"<td>{backend['host']}</td>")
            html.append(f"<td>{backend['port']}</td>")
            html.append(f"<td class='{status_class}'>{status_text}</td>")
            html.append(f"<td>{last_check}</td>")
            html.append(f"<td>{backend['response_time']}</td>")
            html.append("</tr>")
        html.append("</table></div>")
        html.append("<div class='stats-section'><h2>Global Statistics</h2><ul>")
        html.append(f"<li>active_connections: {stats_snapshot.get('active_connections')}</li>")
        html.append(f"<li>total_connections: {stats_snapshot.get('total_connections')}</li>")
        html.append(f"<li>tcp_bytes_client_to_remote: {stats_snapshot.get('total_bytes_client_to_remote')}</li>")
        html.append(f"<li>tcp_bytes_remote_to_client: {stats_snapshot.get('total_bytes_remote_to_client')}</li>")
        html.append(f"<li>udp_sessions: {stats_snapshot.get('udp_sessions')}</li>")
        html.append(f"<li>udp_bytes_c2r: {stats_snapshot.get('bytes_udp_c2r')}</li>")
        html.append(f"<li>udp_bytes_r2c: {stats_snapshot.get('bytes_udp_r2c')}</li>")
        html.append(f"<li>errors: {stats_snapshot.get('errors')}</li>")
        html.append(f"<li>connection_timeouts: {stats_snapshot.get('connection_timeouts')}</li>")
        html.append(f"<li>web_requests_total: {stats_snapshot.get('web_requests_total')}</li>")
        html.append(f"<li>socks5_bypass_count: {stats_snapshot.get('socks5_bypass_count')}</li>")
        html.append(f"<li>socks5_recovered_count: {stats_snapshot.get('socks5_recovered_count')}</li>")
        html.append(f"<li>direct_connections: {stats_snapshot.get('direct_connections')}</li>")
        html.append(f"<li>http2_connections: {stats_snapshot.get('http2_connections', 0)}</li>")
        html.append(f"<li>doh_queries: {stats_snapshot.get('doh_queries', 0)}</li>")
        html.append(f"<li>enhanced_cache_hits: {stats_snapshot.get('enhanced_cache_hits', 0)}</li>")
        html.append("</ul></div>")
        html.append("<div class='stats-section'><h2>Per-protocol</h2><ul>")
        html.append(f"<li>HTTP conn count: {stats_snapshot.get('connections_http')} bytes c2r: {stats_snapshot.get('bytes_http_c2r')} bytes r2c: {stats_snapshot.get('bytes_http_r2c')}</li>")
        html.append(f"<li>HTTPS conn count: {stats_snapshot.get('connections_https')} bytes c2r: {stats_snapshot.get('bytes_https_c2r')} bytes r2c: {stats_snapshot.get('bytes_https_r2c')}</li>")
        html.append(f"<li>DNS conn count: {stats_snapshot.get('connections_dns')} bytes c2r: {stats_snapshot.get('bytes_dns_c2r')} bytes r2c: {stats_snapshot.get('bytes_dns_r2c')}</li>")
        html.append(f"<li>OTHER conn count: {stats_snapshot.get('connections_other')} bytes c2r: {stats_snapshot.get('bytes_other_c2r')} bytes r2c: {stats_snapshot.get('bytes_other_r2c')}</li>")
        html.append("</ul></div>")
        html.append("<div class='stats-section'><h2>Active connections</h2>")
        if not conns_snapshot:
            html.append("<p>No active connections</p>")
        else:
            html.append("<table cellpadding='4'><tr><th>conn_id</th><th>client</th><th>started</th><th>bytes_c2r</th><th>bytes_r2c</th></tr>")
            for cid, info in conns_snapshot.items():
                html.append("<tr>")
                html.append(f"<td>{cid}</td>")
                html.append(f"<td>{info.get('client')}</td>")
                html.append(f"<td>{time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(info.get('start_time')))}</td>")
                html.append(f"<td>{info.get('bytes_c2r')}</td>")
                html.append(f"<td>{info.get('bytes_r2c')}</td>")
                html.append("</tr>")
            html.append("</table>")
        html.append("</div>")
        html.append("<div class='stats-section'><h2>Endpoints</h2><ul>")
        html.append("<li><a href='/metrics'>/metrics (Prometheus)</a></li>")
        html.append("<li><a href='/health'>/health</a></li>")
        html.append("<li><a href='/ready'>/ready</a></li>")
        html.append("<li><a href='/debug/connections'>/debug/connections</a></li>")
        html.append("<li><a href='/debug/socks5_backends'>/debug/socks5_backends</a></li>")
        html.append("</ul></div>")
        html.append("</body></html>")
        body = "\n".join(html).encode('utf-8')
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        
    def log_message(self, format, *args):
        logger.info("%s - - %s", self.address_string(), format % args)
        
    def _health_check(self):
        details = {'socks_backends': None, 'active_connections': None, 'socks5_available': is_socks5_available()}
        ok = True
        with _stats_lock:
            details['active_connections'] = _stats.get('active_connections', 0)
        with _backend_lock:
            details['socks_backends'] = [{'host': h, 'port': p, 'healthy': _backend_status.get((h,p), False)} for (h,p) in _socks5_backends]
        if not _socks5_backends:
            ok = False
            details['socks_backends'] = 'none configured'
        return ok, details

class SimpleStatsHandler(BaseHTTPRequestHandler):
    server_version = "t2s-stats/1.0"
    def _write_json(self, obj, code=200):
        b = json.dumps(obj, default=str).encode('utf-8')
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)
    def do_GET(self):
        web_user = os.getenv('WEB_UI_USER')
        web_pass = os.getenv('WEB_UI_PASS')
        if web_user and web_pass:
            auth = self.headers.get('Authorization')
            if not auth or not auth.startswith('Basic '):
                self.send_response(401)
                self.send_header('WWW-Authenticate', 'Basic realm="t2s"')
                self.end_headers()
                return
            encoded = auth.split(' ', 1)[1].strip()
            try:
                decoded = base64.b64decode(encoded).decode('utf-8')
            except Exception:
                self.send_response(401)
                self.send_header('WWW-Authenticate', 'Basic realm="t2s"')
                self.end_headers()
                return
            user, _, passwd = decoded.partition(':')
            if user != web_user or passwd != web_pass:
                self.send_response(403)
                self.end_headers()
                return
        with _stats_lock:
            _stats['web_requests_total'] = _stats.get('web_requests_total', 0) + 1
        if self.path == '/metrics':
            try:
                metrics = export_prometheus_metrics()
                body = metrics.encode('utf-8')
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; version=0.0.4")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except Exception:
                with _stats_lock:
                    _stats['web_requests_errors'] = _stats.get('web_requests_errors', 0) + 1
                self.send_response(500)
                self.end_headers()
            return
        if self.path == '/ready':
            ok = False
            try:
                with _backend_lock:
                    bkl = list(_socks5_backends)
                if not bkl:
                    ok = False
                else:
                    with _backend_lock:
                        ok = any(_backend_status.get(b, False) for b in bkl)
            except Exception:
                ok = False
            code = 200 if ok else 503
            self.send_response(code)
            self.end_headers()
            self.wfile.write(b'OK' if ok else b'NOT READY')
            return
        if self.path == '/health':
            healthy, details = self._health_check()
            code = 200 if healthy else 503
            self._write_json(details, code=code)
            return
        if self.path == '/debug/connections':
            with _conns_lock:
                conns_snapshot = dict(_conns)
            self._write_json(conns_snapshot, code=200)
            return
        if self.path not in ('/', '/index.html'):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'Not found')
            return
        with _stats_lock:
            stats_snapshot = dict(_stats)
        with _conns_lock:
            conns_snapshot = dict(_conns)
        html = []
        html.append("<html><head><meta charset='utf-8'><title>t2s stats</title>")
        html.append('<meta http-equiv="refresh" content="10">')
        html.append("<style>")
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }")
        html.append("table { border-collapse: collapse; width: 100%; }")
        html.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
        html.append("th { background-color: #f2f2f2; }")
        html.append(".stats-section { margin-bottom: 30px; }")
        html.append("</style>")
        html.append("</head><body>")
        html.append("<h1>Transparent->SOCKS5 — stats</h1>")
        socks5_status = "Available" if is_socks5_available() else "Unavailable (using direct)"
        status_color = "green" if is_socks5_available() else "red"
        html.append(f"<div style='color: {status_color}; font-weight: bold; margin-bottom: 20px;'>SOCKS5 Status: {socks5_status}</div>")
        html.append("<div class='stats-section'><h2>Global</h2><ul>")
        html.append(f"<li>active_connections: {stats_snapshot.get('active_connections')}</li>")
        html.append(f"<li>total_connections: {stats_snapshot.get('total_connections')}</li>")
        html.append(f"<li>tcp_bytes_client_to_remote: {stats_snapshot.get('total_bytes_client_to_remote')}</li>")
        html.append(f"<li>tcp_bytes_remote_to_client: {stats_snapshot.get('total_bytes_remote_to_client')}</li>")
        html.append(f"<li>udp_sessions: {stats_snapshot.get('udp_sessions')}</li>")
        html.append(f"<li>udp_bytes_c2r: {stats_snapshot.get('bytes_udp_c2r')}</li>")
        html.append(f"<li>udp_bytes_r2c: {stats_snapshot.get('bytes_udp_r2c')}</li>")
        html.append(f"<li>errors: {stats_snapshot.get('errors')}</li>")
        html.append(f"<li>connection_timeouts: {stats_snapshot.get('connection_timeouts')}</li>")
        html.append(f"<li>web_requests_total: {stats_snapshot.get('web_requests_total')}</li>")
        html.append(f"<li>socks5_bypass_count: {stats_snapshot.get('socks5_bypass_count')}</li>")
        html.append(f"<li>socks5_recovered_count: {stats_snapshot.get('socks5_recovered_count')}</li>")
        html.append(f"<li>direct_connections: {stats_snapshot.get('direct_connections')}</li>")
        html.append("</ul></div>")
        html.append("<div class='stats-section'><h2>Per-protocol</h2><ul>")
        html.append(f"<li>HTTP conn count: {stats_snapshot.get('connections_http')} bytes c2r: {stats_snapshot.get('bytes_http_c2r')} bytes r2c: {stats_snapshot.get('bytes_http_r2c')}</li>")
        html.append(f"<li>HTTPS conn count: {stats_snapshot.get('connections_https')} bytes c2r: {stats_snapshot.get('bytes_https_c2r')} bytes r2c: {stats_snapshot.get('bytes_https_r2c')}</li>")
        html.append(f"<li>DNS conn count: {stats_snapshot.get('connections_dns')} bytes c2r: {stats_snapshot.get('bytes_dns_c2r')} bytes r2c: {stats_snapshot.get('bytes_dns_r2c')}</li>")
        html.append(f"<li>OTHER conn count: {stats_snapshot.get('connections_other')} bytes c2r: {stats_snapshot.get('bytes_other_c2r')} bytes r2c: {stats_snapshot.get('bytes_other_r2c')}</li>")
        html.append("</ul></div>")
        html.append("<div class='stats-section'><h2>Active connections</h2>")
        if not conns_snapshot:
            html.append("<p>No active connections</p>")
        else:
            html.append("<table cellpadding='4'><tr><th>conn_id</th><th>client</th><th>started</th><th>bytes_c2r</th><th>bytes_r2c</th></tr>")
            for cid, info in conns_snapshot.items():
                html.append("<tr>")
                html.append(f"<td>{cid}</td>")
                html.append(f"<td>{info.get('client')}</td>")
                html.append(f"<td>{time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(info.get('start_time')))}</td>")
                html.append(f"<td>{info.get('bytes_c2r')}</td>")
                html.append(f"<td>{info.get('bytes_r2c')}</td>")
                html.append("</tr>")
            html.append("</table>")
        html.append("</div>")
        html.append("<div class='stats-section'><h2>Endpoints</h2><ul>")
        html.append("<li><a href='/metrics'>/metrics (Prometheus)</a></li>")
        html.append("<li><a href='/health'>/health</a></li>")
        html.append("<li><a href='/ready'>/ready</a></li>")
        html.append("<li><a href='/debug/connections'>/debug/connections</a></li>")
        html.append("</ul></div>")
        html.append("</body></html>")
        body = "\n".join(html).encode('utf-8')
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, format, *args):
        logger.info("%s - - %s", self.address_string(), format % args)
    def _health_check(self):
        details = {'socks_backends': None, 'active_connections': None, 'socks5_available': is_socks5_available()}
        ok = True
        with _stats_lock:
            details['active_connections'] = _stats.get('active_connections', 0)
        with _backend_lock:
            details['socks_backends'] = [{'host': h, 'port': p, 'healthy': _backend_status.get((h,p), False)} for (h,p) in _socks5_backends]
        if not _socks5_backends:
            ok = False
            details['socks_backends'] = 'none configured'
        return ok, details

def start_web_server(port: int, socks_host: str = None, socks_port: int = None):
    global _WEB_SOCKS_HOST, _WEB_SOCKS_PORT
    _WEB_SOCKS_HOST = socks_host
    _WEB_SOCKS_PORT = socks_port
    httpd = None
    def _serve():
        nonlocal httpd
        try:
            httpd = THServer(('127.0.0.1', port), EnhancedStatsHandler)
            logger.info("Enhanced Web UI listening on 127.0.0.1:%d", port)
            while not shutdown_event.is_set():
                httpd.handle_request()
        except Exception as e:
            logger.error("Web UI error: %s", e)
        finally:
            try:
                if httpd:
                    httpd.server_close()
            except Exception:
                pass
    t = threading.Thread(target=_serve, daemon=True)
    t.start()
    return t

def validate_config(cfg):
    if cfg.get('max_conns', 0) <= 0:
        raise ValueError("max_conns должен быть положительным числом")
    if cfg.get('idle_timeout', 0) < 0:
        raise ValueError("idle_timeout не может быть отрицательным")
    if cfg.get('connect_retries', 0) < 0:
        raise ValueError("connect_retries не может быть отрицательным")
    port = cfg.get('udp_listen_port')
    if port is not None and (port <= 0 or port > 65535):
        raise ValueError("udp_listen_port вне диапазона 1-65535")

def graceful_shutdown(timeout=30):
    logger.info("Initiating graceful shutdown, timeout=%s", timeout)
    shutdown_event.set()
    start = time.time()
    while time.time() - start < timeout:
        with _stats_lock:
            if _stats.get('active_connections', 0) == 0:
                logger.info("All connections closed gracefully")
                return
        time.sleep(0.5)
    with _stats_lock:
        remaining = _stats.get('active_connections', 0)
    if remaining > 0:
        logger.warning("Graceful shutdown timed out; %d active connections remain", remaining)

def setup_structured_logger():
    class StructuredFormatter(logging.Formatter):
        def format(self, record):
            log_entry = {
                'timestamp': self.formatTime(record),
                'level': record.levelname,
                'message': record.getMessage(),
                'module': record.module,
                'thread': record.threadName,
            }
            return json.dumps(log_entry)
    handler = logging.StreamHandler()
    handler.setFormatter(StructuredFormatter())
    logging.getLogger('t2s').addHandler(handler)

def reload_config_from_env(cfg):
    changed = {}
    try:
        val = os.getenv('T2S_MAX_CONNS')
        if val:
            new = int(val)
            cfg['max_conns'] = new
            changed['max_conns'] = new
        val = os.getenv('T2S_IDLE_TIMEOUT')
        if val:
            new = int(val)
            cfg['idle_timeout'] = new
            changed['idle_timeout'] = new
        val = os.getenv('T2S_RATE_LIMIT_PER_MINUTE')
        if val:
            new = int(val)
            cfg['rate_limit_per_minute'] = new
            changed['rate_limit_per_minute'] = new
        if changed:
            logger.info("Reloaded config from env: %s", changed)
        else:
            logger.info("SIGHUP received but no relevant env vars found for reload")
    except Exception as e:
        logger.exception("Failed to reload config from env: %s", e)

def _handle_sighup(signum, frame):
    logger.info("Received SIGHUP -> scheduling config reload")
    _reload_event.set()

def install_signal_handlers():
    def _term(signum, frame):
        if not shutdown_event.is_set():
            logger.info("Received signal %d, shutting down...", signum)
            shutdown_event.set()
        else:
            if not _second_signal_forced.is_set():
                logger.warning("Received second signal %d, forcing immediate exit", signum)
                _second_signal_forced.set()
                try:
                    os._exit(1)
                except Exception:
                    pass
    signal.signal(signal.SIGTERM, _term)
    signal.signal(signal.SIGINT, _term)
    try:
        signal.signal(signal.SIGHUP, _handle_sighup)
    except Exception:
        pass

def run_server(listen_addr, listen_port, socks_host, socks_port, socks_user, socks_pass, fixed_target, cfg):
    global _rate_limiter, _socks5_backends, _socks5_available, _enhanced_dns
    with _backend_lock:
        _socks5_backends = list(_socks5_backends)
    health_monitor_thread = None
    if _socks5_backends:
        health_monitor_thread = threading.Thread(target=socks5_health_monitor_all, args=(_socks5_backends, socks_user, socks_pass), daemon=True)
        health_monitor_thread.start()
        logger.info("SOCKS5 health monitor started for %d backends", len(_socks5_backends))
    else:
        with _socks5_lock:
            _socks5_available = False
    if cfg.get('enable_doh', False):
        _enhanced_dns = EnhancedDNSResolver(
            enable_doh=cfg.get('enable_doh', True),
            enable_doq=cfg.get('enable_doq', False)
        )
        logger.info("Enhanced DNS resolver initialized (DoH: %s)", cfg.get('enable_doh', True))
    mode = cfg.get('mode', 'tcp')
    full_config = {
        'mode': mode,
        'listen_addr': listen_addr,
        'listen_port': listen_port,
        'socks_backends': _socks5_backends,
        'socks_user': '***' if socks_user else None,
        'socks_pass': '***' if socks_pass else None,
        'fixed_target': fixed_target,
        'buffer_size': cfg.get('buffer_size'),
        'idle_timeout': cfg.get('idle_timeout'),
        'connect_timeout': cfg.get('connect_timeout'),
        'connect_retries': cfg.get('connect_retries'),
        'retry_backoff': cfg.get('retry_backoff'),
        'keepidle': cfg.get('keepidle'),
        'keepintvl': cfg.get('keepintvl'),
        'keepcnt': cfg.get('keepcnt'),
        'max_conns': cfg.get('max_conns'),
        'backlog': cfg.get('backlog'),
        'stats_interval': cfg.get('stats_interval'),
        'rate_limit_per_minute': cfg.get('rate_limit_per_minute'),
        'enable_http2': cfg.get('enable_http2', False),
        'enable_doh': cfg.get('enable_doh', False),
    }
    logger.info("Starting service with full configuration: %s", json.dumps(full_config, indent=2))
    rate_limit = cfg.get('rate_limit_per_minute', 0)
    env_rl = os.getenv('T2S_RATE_LIMIT_PER_MINUTE')
    if env_rl:
        try:
            rate_limit = int(env_rl)
        except Exception:
            logger.warning("Invalid T2S_RATE_LIMIT_PER_MINUTE value, ignoring")
    _rate_limiter = RateLimiter(max_per_minute=rate_limit or 0)
    tcp_sock = None
    udp_thread = None
    web_thread = None
    reporter = None
    if mode in ('tcp', 'tcp-udp'):
        tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp_sock.bind((listen_addr, listen_port))
        tcp_sock.listen(cfg.get('backlog', 128))
        tcp_sock.settimeout(1.0)
        logger.info("Listening on %s:%d (TCP)", listen_addr, listen_port)
        if fixed_target:
            logger.info("Forwarding all incoming connections to fixed target %s:%d via SOCKS backends %s", fixed_target[0], fixed_target[1], _socks5_backends)
        else:
            logger.info("Transparent mode: will attempt SO_ORIGINAL_DST and forward via SOCKS backends %s", _socks5_backends)
    max_conns = cfg.get('max_conns', 200)
    sem = threading.BoundedSemaphore(max_conns)
    reporter = threading.Thread(target=stats_reporter, args=(cfg.get('stats_interval', 30),), daemon=True)
    reporter.start()
    if mode in ('udp', 'tcp-udp'):
        udp_listen_addr = cfg.get('udp_listen_addr', listen_addr)
        udp_listen_port = cfg.get('udp_listen_port', listen_port)
        udp_thread = threading.Thread(target=udp_server_loop, args=(udp_listen_addr, udp_listen_port, None, None, socks_user, socks_pass, fixed_target, cfg), daemon=True)
        udp_thread.start()
        logger.info("UDP server configured to use same port as TCP: %d", udp_listen_port)
    try:
        if mode in ('tcp', 'tcp-udp'):
            while not shutdown_event.is_set():
                if _reload_event.is_set():
                    logger.info("Applying config reload from env (limited set)")
                    reload_config_from_env(cfg)
                    rl = cfg.get('rate_limit_per_minute', 0)
                    env_rl2 = os.getenv('T2S_RATE_LIMIT_PER_MINUTE')
                    if env_rl2:
                        try:
                            rl = int(env_rl2)
                        except Exception:
                            pass
                    if _rate_limiter:
                        _rate_limiter.max_per_minute = rl or 0
                        logger.info("Rate limiter updated to %s", _rate_limiter.max_per_minute)
                    _reload_event.clear()
                try:
                    try:
                        client_sock, client_addr = tcp_sock.accept()
                    except socket.timeout:
                        continue
                except OSError as e:
                    if e.errno == errno.EINTR:
                        continue
                    if shutdown_event.is_set():
                        break
                    raise
                try:
                    if _rate_limiter and not _rate_limiter.allow_connection():
                        logger.warning("RateLimiter: rejecting connection from %s:%d (rate limit exceeded)", client_sock.getpeername()[0], client_sock.getpeername()[1])
                        try:
                            client_sock.close()
                        except Exception:
                            pass
                        with _stats_lock:
                            _stats['errors'] = _stats.get('errors', 0) + 1
                        continue
                except Exception:
                    logger.exception("RateLimiter failure; allowing connection")
                chosen_backend = select_socks_backend()
                chosen_host, chosen_port = chosen_backend if chosen_backend != (None, None) else (None, None)
                if cfg.get('enable_http2', False):
                    thr = threading.Thread(target=handle_client_enhanced, args=(client_sock, client_addr, chosen_host, chosen_port, socks_user, socks_pass, fixed_target, sem, cfg), daemon=True)
                else:
                    thr = threading.Thread(target=handle_client, args=(client_sock, client_addr, chosen_host, chosen_port, socks_user, socks_pass, fixed_target, sem, cfg), daemon=True)
                thr.start()
        else:
            while not shutdown_event.is_set():
                if _reload_event.is_set():
                    logger.info("Applying config reload from env (limited set)")
                    reload_config_from_env(cfg)
                    _reload_event.clear()
                time.sleep(1)
    except KeyboardInterrupt:
        logger.info("Stopping (KeyboardInterrupt)")
    finally:
        shutdown_event.set()
        logger.info("Shutting down listeners and waiting for active connections to finish")
        try:
            if tcp_sock:
                try:
                    tcp_sock.close()
                except Exception:
                    pass
        except Exception:
            pass
        graceful_shutdown(timeout=cfg.get('graceful_shutdown_timeout', 30))
        logger.info("Server main loop exited.")
        time.sleep(0.1)

def parse_args():
    p = argparse.ArgumentParser(description="Transparent -> SOCKS5 with enhanced features")
    p.add_argument("--listen-addr", default="127.0.0.1")
    p.add_argument("--listen-port", type=int, default=11290)
    p.add_argument("--socks-host", required=True, help="Upstream SOCKS5 host(s) - comma-separated list allowed")
    p.add_argument("--socks-port", type=str, required=True, help="Upstream SOCKS5 port(s) - comma-separated list allowed (e.g. 11260,11740)")
    p.add_argument("--socks-user", default=None, help="optional username for SOCKS5")
    p.add_argument("--socks-pass", default=None, help="optional password for SOCKS5")
    p.add_argument("--target-host", default=None, help="fixed target host (optional)")
    p.add_argument("--target-port", type=int, default=None, help="fixed target port (optional)")
    p.add_argument("--buffer-size", type=int, default=1048576, help="read buffer size")
    p.add_argument("--idle-timeout", type=int, default=300, help="idle timeout in seconds for connections (0 disable)")
    p.add_argument("--connect-timeout", type=int, default=10, help="timeout for TCP connect to upstream")
    p.add_argument("--connect-retries", type=int, default=3, help="retries when connecting to upstream")
    p.add_argument("--retry-backoff", type=float, default=0.5, help="base backoff for retries (exponential)")
    p.add_argument("--keepidle", type=int, default=60, help="TCP keepalive idle (seconds)")
    p.add_argument("--keepintvl", type=int, default=10, help="TCP keepalive interval (seconds)")
    p.add_argument("--keepcnt", type=int, default=5, help="TCP keepalive count")
    p.add_argument("--max-conns", type=int, default=200, help="максимум одновременных сессий")
    p.add_argument("--backlog", type=int, default=128)
    p.add_argument("--stats-interval", type=int, default=30, help="интервал логирования глобальных статистик (сек)")
    p.add_argument("--mode", choices=("tcp", "udp", "tcp-udp"), default="tcp", help="which protocols to enable: tcp (default), udp, tcp-udp")
    p.add_argument("--udp-listen-addr", default=None, help="address for UDP listener (default same as --listen-addr)")
    p.add_argument("--udp-listen-port", type=int, default=None, help="UDP listen port (default same as --listen-port)")
    p.add_argument("--udp-session-timeout", type=int, default=60, help="idle timeout (seconds) for UDP sessions")
    p.add_argument("--udp-buffer-size", type=int, default=65536, help="read buffer size for UDP")
    p.add_argument("--web-socket", action='store_true', help="Enable simple web UI for stats")
    p.add_argument("--web-port", type=int, default=8000, help="Port for the web UI (default 8000)")
    p.add_argument("--logfile", default=None, help="path to log file")
    p.add_argument("--verbose", action='store_true', help="debug logging")
    p.add_argument("--cache-mode", choices=("memory", "disk-cache"), default="memory", help="memory or disk-cache (if disk-cache -> /data/adb/modules/ZDT-D/cache/)")
    p.add_argument("--graceful-shutdown-timeout", type=int, default=30, help="seconds to wait for active connections to finish on shutdown")
    p.add_argument("--enable-http2", action='store_true', help="Enable HTTP/2 support")
    p.add_argument("--enable-doh", action='store_true', help="Enable DNS-over-HTTPS")
    p.add_argument("--enable-doq", action='store_true', help="Enable DNS-over-QUIC (experimental)")
    p.add_argument("--enhanced-cache", action='store_true', help="Enable enhanced caching with HTTP/2 support")
    return p.parse_args()

if __name__ == "__main__":
    args = parse_args()
    fixed_target = None
    if (args.target_host is not None) ^ (args.target_port is not None):
        print("Если используешь --target-host, укажи также --target-port.")
        sys.exit(2)
    if args.target_host is not None and args.target_port is not None:
        fixed_target = (args.target_host, args.target_port)
    level = logging.DEBUG if args.verbose else logging.INFO
    logger = setup_logger(level=level, logfile=args.logfile)
    if args.verbose:
        setup_structured_logger()
    socks_hosts = [h.strip() for h in args.socks_host.split(',') if h.strip()]
    socks_ports = [p.strip() for p in str(args.socks_port).split(',') if p.strip()]
    parsed_ports = []
    for p in socks_ports:
        try:
            parsed_ports.append(int(p))
        except Exception:
            logger.error("Invalid socks port: %s", p)
            sys.exit(2)
    backends = []
    for h in socks_hosts:
        for pr in parsed_ports:
            backends.append((h, pr))
    if not backends:
        logger.error("No valid SOCKS5 backends parsed")
        sys.exit(2)
    with _backend_lock:
        _socks5_backends = backends[:]
        for b in _socks5_backends:
            _backend_status[b] = True
    udp_listen_port = args.udp_listen_port if args.udp_listen_port is not None else args.listen_port
    cfg = {
        'buffer_size': args.buffer_size,
        'idle_timeout': args.idle_timeout,
        'connect_timeout': args.connect_timeout,
        'connect_retries': args.connect_retries,
        'retry_backoff': args.retry_backoff,
        'keepidle': args.keepidle,
        'keepintvl': args.keepintvl,
        'keepcnt': args.keepcnt,
        'max_conns': args.max_conns,
        'backlog': args.backlog,
        'stats_interval': args.stats_interval,
        'mode': args.mode,
        'udp_listen_addr': args.udp_listen_addr if args.udp_listen_addr is not None else args.listen_addr,
        'udp_listen_port': udp_listen_port,
        'udp_session_timeout': args.udp_session_timeout,
        'udp_buffer_size': args.udp_buffer_size,
        'rate_limit_per_minute': int(os.getenv('T2S_RATE_LIMIT_PER_MINUTE', '0') or 0),
        'graceful_shutdown_timeout': args.graceful_shutdown_timeout,
        'enable_http2': args.enable_http2,
        'enable_doh': args.enable_doh,
        'enable_doq': args.enable_doq,
    }
    try:
        validate_config(cfg)
    except Exception as e:
        print("Invalid configuration:", e)
        sys.exit(2)
    install_signal_handlers()
    cache_mode = args.cache_mode
    cache_dir = '/data/adb/modules/ZDT-D/cache/' if cache_mode == 'disk-cache' else None
    if args.enhanced_cache:
        http_cache = EnhancedHTTPCache(cache_ttl=300, cache_max_size=1_000_000, mode=cache_mode, cache_dir=cache_dir)
        logger.info("Enhanced HTTP cache initialized")
    else:
        http_cache = SimpleHTTPCache(cache_ttl=300, cache_max_size=1_000_000, mode=cache_mode, cache_dir=cache_dir)
    dns_cache = DNSCache(ttl=300, mode=cache_mode, cache_dir=cache_dir)
    if args.web_socket:
        try:
            first_backend = _socks5_backends[0] if _socks5_backends else (None, None)
            start_web_server(args.web_port, first_backend[0] if first_backend else None, first_backend[1] if first_backend else None)
        except Exception as e:
            logger.error("Failed to start web UI: %s", e)
    try:
        first = _socks5_backends[0]
        run_server(args.listen_addr, args.listen_port, first[0], first[1], args.socks_user, args.socks_pass, fixed_target, cfg)
    finally:
        graceful_shutdown(timeout=cfg.get('graceful_shutdown_timeout', 30))
