#!/usr/bin/env bash
# Ubuntu 22.04 helper for building ZDT-D release artifacts outside Termux.
# Put this file in the root of the copied ZDT-D project and run it as a separate helper.
# IMPORTANT: do not overwrite the project's own ./build.sh with this file.
#   chmod +x setup_ubuntu22_zdtd_env_v2.sh
#   ./setup_ubuntu22_zdtd_env_v2.sh all
#
# Notes for arm64 Ubuntu:
# - Official Android SDK Build Tools / NDK Linux host tools are usually x86_64.
# - On arm64 Ubuntu this script tries to use qemu-user/binfmt to run them.
# - If binfmt_misc is unavailable in your Ubuntu/chroot/proot, use x86_64 Ubuntu/GitHub Actions instead.

set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-$(pwd)}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-36}"
ZDT_COMPILE_SDK="${ZDT_COMPILE_SDK:-36}"
ANDROID_BUILD_TOOLS_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
CMDLINE_TOOLS_REVISION="${CMDLINE_TOOLS_REVISION:-14742923}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
GRADLE_VERSION="${GRADLE_VERSION:-9.4.1}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
CARGO_PROFILE="${CARGO_PROFILE:-release}"
CARGO_BUILD_TARGET="${CARGO_BUILD_TARGET:-aarch64-linux-android}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ANDROID_HOME="$ANDROID_SDK_ROOT"
SDKMANAGER_BIN="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_REVISION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
DOWNLOADS_DIR="$PROJECT_DIR/.tools/downloads"
LOCAL_GRADLE_HOME="$PROJECT_DIR/.tools/gradle/gradle-$GRADLE_VERSION"
LOCAL_GRADLE_CMD="$LOCAL_GRADLE_HOME/bin/gradle"
ENV_FILE="$PROJECT_DIR/.zdt_ubuntu_build_env"

log() { printf '[ZDT-Ubuntu] %s\n' "$*"; }
warn() { printf '[ZDT-Ubuntu][WARN] %s\n' "$*" >&2; }
fail() { printf '[ZDT-Ubuntu][ERR] %s\n' "$*" >&2; exit 1; }
cmd_exists() { command -v "$1" >/dev/null 2>&1; }

run_as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  elif cmd_exists sudo; then
    sudo "$@"
  else
    fail "Нужен root или sudo для: $*"
  fi
}

ensure_project_root() {
  cd "$PROJECT_DIR"
  [[ -f build.sh ]] || fail "Не найден штатный build.sh проекта. Запусти helper из корня ZDT-D или задай PROJECT_DIR=/path/to/project"
  [[ -d application && -d rust && -d zygisk ]] || fail "Это не похоже на корень ZDT-D: нет application/rust/zygisk"
  if grep -q 'Ubuntu 22.04 helper for building ZDT-D' build.sh 2>/dev/null || grep -q '\[ZDT-Ubuntu\]' build.sh 2>/dev/null; then
    fail "Похоже, helper был сохранён поверх штатного build.sh проекта. Восстанови оригинальный build.sh из архива/git, а этот helper запускай отдельным файлом."
  fi
}

install_apt_deps() {
  log "Устанавливаю пакеты Ubuntu 22.04"
  run_as_root apt-get update
  run_as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ca-certificates curl wget unzip zip git make file pkg-config \
    build-essential openjdk-17-jdk qemu-user-static binfmt-support \
    binutils coreutils findutils sed grep gawk
}

ensure_rust() {
  if ! cmd_exists rustc || ! cmd_exists cargo || ! cmd_exists rustup; then
    log "Устанавливаю rustup/Rust"
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
    # shellcheck disable=SC1090
    source "$HOME/.cargo/env"
  else
    # shellcheck disable=SC1090
    [[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env" || true
  fi
  rustup target add "$CARGO_BUILD_TARGET"
}

install_cmdline_tools() {
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" "$DOWNLOADS_DIR"
  if [[ -x "$SDKMANAGER_BIN" ]]; then
    log "Android cmdline-tools уже есть: $SDKMANAGER_BIN"
    return 0
  fi

  local zip_path="$DOWNLOADS_DIR/$CMDLINE_TOOLS_ZIP"
  local tmp_dir="$DOWNLOADS_DIR/cmdline-tools-extract"
  log "Скачиваю Android cmdline-tools: $CMDLINE_TOOLS_ZIP"
  curl -L --fail --retry 5 -o "$zip_path" "$CMDLINE_TOOLS_URL"
  rm -rf "$tmp_dir" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$tmp_dir" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  unzip -q -o "$zip_path" -d "$tmp_dir"
  cp -a "$tmp_dir/cmdline-tools/." "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
  [[ -x "$SDKMANAGER_BIN" ]] || fail "sdkmanager не найден после установки: $SDKMANAGER_BIN"
}

install_android_sdk() {
  export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}" 
  export ANDROID_HOME ANDROID_SDK_ROOT
  export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

  install_cmdline_tools

  mkdir -p "$HOME/.android"
  : > "$HOME/.android/repositories.cfg"

  log "Принимаю Android SDK licenses"
  yes | "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true

  log "Устанавливаю Android SDK: platform-tools, platforms;android-$ANDROID_API_LEVEL, build-tools;$ANDROID_BUILD_TOOLS_VERSION, ndk;$NDK_VERSION"
  "$SDKMANAGER_BIN" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-$ANDROID_API_LEVEL" \
    "build-tools;$ANDROID_BUILD_TOOLS_VERSION" \
    "ndk;$NDK_VERSION"
}

ensure_gradle() {
  if [[ -x application/gradlew || -x "$LOCAL_GRADLE_CMD" || -n "$(command -v gradle 2>/dev/null || true)" ]]; then
    log "Gradle уже найден"
    return 0
  fi

  mkdir -p "$PROJECT_DIR/.tools/gradle" "$DOWNLOADS_DIR"
  local zip_path="$DOWNLOADS_DIR/gradle-$GRADLE_VERSION-bin.zip"
  log "Скачиваю Gradle $GRADLE_VERSION"
  curl -L --fail --retry 5 -o "$zip_path" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  rm -rf "$LOCAL_GRADLE_HOME"
  unzip -q -o "$zip_path" -d "$PROJECT_DIR/.tools/gradle"
  [[ -x "$LOCAL_GRADLE_CMD" ]] || fail "Gradle не установлен: $LOCAL_GRADLE_CMD"
}

enable_binfmt_for_x86_64() {
  local arch
  arch="$(uname -m)"
  [[ "$arch" == "aarch64" || "$arch" == "arm64" ]] || return 0

  log "Ubuntu arm64: включаю qemu-x86_64 binfmt для официальных Android host tools"
  if [[ -d /proc/sys/fs/binfmt_misc ]]; then
    run_as_root mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null || true
  fi
  run_as_root update-binfmts --enable qemu-x86_64 2>/dev/null || true
  run_as_root systemctl restart systemd-binfmt 2>/dev/null || true

  if [[ ! -e /proc/sys/fs/binfmt_misc/qemu-x86_64 ]]; then
    warn "binfmt qemu-x86_64 не виден. В proot/chroot на Android это часто невозможно."
  fi
}

find_ndk_root() {
  local ndk_root="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
  if [[ ! -d "$ndk_root" ]]; then
    ndk_root="$ANDROID_SDK_ROOT/ndk/$(ls "$ANDROID_SDK_ROOT/ndk" 2>/dev/null | sort -V | tail -1 || true)"
  fi
  [[ -d "$ndk_root" ]] || fail "NDK не найден в $ANDROID_SDK_ROOT/ndk"
  printf '%s' "$ndk_root"
}

configure_cargo_android() {
  local ndk_root ndk_bin android_cc android_ar
  ndk_root="$(find_ndk_root)"
  ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
  android_cc="$ndk_bin/aarch64-linux-android21-clang"
  android_ar="$ndk_bin/llvm-ar"

  [[ -x "$android_cc" ]] || fail "Не найден Android clang: $android_cc"
  [[ -x "$android_ar" ]] || fail "Не найден Android llvm-ar: $android_ar"

  mkdir -p "$PROJECT_DIR/.cargo"
  cat > "$PROJECT_DIR/.cargo/config.toml" <<CFG
[target.aarch64-linux-android]
linker = "$android_cc"
ar = "$android_ar"
CFG

  log "Записан Cargo Android linker: $PROJECT_DIR/.cargo/config.toml"
}

write_project_properties() {
  local java_home keystore_path
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  keystore_path="$PROJECT_DIR/keystores/zdt-debug.keystore"

  mkdir -p "$PROJECT_DIR/application" "$PROJECT_DIR/keystores"
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$PROJECT_DIR/application/local.properties"

  if [[ ! -f "$keystore_path" ]]; then
    log "Создаю debug keystore для release-подписи: $keystore_path"
    keytool -genkeypair \
      -keystore "$keystore_path" \
      -storepass android \
      -keypass android \
      -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" \
      >/dev/null 2>&1
  fi

  cat > "$PROJECT_DIR/application/keystore.properties" <<PROP
storeFile=$keystore_path
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
PROP

  cat > "$ENV_FILE" <<ENV
export JAVA_HOME="$java_home"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export NDK_ROOT="$(find_ndk_root)"
export ANDROID_NDK_ROOT="$(find_ndk_root)"
export ANDROID_NDK_HOME="$(find_ndk_root)"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION:$(find_ndk_root)/toolchains/llvm/prebuilt/linux-x86_64/bin:$LOCAL_GRADLE_HOME/bin:\$HOME/.cargo/bin:\$PATH"
export ANDROID_API_LEVEL="$ANDROID_API_LEVEL"
export ANDROID_BUILD_TOOLS_VERSION="$ANDROID_BUILD_TOOLS_VERSION"
export ZDT_COMPILE_SDK="$ZDT_COMPILE_SDK"
export GRADLE_VERSION="$GRADLE_VERSION"
export BUILD_TYPE="$BUILD_TYPE"
export CARGO_PROFILE="$CARGO_PROFILE"
export CARGO_BUILD_TARGET="$CARGO_BUILD_TARGET"
export NO_DASHBOARD="1"
ENV

  log "Записаны application/local.properties, application/keystore.properties и $ENV_FILE"
}

verify_toolchain() {
  # shellcheck disable=SC1090
  source "$ENV_FILE" 2>/dev/null || true
  export ANDROID_HOME ANDROID_SDK_ROOT

  local aapt2="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/aapt2"
  local apksigner="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/apksigner"
  local zipalign="$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/zipalign"
  local ndk_root ndk_bin android_cc
  ndk_root="$(find_ndk_root)"
  ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
  android_cc="$ndk_bin/aarch64-linux-android21-clang"

  log "Проверка Android SDK / NDK / Rust"
  test -s "$ANDROID_SDK_ROOT/platforms/android-$ANDROID_API_LEVEL/android.jar" || fail "Нет android.jar для android-$ANDROID_API_LEVEL"
  "$aapt2" version >/tmp/zdt-aapt2-version.txt 2>&1 || {
    cat /tmp/zdt-aapt2-version.txt >&2 || true
    fail "aapt2 не запускается. На arm64 Ubuntu нужен рабочий qemu-x86_64/binfmt либо x86_64 Ubuntu."
  }
  "$apksigner" version >/dev/null || fail "apksigner не запускается: $apksigner"
  "$zipalign" -h >/dev/null 2>&1 || true
  "$android_cc" --version >/dev/null || fail "NDK clang не запускается: $android_cc"
  rustup target list --installed | grep -qx "$CARGO_BUILD_TARGET" || fail "Rust target не установлен: $CARGO_BUILD_TARGET"
  log "OK: aapt2 / apksigner / NDK clang / Rust target работают"
}

setup_all() {
  ensure_project_root
  install_apt_deps
  ensure_rust
  install_android_sdk
  ensure_gradle
  enable_binfmt_for_x86_64
  configure_cargo_android
  write_project_properties
  verify_toolchain
}

build_project() {
  ensure_project_root
  [[ -f "$ENV_FILE" ]] || fail "Сначала выполни: $0 setup"
  # shellcheck disable=SC1090
  source "$ENV_FILE"

  # На Ubuntu не нужен Termux PREFIX и lzhiyong aapt2.
  unset PREFIX || true
  unset AAPT2_OVERRIDE || true

  # Предупреждение, если используется одна из временных версий build.sh с packageRelease.
  if grep -q 'GRADLE_TASK=.*packageRelease' "$PROJECT_DIR/build.sh" 2>/dev/null; then
    warn "В штатном build.sh найден packageRelease. Для нормального APK лучше использовать assembleRelease."
  fi

  log "Запускаю полную сборку ZDT-D: module + Release APK"
  chmod +x "$PROJECT_DIR/build.sh"
  BUILD_TYPE=Release \
  ANDROID_API_LEVEL="$ANDROID_API_LEVEL" \
  ANDROID_BUILD_TOOLS_VERSION="$ANDROID_BUILD_TOOLS_VERSION" \
  GRADLE_VERSION="$GRADLE_VERSION" \
  CARGO_PROFILE="$CARGO_PROFILE" \
  CARGO_BUILD_TARGET="$CARGO_BUILD_TARGET" \
  NO_DASHBOARD=1 \
  "$PROJECT_DIR/build.sh" apk

  local apk="$PROJECT_DIR/out/dist/app-release.apk"
  local module_zip="$PROJECT_DIR/out/dist/zdt_module.zip"
  [[ -s "$apk" ]] || fail "APK не найден: $apk"
  [[ -s "$module_zip" ]] || fail "Module zip не найден: $module_zip"

  unzip -l "$apk" | grep -q 'AndroidManifest.xml' || fail "APK без AndroidManifest.xml: $apk"
  "$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/apksigner" verify --verbose "$apk" >/tmp/zdt-apksigner-verify.txt 2>&1 || {
    cat /tmp/zdt-apksigner-verify.txt >&2 || true
    fail "APK не проходит apksigner verify: $apk"
  }

  log "Готово:"
  ls -lh "$apk" "$module_zip"
}

case "${1:-all}" in
  setup)
    setup_all
    ;;
  verify)
    ensure_project_root
    verify_toolchain
    ;;
  build)
    build_project
    ;;
  all)
    setup_all
    build_project
    ;;
  env)
    ensure_project_root
    [[ -f "$ENV_FILE" ]] || fail "Файл окружения ещё не создан. Выполни: $0 setup"
    cat "$ENV_FILE"
    ;;
  *)
    cat <<USAGE
Usage:
  $0 setup   # установить Ubuntu/Android/Rust/Gradle окружение
  $0 build   # собрать module + Release APK
  $0 all     # setup + build
  $0 verify  # проверить aapt2/apksigner/NDK/Rust
  $0 env     # показать export-переменные
USAGE
    exit 1
    ;;
esac
