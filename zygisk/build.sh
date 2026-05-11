#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_SO="${1:-$ROOT_DIR/out/arm64-v8a.so}"
API_LEVEL="${ANDROID_API_LEVEL:-24}"
SRC="$ROOT_DIR/src/main.cpp"
INCLUDE="$ROOT_DIR/include"
TERMUX_PREFIX_DEFAULT="/data/data/com.termux/files/usr"
TERMUX_PREFIX="${PREFIX:-$TERMUX_PREFIX_DEFAULT}"

mkdir -p "$(dirname "$OUT_SO")"

msg() { printf '[ZDT-D][Zygisk] %s\n' "$*"; }
warn() { printf '[ZDT-D][Zygisk][WARN] %s\n' "$*" >&2; }
fail() { printf '[ZDT-D][Zygisk][ERR] %s\n' "$*" >&2; exit 1; }
cmd_exists() { command -v "$1" >/dev/null 2>&1; }

is_android_host() {
  [[ "$(uname -o 2>/dev/null || true)" == "Android" ]] || \
  [[ -d /system && -d /data/data ]] || \
  [[ "${PREFIX:-}" == /data/data/com.termux/files/usr* ]] || \
  [[ -d /data/data/com.termux/files/usr ]]
}

is_termux_runtime() {
  [[ "${PREFIX:-}" == /data/data/com.termux/files/usr* ]] || [[ -d /data/data/com.termux/files/usr ]]
}

host_arch() {
  uname -m 2>/dev/null || printf 'unknown'
}

ensure_termux_zygisk_prereqs() {
  is_termux_runtime || return 0
  local missing=() cmd
  local required=(clang++)
  for cmd in "${required[@]}"; do
    cmd_exists "$cmd" || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    cmd_exists pkg || fail "Не хватает ${missing[*]}, а команда pkg не найдена. Установи Termux package manager."
    warn "Не хватает зависимостей Zygisk-сборки: ${missing[*]}. Пробую установить clang/binutils/file."
    pkg install -y clang binutils file
    hash -r || true
  fi
  for cmd in "${required[@]}"; do
    cmd_exists "$cmd" || fail "После автоустановки всё ещё не найдена команда: $cmd"
  done
}

find_ndk_clang() {
  local ndk candidates=()
  [[ -n "${ANDROID_NDK_ROOT:-}" ]] && candidates+=("$ANDROID_NDK_ROOT")
  [[ -n "${NDK_ROOT:-}" ]] && candidates+=("$NDK_ROOT")
  [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME:-}/ndk" ]] && candidates+=("$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)")
  [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT:-}/ndk" ]] && candidates+=("$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)")
  for ndk in "${candidates[@]}"; do
    [[ -n "$ndk" && -d "$ndk" ]] || continue
    local clang
    clang="$(find "$ndk/toolchains/llvm/prebuilt" -type f -name "aarch64-linux-android${API_LEVEL}-clang++" 2>/dev/null | head -n 1)"
    [[ -x "$clang" ]] || continue
    # Official NDK prebuilts are host binaries. On Android/Termux an x86_64 host toolchain
    # cannot run on arm64, so never select it there.
    if is_android_host; then
      continue
    fi
    printf '%s' "$clang"
    return 0
  done
  if ! is_android_host && command -v "aarch64-linux-android${API_LEVEL}-clang++" >/dev/null 2>&1; then
    command -v "aarch64-linux-android${API_LEVEL}-clang++"
    return 0
  fi
  return 1
}

COMMON_FLAGS=(
  -fPIC
  -shared
  -std=c++17
  -O2
  -fvisibility=hidden
  -fno-exceptions
  -fno-rtti
  -fno-threadsafe-statics
  -ffunction-sections
  -fdata-sections
  -nostdlib++
  -Wall
  -Wextra
  -I"$INCLUDE"
)

link_with() {
  local compiler="$1"
  shift
  "$compiler" "${COMMON_FLAGS[@]}" "$SRC" -o "$OUT_SO" -Wl,-soname,arm64-v8a.so -Wl,--gc-sections -Wl,--exclude-libs,ALL "$@"
}

if [[ ! -f "$SRC" ]]; then
  fail "Не найден исходник: $SRC"
fi

if [[ -n "${ZDT_ZYGISK_CLANG:-}" ]]; then
  [[ -x "$ZDT_ZYGISK_CLANG" || -n "$(command -v "$ZDT_ZYGISK_CLANG" 2>/dev/null || true)" ]] || fail "ZDT_ZYGISK_CLANG задан, но компилятор не найден: $ZDT_ZYGISK_CLANG"
  msg "Using custom compiler: $ZDT_ZYGISK_CLANG"
  link_with "$ZDT_ZYGISK_CLANG"
elif is_android_host; then
  ensure_termux_zygisk_prereqs
  case "$(host_arch)" in
    aarch64|arm64) ;;
    *) fail "Zygisk arm64 сборка на устройстве поддерживается только на arm64/aarch64 host. Текущий host: $(host_arch)" ;;
  esac
  msg "Using Termux/Android arm64 clang++: $(command -v clang++)"
  link_with "clang++"
elif CLANG="$(find_ndk_clang 2>/dev/null)"; then
  msg "Using Android NDK compiler: $CLANG"
  link_with "$CLANG"
elif command -v clang++ >/dev/null 2>&1; then
  # Lightweight fallback for dev containers without Android NDK. It is useful for syntax/ELF checks,
  # while release/device builds should use Termux arm64 clang++ or the Android NDK path above.
  msg "Using fallback host clang++ cross target"
  clang++ -target "aarch64-linux-android${API_LEVEL}" \
    "${COMMON_FLAGS[@]}" "$SRC" -o "$OUT_SO" \
    -nostdlib -Wl,--allow-shlib-undefined -Wl,-soname,arm64-v8a.so
else
  fail "No clang++/NDK compiler found for Zygisk arm64-v8a.so"
fi

if command -v llvm-strip >/dev/null 2>&1; then
  llvm-strip --strip-unneeded "$OUT_SO" 2>/dev/null || true
elif command -v strip >/dev/null 2>&1; then
  strip --strip-unneeded "$OUT_SO" 2>/dev/null || true
fi

if command -v file >/dev/null 2>&1; then
  file "$OUT_SO"
fi

if command -v readelf >/dev/null 2>&1; then
  readelf -Ws "$OUT_SO" | grep -q ' zygisk_module_entry$' || fail "zygisk_module_entry export not found in $OUT_SO"
  if readelf -d "$OUT_SO" | grep -E 'NEEDED.*(libc\+\+|libstdc\+\+)' >/dev/null 2>&1; then
    fail "Zygisk library must not depend on C++ STL runtime"
  fi
else
  warn "readelf not found; skipping Zygisk export/dependency validation"
fi
