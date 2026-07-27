// SPDX-License-Identifier: GPL-3.0-or-later
//
// qwdtt-cli — headless qWDTT transport supervisor.
// Copyright (C) 2026 the ZDT-D authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation, either version 3 of the License, or (at your option)
// any later version.

// Package logging sets up the supervisor's log output: a configured wall-clock
// offset, and helpers to forward child-process output without duplicating
// timestamps.
package logging

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// childTimestamp matches the leading "2006/01/02 15:04:05.000000 " stamp that Go's
// standard logger writes — the transport uses it, so its lines arrive already
// timestamped. We strip it and let the supervisor's own clock stamp the line
// once, in the configured zone.
var childTimestamp = regexp.MustCompile(`^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s+`)

// StripChildTimestamp removes a leading Go-logger timestamp from a child line.
// Lines without one are returned unchanged.
func StripChildTimestamp(line string) string {
	return strings.TrimSpace(childTimestamp.ReplaceAllString(line, ""))
}

// offsetPattern accepts an optional UTC/GMT prefix, a sign, hours, and optional
// minutes as ":MM" or "MM".
var offsetPattern = regexp.MustCompile(`^(?:UTC|GMT)?([+-])(\d{1,2})(?::?(\d{2}))?$`)

// Location turns a configured timezone string into a fixed-offset location.
//
// Android carries no /etc/localtime or /usr/share/zoneinfo, so Go resolves every
// timestamp as UTC and IANA names like "Europe/Kyiv" cannot be loaded without
// embedding the whole tzdata database. A fixed offset from the config keeps the
// binary small and the behaviour predictable.
//
// Accepted: "" or "UTC" (UTC), "UTC+3", "+3", "+03:00", "-0530", "GMT-5".
// Note this is a fixed offset with no DST transitions: in a zone that observes
// DST, update the config when the clocks change.
func Location(tz string) (*time.Location, error) {
	tz = strings.ToUpper(strings.TrimSpace(tz))
	if tz == "" || tz == "UTC" || tz == "GMT" || tz == "Z" {
		return time.UTC, nil
	}

	m := offsetPattern.FindStringSubmatch(tz)
	if m == nil {
		return nil, fmt.Errorf("want an offset like UTC+3, +03:00 or -0530, got %q", tz)
	}

	hours, err := strconv.Atoi(m[2])
	if err != nil {
		return nil, fmt.Errorf("bad hour in %q: %w", tz, err)
	}
	minutes := 0
	if m[3] != "" {
		if minutes, err = strconv.Atoi(m[3]); err != nil {
			return nil, fmt.Errorf("bad minutes in %q: %w", tz, err)
		}
	}
	if minutes > 59 {
		return nil, fmt.Errorf("minutes out of range in %q", tz)
	}

	total := hours*3600 + minutes*60
	if m[1] == "-" {
		total = -total
	}
	// Real-world UTC offsets span UTC-12:00 to UTC+14:00.
	if total < -12*3600 || total > 14*3600 {
		return nil, fmt.Errorf("offset out of range in %q (UTC-12:00..UTC+14:00)", tz)
	}

	return time.FixedZone(offsetName(total), total), nil
}

// offsetName renders a canonical "UTC+03:00" style zone name.
func offsetName(seconds int) string {
	sign := "+"
	if seconds < 0 {
		sign = "-"
		seconds = -seconds
	}
	return fmt.Sprintf("UTC%s%02d:%02d", sign, seconds/3600, (seconds%3600)/60)
}

// SetLocal applies the configured timezone to time.Local so every log timestamp
// uses it. Returns the zone name in use, for a one-line startup log.
func SetLocal(tz string) (string, error) {
	loc, err := Location(tz)
	if err != nil {
		return "", err
	}
	time.Local = loc
	return loc.String(), nil
}
