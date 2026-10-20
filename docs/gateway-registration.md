# Gateway registration

Optional, off by default: Settings → Gateway. When on, the app registers this
phone with a LiteLLM proxy so consumers use one URL and one key instead of a
per-phone address.

## What it does

On serve start (state `READY`), on a settings save, and when the phone's LAN
address changes, the app calls the gateway's model-management API:

```
model_name: phone-<shortid>
litellm_params:
  model: openai/<alias>
  api_base: http://<phone-lan-ip>:<port>/v1
  api_key: <the node's API key>
  timeout: 1800
  stream_timeout: 1800
  max_retries: 0
```

`phone-<shortid>` comes from a generated id stored once in settings, so a
restart, a model switch or an IP change updates the same entry.

On an explicit stop the app attempts `POST /model/delete` best-effort. An
unreachable gateway is not allowed to block shutdown; the stale entry is updated
on the next start.

## What LiteLLM actually does (measured)

These are the behaviours the implementation is built on, from a live
`main-latest` with `store_model_in_db: true` and Postgres:

| Call | Behaviour |
|---|---|
| `POST /model/new` (duplicate name) | **inserts another row** with a new uuid — no 409 |
| `GET /model/info` | the only way to learn an entry's `model_info.id` |
| `POST /model/update` | requires `model_info.id`; a body with only `model_name` → 400 `Authentication Error, model_info not provided` |
| `POST /model/delete` | `{"id": ...}` |
| `POST /model/new` with no database | 500 `No DB Connected. Here's how to do it - …/virtual_keys` |

So idempotency is **lookup, then update**, not "create and handle 409". The
client also converges duplicate rows left by an older build: it keeps the first
id and deletes the extras. The 409 path is still handled, for proxies that
behave that way.

The no-database error is surfaced with the gateway's own sentence plus
`the gateway must run with store_model_in_db: true and a DATABASE_URL` — the
operator's actual next step.

## Security

- The registration key is stored with AES-256-GCM in the Android Keystore, in
  its own preferences file. There is no plaintext fallback: if the Keystore is
  unavailable the app refuses and says why.
- It never enters the settings JSON API, a log line, argv, the device report, or
  `/health`. Errors are scrubbed of both the registration key and the node key
  before they reach the UI.
- `/health` publishes `gateway: {configured, registered, last_error}` only — not
  even the URL, which can carry credentials in a path.
- The gateway must be on the same LAN or tailnet: the app refuses a public
  address at save time, because a registration whose `api_base` a cloud cannot
  reach is a broken entry that fails later instead of now.
- The key that writes models is not the node's API key; the UI says so and asks
  for a dedicated key where the gateway supports one.

## Failure behaviour

- Gateway down at boot: serving is unaffected; registration reports `last_error`
  and is retried on the next start (or the next address change).
- Wrong key: a clear message, no crash, no partial state — the entry is either
  created/updated or it is not.
- Gateway without a database: the verbatim error and the remediation above.
