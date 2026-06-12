package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path"
	"strings"
	"syscall"
	"time"

	"github.com/chezgoulet/phonon/internal/api"
	"github.com/chezgoulet/phonon/internal/auth"
	"github.com/chezgoulet/phonon/internal/config"
	"github.com/chezgoulet/phonon/internal/discovery"
	"github.com/chezgoulet/phonon/internal/health"
	phononlog "github.com/chezgoulet/phonon/internal/log"
	"github.com/chezgoulet/phonon/internal/model"
	"github.com/chezgoulet/phonon/internal/pair"
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

	cfg, cfgErr := loadConfig(cfgPath)
	if cfgErr != nil {
		logger.Warn("config load failed, using defaults", "error", cfgErr, "path", cfgPath)
		cfg = &config.Config{
			Cluster: config.ClusterConfig{
				Auth: config.AuthConfig{Mode: "none"},
			},
		}
	}

	// Create registry and API handlers
	reg := registry.New()

	// Initialize persistent event log
	elPath := cfg.Cluster.EventLog.Path
	if elPath == "" {
		elPath = "phonon.db"
	}
	retentionDays := cfg.Cluster.EventLog.RetentionDays
	if retentionDays <= 0 {
		retentionDays = 90
	}

	eventLog, err := phononlog.New(phononlog.Opts{
		Path:          elPath,
		RetentionDays: retentionDays,
		MaxEvents:     10000,
		Logger:        logger,
	})
	if err != nil {
		logger.Error("event log init failed", "error", err)
		return
	}
	defer eventLog.Close()

	// Attach event log to registry
	reg.SetEventLog(eventLog)

	wsHandler := api.NewWSHandler(reg)

	// 5. Pairing manager — device identity, key exchange, code-based pairing
	coordKeyPath := os.Getenv("PHONON_COORD_KEY")
	if coordKeyPath == "" {
		coordKeyPath = "./coord.key"
	}
	persistPath := os.Getenv("PHONON_PAIRED_PATH")
	if persistPath == "" {
		persistPath = "./paired_devices.json"
	}
	pairingMgr, err := pair.NewManager(coordKeyPath, persistPath)
	if err != nil {
		logger.Error("pairing manager init failed", "error", err)
		return
	}
	logger.Info("pairing manager initialized",
		"coord_key", coordKeyPath,
		"persist_path", persistPath,
		"pubkey", pairingMgr.CoordinatorPublicKey()[:16]+"...",
	)
	defer pairingMgr.StopCleanup()

	sidecarHandler := api.NewSidecarHandler(reg)
	sidecarHandler.SetCoordinatorKey(pairingMgr.CoordinatorPublicKey())
	pairingHandler := api.NewPairingHandler(pairingMgr, reg)
	openaiHandler := api.NewOpenAIHandler(reg, api.WithMaxQueuePerNode(cfg.Cluster.Queue.MaxPerNode))
	clusterHandler := api.NewClusterHandler(reg)

	// The inference proxy now routes to phones via HTTP on the default
	// sidecar port (9876). The phone must be running the Phonon sidecar
	// with an active model load for inference to succeed.
	logger.Info("inference proxy ready — routing to phones via HTTP",
		"component", "openai")


	// Create auth middleware
	authMiddleware := auth.New(auth.Config{
		Mode:     cfg.Cluster.Auth.Mode,
		Issuer:   cfg.Cluster.Auth.Issuer,
		ClientID: cfg.Cluster.Auth.ClientID,
	})

	if err := authMiddleware.Start(); err != nil {
		logger.Error("auth middleware start failed", "error", err)
		return
	}
	defer authMiddleware.Stop()

	// --- Background subsystems ---

	// 1. Health monitor — periodic check loop with hysteresis
	healthCfg := health.DefaultMonitorConfig()
	if cfgErr == nil {
		if cfg.Cluster.Health.Overheat.Threshold > 0 {
			healthCfg.OverheatThreshold = cfg.Cluster.Health.Overheat.Threshold
		}
		if cfg.Cluster.Health.Overheat.ReentryThreshold > 0 {
			healthCfg.OverheatReentryThreshold = cfg.Cluster.Health.Overheat.ReentryThreshold
		}
		if cfg.Cluster.Health.Battery.LowThreshold > 0 {
			healthCfg.BatteryLowThreshold = cfg.Cluster.Health.Battery.LowThreshold
		}
		if cfg.Cluster.Health.Battery.ReentryThreshold > 0 {
			healthCfg.BatteryReentryThreshold = cfg.Cluster.Health.Battery.ReentryThreshold
		}
		if cfg.Cluster.Health.Battery.CapacityThreshold > 0 {
			healthCfg.BatteryCapacityThreshold = cfg.Cluster.Health.Battery.CapacityThreshold
		}
		if cfg.Cluster.Health.DrainingThreshold > 0 {
			healthCfg.DrainingThreshold = cfg.Cluster.Health.DrainingThreshold
		}
		if d := cfg.Cluster.Health.OfflineTimeoutDuration(); d > 0 {
			healthCfg.OfflineTimeout = d
		}
	}
	healthMonitor := health.NewMonitor(reg, healthCfg)
	healthMetrics := healthMonitor.RegisterMetrics()

	// Wire event log to health monitor actions
	healthMonitor.AddAction(health.WithEventLog(eventLog))

	// 2. Discovery manager — mDNS (unless disabled) + manual registration
	var mdnsDiscoverer discovery.Discoverer
	if !cfg.Cluster.Discovery.MDNS.Disabled {
		mdnsDiscoverer = discovery.NewMDNSDiscoverer()
	}
	discoveryMgr := discovery.NewManager(mdnsDiscoverer,
		func(deviceID, deviceModel string, ip net.IP, _ int) error {
			return reg.Register(deviceID, deviceModel, ip.String())
		})

	// 3. Model cache — local file cache for GGUF models
	cacheDir := os.Getenv("PHONON_CACHE_DIR")
	if cacheDir == "" {
		cacheDir = "./cache"
	}
	modelCache := model.NewCache(cacheDir, nil)
	if err := modelCache.Init(); err != nil {
		logger.Warn("model cache init failed, continuing without disk cache", "error", err)
	}

	// 4. Model reconciler — desired vs current state loop
	coordinatorPort := os.Getenv("PHONON_PORT")
	if coordinatorPort == "" {
		coordinatorPort = "8080"
	}
	coordinatorURL := os.Getenv("PHONON_COORDINATOR_URL")
	if coordinatorURL == "" {
		coordinatorURL = fmt.Sprintf("http://localhost:%s", coordinatorPort)
	}
	reconciler := model.NewReconciler(modelCache, reg, wsHandler, coordinatorURL)
	preflightHandler := api.NewPreflightHandler(reg, modelCache, cfg)

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
	pairingHandler.RegisterSidecarRoutes(sidecarMux)
	mux.Handle("/api/v1/sidecar/", sidecarMux)

	// Protected routes — wrapped with auth middleware
	protectedMux := http.NewServeMux()
	openaiHandler.RegisterRoutes(protectedMux)
	clusterHandler.RegisterRoutes(protectedMux)
	preflightHandler.RegisterRoutes(protectedMux)

	// Event log query endpoint (protected)
	eventAPI := phononlog.NewAPIHandler(eventLog)
	eventAPI.RegisterRoutes(protectedMux)

	// Model download endpoint (protected) — serves cached GGUF files
	modelAPI := api.NewModelDownloadHandler(modelCache, cacheDir)
	modelAPI.RegisterRoutes(protectedMux)

	// Pairing operator endpoints (protected)
	pairingHandler.RegisterOperatorRoutes(protectedMux)

	mux.Handle("/api/v1/", authMiddleware.Handler(protectedMux))

	// Metrics — public, served from the health monitor's private Prometheus registry
	mux.Handle("GET /metrics", healthMetrics.Handler())

	// Serve the Web UI from /ui/ and redirect / → /ui/
	serveUI(mux, logger)

	addr := cfg.Cluster.Bind
	if addr == "" {
		addr = ":" + coordinatorPort
	}

	server := &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	go func() {
		// Start with TLS if configured, otherwise plain HTTP
		if cfg != nil && cfg.Cluster.TLS.Enabled {
			cert := cfg.Cluster.TLS.CertFile
			key := cfg.Cluster.TLS.KeyFile
			if cert == "" || key == "" {
				logger.Error("TLS enabled but cert_file or key_file not set")
				os.Exit(1)
			}

			tlsCfg := &tls.Config{
				MinVersion: tls.VersionTLS12,
			}

			// Configure mTLS.
			// If a client CA file is explicitly configured, use it.
			// Otherwise, fall back to the coordinator's Ed25519-derived
			// self-signed CA cert generated by the PairingManager at startup.
			if caFile := cfg.Cluster.TLS.ClientCAFile; caFile != "" {
				caCert, err := os.ReadFile(caFile)
				if err != nil {
					logger.Error("failed to read client CA file", "error", err, "path", caFile)
					os.Exit(1)
				}
				caPool := x509.NewCertPool()
				if !caPool.AppendCertsFromPEM(caCert) {
					logger.Error("failed to parse client CA cert (no valid PEM data)", "path", caFile)
					os.Exit(1)
				}
				tlsCfg.ClientCAs = caPool
				tlsCfg.ClientAuth = tls.RequireAndVerifyClientCert
				logger.Info("mTLS enabled", "client_ca", caFile)
			} else {
				// Derive CA from coordinator Ed25519 identity key
				caPool, err := pairingMgr.TLSClientCA()
				if err != nil {
					logger.Error("failed to create mTLS CA pool from coordinator key", "error", err)
					os.Exit(1)
				}
				tlsCfg.ClientCAs = caPool
				tlsCfg.ClientAuth = tls.RequireAndVerifyClientCert
				logger.Info("mTLS enabled — CA derived from coordinator Ed25519 key")
			}

			server.TLSConfig = tlsCfg

			logger.Info("listening (TLS)",
				"addr", addr,
				"auth_mode", authMiddleware.Status().Mode,
				"cert", cert,
				"mtls", tlsCfg.ClientAuth == tls.RequireAndVerifyClientCert,
			)
			if authMiddleware.Status().Mode == "none" &&
				(strings.HasPrefix(addr, ":") || strings.HasPrefix(addr, "0.0.0.0:") || strings.HasPrefix(addr, "::")) {
				logger.Warn("INSECURE — TLS enabled but auth mode is 'none', anyone with a valid cert can reach the API",
					"addr", addr,
					"fix", "set auth.mode to 'psk' or 'oidc'",
				)
			}
			if err := server.ListenAndServeTLS(cert, key); err != nil && err != http.ErrServerClosed {
				logger.Error("server error", "error", err)
				os.Exit(1)
			}
		} else {
			logger.Info("listening", "addr", addr, "auth_mode", authMiddleware.Status().Mode, "tls", false)
			if authMiddleware.Status().Mode == "none" &&
				(strings.HasPrefix(addr, ":") || strings.HasPrefix(addr, "0.0.0.0:") || strings.HasPrefix(addr, "::")) {
				logger.Warn("INSECURE — binding on all interfaces with no auth",
					"addr", addr,
					"fix", "set auth.mode to 'psk' or 'oidc' in phonon.yaml, or bind 127.0.0.1:8080 for local-only",
				)
			}
			if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
				logger.Error("server error", "error", err)
				os.Exit(1)
			}
		}
	}()

	// Start event log retention loop
	eventLog.StartRetentionLoop(ctx, retentionDays, 24*time.Hour)

	// Start background subsystems now that the HTTP server is running
	healthMonitor.Start()
	if err := discoveryMgr.Start(ctx); err != nil {
		logger.Error("failed to start discovery manager", "error", err)
	}
	if err := reconciler.Start(ctx, cfg.Groups); err != nil {
		logger.Error("failed to start model reconciler", "error", err)
	}

	logger.Info("all subsystems started",
		"health_monitor", true,
		"discovery", mdnsDiscoverer != nil,
		"model_cache", cacheDir,
		"reconciler", len(cfg.Groups) > 0,
	)

	<-ctx.Done()
	logger.Info("shutting down")

	// Stop subsystems in reverse order
	reconciler.Stop()
	_ = discoveryMgr.Stop()
	healthMonitor.Stop()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
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
