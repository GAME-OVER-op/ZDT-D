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
GRADLE_FLAGS=()

AUTO_BUILT_BINS=(
  zdtd
  t2s
)

REQUIRED_EXTERNAL_BINS=(
  byedpi
  dnscrypt
  dpitunnel-cli
  nfqws
  nfqws2
  opera-proxy
  sing-box
)

msg() { printf '[ZDT-D] %s\n' "$*"; }
warn() { printf '[ZDT-D][WARN] %s\n' "$*" >&2; }
fail() { printf '[ZDT-D][ERR] %s\n' "$*" >&2; exit 1; }

cmd_exists() { command -v "$1" >/dev/null 2>&1; }

need_cmd() {
  cmd_exists "$1" || fail "Команда не найдена: $1"
}


detect_cpu_count() {
  if command -v nproc >/dev/null 2>&1; then
    nproc
    return 0
  fi
  getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1'
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
  if command -v javac >/dev/null 2>&1; then
    local javac_path real_javac
    javac_path="$(command -v javac)"
    real_javac="$(readlink -f "$javac_path" 2>/dev/null || printf '%s' "$javac_path")"
    dirname "$(dirname "$real_javac")"
    return 0
  fi
  return 1
}

host_target() {
  rustc -vV 2>/dev/null | awk '/^host: /{print $2}'
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
    printf '%s' "$RUST_DIR/target/$profile"
  else
    printf '%s' "$RUST_DIR/target/$triple/$profile"
  fi
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
  local url="$1"
  local out="$2"
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
  return 0
}

auto_detect_android_sdk_root() {
  local candidates=()
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then candidates+=("$ANDROID_SDK_ROOT"); fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then candidates+=("$ANDROID_HOME"); fi
  local lp
  lp="$(read_local_properties_sdk_dir 2>/dev/null || true)"
  if [[ -n "$lp" ]]; then candidates+=("$lp"); fi
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

is_termux_runtime() {
  [[ -d /data/data/com.termux/files/usr ]] || [[ "${PREFIX:-}" == /data/data/com.termux/files/usr* ]]
}

ensure_termux_build_prereqs() {
  is_termux_runtime || return 0

  local missing=()
  local required=(javac keytool cargo rustc zip unzip make git find aapt2)
  local downloader_ok=0

  for cmd in "${required[@]}"; do
    cmd_exists "$cmd" || missing+=("$cmd")
  done

  if cmd_exists curl || cmd_exists wget; then
    downloader_ok=1
  else
    missing+=(curl-or-wget)
  fi

  if [[ ${#missing[@]} -eq 0 && -x "$AAPT2_TERMUX" ]]; then
    return 0
  fi

  warn "В Termux не хватает зависимостей для автосборки: ${missing[*]:-aapt2}. Пробую установить автоматически."
  install_termux_packages

  for cmd in "${required[@]}"; do
    cmd_exists "$cmd" || fail "После автоустановки в Termux всё ещё не найдена команда: $cmd"
  done
  if ! cmd_exists curl && ! cmd_exists wget; then
    fail 'После автоустановки в Termux всё ещё нет curl/wget.'
  fi
  [[ -x "$AAPT2_TERMUX" ]] || fail "После автоустановки не найден Termux aapt2: $AAPT2_TERMUX"
}

select_aapt2_override() {
  if [[ -n "${AAPT2_OVERRIDE:-}" ]]; then
    [[ -x "$AAPT2_OVERRIDE" ]] || fail "AAPT2_OVERRIDE задан, но файл не исполняемый: $AAPT2_OVERRIDE"
    printf '%s' "$AAPT2_OVERRIDE"
    return 0
  fi

  if is_termux_runtime; then
    if [[ -x "$AAPT2_TERMUX" ]]; then
      printf '%s' "$AAPT2_TERMUX"
      return 0
    fi
    fail "В Termux нужен arm64 aapt2. Установи пакет 'aapt2' или задай AAPT2_OVERRIDE=/data/data/com.termux/files/usr/bin/aapt2. SDK build-tools aapt2 здесь использовать нельзя, это host-бинарник и он падает на Android."
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
  cat > "$LOCAL_PROPERTIES_FILE" <<EOF
sdk.dir=$sdk_escaped
EOF
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
  keytool -genkeypair     -keystore "$DEBUG_KEYSTORE_PATH"     -storepass "$DEBUG_KEYSTORE_PASSWORD"     -keypass "$DEBUG_KEY_PASSWORD"     -alias "$DEBUG_KEY_ALIAS"     -keyalg RSA     -keysize 2048     -validity 10000     -dname "CN=Android Debug,O=Android,C=US"     >/dev/null 2>&1
  [[ -f "$DEBUG_KEYSTORE_PATH" ]] || fail "Не удалось создать keystore: $DEBUG_KEYSTORE_PATH"
}

write_signing_properties() {
  mkdir -p "$APP_DIR"
  cat > "$APP_DIR/keystore.properties" <<EOF
storeFile=$DEBUG_KEYSTORE_PATH
storePassword=$DEBUG_KEYSTORE_PASSWORD
keyAlias=$DEBUG_KEY_ALIAS
keyPassword=$DEBUG_KEY_PASSWORD
EOF
  msg "Обновлён $APP_DIR/keystore.properties -> $DEBUG_KEYSTORE_PATH"
}

doctor() {
  local triple gradle_cmd missing=0 java_home=""
  triple="$(resolve_target)"
  msg 'Проверка окружения'

  for cmd in cargo rustc zip find unzip keytool; do
    if command -v "$cmd" >/dev/null 2>&1; then
      printf '  [ok] %s -> %s\n' "$cmd" "$(command -v "$cmd")"
    else
      printf '  [!!] %s not found\n' "$cmd"
      missing=1
    fi
  done

  if java_home="$(detect_java_home 2>/dev/null)"; then
    printf '  [ok] java home -> %s\n' "$java_home"
  else
    printf '  [!!] java/javac not found\n'
    missing=1
  fi

  if gradle_cmd="$(find_gradle_cmd 2>/dev/null)"; then
    printf '  [ok] gradle -> %s\n' "$gradle_cmd"
  else
    printf '  [!!] gradle/gradlew not found\n'
    missing=1
  fi

  printf '  [ok] rust target requested -> %s\n' "$triple"
  printf '  [ok] auto-built bins -> %s\n' "${AUTO_BUILT_BINS[*]}"
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

  if aapt2_path="$(select_aapt2_override 2>/dev/null)"; then
    printf '  [ok] aapt2 -> %s\n' "$aapt2_path"
  else
    printf '  [..] aapt2 override not found (will rely on AGP default if available)\n'
  fi

  mkdir -p "$PREBUILT_BIN_DIR"
  local ext_missing=0
  for bin in "${REQUIRED_EXTERNAL_BINS[@]}"; do
    if [[ -f "$PREBUILT_BIN_DIR/$bin" ]]; then
      printf '  [ok] external bin -> %s\n' "$bin"
    else
      printf '  [..] missing external bin -> %s\n' "$bin"
      ext_missing=1
    fi
  done

  if [[ "$ext_missing" -eq 1 ]]; then
    warn 'Для полной сборки module/apk доложи все внешние бинарники в prebuilt/bin/arm64-v8a/'
  fi

  if [[ "$missing" -eq 1 ]]; then
    fail 'Окружение неполное. Исправь пункты [!!] выше или выполни ./build.sh setup-all'
  fi

  msg 'Проверка завершена'
}

install_termux_packages() {
  cmd_exists pkg || fail 'Этот режим рассчитан на Termux: команда pkg не найдена.'
  msg 'Устанавливаю/дополняю пакеты Termux: openjdk-17, rust, clang, unzip, zip, curl, wget, make, git, aapt2'
  pkg install -y openjdk-17 rust clang unzip zip curl wget make git aapt2
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
  msg "Gradle установлен: $resolved_cmd"
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
  msg "Android cmdline-tools установлены: $SDKMANAGER_BIN"
}

install_android_sdk_packages() {
  install_android_cmdline_tools
  mkdir -p "$HOME/.android"
  : > "$HOME/.android/repositories.cfg"
  msg "Принимаю лицензии Android SDK"
  yes | "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
  msg "Устанавливаю Android SDK пакеты: platform-tools, platforms;android-$ANDROID_API_LEVEL, build-tools;$ANDROID_BUILD_TOOLS_VERSION"
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
  msg 'Bootstrap завершён. Теперь можешь запускать ./build.sh doctor и ./build.sh'
}

build_rust_binary() {
  local crate_dir="$1"
  local bin_name="$2"
  local triple="$3"

  pushd "$crate_dir" >/dev/null
  if [[ "$triple" == "$(host_target || true)" ]]; then
    msg "Сборка Rust бинарника $bin_name (native host)"
    cargo build --profile "$CARGO_PROFILE"
  else
    msg "Сборка Rust бинарника $bin_name (target=$triple)"
    cargo build --profile "$CARGO_PROFILE" --target "$triple"
  fi
  popd >/dev/null

  local src_bin
  src_bin="$(cargo_out_dir "$triple" "$CARGO_PROFILE")/$bin_name"
  [[ -f "$src_bin" ]] || fail "Не найден собранный бинарник: $src_bin"

  mkdir -p "$MODULE_ROOT_DIR/bin"
  cp -f "$src_bin" "$MODULE_ROOT_DIR/bin/$bin_name"
  chmod 755 "$MODULE_ROOT_DIR/bin/$bin_name"
}

require_external_bins() {
  mkdir -p "$PREBUILT_BIN_DIR"
  local missing=()
  for bin in "${REQUIRED_EXTERNAL_BINS[@]}"; do
    [[ -f "$PREBUILT_BIN_DIR/$bin" ]] || missing+=("$bin")
  done

  if [[ "${#missing[@]}" -gt 0 ]]; then
    printf '[ZDT-D][ERR] Не найдены внешние бинарники в %s:\n' "$PREBUILT_BIN_DIR" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    fail 'Доложи их в prebuilt/bin/arm64-v8a/ и повтори сборку.'
  fi
}

copy_external_bins() {
  mkdir -p "$MODULE_ROOT_DIR/bin"
  for bin in "${REQUIRED_EXTERNAL_BINS[@]}"; do
    cp -f "$PREBUILT_BIN_DIR/$bin" "$MODULE_ROOT_DIR/bin/$bin"
    chmod 755 "$MODULE_ROOT_DIR/bin/$bin"
  done
  msg "Скопированы внешние бинарники: ${REQUIRED_EXTERNAL_BINS[*]}"
}

prepare_module_root() {
  local triple
  triple="$(resolve_target)"

  ensure_termux_build_prereqs
  need_cmd cargo
  need_cmd zip
  need_cmd find
  require_external_bins

  rm -rf "$MODULE_BUILD_DIR"
  mkdir -p "$MODULE_ROOT_DIR"

  msg 'Копирую шаблон модуля'
  cp -a "$MODULE_TEMPLATE_DIR/." "$MODULE_ROOT_DIR/"
  cp -f "$ROOT_DIR/module.prop" "$MODULE_ROOT_DIR/module.prop"

  [[ -d "$MODULE_ROOT_DIR/META-INF" ]] || fail 'В шаблоне модуля отсутствует META-INF'

  build_rust_binary "$RUST_DIR/zdtd" 'zdtd' "$triple"
  build_rust_binary "$RUST_DIR/T2s" 't2s' "$triple"
  copy_external_bins

  mkdir -p "$OUT_DIR/module"
  rm -f "$MODULE_ZIP"
  pushd "$MODULE_ROOT_DIR" >/dev/null
  msg "Упаковываю модуль в $MODULE_ZIP"
  zip -qr "$MODULE_ZIP" .
  popd >/dev/null

  msg "Готово: $MODULE_ZIP"
}

build_apk() {
  ensure_termux_build_prereqs
  prepare_module_root
  ensure_gradle_ready
  patch_paths
  ensure_debug_keystore
  write_signing_properties
  local gradle_cmd java_home aapt2_override gradle_workers
  gradle_cmd="$(find_gradle_cmd)" || fail 'Не удалось подготовить gradle/gradlew.'
  java_home="$(detect_java_home)" || fail 'Не найден JDK. Установи openjdk-17 или задай JAVA_HOME.'
  gradle_workers="$(detect_gradle_workers)"

  mkdir -p "$APK_OUT_DIR" "$DIST_DIR"
  pushd "$APP_DIR" >/dev/null
  msg "Запускаю Android сборку: $GRADLE_TASK"
  msg "Gradle workers: $gradle_workers"
  if aapt2_override="$(select_aapt2_override 2>/dev/null)"; then
    msg "Использую aapt2 override: $aapt2_override"
    JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
      "$gradle_cmd" "${GRADLE_FLAGS[@]}" "--max-workers=$gradle_workers" "-Pandroid.aapt2FromMavenOverride=$aapt2_override" "$GRADLE_TASK"
  else
    JAVA_HOME="$java_home" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" \
      "$gradle_cmd" "${GRADLE_FLAGS[@]}" "--max-workers=$gradle_workers" "$GRADLE_TASK"
  fi
  popd >/dev/null

  local apk_path dist_apk
  apk_path="$(find "$APP_DIR/app/build/outputs/apk" -type f -name '*.apk' | sort | tail -n 1 || true)"
  [[ -n "$apk_path" ]] || fail 'APK не найден после сборки'

  dist_apk="$APK_OUT_DIR/app-release.apk"
  cp -f "$apk_path" "$dist_apk"
  cp -f "$MODULE_ZIP" "$DIST_DIR/zdt_module.zip"
  cp -f "$dist_apk" "$DIST_DIR/app-release.apk"

  msg "APK готов: $dist_apk"
  msg 'Финальные артефакты:'
  msg "  $DIST_DIR/zdt_module.zip"
  msg "  $DIST_DIR/app-release.apk"
}

clean_all() {
  rm -rf "$OUT_DIR" "$APP_DIR/app/build" "$APP_DIR/build" "$RUST_DIR/target"
  rm -rf "$APP_DIR/app/build/generated/zdt-assets"
  msg 'Build outputs cleaned'
}

clear_all() {
  rm -rf     "$OUT_DIR"     "$RUST_DIR/target"     "$ROOT_DIR/.gradle"     "$APP_DIR/.gradle"     "$APP_DIR/.kotlin"     "$APP_DIR/build"     "$APP_DIR/app/build"     "$APP_DIR/app/.cxx"     "$TOOLS_DIR/signing"

  rm -f     "$APP_DIR/local.properties"     "$APP_DIR/keystore.properties"     "$APP_MODULE_DIR/src/main/assets/zdt_module.zip"     "$APP_MODULE_DIR/src/main/assets/module.prop"

  msg 'Project cleared: removed compiled outputs, generated assets, and local build properties. Persistent keystores in ./keystores are preserved'
}

usage() {
  cat <<EOF
Использование:
  ./build.sh                  # полный цикл: module + apk(debug signed); в Termux сам дотянет пакеты, Gradle, SDK и пути
  ./build.sh apk              # собрать модуль и APK (по умолчанию Debug -> app-release.apk)
  ./build.sh module           # собрать только zdt_module.zip
  ./build.sh doctor           # проверка окружения
  ./build.sh keystore         # create or reuse the persistent keystore in ./keystores
  ./build.sh clean            # clean standard build outputs
  ./build.sh clear            # deep clear: remove compiled outputs and generated files, but keep ./keystores intact
  ./build.sh setup-all        # Termux bootstrap: пакеты + Gradle $GRADLE_VERSION + Android SDK $ANDROID_API_LEVEL
  ./build.sh setup-termux     # установить Termux-пакеты (openjdk-17, rust, clang, unzip, zip, curl, wget, make, git, aapt2)
  ./build.sh setup-gradle     # скачать локальный Gradle $GRADLE_VERSION
  ./build.sh setup-android    # скачать cmdline-tools + platform-tools + platforms;android-$ANDROID_API_LEVEL + build-tools;$ANDROID_BUILD_TOOLS_VERSION
  ./build.sh patch-paths      # автонайти SDK и записать application/local.properties

По умолчанию APK собирается задачей assembleDebug, но итоговый файл называется app-release.apk
и подписывается базовым debug keystore (v1/v2/v3).

Структура проекта:
  module_template/              шаблон модуля БЕЗ bin/
  prebuilt/bin/arm64-v8a/       внешние бинарники, которые кладешь вручную
  rust/zdtd                     собирается автоматически -> bin/zdtd
  rust/T2s                      собирается автоматически -> bin/t2s

Полезные переменные окружения:
  ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT
  GRADLE_VERSION=$GRADLE_VERSION
  CMDLINE_TOOLS_REVISION=$CMDLINE_TOOLS_REVISION
  AAPT2_OVERRIDE=/полный/путь/до/aapt2

Важно для Termux:
  На Android нельзя использовать aapt2 из Android SDK build-tools: это host-бинарник для Linux/macOS/Windows.
  В Termux нужен свой aapt2, обычно /data/data/com.termux/files/usr/bin/aapt2.
EOF
}

case "$MODE" in
  apk)
    build_apk
    ;;
  module)
    prepare_module_root
    mkdir -p "$DIST_DIR"
    cp -f "$MODULE_ZIP" "$DIST_DIR/zdt_module.zip"
    msg "Финальный модуль: $DIST_DIR/zdt_module.zip"
    ;;
  doctor)
    doctor
    ;;
  keystore)
    ensure_debug_keystore
    write_signing_properties
    msg "Persistent keystore ready: $DEBUG_KEYSTORE_PATH"
    ;;
  clean)
    clean_all
    ;;
  clear)
    clear_all
    ;;
  setup-all)
    setup_all
    ;;
  setup-termux)
    install_termux_packages
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
