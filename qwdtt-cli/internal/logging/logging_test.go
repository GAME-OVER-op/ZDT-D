// SPDX-License-Identifier: GPL-3.0-or-later

package logging

import "testing"

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

func TestSetupLocalTimeHonoursTZ(t *testing.T) {
	t.Setenv("TZ", "Europe/Kyiv")
	if got := SetupLocalTime(); got != "Europe/Kyiv" {
		t.Errorf("SetupLocalTime() = %q, want Europe/Kyiv", got)
	}
}
