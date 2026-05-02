#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$ROOT_DIR/application"
APP_MODULE_DIR="$APP_DIR/app"
MODULE_TEMPLATE_DIR="$ROOT_DIR/module_template"
RUST_DIR="$ROOT_DIR/rust"
PREBUILT_BIN_DIR="$ROOT_DIR/prebuilt/bin/arm64-v8a"
OUT_DIR="$ROOT_DIR/out"
MODULE_BUILD_DIR="$OUT_DIR/module_build"
MODULE_ROOT_DIR="$MODULE_BUILD_DIR/module_root"
MODULE_ZIP="$OUT_DIR/module/zdt_module.zip"
APK_OUT_DIR="$OUT_DIR/apk"
DIST_DIR="$OUT_DIR/dist"
TOOLS_DIR="$ROOT_DIR/.tools"
DOWNLOADS_DIR="$TOOLS_DIR/downloads"
GRADLE_VERSION="${GRADLE_VERSION:-8.2}"
GRADLE_BASE_DIR="$TOOLS_DIR/gradle"
LOCAL_GRADLE_HOME="$GRADLE_BASE_DIR/gradle-$GRADLE_VERSION"
LOCAL_GRADLE_CMD="$LOCAL_GRADLE_HOME/bin/gradle"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ANDROID_HOME="$ANDROID_SDK_ROOT"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-34}"
ANDROID_BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-34.0.0}"
CMDLINE_TOOLS_REVISION="${CMDLINE_TOOLS_REVISION:-14742923}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_REVISION}_latest.zip"
CMDLINE_TOOLS_URL="${CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}}"
SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
TERMUX_PREFIX_DEFAULT="/data/data/com.termux/files/usr"
TERMUX_PREFIX="${PREFIX:-$TERMUX_PREFIX_DEFAULT}"
AAPT2_TERMUX="$TERMUX_PREFIX/bin/aapt2"
AAPT2_SDK="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/aapt2"
LOCAL_PROPERTIES_FILE="$APP_DIR/local.properties"
MODE="${1:-apk}"
BUILD_TYPE="${BUILD_TYPE:-Debug}"
GRADLE_TASK="assemble${BUILD_TYPE}"
KEYSTORE_DIR="$ROOT_DIR/keystores"
DEBUG_KEYSTORE_PATH="${DEBUG_KEYSTORE_PATH:-$KEYSTORE_DIR/zdt-debug.keystore}"
DEBUG_KEY_ALIAS="androiddebugkey"
DEBUG_KEYSTORE_PASSWORD="android"
DEBUG_KEY_PASSWORD="android"
CARGO_PROFILE="${CARGO_PROFILE:-release}"
TARGET_TRIPLE="${CARGO_BUILD_TARGET:-}"
PROJECT_CARGO_HOME="$TOOLS_DIR/cargo-home"
PROJECT_CARGO_TARGET_DIR="$RUST_DIR/target"
GRADLE_FLAGS=()

AUTO_BUILT_BINS=(zdtd t2s)
REQUIRED_EXTERNAL_BINS=(byedpi dnscrypt dpitunnel-cli nfqws nfqws2 opera-proxy sing-box wireproxy torproxy lyrebird tun2socks openvpn)

RUSTC_BIN=""
CARGO_BIN=""
UI_MODE="dashboard"
[[ "$MODE" == "debug" ]] && UI_MODE="debug"
[[ ! -t 1 ]] && UI_MODE="debug"
[[ "${NO_DASHBOARD:-0}" == "1" ]] && UI_MODE="debug"

DASHBOARD_DIR="$OUT_DIR/.dashboard"
DASHBOARD_CURRENT_FILE="$DASHBOARD_DIR/current.txt"
DASHBOARD_DRAWN=0
DASHBOARD_ACTIVE=0
DASHBOARD_REFRESH_INTERVAL="${DASHBOARD_REFRESH_INTERVAL:-0.25}"
declare -a DASHBOARD_LAST_LINES=()
DASHBOARD_REFRESH_INTERVAL="${DASHBOARD_REFRESH_INTERVAL:-0.25}"
declare -a DASHBOARD_LAST_LINES=()
STAGE_KEYS=(env keystore rustcheck zdtd t2s extbin modulezip assets android apk final)
STAGE_NAMES=(
  "Environment checks"
  "Keystore check"
  "Rust toolchain check"
  "Build zdtd"
  "Build t2s"
  "External binaries"
  "Package module zip"
  "Prepare APK inputs"
  "Android prereqs"
  "Build APK"
  "Final validation"
)

msg() {
  if [[ "$UI_MODE" == "debug" ]]; then
    printf '[ZDT-D] %s\n' "$*"
  fi
}
warn() { printf '[ZDT-D][WARN] %s\n' "$*" >&2; }
fail() { printf '[ZDT-D][ERR] %s\n' "$*" >&2; dashboard_restore; exit 1; }
cmd_exists() { command -v "$1" >/dev/null 2>&1; }
need_cmd() { cmd_exists "$1" || fail "Команда не найдена: $1"; }

is_termux_runtime() {
  [[ -d /data/data/com.termux/files/usr ]] || [[ "${PREFIX:-}" == /data/data/com.termux/files/usr* ]]
}

detect_cpu_count() {
  if command -v nproc >/dev/null 2>&1; then
    nproc
  else
    getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1'
  fi
}

detect_gradle_workers() {
  if [[ -n "${GRADLE_MAX_WORKERS:-}" ]]; then
    printf '%s' "$GRADLE_MAX_WORKERS"
    return 0
  fi
  local cpus
  cpus="$(detect_cpu_count)"
  if [[ "$cpus" =~ ^[0-9]+$ ]] && (( cpus > 1 )); then
    printf '%s' "$cpus"
  else
    printf '1'
  fi
}

detect_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -d "${JAVA_HOME:-}" ]]; then
    printf '%s' "$JAVA_HOME"
    return 0
  fi
  local termux_jdk="$TERMUX_PREFIX/lib/jvm/java-17-openjdk"
  if [[ -x "$termux_jdk/bin/java" ]]; then
    printf '%s' "$termux_jdk"
    return 0
  fi
  if command -v javac >/dev/null 2>&1; then
    local javac_path real_javac
    javac_path="$(command -v javac)"
    real_javac="$(readlink -f "$javac_path" 2>/dev/null || printf '%s' "$javac_path")"
    dirname "$(dirname "$real_javac")"
    return 0
  fi
  if command -v java >/dev/null 2>&1; then
    local java_path real_java
    java_path="$(command -v java)"
    real_java="$(readlink -f "$java_path" 2>/dev/null || printf '%s' "$java_path")"
    dirname "$(dirname "$real_java")"
    return 0
  fi
  return 1
}

ensure_java_home() {
  local java_home=""
  if ! java_home="$(detect_java_home 2>/dev/null)"; then
    if is_termux_runtime && cmd_exists pkg; then
      msg 'Устанавливаю OpenJDK 17 для Android SDK / Gradle'
      pkg install -y openjdk-17
      hash -r || true
      java_home="$(detect_java_home 2>/dev/null || true)"
    fi
  fi
  [[ -n "$java_home" ]] || fail 'Не найден JDK. Установи openjdk-17 или задай JAVA_HOME.'
  export JAVA_HOME="$java_home"
  case ":$PATH:" in
    *":$JAVA_HOME/bin:"*) ;;
    *) export PATH="$JAVA_HOME/bin:$PATH" ;;
  esac
  [[ -x "$JAVA_HOME/bin/java" ]] || fail "Некорректный JAVA_HOME: $JAVA_HOME"
  hash -r || true
}

set_rust_toolchain_paths() {
  if is_termux_runtime; then
    [[ -x "$TERMUX_PREFIX/bin/rustc" ]] || fail "Не найден Termux rustc: $TERMUX_PREFIX/bin/rustc"
    [[ -x "$TERMUX_PREFIX/bin/cargo" ]] || fail "Не найден Termux cargo: $TERMUX_PREFIX/bin/cargo"
    RUSTC_BIN="$TERMUX_PREFIX/bin/rustc"
    CARGO_BIN="$TERMUX_PREFIX/bin/cargo"
  else
    RUSTC_BIN="$(command -v rustc 2>/dev/null || true)"
    CARGO_BIN="$(command -v cargo 2>/dev/null || true)"
    [[ -n "$RUSTC_BIN" && -n "$CARGO_BIN" ]] || fail 'rustc/cargo не найдены'
  fi
}

host_target() {
  if [[ -n "$RUSTC_BIN" ]]; then
    rustc_exec -vV 2>/dev/null | awk '/^host: /{print $2}'
  else
    rustc -vV 2>/dev/null | awk '/^host: /{print $2}'
  fi
}

resolve_target() {
  if [[ -n "$TARGET_TRIPLE" ]]; then
    printf '%s' "$TARGET_TRIPLE"
    return 0
  fi
  local host
  host="$(host_target || true)"
  if [[ "$host" == "aarch64-linux-android" ]]; then
    printf '%s' "$host"
  else
    printf '%s' 'aarch64-linux-android'
  fi
}

cargo_out_dir() {
  local triple="$1"
  local profile="$2"
  local host
  host="$(host_target || true)"
  if [[ "$triple" == "$host" ]]; then
    printf '%s' "$PROJECT_CARGO_TARGET_DIR/$profile"
  else
    printf '%s' "$PROJECT_CARGO_TARGET_DIR/$triple/$profile"
  fi
}

sanitize_rust_env_vars() {
  unset RUSTUP_HOME || true
  unset RUSTUP_TOOLCHAIN || true
  unset RUSTFLAGS || true
  unset CARGO_BUILD_TARGET || true
  unset CARGO_TARGET_DIR || true
  unset CARGO_ENCODED_RUSTFLAGS || true
  unset RUSTC_WRAPPER || true
  unset CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS || true
  unset CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_RUSTFLAGS || true
  unset CARGO_TARGET_I686_LINUX_ANDROID_RUSTFLAGS || true
  unset CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS || true
}

cargo_exec() {
  sanitize_rust_env_vars
  mkdir -p "$PROJECT_CARGO_HOME" "$PROJECT_CARGO_TARGET_DIR"
  env \
    PATH="$TERMUX_PREFIX/bin:${PATH}" \
    CARGO_HOME="$PROJECT_CARGO_HOME" \
    CARGO_TARGET_DIR="$PROJECT_CARGO_TARGET_DIR" \
    RUSTC="$RUSTC_BIN" \
    "$CARGO_BIN" "$@"
}

rustc_exec() {
  sanitize_rust_env_vars
  env PATH="$TERMUX_PREFIX/bin:${PATH}" "$RUSTC_BIN" "$@"
}

find_gradle_cmd() {
  if [[ -x "$APP_DIR/gradlew" ]]; then
    printf '%s' "$APP_DIR/gradlew"
  elif [[ -x "$LOCAL_GRADLE_CMD" ]]; then
    printf '%s' "$LOCAL_GRADLE_CMD"
  elif command -v gradle >/dev/null 2>&1; then
    printf '%s' 'gradle'
  else
    return 1
  fi
}

find_downloader() {
  if command -v curl >/dev/null 2>&1; then
    printf '%s' 'curl'
  elif command -v wget >/dev/null 2>&1; then
    printf '%s' 'wget'
  else
    return 1
  fi
}

download_file() {
  local url="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  local dl
  dl="$(find_downloader)" || fail 'Нужен curl или wget для скачивания зависимостей.'
  if [[ "$dl" == 'curl' ]]; then
    curl -L --fail --retry 3 -o "$out" "$url"
  else
    wget -O "$out" "$url"
  fi
}

escape_for_local_properties() {
  printf '%s' "$1" | sed 's#\\#\\\\#g'
}

read_local_properties_sdk_dir() {
  if [[ -f "$LOCAL_PROPERTIES_FILE" ]]; then
    sed -n 's/^sdk\.dir=//p' "$LOCAL_PROPERTIES_FILE" | tail -n 1 | sed 's#\\\\#\\#g'
  fi
}

is_valid_android_sdk_root() {
  local root="$1"
  [[ -n "$root" ]] || return 1
  [[ -d "$root" ]] || return 1
  [[ -f "$root/platforms/android-$ANDROID_API_LEVEL/android.jar" ]] || return 1
  [[ -d "$root/build-tools/$ANDROID_BUILD_TOOLS_VERSION" ]] || return 1
}

auto_detect_android_sdk_root() {
  local candidates=()
  [[ -n "${ANDROID_SDK_ROOT:-}" ]] && candidates+=("$ANDROID_SDK_ROOT")
  [[ -n "${ANDROID_HOME:-}" ]] && candidates+=("$ANDROID_HOME")
  local lp
  lp="$(read_local_properties_sdk_dir 2>/dev/null || true)"
  [[ -n "$lp" ]] && candidates+=("$lp")
  candidates+=(
    "$HOME/Android/Sdk"
    "/data/data/com.termux/files/home/Android/Sdk"
    "$TERMUX_PREFIX/opt/android-sdk"
    "$TERMUX_PREFIX/share/android-sdk"
  )
  local cand
  for cand in "${candidates[@]}"; do
    if is_valid_android_sdk_root "$cand"; then
      printf '%s' "$cand"
      return 0
    fi
  done
  return 1
}

ensure_android_sdk_ready() {
  local detected=''
  detected="$(auto_detect_android_sdk_root 2>/dev/null || true)"
  if [[ -n "$detected" ]]; then
    ANDROID_SDK_ROOT="$detected"
    ANDROID_HOME="$detected"
    SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
    AAPT2_SDK="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/aapt2"
    return 0
  fi
  warn "Android SDK API $ANDROID_API_LEVEL / Build-Tools $ANDROID_BUILD_TOOLS_VERSION не найдены. Пробую установить автоматически."
  install_android_sdk_packages
  detected="$(auto_detect_android_sdk_root 2>/dev/null || true)"
  if [[ -n "$detected" ]]; then
    ANDROID_SDK_ROOT="$detected"
    ANDROID_HOME="$detected"
    SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
    AAPT2_SDK="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/aapt2"
    return 0
  fi
  fail "Android SDK всё ещё не готов. Ожидаю android.jar в $ANDROID_SDK_ROOT/platforms/android-$ANDROID_API_LEVEL/"
}

ensure_gradle_ready() {
  if find_gradle_cmd >/dev/null 2>&1; then
    return 0
  fi
  warn 'Gradle/gradlew не найдены. Пробую установить локальный Gradle автоматически.'
  install_gradle_local
  find_gradle_cmd >/dev/null 2>&1 || fail 'Не удалось подготовить gradle/gradlew.'
}

ensure_termux_build_prereqs() {
  is_termux_runtime || return 0
  local missing=()
  local required=(javac keytool zip unzip make git find curl rustc cargo aapt2 pkg-config file)
  local cmd
  for cmd in "${required[@]}"; do
    cmd_exists "$cmd" || missing+=("$cmd")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    warn "В Termux не хватает зависимостей для автосборки: ${missing[*]}. Пробую установить автоматически."
    install_termux_packages
  fi
  for cmd in "${required[@]}"; do
    cmd_exists "$cmd" || fail "После автоустановки в Termux всё ещё не найдена команда: $cmd"
  done
  set_rust_toolchain_paths
  if [[ "$RUSTC_BIN" != "$TERMUX_PREFIX/bin/rustc" || "$CARGO_BIN" != "$TERMUX_PREFIX/bin/cargo" ]]; then
    fail "В Termux должна использоваться пакетная связка rust/cargo из $TERMUX_PREFIX/bin. Сейчас: rustc=$RUSTC_BIN cargo=$CARGO_BIN"
  fi
}

select_aapt2_override() {
  if [[ -n "${AAPT2_OVERRIDE:-}" ]]; then
    [[ -x "$AAPT2_OVERRIDE" ]] || fail "AAPT2_OVERRIDE задан, но файл не исполняемый: $AAPT2_OVERRIDE"
    printf '%s' "$AAPT2_OVERRIDE"
    return 0
  fi
  if is_termux_runtime; then
    [[ -x "$AAPT2_TERMUX" ]] || fail "В Termux нужен пакет aapt2: ожидается $AAPT2_TERMUX"
    printf '%s' "$AAPT2_TERMUX"
    return 0
  fi
  if [[ -x "$AAPT2_TERMUX" ]]; then
    printf '%s' "$AAPT2_TERMUX"
    return 0
  fi
  if [[ -x "$AAPT2_SDK" ]]; then
    printf '%s' "$AAPT2_SDK"
    return 0
  fi
  return 1
}

write_local_properties() {
  mkdir -p "$APP_DIR"
  local sdk_escaped
  sdk_escaped="$(escape_for_local_properties "$ANDROID_SDK_ROOT")"
  cat > "$LOCAL_PROPERTIES_FILE" <<PROP
sdk.dir=$sdk_escaped
PROP
  if [[ -n "${NDK_ROOT:-}" && -d "${NDK_ROOT:-}" ]]; then
    printf 'ndk.dir=%s\n' "$(escape_for_local_properties "$NDK_ROOT")" >> "$LOCAL_PROPERTIES_FILE"
  fi
  msg "Обновлён $LOCAL_PROPERTIES_FILE -> $ANDROID_SDK_ROOT"
}

ensure_debug_keystore() {
  if [[ -f "$DEBUG_KEYSTORE_PATH" ]]; then
    return 0
  fi
  need_cmd keytool
  mkdir -p "$(dirname "$DEBUG_KEYSTORE_PATH")"
  msg "Создаю базовый debug keystore: $DEBUG_KEYSTORE_PATH"
  keytool -genkeypair \
    -keystore "$DEBUG_KEYSTORE_PATH" \
    -storepass "$DEBUG_KEYSTORE_PASSWORD" \
    -keypass "$DEBUG_KEY_PASSWORD" \
    -alias "$DEBUG_KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" \
    >/dev/null 2>&1
  [[ -f "$DEBUG_KEYSTORE_PATH" ]] || fail "Не удалось создать keystore: $DEBUG_KEYSTORE_PATH"
}

write_signing_properties() {
  mkdir -p "$APP_DIR"
  cat > "$APP_DIR/keystore.properties" <<PROP
storeFile=$DEBUG_KEYSTORE_PATH
storePassword=$DEBUG_KEYSTORE_PASSWORD
keyAlias=$DEBUG_KEY_ALIAS
keyPassword=$DEBUG_KEY_PASSWORD
PROP
  msg "Обновлён $APP_DIR/keystore.properties -> $DEBUG_KEYSTORE_PATH"
}

rust_stdlib_rlibs_ok() {
  local libdir
  libdir="$(rustc_exec --print target-libdir 2>/dev/null || true)"
  [[ -d "$libdir" ]] || return 1
  compgen -G "$libdir/libstd-*.rlib" >/dev/null || return 1
  compgen -G "$libdir/libcore-*.rlib" >/dev/null || return 1
  compgen -G "$libdir/liballoc-*.rlib" >/dev/null || return 1
}

ensure_rust_stdlib_ready() {
  set_rust_toolchain_paths
  if rust_stdlib_rlibs_ok; then
    return 0
  fi
  if is_termux_runtime; then
    warn 'Termux Rust stdlib выглядит повреждённой или несовместимой. Пробую pkg reinstall rust.'
    pkg reinstall -y rust || true
    set_rust_toolchain_paths
    rust_stdlib_rlibs_ok || fail 'Termux Rust stdlib по-прежнему не содержит требуемые .rlib (std/core/alloc). Удали rustup/переменные среды и повтори.'
  else
    fail 'Rust stdlib не содержит требуемые .rlib (std/core/alloc).'
  fi
}

doctor_rust() {
  set_rust_toolchain_paths
  printf '  [ok] rustc -> %s\n' "$RUSTC_BIN"
  printf '  [ok] cargo -> %s\n' "$CARGO_BIN"
  rustc_exec -vV 2>/dev/null | sed 's/^/  [rustc] /'
  cargo_exec -V 2>/dev/null | sed 's/^/  [cargo] /'
  if [[ -d "$HOME/.rustup" || -x "$HOME/.cargo/bin/rustc" || -x "$HOME/.cargo/bin/cargo" ]]; then
    printf '  [..] detected rustup traces in ~/.cargo or ~/.rustup\n'
  fi
  local bad_env=0 name
  for name in RUSTUP_HOME RUSTUP_TOOLCHAIN RUSTFLAGS CARGO_BUILD_TARGET CARGO_TARGET_DIR CARGO_ENCODED_RUSTFLAGS RUSTC_WRAPPER; do
    if [[ -n "${!name-}" ]]; then
      printf '  [..] env override set -> %s=%s\n' "$name" "${!name}"
      bad_env=1
    fi
  done
  if rust_stdlib_rlibs_ok; then
    printf '  [ok] rust stdlib rlib set is present\n'
  else
    printf '  [!!] rust stdlib rlib set is missing\n'
    bad_env=1
  fi
  return $bad_env
}

doctor() {
  local triple gradle_cmd missing=0 java_home=""
  triple="$(resolve_target)"
  printf '[ZDT-D] Проверка окружения\n'
  local cmd
  for cmd in zip find unzip keytool curl; do
    if command -v "$cmd" >/dev/null 2>&1; then
      printf '  [ok] %s -> %s\n' "$cmd" "$(command -v "$cmd")"
    else
      printf '  [!!] %s not found\n' "$cmd"
      missing=1
    fi
  done
  set_rust_toolchain_paths || missing=1
  doctor_rust || true
  if java_home="$(detect_java_home 2>/dev/null)"; then
    printf '  [ok] java home -> %s\n' "$java_home"
  else
    printf '  [!!] java/javac not found\n'
    missing=1
  fi
  if gradle_cmd="$(find_gradle_cmd 2>/dev/null)"; then
    printf '  [ok] gradle -> %s\n' "$gradle_cmd"
  else
    printf '  [..] gradle/gradlew not found yet\n'
  fi
  printf '  [ok] rust target requested -> %s\n' "$triple"
  printf '  [ok] android sdk root -> %s\n' "$ANDROID_SDK_ROOT"
  if [[ -x "$SDKMANAGER_BIN" ]]; then
    printf '  [ok] sdkmanager -> %s\n' "$SDKMANAGER_BIN"
  else
    printf '  [..] sdkmanager not found yet -> %s\n' "$SDKMANAGER_BIN"
  fi
  if [[ -d "$ANDROID_SDK_ROOT/platforms/android-$ANDROID_API_LEVEL" ]]; then
    printf '  [ok] platforms;android-%s\n' "$ANDROID_API_LEVEL"
  else
    printf '  [..] missing platforms;android-%s\n' "$ANDROID_API_LEVEL"
  fi
  if [[ -d "$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION" ]]; then
    printf '  [ok] build-tools;%s\n' "$ANDROID_BUILD_TOOLS_VERSION"
  else
    printf '  [..] missing build-tools;%s\n' "$ANDROID_BUILD_TOOLS_VERSION"
  fi
  local aapt2_path=''
  if aapt2_path="$(select_aapt2_override 2>/dev/null)"; then
    printf '  [ok] aapt2 -> %s\n' "$aapt2_path"
  else
    printf '  [..] aapt2 override not found\n'
  fi
  mkdir -p "$PREBUILT_BIN_DIR"
  local ext_missing=0 bin
  for bin in "${REQUIRED_EXTERNAL_BINS[@]}"; do
    if [[ -f "$PREBUILT_BIN_DIR/$bin" ]]; then
      printf '  [ok] external bin -> %s\n' "$bin"
    else
      printf '  [..] missing external bin -> %s\n' "$bin"
      ext_missing=1
    fi
  done
  [[ "$ext_missing" -eq 1 ]] && warn 'Для полной сборки module/apk доложи все внешние бинарники в prebuilt/bin/arm64-v8a/'
  [[ "$missing" -eq 0 ]] || fail 'Окружение неполное. Исправь пункты [!!] выше или выполни ./build.sh setup-all'
}

install_termux_packages() {
  cmd_exists pkg || fail 'Этот режим рассчитан на Termux: команда pkg не найдена.'
  msg 'Устанавливаю/дополняю пакеты Termux: openjdk-17, rust, clang, unzip, zip, curl, wget, make, git, aapt2, binutils, pkg-config, file'
  pkg install -y openjdk-17 rust clang unzip zip curl wget make git aapt2 binutils pkg-config file
  hash -r || true
  ensure_java_home
}


install_gradle_local() {
  need_cmd unzip
  mkdir -p "$GRADLE_BASE_DIR" "$DOWNLOADS_DIR"
  local requested_version="$GRADLE_VERSION"
  local resolved_version="$requested_version"
  if [[ "$requested_version" == "8.2.2" ]]; then
    warn 'Gradle 8.2.2 не существует как дистрибутив; использую Gradle 8.2 для AGP 8.2.x.'
    resolved_version='8.2'
  fi
  local resolved_home="$GRADLE_BASE_DIR/gradle-$resolved_version"
  local resolved_cmd="$resolved_home/bin/gradle"
  if [[ -x "$resolved_cmd" ]]; then
    msg "Gradle $resolved_version уже установлен: $resolved_cmd"
    return 0
  fi
  local zip_path="$DOWNLOADS_DIR/gradle-$resolved_version-bin.zip"
  msg "Скачиваю Gradle $resolved_version"
  download_file "https://services.gradle.org/distributions/gradle-$resolved_version-bin.zip" "$zip_path"
  rm -rf "$resolved_home"
  unzip -q -o "$zip_path" -d "$GRADLE_BASE_DIR"
  [[ -x "$resolved_cmd" ]] || fail "Gradle не установлен: $resolved_cmd"
}

install_android_cmdline_tools() {
  need_cmd unzip
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" "$DOWNLOADS_DIR"
  if [[ -x "$SDKMANAGER_BIN" ]]; then
    msg "Android cmdline-tools уже установлены: $SDKMANAGER_BIN"
    return 0
  fi
  local zip_path="$DOWNLOADS_DIR/$CMDLINE_TOOLS_ZIP"
  local tmp_extract="$DOWNLOADS_DIR/cmdline-tools-extract"
  msg "Скачиваю Android cmdline-tools: $CMDLINE_TOOLS_ZIP"
  download_file "$CMDLINE_TOOLS_URL" "$zip_path"
  rm -rf "$tmp_extract" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$tmp_extract" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  unzip -q -o "$zip_path" -d "$tmp_extract"
  cp -a "$tmp_extract/cmdline-tools/." "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
  [[ -x "$SDKMANAGER_BIN" ]] || fail "sdkmanager не найден после установки: $SDKMANAGER_BIN"
}

install_android_sdk_packages() {
  ensure_java_home
  install_android_cmdline_tools
  mkdir -p "$HOME/.android"
  : > "$HOME/.android/repositories.cfg"
  msg 'Принимаю лицензии Android SDK'
  yes | JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
  msg "Устанавливаю Android SDK пакеты: platform-tools, platforms;android-$ANDROID_API_LEVEL, build-tools;$ANDROID_BUILD_TOOLS_VERSION"
  JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" \
  "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-$ANDROID_API_LEVEL" \
    "build-tools;$ANDROID_BUILD_TOOLS_VERSION"
}

patch_paths() {
  ensure_android_sdk_ready
  write_local_properties
}

setup_all() {
  install_termux_packages
  install_gradle_local
  install_android_sdk_packages
  patch_paths
  ensure_rust_stdlib_ready
}

stage_file() { printf '%s/%s.state' "$DASHBOARD_DIR" "$1"; }

clip_text() {
  local width="$1" text="$2"
  if (( ${#text} > width )); then
    printf '%s' "${text:0:width-3}..."
  else
    printf '%s' "$text"
  fi
}

format_elapsed() {
  local total="$1"
  local m=$(( total / 60 ))
  local s=$(( total % 60 ))
  printf '%02d:%02d' "$m" "$s"
}

dashboard_row_for_stage() {
  local idx="$1"
  printf '%d' "$((4 + idx))"
}

dashboard_footer_border_row() {
  printf '%d' "$((4 + ${#STAGE_KEYS[@]}))"
}

dashboard_overall_row() {
  printf '%d' "$(( $(dashboard_footer_border_row) + 2 ))"
}

dashboard_current_row() {
  printf '%d' "$(( $(dashboard_footer_border_row) + 3 ))"
}

dashboard_put_line() {
  local row="$1" text="$2"
  local idx=$((row - 1))
  local prev="${DASHBOARD_LAST_LINES[$idx]-}"
  if [[ "$prev" != "$text" ]]; then
    printf '[%d;1H%s[K' "$row" "$text"
    DASHBOARD_LAST_LINES[$idx]="$text"
  fi
}

dashboard_draw_static_frame() {
  local row border_row overall_row current_row i
  printf '[?25l[2J[H'
  dashboard_put_line 1 '+----+------------------------------+--------+----------+--------+'
  dashboard_put_line 2 '| ID | Stage                        | Status | Progress | Time   |'
  dashboard_put_line 3 '+----+------------------------------+--------+----------+--------+'
  for i in "${!STAGE_KEYS[@]}"; do
    row="$(dashboard_row_for_stage "$i")"
    dashboard_put_line "$row" "| $(printf '%02d' "$((i + 1))") | $(printf '%-28.28s' "${STAGE_NAMES[$i]}") | WAIT   | 000%     | --:--  |"
  done
  border_row="$(dashboard_footer_border_row)"
  overall_row="$(dashboard_overall_row)"
  current_row="$(dashboard_current_row)"
  dashboard_put_line "$border_row" '+----+------------------------------+--------+----------+--------+'
  dashboard_put_line "$((border_row + 1))" ''
  dashboard_put_line "$overall_row" 'Overall : [----------------------------------------] 000%'
  dashboard_put_line "$current_row" 'Current : Idle'
  printf '[%d;1H' "$((current_row + 1))"
}

dashboard_init() {
  [[ "$UI_MODE" == "dashboard" ]] || return 0
  mkdir -p "$DASHBOARD_DIR"
  local i key
  DASHBOARD_LAST_LINES=()
  for i in "${!STAGE_KEYS[@]}"; do
    key="${STAGE_KEYS[$i]}"
    printf 'WAIT|000|0|0
' > "$(stage_file "$key")"
  done
  printf 'Idle
' > "$DASHBOARD_CURRENT_FILE"
  DASHBOARD_ACTIVE=1
  dashboard_draw_static_frame
  DASHBOARD_DRAWN=1
}

dashboard_restore() {
  if [[ "$DASHBOARD_ACTIVE" -eq 1 && "$UI_MODE" == "dashboard" ]]; then
    render_dashboard 1
    printf '[%d;1H[?25h
' "$(( $(dashboard_current_row) + 1 ))"
    DASHBOARD_ACTIVE=0
  fi
}

dashboard_cleanup() {
  rm -rf "$DASHBOARD_DIR"
}

trap 'dashboard_restore; dashboard_cleanup' EXIT

set_dashboard_current() {
  [[ "$UI_MODE" == "dashboard" ]] || return 0
  mkdir -p "$DASHBOARD_DIR"
  printf '%s\n' "$1" > "$DASHBOARD_CURRENT_FILE"
}

stage_update() {
  local key="$1" status="$2" progress="$3"
  local file old_status old_progress old_start old_final start final
  file="$(stage_file "$key")"
  old_status='WAIT'; old_progress='000'; old_start='0'; old_final='0'
  if [[ -f "$file" ]]; then
    IFS='|' read -r old_status old_progress old_start old_final < "$file"
  fi
  start="${4:-$old_start}"
  final="${5:-$old_final}"
  if [[ "$status" == 'RUN' && ( -z "$start" || "$start" == '0' ) ]]; then
    start="$(date +%s)"
    final='0'
  fi
  if [[ "$status" == 'WAIT' ]]; then
    start='0'; final='0'
  fi
  printf '%s|%03d|%s|%s\n' "$status" "$progress" "$start" "$final" > "$file"
}

stage_begin() {
  local key="$1" text="$2"
  stage_update "$key" RUN 0 "$(date +%s)" 0
  set_dashboard_current "$text"
  render_dashboard
}

stage_complete() {
  local key="$1" status="$2"
  local file old_status old_progress old_start old_final now elapsed
  file="$(stage_file "$key")"
  IFS='|' read -r old_status old_progress old_start old_final < "$file"
  now="$(date +%s)"
  elapsed=0
  if [[ "$old_start" =~ ^[0-9]+$ ]] && (( old_start > 0 )); then
    elapsed=$(( now - old_start ))
  fi
  if [[ "$status" == 'DONE' ]]; then
    stage_update "$key" DONE 100 "$old_start" "$elapsed"
  else
    stage_update "$key" FAIL "$old_progress" "$old_start" "$elapsed"
  fi
  render_dashboard
}

stage_done() { stage_complete "$1" DONE; }
stage_fail() { stage_complete "$1" FAIL; }

render_dashboard() {
  [[ "$UI_MODE" == "dashboard" ]] || return 0
  local force="${1:-0}"
  local now overall_sum total row key name status progress start final elapsed time_str current_text line
  now="$(date +%s)"
  overall_sum=0
  total=${#STAGE_KEYS[@]}
  if [[ "$force" == "1" ]]; then
    DASHBOARD_LAST_LINES=()
    dashboard_draw_static_frame
  fi
  for row in "${!STAGE_KEYS[@]}"; do
    key="${STAGE_KEYS[$row]}"
    name="${STAGE_NAMES[$row]}"
    IFS='|' read -r status progress start final < "$(stage_file "$key")"
    elapsed=0
    if [[ "$status" == 'RUN' && "$start" =~ ^[0-9]+$ && "$start" != '0' ]]; then
      elapsed=$(( now - start ))
    elif [[ "$final" =~ ^[0-9]+$ ]]; then
      elapsed="$final"
    fi
    time_str="$(format_elapsed "$elapsed")"
    overall_sum=$(( overall_sum + 10#$progress ))
    line=$(printf '| %02d | %-28.28s | %-6s | %03d%%     | %5s  |' "$((row + 1))" "$name" "$status" "$((10#$progress))" "$time_str")
    dashboard_put_line "$(dashboard_row_for_stage "$row")" "$line"
  done
  local overall=$(( overall_sum / total ))
  local filled=$(( overall * 40 / 100 ))
  local empty=$(( 40 - filled ))
  local bar_filled bar_empty
  bar_filled="$(printf '%*s' "$filled" '' | tr ' ' '#')"
  bar_empty="$(printf '%*s' "$empty" '' | tr ' ' '-')"
  current_text='Idle'
  [[ -f "$DASHBOARD_CURRENT_FILE" ]] && current_text="$(<"$DASHBOARD_CURRENT_FILE")"
  current_text="$(clip_text 60 "$current_text")"
  dashboard_put_line "$(dashboard_overall_row)" "Overall : [${bar_filled}${bar_empty}] $(printf '%03d' "$overall")%"
  dashboard_put_line "$(dashboard_current_row)" "Current : $(printf '%-60.60s' "$current_text")"
  printf '[%d;1H' "$(( $(dashboard_current_row) + 1 ))"
}

print_stage_failure_tail() {
  local title="$1" log_file="$2"
  [[ "$UI_MODE" == "debug" ]] && return 0
  [[ -f "$log_file" ]] || return 0
  printf '\n[ZDT-D][ERR] %s failed. Last output:\n' "$title" >&2
  tail -n 40 "$log_file" >&2 || true
}

estimate_cargo_total_units() {
  local crate_dir="$1" count total
  pushd "$crate_dir" >/dev/null
  count="$(cargo_exec metadata --format-version 1 2>/dev/null | grep -o '"id"[[:space:]]*:' | wc -l | tr -d ' ')"
  popd >/dev/null
  [[ "$count" =~ ^[0-9]+$ ]] || count=0
  total=$(( count / 2 ))
  if (( total < 8 )); then
    total=8
  fi
  printf '%s' "$total"
}

estimate_gradle_tasks() {
  local gradle_cmd="$1" java_home="$2" workers="$3" aapt2_override="$4" count
  pushd "$APP_DIR" >/dev/null
  if [[ -n "$aapt2_override" ]]; then
    count="$(JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" "$gradle_cmd" --console=plain --quiet -m "--max-workers=$workers" "-Pandroid.aapt2FromMavenOverride=$aapt2_override" "$GRADLE_TASK" 2>/dev/null | grep -c '^:app:' || true)"
  else
    count="$(JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" "$gradle_cmd" --console=plain --quiet -m "--max-workers=$workers" "$GRADLE_TASK" 2>/dev/null | grep -c '^:app:' || true)"
  fi
  popd >/dev/null
  [[ "$count" =~ ^[0-9]+$ ]] || count=0
  (( count < 1 )) && count=25
  printf '%s' "$count"
}

run_simple_stage() {
  local key="$1" title="$2"
  shift 2
  if [[ "$UI_MODE" == "debug" ]]; then
    msg "$title"
    "$@"
    return 0
  fi
  stage_begin "$key" "$title"
  if "$@"; then
    stage_done "$key"
    return 0
  else
    stage_fail "$key"
    return 1
  fi
}

run_cargo_stage() {
  local key="$1" title="$2" crate_dir="$3" bin_name="$4" triple="$5"
  local log_file fifo cmd_status=0 parser_pid=0 runner_pid=0 host attempt max_attempts src_bin
  max_attempts=2
  host="$(host_target || true)"

  if [[ "$UI_MODE" == "debug" ]]; then
    for attempt in $(seq 1 "$max_attempts"); do
      if (( attempt == 1 )); then
        msg "$title"
      else
        msg "$title (retry $attempt/$max_attempts)"
      fi
      pushd "$crate_dir" >/dev/null
      if [[ "$triple" == "$host" ]]; then
        cargo_exec build --profile "$CARGO_PROFILE"
      else
        cargo_exec build --profile "$CARGO_PROFILE" --target "$triple"
      fi
      cmd_status=$?
      popd >/dev/null
      if (( cmd_status == 0 )); then
        break
      fi
      if (( attempt < max_attempts )); then
        msg "$title failed, retrying ($((attempt + 1))/$max_attempts)"
        sleep 1
      else
        return "$cmd_status"
      fi
    done
  else
    mkdir -p "$OUT_DIR"
    stage_begin "$key" "$title"
    for attempt in $(seq 1 "$max_attempts"); do
      log_file="$(mktemp "$OUT_DIR/.${key}.XXXX.log")"
      fifo="$(mktemp -u "$OUT_DIR/.${key}.XXXX.fifo")"
      mkfifo "$fifo"
      if (( attempt == 1 )); then
        set_dashboard_current "$title"
      else
        set_dashboard_current "$title (retry $attempt/$max_attempts)"
      fi
      (
        set +e
        pushd "$crate_dir" >/dev/null
        if [[ "$triple" == "$host" ]]; then
          cargo_exec build --profile "$CARGO_PROFILE" >"$fifo" 2>&1
        else
          cargo_exec build --profile "$CARGO_PROFILE" --target "$triple" >"$fifo" 2>&1
        fi
        cmd_status=$?
        popd >/dev/null
        exit "$cmd_status"
      ) &
      runner_pid=$!
      (
        local line trimmed
        while IFS= read -r line; do
          printf '%s
' "$line" >> "$log_file"
          trimmed="${line#"${line%%[![:space:]]*}"}"
          if [[ "$trimmed" == Finished* ]]; then
            set_dashboard_current "$title (finishing)"
          elif (( attempt > 1 )); then
            set_dashboard_current "$title (retry $attempt/$max_attempts)"
          else
            set_dashboard_current "$title"
          fi
        done < <(tr '
' '
' < "$fifo")
      ) &
      parser_pid=$!
      while kill -0 "$runner_pid" 2>/dev/null; do
        render_dashboard
        sleep "$DASHBOARD_REFRESH_INTERVAL"
      done
      cmd_status=0
      wait "$runner_pid" || cmd_status=$?
      wait "$parser_pid" || true
      rm -f "$fifo"
      if (( cmd_status == 0 )); then
        rm -f "$log_file"
        stage_done "$key"
        break
      fi
      if (( attempt < max_attempts )); then
        set_dashboard_current "$title retrying ($((attempt + 1))/$max_attempts)"
        render_dashboard
        rm -f "$log_file"
        sleep 1
      else
        stage_fail "$key"
        print_stage_failure_tail "$title" "$log_file"
        rm -f "$log_file"
        return "$cmd_status"
      fi
    done
  fi

  src_bin="$(cargo_out_dir "$triple" "$CARGO_PROFILE")/$bin_name"
  [[ -f "$src_bin" ]] || fail "Не найден собранный бинарник: $src_bin"
  mkdir -p "$MODULE_ROOT_DIR/bin"
  cp -f "$src_bin" "$MODULE_ROOT_DIR/bin/$bin_name"
  chmod 755 "$MODULE_ROOT_DIR/bin/$bin_name"
}

run_gradle_stage() {
  local key="$1" title="$2" gradle_cmd="$3" java_home="$4" workers="$5" aapt2_override="$6"
  local total progress tasks_done log_file fifo cmd_status=0 parser_pid=0 runner_pid=0
  total="$(estimate_gradle_tasks "$gradle_cmd" "$java_home" "$workers" "$aapt2_override")"

  if [[ "$UI_MODE" == "debug" ]]; then
    msg "$title"
    pushd "$APP_DIR" >/dev/null
    if [[ -n "$aapt2_override" ]]; then
      JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
        "$gradle_cmd" "${GRADLE_FLAGS[@]}" "--max-workers=$workers" "-Pandroid.aapt2FromMavenOverride=$aapt2_override" "$GRADLE_TASK"
    else
      JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
        "$gradle_cmd" "${GRADLE_FLAGS[@]}" "--max-workers=$workers" "$GRADLE_TASK"
    fi
    popd >/dev/null
    return 0
  fi

  mkdir -p "$OUT_DIR"
  log_file="$(mktemp "$OUT_DIR/.${key}.XXXX.log")"
  fifo="$(mktemp -u "$OUT_DIR/.${key}.XXXX.fifo")"
  mkfifo "$fifo"
  stage_begin "$key" "$title (0/$total tasks)"
  (
    set +e
    pushd "$APP_DIR" >/dev/null
    if [[ -n "$aapt2_override" ]]; then
      JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
        "$gradle_cmd" "${GRADLE_FLAGS[@]}" "--max-workers=$workers" "-Pandroid.aapt2FromMavenOverride=$aapt2_override" "$GRADLE_TASK" >"$fifo" 2>&1
    else
      JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
        "$gradle_cmd" "${GRADLE_FLAGS[@]}" "--max-workers=$workers" "$GRADLE_TASK" >"$fifo" 2>&1
    fi
    cmd_status=$?
    popd >/dev/null
    exit "$cmd_status"
  ) &
  runner_pid=$!
  (
    tasks_done=0
    while IFS= read -r line; do
      printf '%s\n' "$line" >> "$log_file"
      if [[ "$line" == '> Task '* ]]; then
        tasks_done=$(( tasks_done + 1 ))
        progress=$(( tasks_done * 100 / total ))
        (( progress > 99 )) && progress=99
        stage_update "$key" RUN "$progress"
        set_dashboard_current "$title ($tasks_done/$total tasks)"
      elif [[ "$line" == BUILD\ SUCCESSFUL* ]]; then
        stage_update "$key" RUN 100
      fi
    done < "$fifo"
  ) &
  parser_pid=$!
  while kill -0 "$runner_pid" 2>/dev/null; do
    render_dashboard
    sleep 1
  done
  wait "$runner_pid" || cmd_status=$?
  wait "$parser_pid" || true
  if (( cmd_status == 0 )); then
    stage_done "$key"
  else
    stage_fail "$key"
    print_stage_failure_tail "$title" "$log_file"
    rm -f "$fifo" "$log_file"
    return "$cmd_status"
  fi
  rm -f "$fifo" "$log_file"
}

require_external_bins() {
  mkdir -p "$PREBUILT_BIN_DIR"
  local missing=() bin
  for bin in "${REQUIRED_EXTERNAL_BINS[@]}"; do
    [[ -f "$PREBUILT_BIN_DIR/$bin" ]] || missing+=("$bin")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    printf '[ZDT-D][ERR] Не найдены внешние бинарники в %s:\n' "$PREBUILT_BIN_DIR" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    fail 'Доложи их в prebuilt/bin/arm64-v8a/ и повтори сборку.'
  fi
}

copy_external_bins() {
  mkdir -p "$MODULE_ROOT_DIR/bin"
  local bin
  for bin in "${REQUIRED_EXTERNAL_BINS[@]}"; do
    cp -f "$PREBUILT_BIN_DIR/$bin" "$MODULE_ROOT_DIR/bin/$bin"
    chmod 755 "$MODULE_ROOT_DIR/bin/$bin"
  done
}

prepare_module_tree_base() {
  rm -rf "$MODULE_BUILD_DIR"
  mkdir -p "$MODULE_ROOT_DIR" "$OUT_DIR/module"
  cp -a "$MODULE_TEMPLATE_DIR/." "$MODULE_ROOT_DIR/"
  cp -f "$ROOT_DIR/module.prop" "$MODULE_ROOT_DIR/module.prop"
  [[ -d "$MODULE_ROOT_DIR/META-INF" ]] || fail 'В шаблоне модуля отсутствует META-INF'
}

check_and_copy_external_bins() {
  require_external_bins
  copy_external_bins
}

package_module_zip() {
  rm -f "$MODULE_ZIP"
  pushd "$MODULE_ROOT_DIR" >/dev/null
  zip -qr "$MODULE_ZIP" .
  popd >/dev/null
}

validate_module_zip() {
  [[ -f "$MODULE_ZIP" ]] || fail "Не найден модульный zip: $MODULE_ZIP"
}

prepare_module_root() {
  local triple
  triple="$(resolve_target)"
  dashboard_init
  run_simple_stage env 'Environment checks' ensure_termux_build_prereqs
  run_simple_stage keystore 'Keystore check' ensure_debug_keystore
  run_simple_stage rustcheck 'Rust toolchain check' ensure_rust_stdlib_ready
  prepare_module_tree_base
  run_cargo_stage zdtd 'Build zdtd' "$RUST_DIR/zdtd" 'zdtd' "$triple"
  run_cargo_stage t2s 'Build t2s' "$RUST_DIR/T2s" 't2s' "$triple"
  run_simple_stage extbin 'External binaries' check_and_copy_external_bins
  run_simple_stage modulezip 'Package module zip' package_module_zip
  run_simple_stage final 'Final validation' validate_module_zip
  set_dashboard_current 'Module ready'
  render_dashboard
  msg "Готово: $MODULE_ZIP"
}

prepare_android_inputs() {
  ensure_gradle_ready
  patch_paths
  write_signing_properties
}

validate_apk_artifacts() {
  local apk_path dist_apk
  apk_path="$(find "$APP_DIR/app/build/outputs/apk" -type f -name '*.apk' | sort | tail -n 1 || true)"
  [[ -n "$apk_path" ]] || fail 'APK не найден после сборки'
  mkdir -p "$APK_OUT_DIR" "$DIST_DIR"
  dist_apk="$APK_OUT_DIR/app-release.apk"
  cp -f "$apk_path" "$dist_apk"
  cp -f "$MODULE_ZIP" "$DIST_DIR/zdt_module.zip"
  cp -f "$dist_apk" "$DIST_DIR/app-release.apk"
}

build_apk() {
  prepare_module_root
  run_simple_stage assets 'Prepare APK inputs' prepare_android_inputs
  run_simple_stage android 'Android prereqs' ensure_android_sdk_ready
  local gradle_cmd java_home aapt2_override gradle_workers
  gradle_cmd="$(find_gradle_cmd)" || fail 'Не удалось подготовить gradle/gradlew.'
  ensure_java_home
  java_home="$JAVA_HOME"
  gradle_workers="$(detect_gradle_workers)"
  aapt2_override="$(select_aapt2_override 2>/dev/null || true)"
  run_gradle_stage apk 'Build APK' "$gradle_cmd" "$java_home" "$gradle_workers" "$aapt2_override"
  run_simple_stage final 'Final validation' validate_apk_artifacts
  set_dashboard_current 'Build finished successfully'
  render_dashboard
  if [[ "$UI_MODE" == "debug" ]]; then
    msg "APK готов: $APK_OUT_DIR/app-release.apk"
    msg "Финальные артефакты:"
    msg "  $DIST_DIR/zdt_module.zip"
    msg "  $DIST_DIR/app-release.apk"
  fi
}

clean_all() {
  rm -rf "$OUT_DIR" "$APP_DIR/app/build" "$APP_DIR/build" "$RUST_DIR/target"
  rm -rf "$APP_DIR/app/build/generated/zdt-assets" "$TOOLS_DIR/cargo-home"
  msg 'Build outputs cleaned'
}

clear_all() {
  rm -rf \
    "$OUT_DIR" \
    "$PROJECT_CARGO_TARGET_DIR" \
    "$PROJECT_CARGO_HOME" \
    "$ROOT_DIR/.gradle" \
    "$APP_DIR/.gradle" \
    "$APP_DIR/.kotlin" \
    "$APP_DIR/build" \
    "$APP_DIR/app/build" \
    "$APP_DIR/app/.cxx"
  rm -f \
    "$APP_DIR/local.properties" \
    "$APP_DIR/keystore.properties" \
    "$APP_MODULE_DIR/src/main/assets/zdt_module.zip" \
    "$APP_MODULE_DIR/src/main/assets/module.prop"
  printf '[ZDT-D] Project cleared: removed compiled outputs, generated assets, and local build properties. Persistent keystores in ./keystores are preserved\n'
  return 0
}

usage() {
  cat <<USAGE
Использование:
  ./build.sh                  # полный цикл: module + apk(debug signed)
  ./build.sh apk              # собрать модуль и APK (по умолчанию Debug -> app-release.apk)
  ./build.sh debug            # собрать APK с полным подробным логом без live dashboard
  ./build.sh module           # собрать только zdt_module.zip
  ./build.sh doctor           # проверка окружения
  ./build.sh doctor-rust      # подробная проверка Rust toolchain
  ./build.sh keystore         # создать или переиспользовать постоянный keystore в ./keystores
  ./build.sh clean            # обычная очистка build outputs
  ./build.sh clear            # глубокая очистка build outputs и generated files; ./keystores сохраняется
  ./build.sh setup-all        # полный bootstrap для Termux
  ./build.sh setup-termux     # установить пакеты Termux
  ./build.sh setup-rust       # проверить/восстановить Termux Rust stdlib и toolchain
  ./build.sh setup-gradle     # скачать локальный Gradle $GRADLE_VERSION
  ./build.sh setup-android    # скачать cmdline-tools + SDK platform/build-tools
  ./build.sh patch-paths      # автонайти SDK и записать application/local.properties
USAGE
}

case "$MODE" in
  apk)
    build_apk
    ;;
  debug)
    UI_MODE='debug'
    build_apk
    ;;
  module)
    prepare_module_root
    mkdir -p "$DIST_DIR"
    cp -f "$MODULE_ZIP" "$DIST_DIR/zdt_module.zip"
    ;;
  doctor)
    doctor
    ;;
  doctor-rust)
    set_rust_toolchain_paths
    doctor_rust || true
    ;;
  keystore)
    ensure_debug_keystore
    write_signing_properties
    printf '[ZDT-D] Persistent keystore ready: %s\n' "$DEBUG_KEYSTORE_PATH"
    ;;
  clean)
    clean_all
    ;;
  clear)
    clear_all
    exit 0
    ;;
  setup-all)
    setup_all
    ;;
  setup-termux)
    install_termux_packages
    ;;
  setup-rust)
    ensure_termux_build_prereqs
    ensure_rust_stdlib_ready
    ;;
  setup-gradle)
    install_gradle_local
    ;;
  setup-android)
    install_android_sdk_packages
    ;;
  patch-paths)
    patch_paths
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage
    fail "Неизвестный режим: $MODE"
    ;;
esac
