// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Package logging sets up the supervisor's log output: a local-time clock that
// works on Android, and helpers to forward child-process output without
// duplicating timestamps.
package logging

import (
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"

	// Embed the IANA timezone database. Android has no /etc/localtime and no
	// /usr/share/zoneinfo, so without this every timestamp falls back to UTC.
	_ "time/tzdata"
)

// childTimestamp matches the leading "2006/01/02 15:04:05.000000 " stamp that Go's
// standard logger writes — the transport uses it, so its lines arrive already
// timestamped. We strip it and let the supervisor's own clock stamp the line
// once, in local time.
var childTimestamp = regexp.MustCompile(`^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+`)

// StripChildTimestamp removes a leading Go-logger timestamp from a child line.
// Lines without one are returned unchanged.
func StripChildTimestamp(line string) string {
	return strings.TrimSpace(childTimestamp.ReplaceAllString(line, ""))
}

// SetupLocalTime points time.Local at the device's timezone so log timestamps
// match the user's wall clock. Resolution order:
//
//  1. $TZ, if set (standard override, honoured on every platform);
//  2. Android's persist.sys.timezone system property, via getprop;
//  3. whatever Go already resolved (UTC on Android).
//
// It returns the zone name in use, for a one-line startup log.
func SetupLocalTime() string {
	if name := strings.TrimSpace(os.Getenv("TZ")); name != "" {
		if loc, err := time.LoadLocation(name); err == nil {
			time.Local = loc
			return name
		}
	}
	if name := androidTimezone(); name != "" {
		if loc, err := time.LoadLocation(name); err == nil {
			time.Local = loc
			return name
		}
	}
	return time.Local.String()
}

// androidTimezone reads persist.sys.timezone (e.g. "Europe/Kyiv"). Returns an
// empty string off Android or when the property is unset.
func androidTimezone() string {
	cmd := exec.Command("getprop", "persist.sys.timezone")
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}
