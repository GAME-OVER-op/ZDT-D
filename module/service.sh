#!/system/bin/sh

while [[ "$(getprop sys.boot_completed)" != "1" ]]; do
  sleep 5
done

MODDIR="${0%/*}"
LOGDIR="/data/adb/modules/ZDT-D/log"
LOGFILE="$LOGDIR/deamon.log"

mkdir -p "$LOGDIR"
setsid "$MODDIR/bin/zdtd" >>"$LOGFILE" 2>&1 </dev/null &

apply() {
  k="$1"
  v="$2"
  p="/proc/sys/$(echo "$k" | tr . /)"
  [ -e "$p" ] || return 0
  echo "$v" > "$p" 2>/dev/null || sysctl -w "$k=$v" >/dev/null 2>&1 || true
}

# --- qdisc: low latency first ---
apply net.core.default_qdisc fq_codel
# fallback if not accepted
sysctl -n net.core.default_qdisc >/dev/null 2>&1 || true
apply net.core.default_qdisc fq
apply net.core.default_qdisc pfifo_fast

# --- congestion control: bbr if available ---
cc="$(sysctl -n net.ipv4.tcp_available_congestion_control 2>/dev/null)"
echo "$cc" | grep -qw bbr && apply net.ipv4.tcp_congestion_control bbr
echo "$cc" | grep -qw cubic && apply net.ipv4.tcp_congestion_control cubic

# --- low-latency + sane backlog/budget ---
apply net.core.netdev_max_backlog 50000
apply net.core.netdev_budget 600
apply net.core.netdev_budget_usecs 4000

# --- socket buffers (reserve without insanity) ---
apply net.core.rmem_default 524288
apply net.core.wmem_default 524288
apply net.core.rmem_max 67108864
apply net.core.wmem_max 67108864
apply net.ipv4.tcp_rmem "4096 262144 67108864"
apply net.ipv4.tcp_wmem "4096 262144 67108864"
apply net.core.optmem_max 65536

# --- ports / servers ---
apply net.ipv4.ip_local_port_range "10240 65535"
apply net.core.somaxconn 4096
apply net.ipv4.tcp_max_syn_backlog 16384

# --- tcp behavior ---
apply net.ipv4.tcp_mtu_probing 1
apply net.ipv4.tcp_fin_timeout 30
apply net.ipv4.tcp_slow_start_after_idle 0
apply net.ipv4.tcp_keepalive_time 600
apply net.ipv4.tcp_keepalive_intvl 60
apply net.ipv4.tcp_keepalive_probes 3
apply net.ipv4.tcp_retries1 3
apply net.ipv4.tcp_retries2 12
apply net.ipv4.tcp_syncookies 1
apply net.ipv4.tcp_sack 1
apply net.ipv4.tcp_dsack 1

# --- security hardening ---
apply net.ipv4.conf.all.accept_redirects 0
apply net.ipv4.conf.default.accept_redirects 0
apply net.ipv4.conf.all.send_redirects 0
apply net.ipv4.conf.all.accept_source_route 0
apply net.ipv4.conf.all.rp_filter 2
apply net.ipv4.conf.default.rp_filter 2
apply net.ipv4.icmp_echo_ignore_broadcasts 1
apply net.ipv4.icmp_ignore_bogus_error_responses 1

# --- disable martian logging ---
apply net.ipv4.conf.all.log_martians 0
apply net.ipv4.conf.default.log_martians 0

# --- conntrack capacity (if exists) ---
apply net.netfilter.nf_conntrack_max 262144
apply net.netfilter.nf_conntrack_tcp_be_liberal 0

# --- safest default: no routing / no route_localnet ---
apply net.ipv4.ip_forward 0
apply net.ipv4.conf.all.route_localnet 0
apply net.ipv4.conf.default.route_localnet 0

# --- eBPF JIT: max security ---
apply net.core.bpf_jit_enable 0
apply net.core.bpf_jit_harden 2
apply net.core.bpf_jit_kallsyms 0

# --- quiet console printk (if allowed) ---
apply kernel.printk "3 3 3 3"

# -- route localnet enable 
apply net.ipv4.conf.all.route_localnet 1

exit 0
