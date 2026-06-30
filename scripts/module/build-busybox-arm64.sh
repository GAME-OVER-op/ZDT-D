#!/usr/bin/env bash
set -euo pipefail

# Build the bundled Android BusyBox helper from official BusyBox sources.
# The resulting binary is used only as a local unzip fallback for ZDT-D module
# installation/checks. No prebuilt third-party BusyBox binary is downloaded.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_PATH="${1:-$ROOT_DIR/prebuilt/bin/arm64-v8a/busybox}"
ZDT_BUSYBOX_ABI="${ZDT_BUSYBOX_ABI:-}"
if [[ -z "$ZDT_BUSYBOX_ABI" ]]; then
  case "$OUT_PATH" in
    */arm-v7a/*|*/armeabi-v7a/*) ZDT_BUSYBOX_ABI="arm-v7a" ;;
    *) ZDT_BUSYBOX_ABI="arm64-v8a" ;;
  esac
fi
case "$ZDT_BUSYBOX_ABI" in
  arm64-v8a)
    BUSYBOX_CLANG_PREFIX="aarch64-linux-android"
    BUSYBOX_FILE_PATTERN="aarch64|ARM aarch64"
    ;;
  arm-v7a|armeabi-v7a)
    ZDT_BUSYBOX_ABI="arm-v7a"
    BUSYBOX_CLANG_PREFIX="armv7a-linux-androideabi"
    BUSYBOX_FILE_PATTERN="ARM"
    ;;
  *)
    printf '[ZDT-D][BusyBox][ERR] Unsupported ZDT_BUSYBOX_ABI: %s\n' "$ZDT_BUSYBOX_ABI" >&2
    exit 1
    ;;
esac
BUSYBOX_VERSION="${ZDT_BUSYBOX_VERSION:-1.37.0}"
BUSYBOX_TARBALL="busybox-${BUSYBOX_VERSION}.tar.bz2"
BUSYBOX_URL="${ZDT_BUSYBOX_SOURCE_URL:-https://busybox.net/downloads/${BUSYBOX_TARBALL}}"
# BusyBox 1.37.0 official source tarball SHA-256.
BUSYBOX_SHA256="${ZDT_BUSYBOX_SOURCE_SHA256:-3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4}"
ANDROID_API="${ZDT_BUSYBOX_ANDROID_API:-23}"
JOBS="${ZDT_BUSYBOX_BUILD_JOBS:-$(command -v nproc >/dev/null 2>&1 && nproc || printf '2')}"
TOOLS_DIR="${ZDT_TOOLS_DIR:-$ROOT_DIR/.tools}"
WORK_DIR="$TOOLS_DIR/busybox"
DOWNLOAD_DIR="$WORK_DIR/downloads"
SRC_DIR="$WORK_DIR/src/busybox-${BUSYBOX_VERSION}"
BUILD_DIR="$WORK_DIR/build/$ZDT_BUSYBOX_ABI"
TARBALL_PATH="$DOWNLOAD_DIR/$BUSYBOX_TARBALL"

msg() { printf '[ZDT-D][BusyBox] %s\n' "$*"; }
fail() { printf '[ZDT-D][BusyBox][ERR] %s\n' "$*" >&2; exit 1; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

host_tag() {
  case "$(uname -s)" in
    Linux) printf 'linux-x86_64' ;;
    Darwin) printf 'darwin-x86_64' ;;
    *) fail "Unsupported host OS: $(uname -s)" ;;
  esac
}

find_ndk_root() {
  local candidate
  for candidate in "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK_HOME:-}" "${NDK_ROOT:-}"; do
    if [[ -n "$candidate" && -d "$candidate/toolchains/llvm/prebuilt" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
    candidate="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1 || true)"
    if [[ -n "$candidate" && -d "$candidate/toolchains/llvm/prebuilt" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  fi
  return 1
}

download_source() {
  mkdir -p "$DOWNLOAD_DIR"
  if [[ -s "$TARBALL_PATH" ]]; then
    msg "source already downloaded: $TARBALL_PATH"
  else
    msg "downloading official source: $BUSYBOX_URL"
    local tmp="$TARBALL_PATH.tmp"
    rm -f "$tmp"
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 --retry-delay 2 -o "$tmp" "$BUSYBOX_URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$tmp" "$BUSYBOX_URL"
    else
      python3 - "$BUSYBOX_URL" "$tmp" <<'PY'
import sys, urllib.request
url, out = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": "ZDT-D-build/1.0"})
with urllib.request.urlopen(req, timeout=120) as r, open(out, "wb") as f:
    while True:
        b = r.read(1024 * 1024)
        if not b:
            break
        f.write(b)
PY
    fi
    mv -f "$tmp" "$TARBALL_PATH"
  fi
  local actual
  actual="$(sha256_file "$TARBALL_PATH")"
  if [[ "$actual" != "$BUSYBOX_SHA256" ]]; then
    fail "BusyBox source SHA-256 mismatch: expected=$BUSYBOX_SHA256 actual=$actual"
  fi
  msg "source sha256 OK: $actual"
}

extract_source() {
  mkdir -p "$WORK_DIR/src"
  if [[ -f "$SRC_DIR/Makefile" ]]; then
    msg "source already extracted: $SRC_DIR"
    return 0
  fi
  rm -rf "$SRC_DIR"
  msg "extracting source"
  tar -xjf "$TARBALL_PATH" -C "$WORK_DIR/src"
}

configure_busybox() {
  mkdir -p "$BUILD_DIR"
  local mini="$BUILD_DIR/zdt-busybox-miniconfig"
  msg "creating minimal BusyBox config"
  cat > "$mini" <<'CONFIG'
# CONFIG_STATIC is not set
CONFIG_LFS=y
CONFIG_LONG_OPTS=y
CONFIG_PIE=y
CONFIG_SHOW_USAGE=y
CONFIG_FEATURE_VERBOSE_USAGE=y
# Keep the applet set intentionally small. ZDT-D needs BusyBox primarily for
# local-header ZIP extraction of protected module archives.
CONFIG_BUSYBOX=y
CONFIG_UNZIP=y
CONFIG_FEATURE_UNZIP_CDF=n
CONFIG_FEATURE_UNZIP_BZIP2=n
CONFIG_FEATURE_UNZIP_LZMA=n
CONFIG_FEATURE_UNZIP_XZ=n
CONFIG_FEATURE_SEAMLESS_Z=y
CONFIG_FEATURE_SEAMLESS_GZ=y
CONFIG_GUNZIP=y
CONFIG_CAT=y
CONFIG_CP=y
CONFIG_CHMOD=y
CONFIG_MKDIR=y
CONFIG_RM=y
CONFIG_ECHO=y
CONFIG_LS=y
CONFIG_TRUE=y
CONFIG_FALSE=y
CONFIG_TEST=y
CONFIG_SHA256SUM=y
CONFIG_FEATURE_MD5_SHA1_SUM_CHECK=y
CONFIG_FEATURE_BUFFERS_USE_MALLOC=y
# CONFIG_STATIC_LIBGCC is not set
CONFIG_WERROR=n
CONFIG_FEATURE_SUID=n
CONFIG_SELINUX=n
CONFIG_PAM=n
CONFIG_FEATURE_COMPRESS_USAGE=n
CONFIG_FEATURE_COPYBUF_KB=4
CONFIG_CROSS_COMPILER_PREFIX=""
CONFIG_SYSROOT=""
CONFIG_EXTRA_CFLAGS=""
CONFIG_EXTRA_LDFLAGS=""
CONFIG_EXTRA_LDLIBS=""
CONFIG_PREFIX="./_install"
CONFIG

  # BusyBox does not provide the Linux-kernel-style olddefconfig target.
  # KCONFIG_ALLCONFIG with allnoconfig is also not enough on all BusyBox
  # versions: some requested applets may remain disabled silently. Generate a
  # baseline config first, then force the small set of symbols ZDT-D needs and
  # let BusyBox oldconfig resolve dependencies.
  make -C "$SRC_DIR" O="$BUILD_DIR" KCONFIG_ALLCONFIG="$mini" allnoconfig >/dev/null

  python3 - "$BUILD_DIR/.config" <<'PY'
from pathlib import Path
import sys

cfg_path = Path(sys.argv[1])
text = cfg_path.read_text(encoding="utf-8", errors="replace").splitlines()
values = {
    "CONFIG_STATIC": "n",
    "CONFIG_LFS": "y",
    "CONFIG_LONG_OPTS": "y",
    "CONFIG_PIE": "y",
    "CONFIG_SHOW_USAGE": "y",
    "CONFIG_FEATURE_VERBOSE_USAGE": "y",
    "CONFIG_UNZIP": "y",
    "CONFIG_FEATURE_UNZIP_CDF": "n",
    "CONFIG_FEATURE_UNZIP_BZIP2": "n",
    "CONFIG_FEATURE_UNZIP_LZMA": "n",
    "CONFIG_FEATURE_UNZIP_XZ": "n",
    "CONFIG_FEATURE_SEAMLESS_Z": "y",
    "CONFIG_FEATURE_SEAMLESS_GZ": "y",
    "CONFIG_GUNZIP": "y",
    "CONFIG_CAT": "y",
    "CONFIG_CP": "y",
    "CONFIG_CHMOD": "y",
    "CONFIG_MKDIR": "y",
    "CONFIG_RM": "y",
    "CONFIG_ECHO": "y",
    "CONFIG_LS": "y",
    "CONFIG_TRUE": "y",
    "CONFIG_FALSE": "y",
    "CONFIG_TEST": "y",
    "CONFIG_SHA256SUM": "y",
    "CONFIG_FEATURE_MD5_SHA1_SUM_CHECK": "y",
    "CONFIG_FEATURE_BUFFERS_USE_MALLOC": "y",
    "CONFIG_STATIC_LIBGCC": "n",
    "CONFIG_WERROR": "n",
    "CONFIG_FEATURE_SUID": "n",
    "CONFIG_SELINUX": "n",
    "CONFIG_PAM": "n",
    "CONFIG_FEATURE_COMPRESS_USAGE": "n",
    "CONFIG_FEATURE_COPYBUF_KB": "4",
    "CONFIG_CROSS_COMPILER_PREFIX": '""',
    "CONFIG_SYSROOT": '""',
    "CONFIG_EXTRA_CFLAGS": '""',
    "CONFIG_EXTRA_LDFLAGS": '""',
    "CONFIG_EXTRA_LDLIBS": '""',
    "CONFIG_PREFIX": '"./_install"',
}
keys = set(values)
out = []
for line in text:
    key = None
    if line.startswith("CONFIG_") and "=" in line:
        key = line.split("=", 1)[0]
    elif line.startswith("# CONFIG_") and line.endswith(" is not set"):
        key = line[len("# "):].split(" ", 1)[0]
    if key in keys:
        continue
    out.append(line)
for key, value in values.items():
    if value == "n":
        out.append(f"# {key} is not set")
    else:
        out.append(f"{key}={value}")
cfg_path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY

  set +o pipefail
  yes "" 2>/dev/null | make -C "$SRC_DIR" O="$BUILD_DIR" oldconfig >/dev/null
  local oldconfig_rc=${PIPESTATUS[1]}
  set -o pipefail
  [[ "$oldconfig_rc" -eq 0 ]] || fail "BusyBox oldconfig failed"

  grep -q '^CONFIG_UNZIP=y$' "$BUILD_DIR/.config" || fail "BusyBox config did not enable CONFIG_UNZIP"
  grep -q '^# CONFIG_STATIC is not set$' "$BUILD_DIR/.config" || fail "BusyBox config still enables static linking"
}

build_busybox() {
  local ndk_root
  ndk_root="$(find_ndk_root)" || fail "Android NDK not found. Set ANDROID_NDK_ROOT/NDK_ROOT or install Android SDK NDK."
  local tag tc cc ar ranlib strip
  tag="$(host_tag)"
  tc="$ndk_root/toolchains/llvm/prebuilt/$tag/bin"
  cc="$tc/${BUSYBOX_CLANG_PREFIX}${ANDROID_API}-clang"
  ar="$tc/llvm-ar"
  ranlib="$tc/llvm-ranlib"
  strip="$tc/llvm-strip"
  [[ -x "$cc" ]] || fail "Android NDK clang not found: $cc"

  rm -rf "$BUILD_DIR"
  configure_busybox

  msg "building BusyBox $BUSYBOX_VERSION for $ZDT_BUSYBOX_ABI with $cc"
  make -C "$SRC_DIR" O="$BUILD_DIR" \
    CC="$cc" AR="$ar" RANLIB="$ranlib" STRIP="$strip" \
    -j"$JOBS" busybox

  mkdir -p "$(dirname "$OUT_PATH")"
  cp -f "$BUILD_DIR/busybox" "$OUT_PATH"
  "$strip" --strip-all "$OUT_PATH" 2>/dev/null || true
  chmod 755 "$OUT_PATH"

  [[ -s "$OUT_PATH" ]] || fail "BusyBox binary was not created: $OUT_PATH"
  if command -v file >/dev/null 2>&1; then
    file "$OUT_PATH" | grep -qiE "$BUSYBOX_FILE_PATTERN" || fail "BusyBox output is not $ZDT_BUSYBOX_ABI ELF: $(file "$OUT_PATH")"
  fi
  if command -v strings >/dev/null 2>&1; then
    strings "$OUT_PATH" | grep -q "unzip" || fail "Built BusyBox does not appear to contain unzip applet"
  fi

  local digest
  digest="$(sha256_file "$OUT_PATH")"
  cat > "${OUT_PATH}.source" <<SRC
name=BusyBox
version=$BUSYBOX_VERSION
source=$BUSYBOX_URL
sourceSha256=$BUSYBOX_SHA256
license=GPL-2.0-only
androidApi=$ANDROID_API
abi=$ZDT_BUSYBOX_ABI
ndk=$ndk_root
binarySha256=$digest
SRC
  msg "busybox ready: $OUT_PATH"
  msg "busybox sha256: $digest"
}

main() {
  download_source
  extract_source
  build_busybox
}

main "$@"
