// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

package wg

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	setconfFile  = "setconf.conf"
	goLogFile    = "amneziawg-go.log"
	pollInterval = 300 * time.Millisecond
	cmdTimeout   = 10 * time.Second
	ipTimeout    = 4 * time.Second
	defaultMTU   = 1280
)

// Params configures a bring-up. It mirrors the fields the fork's amneziawg
// program uses to drive the same binaries.
type Params struct {
	AwgGoBinary  string
	AwgBinary    string
	Tun          string
	RunDir       string
	LinkWait     time.Duration
	TunReadyWait time.Duration
}

// Interface is a live amneziawg-go TUN owned by the supervisor. Down tears it
// back down.
type Interface struct {
	tun         string
	awgGoBinary string
	runDir      string
	cmd         *exec.Cmd
	logger      *log.Logger
}

// Up brings the WireGuard interface up from a split config: it spawns
// amneziawg-go, applies the WireGuard body via `awg setconf`, and configures the
// TUN address and MTU with `ip`. It intentionally installs no routes and binds no
// UIDs — routing is ZDT-D's job (M3). The interface is created by root, so it
// carries no Android VPN UI.
func Up(p Params, split SetconfConfig, logger *log.Logger) (*Interface, error) {
	if err := checkBinary(p.AwgGoBinary); err != nil {
		return nil, fmt.Errorf("amneziawg-go: %w", err)
	}
	if err := checkBinary(p.AwgBinary); err != nil {
		return nil, fmt.Errorf("awg: %w", err)
	}
	if p.LinkWait <= 0 {
		p.LinkWait = 15 * time.Second
	}
	if p.TunReadyWait <= 0 {
		p.TunReadyWait = 25 * time.Second
	}

	iface := &Interface{tun: p.Tun, awgGoBinary: p.AwgGoBinary, runDir: p.RunDir, logger: logger}

	// Clear any stale interface/process from a previous lifetime before spawning.
	iface.teardownInterface()

	if err := os.MkdirAll(p.RunDir, 0o700); err != nil {
		return nil, fmt.Errorf("create run dir: %w", err)
	}
	// amneziawg-go opens its UAPI socket at the CWD-relative "run/amneziawg"
	// (a ZDT-D build patch) and does not create the parent dirs itself. awg's
	// compiled-in RUNSTATEDIR points at the same absolute location, so this must
	// exist under RunDir before spawn or setconf cannot reach the interface.
	if err := os.MkdirAll(filepath.Join(p.RunDir, "run", "amneziawg"), 0o700); err != nil {
		return nil, fmt.Errorf("create uapi socket dir: %w", err)
	}
	setconfPath := filepath.Join(p.RunDir, setconfFile)
	if err := writeFileAtomic(setconfPath, []byte(split.Setconf), 0o600); err != nil {
		return nil, fmt.Errorf("write setconf: %w", err)
	}

	if err := iface.spawn(p); err != nil {
		return nil, err
	}
	if err := iface.waitLink(p.LinkWait); err != nil {
		iface.Down()
		return nil, err
	}
	if err := iface.setconf(p, setconfPath); err != nil {
		iface.Down()
		return nil, err
	}
	if err := iface.applyInterface(split); err != nil {
		iface.Down()
		return nil, err
	}
	if err := iface.waitTunReady(p.TunReadyWait); err != nil {
		iface.Down()
		return nil, err
	}
	logger.Printf("wg: interface %s up (addresses=%s mtu=%d)", p.Tun,
		strings.Join(split.Addresses, ","), mtuOr(split.MTU))
	return iface, nil
}

func (i *Interface) spawn(p Params) error {
	logPath := filepath.Join(p.RunDir, goLogFile)
	logf, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("open %s: %w", logPath, err)
	}
	// amneziawg-go -f keeps the daemon in the foreground; we own its lifecycle and
	// tear it down in Down(), so it is not tied to a command context.
	cmd := exec.Command(p.AwgGoBinary, "-f", p.Tun)
	cmd.Dir = p.RunDir
	cmd.Env = append(os.Environ(), "WG_PROCESS_FOREGROUND=1", "LOG_LEVEL=error")
	cmd.Stdin = nil
	cmd.Stdout = logf
	cmd.Stderr = logf
	// Deliberately NOT a new session: amneziawg-go stays in the supervisor's
	// process group. ZDT-D's myprogram stops us with `kill -15 -- -<pgid>` then a
	// 300ms-later `kill -9 -- -<pgid>` — a group kill. Keeping the daemon in-group
	// means that group kill reaps it and its non-persistent TUN auto-removes, so
	// no orphaned interface survives even if the supervisor is SIGKILLed before
	// its own teardown finishes. We still signal it by PID for internal restarts.

	if err := cmd.Start(); err != nil {
		logf.Close()
		return fmt.Errorf("spawn amneziawg-go: %w", err)
	}
	logf.Close() // the child holds its own dup'd fd
	i.cmd = cmd
	i.logger.Printf("wg: spawned amneziawg-go pid=%d tun=%s log=%s", cmd.Process.Pid, p.Tun, logPath)
	return nil
}

func (i *Interface) setconf(p Params, setconfPath string) error {
	// A single endpoint-resolution retry matches the fork; our Endpoint is
	// loopback so resolution never actually runs.
	code, out, err := runCmdEnv(cmdTimeout, []string{"WG_ENDPOINT_RESOLUTION_RETRIES=1"},
		p.AwgBinary, "setconf", p.Tun, setconfPath)
	if err != nil {
		return fmt.Errorf("awg setconf: %w", err)
	}
	if code != 0 {
		return fmt.Errorf("awg setconf rc=%d: %s", code, strings.TrimSpace(out))
	}
	return nil
}

func (i *Interface) applyInterface(split SetconfConfig) error {
	for _, addr := range split.Addresses {
		code, out, err := runCmd(ipTimeout, "ip", "addr", "add", addr, "dev", i.tun)
		if err != nil {
			return fmt.Errorf("ip addr add %s: %w", addr, err)
		}
		if code != 0 && !strings.Contains(strings.ToLower(out), "file exists") {
			return fmt.Errorf("ip addr add %s rc=%d: %s", addr, code, strings.TrimSpace(out))
		}
	}
	mtu := strconv.Itoa(mtuOr(split.MTU))
	code, out, err := runCmd(ipTimeout, "ip", "link", "set", "dev", i.tun, "mtu", mtu, "up")
	if err != nil {
		return fmt.Errorf("ip link set up: %w", err)
	}
	if code != 0 {
		return fmt.Errorf("ip link set up rc=%d: %s", code, strings.TrimSpace(out))
	}
	return nil
}

func (i *Interface) waitLink(within time.Duration) error {
	deadline := time.Now().Add(within)
	for time.Now().Before(deadline) {
		if code, _, err := runCmd(ipTimeout, "ip", "link", "show", "dev", i.tun); err == nil && code == 0 {
			return nil
		}
		time.Sleep(pollInterval)
	}
	return fmt.Errorf("tun link %s not created within %s", i.tun, within)
}

func (i *Interface) waitTunReady(within time.Duration) error {
	deadline := time.Now().Add(within)
	for time.Now().Before(deadline) {
		code, out, err := runCmd(ipTimeout, "ip", "-o", "-4", "addr", "show", "dev", i.tun)
		if err == nil && code == 0 && strings.Contains(out, "inet ") {
			return nil
		}
		time.Sleep(pollInterval)
	}
	return fmt.Errorf("tun %s did not become ready within %s", i.tun, within)
}

// Down tears the interface down: it kills amneziawg-go and deletes the link. Safe
// to call more than once.
func (i *Interface) Down() {
	if i.cmd != nil && i.cmd.Process != nil {
		_ = i.cmd.Process.Signal(syscall.SIGTERM)
		done := make(chan struct{})
		go func() { _, _ = i.cmd.Process.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			_ = i.cmd.Process.Kill()
		}
		i.cmd = nil
	}
	i.teardownInterface()
	i.logger.Printf("wg: interface %s down", i.tun)
}

// teardownInterface removes the link and reaps any stray amneziawg-go for this
// TUN (a stale process from a crashed prior lifetime).
func (i *Interface) teardownInterface() {
	_, _, _ = runCmd(ipTimeout, "ip", "link", "del", i.tun)
	pattern := fmt.Sprintf("%s -f %s", i.awgGoBinary, i.tun)
	_, _, _ = runCmd(cmdTimeout, "pkill", "-f", pattern)
}

func checkBinary(path string) error {
	fi, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("not found at %s: %w", path, err)
	}
	if fi.IsDir() || fi.Mode()&0o111 == 0 {
		return fmt.Errorf("%s is not an executable", path)
	}
	return nil
}

func mtuOr(m int) int {
	if m <= 0 {
		return defaultMTU
	}
	return m
}

func runCmd(timeout time.Duration, name string, args ...string) (int, string, error) {
	return runCmdEnv(timeout, nil, name, args...)
}

func runCmdEnv(timeout time.Duration, extraEnv []string, name string, args ...string) (int, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	if len(extraEnv) > 0 {
		cmd.Env = append(os.Environ(), extraEnv...)
	}
	out, err := cmd.CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return -1, string(out), fmt.Errorf("timeout after %s", timeout)
	}
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok {
			return ee.ExitCode(), string(out), nil
		}
		return -1, string(out), err
	}
	return 0, string(out), nil
}

func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
