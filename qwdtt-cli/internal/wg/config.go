// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Package wg brings up the WireGuard side of the stack: it transforms the
// transport's emitted wg-turn.conf into the shape awg's `setconf` accepts, then
// spawns amneziawg-go and configures the TUN. The transformation is pure and
// testable; the process/interface management lives in interface.go.
package wg

import (
	"fmt"
	"strconv"
	"strings"
)

// SetconfConfig is the WireGuard-only view of an emitted config, plus the
// interface-level settings that awg's setconf does not accept and must be
// applied via `ip` instead.
type SetconfConfig struct {
	// Setconf is the config text to hand to `awg setconf` — the original with the
	// wg-quick-only [Interface] keys (Address, DNS, MTU, Table, *Up/*Down,
	// SaveConfig) removed. PrivateKey, ListenPort and any amnezia obfuscation
	// params (Jc/Jmin/Jmax/S1/S2/H1..H4) are preserved, and [Peer] is untouched.
	Setconf string
	// Addresses are the [Interface] Address CIDRs, applied with `ip addr add`.
	Addresses []string
	// DNS are the [Interface] DNS servers. qwdtt-cli does not apply these — DNS
	// belongs to ZDT-D's routing layer — but they are surfaced for logging and
	// for the eventual program module.
	DNS []string
	// MTU is the [Interface] MTU, applied with `ip link set ... mtu`.
	MTU int
}

// interfaceOnlyKeys are the [Interface] keys awg setconf rejects; they are
// wg-quick concepts handled outside the WireGuard config.
var interfaceOnlyKeys = map[string]bool{
	"address":    true,
	"dns":        true,
	"mtu":        true,
	"table":      true,
	"preup":      true,
	"postup":     true,
	"predown":    true,
	"postdown":   true,
	"saveconfig": true,
}

// SplitConfig parses an emitted wg-turn.conf and separates the setconf-safe body
// from the interface-level settings. It requires the config to contain an
// [Interface] with a PrivateKey and a [Peer] with a PublicKey, matching what the
// transport emits and what amneziawg-go needs.
func SplitConfig(raw string) (SetconfConfig, error) {
	var out SetconfConfig
	out.MTU = -1

	var kept []string
	section := ""
	sawInterface, sawPeer := false, false
	hasPrivateKey, hasPublicKey := false, false

	for _, line := range strings.Split(strings.ReplaceAll(raw, "\r", ""), "\n") {
		trimmed := strings.TrimSpace(line)

		if strings.HasPrefix(trimmed, "[") && strings.HasSuffix(trimmed, "]") {
			section = strings.ToLower(strings.TrimSpace(trimmed[1 : len(trimmed)-1]))
			if section == "interface" {
				sawInterface = true
			}
			if section == "peer" {
				sawPeer = true
			}
			kept = append(kept, trimmed)
			continue
		}

		if section == "interface" {
			if key, val, ok := cutKV(trimmed); ok {
				lk := strings.ToLower(key)
				switch lk {
				case "address":
					out.Addresses = append(out.Addresses, splitList(val)...)
					continue
				case "dns":
					out.DNS = append(out.DNS, splitList(val)...)
					continue
				case "mtu":
					m, err := strconv.Atoi(strings.TrimSpace(val))
					if err != nil {
						return out, fmt.Errorf("bad MTU %q: %w", val, err)
					}
					out.MTU = m
					continue
				case "privatekey":
					hasPrivateKey = true
				}
				if interfaceOnlyKeys[lk] {
					continue // strip other wg-quick-only keys
				}
			}
		}
		if section == "peer" {
			if key, _, ok := cutKV(trimmed); ok && strings.ToLower(key) == "publickey" {
				hasPublicKey = true
			}
		}

		kept = append(kept, line)
	}

	// Trim trailing blank lines for a tidy setconf file.
	for len(kept) > 0 && strings.TrimSpace(kept[len(kept)-1]) == "" {
		kept = kept[:len(kept)-1]
	}

	if !sawInterface || !sawPeer {
		return out, fmt.Errorf("config must contain [Interface] and [Peer] sections")
	}
	if !hasPrivateKey || !hasPublicKey {
		return out, fmt.Errorf("config must contain [Interface] PrivateKey and [Peer] PublicKey")
	}
	if len(out.Addresses) == 0 {
		return out, fmt.Errorf("config has no [Interface] Address")
	}

	out.Setconf = strings.Join(kept, "\n") + "\n"
	return out, nil
}

// cutKV splits "key = value", trimming an inline comment from the value. Returns
// ok=false for lines without '=' (including comments and blanks).
func cutKV(line string) (key, val string, ok bool) {
	if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
		return "", "", false
	}
	k, v, found := strings.Cut(line, "=")
	if !found {
		return "", "", false
	}
	v = strings.TrimSpace(v)
	for _, marker := range []string{" #", " ;"} {
		if i := strings.Index(v, marker); i >= 0 {
			v = v[:i]
		}
	}
	return strings.TrimSpace(k), strings.TrimSpace(v), true
}

// splitList splits a comma/space separated value and drops empties.
func splitList(val string) []string {
	fields := strings.FieldsFunc(val, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\t'
	})
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}
