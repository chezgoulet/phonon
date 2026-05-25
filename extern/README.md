# extern — External dependency sources

This directory holds pinned upstream sources for native inference engines
used by the sidecar APK.

| Dependency | Method | License | Purpose |
|---|---|---|---|
| `prima.cpp/` | Git submodule | MIT | CPU inference engine (shard mode) |
| `ollitert/` | Pinned release artifact (see README) | Apache 2.0 | NPU inference engine (pool mode) |

## Updating a submodule

```bash
cd extern/prima.cpp
git fetch origin
git checkout <new-tag-or-commit>
cd ..
git add prima.cpp
git commit -m "chore: bump prima.cpp to <version>"
```

## Adding a new submodule

```bash
git submodule add <url> extern/<name>
cd extern/<name>
git checkout <pinned-commit>
cd ../..
git add .gitmodules extern/<name>
git commit -m "feat: add <name> as extern dependency"
```
