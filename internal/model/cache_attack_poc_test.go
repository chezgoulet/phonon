package model

// Proof-of-concept for the blocking code-review finding on
// feature/cache-path-containment (openFileForDownload symlink truncation).
//
// Original attack: a symlink planted at the models-dir download destination
// was FOLLOWED by the O_CREATE|O_TRUNC|O_APPEND open, so the attacker-chosen
// outside target was truncated to zero bytes BEFORE the post-open
// EvalSymlinks containment check ran and refused the streamed body.
// Reproduced pre-fix: victim went 29 -> 0 bytes.
//
// This PoC replays that exact attack against the fixed code and asserts the
// victim file is byte-for-byte UNCHANGED after the failed call. Run with:
//
//	go test ./internal/model -run TestPoC_OriginalTruncationAttack -v

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestPoC_OriginalTruncationAttack(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "cacheroot")
	victim := filepath.Join(base, "victim-on-host.txt")

	sentinel := []byte(victimSentinel) // 29 bytes, as in the original repro
	if err := os.WriteFile(victim, sentinel, 0o644); err != nil {
		t.Fatal(err)
	}
	if len(sentinel) != 29 {
		t.Fatalf("sentinel must be 29 bytes like the original repro, got %d", len(sentinel))
	}

	cache := NewCache(root, nil)
	if err := cache.Init(); err != nil {
		t.Fatalf("Init: %v", err)
	}

	dest := filepath.Join(root, cacheModelsDir, "innocent-model.gguf")
	if err := os.Symlink(victim, dest); err != nil {
		t.Skipf("symlinks unavailable on this platform: %v", err)
	}

	srv := containmentTestServer(t, []byte("ATTACKER-CONTROLLED-MODEL-BODY"))

	fmt.Println("== PoC: planted-symlink truncation attack vs fixed code ==")
	fmt.Printf("cache root : %s\n", root)
	fmt.Printf("dest       : %s  (symlink -> %s)\n", dest, victim)

	before, err := os.ReadFile(victim)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Printf("victim BEFORE attack: %d bytes, content=%q\n", len(before), before)

	err = cache.downloadOnce(context.Background(), srv.URL, dest, "")
	fmt.Printf("downloadOnce error  : %v\n", err)

	after, rerr := os.ReadFile(victim)
	if rerr != nil {
		t.Fatalf("victim unreadable after failed download: %v", rerr)
	}
	fmt.Printf("victim AFTER attack : %d bytes, content=%q\n", len(after), after)

	switch {
	case err == nil:
		fmt.Println("RESULT: FAIL — download succeeded through the planted symlink")
		t.Fatal("download succeeded through a planted symlink — containment bypass")
	case string(after) != string(before):
		fmt.Println("RESULT: FAIL — victim file was truncated/modified despite refused body")
		t.Fatalf("victim modified: %d -> %d bytes", len(before), len(after))
	default:
		fmt.Println("RESULT: PASS — containment error returned AND victim unchanged (0 bytes escaped)")
	}
}
