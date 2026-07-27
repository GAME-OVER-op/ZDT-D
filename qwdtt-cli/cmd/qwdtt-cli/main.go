// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Command qwdtt-cli runs the qWDTT WireGuard-over-VK-TURN transport headless,
// without the qWDTT Android app. It is a single root-side supervisor process: it
// validates VK hashes, spawns the upstream go_client transport, waits for
// wg-turn.conf, and (from M2) brings up the WireGuard interface. ZDT-D owns
// routing on top.
package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/andycar/zdt-d/qwdtt-cli/internal/config"
	"github.com/andycar/zdt-d/qwdtt-cli/internal/logging"
	"github.com/andycar/zdt-d/qwdtt-cli/internal/supervisor"
)

func main() {
	configPath := flag.String("config", "/data/adb/ZDT-D/etc/qwdtt.conf", "path to qwdtt.conf")
	flag.Parse()

	// No prefix and second-resolution time: each line is stamped once, here. Child
	// output arrives already timestamped and is stripped before forwarding.
	logger := log.New(os.Stderr, "", log.LstdFlags)

	cfg, err := config.Load(*configPath)
	if err != nil {
		logger.Printf("config error: %v", err)
		os.Exit(2)
	}

	// Apply the configured wall-clock offset before any further timestamps: Go
	// finds no zoneinfo on Android and would otherwise log everything in UTC.
	// Already validated by config.Load, so this cannot fail here.
	zone, err := logging.SetLocal(cfg.Timezone)
	if err != nil {
		logger.Printf("timezone error: %v", err)
		os.Exit(2)
	}
	logger.Printf("qwdtt-cli starting (timezone %s)", zone)

	// SIGTERM/SIGINT drives a clean stop: the transport releases its TURN
	// allocations on context cancel. A second signal is left to escalate through
	// the OS default so a wedged shutdown can still be forced.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	if err := supervisor.Run(ctx, cfg, logger); err != nil {
		// Non-zero exit on transport death so ZDT-D restarts the whole stack
		// rather than leaving an orphaned TUN pointed at a dead loopback port.
		logger.Printf("fatal: %v", err)
		os.Exit(1)
	}
	logger.Printf("clean shutdown")
}
