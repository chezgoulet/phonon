package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/chezgoulet/phonon/internal/api"
	"github.com/chezgoulet/phonon/internal/auth"
	"github.com/chezgoulet/phonon/internal/config"
	"github.com/chezgoulet/phonon/internal/registry"
	"gopkg.in/yaml.v3"
)

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

	// Public routes (no auth required)
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `{"status":"ok","version":"0.1.0"}`)
	})

	mux.HandleFunc("/api/v1/auth/status", authMiddleware.StatusHandler())

	// Protected routes — wrapped with auth middleware
	protectedMux := http.NewServeMux()
	wsHandler.RegisterRoutes(protectedMux)
	sidecarHandler.RegisterRoutes(protectedMux)
	openaiHandler.RegisterRoutes(protectedMux)
	clusterHandler.RegisterRoutes(protectedMux)
	mux.Handle("/api/v1/", authMiddleware.Handler(protectedMux))

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
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10_000_000_000)
	defer cancel()
	_ = server.Shutdown(shutdownCtx)
	logger.Info("stopped")
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
