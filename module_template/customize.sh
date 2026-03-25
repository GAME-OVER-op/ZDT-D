#!/system/bin/sh
# Magisk Module Customize Script

################################################################################
# Pretty output helpers (Magisk installer provides ui_print + abort)
################################################################################
hr() { ui_print "########################################"; }
sec() { ui_print "## $1"; }
ok() { ui_print "- OK: $1"; }
warn() { ui_print "! $1"; }

fail() {
  hr
  ui_print "!! INSTALLATION ABORTED !!"
  ui_print "! Reason: $1"
  hr
  abort "$1"
}

################################################################################
# Pre-checks: Android 11+ (SDK >= 30) and arm64 only
################################################################################
hr
sec "Magisk Module Pre-checks"
ui_print "## Requirements:"
ui_print "## - Android 11+ (SDK >= 30)"
ui_print "## - arm64 only (arm64-v8a / aarch64)"
hr

SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
REL="$(getprop ro.build.version.release 2>/dev/null)"

case "$SDK" in
  ''|*[!0-9]*)
    fail "Cannot determine Android SDK version (ro.build.version.sdk)."
    ;;
esac

ui_print "## Device:"
ui_print "## - Android: ${REL:-unknown}"
ui_print "## - SDK:     $SDK"
hr

if [ "$SDK" -lt 30 ]; then
  [ -n "$REL" ] || REL="unknown"
  fail "Android 11+ required. Detected Android $REL (SDK $SDK)."
else
  ok "Android version is supported (SDK >= 30)"
fi

ABI64="$(getprop ro.product.cpu.abilist64 2>/dev/null)"
ABI="$(getprop ro.product.cpu.abi 2>/dev/null)"
ABILIST="$(getprop ro.product.cpu.abilist 2>/dev/null)"
UNAME_M="$(uname -m 2>/dev/null | tr 'A-Z' 'a-z')"

ui_print "## Architecture info:"
ui_print "## - abilist64: ${ABI64:-unknown}"
ui_print "## - abi:       ${ABI:-unknown}"
ui_print "## - abilist:   ${ABILIST:-unknown}"
ui_print "## - uname -m:  ${UNAME_M:-unknown}"
hr

if echo "$ABI64" | grep -qE '(^|[ ,])arm64-v8a([ ,]|$)'; then
  ok "arm64-v8a detected (abilist64)"
elif echo "$ABILIST $ABI" | grep -qE '(^|[ ,])arm64-v8a([ ,]|$)'; then
  ok "arm64-v8a detected"
elif [ "$UNAME_M" = "aarch64" ]; then
  ok "aarch64 detected"
else
  warn "Unsupported architecture detected"
  fail "arm64 required (arm64-v8a/aarch64). Detected ABI64='${ABI64:-unknown}' ABI='${ABI:-unknown}' uname='${UNAME_M:-unknown}'"
fi

hr
sec "Checks passed"
ui_print "## * Proceeding with installation..."
hr

################################################################################
# Permissions: chmod 755 for bin/* and service.sh
################################################################################
hr
sec "Permissions"
ui_print "## Setting executable permissions (755)..."

MODDIR="${MODPATH:-$PWD}"

# bin directory inside module
BINDIR="$MODDIR/bin"
SERVICE="$MODDIR/service.sh"

if [ ! -d "$BINDIR" ]; then
  fail "Folder not found: $BINDIR"
fi

# chmod all regular files in bin
COUNT=0
for f in "$BINDIR"/*; do
  [ -e "$f" ] || continue
  if [ -f "$f" ]; then
    chmod 755 "$f" 2>/dev/null || fail "chmod 755 failed: $f"
    ui_print "- 755: bin/$(basename "$f")"
    COUNT=$((COUNT + 1))
  fi
done

if [ "$COUNT" -eq 0 ]; then
  ui_print "! Warning: bin/ is empty or has no regular files"
else
  ok "bin/* permissions set ($COUNT file(s))"
fi

# chmod service.sh (required)
if [ -f "$SERVICE" ]; then
  chmod 755 "$SERVICE" 2>/dev/null || fail "chmod 755 failed: $SERVICE"
  ok "service.sh permissions set (755)"
else
  fail "File not found: $SERVICE"
fi

hr
sec "Done"
ui_print "## Installation steps completed."
hr
