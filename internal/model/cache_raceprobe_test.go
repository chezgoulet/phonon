package model

// PERMANENT adversarial regression probes (adopted from round-2 review —
// they empirically confirmed the check-then-open/check-then-rename races of
// the round-1 fix with a microsecond dir-swap toggler, and now pin the
// pinned-directory-fd redesign):
//
//   - TestProbe_OpenDirSwapRace_TruncatesAndDeletesVictim: a fast
//     real-dir<->symlink toggle on the models dir must NEVER let the
//     download truncate/delete an outside victim (pre-fix: 3 hits/4000
//     trials). Containment now comes from openat(rootFd, "models",
//     O_NOFOLLOW|O_DIRECTORY) + bare-filename ops on the pinned fd.
//   - TestProbe_RenameRace_GetEscapesRoot: Get()'s promote must never land
//     the model outside the cache root even while the models dir is being
//     swapped under it (renameat between pinned dirfds).
//   - TestProbe_FinalComponentSwapCoveredByNoFollow: control — O_NOFOLLOW
//     still holds the final component.
//
// If any probe scores victim hits/escapes, the containment design is
// wrong: fix the design, do NOT weaken these probes.

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// busySleep spins (no scheduler sleep) for ~d.
func busySleep(d time.Duration) {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
	}
}

type toggler struct {
	stop  chan struct{}
	done  chan struct{}
	flips int
	evils int // periods spent in evil state
	reals int
}

func startToggler(modelsDir, evilModels string, realDur, evilDur time.Duration) *toggler {
	tg := &toggler{stop: make(chan struct{}), done: make(chan struct{})}
	go func() {
		defer close(tg.done)
		inReal := false
		_ = os.MkdirAll(modelsDir, 0o755)
		inReal = true
		for {
			select {
			case <-tg.stop:
				return
			default:
			}
			if inReal {
				busySleep(realDur)
				os.RemoveAll(modelsDir) // models holds .names since #314: bare Remove would ENOTEMPTY and stall the toggle
				os.Symlink(evilModels, modelsDir)
				inReal = false
				tg.flips++
			} else {
				busySleep(evilDur)
				os.RemoveAll(modelsDir) // models holds .names since #314: bare Remove would ENOTEMPTY and stall the toggle
				os.MkdirAll(modelsDir, 0o755)
				inReal = true
				tg.flips++
			}
			if inReal {
				tg.reals++
			} else {
				tg.evils++
			}
		}
	}()
	return tg
}

func (tg *toggler) halt(modelsDir string) {
	close(tg.stop)
	<-tg.done
	os.RemoveAll(modelsDir) // models holds .names since #314: bare Remove would ENOTEMPTY and stall the toggle
	os.MkdirAll(modelsDir, 0o755)
}

// TestProbe_RenameRace_GetEscapesRoot attacks the verify→rename window in Get().
func TestProbe_RenameRace_GetEscapesRoot(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("MODEL-BODY-SMALL"))
	}))
	defer srv.Close()

	const maxTrials = 600
	escapes := 0
	refusals := 0
	for trial := 0; trial < maxTrials && escapes < 3; trial++ {
		base := t.TempDir()
		root := filepath.Join(base, "cache")
		evilModels := filepath.Join(base, root+"-evil", cacheModelsDir)
		if err := os.MkdirAll(evilModels, 0o755); err != nil {
			t.Fatal(err)
		}
		cache := NewCache(root, nil)
		cache.log = testLogger(t)
		if err := cache.Init(); err != nil {
			t.Fatal(err)
		}
		modelsDir := filepath.Join(root, cacheModelsDir)

		tg := startToggler(modelsDir, evilModels, 40*time.Microsecond, 40*time.Microsecond)
		_, err := cache.Get(context.Background(), "victim.gguf", srv.URL, "")
		tg.halt(modelsDir)

		if err != nil {
			refusals++
			continue
		}
		// Get succeeded — did the file land in the evil tree?
		if _, statErr := os.Lstat(filepath.Join(evilModels, "victim.gguf")); statErr == nil {
			escapes++
			t.Logf("TRIAL %d: ESCAPE — downloaded model renamed OUTSIDE cache root (%s)", trial, evilModels)
		}
	}
	t.Logf("trials=%d clean-refusals=%d ESCAPES=%d (toggler flips/trial≈%d)", maxTrials, refusals, escapes, 0)
	if escapes > 0 {
		t.Errorf("RENAME RACE CONFIRMED: %d/%d attempts landed model files outside the cache root", escapes, refusals+escapes)
	}
}

// TestProbe_OpenDirSwapRace_TruncatesAndDeletesVictim attacks the parent-check→OpenFile
// window in openFileForDownload, with a victim pre-placed at the evil destination.
func TestProbe_OpenDirSwapRace_TruncatesAndDeletesVictim(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ATTACKER-BODY"))
	}))
	defer srv.Close()

	const maxTrials = 4000
	hits := 0
	refusals := 0
	for trial := 0; trial < maxTrials && hits < 3; trial++ {
		base := t.TempDir()
		root := filepath.Join(base, "cache")
		evilModels := filepath.Join(base, root+"-evil", cacheModelsDir)
		if err := os.MkdirAll(evilModels, 0o755); err != nil {
			t.Fatal(err)
		}
		victim := filepath.Join(evilModels, "escape.gguf")
		sentinel := []byte(victimSentinel)
		if err := os.WriteFile(victim, sentinel, 0o644); err != nil {
			t.Fatal(err)
		}

		cache := NewCache(root, nil)
		cache.log = testLogger(t)
		if err := cache.Init(); err != nil {
			t.Fatal(err)
		}
		modelsDir := filepath.Join(root, cacheModelsDir)

		tg := startToggler(modelsDir, evilModels, 6*time.Microsecond, 6*time.Microsecond)
		dest := filepath.Join(modelsDir, "escape.gguf")
		err := cache.downloadOnce(context.Background(), srv.URL, dest, "")
		tg.halt(modelsDir)

		got, readErr := os.ReadFile(victim)
		switch {
		case os.IsNotExist(readErr):
			hits++
			t.Logf("TRIAL %d: VICTIM DELETED — truncated outside root by raced OpenFile, then removed by post-open cleanup", trial)
		case readErr == nil && string(got) != string(sentinel):
			hits++
			t.Logf("TRIAL %d: VICTIM MODIFIED outside root (%d -> %d bytes)", trial, len(sentinel), len(got))
		case err != nil:
			refusals++
		}
	}
	t.Logf("trials=%d clean-refusals=%d VICTIM-HITS=%d", maxTrials, refusals, hits)
	if hits > 0 {
		t.Errorf("OPEN DIR-SWAP RACE CONFIRMED: %d victim hits (truncate/delete) outside the cache root", hits)
	}
}

// eloopClassRefusal reports whether err is an O_NOFOLLOW/ELOOP-class refusal
// of a planted final-component symlink. Classification is delegated to the
// build-tagged eloopClassRefusalBase (#318): on !plan9 it is STRICTLY
// errno-primary — errors.Is(err, eloopErrno), no string fallback — which is
// sound because diagnoseOpenFailure preserves the real openat errno via %w;
// on plan9 (no ELOOP errno exists, sentinel never matches) the diagnosed
// "symlink" containment message remains as fallback. Requiring this class
// (not merely err != nil) is what keeps the probe non-vacuous: a failure
// upstream of openat (bad URL, dead server) would not qualify.
func eloopClassRefusal(err error) bool {
	return eloopClassRefusalBase(err)
}

// Control: with the final component swapped to a symlink mid-flight (dir stays real),
// O_NOFOLLOW must make the pinned-fd write-open fail with ELOOP — confirms the fix's
// core claim. The probe drives downloadOnceAt through a live httptest server and a
// pinned models-dir fd so the swap genuinely reaches the openat call; an empty URL
// would fail in the HTTP client and make every iteration pass vacuously.
func TestProbe_FinalComponentSwapCoveredByNoFollow(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("DUMMY-MODEL-BYTES"))
	}))
	defer srv.Close()

	base := t.TempDir()
	root := filepath.Join(base, "cache")
	victim := filepath.Join(base, "victim.bin")
	sentinel := []byte(victimSentinel)
	os.WriteFile(victim, sentinel, 0o644)

	cache := NewCache(root, nil)
	cache.log = testLogger(t)
	if err := cache.Init(); err != nil {
		t.Fatal(err)
	}
	modelsDir := filepath.Join(root, cacheModelsDir)
	dest := filepath.Join(modelsDir, "m.gguf")

	pin, pinName, err := cache.pinDestination(dest)
	if err != nil {
		t.Fatal(err)
	}
	defer closePinned(&pin)

	followed := 0
	for i := 0; i < 2000; i++ {
		// Simulate the tightest possible swap: plant symlink JUST BEFORE the call.
		os.Remove(dest)
		os.Symlink(victim, dest)
		err := cache.downloadOnceAt(context.Background(), srv.URL, pin, pinName, "")
		switch {
		case err == nil:
			followed++
		case !eloopClassRefusal(err):
			t.Fatalf("iteration %d: expected ELOOP-class symlink refusal at the openat layer, got %v", i, err)
		}
		if b, e := os.ReadFile(victim); e != nil || string(b) != string(sentinel) {
			t.Fatalf("iteration %d: victim damaged (%v)", i, e)
		}
	}
	if followed > 0 {
		t.Fatalf("final-component symlink followed %d/2000 times despite O_NOFOLLOW", followed)
	}
	t.Log("control OK: final-component symlink never followed; victim intact")
}
