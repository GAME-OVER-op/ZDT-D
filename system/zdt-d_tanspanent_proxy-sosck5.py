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
import re
import functools
import base64
import hashlib
import weakref
import tempfile
import concurrent.futures
from concurrent.futures import ThreadPoolExecutor
import fcntl
from http.server import BaseHTTPRequestHandler, HTTPServer
try:
    from http.server import ThreadingHTTPServer as THServer
except Exception:
    THServer = HTTPServer
from typing import Optional, Tuple
from collections import deque

# New imports for enhanced functionality
try:
    import psutil  # For system monitoring
    PSUTIL_AVAILABLE = True
except ImportError:
    PSUTIL_AVAILABLE = False
    print("psutil not available - system monitoring disabled")

try:
    import prometheus_client  # For advanced metrics
    from prometheus_client import Counter, Gauge, Histogram, generate_latest
    PROMETHEUS_AVAILABLE = True
except ImportError:
    PROMETHEUS_AVAILABLE = False
    print("prometheus_client not available - advanced metrics disabled")

try:
    import yaml  # For YAML config support
    YAML_AVAILABLE = True
except ImportError:
    YAML_AVAILABLE = False
    print("PyYAML not available - YAML config support disabled")

try:
    from dataclasses import dataclass, asdict  # For structured configuration
    DATACLASS_AVAILABLE = True
except ImportError:
    DATACLASS_AVAILABLE = False
    print("dataclasses not available - using dict config")

try:
    import orjson  # Faster JSON processing
    ORJSON_AVAILABLE = True
except ImportError:
    ORJSON_AVAILABLE = False
    print("orjson not available - using standard json")

try:
    import uvloop  # Faster event loop (for asyncio components)
    UVLOOP_AVAILABLE = True
except ImportError:
    UVLOOP_AVAILABLE = False
    print("uvloop not available - using standard event loop")

try:
    import aiohttp  # Async HTTP client for health checks
    AIOHTTP_AVAILABLE = True
except ImportError:
    AIOHTTP_AVAILABLE = False
    print("aiohttp not available - async health checks disabled")

# SSL/TLS support imports
try:
    import ssl
    SSL_AVAILABLE = True
except ImportError:
    SSL_AVAILABLE = False
    print("ssl module not available - TLS support disabled")

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

# Initialize prometheus metrics if available
if PROMETHEUS_AVAILABLE:
    # Connection metrics
    active_connections = Gauge('t2s_active_connections', 'Currently active connections')
    total_connections = Counter('t2s_total_connections', 'Total connections processed')
    connection_errors = Counter('t2s_connection_errors', 'Connection errors', ['type'])
    
    # Traffic metrics
    bytes_transferred = Counter('t2s_bytes_transferred', 'Bytes transferred', ['direction', 'protocol'])
    udp_packets = Counter('t2s_udp_packets', 'UDP packets processed', ['direction'])
    
    # Performance metrics
    connection_duration = Histogram('t2s_connection_duration_seconds', 'Connection duration in seconds')
    backend_response_time = Histogram('t2s_backend_response_time_seconds', 'Backend response time in seconds')
    
    # Cache metrics
    cache_hits = Counter('t2s_cache_hits', 'Cache hits', ['type'])
    cache_misses = Counter('t2s_cache_misses', 'Cache misses', ['type'])

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
    'errors_connection_timeout': 0,
    'errors_socket_error': 0,
    'errors_socks_handshake': 0,
    'errors_dns': 0,
    'errors_auth': 0,
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


def _inc_error(exc=None):
    """Increment total errors and try to attribute to a reason bucket.

    IMPORTANT: Call this ONLY when _stats_lock is already held.
    If exc is None, uses current exception from sys.exc_info().
    """
    if exc is None:
        try:
            exc = sys.exc_info()[1]
        except Exception:
            exc = None

    # total
    _stats['errors'] = _stats.get('errors', 0) + 1

    # Reason attribution (best-effort, avoid breaking request handling because of stats errors)
    try:
        if exc is None:
            return

        # Dedicated buckets where we can reliably detect by type
        if isinstance(exc, socket.timeout):
            _stats['errors_connection_timeout'] = _stats.get('errors_connection_timeout', 0) + 1
            _stats['connection_timeouts'] = _stats.get('connection_timeouts', 0) + 1
            return

        if isinstance(exc, socket.gaierror):
            _stats['errors_dns'] = _stats.get('errors_dns', 0) + 1
            _stats['dns_failures'] = _stats.get('dns_failures', 0) + 1
            return

        # Fallback buckets based on message / errno
        msg = str(exc).lower() if exc else ""

        if ("auth" in msg) or ("authentication" in msg):
            _stats['errors_auth'] = _stats.get('errors_auth', 0) + 1
            _stats['auth_failures'] = _stats.get('auth_failures', 0) + 1
            return

        if ("handshake" in msg) or ("socks" in msg) or ("method" in msg and "socks" in msg):
            _stats['errors_socks_handshake'] = _stats.get('errors_socks_handshake', 0) + 1
            return

        if isinstance(exc, OSError):
            _stats['errors_socket_error'] = _stats.get('errors_socket_error', 0) + 1
            return
    except Exception:
        return


_conns_lock = threading.Lock()
_conns = {}
# Registry for UDP sessions (to allow policy actions on SOCKS recovery)
_udp_sessions_registry = weakref.WeakSet()
_udp_sessions_lock = threading.Lock()

# Traffic policy rules cache (optional; keeps default behavior if env TRAFFIC_RULES is unset)
_TRAFFIC_RULES_RAW = None
_TRAFFIC_RULES_PARSED = None
_TRAFFIC_RULES_LOCK = threading.Lock()

# SOCKS recovery actions
_last_socks_recovery_ts = 0.0

shutdown_event = threading.Event()
_reload_event = threading.Event()

_WEB_SOCKS_HOST = None
_WEB_SOCKS_PORT = None

_rate_limiter = None

_socks5_backends = []
_backend_idx = 0
_backend_lock = threading.Lock()
_backend_status = {}
_backend_extended_status = {}  # Новый словарь для расширенной информации о статусе
_socks5_available = True
_socks5_lock = threading.Lock()
_socks5_last_check = 0
_socks5_check_interval = 35  # Увеличено с 30 до 35 секунд (+5s)

_second_signal_forced = threading.Event()

_enhanced_dns = None
_http2_handler = None

# System monitoring
_system_stats = {
    'cpu_percent': 0.0,
    'memory_usage': 0.0,
    'memory_usage_percent': 0.0,
    'process_memory_rss': 0,
    'network_io': {'bytes_sent': 0, 'bytes_recv': 0},
    'disk_io': {'read_bytes': 0, 'write_bytes': 0}
}
_system_stats_lock = threading.Lock()

# Backend runtime stats
_backend_stats = {} # key: (host,port) -> {'total_bytes': int, 'last_ts': float, 'speed_bps': float, 'ema_speed': float, 'last_response_ms': float}
_backend_stats_lock = threading.Lock()

# Total throughput tracking for Web UI (server-side)
_throughput_lock = threading.Lock()
_throughput_state = {'last_ts': time.time(), 'last_total_bytes': 0}

# SSL/TLS certificate path
_certificate_path = None

# --- добавьте рядом с _backend_stats, _backend_stats_lock ---
from collections import deque as _deque

# TTL/throughput settings
_SSE_INTERVAL = 1  # будет перезаписан из cfg в main
_TTL_SAMPLE_WINDOW = 150        # сколько TTL сэмплов хранить на backend

def _read_proc_mem():
    """Fallback: read VmRSS (kB) from /proc/self/status"""
    try:
        with open('/proc/self/status', 'r') as f:
            for line in f:
                if line.startswith('VmRSS:'):
                    parts = line.split()
                    if len(parts) >= 2:
                        kb = int(parts[1])
                        return kb * 1024  # bytes
    except Exception:
        pass
    return None


def _get_process_rss_bytes():
    """Best-effort RSS (bytes) for current process. Works on Android/Linux."""
    # Prefer psutil.Process().memory_info().rss (correct), but keep /proc fallback.
    if PSUTIL_AVAILABLE:
        try:
            p = psutil.Process(os.getpid())
            mi = p.memory_info()
            rss = getattr(mi, 'rss', None)
            if rss is not None:
                return int(rss)
        except Exception:
            pass
    try:
        rss = _read_proc_mem()
        if rss is not None:
            return int(rss)
    except Exception:
        pass
    return 0
def _update_backend_ttl(backend, ttl_value):
    """Store TTL samples for backend and compute 'ttl_integrity' percent."""
    try:
        ttl = int(ttl_value)
    except Exception:
        return
    now = time.time()
    with _backend_stats_lock:
        st = _backend_stats.get(backend)
        if not st:
            st = {'total_bytes': 0, 'last_ts': now, 'speed_bps': 0.0, 'ema_speed': 0.0, 'last_response_ms': None,
                  'ttl_samples': _deque(maxlen=_TTL_SAMPLE_WINDOW), 'ttl_integrity': None}
            _backend_stats[backend] = st
        if 'ttl_samples' not in st:
            st['ttl_samples'] = _deque(maxlen=_TTL_SAMPLE_WINDOW)
        st['ttl_samples'].append(ttl)
        # compute integrity = share of samples equal to modal TTL
        samples = list(st['ttl_samples'])
        if samples:
            mode = max(set(samples), key=samples.count)
            integrity = 100.0 * sum(1 for x in samples if x == mode) / len(samples)
            st['ttl_integrity'] = integrity

def _update_backend_bytes(backend, delta_bytes):
    """Increment counters for backend and update an EMA speed estimate."""
    now = time.time()
    with _backend_stats_lock:
        st = _backend_stats.get(backend)
        if not st:
            st = {'total_bytes': 0, 'last_ts': now, 'speed_bps': 0.0, 'ema_speed': 0.0, 'last_response_ms': None, 'ttl_samples': _deque(maxlen=_TTL_SAMPLE_WINDOW), 'ttl_integrity': None}
            _backend_stats[backend] = st
        # update total
        st['total_bytes'] = st.get('total_bytes', 0) + delta_bytes
        # compute instantaneous speed if time delta available
        td = now - st.get('last_ts', now)
        inst = 0.0
        if td > 0:
            inst = delta_bytes / td
        st['speed_bps'] = inst
        # EMA smoothing to avoid huge spikes (alpha ~0.3)
        alpha = 0.3
        prev = st.get('ema_speed', 0.0) or 0.0
        st['ema_speed'] = prev * (1 - alpha) + inst * alpha
        st['last_ts'] = now

def _set_backend_response_ms(backend, ms):
    with _backend_stats_lock:
        st = _backend_stats.get(backend)
        if not st:
            st = {'total_bytes': 0, 'last_ts': time.time(), 'speed_bps': 0.0, 'ema_speed': 0.0, 'last_response_ms': ms, 'ttl_samples': _deque(maxlen=_TTL_SAMPLE_WINDOW), 'ttl_integrity': None}
            _backend_stats[backend] = st
        else:
            st['last_response_ms'] = ms

def setup_logger(level=logging.INFO, logfile=None):
    fmt = '%(asctime)s [%(levelname)s] %(message)s'
    log_format = os.getenv('T2S_LOG_FORMAT', 'text').lower()
    root = logging.getLogger('t2s')
    root.setLevel(level)
    # remove existing handlers to avoid duplicate logs when reloading
    for h in list(root.handlers):
        root.removeHandler(h)
    
    # Add colorlog support if available
    try:
        import colorlog
        COLORLOG_AVAILABLE = True
    except ImportError:
        COLORLOG_AVAILABLE = False
    
    if logfile:
        # rotating file handler
        try:
            from logging.handlers import RotatingFileHandler
            fh = RotatingFileHandler(logfile, maxBytes=10_000_000, backupCount=5)
            if log_format == 'json':
                class JsonFormatter(logging.Formatter):
                    def format(self, record):
                        entry = {
                            "timestamp": self.formatTime(record),
                            "level": record.levelname,
                            "module": record.module,
                            "message": record.getMessage(),
                            "thread": record.threadName
                        }
                        if ORJSON_AVAILABLE:
                            return orjson.dumps(entry).decode('utf-8')
                        else:
                            return json.dumps(entry)
                fh.setFormatter(JsonFormatter())
            else:
                fh.setFormatter(logging.Formatter(fmt))
            root.addHandler(fh)
        except Exception:
            logging.basicConfig(level=level, format=fmt)
    else:
        if COLORLOG_AVAILABLE and log_format != 'json':
            # Use colored console output
            formatter = colorlog.ColoredFormatter(
                '%(log_color)s%(asctime)s [%(levelname)s] %(message)s',
                datefmt='%Y-%m-%d %H:%M:%S',
                log_colors={
                    'DEBUG': 'cyan',
                    'INFO': 'green',
                    'WARNING': 'yellow',
                    'ERROR': 'red',
                    'CRITICAL': 'red,bg_white',
                }
            )
            sh = logging.StreamHandler()
            sh.setFormatter(formatter)
            root.addHandler(sh)
        else:
            sh = logging.StreamHandler()
            if log_format == 'json':
                class JsonFormatter(logging.Formatter):
                    def format(self, record):
                        entry = {
                            "timestamp": self.formatTime(record),
                            "level": record.levelname,
                            "module": record.module,
                            "message": record.getMessage(),
                            "thread": record.threadName
                        }
                        if ORJSON_AVAILABLE:
                            return orjson.dumps(entry).decode('utf-8')
                        else:
                            return json.dumps(entry)
                sh.setFormatter(JsonFormatter())
            else:
                sh.setFormatter(logging.Formatter(fmt))
            root.addHandler(sh)
    return root

def _atomic_write(path, data_bytes):
    """ Atomic write with flock to avoid races and partial files. """
    dirpath = os.path.dirname(path)
    os.makedirs(dirpath, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=dirpath)
    try:
        with os.fdopen(fd, 'wb') as f:
            f.write(data_bytes)
            f.flush()
            os.fsync(f.fileno())
        # use rename which is atomic on POSIX
        os.replace(tmp, path)
    except Exception:
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except Exception:
            pass

# Patch DNSCache._persist
def DNSCache__persist(self):
    if self.mode == 'memory':
        return
    try:
        if ORJSON_AVAILABLE:
            b = orjson.dumps(self._cache)
        else:
            b = json.dumps(self._cache).encode('utf-8')
        _atomic_write(self.db_file, b)
    except Exception:
        pass

# Patch SimpleHTTPCache._persist_meta and EnhancedHTTPCache._persist_meta/_persist_http2_meta
def SimpleHTTPCache__persist_meta(self):
    try:
        if ORJSON_AVAILABLE:
            b = orjson.dumps(self._meta)
        else:
            b = json.dumps(self._meta).encode('utf-8')
        _atomic_write(self._meta_file, b)
    except Exception:
        pass

def EnhancedHTTPCache__persist_meta(self):
    try:
        if ORJSON_AVAILABLE:
            b = orjson.dumps(self._meta)
        else:
            b = json.dumps(self._meta).encode('utf-8')
        _atomic_write(self._meta_file, b)
    except Exception:
        pass

def EnhancedHTTPCache__persist_http2_meta(self):
    try:
        if ORJSON_AVAILABLE:
            b = orjson.dumps(self.http2_meta)
        else:
            b = json.dumps(self.http2_meta).encode('utf-8')
        _atomic_write(self.http2_meta_file, b)
    except Exception:
        pass

def system_monitor(interval=15):  # Увеличено с 10 до 15 секунд (+5s)
    """Monitor system resources and update stats"""
    if not PSUTIL_AVAILABLE:
        # Fallback: minimal memory monitoring
        while not shutdown_event.is_set():
            try:
                rss = _read_proc_mem()
                if rss is not None:
                    with _system_stats_lock:
                        _system_stats['process_memory_rss'] = rss
                time.sleep(interval)
            except Exception as e:
                logger.debug("System monitoring error: %s", e)
                time.sleep(interval)
        return
        
    net_io = psutil.net_io_counters()
    disk_io = psutil.disk_io_counters()
    
    with _system_stats_lock:
        if net_io:
            _system_stats['network_io'] = {
                'bytes_sent': net_io.bytes_sent,
                'bytes_recv': net_io.bytes_recv
            }
        if disk_io:
            _system_stats['disk_io'] = {
                'read_bytes': disk_io.read_bytes,
                'write_bytes': disk_io.write_bytes
            }
    
    while not shutdown_event.is_set():
        try:
            cpu_percent = psutil.cpu_percent(interval=1)
            memory = psutil.virtual_memory()
            net_io = psutil.net_io_counters()
            disk_io = psutil.disk_io_counters()
            
            with _system_stats_lock:
                _system_stats['cpu_percent'] = cpu_percent
                _system_stats['memory_usage'] = memory.percent
                _system_stats['memory_usage_percent'] = memory.percent
                _system_stats['process_memory_rss'] = _get_process_rss_bytes()
                if net_io:
                    _system_stats['network_io'] = {
                        'bytes_sent': net_io.bytes_sent,
                        'bytes_recv': net_io.bytes_recv
                    }
                if disk_io:
                    _system_stats['disk_io'] = {
                        'read_bytes': disk_io.read_bytes,
                        'write_bytes': disk_io.write_bytes
                    }
                    
            time.sleep(interval)
        except Exception as e:
            logger.debug("System monitoring error: %s", e)
            time.sleep(interval)

def validate_config_schema(config):
    """Validate configuration against schema"""
    required_fields = ['listen_addr', 'listen_port']
    
    for field in required_fields:
        if field not in config:
            raise ValueError(f"Missing required field: {field}")
    
    # Validate ports
    if config.get('listen_port') and not (1 <= config['listen_port'] <= 65535):
        raise ValueError("listen_port must be между 1 и 65535")
    
    if config.get('web_port') and not (1 <= config['web_port'] <= 65535):
        raise ValueError("web_port must быть между 1 и 65535")
    
    # Validate timeouts
    timeouts = ['idle_timeout', 'connect_timeout', 'udp_session_timeout']
    for timeout in timeouts:
        if config.get(timeout) and config[timeout] < 0:
            raise ValueError(f"{timeout} cannot be negative")
    
    return True

def config_file_watcher(config_path, callback, check_interval=15):  # Увеличено с 10 до 15 секунд (+5s)
    """Watch config file for changes and trigger reload"""
    if not os.path.exists(config_path):
        logger.warning("Config file %s does not exist, skipping watcher", config_path)
        return
        
    last_mtime = os.path.getmtime(config_path)
    
    while not shutdown_event.is_set():
        try:
            current_mtime = os.path.getmtime(config_path)
            if current_mtime != last_mtime:
                logger.info("Config file changed, triggering reload")
                last_mtime = current_mtime
                callback()
        except Exception as e:
            logger.debug("Config file watcher error: %s", e)
        
        time.sleep(check_interval)

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

def enable_tcp_keepalive(sock, keepidle=125, keepintvl=30, keepcnt=3):  # Увеличены таймауты keepidle с 120 до 125 (+5s)
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
    def __init__(self, cache_ttl=600, enable_doh=True, enable_doq=False):  # Увеличен TTL до 600 секунд
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
                logger.debug(f"DoH server {server} failed: %s", e)
                continue
        return None

class DNSCache:
    def __init__(self, ttl: int = 600, mode: str = 'memory', cache_dir: str = None):  # Увеличен TTL до 600 секунд
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
                    if ORJSON_AVAILABLE:
                        self._cache = orjson.loads(f.read())
                    else:
                        self._cache = json.load(f)
            except Exception:
                self._cache = {}

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
    def __init__(self, cache_ttl=600, cache_max_size=1_000_000, mode='memory', cache_dir=None):  # Увеличен TTL до 600 секунд
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
                    if ORJSON_AVAILABLE:
                        self._meta = orjson.loads(f.read())
                    else:
                        self._meta = json.load(f)
            except Exception:
                self._meta = {}
            self.http2_meta_file = os.path.join(self.cache_dir, 'http2_meta.json')
            try:
                with open(self.http2_meta_file, 'r') as f:
                    if ORJSON_AVAILABLE:
                        self.http2_meta = orjson.loads(f.read())
                    else:
                        self.http2_meta = json.load(f)
            except Exception:
                self.http2_meta = {}

    def _key_to_fname(self, host, path):
        key = host + '|' + path
        h = hashlib.sha256(key.encode('utf-8')).hexdigest()
        return os.path.join(self.cache_dir, h)

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
    def __init__(self, cache_ttl=600, cache_max_size=1_000_000, mode='memory', cache_dir=None):  # Увеличен TTL до 600 секунд
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
                    if ORJSON_AVAILABLE:
                        self._meta = orjson.loads(f.read())
                    else:
                        self._meta = json.load(f)
            except Exception:
                self._meta = {}

    def _key_to_fname(self, host, path):
        key = host + '|' + path
        h = hashlib.sha256(key.encode('utf-8')).hexdigest()
        return os.path.join(self.cache_dir, h)

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
            logger.error("HTTP/2 handling failed: %s", e)
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
                logger.error("HTTP/2 processing error: %s", e)
                break
                
    def _handle_http2_event(self, event):
        if isinstance(event, h2.events.RequestReceived):
            self._handle_http2_request(event)
        elif isinstance(event, h2.events.DataReceived):
            self._handle_http2_data(event)
            
    def _handle_http2_request(self, event):
        logger.debug("HTTP/2 request received: %s", event)
        pass
        
    def _handle_http2_data(self, event):
        logger.debug("HTTP/2 data received: %s bytes", len(event.data))
        pass
        
    def cleanup(self):
        try:
            if self.remote_socket:
                self.remote_socket.close()
        except Exception:
                pass

def check_socks5_health(socks_host, socks_port, socks_user=None, socks_pass=None, timeout=5):
    """
    Проверка здоровья SOCKS5 + возвращает (healthy: bool, response_ms: float|None, internet_ping: float|None)
    response_ms — время соединения в миллисекундах (если удалось подключиться).
    Добавлена проверка доступности интернета через сервер.
    """
    s = None
    start_t = time.time()
    try:
        if _enhanced_dns:
            ip = _enhanced_dns.resolve_enhanced(socks_host)
        else:
            ip = dns_cache.resolve(socks_host)
    except Exception:
        ip = socks_host
    
    try:
        start_conn = time.time()
        s = socket.create_connection((ip, socks_port), timeout=timeout)
        conn_ms = (time.time() - start_conn) * 1000.0
        
        # minimal METHODS exchange to verify SOCKS5 listens/responds
        methods = [0x00]
        if socks_user and socks_pass:
            methods.append(0x02)
        s.sendall(struct.pack("!BB", 0x05, len(methods)) + bytes(methods))
        data = s.recv(2)
        if len(data) < 2:
            return False, conn_ms, None
        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            return False, conn_ms, None
        
        if method == 0x02:
            if not socks_user or not socks_pass:
                return False, conn_ms, None
            ub = socks_user.encode('utf-8')
            pb = socks_pass.encode('utf-8')
            if len(ub) > 255 or len(pb) > 255:
                return False, conn_ms, None
            sub = struct.pack("!B", 0x01) + struct.pack("!B", len(ub)) + ub + struct.pack("!B", len(pb)) + pb
            s.sendall(sub)
            subresp = s.recv(2)
            if len(subresp) < 2:
                return False, conn_ms, None
            ver2, status = struct.unpack("!BB", subresp)
            if ver2 != 0x01 or status != 0x00:
                return False, conn_ms, None
        
        # Проверка доступности интернета через сервер - ПЕРЕПИСАНА
        internet_ping = None
        # Пробуем несколько популярных DNS-серверов
        test_targets = [
            ("8.8.4.4", 53),  # Google DNS
            ("8.8.8.8", 53),  # Google DNS альтернативный
            ("1.1.1.1", 53),  # Cloudflare DNS
            ("208.67.222.222", 53),  # OpenDNS
        ]
        
        for target_host, target_port in test_targets:
            try:
                req = struct.pack("!BBB", 0x05, 0x01, 0x00) + struct.pack("!B", 0x01) + socket.inet_aton(target_host) + struct.pack("!H", target_port)
                start_internet = time.time()
                s.sendall(req)
                # Устанавливаем короткий таймаут для проверки интернета
                s.settimeout(2.0)
                resp = s.recv(4)
                if len(resp) >= 4:
                    ver_r, rep, rsv, atyp_r = struct.unpack("!BBBB", resp)
                    if ver_r == 0x05 and rep == 0x00:
                        internet_ping = (time.time() - start_internet) * 1000.0
                        break  # Успешно, выходим из цикла
            except socket.timeout:
                continue  # Пробуем следующий сервер
            except Exception:
                continue  # Пробуем следующий сервер
        
        return True, conn_ms, internet_ping
    except Exception as e:
        logger.debug("SOCKS5 health check failed for %s:%s : %s", socks_host, socks_port, e)
        return False, None, None
    finally:
        try:
            if s:
                s.close()
        except Exception:
            pass

def socks5_health_monitor_all(backends, socks_user=None, socks_pass=None):
    global _socks5_available, _socks5_last_check
    # per-backend backoff state
    backoff = {b: 1.0 for b in backends}
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(8, max(1, len(backends)))) as executor:
        while not shutdown_event.is_set():
            current_time = time.time()
            tasks = {}
            for backend in backends:
                host, port = backend
                # schedule check — note: our check returns (healthy, response_ms, internet_ping)
                tasks[executor.submit(check_socks5_health, host, port, socks_user, socks_pass, timeout=5)] = backend
            
            any_healthy_with_internet = False  # Изменено: только с интернетом
            any_healthy = False  # Любые здоровые
            
            for fut in concurrent.futures.as_completed(tasks, timeout=_socks5_check_interval):
                backend = tasks[fut]
                try:
                    healthy, server_ping, internet_ping = fut.result()
                except Exception:
                    healthy, server_ping, internet_ping = False, None, None
                    
                with _backend_lock:
                    _backend_status[backend] = healthy
                    _backend_extended_status[backend] = {
                        'healthy': healthy,
                        'server_ping': server_ping,
                        'internet_ping': internet_ping,
                        'last_check': current_time,
                        'has_internet': internet_ping is not None  # Новое поле
                    }
                    
                # update backend stats (latency)
                _set_backend_response_ms(backend, server_ping)
                if healthy:
                    any_healthy = True
                    if internet_ping is not None:  # Только если есть интернет
                        any_healthy_with_internet = True
                        backoff[backend] = 1.0
                    else:
                        backoff[backend] = min(60.0, backoff.get(backend, 1.0) * 2.0)
                else:
                    backoff[backend] = min(60.0, backoff.get(backend, 1.0) * 2.0)
                    
            with _socks5_lock:
                old_status = _socks5_available
                # Используем только серверы с интернетом как доступные
                _socks5_available = any_healthy_with_internet
                _socks5_last_check = current_time
                
                if not old_status and any_healthy_with_internet:
                    logger.info("At least one SOCKS5 backend with internet recovered")
                    with _stats_lock:
                        _stats['socks5_recovered_count'] += 1
                    try:
                        _force_reproxy_on_socks_recovery()
                    except Exception:
                        logger.debug('SOCKS recovery re-proxy action failed', exc_info=True)
                elif old_status and not any_healthy_with_internet:
                    logger.warning("All SOCKS5 backends have no internet, bypass enabled")
                    with _stats_lock:
                        _stats['socks5_bypass_count'] += 1
                        
            # sleep small increment but respect per-backend backoff
            if shutdown_event.wait(1.0):
                break

def is_socks5_available():
    with _socks5_lock:
        # Проверяем, есть ли хотя бы один "зеленый" сервер
        with _backend_lock:
            for backend in _socks5_backends:
                extended_info = _backend_extended_status.get(backend, {})
                if (_backend_status.get(backend, True) and 
                    extended_info.get('healthy', False) and 
                    extended_info.get('internet_ping') is not None):
                    return True
        return False

def _env_bool(name: str, default: bool = False) -> bool:
    v = os.getenv(name)
    if v is None:
        return default
    return str(v).strip().lower() not in ("0", "false", "no", "off", "")

def _load_traffic_rules():
    """Load optional traffic rules from TRAFFIC_RULES (JSON).
    If unset/invalid -> returns empty list and keeps default behavior."
    """
    global _TRAFFIC_RULES_RAW, _TRAFFIC_RULES_PARSED
    raw = os.getenv("TRAFFIC_RULES", "")
    with _TRAFFIC_RULES_LOCK:
        if raw == (_TRAFFIC_RULES_RAW or "") and _TRAFFIC_RULES_PARSED is not None:
            return _TRAFFIC_RULES_PARSED
        _TRAFFIC_RULES_RAW = raw
        if not raw.strip():
            _TRAFFIC_RULES_PARSED = []
            return _TRAFFIC_RULES_PARSED
        try:
            data = json.loads(raw)
            if isinstance(data, dict) and "rules" in data:
                data = data["rules"]
            if not isinstance(data, list):
                _TRAFFIC_RULES_PARSED = []
                return _TRAFFIC_RULES_PARSED
            # Normalize
            norm = []
            for r in data:
                if not isinstance(r, dict):
                    continue
                when = r.get("when") or {}
                action = (r.get("action") or "").strip().lower()
                if action not in ("socks", "direct", "drop", "reset", "wait"):
                    continue
                norm.append({"when": when, "action": action, "log": bool(r.get("log", False))})
            _TRAFFIC_RULES_PARSED = norm
            return _TRAFFIC_RULES_PARSED
        except Exception:
            _TRAFFIC_RULES_PARSED = []
            return _TRAFFIC_RULES_PARSED

def _rule_match(rule_when: dict, *, proto: str, host: str, port: int, socks_available: bool, is_udp: bool) -> bool:
    try:
        if not isinstance(rule_when, dict):
            return False
        # proto
        rp = rule_when.get("proto")
        if rp and str(rp).lower() not in ("any", proto):
            return False
        # udp/tcp
        ru = rule_when.get("is_udp")
        if ru is not None:
            if bool(ru) != bool(is_udp):
                return False
        # socks availability
        rsa = rule_when.get("socks_available")
        if rsa is not None:
            if bool(rsa) != bool(socks_available):
                return False
        # port
        if "port" in rule_when:
            try:
                if int(rule_when["port"]) != int(port):
                    return False
            except Exception:
                return False
        pr = rule_when.get("port_range")
        if pr:
            try:
                a,b = str(pr).split("-", 1)
                lo, hi = int(a), int(b)
                if not (lo <= int(port) <= hi):
                    return False
            except Exception:
                return False
        # host regex
        hr = rule_when.get("host_regex")
        if hr:
            try:
                if not re.search(str(hr), str(host or ""), flags=re.IGNORECASE):
                    return False
            except Exception:
                return False
        return True
    except Exception:
        return False

def decide_traffic_action(proto: str, host: str, port: int, *, socks_available: bool, is_udp: bool=False):
    """Return one of: None|socks|direct|drop|reset|wait.
    Default (no rules): None => keep existing logic unchanged.
    """
    rules = _load_traffic_rules()
    if not rules:
        return None
    for r in rules:
        when = r.get("when") or {}
        if _rule_match(when, proto=proto, host=host, port=port, socks_available=socks_available, is_udp=is_udp):
            return r.get("action")
    return None

def _wait_for_socks_recovery(max_wait_s: float = 5.0, poll_s: float = 0.25) -> bool:
    deadline = time.time() + max(0.0, float(max_wait_s))
    while time.time() < deadline and not shutdown_event.is_set():
        if is_socks5_available():
            return True
        time.sleep(max(0.05, float(poll_s)))
    return is_socks5_available()

def _force_reproxy_on_socks_recovery():
    """When SOCKS comes back, forcibly close 'direct fallback' conns/sessions
    so clients reconnect and start going through SOCKS again.
    """
    global _last_socks_recovery_ts
    if not _env_bool("FORCE_REPROXY_ON_SOCKS_RECOVERY", True):
        return

    now = time.time()
    _last_socks_recovery_ts = now

    killed = 0
    # Close TCP direct-fallback connections
    try:
        with _conns_lock:
            for cid, info in list(_conns.items()):
                try:
                    # direct-fallback: backend is None but upstream exists
                    is_direct = bool(info.get("use_direct")) or (info.get("backend") is None and info.get("upstream_sock_ref") is not None)
                    if not is_direct:
                        continue
                    if info.get("killed_by_ui") or info.get("killed_by_policy"):
                        continue
                    info["killed_by_policy"] = True
                    info["kill_reason"] = "socks_recovered"
                    cs = info.get("client_sock_ref")
                    us = info.get("upstream_sock_ref")
                    try:
                        graceful_close(cs)
                    except Exception:
                        pass
                    try:
                        graceful_close(us)
                    except Exception:
                        pass
                    killed += 1
                except Exception:
                    continue
    except Exception:
        pass

    # Close UDP direct sessions
    try:
        with _udp_sessions_lock:
            for s in list(_udp_sessions_registry):
                try:
                    if getattr(s, "alive", False) and getattr(s, "use_direct", False):
                        try:
                            s.close()
                        except Exception:
                            pass
                except Exception:
                    continue
    except Exception:
        pass

    if killed:
        with _stats_lock:
            _stats["killed_on_socks_recovery_count"] = _stats.get("killed_on_socks_recovery_count", 0) + killed
        logger.info("SOCKS recovered: closed %d direct-fallback TCP connections to force re-proxy", killed)
def select_socks_backend():
    global _backend_idx
    with _backend_lock:
        n = len(_socks5_backends)
        if n == 0:
            return None, None
        
        start = _backend_idx % n
        # Сначала ищем только "зеленые" серверы (healthy=True и internet_ping != None)
        for i in range(n):
            idx = (start + i) % n
            candidate = _socks5_backends[idx]
            extended_info = _backend_extended_status.get(candidate, {})
            
            # "Зеленый" сервер: здоровый и есть интернет-соединение
            if (_backend_status.get(candidate, True) and 
                extended_info.get('healthy', False) and 
                extended_info.get('internet_ping') is not None):
                _backend_idx = (idx + 1) % n
                return candidate
        
        # Если нет "зеленых", ищем любые здоровые (включая "желтые")
        for i in range(n):
            idx = (start + i) % n
            candidate = _socks5_backends[idx]
            if _backend_status.get(candidate, True):
                _backend_idx = (idx + 1) % n
                return candidate
        
        # Если нет здоровых, fallback на round-robin (original behavior)
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
                _stats['errors_connection_timeout'] += 1
                _stats['errors'] = _stats.get('errors', 0) + 1
                _stats['connection_timeouts'] = _stats.get('connection_timeouts', 0) + 1
            logger.warning("Timeout in %s", func.__name__)
            raise
        except socket.error as e:
            with _stats_lock:
                _stats['errors_socket_error'] += 1
                _stats['errors'] = _stats.get('errors', 0) + 1
            logger.error("Socket error in %s: %s", func.__name__, e)
            raise
        except RuntimeError as e:
            if "handshake" in str(e).lower() or "auth" in str(e).lower() or "SOCKS" in str(e).upper():
                with _stats_lock:
                    _stats['errors_socks_handshake'] += 1
                    _stats['errors'] = _stats.get('errors', 0) + 1
                logger.error("SOCKS handshake error in %s: %s", func.__name__, e)
                raise
            else:
                with _stats_lock:
                    _stats['errors'] = _stats.get('errors', 0) + 1
                logger.error("Runtime error in %s: %s", func.__name__, e)
                raise
        except socket.gaierror:
            with _stats_lock:
                _stats['errors_dns'] += 1
                _stats['errors'] = _stats.get('errors', 0) + 1
                _stats['dns_failures'] = _stats.get('dns_failures', 0) + 1
            logger.error("DNS resolution error in %s", func.__name__)
            raise
        except Exception as e:
            if "auth" in str(e).lower():
                with _stats_lock:
                    _stats['errors_auth'] += 1
                    _stats['errors'] = _stats.get('errors', 0) + 1
                    _stats['auth_failures'] = _stats.get('auth_failures', 0) + 1
            else:
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
                    _stats['errors_auth'] += 1
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
        socks_avail = is_socks5_available()
        # Try to apply optional per-traffic policy (default: no rules => keep behavior)
        try:
            if self.fixed_target is not None:
                th, tp = self.fixed_target
                proto = classify_protocol(tp)
            else:
                th, tp = (self.socks_host or ""), 0
                proto = "other"
            action = decide_traffic_action(proto, th, int(tp or 0), socks_available=socks_avail, is_udp=True)
        except Exception:
            action = None

        if action in ("drop", "reset"):
            # For UDP sessions, dropping means "do not create session"
            with _stats_lock:
                _stats["policy_dropped_udp_sessions"] = _stats.get("policy_dropped_udp_sessions", 0) + 1
            self.alive = False
            return

        if action == "direct":
            self.use_direct = True

        if action == "socks":
            self.use_direct = False
            if not socks_avail:
                req_policy = (os.getenv("SOCKS_REQUIRED_POLICY", "drop") or "drop").strip().lower()
                if req_policy == "wait":
                    max_wait = float(os.getenv("SOCKS_REQUIRED_MAX_WAIT", "5") or "5")
                    socks_avail = _wait_for_socks_recovery(max_wait_s=max_wait)
                elif req_policy == "direct":
                    self.use_direct = True
                    socks_avail = False
                if not socks_avail and not self.use_direct:
                    with _stats_lock:
                        _stats["policy_dropped_udp_sessions"] = _stats.get("policy_dropped_udp_sessions", 0) + 1
                    self.alive = False
                    return

        # Existing behavior: if SOCKS unavailable => direct UDP session
        if not socks_avail and action not in ("socks",):
            self.use_direct = True

        if self.use_direct:
            self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.udp.bind(("0.0.0.0", 0))
            self.udp.setblocking(False)
            self.alive = True
            with _udp_sessions_lock:
                _udp_sessions_registry.add(self)
            logger.debug("[%s] Created direct UDP session", self.client_addr)
            return

        try:
            # resolve socks host to IP (store socks_ip for relay fallback)
            if _enhanced_dns:
                socks_ip = _enhanced_dns.resolve_enhanced(self.socks_host)
            else:
                socks_ip = dns_cache.resolve(self.socks_host)
        except Exception:
            with _stats_lock:
                _stats['errors_dns'] += 1
                _stats['dns_failures'] = _stats.get('dns_failures', 0) + 1
            raise

        self.tcp = socket.create_connection((socks_ip, self.socks_port), timeout=self.timeout)
        # normal greeting / auth omitted for brevity — оставляем твой код здесь
        # ...
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
                    _stats['errors_auth'] += 1
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
                    _stats['errors_auth'] += 1
                    _stats['auth_failures'] = _stats.get('auth_failures', 0) + 1
                raise RuntimeError("auth failed")
        bnd_addr, bnd_port = socks5_udp_associate(self.tcp, timeout=self.timeout)

        # If server returns 0.0.0.0 as BND.ADDR (common) we should use the actual socks server IP.
        relay_host = bnd_addr
        if not relay_host or relay_host.startswith('0.') or relay_host == '0.0.0.0' or relay_host == '::' :
            relay_host = socks_ip

        self.relay_addr = (relay_host, int(bnd_port))

        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # bind to ephemeral port so server can associate responses to this client
        self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp.bind(("0.0.0.0", 0))
        
        # B — UDP: попытка получать TTL из входящих пакетов
        try:
            # попробовать включить получение TTL (Linux/BSD)
            if hasattr(socket, 'IP_RECVTTL'):
                self.udp.setsockopt(socket.IPPROTO_IP, socket.IP_RECVTTL, 1)
            elif hasattr(socket, 'IP_RECVTOS'):  # placeholder fallback, редко полезно
                self.udp.setsockopt(socket.IPPROTO_IP, socket.IP_RECVTOS, 1)
        except Exception:
            # не критично — просто продолжим без TTL
            logger.debug("Could not enable IP_RECVTTL on UDP socket (platform might not support it)")
        
        # optional: connect UDP socket to relay to filter incoming packets (makes recv simpler)
        try:
            self.udp.connect(self.relay_addr)
        except Exception:
            # connect may fail for some environments; continue without it
            pass
        self.udp.setblocking(False)
        self.alive = True
        with _udp_sessions_lock:
            _udp_sessions_registry.add(self)
        logger.debug("[%s] Created UDP session -> relay %s:%d (socks_ip=%s)", self.client_addr, self.relay_addr[0], self.relay_addr[1], socks_ip)

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

        logger.debug("[%s] UDP send -> %s:%d (via %s) %d bytes", self.client_addr, dst_host, dst_port, "direct" if self.use_direct else f"{self.relay_addr[0]}:{self.relay_addr[1]}", len(data))

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

    # B — заменяем recv_from_relay на версию с recvmsg и TTL
    def recv_from_relay(self):
        try:
            # Попробуем recvmsg (дает ancillary data с TTL на некоторых платформах)
            try:
                pkt, ancdata, flags, addr = self.udp.recvmsg(65535, 1024)
            except (AttributeError, ValueError):
                # recvmsg не поддерживается — fallback
                pkt, addr = self.udp.recvfrom(65535)
                ancdata = []
        except BlockingIOError:
            return None
        except OSError as e:
            logger.debug("udp recvfrom error: %s", e)
            return None

        # извлечь TTL из ancdata если есть
        ttl = None
        try:
            for c in ancdata:
                # c is typically (level, type, data)
                if len(c) >= 3:
                    level, typ, data = c[0], c[1], c[2]
                else:
                    level, typ = c[0], c[1]
                    data = None
                if level == socket.SOL_IP and hasattr(socket, 'IP_TTL') and typ == socket.IP_TTL:
                    try:
                        ttl = int.from_bytes(data, 'little') if isinstance(data, (bytes, bytearray)) else int(data)
                    except Exception:
                        try:
                            ttl = int(data)
                        except Exception:
                            ttl = None
                    break
        except Exception:
            ttl = None

        # Если мы используем direct (не через socks), payload — чистый
        if self.use_direct:
            src_host, src_port = addr
            payload = pkt
            self.last_activity = time.time()
            with _stats_lock:
                _stats['bytes_udp_r2c'] += len(payload)
            # update ttl stats: attribute backend as direct addr (client side)
            if ttl is not None:
                try:
                    backend = (src_host, src_port)
                    _update_backend_ttl(backend, ttl)
                except Exception:
                    pass
            return src_host, src_port, payload

        # Если подключили UDP (connect) к relay, некоторые серверы присылают raw payload без SOCKS5-обертки
        try:
            src_host, src_port, payload = parse_socks5_udp_packet(pkt)
        except Exception as e:
            logger.debug("failed to parse socks5 udp pkt, falling back to raw payload (%s)", e)
            try:
                src_host, src_port = addr
                payload = pkt
            except Exception:
                return None

        self.last_activity = time.time()
        with _stats_lock:
            _stats['bytes_udp_r2c'] += len(payload)

        # update backend TTL sample if we know relay address
        try:
            relay_backend = self.relay_addr or (self.socks_host, self.socks_port)
            if ttl is not None and relay_backend:
                _update_backend_ttl(relay_backend, ttl)
        except Exception:
            pass

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
    buffer_size = cfg.get('buffer_size', 131072)  # Увеличен размер буфера до 128KB
    idle_timeout = cfg.get('idle_timeout', 300)
    last_activity = time.time()
    try:
        a.setblocking(True)
        b.setblocking(True)
    except Exception:
        pass
    
    # Добавлена проверка shutdown_event в цикл
    while not shutdown_event.is_set():
        timeout = 2.0  # Увеличен таймаут select до 2 секунд
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
                    backend_for_conn = None
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['bytes_c2r'] += len(data)
                            backend_for_conn = _conns[conn_id].get('backend')
                    # attribute bytes to backend if present
                    if backend_for_conn:
                        try:
                            _update_backend_bytes(backend_for_conn, len(data))
                        except Exception:
                            pass
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
                    backend_for_conn = None
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['bytes_r2c'] += len(data)
                            backend_for_conn = _conns[conn_id].get('backend')
                    if backend_for_conn:
                        try:
                            _update_backend_bytes(backend_for_conn, len(data))
                        except Exception:
                            pass
                    if protocol == 'http':
                        _stats['bytes_http_r2c'] += len(data)
                    elif protocol == 'https':
                        _stats['bytes_https_r2c'] += len(data)
                    elif protocol == 'dns':
                        _stats['bytes_dns_r2c'] += len(data)
                    else:
                        _stats['bytes_other_r2c'] += len(data)
    
    # Дополнительная проверка при выходе из цикла
    if shutdown_event.is_set():
        logger.debug("[%s] Shutdown event detected, closing connection", client_addr)

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
            chunk = socks_sock.recv(8192)  # Увеличен размер буфера чтения
            if not chunk:
                break
            resp_buf.extend(chunk)
            if b'\r\n\r\n' in resp_buf:
                break
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
                chunk = socks_sock.recv(min(131072, to_read))  # Увеличен размер буфера
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
                chunk = socks_sock.recv(131072)  # Увеличен размер буфера
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

def _get_host_display(proto, initial_request_bytes, target_host, target_port):
    """Получить отображаемое имя хоста для соединения"""
    host_display = None
    
    # Если HTTP и есть initial request bytes, попробуем получить Host header
    if proto == 'http' and initial_request_bytes:
        try:
            _, _, _, req_headers = parse_http_request_headers(initial_request_bytes)
            host_display = req_headers.get('host')
        except Exception:
            host_display = None
    
    # Если все еще не определили и target_host похож на IP, пробуем reverse DNS
    if not host_display:
        try:
            # Проверяем, является ли target_host IP адресом
            socket.inet_aton(target_host)
            is_ip = True
        except socket.error:
            is_ip = False
            
        if is_ip:
            try:
                # Неблокирующий reverse DNS с таймаутом
                def reverse_dns(ip):
                    try:
                        return socket.gethostbyaddr(ip)[0]
                    except Exception:
                        return None
                
                with ThreadPoolExecutor(max_workers=1) as executor:
                    future = executor.submit(reverse_dns, target_host)
                    host_display = future.result(timeout=0.5)
            except Exception:
                host_display = None
    
    # Fallback на target_host
    if not host_display:
        host_display = target_host
    
    return f"{host_display}:{target_port}"

def handle_client(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg):
    stats = {'bytes_client_to_remote': 0, 'bytes_remote_to_client': 0, 'start_time': time.time()}
    conn_id = f"{client_addr[0]}:{client_addr[1]}_{threading.get_ident()}"
    initial_request_bytes = None
    target_host = None
    target_port = None
    proto = None
    host_display = None
    
    with sem:
        with _stats_lock:
            _stats['active_connections'] += 1
            _stats['total_connections'] += 1
        with _conns_lock:
            _conns[conn_id] = {
                'client': f"{client_addr[0]}:{client_addr[1]}",
                'start_time': stats['start_time'],
                'bytes_c2r': 0,
                'bytes_r2c': 0,
                'backend': None,  # will be populated after select_socks_backend / chosen_backend
                'client_sock_ref': client_sock,  # Reference for killing connections
                'upstream_sock_ref': None,  # Will be set later
                'host_display': None,  # Will be set later
                'killed_by_ui': False
            }
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
                        _inc_error()
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
            
            # Получаем отображаемое имя хоста
            if proto == 'http':
                try:
                    client_sock.settimeout(0.5)
                    rv = bytearray()
                    while b'\r\n\r\n' not in rv and len(rv) < 16384:  # Увеличен размер буфера
                        chunk = client_sock.recv(8192)  # Увеличен размер буфера
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
            
            # Обновляем host_display в _conns
            host_display = _get_host_display(proto, initial_request_bytes, target_host, target_port)
            with _conns_lock:
                if conn_id in _conns:
                    _conns[conn_id]['host_display'] = host_display
            
            socks_avail = is_socks5_available()
            action = decide_traffic_action(proto, target_host, target_port, socks_available=socks_avail, is_udp=False)

            # Optional: what to do when all SOCKS backends are down (default keeps current behavior)
            if action is None and not socks_avail:
                down_policy = (os.getenv("ALL_SOCKS_DOWN_POLICY", "direct") or "direct").strip().lower()
                if down_policy == "drop":
                    with _stats_lock:
                        _stats["policy_dropped_connections"] = _stats.get("policy_dropped_connections", 0) + 1
                    graceful_close(client_sock)
                    return
                if down_policy == "wait":
                    max_wait = float(os.getenv("ALL_SOCKS_DOWN_MAX_WAIT", "5") or "5")
                    if _wait_for_socks_recovery(max_wait_s=max_wait):
                        socks_avail = True

            # Apply explicit traffic rule decision (if any)
            if action in ("drop", "reset"):
                with _stats_lock:
                    _stats["policy_dropped_connections"] = _stats.get("policy_dropped_connections", 0) + 1
                if action == "reset":
                    try:
                        client_sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                    except Exception:
                        pass
                graceful_close(client_sock)
                return

            if action == "direct":
                use_direct = True

            if action == "socks":
                use_direct = False
                if not socks_avail:
                    req_policy = (os.getenv("SOCKS_REQUIRED_POLICY", "drop") or "drop").strip().lower()
                    if req_policy == "wait":
                        max_wait = float(os.getenv("SOCKS_REQUIRED_MAX_WAIT", "5") or "5")
                        socks_avail = _wait_for_socks_recovery(max_wait_s=max_wait)
                    elif req_policy == "direct":
                        socks_avail = False  # keep False, we'll switch to direct below
                        use_direct = True
                    # drop by default if still unavailable
                    if not socks_avail and not use_direct:
                        with _stats_lock:
                            _stats["policy_dropped_connections"] = _stats.get("policy_dropped_connections", 0) + 1
                        graceful_close(client_sock)
                        return

            # Existing behavior: if SOCKS unavailable => direct fallback
            if not socks_avail and action not in ("socks",):
                use_direct = True
                with _stats_lock:
                    _stats['direct_connections'] += 1
                logger.info("[%s] Using direct connection (SOCKS5 unavailable)", client_addr)

            # Store chosen mode for UI/management
            try:
                with _conns_lock:
                    if conn_id in _conns:
                        _conns[conn_id]['use_direct'] = bool(use_direct)
                        _conns[conn_id]['policy_action'] = action
            except Exception:
                pass
            
            if use_direct:
                max_retries = cfg.get('connect_retries', 2)  # Уменьшено количество ретраев
                backoff_base = cfg.get('retry_backoff', 1.0)  # Увеличен базовый backoff
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
                        _inc_error()
                    graceful_close(client_sock)
                    return
            else:
                if socks_host is None or socks_port is None:
                    chosen = select_socks_backend()
                    if chosen == (None, None):
                        logger.error("[%s] No SOCKS backends configured", client_addr)
                        with _stats_lock:
                            _inc_error()
                        graceful_close(client_sock)
                        return
                    socks_host, socks_port = chosen
                
                # record which backend is associated with this connection (for per-backend stats)
                try:
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['backend'] = (socks_host, socks_port) if socks_host and socks_port else None
                except Exception:
                    pass
                
                max_retries = cfg.get('connect_retries', 2)  # Уменьшено количество ретраев
                backoff_base = cfg.get('retry_backoff', 1.0)  # Увеличен базовый backoff
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
                        _inc_error()
                    graceful_close(socks_sock)
                    graceful_close(client_sock)
                    return
                try:
                    socks5_connect_via(socks_sock, target_host, target_port, username=socks_user, password=socks_pass, timeout=cfg.get('connect_timeout'))
                except Exception as e:
                    logger.error("[%s] SOCKS5 handshake/CONNECT failed: %s", client_addr, e)
                    with _stats_lock:
                        _inc_error()
                    graceful_close(socks_sock)
                    graceful_close(client_sock)
                    return
            
            # Обновляем upstream_sock_ref
            with _conns_lock:
                if conn_id in _conns:
                    _conns[conn_id]['upstream_sock_ref'] = socks_sock
            
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
                _inc_error()
        finally:
            # Проверяем, не было ли соединение убито через UI/политику (чтобы избежать двойного close)
            killed = False
            kill_reason = None
            with _conns_lock:
                info = _conns.get(conn_id)
                if info:
                    killed = bool(info.get('killed_by_ui', False) or info.get('killed_by_policy', False))
                    kill_reason = info.get('kill_reason')

            duration = time.time() - stats['start_time']

            if not killed:
                graceful_close(client_sock)
                if socks_sock:
                    graceful_close(socks_sock)

            with _stats_lock:
                _stats['active_connections'] = max(0, _stats.get('active_connections', 0) - 1)

            with _conns_lock:
                _conns.pop(conn_id, None)

            connection_type = "direct" if use_direct else "SOCKS5"
            if killed:
                logger.info("[%s] closed (%s) — killed_by=%s, duration: %.2fs, c->r: %d bytes, r->c: %d bytes, host: %s",
                           client_addr, connection_type, kill_reason or "unknown", duration,
                           stats['bytes_client_to_remote'], stats['bytes_remote_to_client'], host_display or "unknown")
            else:
                logger.info("[%s] closed (%s) — duration: %.2fs, c->r: %d bytes, r->c: %d bytes, host: %s",
                           client_addr, connection_type, duration,
                           stats['bytes_client_to_remote'], stats['bytes_remote_to_client'], host_display or "unknown")
def udp_server_loop(listen_addr: str, listen_port: int, socks_host: str, socks_port: int, socks_user: str, socks_pass: str, fixed_target, cfg):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((listen_addr, listen_port))
    sock.setblocking(False)
    logger.info("UDP listening on %s:%d", listen_addr, listen_port)
    sessions = {}
    sessions_lock = threading.Lock()
    session_timeout = cfg.get('udp_session_timeout', 125)  # Увеличен таймаут сессии с 120 до 125 (+5s)
    def cleaner():
        while not shutdown_event.is_set():
            time.sleep(max(1, session_timeout))  # Увеличен интервал очистки
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
            data, client_addr = sock.recvfrom(cfg.get('udp_buffer_size', 131072))  # Увеличен размер буфера
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
                        _inc_error()
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
                        logger.info("Created UDP session for %s -> %s (%s) relay=%s:%d", client_addr, f"{dst_host}:{dst_port}", session_type, s.relay_addr[0], s.relay_addr[1] if s.relay_addr else -1)
                    except Exception as e:
                        logger.error("Failed to create UDP session for %s: %s", client_addr, e)
                        with _stats_lock:
                            _inc_error()
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

def stats_reporter(interval=65):  # Увеличен интервал до 65 секунд (+5s)
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
                errs_timeout = _stats.get('errors_connection_timeout', 0)
                errs_socket = _stats.get('errors_socket_error', 0)
                errs_socks = _stats.get('errors_socks_handshake', 0)
                errs_dns = _stats.get('errors_dns', 0)
                errs_auth = _stats.get('errors_auth', 0)
                to = _stats.get('connection_timeouts', 0)
                bypass = _stats.get('socks5_bypass_count', 0)
                recovered = _stats.get('socks5_recovered_count', 0)
                direct = _stats.get('direct_connections', 0)
                http2_conns = _stats.get('http2_connections', 0)
                doh_queries = _stats.get('doh_queries', 0)
                cache_hits = _stats.get('enhanced_cache_hits', 0)
            socks5_status = "available" if is_socks5_available() else "unavailable"
            logger.info("STATS active=%s total_conn=%s tcp_bytes_c2r=%s tcp_bytes_r2c=%s udp_sessions=%s udp_c2r=%s udp_r2c=%s errors=%s (timeout=%s,socket=%s,socks=%s,dns=%s,auth=%s) timeouts=%s socks5_status=%s bypass_count=%s recovered_count=%s direct_connections=%s http2_connections=%s doh_queries=%s enhanced_cache_hits=%s", 
                       ac, tc, bc2r, br2c, us, u_c2r, u_r2c, errs, errs_timeout, errs_socket, errs_socks, errs_dns, errs_auth, to, socks5_status, bypass, recovered, direct, http2_conns, doh_queries, cache_hits)
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
    
    # Add system metrics if available
    if PSUTIL_AVAILABLE:
        with _system_stats_lock:
            system_snapshot = dict(_system_stats)
        for key, value in system_snapshot.items():
            if isinstance(value, (int, float)):
                name = f"t2s_system_{key}"
                metrics.append(f"# HELP {name} system metric")
                metrics.append(f"# TYPE {name} gauge")
                metrics.append(f"{name} {value}")
            elif isinstance(value, dict):
                for subkey, subvalue in value.items():
                    if isinstance(subvalue, (int, float)):
                        name = f"t2s_system_{key}_{subkey}"
                        metrics.append(f"# HELP {name} system metric")
                        metrics.append(f"# TYPE {name} gauge")
                        metrics.append(f"{name} {subvalue}")
    
    return "\n".join(metrics)

class EnhancedStatsHandler(BaseHTTPRequestHandler):
    server_version = "t2s-enhanced-stats/1.0"
    
    def _get_socks5_backends_status(self):
        status_info = []
        with _backend_lock:
            b_list = list(_socks5_backends)
        # compute which backend has most bytes (mark as in_use)
        top_backend = None
        top_bytes = 0
        with _backend_stats_lock:
            for b in b_list:
                st = _backend_stats.get(b)
                if st and st.get('total_bytes', 0) > top_bytes:
                    top_bytes = st.get('total_bytes', 0)
                    top_backend = b
        with _backend_lock:
            for backend in b_list:
                host, port = backend
                healthy = _backend_status.get(backend, False)
                extended_info = _backend_extended_status.get(backend, {})
                server_ping = extended_info.get('server_ping')
                internet_ping = extended_info.get('internet_ping')
                last_check = extended_info.get('last_check', 0)
                
                # Определение цвета статуса
                if not healthy:
                    status_color = "black"  # Сервер не доступен
                elif internet_ping is None:
                    status_color = "yellow"  # Сервер жив, но интернет недоступен
                else:
                    status_color = "green"  # Все доступно
                
                # C — Конвертация RTT → процент «health» и добавление поля `ttl_integrity`
                ttl_integrity = None
                total_bytes = 0
                with _backend_stats_lock:
                    st = _backend_stats.get(backend)
                    if st:
                        ttl_integrity = st.get('ttl_integrity')
                        total_bytes = st.get('total_bytes', 0)

                # rtt -> percent: 0..max_rtt mapped to 100..0
                rtt_pct = None
                try:
                    if server_ping:
                        max_good = 200.0  # ms — порог «хорошего» RTT
                        rtt_pct = max(0.0, min(100.0, (1.0 - (server_ping / max_good)) * 100.0))
                except Exception:
                    rtt_pct = None
                    
                status_info.append({
                    'host': host,
                    'port': port,
                    'healthy': healthy,
                    'status_color': status_color,
                    'server_ping': f"{server_ping:.0f} ms" if server_ping else "N/A",
                    'internet_ping': f"{internet_ping:.0f} ms" if internet_ping else "N/A",
                    'last_check': last_check,
                    'in_use': (backend == top_backend),
                    'ttl_integrity_percent': f"{int(ttl_integrity)}%" if ttl_integrity is not None else "N/A",
                    'rtt_health_percent': f"{int(rtt_pct)}%" if rtt_pct is not None else "N/A",
                    'total_bytes': total_bytes,
                    'traffic': self._format_bytes(total_bytes)
                })
        return status_info
        
    def _get_backend_response_time(self, backend):
        try:
            with _backend_stats_lock:
                st = _backend_stats.get(backend)
                if not st:
                    return "N/A"
                speed = st.get('ema_speed') or st.get('speed_bps') or 0.0
                latency_ms = st.get('last_response_ms')
                if speed is None or speed <= 0:
                    speed_str = "0 KB/s"
                else:
                    if speed > 2 * 1024 * 1024:
                        speed_str = f"{speed / (1024*1024):.2f} MB/s"
                    else:
                        speed_str = f"{speed / 1024:.1f} KB/s"
                if latency_ms:
                    return f"{speed_str} ({int(latency_ms)} ms)"
                return speed_str
        except Exception:
            return "N/A"
        
    def _write_json(self, obj, code=200):
        if ORJSON_AVAILABLE:
            b = orjson.dumps(obj)
        else:
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
                if PROMETHEUS_AVAILABLE:
                    body = generate_latest()
                else:
                    body = export_prometheus_metrics().encode('utf-8')
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
                        # Только серверы с интернетом считаются готовыми
                        ok = any(_backend_extended_status.get(b, {}).get('internet_ping') is not None 
                                for b in bkl if _backend_status.get(b, False))
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
        if self.path == '/debug/system':
            with _system_stats_lock:
                system_snapshot = dict(_system_stats)
            self._write_json(system_snapshot, code=200)
            return
        
        # D — SSE endpoint `/events` (реальное время)
        if self.path == '/events':
            # Server-Sent Events: stream JSON updates
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Connection', 'keep-alive')
            self.end_headers()
            try:
                # короткая функция для snapshot
                def make_snapshot():
                    with _stats_lock:
                        s = dict(_stats)
                    total_speed_bps = 0.0
                    backend_speed_sum_bps = 0.0
                    with _backend_stats_lock:
                        bs = {}
                        for b, st in _backend_stats.items():
                            speed = st.get('ema_speed') or st.get('speed_bps') or 0.0
                            backend_speed_sum_bps += speed
                            bs[f"{b[0]}:{b[1]}"] = {
                                'speed_bps': speed,
                                'rtt_ms': st.get('last_response_ms'),
                                'ttl_integrity': int(st.get('ttl_integrity', 0)) if st.get('ttl_integrity') is not None else None,
                                'total_bytes': st.get('total_bytes', 0),
                            }
                    with _system_stats_lock:
                        sys = dict(_system_stats)
                    # compute overall throughput from total bytes delta (more accurate than per-backend EMA)
                    try:
                        now_ts = time.time()
                        with _throughput_lock:
                            last_ts = float(_throughput_state.get('last_ts', now_ts) or now_ts)
                            last_total = int(_throughput_state.get('last_total_bytes', 0) or 0)
                            current_total = int(s.get('total_bytes_client_to_remote', 0) or 0) + int(s.get('total_bytes_remote_to_client', 0) or 0)
                            dt = now_ts - last_ts
                            if dt <= 0:
                                total_speed_bps = float(backend_speed_sum_bps)
                            else:
                                total_speed_bps = float(current_total - last_total) / float(dt)
                            _throughput_state['last_ts'] = now_ts
                            _throughput_state['last_total_bytes'] = current_total
                    except Exception:
                        total_speed_bps = float(backend_speed_sum_bps)
                    return {'ts': time.time(), 'stats': s, 'backends': bs, 'system': sys, 'total_speed_bps': total_speed_bps}

                # stream loop
                while not shutdown_event.is_set():
                    snap = make_snapshot()
                    payload = json.dumps(snap, default=str).replace('\n', '\\n')
                    try:
                        self.wfile.write(f"data: {payload}\n\n".encode('utf-8'))
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        break
                    # sleep SSE interval (global)
                    time.sleep(_SSE_INTERVAL)
            except Exception:
                logger.debug("SSE /events connection closed")
            return
        
        if self.path not in ('/', '/index.html'):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'Not found')
            return
        self._send_enhanced_html()
        
    def do_POST(self):
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
        
        if self.path == '/api/conn/kill':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length) if length else b''
            try:
                data = json.loads(body.decode('utf-8'))
                conn_id = data.get('conn_id')
                if not conn_id:
                    return self._write_json({'error': 'conn_id required'}, code=400)
                with _conns_lock:
                    info = _conns.get(conn_id)
                    if not info:
                        return self._write_json({'error': 'not found'}, code=404)
                    # close sockets if present
                    cs = info.get('client_sock_ref')
                    us = info.get('upstream_sock_ref')
                    # Mark as killed by UI
                    info['killed_by_ui'] = True
                    info['kill_reason'] = 'ui'
                    info['killed_at'] = time.time()
                    try:
                        graceful_close(cs)
                    except Exception:
                        pass
                    try:
                        graceful_close(us)
                    except Exception:
                        pass
                    # mark as closed (remove)
                    # Do not remove conn here; connection thread will clean up
                return self._write_json({'result': 'ok'})
            except Exception as e:
                return self._write_json({'error': str(e)}, code=500)
        
        elif self.path == '/api/backends/add':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length) if length else b''
            try:
                data = json.loads(body.decode('utf-8'))
                host = data.get('host')
                port = int(data.get('port', 0))
                if not host or not port:
                    return self._write_json({'error': 'host/port required'}, 400)
                backend = (host, port)
                with _backend_lock:
                    if backend not in _socks5_backends:
                        _socks5_backends.append(backend)
                        _backend_status[backend] = True
                        _backend_extended_status[backend] = {'healthy': False, 'server_ping': None, 'internet_ping': None, 'last_check': 0}
                return self._write_json({'result': 'ok'})
            except Exception as e:
                return self._write_json({'error': str(e)}, code=500)
        
        elif self.path == '/api/backends/remove':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length) if length else b''
            try:
                data = json.loads(body.decode('utf-8'))
                host = data.get('host')
                port = int(data.get('port', 0))
                if not host or not port:
                    return self._write_json({'error': 'host/port required'}, 400)
                backend = (host, port)
                with _backend_lock:
                    if backend in _socks5_backends:
                        _socks5_backends.remove(backend)
                with _backend_stats_lock:
                    _backend_stats.pop(backend, None)
                with _backend_lock:
                    _backend_status.pop(backend, None)
                    _backend_extended_status.pop(backend, None)
                return self._write_json({'result': 'ok'})
            except Exception as e:
                return self._write_json({'error': str(e)}, code=500)
        
        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'Not found')
        
    def _send_enhanced_html(self):
        with _stats_lock:
            stats_snapshot = dict(_stats)
        with _conns_lock:
            conns_snapshot = dict(_conns)
        with _system_stats_lock:
            system_snapshot = dict(_system_stats)
            
        socks5_status = self._get_socks5_backends_status()
        
        # Calculate additional stats
        uptime_seconds = time.time() - START_TIME
        uptime_str = self._format_uptime(uptime_seconds)
        total_traffic = (stats_snapshot.get('total_bytes_client_to_remote', 0) + 
                        stats_snapshot.get('total_bytes_remote_to_client', 0))
        total_traffic_str = self._format_bytes(total_traffic)
        
        html = []
        html.append("<!DOCTYPE html>")
        html.append("<html lang='en'><head><meta charset='utf-8'>")
        html.append("<title>Transparent SOCKS5 Proxy - Enhanced Dashboard</title>")
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
        html.append('<meta http-equiv="refresh" content="30">')  # Увеличен интервал обновления
        
        # CSS Styles with dark/light theme support and improved layout
        html.append("""
        <style>
        :root {
            --bg-primary: #f8f9fa;
            --bg-secondary: #ffffff;
            --bg-card: #ffffff;
            --text-primary: #212529;
            --text-secondary: #6c757d;
            --border-color: #dee2e6;
            --success: #28a745;
            --danger: #dc3545;
            --warning: #ffc107;
            --info: #17a2b8;
            --primary: #007bff;
            --shadow: rgba(0,0,0,0.1);
        }

        [data-theme='dark'] {
            --bg-primary: #121212;
            --bg-secondary: #1e1e1e;
            --bg-card: #2d2d2d;
            --text-primary: #e9ecef;
            --text-secondary: #adb5bd;
            --border-color: #495057;
            --shadow: rgba(0,0,0,0.3);
        }

        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: 'Roboto', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            line-height: 1.6;
            transition: all 0.3s ease;
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px;
        }

        .proxy-card {
            background: var(--bg-card);
            padding: 1.25rem;
            border-radius: 12px;
            box-shadow: 0 4px 6px var(--shadow);
            margin-bottom: 1.25rem;
            border: 1px solid var(--border-color);
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 1rem;
        }

        .proxy-card .left {
            flex: 1 1 auto;
        }

        .proxy-title {
            color: var(--primary);
            font-weight: 700;
            font-size: 1.4rem;
            margin-bottom: 0.25rem;
        }

        .proxy-sub {
            color: var(--text-secondary);
            font-size: 0.95rem;
        }

        .proxy-actions {
            display: flex;
            gap: 0.5rem;
            align-items: center;
        }

        .btn {
            background: var(--primary);
            color: white;
            border: none;
            padding: 0.5rem 0.75rem;
            border-radius: 8px;
            cursor: pointer;
            font-weight: 600;
            box-shadow: 0 2px 4px var(--shadow);
            transition: all 0.2s ease;
        }
        
        .btn:hover {
            opacity: 0.9;
            transform: translateY(-1px);
        }
        
        .btn:active {
            transform: translateY(0);
        }

        .btn.secondary {
            background: transparent;
            color: var(--primary);
            border: 1px solid var(--primary);
        }
        
        .btn.danger {
            background: var(--danger);
            color: white;
            border: none;
            padding: 0.25rem 0.5rem;
            font-size: 0.85rem;
        }
        
        .btn.small {
            padding: 0.25rem 0.5rem;
            font-size: 0.85rem;
        }

        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
            gap: 1rem;
            margin-bottom: 1.5rem;
        }

        .stat-card {
            background: var(--bg-card);
            padding: 1rem;
            border-radius: 12px;
            box-shadow: 0 2px 4px var(--shadow);
            border: 1px solid var(--border-color);
            transition: transform 0.12s ease;
        }

        .stat-card:hover { transform: translateY(-2px); }

        .stat-card h3 { color: var(--text-secondary); font-size: 0.85rem; margin-bottom: 0.5rem; }
        .stat-value { font-size: 1.6rem; font-weight: 700; color: var(--primary); margin-bottom: 0.25rem; }
        .stat-desc { color: var(--text-secondary); font-size: 0.85rem; }

        .section {
            background: var(--bg-card);
            padding: 1.25rem;
            border-radius: 12px;
            box-shadow: 0 4px 6px var(--shadow);
            margin-bottom: 1.25rem;
            border: 1px solid var(--border-color);
        }

        .section h2 { color: var(--text-primary); margin-bottom: 1rem; font-weight: 600; font-size: 1.15rem; }

        .table-wrap { overflow-x: auto; }

        table { width: 100%; border-collapse: collapse; background: var(--bg-card); min-width: 0; }
        th, td { padding: 0.75rem; text-align: left; border-bottom: 1px solid var(--border-color); }
        th { background: var(--bg-secondary); color: var(--text-primary); font-weight: 600; font-size: 0.8rem; text-transform: uppercase; }
        td { color: var(--text-primary); font-size: 0.95rem; }

        .status-circle {
            display: inline-block;
            width: 12px;
            height: 12px;
            border-radius: 50%;
            margin-right: 6px;
        }
        .status-green { background-color: #28a745; }
        .status-yellow { background-color: #ffc107; }
        .status-black { background-color: #212529; }
        
        .status-healthy { color: var(--success); font-weight: 600; }
        .status-unhealthy { color: var(--danger); font-weight: 600; }

        .status-in-use { background: var(--success); color: white; padding: 0.25rem 0.5rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }

        .protocol-badge { display: inline-block; padding: 0.25rem 0.6rem; border-radius: 20px; font-size: 0.8rem; font-weight: 600; margin-right: 0.5rem; }
        .badge-http { background: #28a745; color: white; }
        .badge-https { background: #007bff; color: white; }
        .badge-dns { background: #6f42c1; color: white; }
        .badge-other { background: #6c757d; color: white; }

        .connection-row:hover { background: var(--bg-secondary); }

        .nav-links { display: flex; gap: 0.75rem; margin-top: 0.5rem; flex-wrap: wrap; }
        .nav-link { color: var(--primary); text-decoration: none; padding: 0.45rem 0.75rem; border: 1px solid var(--primary); border-radius: 6px; }
        .nav-link:hover { background: var(--primary); color: white; }
        
        .form-group {
            margin-bottom: 1rem;
        }
        
        .form-label {
            display: block;
            margin-bottom: 0.5rem;
            color: var(--text-primary);
            font-weight: 600;
        }
        
        .form-input {
            width: 100%;
            padding: 0.5rem;
            border: 1px solid var(--border-color);
            border-radius: 6px;
            background: var(--bg-secondary);
            color: var(--text-primary);
        }
        
        .form-row {
            display: flex;
            gap: 1rem;
            margin-bottom: 1rem;
        }
        
        .form-col {
            flex: 1;
        }
        
        .error-message {
            color: var(--danger);
            background: rgba(220, 53, 69, 0.1);
            padding: 0.75rem;
            border-radius: 6px;
            margin-bottom: 1rem;
            border-left: 4px solid var(--danger);
        }
        
        .success-message {
            color: var(--success);
            background: rgba(40, 167, 69, 0.1);
            padding: 0.75rem;
            border-radius: 6px;
            margin-bottom: 1rem;
            border-left: 4px solid var(--success);
        }
        
        .backend-actions {
            display: flex;
            gap: 0.25rem;
        }

        @media (max-width: 768px) {
            .container { padding: 12px; }
            .proxy-card { flex-direction: column; align-items: stretch; gap: 0.75rem; }
            .proxy-actions { justify-content: flex-end; }
            .stat-value { font-size: 1.4rem; }
            table { min-width: 360px; }
            th, td { padding: 0.5rem; }
            .form-row { flex-direction: column; gap: 0.5rem; }
        }
        </style>
        """)
        
        # JavaScript for theme switching and API calls
        html.append("""
        <script>
        function toggleTheme() {
            const html = document.documentElement;
            const currentTheme = html.getAttribute('data-theme');
            const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
            html.setAttribute('data-theme', newTheme);
            localStorage.setItem('theme', newTheme);
        }
        
        function initTheme() {
            const savedTheme = localStorage.getItem('theme') || 'light';
            document.documentElement.setAttribute('data-theme', savedTheme);
        }
        
        function killConn(conn_id){
          fetch('/api/conn/kill', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({conn_id: conn_id})
          }).then(r=>r.json()).then(j=>{
            if(j.result==='ok') {
                showMessage('Connection terminated successfully', 'success');
                setTimeout(() => location.reload(), 1000);
            }
            else alert('Error: '+(j.error||'unknown'));
          }).catch(e=>alert('Request failed: '+e));
        }
        
        function addBackend() {
            const host = document.getElementById('new_backend_host').value;
            const port = document.getElementById('new_backend_port').value;
            
            if (!host || !port) {
                showMessage('Please fill in both host and port', 'error');
                return;
            }
            
            fetch('/api/backends/add', {
                method: 'POST',
                headers: {'Content-Type':'application/json'},
                body: JSON.stringify({host: host, port: parseInt(port)})
            }).then(r=>r.json()).then(j=>{
                if(j.result==='ok') {
                    showMessage('Backend added successfully', 'success');
                    document.getElementById('new_backend_host').value = '';
                    document.getElementById('new_backend_port').value = '';
                    setTimeout(() => location.reload(), 1000);
                }
                else showMessage('Error: '+(j.error||'unknown'), 'error');
            }).catch(e=>showMessage('Request failed: '+e, 'error'));
        }
        
        function removeBackend(host, port) {
            if (!confirm('Are you sure you want to remove backend ' + host + ':' + port + '?')) {
                return;
            }
            
            fetch('/api/backends/remove', {
                method: 'POST',
                headers: {'Content-Type':'application/json'},
                body: JSON.stringify({host: host, port: port})
            }).then(r=>r.json()).then(j=>{
                if(j.result==='ok') {
                    showMessage('Backend removed successfully', 'success');
                    setTimeout(() => location.reload(), 1000);
                }
                else showMessage('Error: '+(j.error||'unknown'), 'error');
            }).catch(e=>showMessage('Request failed: '+e, 'error'));
        }
        
        function showMessage(message, type) {
            const container = document.querySelector('.container');
            const existingMsg = document.querySelector('.message-container');
            if (existingMsg) {
                existingMsg.remove();
            }
            
            const msgDiv = document.createElement('div');
            msgDiv.className = type === 'error' ? 'error-message' : 'success-message';
            msgDiv.textContent = message;
            
            const msgContainer = document.createElement('div');
            msgContainer.className = 'message-container';
            msgContainer.appendChild(msgDiv);
            
            container.insertBefore(msgContainer, container.firstChild);
            
            setTimeout(() => {
                if (msgContainer.parentNode) {
                    msgContainer.remove();
                }
            }, 5000);
        }
        
        document.addEventListener('DOMContentLoaded', initTheme);
        </script>
        """)
        
        # D — Добавляем ссылку на `/events` в HTML
        html.append("""
        <script>
        (function(){
          if (!!window.EventSource) {
            const es = new EventSource('/events');
            es.onmessage = function(e) {
              try {
                const d = JSON.parse(e.data);
                const totalSpeedBps = d.total_speed_bps || 0;
                const el = document.getElementById('total_speed');
                if (el) {
                  el.textContent = totalSpeedBps > 0 ? ((totalSpeedBps/1024).toFixed(1) + ' KB/s') : '0 KB/s';
                }
              } catch (err) { console.debug(err); }
            };
          }
        })();
        </script>
        """)
        
        html.append("</head><body>")
        html.append("<div class='container'>")
        
        # Proxy card with theme toggle inside
        html.append("<div class='proxy-card'>")
        html.append("<div class='left'>")
        html.append("<div class='proxy-title'>🚀 Transparent SOCKS5 Proxy</div>")
        html.append(f"<div class='proxy-sub'>Server Uptime: {uptime_str} | Total Traffic: {total_traffic_str}</div>")
        html.append("</div>")
        html.append("<div class='proxy-actions'>")
        html.append("<button class='btn' onclick='toggleTheme()' aria-label='Toggle theme'>🌓 Toggle Theme</button>")
        html.append("<a class='btn secondary' href='/metrics'>Metrics</a>")
        # D — Добавляем элемент для отображения скорости в реальном времени
        html.append("<div style='margin-left:1rem;'>")
        html.append("<div style='font-size:0.95rem;color:var(--text-secondary)'>Real-time throughput</div>")
        html.append("<div id='total_speed' style='font-weight:700'>0 KB/s</div>")
        html.append("</div>")
        html.append("</div>")
        html.append("</div>")
        
        # Quick stats grid (kept compact)
        html.append("<div class='stats-grid'>")
        html.append(f"""
            <div class='stat-card'>
                <h3>Active Connections</h3>
                <div class='stat-value'>{stats_snapshot.get('active_connections', 0)}</div>
                <div class='stat-desc'>Current TCP sessions</div>
            </div>
        """)
        html.append(f"""
            <div class='stat-card'>
                <h3>Total Connections</h3>
                <div class='stat-value'>{stats_snapshot.get('total_connections', 0)}</div>
                <div class='stat-desc'>Lifetime connections</div>
            </div>
        """)
        html.append(f"""
            <div class='stat-card'>
                <h3>UDP Sessions</h3>
                <div class='stat-value'>{stats_snapshot.get('active_udp_sessions', 0)}</div>
                <div class='stat-desc'>Active UDP sessions</div>
            </div>
        """)
        html.append(f"""
            <div class='stat-card'>
                <h3>Cache Hits</h3>
                <div class='stat-value'>{stats_snapshot.get('enhanced_cache_hits', 0)}</div>
                <div class='stat-desc'>Enhanced cache performance</div>
            </div>
        """)
        html.append("</div>")
        
        # System metrics section if available
        if PSUTIL_AVAILABLE or '_read_proc_mem' in globals():
            html.append("<div class='section'>")
            html.append("<h2>🖥️ System Metrics</h2>")
            html.append("<div class='stats-grid'>")
            html.append(f"""
                <div class='stat-card'>
                    <h3>CPU Usage</h3>
                    <div class='stat-value'>{system_snapshot.get('cpu_percent', 0):.1f}%</div>
                    <div class='stat-desc'>Current CPU utilization</div>
                </div>
            """)
            memory_percent = system_snapshot.get('memory_usage_percent', system_snapshot.get('memory_usage', 0))
            memory_rss = system_snapshot.get('process_memory_rss', 0)
            html.append(f"""
                <div class='stat-card'>
                    <h3>Memory Usage</h3>
                    <div class='stat-value'>{memory_percent:.1f}%</div>
                    <div class='stat-desc'>{self._format_bytes(memory_rss)} RSS</div>
                </div>
            """)
            html.append(f"""
                <div class='stat-card'>
                    <h3>Network Sent</h3>
                    <div class='stat-value'>{self._format_bytes(system_snapshot.get('network_io', {}).get('bytes_sent', 0))}</div>
                    <div class='stat-desc'>Total bytes sent</div>
                </div>
            """)
            html.append(f"""
                <div class='stat-card'>
                    <h3>Network Received</h3>
                    <div class='stat-value'>{self._format_bytes(system_snapshot.get('network_io', {}).get('bytes_recv', 0))}</div>
                    <div class='stat-desc'>Total bytes received</div>
                </div>
            """)
            html.append("</div>")
            html.append("</div>")
        
        # SOCKS5 Backends Section (immediately after proxy card)
        html.append("<div class='section'>")
        html.append("<h2>🔌 SOCKS5 Backends Status</h2>")
        
        # Add backend form
        html.append("""
        <div style='margin-bottom: 1.5rem; padding: 1rem; background: var(--bg-secondary); border-radius: 8px;'>
            <h3 style='margin-bottom: 1rem; color: var(--text-primary);'>Add New Backend</h3>
            <div class='form-row'>
                <div class='form-col'>
                    <label class='form-label' for='new_backend_host'>Host</label>
                    <input type='text' id='new_backend_host' class='form-input' placeholder='socks.example.com'>
                </div>
                <div class='form-col'>
                    <label class='form-label' for='new_backend_port'>Port</label>
                    <input type='number' id='new_backend_port' class='form-input' placeholder='1080' min='1' max='65535'>
                </div>
                <div class='form-col' style='display: flex; align-items: flex-end;'>
                    <button class='btn' onclick='addBackend()'>Add Backend</button>
                </div>
            </div>
        </div>
        """)
        
        if socks5_status:
            html.append("<div class='table-wrap'><table>")
            # F — Вывод ttl_integrity & rtt% в HTML + Traffic
            html.append("<tr><th>Host</th><th>Port</th><th>Status</th><th>Server Ping</th><th>Internet Ping</th><th>TTL Integrity</th><th>RTT Health</th><th>Traffic</th><th>Actions</th></tr>")
            for backend in socks5_status:
                status_class = "status-healthy" if backend['healthy'] else "status-unhealthy"
                status_text = "HEALTHY" if backend['healthy'] else "UNHEALTHY"
                status_circle_class = f"status-circle status-{backend['status_color']}"
                
                # guard last_check which may be None
                if backend.get('last_check'):
                    try:
                        last_check = time.strftime('%H:%M:%S', time.localtime(backend['last_check']))
                    except Exception:
                        last_check = 'N/A'
                else:
                    last_check = 'N/A'
                use_marker = " 🎯 IN USE" if backend.get('in_use') else ""
                
                html.append("<tr>")
                html.append(f"<td><strong>{backend['host']}</strong></td>")
                html.append(f"<td>{backend['port']}</td>")
                html.append(f"<td><span class='{status_circle_class}'></span>{status_text}{use_marker}</td>")
                html.append(f"<td>{backend['server_ping']}</td>")
                html.append(f"<td>{backend['internet_ping']}</td>")
                html.append(f"<td>{backend['ttl_integrity_percent']}</td>")
                html.append(f"<td>{backend['rtt_health_percent']}</td>")
                html.append(f"<td>{backend['traffic']}</td>")
                html.append(f"<td class='backend-actions'><button class='btn danger small' onclick='removeBackend(\"{backend['host']}\", {backend['port']})'>Remove</button></td>")
                html.append("</tr>")
            html.append("</table></div>")
            
            # Легенда статусов
            html.append("<div style='margin-top: 1rem; padding: 0.75rem; background: var(--bg-secondary); border-radius: 8px;'>")
            html.append("<strong>Status Legend:</strong> ")
            html.append("<span style='margin-right: 1rem;'><span class='status-circle status-green'></span> Green - Server and Internet available</span>")
            html.append("<span style='margin-right: 1rem;'><span class='status-circle status-yellow'></span> Yellow - Server alive, no Internet</span>")
            html.append("<span><span class='status-circle status-black'></span> Black - Server unavailable</span>")
            html.append("</div>")
        else:
            html.append("<p>No SOCKS5 backends configured</p>")
        html.append("</div>")
        
        # Traffic Statistics Section
        html.append("<div class='section'>")
        html.append("<h2>📊 Traffic Statistics</h2>")
        # Stack Protocol Distribution and System Metrics vertically to avoid overflow
        html.append("<div style='display: grid; grid-template-columns: 1fr; gap: 1rem;'>")
        
        # Protocol breakdown (full width)
        html.append("<div>")
        html.append("<h3 style='color: var(--text-secondary); margin-bottom: 0.75rem;'>Protocol Distribution</h3>")
        html.append("<div class='table-wrap'><table>")
        html.append("<tr><th>Protocol</th><th>Connections</th><th>Upload</th><th>Download</th></tr>")
        
        protocols = [
            ('HTTP', 'http', 'badge-http'),
            ('HTTPS', 'https', 'badge-https'), 
            ('DNS', 'dns', 'badge-dns'),
            ('Other', 'other', 'badge-other')
        ]
        
        for name, proto, badge_class in protocols:
            conn_count = stats_snapshot.get(f'connections_{proto}', 0)
            upload = self._format_bytes(stats_snapshot.get(f'bytes_{proto}_c2r', 0))
            download = self._format_bytes(stats_snapshot.get(f'bytes_{proto}_r2c', 0))
            html.append(f"<tr>")
            html.append(f"<td><span class='protocol-badge {badge_class}'>{name}</span></td>")
            html.append(f"<td>{conn_count}</td>")
            html.append(f"<td>{upload}</td>")
            html.append(f"<td>{download}</td>")
            html.append(f"</tr>")
        html.append("</table></div>")
        html.append("</div>")
        
        # Error breakdown (full width)
        html.append("<div>")
        html.append("<h3 style='color: var(--text-secondary); margin-bottom: 0.75rem;'>Error Breakdown</h3>")
        html.append("<div class='table-wrap'><table>")
        html.append("<tr><th>Error Type</th><th>Count</th><th>Percentage</th></tr>")
        
        error_types = [
            ('Connection Timeout', 'errors_connection_timeout'),
            ('Socket Error', 'errors_socket_error'),
            ('SOCKS Handshake', 'errors_socks_handshake'),
            ('DNS Resolution', 'errors_dns'),
            ('Authentication', 'errors_auth')
        ]
        
        total_errors = stats_snapshot.get('errors', 0)
        for error_name, error_key in error_types:
            error_count = stats_snapshot.get(error_key, 0)
            if total_errors > 0:
                error_percentage = f"{(error_count / total_errors * 100):.1f}%"
            else:
                error_percentage = "0%"
            html.append(f"<tr>")
            html.append(f"<td>{error_name}</td>")
            html.append(f"<td>{error_count}</td>")
            html.append(f"<td>{error_percentage}</td>")
            html.append(f"</tr>")
        
        html.append(f"<tr style='font-weight: bold;'>")
        html.append(f"<td>Total Errors</td>")
        html.append(f"<td>{total_errors}</td>")
        html.append(f"<td>100%</td>")
        html.append(f"</tr>")
        html.append("</table></div>")
        html.append("</div>")
        
        # System stats (full width)
        html.append("<div>")
        html.append("<h3 style='color: var(--text-secondary); margin-bottom: 0.75rem;'>System Metrics</h3>")
        html.append("<div class='table-wrap'><table>")
        html.append("<tr><th>Metric</th><th>Value</th></tr>")
        
        system_metrics = [
            ('Total TCP Traffic', self._format_bytes(total_traffic)),
            ('UDP Traffic', f"{self._format_bytes(stats_snapshot.get('bytes_udp_c2r', 0))} ↑ / {self._format_bytes(stats_snapshot.get('bytes_udp_r2c', 0))} ↓"),
            ('HTTP/2 Connections', stats_snapshot.get('http2_connections', 0)),
            ('DoH Queries', stats_snapshot.get('doh_queries', 0)),
            ('Direct Connections', stats_snapshot.get('direct_connections', 0)),
            ('Bypass Events', stats_snapshot.get('socks5_bypass_count', 0)),
            ('Recovery Events', stats_snapshot.get('socks5_recovered_count', 0)),
            ('Errors', stats_snapshot.get('errors', 0))
        ]
        
        for metric, value in system_metrics:
            html.append(f"<tr><td>{metric}</td><td><strong>{value}</strong></td></tr>")
        html.append("</table></div>")
        html.append("</div>")
        
        html.append("</div>")
        html.append("</div>")
        
        # Active Connections Section
        html.append("<div class='section'>")
        html.append("<h2>🔗 Active Connections</h2>")
        if not conns_snapshot:
            html.append("<p>No active connections</p>")
        else:
            html.append("<div class='table-wrap'><table>")
            html.append("<tr><th>Client</th><th>Host/Destination</th><th>Duration</th><th>Upload</th><th>Download</th><th>Backend</th><th>Actions</th></tr>")
            for cid, info in conns_snapshot.items():
                duration = time.time() - info.get('start_time', time.time())
                duration_str = self._format_duration(duration)
                upload = self._format_bytes(info.get('bytes_c2r', 0))
                download = self._format_bytes(info.get('bytes_r2c', 0))
                backend = info.get('backend')
                backend_str = f"{backend[0]}:{backend[1]}" if backend else "Direct"
                host_display = info.get('host_display', 'Unknown')
                
                html.append("<tr class='connection-row'>")
                html.append(f"<td>{info.get('client', 'Unknown')}</td>")
                html.append(f"<td>{host_display}</td>")
                html.append(f"<td>{duration_str}</td>")
                html.append(f"<td>{upload}</td>")
                html.append(f"<td>{download}</td>")
                html.append(f"<td>{backend_str}</td>")
                html.append(f"<td><button class='btn danger small' onclick='killConn(\"{cid}\")'>✖ Kill</button></td>")
                html.append("</tr>")
            html.append("</table></div>")
        html.append("</div>")
        
        # Navigation Links
        html.append("<div class='section'>")
        html.append("<h2>🔧 Tools & Endpoints</h2>")
        html.append("<div class='nav-links'>")
        endpoints = [
            ('/metrics', 'Prometheus Metrics'),
            ('/health', 'Health Check'),
            ('/ready', 'Ready Check'),
            ('/debug/connections', 'Debug Connections'),
            ('/debug/socks5_backends', 'Debug Backends'),
            ('/debug/system', 'Debug System'),
            ('/events', 'Live Events (SSE)')
        ]
        
        for endpoint, name in endpoints:
            html.append(f"<a href='{endpoint}' class='nav-link'>{name}</a>")
        html.append("</div>")
        html.append("</div>")
        
        html.append("</div>")  # Close container
        html.append("</body></html>")
        
        body = "\n".join(html).encode('utf-8')
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    
    def _format_uptime(self, seconds):
        """Format uptime seconds to human readable string"""
        days = int(seconds // 86400)
        hours = int((seconds % 86400) // 3600)
        minutes = int((seconds % 3600) // 60)
        
        if days > 0:
            return f"{days}d {hours}h {minutes}m"
        elif hours > 0:
            return f"{hours}h {minutes}m"
        else:
            return f"{minutes}m"
    
    def _format_bytes(self, bytes_count):
        """Format bytes to human readable string"""
        if bytes_count == 0:
            return "0 B"
        
        sizes = ['B', 'KB', 'MB', 'GB', 'TB']
        i = 0
        while bytes_count >= 1024 and i < len(sizes)-1:
            bytes_count /= 1024.0
            i += 1
        
        return f"{bytes_count:.2f} {sizes[i]}"
    
    def _format_duration(self, seconds):
        """Format duration seconds to human readable string"""
        if seconds < 60:
            return f"{int(seconds)}s"
        elif seconds < 3600:
            minutes = int(seconds // 60)
            secs = int(seconds % 60)
            return f"{minutes}m {secs}s"
        else:
            hours = int(seconds // 3600)
            minutes = int((seconds % 3600) // 60)
            return f"{hours}h {minutes}m"
    
    def _check_internet_connectivity(self):
        """Quick internet connectivity check"""
        for target in (("8.8.4.4", 53), ("8.8.8.8", 53), ("8.8.4.4", 80)):
            try:
                s = socket.create_connection(target, timeout=2)
                s.close()
                return True
            except Exception:
                continue
        return False
        
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
        if ORJSON_AVAILABLE:
            b = orjson.dumps(obj)
        else:
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
                if PROMETHEUS_AVAILABLE:
                    body = generate_latest()
                else:
                    body = export_prometheus_metrics().encode('utf-8')
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
                        # Только серверы с интернетом считаются готовыми
                        ok = any(_backend_extended_status.get(b, {}).get('internet_ping') is not None 
                                for b in bkl if _backend_status.get(b, False))
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
        html.append('<meta http-equiv="refresh" content="30">')  # Увеличен интервал обновления
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
            html.append("<table cellpadding='4'><tr><th>conn_id</th><th>client</th><th>host/destination</th><th>started</th><th>bytes_c2r</th><th>bytes_r2c</th></tr>")
            for cid, info in conns_snapshot.items():
                html.append("<tr>")
                html.append(f"<td>{cid}</td>")
                html.append(f"<td>{info.get('client')}</td>")
                html.append(f"<td>{info.get('host_display', 'Unknown')}</td>")
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

def start_web_server(port: int, socks_host: str = None, socks_port: int = None, certificate_path: str = None):
    global _WEB_SOCKS_HOST, _WEB_SOCKS_PORT
    _WEB_SOCKS_HOST = socks_host
    _WEB_SOCKS_PORT = socks_port
    httpd = None
    def _serve():
        nonlocal httpd
        try:
            httpd = THServer(('127.0.0.1', port), EnhancedStatsHandler)
            
            # Add SSL/TLS support if certificate is provided
            if certificate_path and SSL_AVAILABLE:
                try:
                    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                    context.load_cert_chain(certificate_path)
                    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
                    logger.info("Enhanced Web UI with TLS listening on 127.0.0.1:%d (HTTPS)", port)
                except Exception as e:
                    logger.error("Failed to setup TLS: %s", e)
                    logger.info("Enhanced Web UI listening on 127.0.0.1:%d (HTTP - TLS failed)", port)
            else:
                if certificate_path and not SSL_AVAILABLE:
                    logger.warning("Certificate provided but SSL not available, running in HTTP mode")
                logger.info("Enhanced Web UI listening on 127.0.0.1:%d (HTTP)", port)
                
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

def reload_config_from_env(cfg):
    changed = {}
    try:
        # read env or config-file (if T2S_CONFIG_FILE)
        env_vars = {
            'T2S_MAX_CONNS': ('max_conns', int),
            'T2S_IDLE_TIMEOUT': ('idle_timeout', int),
            'T2S_RATE_LIMIT_PER_MINUTE': ('rate_limit_per_minute', int),
            'T2S_ENABLE_HTTP2': ('enable_http2', lambda v: v.lower() in ('1','true','yes')),
        }
        for env_k, (cfg_k, caster) in env_vars.items():
            val = os.getenv(env_k)
            if val is not None and val != '':
                try:
                    new = caster(val)
                    cfg[cfg_k] = new
                    changed[cfg_k] = new
                except Exception:
                    logger.debug("Failed to cast env var %s=%s", env_k, val)
        # also try to load file if set
        conf_path = os.getenv('T2S_CONFIG_FILE')
        if conf_path and os.path.exists(conf_path):
            try:
                with open(conf_path, 'r') as f:
                    if YAML_AVAILABLE and conf_path.endswith(('.yaml', '.yml')):
                        data = yaml.safe_load(f)
                    else:
                        if ORJSON_AVAILABLE:
                            data = orjson.loads(f.read())
                        else:
                            data = json.load(f)
                for k, v in data.items():
                    if k not in cfg:
                        continue
                    cfg[k] = v
                    changed[k] = v
            except Exception:
                logger.exception("Failed to load config file on reload")
        if changed:
            logger.info("Reloaded config from env/file: %s", changed)
        else:
            logger.info("SIGHUP received but no relevant env/file changes found for reload")
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
    global _rate_limiter, _socks5_backends, _socks5_available, _enhanced_dns, _certificate_path
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
    
    # Start system monitoring if available
    if PSUTIL_AVAILABLE or '_read_proc_mem' in globals():
        system_monitor_thread = threading.Thread(target=system_monitor, daemon=True)
        system_monitor_thread.start()
        logger.info("System monitoring started")
    
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
        'certificate_path': _certificate_path,
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
        tcp_sock.listen(cfg.get('backlog', 256))  # Увеличен backlog
        tcp_sock.settimeout(1.0)
        logger.info("Listening on %s:%d (TCP)", listen_addr, listen_port)
        if fixed_target:
            logger.info("Forwarding all incoming connections to fixed target %s:%d via SOCKS backends %s", fixed_target[0], fixed_target[1], _socks5_backends)
        else:
            logger.info("Transparent mode: will attempt SO_ORIGINAL_DST and forward via SOCKS backends %s", _socks5_backends)
    max_conns = cfg.get('max_conns', 100)  # Уменьшено максимальное количество соединений
    sem = threading.BoundedSemaphore(max_conns)
    # Используем ThreadPoolExecutor для управления потоками
    executor = ThreadPoolExecutor(max_workers=max(4, cfg.get('max_conns', 100)))
    reporter = threading.Thread(target=stats_reporter, args=(cfg.get('stats_interval', 65),), daemon=True)  # Увеличен интервал до 65 (+5s)
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
                            _inc_error()
                        continue
                except Exception:
                    logger.exception("RateLimiter failure; allowing connection")
                chosen_backend = select_socks_backend()
                chosen_host, chosen_port = chosen_backend if chosen_backend != (None, None) else (None, None)
                if cfg.get('enable_http2', False):
                    executor.submit(handle_client_enhanced, client_sock, client_addr, chosen_host, chosen_port, socks_user, socks_pass, fixed_target, sem, cfg)
                else:
                    executor.submit(handle_client, client_sock, client_addr, chosen_host, chosen_port, socks_user, socks_pass, fixed_target, sem, cfg)
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
        # Graceful shutdown executor
        executor.shutdown(wait=True)
        time.sleep(0.1)

def self_test_startup(socks_backends, cfg):
    """
    Run lightweight local checks:
    - create TCP and UDP sockets
    - ensure getaddrinfo works
    - optional: attempt quick connect to configured SOCKS backend(s)
    Returns tuple (ok: bool, details: dict)
    """
    details = {'tcp_socket': False, 'udp_socket': False, 'dns': False, 'socks_tests': []}
    ok = True
    # tcp socket bind test
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(('127.0.0.1', 0))
        s.listen(1)
        s.close()
        details['tcp_socket'] = True
    except Exception as e:
        details['tcp_socket_err'] = str(e)
        ok = False
    # udp socket test
    try:
        u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        u.bind(('127.0.0.1', 0))
        u.close()
        details['udp_socket'] = True
    except Exception as e:
        details['udp_socket_err'] = str(e)
        ok = False
    # dns test
    try:
        socket.getaddrinfo('localhost', None)
        details['dns'] = True
    except Exception as e:
        details['dns_err'] = str(e)
        ok = False
    # socks quick tests (non-blocking short connect attempts)
    for backend in (socks_backends or []):
        host, port = backend
        res = {'host': host, 'port': port, 'ok': False, 'err': None}
        try:
            s = socket.create_connection((host, port), timeout=2)
            s.close()
            res['ok'] = True
        except Exception as e:
            res['err'] = str(e)
            ok = ok and False
        details['socks_tests'].append(res)
    return ok, details

class ConfigManager:
    """
    Centralized config manager. Precedence: CLI args > ENV vars > config file > defaults.
    Single-file policy preserved: config file is optional JSON provided via env T2S_CONFIG_FILE or --config-file.
    """
    def __init__(self, cli_args: argparse.Namespace):
        self.cli = vars(cli_args)
        self.env = dict(os.environ)
        self.file_cfg = {}
        self.defaults = {
            'buffer_size': 131072,  # Увеличен размер буфера
            'idle_timeout': 600,  # Увеличен таймаут простоя
            'connect_timeout': 30,  # Увеличен таймаут подключения
            'connect_retries': 2,  # Уменьшено количество ретраев
            'retry_backoff': 1.0,  # Увеличен базовый backoff
            'keepidle': 125,  # Оптимизированы keepalive параметры (+5s)
            'keepintvl': 30,
            'keepcnt': 3,
            'max_conns': 100,  # Уменьшено максимальное количество соединений
            'backlog': 256,  # Увеличен backlog
            'stats_interval': 65,  # Увеличен интервал статистики до 65 (+5s)
            'mode': 'tcp',
            'udp_listen_addr': self.cli.get('listen_addr', '127.0.0.1'),
            'udp_listen_port': self.cli.get('listen_port', 11290),
            'udp_session_timeout': 125,  # Увеличен таймаут UDP сессии с 120 до 125 (+5s)
            'udp_buffer_size': 131072,  # Увеличен размер буфера UDP
            'rate_limit_per_minute': int(self.env.get('T2S_RATE_LIMIT_PER_MINUTE', '0') or 0),
            'graceful_shutdown_timeout': 30,
            'enable_http2': False,
            'enable_doh': False,
            'enable_doq': False,
            'cache_mode': 'memory',
            'enhanced_cache': False,
            # E — CLI / Config: аргументы
            'dns_ttl': 600,
            'cache_ttl': 600,
            'sse_interval': 1,
            'enable_ttl_monitor': False,
        }

    def load_file(self):
        # config file path can be provided by --config-file or env T2S_CONFIG_FILE
        conf_path = self.cli.get('config_file') or self.env.get('T2S_CONFIG_FILE')
        if not conf_path:
            return
        try:
            with open(conf_path, 'r') as f:
                if YAML_AVAILABLE and conf_path.endswith(('.yaml', '.yml')):
                    data = yaml.safe_load(f)
                else:
                    if ORJSON_AVAILABLE:
                        data = orjson.loads(f.read())
                    else:
                        data = json.load(f)
            if isinstance(data, dict):
                self.file_cfg.update(data)
        except Exception:
            logger.warning("ConfigManager: failed to load config file %s", conf_path)

    def get(self, key, fallback=None):
        # precedence: CLI -> ENV -> file -> defaults -> fallback
        if key in self.cli and self.cli.get(key) is not None:
            return self.cli.get(key)
        env_key = 'T2S_' + key.upper()
        if env_key in self.env and self.env[env_key] != '':
            val = self.env[env_key]
            # attempt to coerce numeric values
            if val.isdigit():
                return int(val)
            try:
                return json.loads(val)
            except Exception:
                return val
        if key in self.file_cfg:
            return self.file_cfg[key]
        if key in self.defaults:
            return self.defaults[key]
        return fallback

    def as_cfg_dict(self):
        # build the cfg dictionary used by run_server
        d = {
            'buffer_size': int(self.get('buffer_size')),
            'idle_timeout': int(self.get('idle_timeout')),
            'connect_timeout': int(self.get('connect_timeout')),
            'connect_retries': int(self.get('connect_retries')),
            'retry_backoff': float(self.get('retry_backoff')),
            'keepidle': int(self.get('keepidle')),
            'keepintvl': int(self.get('keepintvl')),
            'keepcnt': int(self.get('keepcnt')),
            'max_conns': int(self.get('max_conns')),
            'backlog': int(self.get('backlog')),
            'stats_interval': int(self.get('stats_interval')),
            'mode': self.get('mode'),
            'udp_listen_addr': self.get('udp_listen_addr'),
            'udp_listen_port': int(self.get('udp_listen_port')),
            'udp_session_timeout': int(self.get('udp_session_timeout')),
            'udp_buffer_size': int(self.get('udp_buffer_size')),
            'rate_limit_per_minute': int(self.get('rate_limit_per_minute')),
            'graceful_shutdown_timeout': int(self.get('graceful_shutdown_timeout')),
            'enable_http2': bool(self.get('enable_http2')),
            'enable_doh': bool(self.get('enable_doh')),
            'enable_doq': bool(self.get('enable_doq')),
            'cache_mode': self.get('cache_mode'),
            'enhanced_cache': bool(self.get('enhanced_cache')),
            # E — CLI / Config: аргументы
            'dns_ttl': int(self.get('dns_ttl')),
            'cache_ttl': int(self.get('cache_ttl')),
            'sse_interval': int(self.get('sse_interval')),
            'enable_ttl_monitor': bool(self.get('enable_ttl_monitor')),
        }
        return d

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
    p.add_argument("--buffer-size", type=int, default=131072, help="read buffer size")  # Увеличен размер по умолчанию
    p.add_argument("--idle-timeout", type=int, default=600, help="idle timeout in seconds for connections (0 disable)")  # Увеличен таймаут
    p.add_argument("--connect-timeout", type=int, default=30, help="timeout for TCP connect to upstream")  # Увеличен таймаут
    p.add_argument("--connect-retries", type=int, default=2, help="retries when connecting to upstream")  # Уменьшено количество ретраев
    p.add_argument("--retry-backoff", type=float, default=1.0, help="base backoff for retries (exponential)")  # Увеличен backoff
    p.add_argument("--keepidle", type=int, default=125, help="TCP keepalive idle (seconds)")  # Оптимизированы параметры (+5s)
    p.add_argument("--keepintvl", type=int, default=30, help="TCP keepalive interval (seconds)")
    p.add_argument("--keepcnt", type=int, default=3, help="TCP keepalive count")
    p.add_argument("--max-conns", type=int, default=100, help="максимум одновременных сессий")  # Уменьшено по умолчанию
    p.add_argument("--backlog", type=int, default=256, help="размер очереди подключений")  # Увеличен backlog
    p.add_argument("--stats-interval", type=int, default=65, help="интервал логирования глобальных статистик (сек)")  # Увеличен интервал до 65 (+5s)
    p.add_argument("--mode", choices=("tcp", "udp", "tcp-udp"), default="tcp", help="which protocols to enable: tcp (default), udp, tcp-udp")
    p.add_argument("--udp-listen-addr", default=None, help="address for UDP listener (default same as --listen-addr)")
    p.add_argument("--udp-listen-port", type=int, default=None, help="UDP listen port (default same as --listen-port)")
    p.add_argument("--udp-session-timeout", type=int, default=125, help="idle timeout (seconds) for UDP sessions")  # Увеличен таймаут до 125 (+5s)
    p.add_argument("--udp-buffer-size", type=int, default=131072, help="read buffer size for UDP")  # Увеличен размер буфера
    p.add_argument("--web-socket", action='store_true', help="Enable simple web UI for stats")
    p.add_argument("--web-port", type=int, default=8000, help="Port for the web UI (default 8000)")  # Изменен порт по умолчанию
    p.add_argument("--logfile", default=None, help="path to log file")
    p.add_argument("--verbose", action='store_true', help="debug logging")
    p.add_argument("--cache-mode", choices=("memory", "disk-cache"), default="memory", help="memory or disk-cache (if disk-cache -> /data/adb/modules/ZDT-D/cache/)")
    p.add_argument("--graceful-shutdown-timeout", type=int, default=30, help="seconds to wait for active connections to finish on shutdown")
    p.add_argument("--enable-http2", action='store_true', help="Enable HTTP/2 support")
    p.add_argument("--enable-doh", action='store_true', help="Enable DNS-over-HTTPS")
    p.add_argument("--enable-doq", action='store_true', help="Enable DNS-over-QUIC (experimental)")
    p.add_argument("--enhanced-cache", action='store_true', help="Enable enhanced caching with HTTP/2 support")
    p.add_argument("--config-file", default=None, help="Path to JSON config file")
    p.add_argument("--self-test", action='store_true', help="Run self-test and exit")
    # Добавлен аргумент для сертификата
    p.add_argument("--certificate", default=None, help="Path to SSL certificate file for web UI (PEM format containing both certificate and private key)")
    # E — CLI / Config: аргументы
    p.add_argument("--dns-ttl", type=int, default=600, help="DNS cache TTL seconds (overrides default)")
    p.add_argument("--cache-ttl", type=int, default=600, help="HTTP cache TTL seconds (overrides default)")
    p.add_argument("--sse-interval", type=int, default=1, help="SSE events interval in seconds for /events")
    p.add_argument("--enable-ttl-monitor", action='store_true', help="Try to monitor TTL from UDP packets (platform dependent)")
    return p.parse_args()

if __name__ == "__main__":
    args = parse_args()
    
    # self-test mode
    if args.self_test:
        socks_hosts = [h.strip() for h in args.socks_host.split(',') if h.strip()]
        socks_ports = [p.strip() for p in str(args.socks_port).split(',') if p.strip()]
        parsed_ports = []
        for p in socks_ports:
            try:
                parsed_ports.append(int(p))
            except Exception:
                pass
        backends = []
        for h in socks_hosts:
            for pr in parsed_ports:
                backends.append((h, pr))
        cm = ConfigManager(args)
        cm.load_file()
        cfg = cm.as_cfg_dict()
        ok, details = self_test_startup(backends, cfg)
        print(json.dumps(details, indent=2))
        sys.exit(0 if ok else 2)
    
    fixed_target = None
    if (args.target_host is not None) ^ (args.target_port is not None):
        print("Если используешь --target-host, укажи также --target-port.")
        sys.exit(2)
    if args.target_host is not None and args.target_port is not None:
        fixed_target = (args.target_host, args.target_port)
    level = logging.DEBUG if args.verbose else logging.INFO
    logger = setup_logger(level=level, logfile=args.logfile)
    if args.verbose:
        class StructuredFormatter(logging.Formatter):
            def format(self, record):
                log_entry = {
                    'timestamp': self.formatTime(record),
                    'level': record.levelname,
                    'message': record.getMessage(),
                    'module': record.module,
                    'thread': record.threadName,
                }
                if ORJSON_AVAILABLE:
                    return orjson.dumps(log_entry).decode('utf-8')
                else:
                    return json.dumps(log_entry)
        handler = logging.StreamHandler()
        handler.setFormatter(StructuredFormatter())
        logging.getLogger('t2s').addHandler(handler)
    
    # ConfigManager usage
    cm = ConfigManager(args)
    cm.load_file()
    cfg = cm.as_cfg_dict()
    
    # Set certificate path globally
    _certificate_path = args.certificate
    if _certificate_path and not os.path.exists(_certificate_path):
        logger.error("Certificate file not found: %s", _certificate_path)
        sys.exit(2)
    if _certificate_path:
        logger.info("Using certificate: %s", _certificate_path)
    
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
            _backend_extended_status[b] = {
                'healthy': True,
                'server_ping': None,
                'internet_ping': None,
                'last_check': 0
            }
    
    try:
        validate_config(cfg)
    except Exception as e:
        print("Invalid configuration:", e)
        sys.exit(2)
    install_signal_handlers()
    cache_mode = cfg.get('cache_mode', 'memory')
    cache_dir = '/data/adb/modules/ZDT-D/cache/' if cache_mode == 'disk-cache' else None
    
    # atomic persist patch
    DNSCache._persist = DNSCache__persist
    SimpleHTTPCache._persist_meta = SimpleHTTPCache__persist_meta
    EnhancedHTTPCache._persist_meta = EnhancedHTTPCache__persist_meta
    EnhancedHTTPCache._persist_http2_meta = EnhancedHTTPCache__persist_http2_meta
    
    # E — set SSE interval globally for handler (+5s as requested)
    try:
        _SSE_INTERVAL = int(cfg.get('sse_interval', 1))
    except Exception:
        _SSE_INTERVAL = 1
    # Add +5 seconds to SSE interval as requested
    _SSE_INTERVAL = max(1, _SSE_INTERVAL)
    
    # TTL sample window configurable?
    # _TTL_SAMPLE_WINDOW = cfg.get('ttl_sample_window', _TTL_SAMPLE_WINDOW)
    
    # E — инициализация кешей через заданные TTL
    if cfg.get('enhanced_cache', False):
        http_cache = EnhancedHTTPCache(cache_ttl=int(cfg.get('cache_ttl', 600)), cache_max_size=1_000_000, mode=cache_mode, cache_dir=cache_dir)
        logger.info("Enhanced HTTP cache initialized with TTL=%d seconds", cfg.get('cache_ttl', 600))
    else:
        http_cache = SimpleHTTPCache(cache_ttl=int(cfg.get('cache_ttl', 600)), cache_max_size=1_000_000, mode=cache_mode, cache_dir=cache_dir)
        logger.info("Simple HTTP cache initialized with TTL=%d seconds", cfg.get('cache_ttl', 600))
    
    dns_cache = DNSCache(ttl=int(cfg.get('dns_ttl', 600)), mode=cache_mode, cache_dir=cache_dir)
    logger.info("DNS cache initialized with TTL=%d seconds", cfg.get('dns_ttl', 600))
    
    if args.web_socket:
        try:
            first_backend = _socks5_backends[0] if _socks5_backends else (None, None)
            start_web_server(args.web_port, first_backend[0] if first_backend else None, first_backend[1] if first_backend else None, _certificate_path)
        except Exception as e:
            logger.error("Failed to start web UI: %s", e)
    try:
        first = _socks5_backends[0]
        run_server(args.listen_addr, args.listen_port, first[0], first[1], args.socks_user, args.socks_pass, fixed_target, cfg)
    finally:
        graceful_shutdown(timeout=cfg.get('graceful_shutdown_timeout', 30))
