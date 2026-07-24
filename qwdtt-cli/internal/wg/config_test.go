// SPDX-License-Identifier: GPL-3.0-or-later

package wg

import (
	"strings"
	"testing"
)

// The exact shape the transport emits (from a captured wg-turn.conf).
const emitted = `[Interface]
PrivateKey = aPrivateKeyBase64PlaceholderAAAAAAAAAAAAAAAAA=
Address = 10.66.0.1/32
DNS = 8.8.8.8
MTU = 1280

[Peer]
PublicKey = aPublicKeyBase64PlaceholderAAAAAAAAAAAAAAAAAA=
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:9000
PersistentKeepalive = 25
`

func TestSplitConfig(t *testing.T) {
	got, err := SplitConfig(emitted)
	if err != nil {
		t.Fatalf("SplitConfig error: %v", err)
	}

	if len(got.Addresses) != 1 || got.Addresses[0] != "10.66.0.1/32" {
		t.Errorf("Addresses = %v, want [10.66.0.1/32]", got.Addresses)
	}
	if got.MTU != 1280 {
		t.Errorf("MTU = %d, want 1280", got.MTU)
	}
	if len(got.DNS) != 1 || got.DNS[0] != "8.8.8.8" {
		t.Errorf("DNS = %v, want [8.8.8.8]", got.DNS)
	}

	// setconf body must NOT contain the interface-only keys awg rejects...
	for _, banned := range []string{"Address", "DNS", "MTU"} {
		if strings.Contains(got.Setconf, banned) {
			t.Errorf("Setconf must not contain %q:\n%s", banned, got.Setconf)
		}
	}
	// ...but must keep the WireGuard essentials and the whole [Peer].
	for _, want := range []string{"[Interface]", "PrivateKey =", "[Peer]", "PublicKey =",
		"AllowedIPs = 0.0.0.0/0", "Endpoint = 127.0.0.1:9000", "PersistentKeepalive = 25"} {
		if !strings.Contains(got.Setconf, want) {
			t.Errorf("Setconf missing %q:\n%s", want, got.Setconf)
		}
	}
}

func TestSplitConfigPreservesAmneziaAndListenPort(t *testing.T) {
	in := `[Interface]
PrivateKey = k
ListenPort = 51820
Jc = 4
S1 = 50
Address = 10.0.0.2/32
MTU = 1420

[Peer]
PublicKey = p
Endpoint = 1.2.3.4:51820
AllowedIPs = 0.0.0.0/0
`
	got, err := SplitConfig(in)
	if err != nil {
		t.Fatalf("SplitConfig error: %v", err)
	}
	for _, want := range []string{"ListenPort = 51820", "Jc = 4", "S1 = 50"} {
		if !strings.Contains(got.Setconf, want) {
			t.Errorf("Setconf should preserve %q:\n%s", want, got.Setconf)
		}
	}
	if got.MTU != 1420 {
		t.Errorf("MTU = %d, want 1420", got.MTU)
	}
}

func TestSplitConfigErrors(t *testing.T) {
	cases := map[string]string{
		"missing peer":        "[Interface]\nPrivateKey = k\nAddress = 10.0.0.1/32\n",
		"missing private key": "[Interface]\nAddress = 10.0.0.1/32\n[Peer]\nPublicKey = p\n",
		"missing address":     "[Interface]\nPrivateKey = k\n[Peer]\nPublicKey = p\n",
		"bad mtu":             "[Interface]\nPrivateKey = k\nAddress = 10.0.0.1/32\nMTU = notanumber\n[Peer]\nPublicKey = p\n",
	}
	for name, cfg := range cases {
		if _, err := SplitConfig(cfg); err == nil {
			t.Errorf("%s: expected error, got nil", name)
		}
	}
}
