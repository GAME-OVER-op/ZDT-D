#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Provision the ZDT-D myprogram + myvpn profiles that run qwdtt-cli and route a
# test app through the qWDTT tunnel (M3). Run as root on the device.
#
#   sh install-zdtd-profiles.sh <test.app.package> [more.packages ...]
#
# It writes:
#   - a myprogram profile "qwdtt" whose command launches qwdtt-cli (it creates
#     the zdtdqw0 TUN),
#   - a myvpn profile "qwdtt" that binds the given app package(s) to zdtdqw0.
#
# Toybox/ash-compatible; no jq or bash-isms. Paths are overridable via env.

set -eu

PROFILE="${PROFILE:-qwdtt}"
TUN="${TUN:-zdtdqw0}"
DNS="${DNS:-8.8.8.8}"
WF="${ZDTD_WORKING_FOLDER:-/data/adb/modules/ZDT-D/working_folder}"
QWDTT_CLI="${QWDTT_CLI:-/data/adb/ZDT-D/bin/qwdtt-cli}"
QWDTT_CONF="${QWDTT_CONF:-/data/adb/ZDT-D/etc/qwdtt.conf}"

die() { echo "error: $*" >&2; exit 1; }

[ "$#" -ge 1 ] || die "usage: $0 <test.app.package> [more.packages ...]"

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
# apps_mode=false: myprogram only launches qwdtt-cli; routing is myvpn's job.
atomic_write "$MP/setting.json" <<JSON
{"apps_mode": false}
JSON
# Launched as: sh -c "<command.txt>" with cwd = $MP/bin. exec replaces sh so
# qwdtt-cli becomes the process-group leader for a clean group stop.
atomic_write "$MP/command.txt" <<CMD
exec $QWDTT_CLI -config $QWDTT_CONF
CMD

echo ">> provisioning myvpn profile '$PROFILE' (tun=$TUN)"
MV="$WF/myvpn/profile/$PROFILE"
mkdir -p "$MV/app/uid" "$MV/app/out"
# cidr_mode=auto: myvpn learns 10.66.0.1/32 from the interface qwdtt-cli brings up.
atomic_write "$MV/setting.json" <<JSON
{"tun": "$TUN", "dns": ["$DNS"], "cidr_mode": "auto", "cidr": ""}
JSON
# App list: packages to route through the tunnel, one per line. Do NOT add the
# 34 excluded_apps (banking/gov/Yandex) here — they must stay direct.
{ for pkg in "$@"; do echo "$pkg"; done; } | atomic_write "$MV/app/uid/user_program"

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
enable_profile myvpn

echo
echo "Done. Verify:"
echo "  1. $QWDTT_CLI and $QWDTT_CONF exist (chmod 700 the conf)."
echo "  2. amneziawg-go + awg present under /data/adb/modules/ZDT-D/bin/."
echo "  3. Restart ZDT-D (or toggle the profiles) so myprogram launches qwdtt-cli."
echo "  4. ip link show $TUN          # interface up"
echo "  5. tail $MP/log/program.log   # qwdtt-cli log: transport + wg bring-up"
echo "  6. From the test app, confirm egress goes through the tunnel."
