# Adding a phone to the pool

The pool is a list of deployments in LiteLLM. Each phone is one deployment with
a pinned alias; the pooled alias spreads one-shot calls across all of them.

## 1. Prepare the phone

```
tools/provision_phone.sh <serial> <host-port> <alias> [model-url]
```

It installs the app, forwards the ports, downloads the model through the app's
own downloader and waits for `/health` to report `ready`. Nothing about the
phone is special-cased in the gateway: it is an OpenAI-compatible endpoint with
a key.

Record its measurements in `devices/<soc>.yaml` — the app's **Contribute this
phone** action emits that file from live detection and the phone's own tuning
profile.

## 2. Register it

In `gateway/litellm.yaml`, add two entries for the phone — a pinned alias and the
pooled alias — using the phone's LAN address and key:

```
  - model_name: phone-b
    litellm_params:
      model: openai/<alias>
      api_base: http://<phone-b>:8080/v1
      api_key: os.environ/PHONE_B_KEY
      timeout: 1800
      stream_timeout: 1800
      max_retries: 0
```

`max_retries: 0` is deliberate: a retry re-runs the whole prompt, which on a
phone is minutes.

## 3. Choose the routing

- **Agent work uses the pinned alias.** llama-server caches the KV prefix per
  slot; spreading an agent's turns across phones throws that cache away and
  every turn re-prefills the system prompt.
- **One-shot calls use the pooled alias.** No prefix worth preserving.

Parallelism is the number of phones. Two phones run two tasks at once, each at
one phone's pace; they do not make a single task faster.

## 4. Failover

There is none today. `max_retries: 0` and a pinned alias mean that if a phone
drops, every task pinned to it fails. That is a deliberate trade — a retry costs
the full prompt again — but it is a limitation, not a feature. Before running
unattended work across several phones, decide whether it is acceptable; if it is
not, the change belongs in the gateway (retry with the prompt cache accounted
for) rather than in the phone.

## What has been verified

One phone has been measured and served agent tasks continuously. The two-phone
configuration in `gateway/litellm.yaml` is written and YAML-valid but has **not**
been run with two physical phones: the second Pixel 7 does not enumerate over
the current USB hub, and a phone on a charge-only cable or an unpowered hub is
not a test.

A two-node run needs: both phones provisioned, a powered hub with PD passthrough
per phone (or one phone at a time), and a serial batch of two tasks against the
two pinned aliases. Until that exists, treat the second slot as unverified.
