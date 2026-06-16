// Package pair — pairing state persistence.
//
// Two implementations:
//   - FileStore: JSON file (default, single-instance deployments)
//   - RedisStore: Redis hash (HA deployments, multiple coordinators)
package pair

import (
	"encoding/json"
	"fmt"
	"os"
)

// Store is the persistence interface for paired devices.
//
// Implementations must be safe for concurrent access (the Manager
// serializes calls through its mutex, but the store itself should
// also be goroutine-safe).
type Store interface {
	// SavePaired persists a single paired device, overwriting any
	// existing entry for the same DeviceID.
	SavePaired(d *PairedDevice) error

	// LoadPaired returns all currently paired devices. Returns an
	// empty slice (not nil) if no devices are paired.
	LoadPaired() ([]*PairedDevice, error)

	// RemovePaired deletes a device from the store.
	RemovePaired(deviceID string) error

	// Close releases any resources held by the store.
	Close() error
}

// ─── FileStore (default, single-instance) ─────────────────────────

// FileStore persists paired devices as a JSON file on disk.
type FileStore struct {
	path string
}

// NewFileStore creates a FileStore at the given path.
func NewFileStore(path string) *FileStore {
	return &FileStore{path: path}
}

func (s *FileStore) SavePaired(d *PairedDevice) error {
	all, err := s.LoadPaired()
	if err != nil {
		return err
	}

	// Upsert
	found := false
	for i, existing := range all {
		if existing.DeviceID == d.DeviceID {
			all[i] = d
			found = true
			break
		}
	}
	if !found {
		all = append(all, d)
	}

	return s.writeAll(all)
}

func (s *FileStore) LoadPaired() ([]*PairedDevice, error) {
	data, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return []*PairedDevice{}, nil
		}
		return nil, fmt.Errorf("read %s: %w", s.path, err)
	}
	if len(data) == 0 {
		return []*PairedDevice{}, nil
	}
	var devices []*PairedDevice
	if err := json.Unmarshal(data, &devices); err != nil {
		return nil, fmt.Errorf("unmarshal %s: %w", s.path, err)
	}
	return devices, nil
}

func (s *FileStore) RemovePaired(deviceID string) error {
	all, err := s.LoadPaired()
	if err != nil {
		return err
	}
	filtered := make([]*PairedDevice, 0, len(all))
	for _, d := range all {
		if d.DeviceID != deviceID {
			filtered = append(filtered, d)
		}
	}
	return s.writeAll(filtered)
}

func (s *FileStore) writeAll(devices []*PairedDevice) error {
	// Sort is intentionally omitted — ordering isn't guaranteed but
	// stability isn't required for correctness.
	data, err := json.Marshal(devices)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	if err := os.WriteFile(s.path, data, 0600); err != nil {
		return fmt.Errorf("write %s: %w", s.path, err)
	}
	return nil
}

func (s *FileStore) Close() error { return nil }

// ─── RedisStore (HA mode) ─────────────────────────────────────────

// RedisStore persists paired devices in a Redis hash.
//
// Layout:
//   Key: "phonon:paired"
//   Field: device_id
//   Value: JSON-serialized PairedDevice
//
// The entire set is loaded once at startup and cached in-memory by
// Manager. Writes go through Redis synchronously so that all
// coordinator instances see the same state.
type RedisStore struct {
	client RedisClient
	key    string
}

// RedisClient is the minimal Redis interface the store needs.
// Production: *redis.Client (go-redis/v9).
type RedisClient interface {
	HSet(key, field string, value interface{}) error
	HGet(key, field string) (string, error)
	HDel(key, field string) error
	HGetAll(key string) (map[string]string, error)
	Close() error
}

// NewRedisStore creates a RedisStore using the provided client.
// The client must already be configured and connected.
func NewRedisStore(client RedisClient, key string) *RedisStore {
	if key == "" {
		key = "phonon:paired"
	}
	return &RedisStore{client: client, key: key}
}

func (s *RedisStore) SavePaired(d *PairedDevice) error {
	data, err := json.Marshal(d)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	return s.client.HSet(s.key, d.DeviceID, string(data))
}

func (s *RedisStore) LoadPaired() ([]*PairedDevice, error) {
	all, err := s.client.HGetAll(s.key)
	if err != nil {
		return nil, err
	}
	devices := make([]*PairedDevice, 0, len(all))
	for _, raw := range all {
		var d PairedDevice
		if err := json.Unmarshal([]byte(raw), &d); err != nil {
			return nil, fmt.Errorf("unmarshal from redis: %w", err)
		}
		devices = append(devices, &d)
	}
	return devices, nil
}

func (s *RedisStore) RemovePaired(deviceID string) error {
	return s.client.HDel(s.key, deviceID)
}

func (s *RedisStore) Close() error {
	return s.client.Close()
}

// NOTE: The actual *redis.Client adapter (go-redis/v9) lives in
// internal/pair/redis_adapter.go to keep the dependency import in one file.
