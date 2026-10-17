# Host-side tooling

The worker that turns the phone pool into a coding lane. Nothing here runs on a
phone.

- `opencode.json` — provider definitions for the LiteLLM gateway (`litellm/…`)
  and for a phone directly (`phone/…`, useful before the gateway exists), the
  `phone-coder` agent, and the trimmed toolset. Placeholder hostnames must be
  replaced: `litellm.lan`, `phone-a.lan`.
- `run_batch.py` — serial runner. One task at a time; a branch per task; a
  mandatory check command; commit on green, hard reset and branch deletion on
  red; never merges or pushes.
- `tasks.example.jsonl` — task schema and three mechanical examples.

## Use

```bash
export PHONE_URL=http://phone-a.lan:8080     # direct, for bring-up
export PHONE_KEY=...
export LITELLM_KEY=...
export OPENCODE_CONFIG=$PWD/opencode.json

python3 run_batch.py --dry-run --tasks tasks.jsonl
python3 run_batch.py --tasks tasks.jsonl --loop
```

A task may pin a phone with `"model": "litellm/phone-a"`. Pinning preserves the
llama-server KV prefix cache across the task's turns; the pooled
`litellm/gemma-4-e2b` name is for one-shot calls only.

## Rules encoded in the runner

1. A task without a check is not run.
2. A dirty repo is not touched.
3. OpenCode is not allowed to run git.
4. Red means revert, not repair.
5. If the phone stops answering, the batch stops — that is not the task's fault
   and must not be recorded as one.
