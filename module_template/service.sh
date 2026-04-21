#!/system/bin/sh

while [[ "$(getprop sys.boot_completed)" != "1" ]]; do
  sleep 5
done

MODDIR="${0%/*}"
LOGDIR="/data/adb/modules/ZDT-D/log"
LOGFILE="$LOGDIR/deamon.log"

mkdir -p "$LOGDIR"
setsid "$MODDIR/bin/zdtd" >>"$LOGFILE" 2>&1 </dev/null &
#!/system/bin/sh

apply() {
  k="$1"
  v="$2"
  p="/proc/sys/$(echo "$k" | tr . /)"
  [ -e "$p" ] || return 0
  echo "$v" > "$p" 2>/dev/null || sysctl -w "$k=$v" >/dev/null 2>&1 || true
}

getv() {
  k="$1"
  p="/proc/sys/$(echo "$k" | tr . /)"
  [ -r "$p" ] && cat "$p" 2>/dev/null || sysctl -n "$k" 2>/dev/null
}

try_set_qdisc() {
  for q in fq_codel fq codel; do
    apply net.core.default_qdisc "$q"
    cur="$(getv net.core.default_qdisc)"
    [ "$cur" = "$q" ] && return 0
  done
  return 0
}

set_cc() {
  cc="$(getv net.ipv4.tcp_available_congestion_control)"
  echo "$cc" | grep -qw bbr && { apply net.ipv4.tcp_congestion_control bbr; return 0; }
  echo "$cc" | grep -qw cubic && { apply net.ipv4.tcp_congestion_control cubic; return 0; }
  return 0
}

# host mode
apply net.ipv4.ip_forward 0

# qdisc / congestion control
try_set_qdisc
set_cc

# queues / buffers
apply net.core.netdev_max_backlog 4096
apply net.core.rmem_default 262144
apply net.core.wmem_default 262144
apply net.core.rmem_max 4194304
apply net.core.wmem_max 4194304
apply net.core.optmem_max 65536

# ports / backlog
apply net.ipv4.ip_local_port_range "10240 65535"
apply net.core.somaxconn 4096
apply net.ipv4.tcp_max_syn_backlog 4096

# tcp
apply net.ipv4.tcp_mtu_probing 1
apply net.ipv4.tcp_slow_start_after_idle 0

# security / sane defaults
apply net.ipv4.conf.all.accept_redirects 0
apply net.ipv4.conf.default.accept_redirects 0
apply net.ipv4.conf.all.send_redirects 0
apply net.ipv4.conf.default.send_redirects 0
apply net.ipv4.conf.all.accept_source_route 0
apply net.ipv4.conf.default.accept_source_route 0
apply net.ipv4.conf.all.route_localnet 0
apply net.ipv4.conf.default.route_localnet 0
apply net.ipv4.conf.all.rp_filter 2
apply net.ipv4.conf.default.rp_filter 2
apply net.ipv4.icmp_echo_ignore_broadcasts 1
apply net.ipv4.icmp_ignore_bogus_error_responses 1
apply net.ipv4.conf.all.log_martians 0
apply net.ipv4.conf.default.log_martians 0

# conntrack
apply net.netfilter.nf_conntrack_max 262144
apply net.netfilter.nf_conntrack_tcp_be_liberal 0

# quiet printk
apply kernel.printk "3 3 3 3"

# Disable system DNS 
settings put global private_dns_mode off

exit 0
