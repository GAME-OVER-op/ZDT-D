// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Package supervisor owns the qWDTT transport child process: it prepares the
// persistent state directory, validates VK hashes before launch, runs the
// transport under a restart loop with a watchdog, feeds manually-solved captcha
// tokens, and drives a clean stop on shutdown.
//
// One supervisor process owns the whole stack because ZDT-D gives no startup
// ordering between profiles: the WireGuard client is useless until the transport
// is live and wg-turn.conf exists, so both must share one restart path. (The
// amneziawg-go stage lands in M2 alongside runTransport.)
package supervisor

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/andycar/zdt-d/qwdtt-cli/internal/config"
	"github.com/andycar/zdt-d/qwdtt-cli/internal/transport"
)

const (
	configFile      = "wg-turn.conf"
	profileFile     = "vk_profile.json"
	captchaFPFile   = "captcha_browser_fp"
	stopGrace       = 8 * time.Second // after STOP/SIGTERM before SIGKILL
	configPollEvery = 500 * time.Millisecond
)

// Run prepares state, validates hashes, and supervises the transport until ctx
// is cancelled or restarts are exhausted. A non-nil return means the stack could
// not be kept alive and the caller should exit non-zero so ZDT-D restarts
// everything rather than leaving an orphaned TUN.
func Run(ctx context.Context, cfg *config.Config, logger *log.Logger) error {
	if err := prepareStateDir(cfg, logger); err != nil {
		return fmt.Errorf("prepare state dir: %w", err)
	}

	attempts := 0
	for {
		if ctx.Err() != nil {
			return nil // clean shutdown
		}

		// Re-validate on every attempt: a hash can die between check and launch,
		// and a dead hash strands every worker group behind it. Cheap insurance.
		hashes, err := validateHashes(ctx, cfg, logger)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("hash validation: %w", err)
		}
		if len(hashes) == 0 {
			return errors.New("no VK hashes passed validation; cannot start transport")
		}
		logger.Printf("validated %d/%d hashes usable", len(hashes), len(cfg.Hashes))

		res := runTransport(ctx, cfg, hashes, logger)
		if res.stopped {
			return nil // clean shutdown requested
		}

		attempts++
		if cfg.MaxRestarts > 0 && attempts > cfg.MaxRestarts {
			return fmt.Errorf("transport exceeded %d restarts; giving up", cfg.MaxRestarts)
		}

		backoff := cfg.RestartBackoff
		logger.Printf("transport ended (%s); restart %d in %s", res.reason(), attempts, backoff)
		select {
		case <-time.After(backoff):
		case <-ctx.Done():
			return nil
		}
	}
}

// prepareStateDir ensures the persistent working directory exists and seeds the
// browser identity files if the operator supplied seeds and the state dir lacks
// them. A stable identity across launches is an explicit invariant.
func prepareStateDir(cfg *config.Config, logger *log.Logger) error {
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		return err
	}
	seed := func(src, name string) error {
		if src == "" {
			return nil
		}
		dst := filepath.Join(cfg.StateDir, name)
		if _, err := os.Stat(dst); err == nil {
			return nil // already present — keep the rotated-in-place identity
		}
		data, err := os.ReadFile(src)
		if err != nil {
			return fmt.Errorf("read seed %s: %w", src, err)
		}
		if err := os.WriteFile(dst, data, 0o600); err != nil {
			return err
		}
		logger.Printf("seeded %s from %s", name, src)
		return nil
	}
	if err := seed(cfg.SeedProfile, profileFile); err != nil {
		return err
	}
	return seed(cfg.SeedCaptchaFP, captchaFPFile)
}

// validateHashes runs the transport in -check-hashes mode and returns the subset
// that reported status "ok".
func validateHashes(ctx context.Context, cfg *config.Config, logger *log.Logger) ([]string, error) {
	args := transport.Args(cfg, cfg.Hashes, true)
	cmd := exec.CommandContext(ctx, cfg.TransportBinary, args...)
	cmd.Dir = cfg.StateDir
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = logWriter{logger, "check/err"}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start check: %w", err)
	}

	var good []string
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		hc, ok := transport.ParseHashCheck(line)
		if !ok {
			continue
		}
		if hc.OK() {
			good = append(good, hc.Hash)
			logger.Printf("hash %d ok (%s)", hc.Index, hc.Message)
		} else {
			logger.Printf("hash %d dropped: status=%s %s", hc.Index, hc.Status, hc.Message)
		}
	}
	if err := cmd.Wait(); err != nil {
		// A non-zero exit from the check pass is not fatal on its own; what
		// matters is whether any hash reported ok.
		logger.Printf("hash-check process exited: %v", err)
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return good, nil
}

type runOutcome struct {
	stopped  bool // ended because parent ctx was cancelled (clean shutdown)
	watchdog bool // ended because the watchdog forced a restart
	exitErr  error
}

func (r runOutcome) reason() string {
	switch {
	case r.watchdog:
		return "watchdog"
	case r.exitErr != nil:
		return "exit: " + r.exitErr.Error()
	default:
		return "process exit"
	}
}

// runTransport runs a single transport lifetime. It returns when the child exits,
// the watchdog trips, or ctx is cancelled (clean stop).
func runTransport(ctx context.Context, cfg *config.Config, hashes []string, logger *log.Logger) runOutcome {
	// A stale config from a previous lifetime would fool the readiness watcher.
	_ = os.Remove(filepath.Join(cfg.StateDir, configFile))

	runCtx, runCancel := context.WithCancel(ctx)
	defer runCancel()

	args := transport.Args(cfg, hashes, false)
	cmd := exec.Command(cfg.TransportBinary, args...)
	cmd.Dir = cfg.StateDir

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return runOutcome{exitErr: fmt.Errorf("stdin pipe: %w", err)}
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return runOutcome{exitErr: fmt.Errorf("stdout pipe: %w", err)}
	}
	cmd.Stderr = logWriter{logger, "transport/err"}

	if err := cmd.Start(); err != nil {
		return runOutcome{exitErr: fmt.Errorf("start transport: %w", err)}
	}
	logger.Printf("transport started pid=%d (workers=%d, hashes=%d)", cmd.Process.Pid, cfg.Workers, len(hashes))

	var activeWorkers atomic.Int32
	activeWorkers.Store(-1) // -1 = unknown until the first STATS| marker

	var stdinMu sync.Mutex
	sendLine := func(s string) {
		stdinMu.Lock()
		defer stdinMu.Unlock()
		_, _ = io.WriteString(stdin, s+"\n")
	}

	// stdout marker reader.
	go readMarkers(stdout, &activeWorkers, logger)

	// captcha token feed.
	if cfg.CaptchaTokenFile != "" {
		go feedCaptchaTokens(runCtx, cfg.CaptchaTokenFile, sendLine, logger)
	}

	// watchdog: readiness gate + active-count enforcement.
	var watchdogTripped atomic.Bool
	go func() {
		if !awaitConfig(runCtx, cfg, logger) {
			return // ctx cancelled or run already ending
		}
		logger.Printf("%s written; transport ready", configFile)
		if cfg.WatchdogMinActive <= 0 {
			return
		}
		// Give workers a grace period to ramp before enforcing the floor.
		select {
		case <-time.After(cfg.StartupDeadline):
		case <-runCtx.Done():
			return
		}
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-runCtx.Done():
				return
			case <-ticker.C:
				a := activeWorkers.Load()
				if a >= 0 && int(a) < cfg.WatchdogMinActive {
					logger.Printf("watchdog: active=%d < min=%d; forcing restart", a, cfg.WatchdogMinActive)
					watchdogTripped.Store(true)
					runCancel()
					return
				}
			}
		}
	}()

	// Wait for the process in the background so we can select against ctx.
	waitErr := make(chan error, 1)
	go func() { waitErr <- cmd.Wait() }()

	select {
	case err := <-waitErr:
		// Child exited on its own.
		return runOutcome{exitErr: err, watchdog: watchdogTripped.Load(), stopped: false}
	case <-runCtx.Done():
		// Either a clean parent shutdown or a watchdog-forced restart. Both drive
		// the same graceful teardown; the transport releases its TURN allocations
		// on ctx cancel, so we must never SIGKILL first.
		stopped := ctx.Err() != nil && !watchdogTripped.Load()
		gracefulStop(cmd, sendLine, waitErr, logger)
		return runOutcome{stopped: stopped, watchdog: watchdogTripped.Load()}
	}
}

// gracefulStop asks the transport to stop cleanly (STOP on stdin, then SIGTERM),
// and escalates to SIGKILL only if it overstays the grace window.
func gracefulStop(cmd *exec.Cmd, sendLine func(string), waitErr <-chan error, logger *log.Logger) {
	sendLine("STOP")
	_ = cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-waitErr:
		return
	case <-time.After(stopGrace):
		logger.Printf("transport did not exit within %s; SIGKILL", stopGrace)
		_ = cmd.Process.Kill()
		<-waitErr
	}
}

// readMarkers consumes the transport's stdout, updating the active-worker gauge
// from STATS| markers and echoing everything for observability. Control decisions
// use only the structured markers, never the localized log text.
func readMarkers(r io.Reader, active *atomic.Int32, logger *log.Logger) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" {
			continue
		}
		if st, ok := transport.ParseStats(line); ok {
			active.Store(int32(st.Active))
		}
		logger.Printf("[transport] %s", line)
	}
}

// awaitConfig blocks until wg-turn.conf appears (non-empty) in the state dir, the
// startup deadline elapses, or the run context is cancelled. Watching the file is
// language-independent, unlike parsing the "[КОНФИГ] Сохранён" log line.
func awaitConfig(ctx context.Context, cfg *config.Config, logger *log.Logger) bool {
	path := filepath.Join(cfg.StateDir, configFile)
	deadline := time.NewTimer(cfg.StartupDeadline)
	defer deadline.Stop()
	ticker := time.NewTicker(configPollEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return false
		case <-deadline.C:
			logger.Printf("watchdog: %s not written within %s", configFile, cfg.StartupDeadline)
			return false
		case <-ticker.C:
			if fi, err := os.Stat(path); err == nil && fi.Size() > 0 {
				return true
			}
		}
	}
}

// feedCaptchaTokens tails a watched file; each newly-appended non-empty line is
// forwarded to the transport as CAPTCHA_RESULT|<token>. This is the manual
// token-feed path for headless operation, where no WebView fallback exists.
func feedCaptchaTokens(ctx context.Context, path string, sendLine func(string), logger *log.Logger) {
	var offset int64
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	// Start at end-of-file: only tokens written after startup are acted on.
	if fi, err := os.Stat(path); err == nil {
		offset = fi.Size()
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		fi, err := os.Stat(path)
		if err != nil {
			continue // file may not exist yet
		}
		if fi.Size() < offset {
			offset = 0 // truncated/rotated — reread from the top
		}
		if fi.Size() == offset {
			continue
		}
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			f.Close()
			continue
		}
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			token := strings.TrimSpace(sc.Text())
			if token == "" {
				continue
			}
			sendLine("CAPTCHA_RESULT|" + token)
			logger.Printf("captcha token forwarded from %s", filepath.Base(path))
		}
		offset = fi.Size()
		f.Close()
	}
}

// logWriter adapts a *log.Logger to io.Writer, prefixing each write so child
// stderr is distinguishable in the combined log.
type logWriter struct {
	logger *log.Logger
	prefix string
}

func (w logWriter) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line != "" {
			w.logger.Printf("[%s] %s", w.prefix, line)
		}
	}
	return len(p), nil
}
