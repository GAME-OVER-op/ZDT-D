#!/usr/bin/env python3
# transparent_to_socks5_stdlib_fixed.py
"""
Расширенная и автоматизированная версия transparent_to_socks5_stdlib_fixed.py.

Основные улучшения (кратко):
 - Ограничение количества одновременных подключений (параметр --max-conns).
 - Retry логика при подключении к upstream SOCKS5 (кол-во попыток и backoff).
 - Детальное логирование: уровни, время, client addr, target, chunk sizes (debug),
   суммарные байты в каждой сессии (info при закрытии), глобальные статистики.
 - Настраиваемые TCP-keepalive параметры через CLI (--keepidle/--keepintvl/--keepcnt).
 - Настройка буфера чтения (--buffer-size) и таймаут простоя/idle (--idle-timeout).
 - Автоматическое закрытие долгих неактивных соединений (idle timeout).
 - Семафор для контроля максимума потоков (защита от исчерпания FD).
 - Небольшие оптимизации и более аккуратный shutdown.

Работает только на стандартной библиотеке.
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
from datetime import datetime

# Значение для SO_ORIGINAL_DST (обычно 80 в linux-headers)
SO_ORIGINAL_DST = 80

# Глобальные счётчики
_stats_lock = threading.Lock()
_stats = {
    'active_connections': 0,
    'total_connections': 0,
    'total_bytes_client_to_remote': 0,
    'total_bytes_remote_to_client': 0,
}


def setup_logger(level=logging.INFO, logfile=None):
    fmt = '%(asctime)s [%(levelname)s] %(message)s'
    if logfile:
        logging.basicConfig(level=level, format=fmt, filename=logfile)
    else:
        logging.basicConfig(level=level, format=fmt)
    return logging.getLogger('t2s')

logger = setup_logger()


def get_original_dst(conn):
    od = conn.getsockopt(socket.SOL_IP, SO_ORIGINAL_DST, 16)
    family, port = struct.unpack_from('!HH', od, 0)
    ip_bytes = od[4:8]
    ip_str = socket.inet_ntoa(ip_bytes)
    return ip_str, port


def socks5_connect_via(sock, target_host, target_port, username=None, password=None, timeout=None):
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


def forward_loop(a, b, client_addr, cfg, stats):
    # cfg: dict with buffer_size and idle_timeout and log_level
    buffer_size = cfg.get('buffer_size', 65536)
    idle_timeout = cfg.get('idle_timeout', 300)
    last_activity = time.time()

    try:
        a.setblocking(True)
        b.setblocking(True)
    except Exception:
        pass

    while True:
        timeout = 1.0  # регулярно проверяем idle
        try:
            r, _, _ = select.select([a, b], [], [], timeout)
        except OSError as e:
            if e.errno == errno.EINTR:
                continue
            logger.error(f"select error: {e}")
            break

        if not r:
            # проверим idle
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
                # peer closed
                logger.debug(f"[{client_addr}] peer closed fd {s.fileno()}")
                return

            now = time.time()
            last_activity = now

            dst = b if s is a else a
            # логируем размер пакета на DEBUG, на INFO — агрегированные данные
            logger.debug(f"[{client_addr}] chunk {len(data)} bytes {'client->remote' if s is a else 'remote->client'}")

            try:
                dst.sendall(data)
            except BrokenPipeError:
                logger.debug(f"[{client_addr}] BrokenPipe writing to fd {dst.fileno()}")
                return
            except Exception as e:
                logger.debug(f"[{client_addr}] send error to fd {dst.fileno()}: {e}")
                return

            # обновляем глобальную статистику
            with _stats_lock:
                if s is a:
                    _stats['total_bytes_client_to_remote'] += len(data)
                    stats['bytes_client_to_remote'] += len(data)
                else:
                    _stats['total_bytes_remote_to_client'] += len(data)
                    stats['bytes_remote_to_client'] += len(data)


def graceful_close(sock):
    try:
        sock.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    try:
        sock.close()
    except Exception:
        pass


def handle_client(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg):
    # счётчики для этой сессии
    stats = {
        'bytes_client_to_remote': 0,
        'bytes_remote_to_client': 0,
        'start_time': time.time(),
    }

    with sem:  # ограничитель параллельных сессий
        with _stats_lock:
            _stats['active_connections'] += 1
            _stats['total_connections'] += 1

        try:
            # определим цель
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

            # попытки подключения к upstream
            max_retries = cfg.get('connect_retries', 3)
            backoff_base = cfg.get('retry_backoff', 0.5)
            socks_sock = None
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
            forward_loop(client_sock, socks_sock, client_addr, cfg, stats)

        finally:
            duration = time.time() - stats['start_time']
            graceful_close(client_sock)
            if 'socks_sock' in locals() and socks_sock:
                graceful_close(socks_sock)

            with _stats_lock:
                _stats['active_connections'] -= 1

            logger.info(f"[{client_addr}] closed — duration: {duration:.2f}s, c->r: {stats['bytes_client_to_remote']} bytes, r->c: {stats['bytes_remote_to_client']} bytes")


def stats_reporter(interval=30):
    while True:
        time.sleep(interval)
        with _stats_lock:
            ac = _stats['active_connections']
            tc = _stats['total_connections']
            bc2r = _stats['total_bytes_client_to_remote']
            br2c = _stats['total_bytes_remote_to_client']
        logger.info(f"STATS active={ac} total_conn={tc} total_bytes_c2r={bc2r} total_bytes_r2c={br2c}")


def run_server(listen_addr, listen_port, socks_host, socks_port, socks_user, socks_pass, fixed_target, cfg):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((listen_addr, listen_port))
    s.listen(cfg.get('backlog', 128))

    logger.info(f"Listening on {listen_addr}:{listen_port}")
    if fixed_target:
        logger.info(f"Forwarding all incoming connections to fixed target {fixed_target[0]}:{fixed_target[1]} via SOCKS {socks_host}:{socks_port}")
    else:
        logger.info(f"Transparent mode: will attempt SO_ORIGINAL_DST and forward via SOCKS {socks_host}:{socks_port}")
        logger.info("(Убедись, что iptables REDIRECT настроен и процесс имеет права для этого.)")

    max_conns = cfg.get('max_conns', 200)
    sem = threading.BoundedSemaphore(max_conns)

    # Запускаем фоновый репортер статистики
    reporter = threading.Thread(target=stats_reporter, args=(cfg.get('stats_interval', 30),), daemon=True)
    reporter.start()

    try:
        while True:
            try:
                client_sock, client_addr = s.accept()
            except OSError as e:
                if e.errno == errno.EINTR:
                    continue
                raise

            thr = threading.Thread(
                target=handle_client,
                args=(client_sock, client_addr, socks_host, socks_port, socks_user, socks_pass, fixed_target, sem, cfg),
                daemon=True
            )
            thr.start()
    except KeyboardInterrupt:
        logger.info("Stopping (KeyboardInterrupt)")
    finally:
        s.close()


def parse_args():
    p = argparse.ArgumentParser(description="Transparent -> SOCKS5 (stdlib) — improved")
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
    }

    run_server(args.listen_addr, args.listen_port, args.socks_host, args.socks_port, args.socks_user, args.socks_pass, fixed_target, cfg)
