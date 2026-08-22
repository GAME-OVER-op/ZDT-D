#!/usr/bin/env bash
set -Eeuo pipefail
shopt -s nullglob
export DEBIAN_FRONTEND=noninteractive

ZDT_ROOT=${ZDT_ROOT:-/opt/zdt-vps}
ZDT_ETC=${ZDT_ETC:-/etc/zdt-vps}
ZDT_STATE=${ZDT_STATE:-/var/lib/zdt-vps}
ZDT_LOG=${ZDT_LOG:-/var/log/zdt-vps}
ZDT_BACKUP=$ZDT_STATE/backups
SYSTEMD_DIR=/etc/systemd/system
OPENVPN_DIR=/etc/openvpn/server
WIREGUARD_DIR=/etc/wireguard
SYSCTL_FILE=/etc/sysctl.d/99-zdt-vps-forward.conf
FIREWALL_CHAIN=ZDT_VPS_INPUT
CURRENT_STAGE=initializing
TX_BACKUP=
TX_ACTIVE=0

stage() { CURRENT_STAGE="$*"; printf 'ZDT_STAGE=%s\n' "$CURRENT_STAGE"; }
info() { printf 'ZDT_INFO=%s\n' "$*"; }
warn() { printf 'ZDT_WARNING=%s\n' "$*"; }
die() { printf 'ZDT_ERROR=%s\n' "$*" >&2; return 1; }
b64() { printf '%s' "$1" | base64 -w0; }
valid_id() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$ ]]; }
valid_port() { [[ "$1" =~ ^[0-9]+$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }
q() { printf '%q' "$1"; }
profile_dir() { printf '%s/profiles/%s/%s' "$ZDT_STATE" "$1" "$2"; }
load_meta() { source "$(profile_dir "$1" "$2")/meta.env"; }
ensure_dirs() { mkdir -p "$ZDT_ROOT/bin" "$ZDT_ETC" "$ZDT_STATE/services" "$ZDT_STATE/profiles" "$ZDT_LOG" "$ZDT_BACKUP"; }


stats_port_reserved() {
  local port=$1 meta
  for meta in "$ZDT_STATE"/profiles/{xray,hysteria2}/*/meta.env; do
    [[ -f $meta ]] || continue
    grep -Eq "^STATS_PORT=${port}$" "$meta" && return 0
  done
  ss -H -lnt "sport = :$port" 2>/dev/null | grep -q .
}
next_stats_port() {
  local port
  for port in $(seq 29000 29999); do
    if ! stats_port_reserved "$port"; then
      printf '%s\n' "$port"
      return 0
    fi
  done
  die 'No free local statistics port is available'
}

unit_is_active() { systemctl is-active --quiet "$1" 2>/dev/null; }
unit_is_enabled() { systemctl is-enabled --quiet "$1" 2>/dev/null; }
record_unit_state() {
  local unit=$1 active=0 enabled=0
  unit_is_active "$unit" && active=1 || true
  unit_is_enabled "$unit" && enabled=1 || true
  printf '%s|%s|%s\n' "$unit" "$active" "$enabled" >> "$TX_BACKUP/external-units.env"
}
restore_unit_states() {
  local file=$1 unit active enabled
  [[ -f $file ]] || return 0
  while IFS='|' read -r unit active enabled; do
    [[ -n $unit ]] || continue
    if [[ $enabled == 1 ]]; then systemctl enable "$unit" >/dev/null 2>&1 || true; else systemctl disable "$unit" >/dev/null 2>&1 || true; fi
    if [[ $active == 1 ]]; then systemctl start "$unit" >/dev/null 2>&1 || true; else systemctl stop "$unit" >/dev/null 2>&1 || true; fi
  done < "$file"
}

managed_units() {
  systemctl list-unit-files --no-legend 2>/dev/null | awk '{print $1}' | grep -E '^(zdt-vps-firewall\.service|zdt-dnscrypt\.service|zdt-openvpn-net-.*\.service|zdt-xray-.*\.service|zdt-hysteria2-.*\.service|openvpn-server@zdt-.*\.service|wg-quick@zdtwg.*\.service)$' || true
}
stop_managed_units() {
  local units=()
  mapfile -t units < <(managed_units)
  (( ${#units[@]} == 0 )) || systemctl stop "${units[@]}" >/dev/null 2>&1 || true
}
restore_managed_units() {
  systemctl daemon-reload >/dev/null 2>&1 || true
  [[ -x $ZDT_ROOT/bin/rebuild-firewall && -f $SYSTEMD_DIR/zdt-vps-firewall.service ]] && systemctl enable --now zdt-vps-firewall.service >/dev/null 2>&1 || true
  [[ -f $ZDT_STATE/services/dnscrypt.version && -f $SYSTEMD_DIR/zdt-dnscrypt.service ]] && systemctl enable --now zdt-dnscrypt.service >/dev/null 2>&1 || true
  local p
  for p in "$ZDT_STATE/profiles/openvpn"/*; do
    [[ -f $p/meta.env ]] || continue; source "$p/meta.env"
    systemctl enable --now "openvpn-server@zdt-$ID.service" "zdt-openvpn-net-$ID.service" >/dev/null 2>&1 || true
  done
  for p in "$ZDT_STATE/profiles/wireproxy"/*; do
    [[ -f $p/meta.env ]] || continue; source "$p/meta.env"
    systemctl enable --now "wg-quick@$IFACE.service" >/dev/null 2>&1 || true
  done
  for p in "$ZDT_STATE/profiles/xray"/*; do
    [[ -f $p/meta.env ]] || continue; source "$p/meta.env"
    systemctl enable --now "zdt-xray-$ID.service" >/dev/null 2>&1 || true
  done
  for p in "$ZDT_STATE/profiles/hysteria2"/*; do
    [[ -f $p/meta.env ]] || continue; source "$p/meta.env"
    systemctl enable --now "zdt-hysteria2-$ID.service" >/dev/null 2>&1 || true
  done
}

begin_tx() {
  ensure_dirs
  TX_BACKUP="$ZDT_BACKUP/$(date +%Y%m%d_%H%M%S)_$RANDOM"
  mkdir -p "$TX_BACKUP"
  : > "$TX_BACKUP/external-units.env"
  record_unit_state systemd-resolved.service
  record_unit_state dnscrypt-proxy.service
  record_unit_state dnscrypt-proxy.socket
  record_unit_state dnscrypt-proxy-resolvconf.service
  tar -czpf "$TX_BACKUP/resolv.tar.gz" /etc/resolv.conf 2>/dev/null || true

  local paths=() p
  for p in "$ZDT_ROOT" "$ZDT_ETC" "$ZDT_STATE/services" "$ZDT_STATE/profiles" \
    "$ZDT_STATE/dnscrypt-original" "$ZDT_STATE/dnscrypt-cache" \
    "$SYSCTL_FILE" "$SYSTEMD_DIR"/zdt-vps-firewall.service "$SYSTEMD_DIR"/zdt-dnscrypt.service \
    "$SYSTEMD_DIR"/zdt-openvpn-net-*.service "$SYSTEMD_DIR"/zdt-xray-*.service \
    "$SYSTEMD_DIR"/zdt-hysteria2-*.service "$SYSTEMD_DIR"/openvpn-server@zdt-*.service.d "$OPENVPN_DIR"/zdt-*.conf \
    "$WIREGUARD_DIR"/zdtwg*.conf /etc/letsencrypt/renewal-hooks/deploy/zdt-vps-reload; do
    [[ -e $p || -L $p ]] && paths+=("$p")
  done
  if (( ${#paths[@]} > 0 )); then tar -czpf "$TX_BACKUP/state.tar.gz" "${paths[@]}" 2>/dev/null || true; fi
  TX_ACTIVE=1
  printf 'ZDT_BACKUP=%s\n' "$TX_BACKUP"
}

cleanup_managed_files() {
  stop_managed_units
  rm -rf "$ZDT_ROOT" "$ZDT_ETC" "$ZDT_STATE/services" "$ZDT_STATE/profiles" "$ZDT_STATE/certificates" \
    "$ZDT_STATE/dnscrypt-original" "$ZDT_STATE/dnscrypt-cache"
  rm -f "$SYSTEMD_DIR"/zdt-vps-firewall.service "$SYSTEMD_DIR"/zdt-dnscrypt.service \
    "$SYSTEMD_DIR"/zdt-openvpn-net-*.service "$SYSTEMD_DIR"/zdt-xray-*.service \
    "$SYSTEMD_DIR"/zdt-hysteria2-*.service "$OPENVPN_DIR"/zdt-*.conf \
    "$WIREGUARD_DIR"/zdtwg*.conf "$SYSCTL_FILE"
  rm -rf "$SYSTEMD_DIR"/openvpn-server@zdt-*.service.d 2>/dev/null || true
  rm -f /etc/letsencrypt/renewal-hooks/deploy/zdt-vps-reload
  while iptables -w 5 -D INPUT -j "$FIREWALL_CHAIN" 2>/dev/null; do :; done
  iptables -w 5 -F "$FIREWALL_CHAIN" 2>/dev/null || true
  iptables -w 5 -X "$FIREWALL_CHAIN" 2>/dev/null || true
  for iface in 'zdtun+' 'zdtwg+'; do
    while iptables -w 5 -t nat -D PREROUTING -i "$iface" -j ZDT_VPS_DNS 2>/dev/null; do :; done
  done
  iptables -w 5 -t nat -F ZDT_VPS_DNS 2>/dev/null || true
  iptables -w 5 -t nat -X ZDT_VPS_DNS 2>/dev/null || true
}

rollback() {
  local code=$?
  if (( TX_ACTIVE == 1 )); then
    set +e
    printf 'ZDT_ROLLBACK=started\n'
    cleanup_managed_files
    rollback_certificates
    if [[ -f $TX_BACKUP/state.tar.gz ]]; then tar -xzpf "$TX_BACKUP/state.tar.gz" -C / >/dev/null 2>&1 || true; fi
    rm -f /etc/resolv.conf
    if [[ -f $TX_BACKUP/resolv.tar.gz ]]; then tar -xzpf "$TX_BACKUP/resolv.tar.gz" -C / >/dev/null 2>&1 || true; fi
    restore_unit_states "$TX_BACKUP/external-units.env"
    restore_managed_units
    printf 'ZDT_ROLLBACK=completed\n'
  fi
  printf 'ZDT_FAILED_STAGE=%s\n' "$CURRENT_STAGE" >&2
  exit "$code"
}
trap rollback ERR

commit_tx() {
  TX_ACTIVE=0
  printf 'ZDT_COMMIT=ok\n'
  find "$ZDT_BACKUP" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null | sort -nr | awk 'NR>10 {print $2}' | xargs -r rm -rf
}

backup_certificate_for_tx() {
  local domain=$1 marker archive
  (( TX_ACTIVE == 1 )) || return 0
  marker="$TX_BACKUP/certificate-${domain}.recorded"
  [[ -e $marker ]] && return 0
  touch "$marker"
  if [[ -e /etc/letsencrypt/live/$domain || -e /etc/letsencrypt/archive/$domain || -e /etc/letsencrypt/renewal/$domain.conf ]]; then
    archive="$TX_BACKUP/certificate-${domain}.tar.gz"
    local paths=()
    [[ -e /etc/letsencrypt/live/$domain ]] && paths+=("/etc/letsencrypt/live/$domain")
    [[ -e /etc/letsencrypt/archive/$domain ]] && paths+=("/etc/letsencrypt/archive/$domain")
    [[ -e /etc/letsencrypt/renewal/$domain.conf ]] && paths+=("/etc/letsencrypt/renewal/$domain.conf")
    tar -czpf "$archive" "${paths[@]}" 2>/dev/null || true
    printf '%s|%s\n' "$domain" "$archive" >> "$TX_BACKUP/existing-certificates.list"
  else
    printf '%s\n' "$domain" >> "$TX_BACKUP/new-certificates.list"
  fi
}

delete_certificate_files() {
  local domain=$1
  if command -v certbot >/dev/null 2>&1; then certbot delete --cert-name "$domain" --non-interactive >/dev/null 2>&1 || true; fi
  rm -rf "/etc/letsencrypt/live/$domain" "/etc/letsencrypt/archive/$domain"
  rm -f "/etc/letsencrypt/renewal/$domain.conf"
}

rollback_certificates() {
  local domain archive
  if [[ -f $TX_BACKUP/new-certificates.list ]]; then
    while IFS= read -r domain; do [[ -n $domain ]] && delete_certificate_files "$domain"; done < "$TX_BACKUP/new-certificates.list"
  fi
  if [[ -f $TX_BACKUP/existing-certificates.list ]]; then
    while IFS='|' read -r domain archive; do
      [[ -n $domain ]] || continue
      delete_certificate_files "$domain"
      [[ -f $archive ]] && tar -xzpf "$archive" -C / >/dev/null 2>&1 || true
    done < "$TX_BACKUP/existing-certificates.list"
  fi
}

certificate_in_use() {
  local domain=$1 p
  for p in "$ZDT_STATE/profiles/xray"/*; do
    [[ -f $p/meta.env ]] || continue
    unset DOMAIN MODE
    source "$p/meta.env"
    [[ ${MODE:-} == ws && ${DOMAIN:-} == "$domain" ]] && return 0
  done
  return 1
}

release_certificate() {
  local domain=$1 state owned=0
  [[ -n $domain ]] || return 0
  state="$ZDT_STATE/certificates/$domain.env"
  certificate_in_use "$domain" && return 0
  if [[ -f $state ]]; then
    unset OWNED
    source "$state"
    owned=${OWNED:-0}
  fi
  if [[ $owned == 1 ]]; then
    backup_certificate_for_tx "$domain"
    delete_certificate_files "$domain"
  fi
  rm -f "$state"
}

with_tx() { begin_tx; "$@"; commit_tx; }

require_root() { [[ $(id -u) -eq 0 ]] || die 'Root privileges are required'; }
check_platform() {
  stage 'Checking operating system and architecture'
  local arch; arch=$(uname -m)
  [[ $arch == x86_64 || $arch == amd64 ]] || die 'Unsupported architecture. Only x86_64/amd64 is supported.'
  [[ -r /etc/os-release ]] || die '/etc/os-release is missing'
  . /etc/os-release
  case "$ID:$VERSION_ID" in
    ubuntu:20.04|ubuntu:22.04|ubuntu:24.04) ;;
    debian:11|debian:12|debian:13|debian:11.*|debian:12.*|debian:13.*) ;;
    *) die "Unsupported system: ${PRETTY_NAME:-$ID $VERSION_ID}" ;;
  esac
  command -v systemctl >/dev/null || die 'systemd is required'
  command -v apt-get >/dev/null || die 'apt is required'
}
apt_install() { apt-get update -y; apt-get install -y --no-install-recommends "$@"; }

port_free() {
  local proto=$1 port=$2
  valid_port "$port" || return 1
  if [[ $proto == tcp ]]; then ! ss -H -lnt "sport = :$port" 2>/dev/null | grep -q .; else ! ss -H -lnu "sport = :$port" 2>/dev/null | grep -q .; fi
}
managed_port_in_use() {
  local proto=$1 port=$2 dir meta
  for dir in "$ZDT_STATE/profiles"/{openvpn,xray,hysteria2,wireproxy}/*; do
    meta=$dir/meta.env; [[ -f $meta ]] || continue
    unset PROTOCOL PORT
    source "$meta"
    [[ ${PROTOCOL:-} == "$proto" && ${PORT:-} == "$port" ]] && return 0
  done
  return 1
}
require_free_port() {
  local proto=$1 port=$2
  valid_port "$port" || die "Invalid port: $port"
  managed_port_in_use "$proto" "$port" && die "Port $port/$proto is already assigned to a ZDT-D profile"
  port_free "$proto" "$port" || die "Port $port/$proto is already occupied"
}
next_index() {
  local kind=$1 i p used
  for ((i=1; i<=250; i++)); do
    used=0
    for p in "$ZDT_STATE/profiles/$kind"/*; do
      [[ -f $p/meta.env ]] || continue; unset INDEX; source "$p/meta.env"
      [[ ${INDEX:-0} == "$i" ]] && { used=1; break; }
    done
    (( used == 0 )) && { printf '%s\n' "$i"; return 0; }
  done
  die "No free network index is available for $kind"
}
next_wire_client_octet() {
  local dir=$1 i c used
  for ((i=2; i<=254; i++)); do
    used=0
    for c in "$dir/clients"/*; do
      [[ -f $c/ip ]] || continue
      [[ $(cat "$c/ip") == *.$i ]] && { used=1; break; }
    done
    (( used == 0 )) && { printf '%s\n' "$i"; return 0; }
  done
  die 'The WireProxy profile has no free client addresses'
}

external_service_warning() {
  local kind=$1
  case "$kind" in
    dnscrypt) systemctl list-unit-files 2>/dev/null | grep -q '^dnscrypt-proxy' && warn 'An external dnscrypt-proxy installation was detected. ZDT-D will preserve its state and use isolated paths.' || true ;;
    openvpn) find /etc/openvpn -type f -name '*.conf' ! -name 'zdt-*' 2>/dev/null | grep -q . && warn 'Existing OpenVPN configurations were detected. ZDT-D profiles are isolated and are not adopted.' || true ;;
    xray) command -v xray >/dev/null 2>&1 && warn 'An external Xray installation was detected. ZDT-D will install an isolated copy.' || true ;;
    hysteria2) command -v hysteria >/dev/null 2>&1 && warn 'An external Hysteria installation was detected. ZDT-D will install an isolated copy.' || true ;;
    wireproxy) find /etc/wireguard -type f -name '*.conf' ! -name 'zdtwg*' 2>/dev/null | grep -q . && warn 'Existing WireGuard configurations were detected. ZDT-D profiles are isolated and are not adopted.' || true ;;
  esac
}

service_installed() { local kind=$1; [[ -e $ZDT_STATE/services/$kind || -e $ZDT_STATE/services/$kind.version ]]; }
any_managed_service() {
  local k
  for k in dnscrypt openvpn xray hysteria2 wireproxy; do service_installed "$k" && return 0; done
  return 1
}

write_firewall_script() {
  cat > "$ZDT_ROOT/bin/rebuild-firewall" <<'FIREWALL'
#!/usr/bin/env bash
set -Eeuo pipefail
shopt -s nullglob
STATE=/var/lib/zdt-vps
CHAIN=ZDT_VPS_INPUT
DNS_CHAIN=ZDT_VPS_DNS
iptables -w 5 -N "$CHAIN" 2>/dev/null || true
iptables -w 5 -F "$CHAIN"
iptables -w 5 -t nat -N "$DNS_CHAIN" 2>/dev/null || true
iptables -w 5 -t nat -F "$DNS_CHAIN"
for iface in 'zdtun+' 'zdtwg+'; do
  while iptables -w 5 -t nat -D PREROUTING -i "$iface" -j "$DNS_CHAIN" 2>/dev/null; do :; done
done
if [[ -f $STATE/services/dnscrypt.version ]]; then
  for proto in udp tcp; do
    iptables -w 5 -A "$CHAIN" -i lo -p "$proto" --dport 53 -j ACCEPT
    iptables -w 5 -A "$CHAIN" -i 'tun+' -p "$proto" --dport 53 -j ACCEPT
    iptables -w 5 -A "$CHAIN" -i 'zdt+' -p "$proto" --dport 53 -j ACCEPT
    iptables -w 5 -t nat -A "$DNS_CHAIN" -p "$proto" --dport 53 -j REDIRECT --to-ports 53
    if [[ $proto == udp ]]; then
      iptables -w 5 -A "$CHAIN" -p udp --dport 53 -j REJECT --reject-with icmp-port-unreachable
    else
      iptables -w 5 -A "$CHAIN" -p tcp --dport 53 -j REJECT --reject-with tcp-reset
    fi
  done
  iptables -w 5 -t nat -I PREROUTING 1 -i 'zdtwg+' -j "$DNS_CHAIN"
  iptables -w 5 -t nat -I PREROUTING 1 -i 'zdtun+' -j "$DNS_CHAIN"
fi
for meta in "$STATE"/profiles/{openvpn,xray,hysteria2,wireproxy}/*/meta.env; do
  [[ -f $meta ]] || continue
  unset PORT PROTOCOL
  # shellcheck disable=SC1090
  source "$meta"
  [[ ${PORT:-} =~ ^[0-9]+$ && ( ${PROTOCOL:-} == tcp || ${PROTOCOL:-} == udp ) ]] || continue
  iptables -w 5 -A "$CHAIN" -p "$PROTOCOL" --dport "$PORT" -j ACCEPT
done
while iptables -w 5 -D INPUT -j "$CHAIN" 2>/dev/null; do :; done
iptables -w 5 -I INPUT 1 -j "$CHAIN"
FIREWALL
  chmod 700 "$ZDT_ROOT/bin/rebuild-firewall"
  cat > "$SYSTEMD_DIR/zdt-vps-firewall.service" <<UNIT
[Unit]
Description=ZDT-D managed VPS firewall rules
After=network-pre.target
Before=network-online.target zdt-dnscrypt.service
[Service]
Type=oneshot
ExecStart=$ZDT_ROOT/bin/rebuild-firewall
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
UNIT
}
refresh_firewall() {
  if any_managed_service; then
    write_firewall_script
    systemctl daemon-reload
    systemctl enable zdt-vps-firewall.service >/dev/null 2>&1 || true
    systemctl restart zdt-vps-firewall.service
  else
    systemctl disable --now zdt-vps-firewall.service >/dev/null 2>&1 || true
    while iptables -w 5 -D INPUT -j "$FIREWALL_CHAIN" 2>/dev/null; do :; done
    iptables -w 5 -F "$FIREWALL_CHAIN" 2>/dev/null || true
    iptables -w 5 -X "$FIREWALL_CHAIN" 2>/dev/null || true
    for iface in 'zdtun+' 'zdtwg+'; do while iptables -w 5 -t nat -D PREROUTING -i "$iface" -j ZDT_VPS_DNS 2>/dev/null; do :; done; done
    iptables -w 5 -t nat -F ZDT_VPS_DNS 2>/dev/null || true
    iptables -w 5 -t nat -X ZDT_VPS_DNS 2>/dev/null || true
    rm -f "$SYSTEMD_DIR/zdt-vps-firewall.service" "$ZDT_ROOT/bin/rebuild-firewall"
    systemctl daemon-reload
  fi
}

persist_dns_original_state() {
  local dir=$ZDT_STATE/dnscrypt-original
  [[ -d $dir ]] && return 0
  mkdir -p "$dir"
  cp -f "$TX_BACKUP/external-units.env" "$dir/external-units.env"
  cp -f "$TX_BACKUP/resolv.tar.gz" "$dir/resolv.tar.gz" 2>/dev/null || true
}
write_temporary_resolv() {
  rm -f /etc/resolv.conf
  cat > /etc/resolv.conf <<'RESOLV'
nameserver 1.1.1.1
nameserver 1.0.0.1
nameserver 9.9.9.9
options edns0 timeout:2 attempts:2
RESOLV
}
ensure_download_dns() {
  local host
  for host in github.com raw.githubusercontent.com; do
    if ! getent ahostsv4 "$host" >/dev/null 2>&1; then
      stage 'Preparing temporary DNS for downloads'
      warn "System DNS cannot resolve $host; using temporary bootstrap resolvers"
      persist_dns_original_state
      write_temporary_resolv
      for host in github.com raw.githubusercontent.com; do
        getent ahostsv4 "$host" >/dev/null 2>&1 || die "Unable to resolve $host even with temporary bootstrap DNS"
      done
      return 0
    fi
  done
}
prepare_dns_port() {
  persist_dns_original_state
  systemctl stop zdt-dnscrypt.service >/dev/null 2>&1 || true
  for unit in systemd-resolved.service dnscrypt-proxy.socket dnscrypt-proxy-resolvconf.service dnscrypt-proxy.service; do
    systemctl disable --now "$unit" >/dev/null 2>&1 || true
  done
  sleep 1
  write_temporary_resolv
  if ss -H -lntup 2>/dev/null | grep -qE '(^|[[:space:]])[^[:space:]]+[[:space:]].*:53([[:space:]]|$)'; then
    ss -lntup | grep ':53' >&2 || true
    die 'Port 53 is occupied by a service that ZDT-D cannot safely replace'
  fi
}
restore_dns_original_state() {
  local dir=$ZDT_STATE/dnscrypt-original
  [[ -d $dir ]] || return 0
  rm -f /etc/resolv.conf
  [[ -f $dir/resolv.tar.gz ]] && tar -xzpf "$dir/resolv.tar.gz" -C / >/dev/null 2>&1 || true
  restore_unit_states "$dir/external-units.env"
  rm -rf "$dir"
}
install_dnscrypt() {
  external_service_warning dnscrypt
  ensure_download_dns
  stage 'Installing DNSCrypt packages'
  apt_install curl ca-certificates tar iptables iproute2 dnsutils python3
  ensure_dirs

  local version=2.1.15 archive=dnscrypt-proxy-linux_x86_64-2.1.15.tar.gz tmp config_template
  tmp=$(mktemp -d)
  config_template="$tmp/example-dnscrypt-proxy.toml"
  stage 'Downloading DNSCrypt files'
  curl -fL --retry 4 --retry-delay 2 --connect-timeout 15 \
    "https://github.com/DNSCrypt/dnscrypt-proxy/releases/download/${version}/${archive}" -o "$tmp/$archive"
  curl -fL --retry 4 --retry-delay 2 --connect-timeout 15 \
    "https://raw.githubusercontent.com/DNSCrypt/dnscrypt-proxy/${version}/dnscrypt-proxy/example-dnscrypt-proxy.toml" -o "$config_template"
  tar -xzf "$tmp/$archive" -C "$tmp"
  [[ -x $tmp/linux-x86_64/dnscrypt-proxy ]] || die 'The DNSCrypt archive does not contain the expected x86_64 binary'

  stage 'Preparing local DNS port'
  prepare_dns_port
  install -m 0755 "$tmp/linux-x86_64/dnscrypt-proxy" "$ZDT_ROOT/bin/dnscrypt-proxy"

  stage 'Configuring DNSCrypt on TCP/UDP 53'
  mkdir -p "$ZDT_ETC/dnscrypt" "$ZDT_STATE/dnscrypt-cache"
  cp -f "$config_template" "$ZDT_ETC/dnscrypt/dnscrypt-proxy.toml"
  python3 - "$ZDT_ETC/dnscrypt/dnscrypt-proxy.toml" <<'PYCONF'
import re,sys
path=sys.argv[1]
with open(path,encoding='utf-8') as f: lines=f.readlines()
values={
 'listen_addresses': "['0.0.0.0:53']",
 'max_clients': '250',
 'ipv4_servers': 'true',
 'ipv6_servers': 'false',
 'dnscrypt_servers': 'true',
 'doh_servers': 'true',
 'odoh_servers': 'false',
 'require_dnssec': 'false',
 'require_nolog': 'true',
 'require_nofilter': 'true',
 'force_tcp': 'false',
 'timeout': '5000',
 'keepalive': '30',
 'cert_refresh_delay': '240',
 'bootstrap_resolvers': "['1.1.1.1:53', '1.0.0.1:53', '9.9.9.9:53']",
 'ignore_system_dns': 'true',
 'netprobe_address': "'1.1.1.1:53'",
 'cache': 'true',
 'cache_size': '4096',
 'cache_min_ttl': '60',
 'cache_max_ttl': '86400',
 'cache_neg_min_ttl': '60',
 'cache_neg_max_ttl': '600',
 'http3': 'false',
 'http3_probe': 'false',
}
append_if_missing={'listen_addresses','bootstrap_resolvers','netprobe_address'}
for key,value in values.items():
    pattern=re.compile(r'^\s*#?\s*'+re.escape(key)+r'\s*=')
    for i,line in enumerate(lines):
        if pattern.match(line):
            lines[i]=f'{key} = {value}\n'
            break
    else:
        if key in append_if_missing:
            insert=next((i for i,line in enumerate(lines) if line.lstrip().startswith('[')),len(lines))
            lines.insert(insert,f'{key} = {value}\n')
with open(path,'w',encoding='utf-8') as f: f.writelines(lines)
PYCONF
  "$ZDT_ROOT/bin/dnscrypt-proxy" -check -config "$ZDT_ETC/dnscrypt/dnscrypt-proxy.toml" || die 'DNSCrypt configuration validation failed'

  cat > "$SYSTEMD_DIR/zdt-dnscrypt.service" <<UNIT
[Unit]
Description=ZDT-D managed DNSCrypt
After=network-online.target zdt-vps-firewall.service
Wants=network-online.target
Requires=zdt-vps-firewall.service
[Service]
Type=simple
WorkingDirectory=$ZDT_ETC/dnscrypt
ExecStart=$ZDT_ROOT/bin/dnscrypt-proxy -config $ZDT_ETC/dnscrypt/dnscrypt-proxy.toml
Restart=on-failure
RestartSec=3
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE CAP_SETUID CAP_SETGID
[Install]
WantedBy=multi-user.target
UNIT
  printf '%s\n' "$version" > "$ZDT_STATE/services/dnscrypt.version"
  refresh_firewall
  stage 'Starting and verifying DNSCrypt'
  systemctl daemon-reload
  systemctl enable --now zdt-dnscrypt.service

  local listener_ok=0 dns_udp_ok=0 dns_tcp_ok=0 host
  for _ in $(seq 1 30); do
    if unit_is_active zdt-dnscrypt.service && \
       ss -H -lnu 'sport = :53' 2>/dev/null | grep -q . && \
       ss -H -lnt 'sport = :53' 2>/dev/null | grep -q .; then listener_ok=1; break; fi
    sleep 1
  done
  (( listener_ok == 1 )) || {
    systemctl status zdt-dnscrypt.service --no-pager >&2 || true
    journalctl -u zdt-dnscrypt.service -n 160 --no-pager >&2 || true
    die 'DNSCrypt did not start listening on TCP and UDP port 53'
  }
  for _ in $(seq 1 24); do
    for host in example.com cloudflare.com github.com; do
      timeout 8 dig @127.0.0.1 "$host" +time=4 +tries=1 +short 2>/dev/null | grep -q . && dns_udp_ok=1 || true
      timeout 8 dig @127.0.0.1 "$host" +tcp +time=4 +tries=1 +short 2>/dev/null | grep -q . && dns_tcp_ok=1 || true
      (( dns_udp_ok == 1 && dns_tcp_ok == 1 )) && break 2
    done
    sleep 2
  done
  if (( dns_udp_ok == 0 || dns_tcp_ok == 0 )); then
    "$ZDT_ROOT/bin/dnscrypt-proxy" -check -config "$ZDT_ETC/dnscrypt/dnscrypt-proxy.toml" >&2 || true
    systemctl status zdt-dnscrypt.service --no-pager >&2 || true
    journalctl -u zdt-dnscrypt.service -n 180 --no-pager >&2 || true
    die "DNSCrypt is listening, but DNS verification failed (UDP=$dns_udp_ok TCP=$dns_tcp_ok)"
  fi
  rm -f /etc/resolv.conf
  cat > /etc/resolv.conf <<'RESOLV'
nameserver 127.0.0.1
options edns0 timeout:3 attempts:2
RESOLV
  rm -rf "$tmp"
}

enable_ip_forward() {
  if [[ ! -f $ZDT_STATE/original-ip-forward ]]; then cat /proc/sys/net/ipv4/ip_forward > "$ZDT_STATE/original-ip-forward"; fi
  printf 'net.ipv4.ip_forward=1\n' > "$SYSCTL_FILE"
  sysctl -w net.ipv4.ip_forward=1 >/dev/null
}
restore_ip_forward_if_unused() {
  local has=0 p
  for p in "$ZDT_STATE/profiles/openvpn"/* "$ZDT_STATE/profiles/wireproxy"/*; do [[ -d $p ]] && { has=1; break; }; done
  (( has == 1 )) && return 0
  rm -f "$SYSCTL_FILE"
  if [[ -f $ZDT_STATE/original-ip-forward ]]; then
    sysctl -w "net.ipv4.ip_forward=$(cat "$ZDT_STATE/original-ip-forward")" >/dev/null || true
    rm -f "$ZDT_STATE/original-ip-forward"
  fi
}

openvpn_cipher_config() {
  if openvpn --help 2>&1 | grep -q -- '--data-ciphers'; then
    printf '%s\n' 'data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305' 'data-ciphers-fallback AES-256-GCM'
  else
    printf '%s\n' 'ncp-ciphers AES-256-GCM:AES-128-GCM' 'cipher AES-256-GCM'
  fi
}
generate_openvpn_tls_key() {
  local path=$1
  rm -f "$path"
  if openvpn --genkey tls-crypt "$path" >/dev/null 2>&1; then return 0; fi
  rm -f "$path"
  openvpn --genkey --secret "$path"
}


install_openvpn_traffic_helpers() {
  ensure_dirs
  cat > "$ZDT_ROOT/bin/openvpn-traffic-reset" <<'SCRIPT'
#!/usr/bin/env bash
set -eu
profile=${1:?profile required}
[[ $profile =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$ ]] || exit 1
state="/var/lib/zdt-vps/profiles/openvpn/$profile"
runtime="/run/zdt-vps/openvpn/$profile"
rm -rf "$runtime"
mkdir -p "$runtime"
# The status file belongs to the daemon lifetime as well.  Clear it before
# OpenVPN starts so a restart cannot briefly expose counters from the previous
# process while the new daemon has not written its first status snapshot yet.
: > "$state/status.log"
: > "$runtime/cn.map"
for c in "$state"/clients/*; do
  [[ -d $c && -f $c/cn ]] || continue
  printf '%s|%s\n' "$(cat "$c/cn")" "$(basename "$c")" >> "$runtime/cn.map"
done
chown -R nobody:nogroup "$runtime" 2>/dev/null || true
chmod 0770 "$runtime"
chmod 0660 "$runtime/cn.map" 2>/dev/null || true
SCRIPT
  cat > "$ZDT_ROOT/bin/openvpn-traffic-disconnect" <<'SCRIPT'
#!/usr/bin/env bash
set -eu
profile=${1:?profile required}
[[ $profile =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$ ]] || exit 0
runtime="/run/zdt-vps/openvpn/$profile"
map="$runtime/cn.map"
cn=${common_name:-}
received=${bytes_received:-0}
sent=${bytes_sent:-0}
[[ -d $runtime && -f $map && -n $cn ]] || exit 0
[[ $received =~ ^[0-9]+$ ]] || received=0
[[ $sent =~ ^[0-9]+$ ]] || sent=0
client=$(awk -F '|' -v cn="$cn" '$1==cn {print $2; exit}' "$map" 2>/dev/null || true)
[[ -n $client ]] || exit 0
lock="$runtime/.lock"
locked=0
for _ in $(seq 1 50); do
  if mkdir "$lock" 2>/dev/null; then locked=1; break; fi
  sleep 0.02
done
(( locked == 1 )) || exit 0
trap 'rmdir "$lock" 2>/dev/null || true' EXIT
file="$runtime/$client"
old_received=0; old_sent=0
if [[ -f $file ]]; then read -r old_received old_sent < "$file" || true; fi
[[ ${old_received:-} =~ ^[0-9]+$ ]] || old_received=0
[[ ${old_sent:-} =~ ^[0-9]+$ ]] || old_sent=0
printf '%s %s\n' "$((old_received + received))" "$((old_sent + sent))" > "$file"
chown nobody:nogroup "$file" 2>/dev/null || true
chmod 0660 "$file" 2>/dev/null || true
SCRIPT
  chmod 0755 "$ZDT_ROOT/bin/openvpn-traffic-reset" "$ZDT_ROOT/bin/openvpn-traffic-disconnect"
}

write_openvpn_traffic_dropin() {
  local profile=$1 dropin="$SYSTEMD_DIR/openvpn-server@zdt-$profile.service.d"
  mkdir -p "$dropin"
  cat > "$dropin/zdt-traffic.conf" <<UNIT
[Service]
ExecStartPre=$ZDT_ROOT/bin/openvpn-traffic-reset $profile
UNIT
}

ensure_openvpn_traffic_config() {
  local profile=$1 dir conf
  install_openvpn_traffic_helpers
  dir=$(profile_dir openvpn "$profile")
  conf="$OPENVPN_DIR/zdt-$profile.conf"
  [[ -f $conf ]] || die 'OpenVPN profile configuration is missing'
  sed -i -E '/^status[[:space:]]+/d;/^status-version[[:space:]]+/d;/^script-security[[:space:]]+/d;/^client-disconnect[[:space:]]+/d' "$conf"
  cat >> "$conf" <<CONF
status $dir/status.log 5
status-version 3
script-security 2
client-disconnect "$ZDT_ROOT/bin/openvpn-traffic-disconnect $profile"
CONF
  write_openvpn_traffic_dropin "$profile"
}

install_openvpn() {
  external_service_warning openvpn
  stage 'Installing OpenVPN and Easy-RSA'
  apt_install openvpn easy-rsa openssl iptables iproute2 python3
  ensure_dirs; mkdir -p "$ZDT_STATE/profiles/openvpn" "$OPENVPN_DIR"
  install_openvpn_traffic_helpers
  openvpn --version | head -1 > "$ZDT_STATE/services/openvpn.version" || echo managed > "$ZDT_STATE/services/openvpn.version"
  refresh_firewall
}
create_openvpn_profile() {
  local id=$1 name=$2 port=$3 proto=$4 host=$5
  service_installed openvpn || die 'OpenVPN is not installed'
  valid_id "$id" || die 'Invalid profile identifier'
  [[ $proto == udp || $proto == tcp ]] || die 'OpenVPN protocol must be tcp or udp'
  [[ ! -e $(profile_dir openvpn "$id") ]] || die 'A profile with this identifier already exists'
  require_free_port "$proto" "$port"
  local index; index=$(next_index openvpn)
  local subnet="10.90.$index.0" gateway="10.90.$index.1" tun="zdtun$index"
  local dir; dir=$(profile_dir openvpn "$id"); mkdir -p "$dir/clients"
  local pki=$dir/easy-rsa server_cn="zdt-${id}-server-$(date +%s)-$RANDOM"
  stage 'Creating an isolated OpenVPN PKI'
  make-cadir "$pki"
  (cd "$pki"; EASYRSA_BATCH=1 EASYRSA_REQ_CN="zdt-$id-ca" ./easyrsa init-pki; EASYRSA_BATCH=1 EASYRSA_REQ_CN="zdt-$id-ca" ./easyrsa build-ca nopass; EASYRSA_BATCH=1 ./easyrsa gen-req "$server_cn" nopass; EASYRSA_BATCH=1 ./easyrsa sign-req server "$server_cn"; EASYRSA_BATCH=1 ./easyrsa gen-crl)
  generate_openvpn_tls_key "$dir/ta.key"
  cp "$pki/pki/ca.crt" "$dir/ca.crt"
  cp "$pki/pki/issued/$server_cn.crt" "$dir/server.crt"
  cp "$pki/pki/private/$server_cn.key" "$dir/server.key"
  cp "$pki/pki/crl.pem" "$dir/crl.pem"
  chmod 600 "$dir/server.key" "$dir/ta.key"; chmod 644 "$dir/crl.pem"
  cat > "$dir/meta.env" <<META
ID=$(q "$id")
NAME=$(q "$name")
PORT=$port
PROTOCOL=$proto
HOST=$(q "$host")
MODE=standard
SUBNET=$subnet
GATEWAY=$gateway
TUN=$tun
INDEX=$index
SERVER_CN=$(q "$server_cn")
META
  local server_proto=$proto
  [[ $proto == tcp ]] && server_proto=tcp-server
  cat > "$OPENVPN_DIR/zdt-$id.conf" <<CONF
port $port
proto $server_proto
dev-type tun
dev $tun
topology subnet
server $subnet 255.255.255.0
ca $dir/ca.crt
cert $dir/server.crt
key $dir/server.key
dh none
ecdh-curve prime256v1
tls-crypt $dir/ta.key
crl-verify $dir/crl.pem
auth SHA256
$(openvpn_cipher_config)
keepalive 10 60
persist-key
persist-tun
user nobody
group nogroup
push "redirect-gateway def1 bypass-dhcp"
push "dhcp-option DNS 1.1.1.1"
push "dhcp-option DNS 1.0.0.1"
status $dir/status.log 5
status-version 3
script-security 2
client-disconnect "$ZDT_ROOT/bin/openvpn-traffic-disconnect $id"
verb 3
CONF
  write_openvpn_traffic_dropin "$id"
  cat > "$SYSTEMD_DIR/zdt-openvpn-net-$id.service" <<UNIT
[Unit]
Description=ZDT-D OpenVPN network rules $id
After=openvpn-server@zdt-$id.service
Requires=openvpn-server@zdt-$id.service
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'iptables -w 5 -t nat -C POSTROUTING -s $subnet/24 -j MASQUERADE 2>/dev/null || iptables -w 5 -t nat -A POSTROUTING -s $subnet/24 -j MASQUERADE; iptables -w 5 -C FORWARD -i $tun -j ACCEPT 2>/dev/null || iptables -w 5 -A FORWARD -i $tun -j ACCEPT; iptables -w 5 -C FORWARD -o $tun -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -w 5 -A FORWARD -o $tun -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT'
ExecStop=/bin/sh -c 'iptables -w 5 -t nat -D POSTROUTING -s $subnet/24 -j MASQUERADE 2>/dev/null || true; iptables -w 5 -D FORWARD -i $tun -j ACCEPT 2>/dev/null || true; iptables -w 5 -D FORWARD -o $tun -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true'
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
UNIT
  enable_ip_forward
  refresh_firewall
  stage 'Starting and verifying OpenVPN profile'
  systemctl daemon-reload
  systemctl enable --now "openvpn-server@zdt-$id.service" "zdt-openvpn-net-$id.service"
  unit_is_active "openvpn-server@zdt-$id.service" || { journalctl -u "openvpn-server@zdt-$id.service" -n 120 --no-pager >&2 || true; die 'OpenVPN profile failed to start'; }
  ip link show "$tun" >/dev/null 2>&1 || { journalctl -u "openvpn-server@zdt-$id.service" -n 120 --no-pager >&2 || true; die "OpenVPN started without creating interface $tun"; }
  if [[ $proto == tcp ]]; then ss -H -lnt "sport = :$port" | grep -q . || die "OpenVPN is not listening on TCP port $port"; else ss -H -lnu "sport = :$port" | grep -q . || die "OpenVPN is not listening on UDP port $port"; fi
}
create_openvpn_client() {
  local profile=$1 client=$2 display=${3:-$2}
  valid_id "$client" || die 'Invalid client identifier'
  load_meta openvpn "$profile"
  local dir; dir=$(profile_dir openvpn "$profile"); [[ ! -d $dir/clients/$client ]] || die 'Client name already exists in this profile'
  local pki=$dir/easy-rsa cn="zdt-${profile}-${client}-$(date +%s)-$RANDOM" cdir=$dir/clients/$client
  stage 'Creating OpenVPN client certificate'
  (cd "$pki"; EASYRSA_BATCH=1 ./easyrsa gen-req "$cn" nopass; EASYRSA_BATCH=1 ./easyrsa sign-req client "$cn")
  mkdir -p "$cdir"
  printf '%s\n' "$cn" > "$cdir/cn"; printf '%s\n' "$display" > "$cdir/name"; date +%s > "$cdir/created_at"
  local client_proto=$PROTOCOL; [[ $PROTOCOL == tcp ]] && client_proto=tcp-client
  cat > "$cdir/client.ovpn" <<CONF
client
dev tun
proto $client_proto
remote $HOST $PORT
resolv-retry infinite
nobind
persist-key
persist-tun
remote-cert-tls server
auth SHA256
$(openvpn_cipher_config)
verb 3
<ca>
$(cat "$dir/ca.crt")
</ca>
<cert>
$(awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' "$pki/pki/issued/$cn.crt")
</cert>
<key>
$(cat "$pki/pki/private/$cn.key")
</key>
<tls-crypt>
$(cat "$dir/ta.key")
</tls-crypt>
CONF
  chmod 600 "$cdir/client.ovpn"
  # Creating a certificate does not restart OpenVPN, so keep the runtime CN map
  # in sync without resetting the daemon-lifetime traffic counters.
  local runtime="/run/zdt-vps/openvpn/$profile"
  if [[ -d $runtime && -f $runtime/cn.map ]]; then
    printf '%s|%s\n' "$cn" "$client" >> "$runtime/cn.map"
    chown nobody:nogroup "$runtime/cn.map" 2>/dev/null || true
    chmod 0660 "$runtime/cn.map" 2>/dev/null || true
  fi
}
delete_openvpn_client() {
  local profile=$1 client=$2
  load_meta openvpn "$profile"
  local dir; dir=$(profile_dir openvpn "$profile"); local cdir=$dir/clients/$client
  [[ -d $cdir ]] || die 'Client not found'
  local pki=$dir/easy-rsa cn; cn=$(cat "$cdir/cn")
  stage 'Revoking OpenVPN certificate'
  (cd "$pki"; EASYRSA_BATCH=1 ./easyrsa revoke "$cn"; EASYRSA_BATCH=1 ./easyrsa gen-crl)
  cp "$pki/pki/crl.pem" "$dir/crl.pem"; chmod 644 "$dir/crl.pem"
  rm -rf "$cdir"
  systemctl restart "openvpn-server@zdt-$profile.service"
}

install_wireproxy() {
  external_service_warning wireproxy
  stage 'Installing WireGuard tools for WireProxy clients'
  apt_install wireguard-tools iptables iproute2 qrencode
  ensure_dirs; mkdir -p "$ZDT_STATE/profiles/wireproxy" "$WIREGUARD_DIR"
  wg --version | head -1 > "$ZDT_STATE/services/wireproxy.version" || echo managed > "$ZDT_STATE/services/wireproxy.version"
  refresh_firewall
}
rebuild_wireproxy() {
  local profile=$1; load_meta wireproxy "$profile"; local dir; dir=$(profile_dir wireproxy "$profile")
  cat > "$WIREGUARD_DIR/$IFACE.conf" <<CONF
[Interface]
Address = $GATEWAY/24
ListenPort = $PORT
PrivateKey = $(cat "$dir/server_private")
PostUp = iptables -w 5 -t nat -C POSTROUTING -s $SUBNET/24 -j MASQUERADE 2>/dev/null || iptables -w 5 -t nat -A POSTROUTING -s $SUBNET/24 -j MASQUERADE; iptables -w 5 -C FORWARD -i %i -j ACCEPT 2>/dev/null || iptables -w 5 -A FORWARD -i %i -j ACCEPT; iptables -w 5 -C FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -w 5 -A FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
PostDown = iptables -w 5 -t nat -D POSTROUTING -s $SUBNET/24 -j MASQUERADE 2>/dev/null || true; iptables -w 5 -D FORWARD -i %i -j ACCEPT 2>/dev/null || true; iptables -w 5 -D FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true
CONF
  local c
  for c in "$dir"/clients/*; do
    [[ -d $c ]] || continue
    cat >> "$WIREGUARD_DIR/$IFACE.conf" <<CONF

[Peer]
# $(basename "$c")
PublicKey = $(cat "$c/public")
PresharedKey = $(cat "$c/psk")
AllowedIPs = $(cat "$c/ip")/32
CONF
  done
  chmod 600 "$WIREGUARD_DIR/$IFACE.conf"
  systemctl daemon-reload
  systemctl enable "wg-quick@$IFACE.service" >/dev/null 2>&1 || true
  systemctl restart "wg-quick@$IFACE.service"
  unit_is_active "wg-quick@$IFACE.service" || { journalctl -u "wg-quick@$IFACE.service" -n 100 --no-pager >&2 || true; die 'WireGuard server profile failed to start'; }
}
create_wireproxy_profile() {
  local id=$1 name=$2 port=$3 host=$4
  service_installed wireproxy || die 'WireProxy server tools are not installed'
  valid_id "$id" || die 'Invalid profile identifier'
  [[ ! -e $(profile_dir wireproxy "$id") ]] || die 'A profile with this identifier already exists'
  require_free_port udp "$port"
  local index; index=$(next_index wireproxy)
  local subnet="10.91.$index.0" gateway="10.91.$index.1" iface="zdtwg$index"
  local dir; dir=$(profile_dir wireproxy "$id"); mkdir -p "$dir/clients"
  umask 077; wg genkey | tee "$dir/server_private" | wg pubkey > "$dir/server_public"
  cat > "$dir/meta.env" <<META
ID=$(q "$id")
NAME=$(q "$name")
PORT=$port
PROTOCOL=udp
HOST=$(q "$host")
MODE=wireguard
SUBNET=$subnet
GATEWAY=$gateway
INDEX=$index
IFACE=$iface
META
  enable_ip_forward
  refresh_firewall
  rebuild_wireproxy "$id"
}
create_wireproxy_client() {
  local profile=$1 client=$2 display=${3:-$2}
  valid_id "$client" || die 'Invalid client identifier'; load_meta wireproxy "$profile"
  local dir; dir=$(profile_dir wireproxy "$profile"); [[ ! -d $dir/clients/$client ]] || die 'Client name already exists in this profile'
  local number; number=$(next_wire_client_octet "$dir")
  local cdir=$dir/clients/$client; mkdir -p "$cdir"
  umask 077; wg genkey | tee "$cdir/private" | wg pubkey > "$cdir/public"; wg genpsk > "$cdir/psk"
  echo "${SUBNET%.*}.$number" > "$cdir/ip"; date +%s > "$cdir/created_at"; printf '%s\n' "$display" > "$cdir/name"
  local socks=$((25300 + number))
  cat > "$cdir/client.conf" <<CONF
[Interface]
Address = $(cat "$cdir/ip")/32
PrivateKey = $(cat "$cdir/private")
DNS = 1.1.1.1, 1.0.0.1

[Peer]
PublicKey = $(cat "$dir/server_public")
PresharedKey = $(cat "$cdir/psk")
Endpoint = $HOST:$PORT
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25

[Socks5]
BindAddress = 127.0.0.1:$socks
CONF
  chmod 600 "$cdir/client.conf"
  rebuild_wireproxy "$profile"
}
delete_wireproxy_client() {
  local profile=$1 client=$2 dir; dir=$(profile_dir wireproxy "$profile")
  [[ -d $dir/clients/$client ]] || die 'Client not found'
  rm -rf "$dir/clients/$client"; rebuild_wireproxy "$profile"
}

write_tls_reload_hook() {
  mkdir -p /etc/letsencrypt/renewal-hooks/deploy
  cat > /etc/letsencrypt/renewal-hooks/deploy/zdt-vps-reload <<'HOOK'
#!/usr/bin/env bash
set -e
for unit in $(systemctl list-unit-files --no-legend | awk '{print $1}' | grep -E '^zdt-(xray|hysteria2)-'); do systemctl try-restart "$unit" || true; done
HOOK
  chmod 700 /etc/letsencrypt/renewal-hooks/deploy/zdt-vps-reload
}
ensure_certificate() {
  local domain=$1 email=$2 state owned=0 had_certificate=0
  [[ $domain =~ ^([A-Za-z0-9-]+\.)+[A-Za-z]{2,63}$ ]] || die 'A valid public domain is required for automatic TLS'
  if [[ -n $email && $email != *@*.* ]]; then die 'The optional Let’s Encrypt email address is invalid'; fi
  mkdir -p "$ZDT_STATE/certificates"
  state="$ZDT_STATE/certificates/$domain.env"
  if [[ -f $state ]]; then unset OWNED; source "$state"; owned=${OWNED:-0}; fi
  if [[ -s /etc/letsencrypt/live/$domain/fullchain.pem && -s /etc/letsencrypt/live/$domain/privkey.pem ]]; then
    had_certificate=1
    if openssl x509 -checkend 604800 -noout -in "/etc/letsencrypt/live/$domain/fullchain.pem" >/dev/null 2>&1; then
      [[ -f $state ]] || printf 'DOMAIN=%q\nOWNED=0\n' "$domain" > "$state"
      write_tls_reload_hook
      return 0
    fi
  fi
  backup_certificate_for_tx "$domain"
  port_free tcp 80 || die 'TCP port 80 must be free while requesting a Let’s Encrypt certificate'
  stage 'Requesting TLS certificate with Let’s Encrypt'
  write_firewall_script; systemctl daemon-reload; systemctl enable --now zdt-vps-firewall.service >/dev/null 2>&1 || true
  iptables -w 5 -I "$FIREWALL_CHAIN" 1 -p tcp --dport 80 -j ACCEPT
  local rc=0
  local contact_args=()
  if [[ -n $email ]]; then contact_args=(--email "$email"); else contact_args=(--register-unsafely-without-email); fi
  certbot certonly --standalone --non-interactive --agree-tos --keep-until-expiring "${contact_args[@]}" -d "$domain" || rc=$?
  iptables -w 5 -D "$FIREWALL_CHAIN" -p tcp --dport 80 -j ACCEPT 2>/dev/null || true
  (( rc == 0 )) || die 'Let’s Encrypt certificate request failed'
  [[ -s /etc/letsencrypt/live/$domain/fullchain.pem && -s /etc/letsencrypt/live/$domain/privkey.pem ]] || die 'Let’s Encrypt did not create the expected certificate files'
  (( had_certificate == 0 )) && owned=1
  printf 'DOMAIN=%q\nOWNED=%s\n' "$domain" "$owned" > "$state"
  write_tls_reload_hook
}

install_xray() {
  external_service_warning xray
  stage 'Installing Xray dependencies'
  apt_install curl unzip ca-certificates python3 openssl certbot
  ensure_dirs
  local tmp
  tmp=$(mktemp -d)
  curl -fL --retry 3 "https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-64.zip" -o "$tmp/xray.zip"
  unzip -q "$tmp/xray.zip" -d "$tmp/xray"
  install -m 0755 "$tmp/xray/xray" "$ZDT_ROOT/bin/xray"
  rm -rf "$tmp"
  "$ZDT_ROOT/bin/xray" version | head -1 > "$ZDT_STATE/services/xray.version" || echo managed > "$ZDT_STATE/services/xray.version"
  mkdir -p "$ZDT_STATE/profiles/xray"
  refresh_firewall
}
rebuild_xray() {
  local profile=$1 dir stats_port snis
  dir=$(profile_dir xray "$profile")
  unset STATS_PORT SNIS
  load_meta xray "$profile"
  if [[ -z ${STATS_PORT:-} ]]; then
    STATS_PORT=$(next_stats_port)
    printf 'STATS_PORT=%s\n' "$STATS_PORT" >> "$dir/meta.env"
  fi
  stats_port=$STATS_PORT
  snis=${SNIS:-${SNI:-}}
  python3 - "$dir" "$MODE" "$PORT" "$DOMAIN" "$snis" "$stats_port" <<'PY'
import glob,json,os,sys
root,mode,port,domain,snis_raw,stats_port=sys.argv[1:]
snis=[]
for value in snis_raw.split(','):
    value=value.strip()
    if value and value not in snis: snis.append(value)
clients=[]
for f in sorted(glob.glob(root+'/clients/*/client.json')):
    with open(f,encoding='utf-8') as h:
        item=json.load(h)
    item['_client_id']=os.path.basename(os.path.dirname(f))
    clients.append(item)
client_items=[]
for c in clients:
    item={'id':c['uuid'],'email':c['_client_id']}
    if mode=='reality': item['flow']='xtls-rprx-vision'
    client_items.append(item)
settings={'clients':client_items,'decryption':'none'}
if mode=='reality':
    if not snis: raise SystemExit('Reality SNI list is empty')
    meta={}
    with open(root+'/reality.env',encoding='utf-8') as h:
        for line in h:
            k,v=line.rstrip().split('=',1); meta[k]=v
    stream={'network':'tcp','security':'reality','realitySettings':{'show':False,'target':snis[0]+':443','xver':0,'serverNames':snis,'privateKey':meta['PRIVATE'],'shortIds':[meta['SHORT']]}}
else:
    stream={'network':'ws','security':'tls','tlsSettings':{'certificates':[{'certificateFile':'/etc/letsencrypt/live/'+domain+'/fullchain.pem','keyFile':'/etc/letsencrypt/live/'+domain+'/privkey.pem'}]},'wsSettings':{'path':'/zdt-'+os.path.basename(root)}}
config={
    'log':{'loglevel':'warning'},
    'stats':{},
    'policy':{'levels':{'0':{'statsUserUplink':True,'statsUserDownlink':True}}},
    'metrics':{'listen':'127.0.0.1:'+stats_port},
    'inbounds':[{'listen':'0.0.0.0','port':int(port),'protocol':'vless','settings':settings,'streamSettings':stream}],
    'outbounds':[{'protocol':'freedom','tag':'direct'},{'protocol':'blackhole','tag':'blocked'}],
}
with open(root+'/server.json','w',encoding='utf-8') as h: json.dump(config,h,indent=2)
PY
  cat > "$SYSTEMD_DIR/zdt-xray-$profile.service" <<UNIT
[Unit]
Description=ZDT-D Xray profile $profile
After=network-online.target zdt-vps-firewall.service
Wants=network-online.target
Requires=zdt-vps-firewall.service
[Service]
ExecStart=$ZDT_ROOT/bin/xray run -config $dir/server.json
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
UNIT
  "$ZDT_ROOT/bin/xray" run -test -config "$dir/server.json"
  systemctl daemon-reload; systemctl enable --now "zdt-xray-$profile.service"; systemctl restart "zdt-xray-$profile.service"
  unit_is_active "zdt-xray-$profile.service" || { journalctl -u "zdt-xray-$profile.service" -n 100 --no-pager >&2 || true; die 'Xray profile failed to start'; }
}
create_xray_profile() {
  local id=$1 name=$2 port=$3 mode=$4 host=$5 domain=$6 email=$7 snis_raw=$8
  service_installed xray || die 'Xray is not installed'
  valid_id "$id" || die 'Invalid profile identifier'
  [[ ! -e $(profile_dir xray "$id") ]] || die 'A profile with this identifier already exists'
  [[ $mode == reality || $mode == ws ]] || die 'Unsupported Xray mode'
  require_free_port tcp "$port"
  local dir endpoint=$host sni='' snis='' stats_port
  dir=$(profile_dir xray "$id"); mkdir -p "$dir/clients"
  stats_port=$(next_stats_port)
  if [[ $mode == reality ]]; then
    local candidate
    IFS=',' read -r -a _zdt_sni_values <<< "$snis_raw"
    for candidate in "${_zdt_sni_values[@]}"; do
      candidate=$(printf '%s' "$candidate" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')
      [[ -n $candidate ]] || continue
      [[ $candidate =~ ^([A-Za-z0-9-]+\.)*[A-Za-z0-9-]+$ ]] || die "Invalid Reality SNI: $candidate"
      if [[ ",$snis," != *",$candidate,"* ]]; then
        [[ -n $snis ]] && snis+=','
        snis+=$candidate
      fi
    done
    sni=${snis%%,*}
    [[ -n $sni ]] || die 'SNI is required for VLESS Reality'
    local keys private public short
    keys=$($ZDT_ROOT/bin/xray x25519)
    private=$(printf '%s\n' "$keys" | awk -F: 'tolower($1) ~ /private/ {gsub(/[ \t]/,"",$2);print $2;exit}')
    public=$(printf '%s\n' "$keys" | awk -F: 'tolower($1) ~ /public|password/ {gsub(/[ \t]/,"",$2);print $2;exit}')
    short=$(openssl rand -hex 8)
    [[ -n $private && -n $public ]] || die 'Unable to generate Reality keys'
    printf 'PRIVATE=%s\nPUBLIC=%s\nSHORT=%s\n' "$private" "$public" "$short" > "$dir/reality.env"
  else
    ensure_certificate "$domain" "$email"
    endpoint=$domain
  fi
  cat > "$dir/meta.env" <<META
ID=$(q "$id")
NAME=$(q "$name")
PORT=$port
PROTOCOL=tcp
HOST=$(q "$endpoint")
MODE=$mode
DOMAIN=$(q "$domain")
SNI=$(q "$sni")
SNIS=$(q "$snis")
STATS_PORT=$stats_port
META
  refresh_firewall
  rebuild_xray "$id"
}
create_xray_client() {
  local profile=$1 client=$2 display=${3:-$2}
  valid_id "$client" || die 'Invalid client identifier'; load_meta xray "$profile"
  local dir; dir=$(profile_dir xray "$profile"); [[ ! -d $dir/clients/$client ]] || die 'Client name already exists in this profile'
  local uuid; uuid=$($ZDT_ROOT/bin/xray uuid)
  mkdir -p "$dir/clients/$client"
  python3 - "$dir/clients/$client/client.json" "$display" "$uuid" "$(date +%s)" <<'PY'
import json,sys
path,name,uuid,created=sys.argv[1:]
with open(path,'w',encoding='utf-8') as h: json.dump({'name':name,'uuid':uuid,'created_at':int(created)},h)
PY
  rebuild_xray "$profile"
}
delete_xray_client() {
  local profile=$1 client=$2 dir; dir=$(profile_dir xray "$profile")
  [[ -d $dir/clients/$client ]] || die 'Client not found'
  rm -rf "$dir/clients/$client"; rebuild_xray "$profile"
}

install_hysteria2() {
  external_service_warning hysteria2
  stage 'Installing Hysteria2 dependencies'
  apt_install curl ca-certificates python3 openssl
  ensure_dirs
  curl -fL --retry 3 https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-amd64 -o "$ZDT_ROOT/bin/hysteria"
  chmod 0755 "$ZDT_ROOT/bin/hysteria"
  "$ZDT_ROOT/bin/hysteria" version | head -1 > "$ZDT_STATE/services/hysteria2.version" || echo managed > "$ZDT_STATE/services/hysteria2.version"
  mkdir -p "$ZDT_STATE/profiles/hysteria2"
  refresh_firewall
}
ensure_hysteria2_local_certificate() {
  local dir=$1 tls_name=$2 san
  [[ -n $tls_name ]] || tls_name='zdt-hysteria.local'
  if [[ $tls_name =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    san="IP:$tls_name"
  elif [[ $tls_name =~ ^([A-Za-z0-9-]+\.)*[A-Za-z0-9-]+$ ]]; then
    san="DNS:$tls_name"
  else
    die 'Invalid Hysteria2 SNI name'
  fi
  stage 'Generating local TLS certificate for Hysteria2'
  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 3650 \
    -keyout "$dir/tls.key" -out "$dir/tls.crt" \
    -subj "/CN=$tls_name" \
    -addext "subjectAltName=$san" \
    -addext 'keyUsage=digitalSignature,keyEncipherment' \
    -addext 'extendedKeyUsage=serverAuth' >/dev/null 2>&1
  chmod 600 "$dir/tls.key"
  chmod 644 "$dir/tls.crt"
  openssl x509 -in "$dir/tls.crt" -noout -checkend 86400 >/dev/null 2>&1 || die 'Generated Hysteria2 certificate is invalid'
}

rebuild_hysteria2() {
  local profile=$1 dir stats_port stats_secret
  dir=$(profile_dir hysteria2 "$profile")
  unset STATS_PORT STATS_SECRET
  load_meta hysteria2 "$profile"
  if [[ -z ${STATS_PORT:-} ]]; then
    STATS_PORT=$(next_stats_port)
    printf 'STATS_PORT=%s\n' "$STATS_PORT" >> "$dir/meta.env"
  fi
  if [[ -z ${STATS_SECRET:-} ]]; then
    STATS_SECRET=$(openssl rand -hex 16)
    printf 'STATS_SECRET=%s\n' "$STATS_SECRET" >> "$dir/meta.env"
  fi
  stats_port=$STATS_PORT; stats_secret=$STATS_SECRET
  [[ -s $dir/tls.crt && -s $dir/tls.key ]] || ensure_hysteria2_local_certificate "$dir" "${SNI:-zdt-hysteria.local}"
  cat > "$dir/server.yaml" <<YAML
listen: :$PORT
tls:
  cert: $dir/tls.crt
  key: $dir/tls.key
trafficStats:
  listen: 127.0.0.1:$stats_port
  secret: $stats_secret
auth:
  type: userpass
YAML
  local c user pass client_count=0
  for c in "$dir"/clients/*; do [[ -d $c ]] && ((client_count+=1)); done
  if (( client_count == 0 )); then
    printf '  userpass: {}\n' >> "$dir/server.yaml"
  else
    printf '  userpass:\n' >> "$dir/server.yaml"
    for c in "$dir"/clients/*; do
      [[ -d $c ]] || continue
      user=$(basename "$c"); pass=$(cat "$c/password")
      printf '    %s: %s\n' "$user" "$pass" >> "$dir/server.yaml"
    done
  fi
  cat >> "$dir/server.yaml" <<YAML
masquerade:
  type: proxy
  proxy:
    url: https://www.google.com/
    rewriteHost: true
YAML
  cat > "$SYSTEMD_DIR/zdt-hysteria2-$profile.service" <<UNIT
[Unit]
Description=ZDT-D Hysteria2 profile $profile
After=network-online.target zdt-vps-firewall.service
Wants=network-online.target
Requires=zdt-vps-firewall.service
[Service]
ExecStart=$ZDT_ROOT/bin/hysteria server -c $dir/server.yaml
Restart=on-failure
RestartSec=3
AmbientCapabilities=CAP_NET_BIND_SERVICE
[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload; systemctl enable --now "zdt-hysteria2-$profile.service"; systemctl restart "zdt-hysteria2-$profile.service"
  unit_is_active "zdt-hysteria2-$profile.service" || { journalctl -u "zdt-hysteria2-$profile.service" -n 100 --no-pager >&2 || true; die 'Hysteria2 profile failed to start'; }
}
create_hysteria2_profile() {
  local id=$1 name=$2 port=$3 host=$4 domain=${5:-} email=${6:-} requested_sni=${7:-}
  local tls_name=${requested_sni:-${domain:-zdt-hysteria.local}}
  service_installed hysteria2 || die 'Hysteria2 is not installed'
  valid_id "$id" || die 'Invalid profile identifier'
  [[ ! -e $(profile_dir hysteria2 "$id") ]] || die 'A profile with this identifier already exists'
  require_free_port udp "$port"
  local dir stats_port stats_secret
  dir=$(profile_dir hysteria2 "$id"); mkdir -p "$dir/clients"
  stats_port=$(next_stats_port); stats_secret=$(openssl rand -hex 16)
  ensure_hysteria2_local_certificate "$dir" "$tls_name"
  cat > "$dir/meta.env" <<META
ID=$(q "$id")
NAME=$(q "$name")
PORT=$port
PROTOCOL=udp
HOST=$(q "$host")
MODE=local-tls
DOMAIN=$(q "$tls_name")
SNI=$(q "$tls_name")
STATS_PORT=$stats_port
STATS_SECRET=$stats_secret
META
  refresh_firewall
  rebuild_hysteria2 "$id"
}
create_hysteria2_client() {
  local profile=$1 client=$2 display=${3:-$2}
  valid_id "$client" || die 'Invalid client identifier'
  local dir; dir=$(profile_dir hysteria2 "$profile"); [[ ! -d $dir/clients/$client ]] || die 'Client name already exists in this profile'
  mkdir -p "$dir/clients/$client"
  openssl rand -hex 16 > "$dir/clients/$client/password"
  date +%s > "$dir/clients/$client/created_at"
  printf '%s\n' "$display" > "$dir/clients/$client/name"
  rebuild_hysteria2 "$profile"
}
delete_hysteria2_client() {
  local profile=$1 client=$2 dir; dir=$(profile_dir hysteria2 "$profile")
  [[ -d $dir/clients/$client ]] || die 'Client not found'
  rm -rf "$dir/clients/$client"; rebuild_hysteria2 "$profile"
}

has_active_profile_unit() {
  local pattern=$1
  systemctl list-units --type=service --state=active --no-legend "$pattern" 2>/dev/null | grep -q .
}
service_active() {
  local kind=$1
  case "$kind" in
    dnscrypt) unit_is_active zdt-dnscrypt.service ;;
    openvpn) has_active_profile_unit 'openvpn-server@zdt-*.service' ;;
    xray) has_active_profile_unit 'zdt-xray-*.service' ;;
    hysteria2) has_active_profile_unit 'zdt-hysteria2-*.service' ;;
    wireproxy) has_active_profile_unit 'wg-quick@zdtwg*.service' ;;
  esac
}
inventory() {
  local k version active installed
  for k in dnscrypt openvpn xray hysteria2 wireproxy; do
    installed=0; active=0; version=''
    service_installed "$k" && installed=1
    service_active "$k" && active=1 || true
    [[ -f $ZDT_STATE/services/$k.version ]] && version=$(head -1 "$ZDT_STATE/services/$k.version")
    printf 'ZDT_SERVICE|%s|%s|%s|%s\n' "$k" "$installed" "$active" "$(b64 "$version")"
  done
}
openvpn_traffic_lines() {
  local profile=$1 dir runtime
  dir=$(profile_dir openvpn "$profile")
  runtime="/run/zdt-vps/openvpn/$profile"
  python3 - "$dir" "$runtime" <<'PY'
import csv,glob,os,sys
root,runtime=sys.argv[1:]
cn_to_id={}
for c in glob.glob(root+'/clients/*'):
    if not os.path.isdir(c): continue
    try:
        with open(c+'/cn',encoding='utf-8') as h: cn=h.read().strip()
    except OSError: continue
    if cn: cn_to_id[cn]=os.path.basename(c)
totals={cid:[0,0] for cid in cn_to_id.values()}
if os.path.isdir(runtime):
    for cid in list(totals):
        try:
            with open(os.path.join(runtime,cid),encoding='utf-8') as h:
                a,b=h.read().split()[:2]
            totals[cid][0]+=int(a); totals[cid][1]+=int(b)
        except (OSError,ValueError,IndexError): pass
status=os.path.join(root,'status.log')
try:
    with open(status,encoding='utf-8',errors='replace') as h: lines=h.read().splitlines()
except OSError:
    lines=[]
header=None; delim='\t' if any(line.startswith('HEADER\tCLIENT_LIST') for line in lines) else ','
for line in lines:
    try: row=next(csv.reader([line],delimiter=delim))
    except Exception: continue
    if len(row)>=3 and row[0]=='HEADER' and row[1]=='CLIENT_LIST':
        header=row[2:]
        continue
    if not header or len(row)<2 or row[0]!='CLIENT_LIST': continue
    values=row[1:]
    record={header[i]:values[i] for i in range(min(len(header),len(values)))}
    cid=cn_to_id.get(record.get('Common Name',''))
    if not cid: continue
    try: totals[cid][0]+=int(record.get('Bytes Received','0') or 0)
    except ValueError: pass
    try: totals[cid][1]+=int(record.get('Bytes Sent','0') or 0)
    except ValueError: pass
for cid in sorted(totals): print(f'{cid}|{totals[cid][0]}|{totals[cid][1]}')
PY
}

wireproxy_traffic_lines() {
  local profile=$1 dir dump c pub line rx tx
  unset IFACE
  load_meta wireproxy "$profile"
  dir=$(profile_dir wireproxy "$profile")
  dump=$(wg show "$IFACE" dump 2>/dev/null || true)
  for c in "$dir"/clients/*; do
    [[ -d $c && -f $c/public ]] || continue
    pub=$(cat "$c/public")
    line=$(printf '%s\n' "$dump" | awk -F '\t' -v key="$pub" '$1==key {print $6 "|" $7; exit}')
    rx=${line%%|*}; tx=${line#*|}
    [[ $rx =~ ^[0-9]+$ ]] || rx=0
    [[ $tx =~ ^[0-9]+$ ]] || tx=0
    printf '%s|%s|%s\n' "$(basename "$c")" "$rx" "$tx"
  done
}

xray_traffic_lines() {
  local profile=$1 dir payload
  dir=$(profile_dir xray "$profile")
  unset STATS_PORT
  load_meta xray "$profile"
  [[ -n ${STATS_PORT:-} ]] || return 0
  payload=$(curl -fsS --max-time 2 "http://127.0.0.1:$STATS_PORT/debug/vars" 2>/dev/null || true)
  XRAY_STATS_PAYLOAD=$payload python3 - "$dir" <<'PY'
import glob,json,os,sys
root=sys.argv[1]
try: data=json.loads(os.environ.get('XRAY_STATS_PAYLOAD',''))
except Exception: data={}
users=data.get('stats',{}).get('user',{}) if isinstance(data,dict) else {}
for c in sorted(glob.glob(root+'/clients/*')):
    if not os.path.isdir(c): continue
    cid=os.path.basename(c); stat=users.get(cid,{}) if isinstance(users,dict) else {}
    try: up=int(stat.get('uplink',0) or 0)
    except Exception: up=0
    try: down=int(stat.get('downlink',0) or 0)
    except Exception: down=0
    print(f'{cid}|{up}|{down}')
PY
}

hysteria2_traffic_lines() {
  local profile=$1 dir payload
  dir=$(profile_dir hysteria2 "$profile")
  unset STATS_PORT STATS_SECRET
  load_meta hysteria2 "$profile"
  [[ -n ${STATS_PORT:-} && -n ${STATS_SECRET:-} ]] || return 0
  payload=$(curl -fsS --max-time 2 -H "Authorization: $STATS_SECRET" "http://127.0.0.1:$STATS_PORT/traffic" 2>/dev/null || true)
  HYSTERIA_STATS_PAYLOAD=$payload python3 - "$dir" <<'PY'
import glob,json,os,sys
root=sys.argv[1]
try: data=json.loads(os.environ.get('HYSTERIA_STATS_PAYLOAD',''))
except Exception: data={}
for c in sorted(glob.glob(root+'/clients/*')):
    if not os.path.isdir(c): continue
    cid=os.path.basename(c); stat=data.get(cid,{}) if isinstance(data,dict) else {}
    # Hysteria reports tx/rx from the server point of view.  Present traffic
    # consistently from the client point of view: client upload = server rx,
    # client download = server tx.
    try: up=int(stat.get('rx',0) or 0)
    except Exception: up=0
    try: down=int(stat.get('tx',0) or 0)
    except Exception: down=0
    print(f'{cid}|{up}|{down}')
PY
}

client_traffic_lines() {
  local kind=$1 profile=$2
  case "$kind" in
    openvpn) openvpn_traffic_lines "$profile" || true ;;
    wireproxy) wireproxy_traffic_lines "$profile" || true ;;
    xray) xray_traffic_lines "$profile" || true ;;
    hysteria2) hysteria2_traffic_lines "$profile" || true ;;
  esac
}

list_profiles() {
  local kind=$1 dir p count active unit upload download sni_b64 _cid _up _down snis_value
  dir="$ZDT_STATE/profiles/$kind"; [[ -d $dir ]] || return 0
  for p in "$dir"/*; do
    [[ -d $p && -f $p/meta.env ]] || continue
    unset SNIS STATS_PORT STATS_SECRET IFACE
    source "$p/meta.env"
    count=$(find "$p/clients" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l); active=0
    case "$kind" in
      openvpn) unit="openvpn-server@zdt-$ID.service" ;;
      xray) unit="zdt-xray-$ID.service" ;;
      hysteria2) unit="zdt-hysteria2-$ID.service" ;;
      wireproxy) unit="wg-quick@$IFACE.service" ;;
      *) continue ;;
    esac
    unit_is_active "$unit" && active=1 || true
    upload=0; download=0
    while IFS='|' read -r _cid _up _down; do
      [[ ${_up:-} =~ ^[0-9]+$ ]] || _up=0
      [[ ${_down:-} =~ ^[0-9]+$ ]] || _down=0
      upload=$((upload + _up)); download=$((download + _down))
    done < <(client_traffic_lines "$kind" "$ID")
    sni_b64=''
    if [[ $kind == xray && ${MODE:-} == reality ]]; then
      snis_value=${SNIS:-${SNI:-}}
      sni_b64=$(b64 "$snis_value")
    fi
    printf 'ZDT_PROFILE|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
      "$kind" "$ID" "$(b64 "$NAME")" "$MODE" "$PROTOCOL" "$PORT" "$(b64 "${HOST:-}")" "$(b64 "${DOMAIN:-}")" "$count" "$active" "$upload" "$download" "$sni_b64"
  done
}
client_display_name() {
  local dir=$1
  if [[ -f $dir/name ]]; then cat "$dir/name"; elif [[ -f $dir/client.json ]]; then python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("name", ""))' "$dir/client.json"; else basename "$dir"; fi
}
list_clients() {
  local kind=$1 profile=$2 dir c created name cid up down
  dir=$(profile_dir "$kind" "$profile"); [[ -d $dir/clients ]] || return 0
  declare -A traffic_up=() traffic_down=()
  while IFS='|' read -r cid up down; do
    [[ -n ${cid:-} ]] || continue
    traffic_up["$cid"]=${up:-0}; traffic_down["$cid"]=${down:-0}
  done < <(client_traffic_lines "$kind" "$profile")
  for c in "$dir"/clients/*; do
    [[ -d $c ]] || continue; created=0; cid=$(basename "$c")
    [[ -f $c/created_at ]] && created=$(cat "$c/created_at")
    [[ -f $c/client.json ]] && created=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("created_at",0))' "$c/client.json")
    name=$(client_display_name "$c")
    up=${traffic_up[$cid]:-0}; down=${traffic_down[$cid]:-0}
    printf 'ZDT_CLIENT|%s|%s|%s|%s|%s|%s|%s\n' "$kind" "$profile" "$cid" "$(b64 "$name")" "$created" "$up" "$down"
  done
}
get_config() {
  local kind=$1 profile=$2 client=$3 dir cdir file link='' mime='text/plain' name sni_options='' 
  dir=$(profile_dir "$kind" "$profile"); cdir=$dir/clients/$client; [[ -d $cdir ]] || die 'Client not found'
  name=$(client_display_name "$cdir")
  case "$kind" in
    openvpn) file="$cdir/client.ovpn"; mime='application/x-openvpn-profile' ;;
    wireproxy) file="$cdir/client.conf"; mime='text/plain' ;;
    xray)
      unset SNIS
      load_meta xray "$profile"
      local uuid; uuid=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["uuid"])' "$cdir/client.json")
      if [[ $MODE == reality ]]; then
        sni_options=${SNIS:-${SNI:-}}
        source "$dir/reality.env"
        link=$(python3 - "$uuid" "$HOST" "$PORT" "$SNI" "$PUBLIC" "$SHORT" "$name" <<'PY'
import sys,urllib.parse
uuid,host,port,sni,pbk,sid,name=sys.argv[1:]
q=urllib.parse.urlencode({'encryption':'none','security':'reality','sni':sni,'fp':'chrome','pbk':pbk,'sid':sid,'type':'tcp','flow':'xtls-rprx-vision'})
print('vless://'+uuid+'@'+host+':'+port+'?'+q+'#'+urllib.parse.quote(name,safe=''))
PY
)
      else
        link=$(python3 - "$uuid" "$HOST" "$PORT" "$DOMAIN" "$profile" "$name" <<'PY'
import sys,urllib.parse
uuid,host,port,domain,profile,name=sys.argv[1:]
q=urllib.parse.urlencode({'encryption':'none','security':'tls','sni':domain,'type':'ws','host':domain,'path':'/zdt-'+profile})
print('vless://'+uuid+'@'+host+':'+port+'?'+q+'#'+urllib.parse.quote(name,safe=''))
PY
)
      fi
      file="$cdir/client.txt"; printf '%s\n' "$link" > "$file" ;;
    hysteria2)
      load_meta hysteria2 "$profile"
      local pass pin; pass=$(cat "$cdir/password")
      pin=$(openssl x509 -in "$dir/tls.crt" -noout -fingerprint -sha256 | sed 's/^[^=]*=//')
      [[ -n $pin ]] || die 'Unable to calculate Hysteria2 certificate fingerprint'
      link=$(python3 - "$client" "$pass" "$HOST" "$PORT" "$SNI" "$pin" "$name" <<'PY'
import sys,urllib.parse
user,pwd,host,port,sni,pin,name=sys.argv[1:]
auth=urllib.parse.quote(user,safe='')+':'+urllib.parse.quote(pwd,safe='')
q=urllib.parse.urlencode({'sni':sni,'insecure':'1','pinSHA256':pin})
print('hysteria2://'+auth+'@'+host+':'+port+'/?'+q+'#'+urllib.parse.quote(name,safe=''))
PY
)
      file="$cdir/client.json"
      python3 - "$file" "$HOST" "$PORT" "$client" "$pass" "$SNI" "$pin" <<'PY'
import json,sys
file,host,port,user,pwd,sni,pin=sys.argv[1:]
with open(file,'w',encoding='utf-8') as h:
    json.dump({'server':host+':'+port,'auth':user+':'+pwd,'tls':{'sni':sni,'insecure':True,'pinSHA256':pin}},h,indent=2)
PY
      mime='application/json' ;;
    *) die 'Configurations are not supported for this service' ;;
  esac
  [[ -f $file ]] || die 'Generated configuration is missing'
  printf 'ZDT_FILENAME=%s\n' "$(b64 "$(basename "$file")")"
  printf 'ZDT_MIME=%s\n' "$(b64 "$mime")"
  printf 'ZDT_CLIENT_NAME=%s\n' "$(b64 "$name")"
  [[ -n $link ]] && printf 'ZDT_LINK=%s\n' "$(b64 "$link")"
  [[ -n $sni_options ]] && printf 'ZDT_SNI_OPTIONS=%s\n' "$(b64 "$sni_options")"
  printf 'ZDT_CONFIG_BEGIN\n'; cat "$file"; printf '\nZDT_CONFIG_END\n'
}

delete_profile() {
  local kind=$1 profile=$2 dir unit='' certificate_domain=''
  dir=$(profile_dir "$kind" "$profile"); [[ -d $dir && -f $dir/meta.env ]] || die 'Profile not found'
  source "$dir/meta.env"
  [[ $kind == xray && ${MODE:-} == ws ]] && certificate_domain=${DOMAIN:-}
  case "$kind" in
    openvpn)
      systemctl disable --now "zdt-openvpn-net-$profile.service" "openvpn-server@zdt-$profile.service" >/dev/null 2>&1 || true
      rm -f "$SYSTEMD_DIR/zdt-openvpn-net-$profile.service" "$OPENVPN_DIR/zdt-$profile.conf"
      rm -rf "$SYSTEMD_DIR/openvpn-server@zdt-$profile.service.d" "/run/zdt-vps/openvpn/$profile" ;;
    xray)
      systemctl disable --now "zdt-xray-$profile.service" >/dev/null 2>&1 || true; rm -f "$SYSTEMD_DIR/zdt-xray-$profile.service" ;;
    hysteria2)
      systemctl disable --now "zdt-hysteria2-$profile.service" >/dev/null 2>&1 || true; rm -f "$SYSTEMD_DIR/zdt-hysteria2-$profile.service" ;;
    wireproxy)
      systemctl disable --now "wg-quick@$IFACE.service" >/dev/null 2>&1 || true; rm -f "$WIREGUARD_DIR/$IFACE.conf" ;;
    *) die 'Unknown profile service' ;;
  esac
  rm -rf "$dir"
  [[ -n $certificate_domain ]] && release_certificate "$certificate_domain"
  restore_ip_forward_if_unused
  refresh_firewall
  systemctl daemon-reload
}
remove_service() {
  local kind=$1 p
  service_installed "$kind" || return 0
  if [[ $kind != dnscrypt ]]; then
    for p in "$ZDT_STATE/profiles/$kind"/*; do [[ -d $p ]] && delete_profile "$kind" "$(basename "$p")"; done
  fi
  case "$kind" in
    dnscrypt)
      systemctl disable --now zdt-dnscrypt.service >/dev/null 2>&1 || true
      rm -f "$SYSTEMD_DIR/zdt-dnscrypt.service" "$ZDT_ROOT/bin/dnscrypt-proxy"
      rm -rf "$ZDT_ETC/dnscrypt" "$ZDT_STATE/dnscrypt-cache"
      rm -f "$ZDT_STATE/services/dnscrypt.version"
      restore_dns_original_state ;;
    openvpn)
      rm -rf "$ZDT_STATE/profiles/openvpn"; rm -f "$ZDT_STATE/services/openvpn.version" ;;
    xray)
      rm -rf "$ZDT_STATE/profiles/xray"; rm -f "$ZDT_STATE/services/xray.version" "$ZDT_ROOT/bin/xray" ;;
    hysteria2)
      rm -rf "$ZDT_STATE/profiles/hysteria2"; rm -f "$ZDT_STATE/services/hysteria2.version" "$ZDT_ROOT/bin/hysteria" ;;
    wireproxy)
      rm -rf "$ZDT_STATE/profiles/wireproxy"; rm -f "$ZDT_STATE/services/wireproxy.version" ;;
    *) die 'Unknown service' ;;
  esac
  restore_ip_forward_if_unused
  refresh_firewall
  if [[ ! -d $ZDT_STATE/profiles/xray || -z $(find "$ZDT_STATE/profiles/xray" -mindepth 1 -maxdepth 1 -type d -print -quit 2>/dev/null) ]]; then
    rm -f /etc/letsencrypt/renewal-hooks/deploy/zdt-vps-reload
  fi
  systemctl daemon-reload
}

restart_service() {
  local kind=$1 profile=${2:-}
  case "$kind" in
    dnscrypt) systemctl restart zdt-dnscrypt.service ;;
    openvpn) [[ -n $profile ]] || die 'Profile is required'; ensure_openvpn_traffic_config "$profile"; systemctl daemon-reload; systemctl restart "openvpn-server@zdt-$profile.service" "zdt-openvpn-net-$profile.service" ;;
    xray) [[ -n $profile ]] || die 'Profile is required'; rebuild_xray "$profile" ;;
    hysteria2) [[ -n $profile ]] || die 'Profile is required'; rebuild_hysteria2 "$profile" ;;
    wireproxy) [[ -n $profile ]] || die 'Profile is required'; load_meta wireproxy "$profile"; systemctl restart "wg-quick@$IFACE.service" ;;
    *) die 'Unknown service' ;;
  esac
}
reboot_server() {
  stage 'Scheduling server reboot'
  command -v systemctl >/dev/null 2>&1 || die 'systemd is required to reboot the server'
  nohup sh -c 'sleep 2; systemctl reboot' </dev/null >/dev/null 2>&1 &
  info 'Server reboot scheduled'
}

show_logs() {
  local kind=$1 profile=${2:-} unit
  case "$kind" in
    dnscrypt) unit=zdt-dnscrypt.service ;;
    openvpn) unit="openvpn-server@zdt-$profile.service" ;;
    xray) unit="zdt-xray-$profile.service" ;;
    hysteria2) unit="zdt-hysteria2-$profile.service" ;;
    wireproxy) load_meta wireproxy "$profile"; unit="wg-quick@$IFACE.service" ;;
    *) die 'Unknown service' ;;
  esac
  journalctl -u "$unit" -n 150 --no-pager
}

main() {
  require_root; ensure_dirs
  local action=${1:-}; shift || true
  case "$action" in
    check-platform) check_platform ;;
    inventory) inventory ;;
    install)
      check_platform; local kind=${1:?service required}
      case "$kind" in
        dnscrypt) with_tx install_dnscrypt ;;
        openvpn) with_tx install_openvpn ;;
        xray) with_tx install_xray ;;
        hysteria2) with_tx install_hysteria2 ;;
        wireproxy) with_tx install_wireproxy ;;
        *) die 'Unknown service' ;;
      esac ;;
    remove) with_tx remove_service "${1:?service required}" ;;
    list-profiles) list_profiles "${1:?service required}" ;;
    create-profile)
      check_platform
      local kind=$1 id=$2 name=$3 port=$4 mode=$5 host=$6 domain=${7:-} email=${8:-} sni=${9:-}
      case "$kind" in
        openvpn) with_tx create_openvpn_profile "$id" "$name" "$port" "$mode" "$host" ;;
        wireproxy) with_tx create_wireproxy_profile "$id" "$name" "$port" "$host" ;;
        xray) with_tx create_xray_profile "$id" "$name" "$port" "$mode" "$host" "$domain" "$email" "$sni" ;;
        hysteria2) with_tx create_hysteria2_profile "$id" "$name" "$port" "$host" "$domain" "$email" "$sni" ;;
        *) die 'Profiles are not supported for this service' ;;
      esac ;;
    delete-profile) with_tx delete_profile "$1" "$2" ;;
    list-clients) list_clients "$1" "$2" ;;
    create-client)
      case "$1" in
        openvpn) with_tx create_openvpn_client "$2" "$3" "${4:-$3}" ;;
        wireproxy) with_tx create_wireproxy_client "$2" "$3" "${4:-$3}" ;;
        xray) with_tx create_xray_client "$2" "$3" "${4:-$3}" ;;
        hysteria2) with_tx create_hysteria2_client "$2" "$3" "${4:-$3}" ;;
        *) die 'Unknown service' ;;
      esac ;;
    delete-client)
      case "$1" in
        openvpn) with_tx delete_openvpn_client "$2" "$3" ;;
        wireproxy) with_tx delete_wireproxy_client "$2" "$3" ;;
        xray) with_tx delete_xray_client "$2" "$3" ;;
        hysteria2) with_tx delete_hysteria2_client "$2" "$3" ;;
        *) die 'Unknown service' ;;
      esac ;;
    get-config) get_config "$1" "$2" "$3" ;;
    restart) restart_service "$1" "${2:-}" ;;
    reboot) reboot_server ;;
    logs) show_logs "$1" "${2:-}" ;;
    *) die 'Unknown action' ;;
  esac
}
main "$@"
