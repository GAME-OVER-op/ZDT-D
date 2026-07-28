#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build the qwdtt command-line tools and stage them where the ZDT-D module build
# expects external binaries. Produces two binaries:
#
#   qwdtt-cli        the transport supervisor (pure Go, no cgo/NDK)
#   qwdtt-transport  the patched upstream go_client transport (cgo, needs the NDK)
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk scripts/build-qwdtt-bins.sh [ABI] [OUT_DIR]
#
#   ABI      Android ABI (default: arm64-v8a; also armeabi-v7a, x86_64)
#   OUT_DIR  where to place the two binaries
#            (default: prebuilt/bin/<ABI>, which build.sh consumes)
#
# The transport build (upstream/qwdtt/fetch-and-build.sh) fetches the pinned
# upstream, applies our patches and cross-compiles it; see upstream/qwdtt/.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${1:-arm64-v8a}"
OUT_DIR="${2:-$ROOT_DIR/prebuilt/bin/$ABI}"

case "$ABI" in
	arm64-v8a)   GOARCH="arm64"; GOARM="" ;;
	armeabi-v7a) GOARCH="arm";   GOARM="7" ;;
	x86_64)      GOARCH="amd64"; GOARM="" ;;
	*) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
esac

command -v go >/dev/null 2>&1 || { echo "go toolchain not found in PATH" >&2; exit 1; }
mkdir -p "$OUT_DIR"

echo ">> [qwdtt] building qwdtt-cli ($ABI, pure Go)"
(
	cd "$ROOT_DIR/qwdtt-cli"
	export GOOS=android GOARCH="$GOARCH" CGO_ENABLED=0
	[ -n "$GOARM" ] && export GOARM="$GOARM"
	go build -trimpath -ldflags "-s -w" -o "$OUT_DIR/qwdtt-cli" ./cmd/qwdtt-cli
)

echo ">> [qwdtt] building qwdtt-transport ($ABI, cgo via NDK)"
"$ROOT_DIR/upstream/qwdtt/fetch-and-build.sh" "$ABI" "$OUT_DIR/qwdtt-transport"

chmod 755 "$OUT_DIR/qwdtt-cli" "$OUT_DIR/qwdtt-transport"
echo ">> [qwdtt] done:"
ls -l "$OUT_DIR/qwdtt-cli" "$OUT_DIR/qwdtt-transport"
