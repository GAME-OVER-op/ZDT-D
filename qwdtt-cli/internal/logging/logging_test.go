// SPDX-License-Identifier: GPL-3.0-or-later

package logging

import (
	"testing"
	"time"
)

func TestStripChildTimestamp(t *testing.T) {
	cases := map[string]string{
		// The transport's own log format (date + micros), as seen on-device.
		"2026/07/26 14:23:50.084869 [КЛИЕНТ] VK auth mode: anonymous": "[КЛИЕНТ] VK auth mode: anonymous",
		// Second-resolution variant.
		"2026/07/26 14:23:50 [ГРУППА #1] Запрос кредов": "[ГРУППА #1] Запрос кредов",
		// Structured markers must survive untouched.
		"STATS|9|0|0":                      "STATS|9|0|0",
		"HASH_CHECK|1|abcd|ok|TURN urls=2": "HASH_CHECK|1|abcd|ok|TURN urls=2",
		// A line with no timestamp is only trimmed.
		"  plain line  ": "plain line",
		// A timestamp-looking string mid-line must not be stripped.
		"prefix 2026/07/26 14:23:50 tail": "prefix 2026/07/26 14:23:50 tail",
	}
	for in, want := range cases {
		if got := StripChildTimestamp(in); got != want {
			t.Errorf("StripChildTimestamp(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestLocation(t *testing.T) {
	ok := map[string]struct {
		name    string
		seconds int
	}{
		"":          {"UTC", 0},
		"UTC":       {"UTC", 0},
		"utc+3":     {"UTC+03:00", 3 * 3600},
		"+3":        {"UTC+03:00", 3 * 3600},
		"+03:00":    {"UTC+03:00", 3 * 3600},
		"+0330":     {"UTC+03:30", 3*3600 + 30*60},
		"-5":        {"UTC-05:00", -5 * 3600},
		"GMT-05:30": {"UTC-05:30", -(5*3600 + 30*60)},
		"+14:00":    {"UTC+14:00", 14 * 3600},
	}
	for in, want := range ok {
		loc, err := Location(in)
		if err != nil {
			t.Errorf("Location(%q) unexpected error: %v", in, err)
			continue
		}
		if loc.String() != want.name {
			t.Errorf("Location(%q) name = %q, want %q", in, loc.String(), want.name)
		}
		if _, off := time.Now().In(loc).Zone(); off != want.seconds {
			t.Errorf("Location(%q) offset = %ds, want %ds", in, off, want.seconds)
		}
	}

	for _, bad := range []string{"Europe/Kyiv", "+15", "-13", "abc", "+3:75", "++3"} {
		if _, err := Location(bad); err == nil {
			t.Errorf("Location(%q) expected an error, got nil", bad)
		}
	}
}
