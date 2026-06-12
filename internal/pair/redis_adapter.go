package pair

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// RedisAdapter wraps *redis.Client to implement RedisClient interface.
// This lives in its own file so the go-redis import is isolated here.
type RedisAdapter struct {
	client *redis.Client
}

// NewRedisAdapter creates a RedisAdapter from go-redis options.
func NewRedisAdapter(addr, password string, db int) (*RedisAdapter, error) {
	opts := &redis.Options{
		Addr:         addr,
		Password:     password,
		DB:           db,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
		PoolSize:     5,
	}
	client := redis.NewClient(opts)
	return &RedisAdapter{client: client}, nil
}

// HSet sets a field in a Redis hash. Uses context.Background() for simplicity
// since the store is called under Manager's mutex — no long-lived ctx needed.
func (a *RedisAdapter) HSet(key, field string, value interface{}) error {
	// value can be string, []byte, or any primitive/serializable type.
	// go-redis accepts interface{} here.
	return a.client.HSet(ctx, key, field, value).Err()
}

// HGet retrieves a field from a Redis hash.
func (a *RedisAdapter) HGet(key, field string) (string, error) {
	return a.client.HGet(ctx, key, field).Result()
}

// HDel removes a field from a Redis hash.
func (a *RedisAdapter) HDel(key, field string) error {
	return a.client.HDel(ctx, key, field).Err()
}

// HGetAll retrieves all fields and values from a Redis hash.
func (a *RedisAdapter) HGetAll(key string) (map[string]string, error) {
	return a.client.HGetAll(ctx, key).Result()
}

// Close closes the Redis connection.
func (a *RedisAdapter) Close() error {
	return a.client.Close()
}

// Shared context for Redis operations. Each coordinator has its own
// connection pool so this is safe for concurrent use.
var ctx = context.Background()

// Ensure RedisAdapter implements RedisClient at compile time.
var _ RedisClient = (*RedisAdapter)(nil)
