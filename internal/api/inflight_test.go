package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestInflightTrackerAcquireRelease(t *testing.T) {
	tr := newInflightTracker()

	if d := tr.depth("a"); d != 0 {
		t.Fatalf("fresh depth = %d, want 0", d)
	}
	rel1 := tr.acquire("a")
	rel2 := tr.acquire("a")
	if d := tr.depth("a"); d != 2 {
		t.Fatalf("after two acquires depth = %d, want 2", d)
	}

	rel1()
	if d := tr.depth("a"); d != 1 {
		t.Fatalf("after one release depth = %d, want 1", d)
	}
	// Release is idempotent: calling it again must not double-decrement.
	rel1()
	if d := tr.depth("a"); d != 1 {
		t.Fatalf("after idempotent release depth = %d, want 1", d)
	}

	rel2()
	if d := tr.depth("a"); d != 0 {
		t.Fatalf("after all releases depth = %d, want 0", d)
	}
}

func TestInflightTrackerSnapshotIsolated(t *testing.T) {
	tr := newInflightTracker()
	tr.acquire("a")
	tr.acquire("b")
	tr.acquire("b")

	snap := tr.snapshot()
	if snap["a"] != 1 || snap["b"] != 2 {
		t.Fatalf("snapshot = %v, want a:1 b:2", snap)
	}
	// Mutating the snapshot must not affect the tracker.
	snap["a"] = 99
	if d := tr.depth("a"); d != 1 {
		t.Fatalf("tracker mutated via snapshot: depth = %d", d)
	}
}

// TestConcurrentRequestsSpreadAcrossPhones is the regression guard for the bug
// that motivated coordinator-side in-flight tracking: when routing trusted the
// phone-reported queue depth (hardcoded to 0), two concurrent requests both
// picked the same "least loaded" phone. With real in-flight accounting they
// must land on different phones.
func TestConcurrentRequestsSpreadAcrossPhones(t *testing.T) {
	reg := resilienceTestRegistry(t) // phone-01 @10.0.0.5, phone-02 @10.0.0.6
	h := NewOpenAIHandler(reg, WithMaxQueuePerNode(0))
	h.AddModel("test-model", "test")

	arrived := make(chan string, 2)
	block := make(chan struct{})
	h.inferenceProxy = func(phoneURL string, _ PhoneInferenceRequest) (*PhoneInferenceResponse, error) {
		id := "phone-01"
		if strings.Contains(phoneURL, "10.0.0.6") {
			id = "phone-02"
		}
		arrived <- id // signal this phone received a request
		<-block       // hold the in-flight slot until both have arrived
		return &PhoneInferenceResponse{Text: "ok", Tokens: 1}, nil
	}

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(testChatBody))
			mux.ServeHTTP(httptest.NewRecorder(), req)
		}()
	}

	// Both requests must be in flight simultaneously (on different phones)
	// before either is allowed to complete and free its slot.
	got := map[string]int{}
	got[<-arrived]++
	got[<-arrived]++
	close(block)
	wg.Wait()

	if got["phone-01"] != 1 || got["phone-02"] != 1 {
		t.Errorf("concurrent requests should spread one per phone, got %v", got)
	}
}
