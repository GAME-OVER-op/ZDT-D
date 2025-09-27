#!/usr/bin/env python3
# transparent_to_socks5_with_mode_ipv6_web.py
"""
Transparent -> SOCKS5 (stdlib) с опцией TCP/UDP/tcp-udp, поддержкой IPv6 SO_ORIGINAL_DST,
graceful SIGTERM/SIGINT и простой веб-панелью для отображения статистики.

CLI добавлены опции:
  --mode tcp|udp|tcp-udp
  --web-socket (включить веб-панель)
  --web-port (порт веб-панели, default 8000)

Примечания:
 - UDP используется только если mode требует.
 - SO_ORIGINAL_DST для IPv6 пытается через SOL_IPV6 с фоллбэком; поведение зависит от платформы.
 - Веб-панель — простая, без стилей.
"""
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
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Optional, Tuple

# Значение для SO_ORIGINAL_DST (обычно 80 в linux-headers)
SO_ORIGINAL_DST = 80

# (Иногда для IPv6 есть отдельный оптнейм в ядре; если нет в stdlib, используем 80 в качестве фоллбэка)
IP6T_SO_ORIGINAL_DST_FALLBACK = 80

# Глобальные счётчики
_stats_lock = threading.Lock()
_stats = {
    'active_connections': 0,
    'total_connections': 0,
    'total_bytes_client_to_remote': 0,
    'total_bytes_remote_to_client': 0,
    'udp_sessions': 0,
    'bytes_udp_c2r': 0,
    'bytes_udp_r2c': 0,
}

# Глобальный реестр активных соединений для веб-панели
_conns_lock = threading.Lock()
_conns = {}  # conn_id -> {client, start_time, bytes_c2r, bytes_r2c}

shutdown_event = threading.Event()


def setup_logger(level=logging.INFO, logfile=None):
    fmt = '%(asctime)s [%(levelname)s] %(message)s'
    if logfile:
        logging.basicConfig(level=level, format=fmt, filename=logfile)
    else:
        logging.basicConfig(level=level, format=fmt)
    return logging.getLogger('t2s')


logger = setup_logger()


# ---------- Utilities ----------

def recv_exact(sock: socket.socket, n: int, timeout: float = None) -> bytes:
    """Считывает ровно n байт или бросает ConnectionError."""
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
    """Parse sockaddr buffer returned by SO_ORIGINAL_DST (supports IPv4 and IPv6).
    Returns (ip_str, port).
    Raises RuntimeError on unsupported format.
    """
    if not raw:
        raise RuntimeError("empty SO_ORIGINAL_DST")
    # Try to read family from first 2 bytes (network byte order)
    if len(raw) >= 2:
        try:
            family = struct.unpack_from('!H', raw, 0)[0]
        except Exception:
            family = None
    else:
        family = None

    # IPv4 sockaddr_in typical layout (16 bytes): family(2), port(2), addr(4), zeros...
    if family == socket.AF_INET and len(raw) >= 16:
        port = struct.unpack_from('!H', raw, 2)[0]
        ip_bytes = raw[4:8]
        ip_str = socket.inet_ntoa(ip_bytes)
        return ip_str, port

    # IPv6 sockaddr_in6 typical layout (28 bytes): family(2), port(2), flowinfo(4), addr(16), scope_id(4)
    if family == socket.AF_INET6 and len(raw) >= 28:
        port = struct.unpack_from('!H', raw, 2)[0]
        addr_raw = raw[8:24]
        ip_str = socket.inet_ntop(socket.AF_INET6, addr_raw)
        return ip_str, port

    # Fallback heuristics: if length matches IPv4 sockaddr length
    if len(raw) >= 16:
        # read as IPv4 by default
        try:
            port = struct.unpack_from('!H', raw, 2)[0]
            ip_bytes = raw[4:8]
            ip_str = socket.inet_ntoa(ip_bytes)
            return ip_str, port
        except Exception:
            pass

    raise RuntimeError(f"unsupported SO_ORIGINAL_DST raw length/family (len={len(raw)})")


def get_original_dst(conn: socket.socket) -> Tuple[str, int]:
    """Try to get original destination for IPv4 and IPv6 redirected sockets.
    Tries SOL_IP + SO_ORIGINAL_DST first, then SOL_IPV6 with a fallback optname.
    Raises RuntimeError if not available.
    """
    # Try IPv4
    try:
        od = conn.getsockopt(socket.SOL_IP, SO_ORIGINAL_DST, 128)
        return parse_original_dst(od)
    except Exception as e_ipv4:
        # Try IPv6 (if platform supports SOL_IPV6)
        try:
            od6 = conn.getsockopt(socket.SOL_IPV6, SO_ORIGINAL_DST, 128)
            return parse_original_dst(od6)
        except Exception:
            # try fallback optname (some systems expose IP6T_SO_ORIGINAL_DST differently)
            try:
                od6b = conn.getsockopt(socket.SOL_IPV6, IP6T_SO_ORIGINAL_DST_FALLBACK, 128)
                return parse_original_dst(od6b)
            except Exception:
                raise RuntimeError("SO_ORIGINAL_DST not available (socket not redirected or platform unsupported)") from e_ipv4


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


# ---------- Original SOCKS5 TCP connect (preserved) ----------

def socks5_connect_via(sock: socket.socket, target_host, target_port, username=None, password=None, timeout=None):
    old_timeout = sock.gettimeout()
    try:
        sock.settimeout(timeout)
        methods = [0x00]
        if username is not None and password is not None:
            methods.append(0x02)
        req = struct.pack("!BB", 0x05, len(methods)) + bytes(methods)
        sock.sendall(req)

        data = sock.recv(2)
        if len(data) < 2:
            raise RuntimeError("SOCKS5: короткий ответ на METHODS")
        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            raise RuntimeError(f"SOCKS5: некорректная версия в ответе: {ver}")
        if method == 0xFF:
            raise RuntimeError("SOCKS5: нет подходящего метода аутентификации (0xFF)")

        if method == 0x02:
            if username is None or password is None:
                raise RuntimeError("SOCKS5: сервер требует USER/PASS, но креды не переданы")
            uname_b = username.encode('utf-8')
            pwd_b = password.encode('utf-8')
            if len(uname_b) > 255 or len(pwd_b) > 255:
                raise RuntimeError("SOCKS5: username/password слишком длинные (макс 255 байт)")
            subreq = struct.pack("!B", 0x01) + struct.pack("!B", len(uname_b)) + uname_b + struct.pack("!B", len(pwd_b)) + pwd_b
            sock.sendall(subreq)
            subresp = sock.recv(2)
            if len(subresp) < 2:
                raise RuntimeError("SOCKS5 USER/PASS: короткий ответ")
            ver2, status = struct.unpack("!BB", subresp)
            if ver2 != 0x01:
                raise RuntimeError(f"SOCKS5 USER/PASS: неверная версия {ver2}")
            if status != 0x00:
                raise RuntimeError("SOCKS5 USER/PASS: аутентификация неуспешна")

        try:
            ipv4 = socket.inet_aton(target_host)
            atyp = 0x01
            addr_part = ipv4
        except OSError:
            atyp = 0x03
            host_b = target_host.encode('idna')
            if len(host_b) > 255:
                raise RuntimeError("SOCKS5: имя хоста слишком длинное для DOMAINNAME")
            addr_part = struct.pack("!B", len(host_b)) + host_b

        port_part = struct.pack("!H", int(target_port))
        req = struct.pack("!BBB", 0x05, 0x01, 0x00) + struct.pack("!B", atyp) + addr_part + port_part
        sock.sendall(req)

        resp = sock.recv(4)
        if len(resp) < 4:
            raise RuntimeError("SOCKS5 CONNECT: слишком короткий ответ")
        ver_r, rep, rsv, atyp_r = struct.unpack("!BBBB", resp)
        if ver_r != 0x05:
            raise RuntimeError(f"SOCKS5 CONNECT: неверная версия ответа {ver_r}")
        if rep != 0x00:
            # Читаем доступные дополнительные байты для диагностики, если они есть
            try:
                remaining = sock.recv(4096)
            except Exception:
                remaining = b''
            raise RuntimeError(f"SOCKS5 CONNECT: сервер вернул ошибку REP=0x{rep:02x} (extra={remaining!r})")

        if atyp_r == 0x01:
            addr_raw = sock.recv(4)
            if len(addr_raw) < 4:
                raise RuntimeError("SOCKS5 CONNECT: короткий BND.ADDR IPv4")
            bnd_addr = socket.inet_ntoa(addr_raw)
        elif atyp_r == 0x03:
            ln_b = sock.recv(1)
            if len(ln_b) < 1:
                raise RuntimeError("SOCKS5 CONNECT: короткий BND.ADDR len")
            ln = struct.unpack("!B", ln_b)[0]
            addr_raw = sock.recv(ln)
            if len(addr_raw) < ln:
                raise RuntimeError("SOCKS5 CONNECT: короткий BND.ADDR domain")
            bnd_addr = addr_raw.decode('idna')
        elif atyp_r == 0x04:
            addr_raw = sock.recv(16)
            if len(addr_raw) < 16:
                raise RuntimeError("SOCKS5 CONNECT: короткий BND.ADDR IPv6")
            bnd_addr = socket.inet_ntop(socket.AF_INET6, addr_raw)
        else:
            raise RuntimeError("SOCKS5 CONNECT: неизвестный ATYP в ответе")

        port_raw = sock.recv(2)
        if len(port_raw) < 2:
            raise RuntimeError("SOCKS5 CONNECT: короткий BND.PORT")
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


# ---------- UDP: helpers & session class ----------

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


def socks5_udp_associate(tcp_sock: socket.socket, timeout: float = None):
    """
    Perform greeting/auth if necessary (we assume sock already did greeting/auth when created),
    then send UDP ASSOCIATE (cmd=0x03) with DST=0.0.0.0:0 and parse BND.ADDR/PORT.
    We implement greeting+associate here to keep flow self-contained.
    """
    old = tcp_sock.gettimeout()
    try:
        tcp_sock.settimeout(timeout)
        # send UDP ASSOCIATE (client usually sends 0.0.0.0:0)
        req = struct.pack('!BBBB', 0x05, 0x03, 0x00, 0x01)  # VER, CMD=UDP ASSOC, RSV, ATYP=IPv4
        req += socket.inet_aton('0.0.0.0') + struct.pack('!H', 0)
        tcp_sock.sendall(req)

        hdr = tcp_sock.recv(4)
        if len(hdr) < 4:
            raise RuntimeError("SOCKS5 UDP ASSOC: short reply header")
        ver, rep, rsv, atyp = struct.unpack('!BBBB', hdr)
        if ver != 0x05:
            raise RuntimeError(f"SOCKS5 UDP ASSOC: bad version {ver}")
        if rep != 0x00:
            try:
                extra = tcp_sock.recv(4096)
            except Exception:
                extra = b''
            raise RuntimeError(f"SOCKS5 UDP ASSOC reply rep=0x{rep:02x} extra={extra!r}")
        if atyp == 0x01:
            addr_raw = tcp_sock.recv(4)
            if len(addr_raw) < 4:
                raise RuntimeError("short BND.ADDR IPv4")
            bnd_addr = socket.inet_ntoa(addr_raw)
        elif atyp == 0x03:
            ln_b = tcp_sock.recv(1)
            if len(ln_b) < 1:
                raise RuntimeError("short BND.ADDR len")
            ln = struct.unpack('!B', ln_b)[0]
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
        bnd_port = struct.unpack('!H', port_raw)[0]
        return bnd_addr, bnd_port
    finally:
        try:
            tcp_sock.settimeout(old)
        except Exception:
            pass


class UDPSession:
    """UDP session per client_addr"""
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

    def start(self):
        # create tcp control socket
        self.tcp = socket.create_connection((self.socks_host, self.socks_port), timeout=self.timeout)
        # greeting + optional auth (reuse original handshake pattern)
        methods = [0x00]
        if self.socks_user is not None and self.socks_pass is not None:
            methods.append(0x02)
        self.tcp.sendall(struct.pack("!BB", 0x05, len(methods)) + bytes(methods))
        data = self.tcp.recv(2)
        if len(data) < 2:
            raise RuntimeError("SOCKS5 UDP: short greeting reply")
        ver, method = struct.unpack("!BB", data)
        if ver != 0x05:
            raise RuntimeError("SOCKS5 UDP: bad version in greeting")
        if method == 0x02:
            if self.socks_user is None or self.socks_pass is None:
                raise RuntimeError("SOCKS5 UDP: server requires username/password")
            ub = self.socks_user.encode('utf-8')
            pb = self.socks_pass.encode('utf-8')
            subreq = struct.pack("!B", 0x01) + struct.pack("!B", len(ub)) + ub + struct.pack("!B", len(pb)) + pb
            self.tcp.sendall(subreq)
            subresp = self.tcp.recv(2)
            if len(subresp) < 2:
                raise RuntimeError("SOCKS5 UDP: short auth reply")
            ver2, status = struct.unpack("!BB", subresp)
            if ver2 != 0x01 or status != 0x00:
                raise RuntimeError("SOCKS5 UDP: auth failed")
        # UDP ASSOCIATE
        bnd_addr, bnd_port = socks5_udp_associate(self.tcp, timeout=self.timeout)
        self.relay_addr = (bnd_addr, bnd_port)
        # create udp socket to talk to relay
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(("0.0.0.0", 0))
        self.udp.setblocking(False)
        self.alive = True
        logger.debug("[%s] Created UDP session -> relay %s:%d", self.client_addr, bnd_addr, bnd_port)

    def send_from_client(self, dst_host, dst_port, data):
        pkt = build_socks5_udp_packet(dst_host, dst_port, data)
        with self.lock:
            if not self.alive:
                raise RuntimeError("UDP session not alive")
            self.udp.sendto(pkt, self.relay_addr)
            self.last_activity = time.time()
            with _stats_lock:
                _stats['bytes_udp_c2r'] += len(data)

    def recv_from_relay(self):
        """Non-blocking read; returns (src_host, src_port, payload) or None"""
        try:
            pkt, addr = self.udp.recvfrom(65535)
        except BlockingIOError:
            return None
        except OSError as e:
            logger.debug("udp recvfrom error: %s", e)
            return None
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


# ---------- Forwarding loop for TCP (preserve original) ----------

def forward_loop(a: socket.socket, b: socket.socket, client_addr, cfg, stats, conn_id: str):
    buffer_size = cfg.get('buffer_size', 65536)
    idle_timeout = cfg.get('idle_timeout', 300)
    last_activity = time.time()

    try:
        a.setblocking(True)
        b.setblocking(True)
    except Exception:
        pass

    while True:
        timeout = 1.0  # regularly check idle
        try:
            r, _, _ = select.select([a, b], [], [], timeout)
        except OSError as e:
            if e.errno == errno.EINTR:
                continue
            logger.error(f"select error: {e}")
            break

        if not r:
            if idle_timeout and (time.time() - last_activity) > idle_timeout:
                logger.info(f"[{client_addr}] idle timeout ({idle_timeout}s), closing")
                break
            continue

        for s in r:
            try:
                data = s.recv(buffer_size)
            except OSError as e:
                if e.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                    continue
                logger.debug(f"recv error on fd {s.fileno()}: {e}")
                return
            except Exception as e:
                logger.debug(f"recv exception on fd {s.fileno()}: {e}")
                return

            if not data:
                logger.debug(f"[{client_addr}] peer closed fd {s.fileno()}")
                return

            last_activity = time.time()
            dst = b if s is a else a
            direction = 'client->remote' if s is a else 'remote->client'
            logger.debug(f"[{client_addr}] chunk {len(data)} bytes {direction}")

            try:
                dst.sendall(data)
            except BrokenPipeError:
                logger.debug(f"[{client_addr}] BrokenPipe writing to fd {dst.fileno()}")
                return
            except Exception as e:
                logger.debug(f"[{client_addr}] send error to fd {dst.fileno()}: {e}")
                return

            with _stats_lock:
                if s is a:
                    _stats['total_bytes_client_to_remote'] += len(data)
                    stats['bytes_client_to_remote'] += len(data)
                    # update global conn registry
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['bytes_c2r'] += len(data)
                else:
                    _stats['total_bytes_remote_to_client'] += len(data)
                    stats['bytes_remote_to_client'] += len(data)
                    with _conns_lock:
                        if conn_id in _conns:
                            _conns[conn_id]['bytes_r2c'] += len(data)


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


def handle_client(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg):
    stats = {
        'bytes_client_to_remote': 0,
        'bytes_remote_to_client': 0,
        'start_time': time.time(),
    }

    conn_id = f"{client_addr[0]}:{client_addr[1]}_{threading.get_ident()}"

    with sem:
        with _stats_lock:
            _stats['active_connections'] += 1
            _stats['total_connections'] += 1

        # register connection for web UI
        with _conns_lock:
            _conns[conn_id] = {
                'client': f"{client_addr[0]}:{client_addr[1]}",
                'start_time': stats['start_time'],
                'bytes_c2r': 0,
                'bytes_r2c': 0
            }

        socks_sock = None
        try:
            # determine target
            if fixed_target is not None:
                target_host, target_port = fixed_target
            else:
                try:
                    target_host, target_port = get_original_dst(client_sock)
                except Exception as e:
                    logger.warning(f"[{client_addr}] Не удалось получить SO_ORIGINAL_DST: {e}")
                    graceful_close(client_sock)
                    return

            logger.info(f"[{client_addr}] -> target {target_host}:{target_port}")

            try:
                client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except Exception:
                pass
            enable_tcp_keepalive(client_sock, cfg.get('keepidle'), cfg.get('keepintvl'), cfg.get('keepcnt'))

            # connect to upstream with retries
            max_retries = cfg.get('connect_retries', 3)
            backoff_base = cfg.get('retry_backoff', 0.5)
            last_err = None
            for attempt in range(1, max_retries + 1):
                try:
                    socks_sock = socket.create_connection((socks_host, socks_port), timeout=cfg.get('connect_timeout'))
                    break
                except Exception as e:
                    last_err = e
                    logger.warning(f"[{client_addr}] попытка {attempt}/{max_retries} подключиться к SOCKS {socks_host}:{socks_port} не удалась: {e}")
                    sleep_time = backoff_base * (2 ** (attempt - 1))
                    time.sleep(sleep_time)
            else:
                logger.error(f"[{client_addr}] не удалось подключиться к upstream SOCKS после {max_retries} попыток: {last_err}")
                graceful_close(client_sock)
                return

            try:
                socks5_connect_via(socks_sock, target_host, target_port, username=socks_user, password=socks_pass, timeout=cfg.get('connect_timeout'))
            except Exception as e:
                logger.error(f"[{client_addr}] SOCKS5 handshake/CONNECT failed: {e}")
                graceful_close(socks_sock)
                graceful_close(client_sock)
                return

            try:
                socks_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except Exception:
                pass
            enable_tcp_keepalive(socks_sock, cfg.get('keepidle'), cfg.get('keepintvl'), cfg.get('keepcnt'))

            # forwarding
            forward_loop(client_sock, socks_sock, client_addr, cfg, stats, conn_id)

        finally:
            duration = time.time() - stats['start_time']
            graceful_close(client_sock)
            if socks_sock:
                graceful_close(socks_sock)

            with _stats_lock:
                _stats['active_connections'] -= 1

            # unregister connection
            with _conns_lock:
                if conn_id in _conns:
                    # preserve bytes for logs if needed
                    _conns.pop(conn_id, None)

            logger.info(f"[{client_addr}] closed — duration: {duration:.2f}s, c->r: {stats['bytes_client_to_remote']} bytes, r->c: {stats['bytes_remote_to_client']} bytes")


# ---------- UDP server loop (unchanged behavior, IPv4) ----------

def udp_server_loop(listen_addr: str, listen_port: int, socks_host: str, socks_port: int,
                    socks_user: str, socks_pass: str, fixed_target, cfg):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((listen_addr, listen_port))
    sock.setblocking(False)
    logger.info(f"UDP listening on {listen_addr}:{listen_port}")

    sessions = {}
    sessions_lock = threading.Lock()
    session_timeout = cfg.get('udp_session_timeout', 60)

    # cleaner thread
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
                    logger.info("UDP session %s timed out and closed", k)
    tclean = threading.Thread(target=cleaner, daemon=True)
    tclean.start()

    logger.info(f"UDP server ready (session timeout {session_timeout}s)")

    while not shutdown_event.is_set():
        # receive
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
            # determine dst
            if fixed_target is not None:
                dst_host, dst_port = fixed_target
            else:
                # try SO_ORIGINAL_DST on the UDP socket (may not be supported)
                try:
                    od = sock.getsockopt(socket.SOL_IP, SO_ORIGINAL_DST, 128)
                    dst_host, dst_port = parse_original_dst(od)
                except Exception as e:
                    logger.warning("Cannot determine original dst for UDP packet from %s: %s — dropping. Use --target-host/--target-port for UDP.", client_addr, e)
                    continue

            # get/create session
            with sessions_lock:
                s = sessions.get(client_addr)
                if s is None or not s.alive:
                    try:
                        s = UDPSession(client_addr, socks_host, socks_port, socks_user, socks_pass, timeout=cfg.get('connect_timeout', 10), fixed_target=fixed_target)
                        s.start()
                        sessions[client_addr] = s
                        with _stats_lock:
                            _stats['udp_sessions'] += 1
                        logger.info("Created UDP session for %s -> relay %s:%d", client_addr, s.relay_addr[0], s.relay_addr[1])
                    except Exception as e:
                        logger.error("Failed to create UDP session for %s: %s", client_addr, e)
                        continue
            # forward
            try:
                s.send_from_client(dst_host, dst_port, data)
                logger.debug("UDP %s -> %s:%d (%d bytes) via relay", client_addr, dst_host, dst_port, len(data))
            except Exception as e:
                logger.debug("Failed to send from client %s via session: %s", client_addr, e)

        # poll sessions for replies (simple iteration)
        with sessions_lock:
            for client_k, s in list(sessions.items()):
                if not s.alive:
                    continue
                res = s.recv_from_relay()
                if res:
                    src_host, src_port, payload = res
                    try:
                        sock.sendto(payload, client_k)
                        logger.debug("UDP reply to %s from %s:%d (%d bytes)", client_k, src_host, src_port, len(payload))
                    except Exception as e:
                        logger.debug("Failed sendto client %s: %s", client_k, e)

    # cleanup
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


# ---------- Stats reporter ----------

def stats_reporter(interval=30):
    while not shutdown_event.wait(interval):
        with _stats_lock:
            ac = _stats['active_connections']
            tc = _stats['total_connections']
            bc2r = _stats['total_bytes_client_to_remote']
            br2c = _stats['total_bytes_remote_to_client']
            us = _stats['udp_sessions']
            u_c2r = _stats['bytes_udp_c2r']
            u_r2c = _stats['bytes_udp_r2c']
        logger.info(f"STATS active={ac} total_conn={tc} tcp_bytes_c2r={bc2r} tcp_bytes_r2c={br2c} udp_sessions={us} udp_c2r={u_c2r} udp_r2c={u_r2c}")


# ---------- Simple web UI ----------

class SimpleStatsHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ('/', '/index.html'):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'Not found')
            return

        # Build simple HTML page
        with _stats_lock:
            stats_snapshot = dict(_stats)
        with _conns_lock:
            conns_snapshot = dict(_conns)

        html = []
        html.append("<html><head><meta charset='utf-8'><title>t2s stats</title></head><body>")
        html.append("<h1>Transparent->SOCKS5 — stats</h1>")
        html.append("<h2>Global</h2>")
        html.append("<ul>")
        html.append(f"<li>active_connections: {stats_snapshot.get('active_connections')}</li>")
        html.append(f"<li>total_connections: {stats_snapshot.get('total_connections')}</li>")
        html.append(f"<li>tcp_bytes_client_to_remote: {stats_snapshot.get('total_bytes_client_to_remote')}</li>")
        html.append(f"<li>tcp_bytes_remote_to_client: {stats_snapshot.get('total_bytes_remote_to_client')}</li>")
        html.append(f"<li>udp_sessions: {stats_snapshot.get('udp_sessions')}</li>")
        html.append(f"<li>udp_bytes_c2r: {stats_snapshot.get('bytes_udp_c2r')}</li>")
        html.append(f"<li>udp_bytes_r2c: {stats_snapshot.get('bytes_udp_r2c')}</li>")
        html.append("</ul>")

        html.append("<h2>Active connections</h2>")
        if not conns_snapshot:
            html.append("<p>No active connections</p>")
        else:
            html.append("<table border='1' cellpadding='4'><tr><th>conn_id</th><th>client</th><th>started</th><th>bytes_c2r</th><th>bytes_r2c</th></tr>")
            for cid, info in conns_snapshot.items():
                html.append("<tr>")
                html.append(f"<td>{cid}</td>")
                html.append(f"<td>{info.get('client')}</td>")
                html.append(f"<td>{time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(info.get('start_time')))}</td>")
                html.append(f"<td>{info.get('bytes_c2r')}</td>")
                html.append(f"<td>{info.get('bytes_r2c')}</td>")
                html.append("</tr>")
            html.append("</table>")

        html.append("</body></html>")
        body = "\n".join(html).encode('utf-8')

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def start_web_server(port: int):
    def _serve():
        try:
            httpd = HTTPServer(('127.0.0.1', port), SimpleStatsHandler)
            logger.info("Web UI listening on 127.0.0.1:%d", port)
            # serve until shutdown_event set
            while not shutdown_event.is_set():
                httpd.handle_request()
        except Exception as e:
            logger.error("Web UI error: %s", e)
    t = threading.Thread(target=_serve, daemon=True)
    t.start()
    return t


# ---------- Signal handling ----------

def install_signal_handlers():
    def _term(signum, frame):
        logger.info("Received signal %d, shutting down...", signum)
        shutdown_event.set()
    signal.signal(signal.SIGTERM, _term)
    signal.signal(signal.SIGINT, _term)


# ---------- Server runner (supports modes) ----------

def run_server(listen_addr, listen_port, socks_host, socks_port, socks_user, socks_pass, fixed_target, cfg):
    mode = cfg.get('mode', 'tcp')  # 'tcp', 'udp', 'tcp-udp'
    # show startup parameters in log
    log_cfg = {
        'mode': mode,
        'listen_addr': listen_addr,
        'listen_port': listen_port,
        'socks_host': socks_host,
        'socks_port': socks_port,
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
        'udp_options': {
            'udp_listen_addr': cfg.get('udp_listen_addr'),
            'udp_listen_port': cfg.get('udp_listen_port'),
            'udp_session_timeout': cfg.get('udp_session_timeout'),
            'udp_buffer_size': cfg.get('udp_buffer_size')
        }
    }
    logger.info("Starting service with config: %s", log_cfg)

    tcp_sock = None
    udp_thread = None

    if mode in ('tcp', 'tcp-udp'):
        # prepare TCP listener (IPv4 only, following original behavior)
        tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp_sock.bind((listen_addr, listen_port))
        tcp_sock.listen(cfg.get('backlog', 128))
        logger.info(f"Listening on {listen_addr}:{listen_port} (TCP)")
        if fixed_target:
            logger.info(f"Forwarding all incoming connections to fixed target {fixed_target[0]}:{fixed_target[1]} via SOCKS {socks_host}:{socks_port}")
        else:
            logger.info(f"Transparent mode: will attempt SO_ORIGINAL_DST and forward via SOCKS {socks_host}:{socks_port}")
            logger.info("(Убедись, что iptables REDIRECT настроен и процесс имеет права для этого.)")

    max_conns = cfg.get('max_conns', 200)
    sem = threading.BoundedSemaphore(max_conns)

    # start stats reporter
    reporter = threading.Thread(target=stats_reporter, args=(cfg.get('stats_interval', 30),), daemon=True)
    reporter.start()

    # start UDP server if needed
    if mode in ('udp', 'tcp-udp'):
        udp_listen_addr = cfg.get('udp_listen_addr', listen_addr)
        udp_listen_port = cfg.get('udp_listen_port', listen_port + 1)
        udp_thread = threading.Thread(target=udp_server_loop, args=(udp_listen_addr, udp_listen_port, socks_host, socks_port, socks_user, socks_pass, fixed_target, cfg), daemon=True)
        udp_thread.start()

    try:
        if mode in ('tcp', 'tcp-udp'):
            # accept loop
            while not shutdown_event.is_set():
                try:
                    client_sock, client_addr = tcp_sock.accept()
                except OSError as e:
                    if e.errno == errno.EINTR:
                        continue
                    # if shutdown requested, break
                    if shutdown_event.is_set():
                        break
                    raise
                thr = threading.Thread(
                    target=handle_client,
                    args=(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg),
                    daemon=True
                )
                thr.start()
        else:
            # only UDP mode — wait until shutdown
            while not shutdown_event.is_set():
                time.sleep(1)
    except KeyboardInterrupt:
        logger.info("Stopping (KeyboardInterrupt)")
    finally:
        shutdown_event.set()
        try:
            if tcp_sock:
                tcp_sock.close()
        except Exception:
            pass
        logger.info("Server stopped.")


# ---------- CLI ----------

def parse_args():
    p = argparse.ArgumentParser(description="Transparent -> SOCKS5 (stdlib) — improved with UDP, IPv6 SO_ORIGINAL_DST, SIGTERM and web UI")
    p.add_argument("--listen-addr", default="127.0.0.1")
    p.add_argument("--listen-port", type=int, default=11290)
    p.add_argument("--socks-host", required=True, help="Upstream SOCKS5 host")
    p.add_argument("--socks-port", type=int, required=True, help="Upstream SOCKS5 port")
    p.add_argument("--socks-user", default=None, help="optional username for SOCKS5")
    p.add_argument("--socks-pass", default=None, help="optional password for SOCKS5")
    p.add_argument("--target-host", default=None, help="fixed target host (optional)")
    p.add_argument("--target-port", type=int, default=None, help="fixed target port (optional)")

    p.add_argument("--buffer-size", type=int, default=65536, help="read buffer size")
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

    # UDP options
    p.add_argument("--udp-listen-addr", default=None, help="address for UDP listener (default same as --listen-addr)")
    p.add_argument("--udp-listen-port", type=int, default=None, help="UDP listen port (default listen_port+1)")
    p.add_argument("--udp-session-timeout", type=int, default=60, help="idle timeout (seconds) for UDP sessions")
    p.add_argument("--udp-buffer-size", type=int, default=65536, help="read buffer size for UDP")

    # Web UI
    p.add_argument("--web-socket", action='store_true', help="Enable simple web UI for stats (no styling)")
    p.add_argument("--web-port", type=int, default=8000, help="Port for the web UI (default 8000)")

    p.add_argument("--logfile", default=None, help="path to log file")
    p.add_argument("--verbose", action='store_true', help="debug logging")
    return p.parse_args()


if __name__ == "__main__":
    args = parse_args()
    fixed_target = None
    if (args.target_host is not None) ^ (args.target_port is not None):
        print("Если используешь --target-host, укажи также --target-port.")
        sys.exit(2)
    if args.target_host is not None and args.target_port is not None:
        fixed_target = (args.target_host, args.target_port)

    # настроим логгер по аргументам
    level = logging.DEBUG if args.verbose else logging.INFO
    logger = setup_logger(level=level, logfile=args.logfile)

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
        'udp_listen_port': args.udp_listen_port if args.udp_listen_port is not None else (args.listen_port + 1),
        'udp_session_timeout': args.udp_session_timeout,
        'udp_buffer_size': args.udp_buffer_size,
    }

    # install signal handlers for graceful shutdown
    install_signal_handlers()

    # optional web UI
    if args.web_socket:
        start_web_server(args.web_port)

    run_server(args.listen_addr, args.listen_port, args.socks_host, args.socks_port, args.socks_user, args.socks_pass, fixed_target, cfg)
