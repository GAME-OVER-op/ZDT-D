#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Provision the ZDT-D profiles that run qwdtt-cli and route a test app through
# the qWDTT tunnel (M3). Run as root on the device.
#
#   sh install-zdtd-profiles.sh <test.app.package> [more.packages ...]
#
# It always writes a myprogram profile "qwdtt" whose command launches qwdtt-cli.
# How the app is routed depends on MODE, which must match `mode` in qwdtt.conf:
#
#   MODE=vpn   (default) qwdtt-cli creates the zdtdqw0 TUN; a myvpn profile binds
#              the app UIDs to it.
#   MODE=socks the transport serves SOCKS5 from its own userspace WireGuard (no
#              TUN, no amneziawg-go/awg); a myproxy profile redirects the app
#              UIDs through t2s to that SOCKS5 port.
#
# NOTE for MODE=socks: the upstream SOCKS server is TCP CONNECT only, so app UDP
# (QUIC, direct DNS) is not carried and goes direct -- which a whitelist ISP
# drops. Treat it as experimental until tested on your SIM.
#
# Toybox/ash-compatible; no jq or bash-isms. Paths are overridable via env.

set -eu

PROFILE="${PROFILE:-qwdtt}"
MODE="${MODE:-vpn}"
# MODE=socks only: must match socks_addr in qwdtt.conf.
SOCKS_HOST="${SOCKS_HOST:-127.0.0.1}"
SOCKS_PORT="${SOCKS_PORT:-1080}"
# t2s listener ports for the myproxy profile (myproxy's own defaults).
T2S_PORT="${T2S_PORT:-12348}"
T2S_WEB_PORT="${T2S_WEB_PORT:-8004}"
TUN="${TUN:-zdtdqw0}"
DNS="${DNS:-8.8.8.8}"
# Tunnel CIDR for the myvpn profile. When set (the default), the profile uses
# cidr_mode=manual, which avoids myvpn's auto-detect path entirely. The address
# is assigned by the VPS, so verify it once with
#   ip -o -4 addr show dev <tun>
# and override with CIDR=... if your server hands out a different one. Set
# CIDR="" to fall back to cidr_mode=auto.
CIDR="${CIDR-10.66.0.2/32}"
WF="${ZDTD_WORKING_FOLDER:-/data/adb/modules/ZDT-D/working_folder}"
# Binaries and config are uploaded into the profile's bin/ dir via the ZDT-D app
# (myprogram's per-profile file uploader, which chmod 755s them). myprogram runs
# the command with cwd = that bin/ dir.
QWDTT_CLI="${QWDTT_CLI:-/data/adb/modules/ZDT-D/working_folder/myprogram/profile/qwdtt/bin/qwdtt-cli}"
QWDTT_TRANSPORT="${QWDTT_TRANSPORT:-/data/adb/modules/ZDT-D/working_folder/myprogram/profile/qwdtt/bin/qwdtt-transport}"
QWDTT_CONF="${QWDTT_CONF:-/data/adb/modules/ZDT-D/working_folder/myprogram/profile/qwdtt/bin/qwdtt.conf}"

die() { echo "error: $*" >&2; exit 1; }

[ "$#" -ge 1 ] || die "usage: $0 <test.app.package> [more.packages ...]"

case "$MODE" in
	vpn|socks) ;;
	*) die "MODE must be vpn or socks, got '$MODE'" ;;
esac

# Refuse UID 0 / system entries in the routed set: root in the bound range makes
# the transport tunnel itself and the stack deadlocks (project invariant).
for pkg in "$@"; do
	case "$pkg" in
		""|root|system|0|android|com.android.*|com.google.android.gms*)
			die "refusing to route a system/root entry: '$pkg' (would deadlock the stack)"
			;;
	esac
done

atomic_write() { # atomic_write <path> <<heredoc content on stdin>
	dir=$(dirname "$1")
	mkdir -p "$dir"
	tmp="$1.tmp.$$"
	cat > "$tmp"
	mv "$tmp" "$1"
}

echo ">> provisioning myprogram profile '$PROFILE'"
MP="$WF/myprogram/profile/$PROFILE"
mkdir -p "$MP/bin" "$MP/log" "$MP/app/uid" "$MP/app/out"
# apps_mode=false: myprogram only launches qwdtt-cli; routing belongs to the
# myvpn (MODE=vpn) or myproxy (MODE=socks) profile below.
atomic_write "$MP/setting.json" <<JSON
{"apps_mode": false}
JSON
# Launched as: sh -c "<command.txt>" with cwd = $MP/bin. exec replaces sh so
# qwdtt-cli becomes the process-group leader for a clean group stop.
atomic_write "$MP/command.txt" <<CMD
exec $QWDTT_CLI -config $QWDTT_CONF
CMD

# Routing profile: myvpn binds UIDs to the TUN; myproxy redirects UIDs through
# t2s into the transport's SOCKS5 port. Exactly one is provisioned.
if [ "$MODE" = "socks" ]; then
	echo ">> provisioning myproxy profile '$PROFILE' (socks=$SOCKS_HOST:$SOCKS_PORT)"
	MX="$WF/myproxy/profile/$PROFILE"
	mkdir -p "$MX/app/uid" "$MX/app/out" "$MX/log"
	# t2s listens locally and forwards to the transport's SOCKS5 server.
	atomic_write "$MX/setting.json" <<JSON
{"t2s_port": $T2S_PORT, "t2s_web_port": $T2S_WEB_PORT}
JSON
	atomic_write "$MX/proxy.json" <<JSON
{"host": "$SOCKS_HOST", "port": $SOCKS_PORT, "user": "", "pass": ""}
JSON
	# App list: packages to route through the tunnel, one per line. Do NOT add the
	# 34 excluded_apps (banking/gov/Yandex) here — they must stay direct.
	{ for pkg in "$@"; do echo "$pkg"; done; } | atomic_write "$MX/app/uid/user_program"

	ROUTING_PROGRAM=myproxy
	ROUTING_DIR="$MX"
else

echo ">> provisioning myvpn profile '$PROFILE' (tun=$TUN)"
MV="$WF/myvpn/profile/$PROFILE"
mkdir -p "$MV/app/uid" "$MV/app/out"
# cidr_mode: prefer manual. With auto, myvpn learns the CIDR by inspecting the
# interface — but the link exists for a moment before qwdtt-cli assigns its
# address, so auto can lose that race and skip the profile, leaving the app UIDs
# unbound (the tunnel is up, yet the app has no route). Manual sidesteps it.
if [ -n "$CIDR" ]; then
	echo "   cidr_mode=manual cidr=$CIDR"
	atomic_write "$MV/setting.json" <<JSON
{"tun": "$TUN", "dns": ["$DNS"], "cidr_mode": "manual", "cidr": "$CIDR"}
JSON
else
	echo "   cidr_mode=auto (requires a ZDT-D build with the myvpn CIDR-wait fix)"
	atomic_write "$MV/setting.json" <<JSON
{"tun": "$TUN", "dns": ["$DNS"], "cidr_mode": "auto", "cidr": ""}
JSON
fi
# App list: packages to route through the tunnel, one per line. Do NOT add the
# 34 excluded_apps (banking/gov/Yandex) here — they must stay direct.
{ for pkg in "$@"; do echo "$pkg"; done; } | atomic_write "$MV/app/uid/user_program"

ROUTING_PROGRAM=myvpn
ROUTING_DIR="$MV"
fi

# active.json: enable the profile for each program. Only written when absent or
# empty, so an existing enabled set for other profiles is never clobbered.
enable_profile() {
	prog="$1"
	active="$WF/$prog/active.json"
	mkdir -p "$WF/$prog"
	if [ -s "$active" ] && grep -q '"enabled"[[:space:]]*:[[:space:]]*true' "$active" 2>/dev/null; then
		echo "!! $prog/active.json already has enabled profiles; not modifying it."
		echo "   Enable '$PROFILE' for $prog in the ZDT-D app, or add manually:"
		echo '     {"profiles":{"'"$PROFILE"'":{"enabled":true}, ...}}'
		return
	fi
	atomic_write "$active" <<JSON
{"profiles": {"$PROFILE": {"enabled": true}}}
JSON
	echo ">> enabled $prog profile '$PROFILE'"
}
enable_profile myprogram
enable_profile "$ROUTING_PROGRAM"

# Preflight: the binaries/config are uploaded separately (via the app). Warn if
# they are not in place yet so the first launch does not silently fail.
for f in "$QWDTT_CLI" "$QWDTT_TRANSPORT" "$QWDTT_CONF"; do
	[ -e "$f" ] || echo "!! not present yet (upload via the ZDT-D app): $f"
done

echo
echo "Done. Verify:"
echo "  1. Upload qwdtt-cli, qwdtt-transport, qwdtt.conf into the profile bin/ via the app:"
echo "       $MP/bin/"
echo "  2. In $QWDTT_CONF set:"
echo "       transport_binary = $QWDTT_TRANSPORT"
echo "       mode = $MODE"
if [ "$MODE" = "socks" ]; then
	echo "       socks_addr = $SOCKS_HOST:$SOCKS_PORT"
fi
echo "     (the transport path and mode are read from qwdtt.conf, not this script;"
echo "      MODE here only selects which routing profile is provisioned)."
if [ "$MODE" = "socks" ]; then
	echo "  3. (no amneziawg-go/awg needed in socks mode)"
else
	echo "  3. amneziawg-go + awg present under /data/adb/modules/ZDT-D/bin/."
fi
echo "  4. Restart ZDT-D (or toggle the profiles) so myprogram launches qwdtt-cli."
if [ "$MODE" = "socks" ]; then
	echo "  5. grep 'socks: listening' $MP/log/program.log"
else
	echo "  5. ip link show $TUN          # interface up"
fi
echo "  6. tail $MP/log/program.log   # qwdtt-cli log"
echo "  7. cat $ROUTING_DIR/app/out/user_program   # must list <package>=<uid>; empty"
echo "     means $ROUTING_PROGRAM skipped the profile (check zdtd.log)."
echo "  8. From the test app (its real UID, not root), confirm egress via the tunnel."
if [ "$MODE" = "socks" ]; then
	echo
	echo "socks mode is TCP CONNECT only: app UDP (QUIC, direct DNS) is not carried"
	echo "and goes direct, which a whitelist ISP drops. Verify before relying on it."
fi
if [ "$MODE" = "vpn" ] && [ -n "$CIDR" ]; then
	echo
	echo "Note: cidr=$CIDR is assumed. Verify with 'ip -o -4 addr show dev $TUN'"
	echo "and re-run with CIDR=<actual> if the VPS assigns a different address."
fi
