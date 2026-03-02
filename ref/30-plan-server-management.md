# Plan: Server Management Implementation

Based on design #29. Steps are ordered by dependency.

## Step 1: Add `cache_prompt` to request body

**Files:** `src/main/kotlin/com/github/lucatume/completamente/completion/fim.kt`

- Add `@SerialName("cache_prompt") val cachePrompt: Boolean = true` to `CompletionRequestBody`
- No other changes needed — the field will be serialized automatically

**Checkpoint:** Existing completion requests now include `cache_prompt: true`. Manual test: trigger a completion, inspect server logs to confirm prompt caching is active.

## Step 2: Extend Settings with server management fields

**Files:**
- `src/main/kotlin/com/github/lucatume/completamente/services/Settings.kt`
- `src/main/kotlin/com/github/lucatume/completamente/services/SettingsState.kt`

Add three fields to both:
- `llamaServerBinaryPath: String = ""`
- `modelPath: String = ""`
- `serverCtxSize: Int = 8192`

In `SettingsState`, add the mutable properties and update `toSettings()`.

**Checkpoint:** Settings persist and load correctly across IDE restarts.

## Step 3: Create `ServerManager` service

**File:** `src/main/kotlin/com/github/lucatume/completamente/services/ServerManager.kt` (new)

Implement the project-level service:

1. **State enums:** `ServerState { UNKNOWN, RUNNING, STOPPED, STARTING }`, `OwnershipState { UNMANAGED, MANAGED }`
2. **`checkServerHealth(): ServerState`** — HTTP GET to `{serverUrl}/health`, return `RUNNING` (200) or `STOPPED` (connection refused / non-200). Use a short connect timeout (2s). Run on a background thread.
3. **`startServer(): Boolean`** — Validate binary/model paths exist. Extract host/port from `serverUrl`. Build the command line (see design #29). Launch via `ProcessBuilder`. Poll `/health` every 500ms up to 15s. Return true if healthy, false on timeout.
4. **`stopServer()`** — If `ownershipState == MANAGED` and process alive, call `process.destroyForcibly()`. Wait up to 5s. Reset state.
5. **`dispose()`** — Call `stopServer()`.
6. **`userDeclinedServerStart: Boolean`** — Session flag, defaults to `false`.

Register in `plugin.xml`:
```xml
<projectService serviceImplementation="...services.ServerManager"/>
```

**Checkpoint:** Can programmatically start and stop llama-server. Health check returns correct state.

## Step 4: Register notification group

**File:** `src/main/resources/META-INF/plugin.xml`

Add:
```xml
<notificationGroup id="completamente" displayType="STICKY_BALLOON"/>
```

**Checkpoint:** Notification group is registered and available at runtime.

## Step 5: Add first-completion server check

**File:** `src/main/kotlin/com/github/lucatume/completamente/fim/FimSuggestionManager.kt`

In `showSuggestion()`, before the first completion request:

1. Add a `private var serverChecked = false` flag.
2. On first call where `!serverChecked`:
   - Set `serverChecked = true`
   - Call `ServerManager.checkServerHealth()` on a background thread
   - If `RUNNING` → proceed
   - If `STOPPED` and URL is local:
     - If binary/model not configured → show info notification (configure in settings)
     - If configured → show notification with "Start Server" and "Not Now" actions
   - If `STOPPED` and URL is not local → show warning notification (server unreachable)
   - If user clicks "Not Now" → set `ServerManager.userDeclinedServerStart = true`
   - If user clicks "Start Server" → call `ServerManager.startServer()` on background thread, then proceed with completion

**Local URL detection:** Parse `serverUrl` and check host against `localhost`, `127.0.0.1`, `127.0.0.0`, `::1`.

**Checkpoint:** Opening a project and triggering a completion with the server down shows the notification. Clicking "Start Server" launches the server and completions work. Clicking "Not Now" suppresses the notification for the session.

## Step 6: Update settings UI

**File:** `src/main/kotlin/com/github/lucatume/completamente/settings/SettingsConfigurable.kt`

Add a "Server Management" group between "FIM Suggestions" and "Ring Buffer":

1. **Fields:** `llamaServerBinaryPath` (text field with browse button via `textFieldWithBrowseButton()`), `modelPath` (text field with browse button), `serverCtxSize` (text field).
2. **Status label:** A `JBLabel` that shows the current server state. Populated when `createComponent()` is called by running `ServerManager.checkServerHealth()`.
3. **Start/Stop button:** A `JButton` whose label and action depend on state:
   - If `STOPPED` and paths configured → "Start Server" button → calls `ServerManager.startServer()`
   - If `RUNNING` and `MANAGED` → "Stop Server" button → calls `ServerManager.stopServer()`
   - If `RUNNING` and `UNMANAGED` → no button (just status display)
   - If paths not configured → no button, status shows "(binary not configured)"
4. **Refresh:** After start/stop, update the status label.

Use IntelliJ's Kotlin UI DSL (`panel { ... }`) for layout consistency with existing settings.

For file browse buttons, use `textFieldWithBrowseButton()` with `FileChooserDescriptorFactory.createSingleFileDescriptor()`.

**Checkpoint:** Settings panel shows server status. Can start/stop managed server from settings. File browse dialogs work for binary and model paths.

## Step 7: Wire ServerManager disposal to project lifecycle

**File:** `src/main/kotlin/com/github/lucatume/completamente/startup/CompletamenteStartupActivity.kt`

In `execute()`, get the `ServerManager` service instance to ensure it's initialized. The project-level service's `dispose()` is automatically called by IntelliJ when the project closes — no extra wiring needed beyond registering it as a `projectService` that implements `Disposable`.

**Checkpoint:** Start managed server, close project → server process is killed. Start managed server, quit IDE → server process is killed.

## Step 8: Tests

**Files:** New test files in `src/test/`

1. **`ServerManager` unit tests:**
   - `checkServerHealth()` returns `RUNNING` when server responds 200
   - `checkServerHealth()` returns `STOPPED` when connection refused
   - `startServer()` builds correct command line
   - `stopServer()` kills managed process
   - `dispose()` kills managed process
   - `userDeclinedServerStart` flag prevents re-prompting

2. **Local URL detection tests:**
   - `http://localhost:8017` → local
   - `http://127.0.0.1:8017` → local
   - `http://192.168.1.100:8017` → not local
   - `https://my-server.example.com:8017` → not local

3. **Settings serialization tests:**
   - New fields persist and load correctly
   - Default values are correct

**Checkpoint:** All tests pass.
