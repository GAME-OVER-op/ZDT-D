#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fetch the pinned qWDTT upstream, apply our minimal patches, and cross-compile
# the standalone transport for Android. Produces a plain ELF executable (upstream
# names it libclient.so purely for APK packaging; we deploy it as a root-side CLI).
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk ./fetch-and-build.sh [ABI] [OUT]
#
#   ABI  Android ABI to target       (default: arm64-v8a)
#   OUT  output binary path          (default: out/qwdtt-transport)
#
# Requires: git, go (>= 1.24; the pinned module requests a newer toolchain and
# GOTOOLCHAIN=auto will fetch it), and the Android NDK for the CGO build.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ABI="${1:-arm64-v8a}"
OUT="${2:-$HERE/out/qwdtt-transport}"
WORK="$HERE/.work/qwdtt"
API_LEVEL="${ANDROID_NATIVE_API_LEVEL:-29}"

# --- read the pin ------------------------------------------------------------
# shellcheck disable=SC1090
URL="$(grep -E '^URL=' "$HERE/UPSTREAM" | cut -d= -f2-)"
COMMIT="$(grep -E '^COMMIT=' "$HERE/UPSTREAM" | cut -d= -f2-)"
SUBDIR="$(grep -E '^SUBDIR=' "$HERE/UPSTREAM" | cut -d= -f2-)"
[ -n "$URL" ] && [ -n "$COMMIT" ] && [ -n "$SUBDIR" ] || {
	echo "UPSTREAM pin is incomplete" >&2
	exit 1
}

case "$ABI" in
	arm64-v8a)   GOARCH="arm64"; CLANG="aarch64-linux-android" ;;
	armeabi-v7a) GOARCH="arm";   CLANG="armv7a-linux-androideabi" ;;
	x86_64)      GOARCH="amd64"; CLANG="x86_64-linux-android" ;;
	*) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || { echo "Set ANDROID_NDK_HOME to the Android NDK" >&2; exit 1; }
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}${API_LEVEL}-clang"
[ -x "$CC" ] || { echo "NDK compiler not found: $CC" >&2; exit 1; }

# --- fetch at the pinned commit ---------------------------------------------
if [ ! -d "$WORK/.git" ]; then
	echo ">> cloning $URL"
	rm -rf "$WORK"
	git clone --no-checkout "$URL" "$WORK"
fi
git -C "$WORK" fetch --depth 1 origin "$COMMIT" 2>/dev/null || git -C "$WORK" fetch origin
git -C "$WORK" checkout -q --force "$COMMIT"
git -C "$WORK" clean -qfdx "$SUBDIR"
echo ">> checked out $COMMIT"

# --- apply our patches -------------------------------------------------------
for p in "$HERE"/patches/*.patch; do
	[ -e "$p" ] || continue
	echo ">> applying $(basename "$p")"
	git -C "$WORK" apply "$p"
done

# --- cross-compile -----------------------------------------------------------
mkdir -p "$(dirname "$OUT")"
GO_VERSION="$(go version | awk '{print $3}' | sed 's/^go//')"
LDFLAGS="-s -w"
# tls-client/utls rely on //go:linkname; Go >= 1.23 rejects it without this flag.
case "$GO_VERSION" in
	1.1[0-9].*|1.2[0-2].*) : ;;              # older Go: no flag needed
	*) LDFLAGS="$LDFLAGS -checklinkname=0" ;; # 1.23+ and 2.x
esac

echo ">> building $ABI -> $OUT"
(
	cd "$WORK/$SUBDIR"
	GOTOOLCHAIN=auto GOFLAGS=-mod=mod \
	GOOS=android GOARCH="$GOARCH" CGO_ENABLED=1 CC="$CC" \
		go build -trimpath -ldflags="$LDFLAGS" -o "$OUT" .
)
echo ">> done: $OUT"
