# Silence Hook Runtime Roadmap (No IPC Variant)

## 1. Goals

- Keep UI refresh behavior unchanged in app process.
- Let `system_server` hook maintain its own background `ProcList` cache.
- Avoid IPC/binder for now to reduce crash risk and complexity.
- Keep hook decisions low-power and debounced.
- Make debugging observable even with two independent data paths.

## 2. Runtime Model

Two loops, independent:

- `UI Loop` (app process):
  - Current process page refresh behavior stays as-is.
  - Used for display/manual controls.

- `Hook Loop` (system_server):
  - Holds in-memory `ProcCache` snapshot:
    - package -> pids
    - pid -> procState/background/foreground
    - last seen timestamp
  - Uses event-driven updates first.
  - Uses periodic pull as fallback and correction.

No shared in-memory object between processes.

## 3. Hook Triggering Strategy

### 3.1 Event path (fast path)

On `movedToBackground/Foreground` signals:

1. Update local `ProcCache` entry only (no full process list pull).
2. Recompute package-level desired state from cache.
3. Run debounce + minimum switch interval gate.
4. Evaluate freeze conditions for impacted package only.
5. Apply freeze/unfreeze if state changed.

### 3.2 Pull path (fallback path)

Run periodic snapshot in hook process:

1. Pull process status list into `ProcCache`.
2. Reconcile stale/missing pids.
3. Scan packages from `FreezeList`.
4. Re-evaluate rules and perform corrective freeze/unfreeze.

### 3.3 Adaptive interval

Base interval: `30s` (configurable).

If no meaningful event for a window:

- 30s -> 45s -> 60s -> 90s -> 120s (cap)

When event appears:

- Reset to base interval immediately.

## 4. Rule Evaluation Plan

Per package from `FreezeList`:

- Target processes:
  - `ALL` or selected child process names.
- Guard conditions (`dont_freeze_when`):
  - `AUDIO`
  - `NETWORK`
  - `VISIBLE`

Decision:

- Freeze when package is background and all guards are false.
- Unfreeze when foreground or any guard becomes true.

## 5. Debugging Without IPC

Core issue: two independent loops are hard to compare.

Use file-based debug artifacts (not IPC):

- Hook writes compact JSON trace to app-readable file:
  - `/data/user_de/0/cn.himpqblog.slience/files/hook_trace.jsonl`
- One line per decision/event:
  - timestamp
  - source (`event` / `poll`)
  - package
  - desired state
  - applied action
  - guard snapshot (`AUDIO/NETWORK/VISIBLE`)
  - debounce/interval skip reason (if any)

UI can provide:

- "Import hook trace" button (read-only).
- simple timeline view for per-package transitions.

This avoids binder/IPC but still gives deterministic postmortem visibility.

## 6. Data Contracts

### 6.1 FreezeList runtime file

- Continue using runtime writable file:
  - `/data/user_de/0/cn.himpqblog.slience/files/FreezeList.json`
- Hook and app both read same file.
- App writes updates atomically (write temp + rename).

### 6.2 Hook cache in memory

Not shared externally. Rebuilt by:

- event updates
- periodic pulls

## 7. Rollout Steps

1. Introduce `HookProcCache` model in `HookLegacy`.
2. Move event handling to cache-first update path.
3. Add periodic pull worker in hook process.
4. Add adaptive interval controller.
5. Integrate rule evaluation (`ALL`, child process names, guards).
6. Add file trace writer + UI trace reader.
7. Tune debounce/interval constants from real device logs.

## 8. Suggested Initial Constants

- `STABLE_STATE_WINDOW_MS = 1200`
- `PACKAGE_SIGNAL_DEBOUNCE_MS = 1500`
- `MIN_COMMIT_SWITCH_INTERVAL_MS = 2500`
- `HOOK_POLL_BASE_INTERVAL_SEC = 30`
- `HOOK_POLL_MAX_INTERVAL_SEC = 120`
- `HOOK_IDLE_ESCALATION_STEP_SEC = 15`

## 9. Risk Notes

- Root shell in `system_server` can still be expensive; must batch commands.
- Guard checks (`dumpsys`) are costly; cache and throttle them.
- File trace writer must cap size and rotate.
- Rule writes from UI need atomic file replacement to avoid partial reads.
