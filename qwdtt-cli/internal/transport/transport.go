// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Package transport builds the argument vector for the upstream go_client binary
// and parses its structured stdout markers. It is deliberately pure and
// stateless: no process management lives here, so the argv construction and the
// marker parsers are trivially testable.
//
// The markers parsed here are the transport's language-independent contract
// (HASH_CHECK|, PING_RESULT|, STATS|). The supervisor never parses the localized
// Russian log lines for control decisions — those drift and are for humans.
package transport

import (
	"strconv"
	"strings"

	"github.com/andycar/zdt-d/qwdtt-cli/internal/config"
)

// Args builds the transport argument vector. When checkHashes is true it produces
// the validation-only invocation (-check-hashes); otherwise the full run.
// The hashes slice overrides cfg.Hashes so the caller can pass the validated
// subset into the run.
func Args(cfg *config.Config, hashes []string, checkHashes bool) []string {
	args := []string{
		"-peer", cfg.Peer,
		"-vk", strings.Join(hashes, ","),
		"-listen", cfg.Listen,
		"-password", cfg.Password,
		"-n", strconv.Itoa(cfg.Workers),
		"-obfs", cfg.Obfs,
		"-vk-auth", cfg.VKAuth,
		"-vk-anon-path", cfg.VKAnonPath,
		"-go-dns", cfg.GoDNS,
		"-captcha-mode", cfg.CaptchaMode,
		"-device-id", cfg.DeviceID,
	}
	if checkHashes {
		args = append(args, "-check-hashes")
	}
	// Only emitted for SOCKS mode: -mode/-socks exist from upstream v1.3.7 on, so
	// leaving them off keeps a plain "vpn" run working with an older transport
	// binary (an unknown flag is fatal to Go's flag package).
	if cfg.SocksMode() {
		args = append(args, "-mode", "socks", "-socks", cfg.SocksAddr)
	}
	return args
}

// HashCheck is one parsed HASH_CHECK| line.
type HashCheck struct {
	Index   int
	Hash    string
	Status  string // ok | captcha | dead | limited | network | error
	Message string
}

// OK reports whether the hash validated cleanly and should join the run.
func (h HashCheck) OK() bool { return h.Status == "ok" }

// Terminal reports whether the status means the hash will never work this session
// and must be dropped rather than retried. A "dead" hash left in the set strands
// every downstream worker group behind it (the baton-relay deadlock).
func (h HashCheck) Terminal() bool {
	switch h.Status {
	case "dead", "error":
		return true
	default:
		return false
	}
}

// ParseHashCheck parses a "HASH_CHECK|idx|hash|status|message" line. The trailing
// message may itself be empty. Returns ok=false for any other line.
func ParseHashCheck(line string) (HashCheck, bool) {
	const prefix = "HASH_CHECK|"
	if !strings.HasPrefix(line, prefix) {
		return HashCheck{}, false
	}
	parts := strings.SplitN(strings.TrimPrefix(line, prefix), "|", 4)
	if len(parts) < 3 {
		return HashCheck{}, false
	}
	idx, err := strconv.Atoi(parts[0])
	if err != nil {
		return HashCheck{}, false
	}
	hc := HashCheck{Index: idx, Hash: parts[1], Status: parts[2]}
	if len(parts) == 4 {
		hc.Message = parts[3]
	}
	return hc, true
}

// Stats is one parsed STATS| line. This marker is an M0 delta: current upstream
// only logs the active count in a localized line, so the supervisor tolerates its
// absence and simply never enforces the active-count watchdog until the transport
// is patched to emit it.
type Stats struct {
	Active    int
	BytesUp   int64
	BytesDown int64
}

// ParseStats parses a "STATS|active|bytesUp|bytesDown" line.
func ParseStats(line string) (Stats, bool) {
	const prefix = "STATS|"
	if !strings.HasPrefix(line, prefix) {
		return Stats{}, false
	}
	parts := strings.Split(strings.TrimPrefix(line, prefix), "|")
	if len(parts) != 3 {
		return Stats{}, false
	}
	active, err := strconv.Atoi(parts[0])
	if err != nil {
		return Stats{}, false
	}
	up, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return Stats{}, false
	}
	down, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil {
		return Stats{}, false
	}
	return Stats{Active: active, BytesUp: up, BytesDown: down}, true
}
