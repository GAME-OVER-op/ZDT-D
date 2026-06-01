#!/system/bin/sh
# Magisk Module Customize Script

################################################################################
# Pretty output helpers (Magisk installer provides ui_print + abort)
################################################################################
hr() { ui_print "########################################"; }
sec() { ui_print "## $1"; }
ok() { ui_print "- OK: $1"; }
warn() { ui_print "! $1"; }

ZDTD_PROGRESS_FILE="${ZDTD_INSTALL_PROGRESS_FILE:-/data/local/tmp/zdt_install_progress}"

zdt_progress() {
  percent="$1"
  shift
  message="$*"

  if [ -n "${ZDTD_PROGRESS_FILE:-}" ]; then
    tmp="${ZDTD_PROGRESS_FILE}.tmp.$$"
    {
      echo "percent=$percent"
      echo "message=$message"
      echo "time=$(date +%s 2>/dev/null || echo 0)"
    } > "$tmp" 2>/dev/null && {
      chmod 0644 "$tmp" 2>/dev/null || true
      mv -f "$tmp" "$ZDTD_PROGRESS_FILE" 2>/dev/null || true
    }
  fi

  ui_print "ZDTD_PROGRESS:$percent:$message"
}

fail() {
  zdt_progress 98 "Installation aborted"
  hr
  ui_print "!! INSTALLATION ABORTED !!"
  ui_print "! Reason: $1"
  hr
  abort "$1"
}

################################################################################
# Pre-checks: Android 9+ (SDK >= 28) and arm64 only
################################################################################
zdt_progress 65 "Running module pre-checks"
hr
sec "Magisk Module Pre-checks"
ui_print "## Requirements:"
ui_print "## - Android 9+ (SDK >= 28)"
ui_print "## - Officially supported: Android 11+ (SDK >= 30)"
ui_print "## - arm64 only (arm64-v8a / aarch64)"
hr


num_or_zero() {
  case "${1:-}" in
    ''|*[!0-9]*) echo 0 ;;
    *) echo "$1" ;;
  esac
}

fail_code() {
  code="$1"
  shift
  ui_print "! $code"
  fail "$*"
}

ZYGISK_MARKER="/data/adb/ZDT-D/zygisk"

if [ -f "$ZYGISK_MARKER" ]; then
  MAGISK_CODE="$(num_or_zero "${MAGISK_VER_CODE:-0}")"
  KSU_CODE="$(num_or_zero "${KSU_VER_CODE:-0}")"
  APATCH_CODE="$(num_or_zero "${APATCH_VER_CODE:-0}")"

  IS_KSU=0
  IS_APATCH=0
  [ "${KSU:-}" = "true" ] && IS_KSU=1
  [ -n "${KSU_VER_CODE:-}" ] && IS_KSU=1
  [ "${APATCH:-}" = "true" ] && IS_APATCH=1
  [ "${KERNELPATCH:-}" = "true" ] && IS_APATCH=1
  [ -n "${APATCH_VER_CODE:-}" ] && IS_APATCH=1

  zdt_progress 68 "Checking Zygisk requirements"
  ui_print "## Zygisk component requested"

  if [ "$IS_APATCH" -eq 1 ]; then
    ui_print "## Root manager: APatch / KernelPatch compatible"
    if [ "$APATCH_CODE" -gt 0 ] && [ "$APATCH_CODE" -lt 10700 ]; then
      fail_code "ZDTD_ZYGISK_APATCH_TOO_OLD" "APatch 10700+ required for Zygisk-compatible installation. Detected APATCH_VER_CODE=$APATCH_CODE."
    elif [ "$APATCH_CODE" -eq 0 ]; then
      ui_print "! ZDTD_ZYGISK_APATCH_UNSUPPORTED"
      warn "Cannot determine APatch version. Zygisk component requires APatch 10700+ and an installed, enabled, running Zygisk layer."
    else
      ok "APatch versionCode is compatible: $APATCH_CODE"
    fi
    warn "APatch requires a compatible Zygisk layer (for example ZygiskNext) to be installed, enabled, and running."
  elif [ "$IS_KSU" -eq 1 ]; then
    ui_print "## Root manager: KernelSU compatible"
    if [ "$KSU_CODE" -gt 0 ] && [ "$KSU_CODE" -lt 10940 ]; then
      fail_code "ZDTD_ZYGISK_KSU_TOO_OLD" "KernelSU 10940+ required for Zygisk-compatible installation. Detected KSU_VER_CODE=$KSU_CODE."
    elif [ "$KSU_CODE" -eq 0 ]; then
      ui_print "! ZDTD_ZYGISK_KSU_UNSUPPORTED"
      warn "Cannot determine KernelSU version. Zygisk component requires KernelSU 10940+ and an installed, enabled, running Zygisk layer."
    else
      ok "KernelSU versionCode is compatible: $KSU_CODE"
    fi
    warn "KernelSU requires a compatible Zygisk layer (for example ZygiskNext/ZygiskOnKernelSU) to be installed, enabled, and running."
  else
    ui_print "## Root manager: Magisk"
    if [ "$MAGISK_CODE" -lt 26000 ]; then
      fail_code "ZDTD_ZYGISK_MAGISK_TOO_OLD" "Magisk 26.0+ required for Zygisk API v4. Detected MAGISK_VER_CODE=$MAGISK_CODE."
    fi
    ok "Magisk versionCode is compatible: $MAGISK_CODE"
  fi
fi

SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
REL="$(getprop ro.build.version.release 2>/dev/null)"

case "$SDK" in
  ''|*[!0-9]*)
    fail "Cannot determine Android SDK version (ro.build.version.sdk)."
    ;;
esac

zdt_progress 72 "Checking Android version"
ui_print "## Device:"
ui_print "## - Android: ${REL:-unknown}"
ui_print "## - SDK:     $SDK"
hr

if [ "$SDK" -lt 28 ]; then
  [ -n "$REL" ] || REL="unknown"
  fail "Android 9+ required. Detected Android $REL (SDK $SDK)."
elif [ "$SDK" -lt 30 ]; then
  warn "Android $REL (SDK $SDK) detected."
  warn "This Android version is allowed, but not officially tested."
  warn "Official support starts from Android 11+ (SDK >= 30)."
  warn "If something does not work on Android 9/10, it is at the user's own risk."
else
  ok "Android version is supported (SDK >= 30)"
fi

zdt_progress 78 "Checking CPU architecture"
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

zdt_progress 82 "Pre-checks passed"
hr
sec "Checks passed"
ui_print "## * Proceeding with installation..."
hr

################################################################################
# Permissions: chmod 755 for bin/* and service.sh
################################################################################
zdt_progress 86 "Applying module permissions"
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

zdt_progress 90 "Preparing service scripts"

# chmod service.sh (required)
if [ -f "$SERVICE" ]; then
  chmod 755 "$SERVICE" 2>/dev/null || fail "chmod 755 failed: $SERVICE"
  ok "service.sh permissions set (755)"
else
  fail "File not found: $SERVICE"
fi

zdt_progress 93 "Preparing optional Zygisk component"
ZYGISK_DIR="$MODDIR/zygisk"
ZYGISK_SO="$ZYGISK_DIR/arm64-v8a.so"
if [ -f "$ZYGISK_MARKER" ]; then
  ui_print "- Zygisk component: enabled by marker"
  rm -f "$ZYGISK_DIR/unloaded" 2>/dev/null || true
  if [ -f "$ZYGISK_SO" ]; then
    chmod 755 "$ZYGISK_DIR" 2>/dev/null || true
    chmod 644 "$ZYGISK_SO" 2>/dev/null || fail "chmod 644 failed: $ZYGISK_SO"
    ok "Zygisk arm64-v8a.so found"
  else
    fail_code "ZDTD_ZYGISK_LIBRARY_MISSING" "Zygisk marker exists, but library not found: $ZYGISK_SO"
  fi
else
  ui_print "- Zygisk component: disabled"
  rm -rf "$ZYGISK_DIR" 2>/dev/null || true
fi


zdt_progress 96 "Finalizing module installation"
hr
sec "Done"
ui_print "## Installation steps completed."
hr