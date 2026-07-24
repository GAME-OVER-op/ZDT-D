// SPDX-License-Identifier: GPL-3.0-or-later

package transport

import (
	"testing"

	"github.com/andycar/zdt-d/qwdtt-cli/internal/config"
)

func TestParseHashCheck(t *testing.T) {
	tests := []struct {
		line     string
		wantOK   bool
		wantHC   HashCheck
		wantGood bool // HashCheck.OK()
		wantTerm bool // HashCheck.Terminal()
	}{
		{
			line:     "HASH_CHECK|1|abcd1234|ok|TURN urls=3",
			wantOK:   true,
			wantHC:   HashCheck{Index: 1, Hash: "abcd1234", Status: "ok", Message: "TURN urls=3"},
			wantGood: true,
			wantTerm: false,
		},
		{
			line:     "HASH_CHECK|2|deadbeef|dead|Звонок не найден или закрыт",
			wantOK:   true,
			wantHC:   HashCheck{Index: 2, Hash: "deadbeef", Status: "dead", Message: "Звонок не найден или закрыт"},
			wantGood: false,
			wantTerm: true,
		},
		{
			// Empty trailing message.
			line:   "HASH_CHECK|3|feed|captcha|",
			wantOK: true,
			wantHC: HashCheck{Index: 3, Hash: "feed", Status: "captcha", Message: ""},
		},
		{line: "HASH_CHECK_START|1|abcd", wantOK: false},
		{line: "[КЛИЕНТ] что-то по-русски", wantOK: false},
		{line: "HASH_CHECK|notanint|x|ok", wantOK: false},
	}
	for _, tt := range tests {
		hc, ok := ParseHashCheck(tt.line)
		if ok != tt.wantOK {
			t.Errorf("ParseHashCheck(%q) ok=%v, want %v", tt.line, ok, tt.wantOK)
			continue
		}
		if !ok {
			continue
		}
		if hc != tt.wantHC {
			t.Errorf("ParseHashCheck(%q) = %+v, want %+v", tt.line, hc, tt.wantHC)
		}
		if hc.OK() != tt.wantGood {
			t.Errorf("%q OK()=%v, want %v", tt.line, hc.OK(), tt.wantGood)
		}
		if tt.wantHC.Status != "" && hc.Terminal() != tt.wantTerm && tt.line != "HASH_CHECK|3|feed|captcha|" {
			t.Errorf("%q Terminal()=%v, want %v", tt.line, hc.Terminal(), tt.wantTerm)
		}
	}
}

func TestParseStats(t *testing.T) {
	st, ok := ParseStats("STATS|41|1048576|2097152")
	if !ok {
		t.Fatal("ParseStats returned ok=false for valid line")
	}
	if st.Active != 41 || st.BytesUp != 1048576 || st.BytesDown != 2097152 {
		t.Errorf("ParseStats = %+v", st)
	}
	if _, ok := ParseStats("STATS|41|1048576"); ok {
		t.Error("ParseStats accepted a short line")
	}
	if _, ok := ParseStats("[СТАТИСТИКА] Активных: 41"); ok {
		t.Error("ParseStats accepted a localized log line")
	}
}

func TestArgs(t *testing.T) {
	cfg := &config.Config{
		Peer: "203.0.113.10:56000", Listen: "127.0.0.1:9000", Password: "pw",
		Workers: 45, Obfs: "video", VKAuth: "anonymous", VKAnonPath: "vkcalls",
		GoDNS: "yandex", CaptchaMode: "auto", DeviceID: "abc",
	}
	got := Args(cfg, []string{"h1", "h2"}, false)
	if !contains(got, "-vk", "h1,h2") {
		t.Errorf("Args missing joined -vk: %v", got)
	}
	if !contains(got, "-n", "45") {
		t.Errorf("Args missing -n 45: %v", got)
	}
	if hasFlag(got, "-check-hashes") {
		t.Errorf("Args(checkHashes=false) should not include -check-hashes: %v", got)
	}
	check := Args(cfg, []string{"h1"}, true)
	if !hasFlag(check, "-check-hashes") {
		t.Errorf("Args(checkHashes=true) missing -check-hashes: %v", check)
	}
}

func contains(args []string, flag, val string) bool {
	for i := 0; i+1 < len(args); i++ {
		if args[i] == flag && args[i+1] == val {
			return true
		}
	}
	return false
}

func hasFlag(args []string, flag string) bool {
	for _, a := range args {
		if a == flag {
			return true
		}
	}
	return false
}
