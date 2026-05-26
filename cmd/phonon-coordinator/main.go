package main

import (
	"context"
	"time"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path"
	"strings"
	"syscall"

	"github.com/chezgoulet/phonon/internal/api"
	"github.com/chezgoulet/phonon/internal/auth"
	"github.com/chezgoulet/phonon/internal/config"
	"github.com/chezgoulet/phonon/internal/registry"
	"gopkg.in/yaml.v3"
)

// uiFS is declared in ui_embed.go (this package)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))

	logger.Info("phonon-coordinator starting",
		"version", "0.1.0",
		"phase", "alpha",
	)

	// Load configuration
	cfgPath := "phonon.yaml"
	if p := os.Getenv("PHONON_CONFIG"); p != "" {
		cfgPath = p
	}

	cfg, err := loadConfig(cfgPath)
	if err != nil {
		logger.Warn("config load failed, using defaults", "error", err, "path", cfgPath)
		cfg = &config.Config{
			Cluster: config.ClusterConfig{
				Auth: config.AuthConfig{Mode: "none"},
			},
		}
	}

	// Create registry and API handlers
	reg := registry.New()

	wsHandler := api.NewWSHandler(reg)

	sidecarHandler := api.NewSidecarHandler(reg)
	openaiHandler := api.NewOpenAIHandler(reg)
	clusterHandler := api.NewClusterHandler(reg)

	// Create auth middleware
	authMiddleware := auth.New(auth.Config{
		Mode:     cfg.Cluster.Auth.Mode,
		Issuer:   cfg.Cluster.Auth.Issuer,
		ClientID: cfg.Cluster.Auth.ClientID,
	})

	if err := authMiddleware.Start(); err != nil {
		logger.Error("auth middleware start failed", "error", err)
		os.Exit(1)
	}
	defer authMiddleware.Stop()

	// Set up routes
	mux := http.NewServeMux()

	// CORS middleware wraps all routes
	{
		base := mux
		mux = http.NewServeMux()
		mux.Handle("/", corsMiddleware(base))
	}

	// Public routes (no auth required)
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"status":"ok","version":"0.1.0"}`)
	})

	mux.HandleFunc("/api/v1/auth/status", authMiddleware.StatusHandler())

	// Sidecar routes — no auth required (phones don't have OIDC tokens)
	sidecarMux := http.NewServeMux()
	wsHandler.RegisterRoutes(sidecarMux)
	sidecarHandler.RegisterRoutes(sidecarMux)
	mux.Handle("/api/v1/sidecar/", sidecarMux)

	// Protected routes — wrapped with auth middleware
	protectedMux := http.NewServeMux()
	openaiHandler.RegisterRoutes(protectedMux)
	clusterHandler.RegisterRoutes(protectedMux)
	mux.Handle("/api/v1/", authMiddleware.Handler(protectedMux))

	// Serve the Web UI from /ui/ and redirect / → /ui/
	serveUI(mux, logger)

	addr := ":8080"
	if p := os.Getenv("PHONON_PORT"); p != "" {
		addr = ":" + p
	}

	server := &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	go func() {
		logger.Info("listening", "addr", addr, "auth_mode", authMiddleware.Status().Mode)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10 * time.Second)
	defer cancel()
	_ = server.Shutdown(shutdownCtx)
	logger.Info("stopped")
}

// serveUI serves the Vite-built React app from /ui/ and redirects / → /ui/.
// corsMiddleware adds CORS headers to all responses and handles OPTIONS preflight.
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Phonon-Device")
		w.Header().Set("Access-Control-Max-Age", "86400")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}

func serveUI(mux *http.ServeMux, log *slog.Logger) {
	// The embed pattern static/* embeds files from cmd/phonon-coordinator/static/.
	// Copy ui/dist/ → cmd/phonon-coordinator/static/ before building:
	//   cp -r ui/dist cmd/phonon-coordinator/static
	//
	// The embedded FS contains files directly (no prefix to strip).
	subFS, err := fs.Sub(uiFS, "static")
	if err != nil {
		log.Warn("UI not built — skipping static file server", "error", err)
		// Create a handler that shows a helpful message when the UI isn't built
		mux.HandleFunc("/ui/", func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			fmt.Fprint(w, `<!DOCTYPE html>
<html><body style="font-family:monospace;padding:2em;background:#0f172a;color:#94a3b8">
<h1 style="color:#38bdf8">Phonon Cluster</h1>
<p>UI not built yet. Run <code style="background:#1e293b;padding:2px 6px;border-radius:4px">cd ui && npm run build</code> to build the frontend.</p>
<p>API ready at <a href="/api/v1/cluster/health" style="color:#38bdf8">/api/v1/cluster/health</a></p>
</body></html>`)
		})
		return
	}

	fileServer := http.FileServer(http.FS(subFS))

	// Serve /ui/ and all subpaths from the embedded filesystem
	mux.Handle("/ui/", http.StripPrefix("/ui/", fileServer))

	// Redirect / → /ui/ for the SPA
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// Only handle exact root or non-API paths
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/ui/", http.StatusFound)
			return
		}
		// For SPA client-side routing: serve index.html for non-file paths
		if !strings.HasPrefix(r.URL.Path, "/api/") && !strings.HasPrefix(r.URL.Path, "/ws") {
			// Check if the path looks like a file (has an extension)
			ext := path.Ext(r.URL.Path)
			if ext == "" {
				// Serve index.html for SPA routes
				r.URL.Path = "/ui/index.html"
				http.StripPrefix("/ui/", fileServer).ServeHTTP(w, r)
				return
			}
		}
		http.NotFound(w, r)
	})

	log.Info("UI served at /ui/")
}

func loadConfig(path string) (*config.Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open config: %w", err)
	}
	defer f.Close()

	var cfg config.Config
	if err := yaml.NewDecoder(f).Decode(&cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	return &cfg, nil
}
