# LAN binding and API keys

Date: 2026-10-17
Branch: `fix/lan-binding` (builds on `feat/lan-binding`)
Device: stock Pixel 7 (`panther`), serial `2A091FDH200AJ1`, Android 17,
arm64-v8a, unrooted. No other device was used.

The app can bind its operator API and `llama-server` to a LAN address so a
phone does not need `adb forward`. Binding off-loopback requires an API key.

This revision fixes three observed defects in the first LAN attempt:

1. `POST /settings {"bind_address":"0.0.0.0"}` with no key was accepted and the
   operator port was bound to `0.0.0.0` before the safety check ran.
2. After setting a key and binding to `0.0.0.0`, `/v1/models` with the bearer
   token returned 502 while `/health` still said `ready`.
3. Clearing the key and restoring `127.0.0.1` left the service in `error`
   (`pid: null`) until an explicit `POST /server/start`.

The current behaviour is described below, followed by the observed test run.

## Settings

| Key | Default | Accepted values |
|---|---|---|
| `bind_address` | `127.0.0.1` | `127.0.0.1`, `0.0.0.0`, or an IPv4 address currently assigned to a local interface |
| `api_key` | empty | any non-empty string; **mandatory** when `bind_address` is not loopback |

`bind_address` is validated before it is stored. Hostnames, IPv6 literals,
malformed IPv4 and addresses not present on a local interface are rejected with
HTTP 400. Validation observes the interfaces that exist at the time of the
request: if Ethernet or Wi-Fi is not up yet, a specific address cannot be saved.

Set it from the activity or over the loopback API:

```bash
# safe: loopback, no key
curl -sS -X POST http://127.0.0.1:8080/settings \
  -H 'Content-Type: application/json' \
  -d '{"bind_address":"127.0.0.1","api_key":""}'

# LAN: wildcard address, key required
curl -sS -X POST http://127.0.0.1:8080/settings \
  -H 'Content-Type: application/json' \
  -d '{"bind_address":"0.0.0.0","api_key":"<generated-on-device>"}'
```

`GET /settings` never returns the key, only `"api_key_set": true|false`. The key
is stored in the app's private SharedPreferences, not in the Android Keystore.
It is also passed to `llama-server` as `--api-key`, so the backend port is
protected as well as the operator port.

## Validation and binding

Settings changes are two-phase:

1. **Validate the prospective configuration.** The request is applied to an
   in-memory copy of the current settings. Every field is checked, including
   the pair `(prospective bind_address, prospective api_key)`. If the
   prospective bind is not loopback and the prospective key is blank, the
   request is rejected with HTTP 400 and `{"error":"bind_address requires
   api_key"}`. Nothing is persisted, no listener is changed and no server is
   restarted. In particular, clearing `api_key` while the bind is off-loopback
   is rejected; send the new key and address together when moving back to
   loopback.
2. **Commit and rebind.** Only after the whole prospective configuration is
   valid is it written. If `bind_address`, `api_key` or `port` changed, the
   existing operator listener is closed *before* the key is cleared, so the
   port is never off-host with a blank key, even briefly. A single reload
   operation then restarts the backend and binds the new listener.

There is a second, independent check immediately before the operator socket is
bound. The check and `bind()` are performed while holding the settings lock, so
a concurrent key change cannot race the listener onto the LAN. If the invariant
fails at that point, the app logs `refusing to bind ...: bind_address requires
api_key`, does not bind the socket, stops the backend, and puts `/health` into
`state: "error"` with that error string.

If an unsafe pair is already in storage at boot (for example from an older
build), the app keeps a **loopback-only** operator listener so `/health` can
report the refusal, and never binds the requested off-host address. This is a
diagnostic fallback, not an exposure path.

## Authentication

`/health` is a liveness probe and stays unauthenticated. It reports state,
model name, PID, uptime, temperature, configured bind address, `api_key_set`
and `exposed_off_host`; it contains no prompt, log or key material.

When a key is configured, every other route requires
`Authorization: Bearer <api_key>`:

- `/v1/*`
- `/settings`
- `/model/*`
- `/server/*`
- `/logs`
- `/debug/*`

Missing or wrong credentials return HTTP 401. When no key is configured and the
bind address is loopback, behaviour is unchanged and no header is required.

`llama-server` is started with `--host <bind_address>` and `--api-key <key>`.
The proxy forwards the caller's bearer token, or injects the configured key
when the backend request has no `Authorization` header. Direct access to the
backend port therefore requires the same key; it is not an unauthenticated side
door.

## Deterministic rebind

Changing `bind_address`, `api_key`, `port`, or any other setting that affects
the backend tears down the operator listener, restarts `llama-server` and binds
the new listener as one serialized reload. While that is happening `/health`
cannot report `ready`: it is either unreachable (between closing the old
listener and binding the new one) or returns HTTP 503 with `state: "loading"`.
It turns `ready` only after the new listener is bound and the new backend
answers `/health`.

Restoring a safe configuration after a refusal also restarts the server
automatically; an explicit `POST /server/start` is not required.

## Putting a phone on the network

### Wi-Fi

1. Join the trusted LAN SSID, or provision it before deploying the phone.
2. Decide whether MAC randomization is acceptable. If the router reserves an
   address by MAC, either disable randomization for that SSID or reserve the
   randomized MAC the phone actually presents.
3. Prefer a DHCP reservation. It survives reboots, avoids address conflicts, and
   keeps the router as the source of truth.
4. Put the phone on the charger and set Charging optimization to 80%; see
   [`battery.md`](battery.md).

### USB-C Ethernet with Power Delivery

1. Use a USB-C hub or adapter with **PD passthrough** and Ethernet
   (ASIX AX88179-class adapters are the assumption in
   [`battery.md`](battery.md)).
2. Connect Ethernet and the PD charger. Android's Ethernet stack is native; no
   app is needed.
3. Confirm the interface and address on the device:

   ```bash
   adb -s <serial> shell 'ip -4 addr show'
   adb -s <serial> shell 'ip route'
   ```

4. Reserve the address on the DHCP server by the adapter's MAC, or configure a
   static lease on the router. Do not configure a static IP on the phone unless
   you also document it.

### Choosing `bind_address`

- `0.0.0.0` is the recommended appliance setting: it binds every IPv4
  interface and keeps working if the address changes after a DHCP renewal.
  Combined with `api_key`, it is safe on a trusted LAN.
- A specific local IPv4 (for example `192.168.1.42`) restricts the listener to
  that interface. Use it only when the address is stable and present before the
  app starts. If the interface is down at boot, the socket bind fails and
  `ApiServer` retries every 5 seconds; there is no health listener until the
  address exists.
- `127.0.0.1` is the default and the USB/`adb forward` shape. It is not
  reachable from the LAN.

## Security model: trusted LAN only

**This is a trusted-LAN design, not an internet-facing server.**

- Traffic is plain HTTP. The API key is sent in clear text on the wire. Any peer
  that can observe the LAN can capture it.
- The key prevents unauthorised use of the API; it does not provide
  confidentiality and it does not protect the model or prompts from a network
  eavesdropper.
- There is no TLS, no rate limiting, no user accounts and no audit log of
  callers.
- Do not port-forward `8080` or `8081` from the internet. Do not put the phone in
  a router DMZ.

If remote access is needed, put a tunnel with its own authentication in front:
WireGuard, Tailscale, or an SSH tunnel to a host on the same LAN. Authenticate at
the tunnel, keep the phone on loopback or a private interface, and do not expose
the phone's ports directly.

The mandatory-key rule exists to stop an accidental
`llama-server`-on-the-LAN-with-no-auth deployment. It is not a substitute for
network isolation.

## What was tested on the Pixel 7

Installed debug APK: 12,130,691 bytes, SHA-256
`b0e39709af52dec442444799807590a146a47b01b7ed83dc972d06761287fefd`.
The device was serving Qwen3.5-2B Q4_K_M at 32K context throughout. The test key
was generated on the device, used only over the USB forward, cleared at the end,
and is not recorded here. The final state is `127.0.0.1`, no key, `ready`.

`ss` was run through the unprivileged adb shell, which also prints an unrelated
`Cannot open netlink socket: Permission denied` line; the `LISTEN` lines below are
verbatim.

### 1. Default: loopback, no key

```text
$ curl -sS -w '\nHTTP %{http_code}\n' http://127.0.0.1:8080/health
{"state":"ready","model":"Qwen3.5-2B-Q4_K_M.gguf","pid":20357,...,
 "bind_address":"127.0.0.1","api_key_set":false,"exposed_off_host":false,...}
HTTP 200

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v1/models
200

$ adb -s 2A091FDH200AJ1 shell 'ss -ltn | grep -E "8080|8081"'
LISTEN 0 0 127.0.0.1:8081           0.0.0.0:*
LISTEN 0 0 [::ffff:127.0.0.1]:8080  *:*
```

### 2. `bind_address=0.0.0.0`, no key: rejected before any change

```text
$ curl -sS -w '\nHTTP %{http_code}\n' -X POST http://127.0.0.1:8080/settings \
    -H 'Content-Type: application/json' -d '{"bind_address":"0.0.0.0"}'
{"error":"bind_address requires api_key"}
HTTP 400

$ curl -sS http://127.0.0.1:8080/settings
{"...","bind_address":"127.0.0.1",...,"api_key_set":false,...}

$ curl -sS -w '\nHTTP %{http_code}\n' http://127.0.0.1:8080/health
{"state":"ready",...,"bind_address":"127.0.0.1","api_key_set":false,
 "exposed_off_host":false,...}
HTTP 200

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v1/models
200

$ adb -s 2A091FDH200AJ1 shell 'ss -ltn | grep -E "8080|8081"'
LISTEN 0 0 127.0.0.1:8081           0.0.0.0:*
LISTEN 0 0 [::ffff:127.0.0.1]:8080  *:*
```

The listener was not rebound and the server was not restarted. Logcat:

```text
10-17 16:58:49.420 20297 20389 W Phonon  : settings rejected: bind_address requires api_key
```

### 3. Key set, then `bind_address=0.0.0.0`

```text
$ curl -sS -X POST http://127.0.0.1:8080/settings \
    -H 'Content-Type: application/json' -d '{"api_key":"<key>"}'
{"...","bind_address":"127.0.0.1",...,"api_key_set":true,...}
HTTP 200

$ curl -sS -X POST http://127.0.0.1:8080/settings \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer <key>' \
    -d '{"bind_address":"0.0.0.0"}'
{"...","bind_address":"0.0.0.0",...,"api_key_set":true,...}
HTTP 200

$ curl -sS http://127.0.0.1:8080/health
{"state":"ready","model":"Qwen3.5-2B-Q4_K_M.gguf","pid":20443,...,
 "bind_address":"0.0.0.0","api_key_set":true,"exposed_off_host":true,...}
HTTP 200

$ curl -sS -o /dev/null -w '%{http_code}\n' \
    -H 'Authorization: Bearer <key>' http://127.0.0.1:8080/v1/models
200

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v1/models
401

$ curl -sS -o /dev/null -w '%{http_code}\n' \
    -H 'Authorization: Bearer wrong' http://127.0.0.1:8080/v1/models
401

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/settings
401

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/health
200

$ adb -s 2A091FDH200AJ1 shell 'ss -ltn | grep -E "8080|8081"'
LISTEN 0 0 0.0.0.0:8081  0.0.0.0:*
LISTEN 0 0       *:8080       *:*
```

The backend was also checked directly through a temporary
`adb forward tcp:18081 tcp:8081` (loopback on the device), proving that
`llama-server` itself requires the key:

```text
$ curl -sS -o /dev/null -w '%{http_code}\n' \
    -H 'Authorization: Bearer <key>' http://127.0.0.1:18081/v1/models
200

$ curl -sS -i http://127.0.0.1:18081/v1/models
HTTP/1.1 401 Unauthorized
Server: llama.cpp
```

Logcat for the two restarts (key on loopback, then key on `0.0.0.0`):

```text
10-17 16:58:49.627 20297 20414 I Phonon  : server settings changed; restarting llama-server
10-17 16:58:49.936 20297 20414 I Phonon  : starting .../libllama-server.so ... --host 127.0.0.1 --port 8081 ... -lm mmap --api-key ***
10-17 16:58:49.953 20297 20422 I Phonon  : API listening on 127.0.0.1:8080
10-17 16:58:51.981 20297 20421 I Phonon  : ready after 2s, pid=20416
10-17 16:58:52.211 20297 20414 I Phonon  : server settings changed; restarting llama-server
10-17 16:58:52.525 20297 20414 I Phonon  : starting .../libllama-server.so ... --host 0.0.0.0 --port 8081 ... -lm mmap --api-key ***
10-17 16:58:52.555 20297 20453 I Phonon  : API listening on 0.0.0.0:8080
10-17 16:58:54.587 20297 20452 I Phonon  : ready after 2s, pid=20443
```

The key never appears in logs: the launch line masks it as `--api-key ***`.

A separate rebind was sampled every ~200 ms to verify that `ready` waits for
both sides. The operator listener came back before the backend finished
loading, and `/health` reported `loading`, then `ready`:

```text
t=98ms   HTTP 000 state=-
t=406ms  HTTP 000 state=-
t=641ms  HTTP 503 state=loading
...
t=2307ms HTTP 503 state=loading
t=2565ms HTTP 200 state=ready
```

### 4. Restore `127.0.0.1`, clear the key

One request carries both changes, so the guard never sees a transient
`0.0.0.0` + blank key combination:

```text
$ curl -sS -X POST http://127.0.0.1:8080/settings \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer <key>' \
    -d '{"bind_address":"127.0.0.1","api_key":""}'
{"...","bind_address":"127.0.0.1",...,"api_key_set":false,...}
HTTP 200

# 2 s later, with no POST /server/start:
$ curl -sS http://127.0.0.1:8080/health
{"state":"ready","model":"Qwen3.5-2B-Q4_K_M.gguf","pid":20473,...,
 "bind_address":"127.0.0.1","api_key_set":false,"exposed_off_host":false,...}
HTTP 200

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/v1/models
200

$ adb -s 2A091FDH200AJ1 shell 'ss -ltn | grep -E "8080|8081"'
LISTEN 0 0 127.0.0.1:8081           0.0.0.0:*
LISTEN 0 0 [::ffff:127.0.0.1]:8080  *:*
```

Logcat:

```text
10-17 16:58:54.994 20297 20414 I Phonon  : server settings changed; restarting llama-server
10-17 16:58:55.325 20297 20414 I Phonon  : starting .../libllama-server.so ... --host 127.0.0.1 --port 8081 ... -lm mmap
10-17 16:58:55.358 20297 20479 I Phonon  : API listening on 127.0.0.1:8080
10-17 16:58:57.386 20297 20478 I Phonon  : ready after 2s, pid=20473
```

The final stored settings were verified on the device: `bind_address` is
`127.0.0.1` and `api_key` is empty; the test key was removed from the host.

## What could not be tested

**The off-host path was not exercised end to end.** The Pixel still has no
non-loopback network interface: `ip -4 addr` shows only `lo`, Wi-Fi is disabled
and no Ethernet adapter is attached. No second machine could reach the phone
over a LAN, so these remain untested:

- a request from another host to `<phone-ip>:8080`;
- Wi-Fi association, DHCP reservation and address renewal;
- USB-C Ethernet with PD passthrough;
- binding to a specific non-loopback local IPv4 (no such address existed, so
  the validator could only be exercised on rejected values);
- behaviour when a saved specific address disappears and later returns.

The wildcard listener is visible in `ss` as `*:8080` (the JVM's dual-stack
socket) and `0.0.0.0:8081` (llama.cpp). That proves the wildcard bind, not
reachability from another host. Finish the off-host test on a network-enabled
phone with:

```bash
# On the phone: bind_address=<local IPv4 or 0.0.0.0>, api_key=<key>
# From another host on the same LAN:
curl -sS -i http://<phone-ip>:8080/health
curl -sS -i http://<phone-ip>:8080/v1/models -H "Authorization: Bearer $KEY"
curl -sS -i http://<phone-ip>:8080/settings  -H "Authorization: Bearer $KEY"
curl -sS -i http://<phone-ip>:8080/v1/models   # expect 401
```

Also confirm with `ss -ltn` on the phone that the requested address, and only
the requested address, is bound, and that `llama-server --host` matches it in
`/logs`.

## Off-host verification (2026-10-17, GrapheneOS Pixel 7)

Device: Pixel 7, GrapheneOS user build, Wi-Fi on VLAN 10, address
`10.0.10.195/24`, app installed from this branch.

Steps and results:

1. Set `api_key` over the USB-forwarded loopback port → 200.
2. Set `bind_address=0.0.0.0` **with the bearer token** → 200; `bind_address`
   persisted as `0.0.0.0`; listeners became `*:8080` (operator) and
   `0.0.0.0:8081` (llama-server).
3. From sasquatch over the LAN:
   - `GET /health` → **200**;
   - `GET /v1/models` without a key → **401**;
   - `GET /v1/models` with the key → **200**;
   - a completion returned `Hello!` at **14.36 tok/s decode** in 1.19 s.
4. LiteLLM deployment `phone-b` pointed at `http://10.0.10.195:8080/v1` with the
   key; `GET /v1/models` through the gateway lists `phone-a` and `phone-b`, and a
   completion for `phone-b` returned correctly.

### Issue found

Posting the `bind_address` change **without** the bearer token returned
`http=000` — the connection was closed rather than a `401`. Control endpoints
should answer `401` so a client can distinguish an auth failure from a dead
server. Not fixed yet.

### Still unverified

Two phones serving at once. The USB-attached stock Pixel was offline during this
test (its cable no longer enumerates), so only `phone-b` was reachable. The
pinned-alias concurrency test needs both devices attached.
