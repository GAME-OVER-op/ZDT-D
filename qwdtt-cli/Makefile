# SPDX-License-Identifier: GPL-3.0-or-later
#
# qwdtt-cli supervisor build. The supervisor is pure Go (no cgo), so it
# cross-compiles to android/arm64 without the NDK. The upstream go_client
# transport it drives DOES need the NDK (CGO_ENABLED=1) and is built separately.

BIN        := qwdtt-cli
PKG        := ./cmd/qwdtt-cli
OUT        ?= out
GOFLAGS    := -trimpath -ldflags "-s -w"

.PHONY: all build arm64 host vet test clean

all: arm64

# On-device target. No NDK needed — the supervisor calls no C.
arm64:
	GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
		go build $(GOFLAGS) -o $(OUT)/$(BIN) $(PKG)

# Native build for local development and CI compile-checks.
host:
	go build $(GOFLAGS) -o $(OUT)/$(BIN)-host $(PKG)

vet:
	go vet ./...

test:
	go test ./...

clean:
	rm -rf $(OUT)
