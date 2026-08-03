// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Package config loads and validates the qwdtt-cli supervisor configuration.
//
// The config surface mirrors the upstream transport's flag schema (documented in
// docs/M0-findings.md): every observable qWDTT setting maps to exactly one flag
// on the standalone go_client binary. The supervisor holds no secrets of its own
// — hashes, the tunnel password, the peer host and device identity all come from
// a gitignored config file. Ship only qwdtt.example.conf.
package config

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/andycar/zdt-d/qwdtt-cli/internal/logging"
)

// Config is the fully-resolved supervisor configuration.
type Config struct {
	// TransportBinary is the path to the built go_client executable that the
	// supervisor spawns and drives. Required.
	TransportBinary string
	// StateDir is a persistent working directory the transport child is chdir'd
	// into. All of the transport's CWD-relative artifacts live here:
	// wg-turn.conf, vk_profile.json, captcha_browser_fp. Required and must
	// survive restarts so the browser identity stays stable.
	StateDir string

	// Peer is the VPS DTLS endpoint as host:port (e.g. 2.27.36.231:56000). The
	// handset never contacts this directly — the transport reaches it only via
	// VK TURN relays. Required.
	Peer string
	// Listen is the local UDP endpoint the WireGuard client dials. Must be
	// loopback; a non-loopback bind would expose the tunnel and, on a whitelist
	// ISP, leak. Default 127.0.0.1:9000.
	Listen string
	// Hashes are the VK call join hashes. At least one is required; dead ones are
	// dropped at startup by hash validation.
	Hashes []string
	// Password is the plaintext tunnel password. The transport derives its WRAP
	// key from it via HKDF; without it the transport refuses to start. The
	// encrypted keystore blob from the app cannot be reused. Required.
	Password string
	// Workers is the requested total worker count. The transport floors it to a
	// multiple of 9 (workersPerGroup) and caps it at 108. Default 45.
	Workers int
	// Obfs is the RTP masking mode: "audio" or "video". The captured device uses
	// "video" (H.264 call masking). Default "video".
	Obfs string
	// VKAuth is "anonymous" or "account". Default "anonymous".
	VKAuth string
	// VKAnonPath is "vkcalls" or "legacy". Default "vkcalls".
	VKAnonPath string
	// GoDNS is the resolver used to *reach* VK (not tunnel DNS): a preset name,
	// "custom:IP" or "doh:URL". Default "yandex".
	GoDNS string
	// CaptchaMode is "auto" or "rjs". "wv" (WebView) is rejected — there is no
	// WebView headless. Default "auto".
	CaptchaMode string
	// DeviceID is the stable 8-byte hex device identity.
	DeviceID string

	// SeedProfile, if set, is copied into StateDir/vk_profile.json when that file
	// is absent, so the transport starts from a known browser identity rather
	// than generating a fresh one.
	SeedProfile string
	// SeedCaptchaFP, if set, is copied into StateDir/captcha_browser_fp when
	// absent.
	SeedCaptchaFP string

	// CaptchaTokenFile, if set, is watched for manually-solved captcha tokens.
	// Each new line is forwarded to the transport as CAPTCHA_RESULT|<token>.
	CaptchaTokenFile string

	// Timezone is the fixed UTC offset used for log timestamps, e.g. "UTC+3" or
	// "+03:00". Android ships no zoneinfo, so Go would otherwise log in UTC.
	// A fixed offset has no DST transitions — update it when the clocks change.
	// Empty means UTC.
	Timezone string

	// Mode selects how tunnelled traffic is exposed:
	//
	//   "vpn"   (default) the transport listens on Listen and qwdtt-cli brings up
	//           a WireGuard TUN against the emitted config. ZDT-D routes apps to
	//           the TUN via myvpn. This is the verified-on-device path.
	//   "socks" the transport runs WireGuard in a userspace netstack and serves
	//           SOCKS5 on SocksAddr — no TUN, no amneziawg-go, no awg. Route apps
	//           with ZDT-D's myproxy instead. Requires an upstream build with
	//           -mode support (our pin has it).
	//
	// SOCKS is TCP CONNECT only: app UDP traffic (QUIC, direct DNS) is NOT
	// carried and will go direct — which a whitelist ISP drops. Prefer "vpn"
	// unless you have verified this is acceptable.
	Mode string
	// SocksAddr is the local SOCKS5 listen address used when Mode is "socks".
	// Must be loopback. Default 127.0.0.1:1080.
	SocksAddr string

	// WgBringup enables the M2 WireGuard stage: once wg-turn.conf is written, the
	// supervisor spawns amneziawg-go, applies the config via awg, and brings up
	// the TUN. Disable it to run the transport alone (hash checks, config
	// generation, debugging). Default true.
	WgBringup bool
	// Tun is the interface name amneziawg-go creates. Root-created, so it carries
	// no Android VPN UI; ZDT-D's Zygisk layer hides it from target UIDs. Default
	// "zdtdqw0".
	Tun string
	// MTU overrides the MTU the transport emits in wg-turn.conf (1280). 0 keeps
	// the emitted value. Lower it toward 1200 if fragmentation appears under
	// sustained load — WireGuard inside RTP inside DTLS inside a TURN relay is a
	// deep stack. Valid range 576..9000 when set.
	MTU int
	// AwgGoBinary is the amneziawg-go userspace WireGuard daemon.
	AwgGoBinary string
	// AwgBinary is the awg control tool (wg-compatible) used for `setconf`.
	AwgBinary string
	// AwgRunDir is the working directory amneziawg-go is spawned in (where it
	// keeps its UAPI socket). Empty means StateDir/awg.
	AwgRunDir string
	// LinkWait bounds how long to wait for the TUN link to appear after spawning
	// amneziawg-go. Default 15s.
	LinkWait time.Duration
	// TunReadyWait bounds how long to wait for the TUN to carry its address after
	// configuration. Default 25s.
	TunReadyWait time.Duration

	// StartupDeadline bounds how long the supervisor waits for wg-turn.conf to
	// appear after a (re)start before treating the attempt as failed. Default 90s.
	StartupDeadline time.Duration
	// MaxRestarts bounds transport restarts before the supervisor gives up and
	// exits non-zero (so ZDT-D restarts the whole stack). 0 means unlimited.
	MaxRestarts int
	// RestartBackoff is the base delay between restart attempts. Default 5s.
	RestartBackoff time.Duration
	// WatchdogMinActive is the minimum steady-state active worker count below
	// which the supervisor restarts the transport. Requires the transport to emit
	// the STATS| marker (see M0 delta #2). 0 disables the check.
	WatchdogMinActive int
}

// Load reads and validates a config file at path.
func Load(path string) (*Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open config: %w", err)
	}
	defer f.Close()

	cfg := defaults()
	sc := bufio.NewScanner(f)
	ln := 0
	for sc.Scan() {
		ln++
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		key, val, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf("config line %d: expected key = value, got %q", ln, line)
		}
		key = strings.TrimSpace(key)
		val = strings.TrimSpace(val)
		if err := cfg.set(key, val); err != nil {
			return nil, fmt.Errorf("config line %d: %w", ln, err)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func defaults() *Config {
	return &Config{
		Listen:          "127.0.0.1:9000",
		Workers:         45,
		Obfs:            "video",
		VKAuth:          "anonymous",
		VKAnonPath:      "vkcalls",
		GoDNS:           "yandex",
		CaptchaMode:     "auto",
		DeviceID:        "unknown",
		StartupDeadline: 90 * time.Second,
		MaxRestarts:     10,
		RestartBackoff:  5 * time.Second,
		WgBringup:       true,
		Mode:            "vpn",
		SocksAddr:       "127.0.0.1:1080",
		Tun:             "zdtdqw0",
		AwgGoBinary:     "/data/adb/modules/ZDT-D/bin/amneziawg-go",
		AwgBinary:       "/data/adb/modules/ZDT-D/bin/awg",
		LinkWait:        15 * time.Second,
		TunReadyWait:    25 * time.Second,
	}
}

func (c *Config) set(key, val string) error {
	switch key {
	case "transport_binary":
		c.TransportBinary = val
	case "state_dir":
		c.StateDir = val
	case "peer":
		c.Peer = val
	case "listen":
		c.Listen = val
	case "vk_hashes", "hashes":
		c.Hashes = splitHashes(val)
	case "password":
		c.Password = val
	case "workers":
		n, err := strconv.Atoi(val)
		if err != nil {
			return fmt.Errorf("workers: %w", err)
		}
		c.Workers = n
	case "obfs":
		c.Obfs = val
	case "vk_auth":
		c.VKAuth = val
	case "vk_anon_path":
		c.VKAnonPath = val
	case "go_dns":
		c.GoDNS = val
	case "captcha_mode":
		c.CaptchaMode = val
	case "device_id":
		c.DeviceID = val
	case "seed_profile":
		c.SeedProfile = val
	case "seed_captcha_fp":
		c.SeedCaptchaFP = val
	case "captcha_token_file":
		c.CaptchaTokenFile = val
	case "timezone":
		c.Timezone = val
	case "mode":
		c.Mode = strings.ToLower(strings.TrimSpace(val))
	case "socks_addr", "socks":
		c.SocksAddr = val
	case "wg_bringup":
		b, err := parseBool(val)
		if err != nil {
			return fmt.Errorf("wg_bringup: %w", err)
		}
		c.WgBringup = b
	case "tun":
		c.Tun = val
	case "mtu":
		n, err := strconv.Atoi(val)
		if err != nil {
			return fmt.Errorf("mtu: %w", err)
		}
		c.MTU = n
	case "awg_go_binary":
		c.AwgGoBinary = val
	case "awg_binary":
		c.AwgBinary = val
	case "awg_run_dir":
		c.AwgRunDir = val
	case "link_wait":
		d, err := time.ParseDuration(val)
		if err != nil {
			return fmt.Errorf("link_wait: %w", err)
		}
		c.LinkWait = d
	case "tun_ready_wait":
		d, err := time.ParseDuration(val)
		if err != nil {
			return fmt.Errorf("tun_ready_wait: %w", err)
		}
		c.TunReadyWait = d
	case "startup_deadline":
		d, err := time.ParseDuration(val)
		if err != nil {
			return fmt.Errorf("startup_deadline: %w", err)
		}
		c.StartupDeadline = d
	case "max_restarts":
		n, err := strconv.Atoi(val)
		if err != nil {
			return fmt.Errorf("max_restarts: %w", err)
		}
		c.MaxRestarts = n
	case "restart_backoff":
		d, err := time.ParseDuration(val)
		if err != nil {
			return fmt.Errorf("restart_backoff: %w", err)
		}
		c.RestartBackoff = d
	case "watchdog_min_active":
		n, err := strconv.Atoi(val)
		if err != nil {
			return fmt.Errorf("watchdog_min_active: %w", err)
		}
		c.WatchdogMinActive = n
	default:
		return fmt.Errorf("unknown key %q", key)
	}
	return nil
}

// splitHashes splits on the same separators the transport's ParseHashes accepts
// and trims empties, so a value copied from anywhere (comma, space, newline)
// resolves the same way here and downstream.
func splitHashes(val string) []string {
	fields := strings.FieldsFunc(val, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\t'
	})
	out := make([]string, 0, len(fields))
	for _, f := range fields {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}

// Validate enforces the invariants that must hold before any process is spawned.
func (c *Config) Validate() error {
	if c.TransportBinary == "" {
		return fmt.Errorf("transport_binary is required")
	}
	if c.StateDir == "" {
		return fmt.Errorf("state_dir is required")
	}
	if c.Peer == "" {
		return fmt.Errorf("peer is required (VPS DTLS host:port)")
	}
	if _, _, err := net.SplitHostPort(c.Peer); err != nil {
		return fmt.Errorf("peer %q must be host:port: %w", c.Peer, err)
	}
	if len(c.Hashes) == 0 {
		return fmt.Errorf("at least one vk_hashes entry is required")
	}
	if c.Password == "" {
		// The transport derives its WRAP key from this; it will refuse to start
		// without it, and the encrypted app blob cannot be reused.
		return fmt.Errorf("password is required (plaintext tunnel password)")
	}
	host, _, err := net.SplitHostPort(c.Listen)
	if err != nil {
		return fmt.Errorf("listen %q must be host:port: %w", c.Listen, err)
	}
	if !isLoopback(host) {
		// A non-loopback local WG endpoint exposes the tunnel entry point and, on
		// a whitelist ISP, is a direct leak surface. Refuse it.
		return fmt.Errorf("listen host %q must be loopback (127.0.0.1 or ::1)", host)
	}
	switch c.VKAuth {
	case "anonymous", "account":
	default:
		return fmt.Errorf("vk_auth %q must be anonymous or account", c.VKAuth)
	}
	switch strings.ToLower(c.CaptchaMode) {
	case "auto", "rjs":
	case "wv":
		return fmt.Errorf("captcha_mode wv (WebView) is not usable headless; use auto or rjs plus captcha_token_file")
	default:
		return fmt.Errorf("captcha_mode %q must be auto or rjs", c.CaptchaMode)
	}
	switch c.Obfs {
	case "audio", "video":
	default:
		return fmt.Errorf("obfs %q must be audio or video", c.Obfs)
	}
	if c.Workers < 1 {
		return fmt.Errorf("workers must be >= 1")
	}
	switch c.Mode {
	case "vpn":
	case "socks":
		host, _, err := net.SplitHostPort(c.SocksAddr)
		if err != nil {
			return fmt.Errorf("socks_addr %q must be host:port: %w", c.SocksAddr, err)
		}
		if !isLoopback(host) {
			// A non-loopback SOCKS bind would expose the tunnel to the network.
			return fmt.Errorf("socks_addr host %q must be loopback (127.0.0.1 or ::1)", host)
		}
	default:
		return fmt.Errorf("mode %q must be vpn or socks", c.Mode)
	}
	if c.WgBringup && c.Mode == "vpn" {
		if !isValidIfname(c.Tun) {
			return fmt.Errorf("tun %q must be 1..15 chars (letters, digits, _.-) and not empty", c.Tun)
		}
		if c.MTU != 0 && (c.MTU < 576 || c.MTU > 9000) {
			return fmt.Errorf("mtu %d must be 0 (use the emitted value) or in range 576..9000", c.MTU)
		}
	}
	if _, err := logging.Location(c.Timezone); err != nil {
		return fmt.Errorf("timezone: %w", err)
	}
	return nil
}

// defaultAwgRunDir is the working directory amneziawg-go must be spawned in for
// the ZDT-D-built binaries. ZDT-D patches amneziawg-go's UAPI socket to the
// CWD-relative "run/amneziawg" and builds awg with a matching absolute
// RUNSTATEDIR, so the two only agree on the socket path when amneziawg-go's cwd
// is this directory. Overridable via awg_run_dir only if you ship your own build
// with different socket paths.
const defaultAwgRunDir = "/data/adb/modules/ZDT-D/working_folder/amneziawg"

// SocksMode reports whether the transport should expose a local SOCKS5 proxy
// instead of a WireGuard endpoint.
func (c *Config) SocksMode() bool { return c.Mode == "socks" }

// ShouldBringUpWireguard reports whether the supervisor owns a WireGuard TUN.
// SOCKS mode keeps WireGuard inside the transport's userspace netstack, so there
// is no interface for us to create.
func (c *Config) ShouldBringUpWireguard() bool { return c.WgBringup && !c.SocksMode() }

// AwgRunDirOr returns the configured amneziawg working directory, defaulting to
// the path the ZDT-D-built amneziawg-go/awg pair expect.
func (c *Config) AwgRunDirOr() string {
	if c.AwgRunDir != "" {
		return c.AwgRunDir
	}
	return defaultAwgRunDir
}

func parseBool(s string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "1", "true", "yes", "on":
		return true, nil
	case "0", "false", "no", "off":
		return false, nil
	default:
		return false, fmt.Errorf("expected a boolean, got %q", s)
	}
}

// isValidIfname mirrors the Linux interface-name constraints the amneziawg
// program relies on: non-empty, <= 15 bytes, no slash/space/control chars.
func isValidIfname(name string) bool {
	if name == "" || len(name) > 15 {
		return false
	}
	for _, r := range name {
		if !(r == '_' || r == '.' || r == '-' || (r >= 'a' && r <= 'z') ||
			(r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')) {
			return false
		}
	}
	return true
}

func isLoopback(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
