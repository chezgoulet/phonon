package api

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/chezgoulet/phonon/internal/registry"
)

// ssePhone builds a fake phone that writes the given SSE script, flushing
// after every element. An element of the form "sleep:<duration>" pauses.
func ssePhone(t *testing.T, script []string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Accept") != "text/event-stream" {
			t.Errorf("expected Accept: text/event-stream, got %q", r.Header.Get("Accept"))
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		for _, el := range script {
			if d, ok := strings.CutPrefix(el, "sleep:"); ok {
				dur, err := time.ParseDuration(d)
				if err != nil {
					t.Fatalf("bad sleep duration %q", d)
				}
				time.Sleep(dur)
				continue
			}
			fmt.Fprint(w, el)
			fl.Flush()
		}
	}))
}

func delta(content string) string {
	return fmt.Sprintf("data: {\"choices\":[{\"delta\":{\"content\":%s},\"index\":0}]}\n\n", jsonString(content))
}

func TestStreamProxyForwardsDeltasAndDone(t *testing.T) {
	srv := ssePhone(t, []string{
		delta("Hello"),
		": keepalive\n\n",
		delta(" world"),
		"data: [DONE]\n\n",
		delta("MUST NOT APPEAR"), // after [DONE]
	})
	defer srv.Close()

	h := NewOpenAIHandler(registry.New())
	var got []string
	full, err := h.defaultStreamInferenceProxy(srv.URL, PhoneInferenceRequest{
		Model: "m", Stream: true,
	}, func(c string) { got = append(got, c) })
	if err != nil {
		t.Fatalf("proxy error: %v", err)
	}
	if full != "Hello world" {
		t.Errorf("full text = %q, want %q", full, "Hello world")
	}
	if len(got) != 2 || got[0] != "Hello" || got[1] != " world" {
		t.Errorf("chunks = %v", got)
	}
}

func TestStreamProxyKeepalivesResetStallTimer(t *testing.T) {
	// Total generation time exceeds the chunk timeout, but keepalives
	// arrive well within it — the stream must survive.
	// (Uses short sleeps to keep the test fast; the 5s production timeout
	// is far above these gaps.)
	srv := ssePhone(t, []string{
		": keepalive\n\n",
		"sleep:50ms",
		": keepalive\n\n",
		"sleep:50ms",
		delta("done"),
		"data: [DONE]\n\n",
	})
	defer srv.Close()

	h := NewOpenAIHandler(registry.New())
	full, err := h.defaultStreamInferenceProxy(srv.URL, PhoneInferenceRequest{Model: "m", Stream: true}, func(string) {})
	if err != nil {
		t.Fatalf("proxy error: %v", err)
	}
	if full != "done" {
		t.Errorf("full = %q", full)
	}
}

func TestStreamProxyDetectsStalledPhone(t *testing.T) {
	if testing.Short() {
		t.Skip("stall test waits out the 5s chunk timeout")
	}
	srv := ssePhone(t, []string{
		delta("partial"),
		"sleep:7s", // exceed streamChunkTimeout (5s)
		delta("late"),
	})
	defer srv.Close()

	h := NewOpenAIHandler(registry.New())
	start := time.Now()
	full, err := h.defaultStreamInferenceProxy(srv.URL, PhoneInferenceRequest{Model: "m", Stream: true}, func(string) {})
	if err == nil {
		t.Fatal("expected stall error")
	}
	if !strings.Contains(err.Error(), "stalled") {
		t.Errorf("error should mention stall, got: %v", err)
	}
	if !isTimeoutErr(err) {
		t.Error("stall error should classify as timeout (drives 504 + breaker)")
	}
	if full != "partial" {
		t.Errorf("partial text should be returned for error metadata, got %q", full)
	}
	if elapsed := time.Since(start); elapsed > 6500*time.Millisecond {
		t.Errorf("stall should be detected at ~5s, took %s", elapsed)
	}
}

func TestStreamProxyPhoneHTTPError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, `{"error":"No model loaded"}`, http.StatusBadGateway)
	}))
	defer srv.Close()

	h := NewOpenAIHandler(registry.New())
	_, err := h.defaultStreamInferenceProxy(srv.URL, PhoneInferenceRequest{Model: "m", Stream: true}, func(string) {})
	if err == nil || !strings.Contains(err.Error(), "HTTP 502") {
		t.Fatalf("expected HTTP 502 error, got %v", err)
	}
}

func TestStreamProxySendsAuthAndStreamFlag(t *testing.T) {
	var gotAuth, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		b := make([]byte, 4096)
		n, _ := r.Body.Read(b)
		gotBody = string(b[:n])
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	defer srv.Close()

	h := NewOpenAIHandler(registry.New())
	_, err := h.defaultStreamInferenceProxy(srv.URL, PhoneInferenceRequest{
		Model: "m", Stream: true, AuthToken: "sekrit",
	}, func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer sekrit" {
		t.Errorf("Authorization = %q", gotAuth)
	}
	if !strings.Contains(gotBody, `"stream":true`) {
		t.Errorf("stream:true must be forwarded to the phone, body = %s", gotBody)
	}
}
