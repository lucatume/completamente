# Design: Completion Server Management

## Goal

Allow the plugin to detect, start, stop, and monitor the llama.cpp completion server. When the server URL is a local address, the plugin should offer to manage the server lifecycle automatically.

## Non-Goals

- Remote server management (non-localhost URLs are assumed externally managed)
- Model downloading (the user must have the model file already)
- Supporting multiple concurrent servers or slots
- Auto-updating llama.cpp binaries

## Concepts

**Managed server**: A llama-server process started by the plugin. The plugin owns its lifecycle: it started it, it will kill it.

**External server**: A server the user started independently. The plugin connects to it but does not manage its lifecycle.

**Local URL**: A server URL matching `localhost:*`, `127.0.0.1:*`, or `127.0.0.0:*` (and the `http://` variants). Only local URLs are eligible for managed server offers.

## Architecture

### New Settings Fields

Add to `Settings` / `SettingsState`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `llamaServerBinaryPath` | `String` | `""` | Path to `llama-server` binary |
| `modelPath` | `String` | `""` | Path to the GGUF model file |
| `serverCtxSize` | `Int` | `8192` | Context window size for managed server |

These are configured in the settings panel and used when starting a managed server.

### ServerManager (Project-level Service)

A new project-level service that owns the managed server process.

```
class ServerManager(private val project: Project) : Disposable {
    enum class ServerState { UNKNOWN, RUNNING, STOPPED, STARTING }
    enum class OwnershipState { UNMANAGED, MANAGED }

    val serverState: ServerState          // current observed state
    val ownershipState: OwnershipState    // whether we started it

    fun checkServerHealth(): ServerState  // GET /health or similar probe
    fun startServer(): Boolean            // launch llama-server process
    fun stopServer()                      // kill managed process
    fun dispose()                         // kill on project close
}
```

**Health check:** `GET {serverUrl}/health` (llama-server responds 200 when ready). Alternatively, attempt a TCP connection to the port. The `/health` endpoint is more reliable as it confirms the server is actually llama-server and has loaded the model.

**Process management:** Use `ProcessBuilder` to launch `llama-server` with the flags from discovery doc #28. Hold the `Process` reference. On `dispose()`, call `process.destroyForcibly()`.

**State transitions:**
```
UNKNOWN → (health check) → RUNNING or STOPPED
STOPPED → (startServer) → STARTING → (health check passes) → RUNNING
RUNNING → (stopServer, managed only) → STOPPED
RUNNING → (dispose, managed only) → STOPPED
```

### First-Completion Check

When `FimSuggestionManager.showSuggestion()` is called for the first time in a session:

1. `ServerManager.checkServerHealth()`
2. If `RUNNING` → proceed normally, no further checks needed
3. If `STOPPED` and URL is local:
   - Check that `llamaServerBinaryPath` and `modelPath` are configured
   - If not configured → show notification: "Completion server is not running. Configure the server binary and model path in Settings → Tools → completamente."
   - If configured → show notification with actions:
     - **"Start Server"** → `ServerManager.startServer()`, set `ownershipState = MANAGED`
     - **"Not Now"** → set a session flag `userDeclinedServerStart = true`, suppress future checks
4. If `STOPPED` and URL is not local → show notification: "Completion server at {url} is not reachable." (no start offer)

**Session flag:** `userDeclinedServerStart` lives in `ServerManager` as a boolean. It resets when the project is reopened (since `ServerManager` is project-scoped).

### Notification Mechanism

Use IntelliJ's `NotificationGroup` + `Notification` API:

```kotlin
val NOTIFICATION_GROUP = NotificationGroupManager.getInstance()
    .getNotificationGroup("completamente")

// In plugin.xml:
// <notificationGroup id="completamente" displayType="STICKY_BALLOON"/>
```

Sticky balloon ensures the user sees it and can act on it. The notification includes action links ("Start Server", "Not Now").

### Settings UI Changes

Restructure the settings panel into three groups:

```
Settings → Tools → completamente

┌─ FIM Suggestions ──────────────────────────────┐
│  Server URL:        [http://localhost:8017    ] │
│  ☑ Automatic suggestions                       │
│  Recent diffs:      [10                       ] │
└─────────────────────────────────────────────────┘

┌─ Server Management ────────────────────────────┐
│  llama-server binary: [/usr/local/bin/llama-se] │
│  Model file:          [/path/to/model.gguf    ] │
│  Context size:        [8192                   ] │
│                                                 │
│  Status: ● Running (managed)                    │
│  [Stop Server]                                  │
│                                                 │
│  — or when stopped: —                           │
│  Status: ○ Not running                          │
│  [Start Server]                                 │
└─────────────────────────────────────────────────┘

┌─ Ring Buffer (Extra Context) ──────────────────┐
│  Number of chunks:   [16                      ] │
│  Chunk size (lines): [64                      ] │
│  Max queued chunks:  [16                      ] │
└─────────────────────────────────────────────────┘
```

**Status indicator:** A label that updates when the panel is opened (or via a refresh button). Shows one of:
- `● Running (managed)` — green dot, with "Stop Server" button
- `● Running (external)` — green dot, no stop button
- `○ Not running` — gray dot, with "Start Server" button (only if binary/model configured)
- `○ Not running (binary not configured)` — gray dot, no button

**Start/Stop buttons:** When clicked from the settings panel, the server is considered "managed" (same as if started from the notification).

### Server Startup Command

When starting a managed server, the plugin builds:

```
<llamaServerBinaryPath> \
    --model <modelPath> \
    --ctx-size <serverCtxSize> \
    --host 127.0.0.1 \
    --port <extracted-from-serverUrl> \
    --parallel 1 \
    --cache-prompt \
    --temp 0.0
```

The host and port are extracted from the configured `serverUrl`. If the URL is `http://localhost:8017`, the server binds to `127.0.0.1:8017`.

### Server Lifecycle on Project Close

`ServerManager` implements `Disposable`. Its `dispose()` method:

1. If `ownershipState == MANAGED` and process is alive → `process.destroyForcibly()`
2. Wait up to 5 seconds for process termination
3. Log the outcome

This is registered as a project-level service, so IntelliJ calls `dispose()` when the project closes or the IDE shuts down.

### Adding `cache_prompt` to Requests

As a side-effect of this work, add `cache_prompt: true` to the `CompletionRequestBody` (see discovery #28). This is independent of server management but maximizes performance of both managed and external servers.

## Alternatives Considered

### Using `/infill` endpoint instead of `/completion`

llama-server has a dedicated `/infill` endpoint for FIM. However, the SweepAI model uses a custom prompt format with `<|file_sep|>` tokens that doesn't map to the standard prefix/suffix/middle infill API. Sticking with `/completion` and our own prompt construction gives us full control.

### Auto-detecting llama-server binary via PATH

Could scan `$PATH` for `llama-server` instead of requiring explicit configuration. Rejected because: (1) the user might have multiple versions, (2) explicit is better than implicit for a tool that will bind to a port and consume GPU resources, (3) trivial to add later if desired.

### Using IntelliJ's `OSProcessHandler` instead of raw `Process`

IntelliJ provides `OSProcessHandler` for managed processes with output capture and lifecycle callbacks. This is worth considering during implementation — it handles edge cases like zombie processes better than raw `Process`. Trade-off: more IntelliJ API coupling vs. robustness.

## Compromises

- **No model auto-download:** The user must obtain the model file themselves. This keeps the plugin simple and avoids network operations. The harness script `06-harness-download-sweepai-model.py` exists for reference.
- **Single server only:** No support for switching between multiple models or running multiple servers. One `serverUrl`, one managed process.
- **No output capture in settings UI:** The managed server's stdout/stderr is not displayed in the settings panel. If the server fails to start, the user sees "Not running" and must check logs. A future enhancement could show last N lines of server output.
