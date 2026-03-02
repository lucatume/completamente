# Discovery: Using Remote Robot HTTP API for Agent-Driven IDE Debugging

## Overview

This document explores how a CLI agent (e.g. Claude Code) can drive a running IntelliJ IDE instance
over HTTP using the **Remote Robot** plugin's HTTP API — without requiring a JVM client. The goal is
to let an agent interact with the IDE the way a developer would: open files, type code, trigger
completions, inspect ghost text, and observe the plugin's behavior in real time.

---

## 1. Architecture

The Remote Robot framework has two parts:

- **robot-server plugin** — runs inside the IDE, starts an HTTP server (port 8082 in our config)
- **remote-robot client** — normally a Kotlin/Java library, but the server exposes plain HTTP
  endpoints that any process can call

```
┌─────────────────┐         HTTP (port 8082)         ┌──────────────────────┐
│   CLI Agent     │ ◄──────────────────────────────► │   IntelliJ IDE       │
│  (curl / fetch) │    JSON requests/responses        │  + robot-server      │
└─────────────────┘                                   │  + completamente     │
                                                      └──────────────────────┘
```

The agent needs no JVM runtime. All interaction happens over HTTP with JSON payloads.

## 2. Current Project Setup

The project already has Remote Robot configured:

- **Version**: 0.11.23 (in `gradle/libs.versions.toml`)
- **Port**: 8082 (in `build.gradle.kts`, `-Drobot-server.port=8082`)
- **Gradle task**: `./gradlew runIdeForUiTests` launches an IDE instance with the robot-server
  plugin installed
- **Existing tests**: `src/uiTest/kotlin/` contains `BaseUiTest` and `SmokeUiTest`

No additional setup is needed — the infrastructure is already in place.

## 3. HTTP API Surface

The robot-server exposes these endpoint families. All use JSON unless noted otherwise.

### 3.1 Health Check

| Method | Path     | Response    |
|--------|----------|-------------|
| `GET`  | `/hello` | Plain text: `"Hello from idea"` |

```bash
curl http://localhost:8082/hello
```

### 3.2 UI Hierarchy Browser

| Method | Path         | Response |
|--------|--------------|----------|
| `GET`  | `/`          | Interactive HTML page showing full Swing component tree |
| `GET`  | `/hierarchy` | Same as `/` |

Open `http://localhost:8082/` in a browser to explore the component tree and discover XPath
selectors. The page includes a built-in XPath tester.

### 3.3 Find Components by XPath

| Method | Path                              | Description           |
|--------|-----------------------------------|-----------------------|
| `POST` | `/xpath/component`                | Find single component |
| `POST` | `/xpath/components`               | Find all matching     |
| `POST` | `/xpath/{containerId}/component`  | Find within container |
| `POST` | `/xpath/{containerId}/components` | Find all within container |

Request body:
```json
{"xpath": "//div[@class='EditorComponentImpl']"}
```

Response:
```json
{
  "status": "SUCCESS",
  "elementList": [
    {
      "id": "some-uuid",
      "className": "com.intellij.openapi.editor.impl.EditorComponentImpl",
      "x": 100, "y": 200, "width": 800, "height": 600
    }
  ]
}
```

The returned `id` can be used in subsequent calls (screenshots, JS execution in component context,
data extraction).

### 3.4 JavaScript Execution (Key Capability)

These endpoints execute **Rhino JavaScript** inside the IDE process with full access to the JVM
classpath — every IntelliJ API, every Swing class, every plugin class.

| Method | Path                            | Description                    |
|--------|---------------------------------|--------------------------------|
| `POST` | `/js/execute`                   | Fire-and-forget (like `runJs`) |
| `POST` | `/js/retrieveAny`               | Returns a value (like `callJs`) |
| `POST` | `/{componentId}/js/execute`     | Execute in component context   |
| `POST` | `/{componentId}/js/retrieveAny` | Retrieve in component context  |

Request body:
```json
{
  "script": "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length",
  "runInEdt": true
}
```

Response from `/js/retrieveAny`:
```json
{
  "status": "SUCCESS",
  "bytes": [/* Java-serialized return value */],
  "log": ""
}
```

Response from `/js/execute`:
```json
{
  "status": "SUCCESS",
  "message": null,
  "log": ""
}
```

**Note on `runInEdt`**: Set to `true` when the script touches Swing/UI components or IntelliJ's
read/write model. Set to `false` for background work. Most IDE interactions need `true`.

**Note on encryption**: Encryption is off by default. Scripts are sent as plain text. Only enabled
when `-Drobot.encryption.enabled=true` is set (we don't set it).

### 3.5 Screenshots

| Method | Path                      | Description              |
|--------|---------------------------|--------------------------|
| `GET`  | `/screenshot`             | Full IDE screenshot      |
| `GET`  | `/{componentId}/screenshot` | Single component screenshot |

Response is a `ByteResponse` with PNG bytes as a JSON number array in the `bytes` field.

### 3.6 Component Text Data

| Method | Path               | Description                        |
|--------|--------------------|------------------------------------|
| `POST` | `/{componentId}/data` | Get text content and positions  |

Returns `ComponentDataResponse` with `textDataList` entries containing text, position, and
bundle keys.

---

## 4. Practical Recipes for Agent-Driven Debugging

These recipes show how an agent can replicate developer interactions using `curl`. All scripts run
as Rhino JavaScript inside the IDE.

### 4.1 Check IDE State

```bash
# Is the IDE alive?
curl http://localhost:8082/hello

# How many projects are open?
curl -X POST http://localhost:8082/js/retrieveAny \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length",
    "runInEdt": true
  }'
```

### 4.2 Open a File in the Editor

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        .findFileByPath(\"/path/to/file.kt\");
      com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true);
    ",
    "runInEdt": true
  }'
```

### 4.3 Get Current Editor Content and Cursor Position

```bash
curl -X POST http://localhost:8082/js/retrieveAny \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        .getSelectedTextEditor();
      var doc = editor.getDocument();
      var caret = editor.getCaretModel().getOffset();
      var line = doc.getLineNumber(caret);
      JSON.stringify({
        filePath: com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
          .getFile(doc).getPath(),
        content: doc.getText(),
        caretOffset: caret,
        caretLine: line,
        lineCount: doc.getLineCount()
      });
    ",
    "runInEdt": true
  }'
```

### 4.4 Type Text at the Cursor

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        .getSelectedTextEditor();
      var offset = editor.getCaretModel().getOffset();
      com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(
        new java.lang.Runnable({ run: function() {
          editor.getDocument().insertString(offset, \"fun hello() {\");
        }})
      );
    ",
    "runInEdt": true
  }'
```

### 4.5 Move the Caret

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        .getSelectedTextEditor();
      editor.getCaretModel().moveToOffset(42);
    ",
    "runInEdt": true
  }'
```

### 4.6 Simulate Keystrokes (Triggers FimTypingListener)

Direct document edits via `insertString` may not fire `DocumentListener` events the same way
real typing does. To properly trigger the `FimTypingListener` (and its 300ms debounce), simulate
actual keystrokes through AWT:

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        .getSelectedTextEditor();
      var component = editor.getContentComponent();
      var robot = new java.awt.Robot();
      // Type a character - this goes through the full input pipeline
      robot.keyPress(java.awt.event.KeyEvent.VK_A);
      robot.keyRelease(java.awt.event.KeyEvent.VK_A);
    ",
    "runInEdt": false
  }'
```

Alternatively, use IntelliJ's `TypedAction` API for a higher-level approach:

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        .getSelectedTextEditor();
      var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editor)
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
        .build();
      com.intellij.openapi.editor.actionSystem.TypedAction.getInstance()
        .actionPerformed(editor, \"f\", dataContext);
    ",
    "runInEdt": true
  }'
```

### 4.7 Trigger FIM Completion Manually (Alt+Backslash)

Invoke the `RequestFimAction` directly through the action manager:

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        .getSelectedTextEditor();
      var action = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        .getAction(\"com.github.lucatume.completamente.fim.RequestFimAction\");
      var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editor)
        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
        .build();
      var event = new com.intellij.openapi.actionSystem.AnActionEvent(
        null, dataContext,
        com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP,
        action.getTemplatePresentation().clone(),
        com.intellij.openapi.actionSystem.ActionManager.getInstance(), 0
      );
      action.actionPerformed(event);
    ",
    "runInEdt": true
  }'
```

### 4.8 Read FimSuggestionManager State

Inspect the current suggestion state to see if a completion is active and what it contains:

```bash
curl -X POST http://localhost:8082/js/retrieveAny \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var manager = project.getService(
        java.lang.Class.forName(
          \"com.github.lucatume.completamente.fim.FimSuggestionManager\"
        )
      );
      // Use reflection to read private fields
      var cls = manager.getClass();
      var editField = cls.getDeclaredField(\"currentEdit\");
      editField.setAccessible(true);
      var currentEdit = editField.get(manager);
      var editorField = cls.getDeclaredField(\"currentEditor\");
      editorField.setAccessible(true);
      var currentEditor = editorField.get(manager);
      JSON.stringify({
        hasActiveSuggestion: currentEdit != null,
        hasActiveEditor: currentEditor != null,
        editDetails: currentEdit != null ? currentEdit.toString() : null
      });
    ",
    "runInEdt": true
  }'
```

### 4.9 Read DiffTracker State

```bash
curl -X POST http://localhost:8082/js/retrieveAny \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var tracker = project.getService(
        java.lang.Class.forName(
          \"com.github.lucatume.completamente.services.DiffTracker\"
        )
      );
      var diffs = tracker.getRecentDiffs();
      JSON.stringify({
        diffCount: diffs.size(),
        diffs: diffs.stream().map(function(d) {
          return { file: d.getFilePath(), timestamp: d.getTimestamp() };
        }).toArray()
      });
    ",
    "runInEdt": true
  }'
```

### 4.10 Read ChunksRingBuffer State

```bash
curl -X POST http://localhost:8082/js/retrieveAny \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var ring = project.getService(
        java.lang.Class.forName(
          \"com.github.lucatume.completamente.services.ChunksRingBuffer\"
        )
      );
      var chunks = ring.getChunks();
      JSON.stringify({
        chunkCount: chunks.size(),
        chunks: chunks.stream().map(function(c) {
          return { file: c.getFilePath(), size: c.getContent().length() };
        }).toArray()
      });
    ",
    "runInEdt": true
  }'
```

### 4.11 Accept Current Suggestion (Tab)

```bash
curl -X POST http://localhost:8082/js/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "script": "
      var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
      var manager = project.getService(
        java.lang.Class.forName(
          \"com.github.lucatume.completamente.fim.FimSuggestionManager\"
        )
      );
      manager.acceptSuggestion();
    ",
    "runInEdt": true
  }'
```

### 4.12 Take a Screenshot

```bash
# Full IDE screenshot — save to file
curl -s http://localhost:8082/screenshot | python3 -c "
import json, sys
data = json.load(sys.stdin)
with open('ide-screenshot.png', 'wb') as f:
    f.write(bytes(b & 0xff for b in data['bytes']))
"
```

---

## 5. Agent Debugging Workflow

A typical debugging session for completion issues would follow this pattern:

```
1.  Start IDE            ./gradlew runIdeForUiTests
2.  Verify connection    GET /hello
3.  Open target file     POST /js/execute  (FileEditorManager.openFile)
4.  Read editor state    POST /js/retrieveAny  (document text, caret pos)
5.  Type code            POST /js/execute  (TypedAction or insertString)
6.  Wait for debounce    sleep 500ms
7.  Trigger completion   POST /js/execute  (RequestFimAction.actionPerformed)
8.  Wait for response    sleep 1-3s (network round-trip to llama.cpp)
9.  Read suggestion      POST /js/retrieveAny  (FimSuggestionManager fields)
10. Read service state   POST /js/retrieveAny  (DiffTracker, ChunksRingBuffer)
11. Screenshot           GET /screenshot
12. Accept or dismiss    POST /js/execute  (acceptSuggestion / dismissSuggestion)
13. Verify result        POST /js/retrieveAny  (document text after accept)
```

The agent can repeat steps 4-13 in a loop, varying the input code, cursor position, and timing
to reproduce specific completion bugs.

### 5.1 What the Agent Can Observe

| Observable                     | How                                              |
|-------------------------------|--------------------------------------------------|
| Editor content                | `editor.getDocument().getText()`                 |
| Cursor position               | `editor.getCaretModel().getOffset()`             |
| Active suggestion text        | `FimSuggestionManager.currentEdit` (reflection)  |
| Ghost text inlays             | `editor.getInlayModel().getInlineElementsInRange()` |
| Recent diffs                  | `DiffTracker.getRecentDiffs()`                   |
| Chunk ring contents           | `ChunksRingBuffer.getChunks()`                   |
| Plugin settings               | `SettingsState.getInstance()` fields             |
| Open files                    | `FileEditorManager.getOpenFiles()`               |
| IDE errors/exceptions         | `Logger` output or `Messages` dialogs            |
| Visual state                  | Screenshots via `/screenshot`                    |

### 5.2 What the Agent Can Control

| Action                        | How                                              |
|-------------------------------|--------------------------------------------------|
| Open/close files              | `FileEditorManager.openFile()` / `.closeFile()`  |
| Type characters               | `TypedAction.actionPerformed()` or `java.awt.Robot` |
| Move caret                    | `editor.getCaretModel().moveToOffset()`          |
| Select text                   | `editor.getSelectionModel().setSelection()`      |
| Trigger FIM request           | `RequestFimAction.actionPerformed()`             |
| Accept suggestion             | `FimSuggestionManager.acceptSuggestion()`        |
| Dismiss suggestion            | `FimSuggestionManager.dismissSuggestion()`       |
| Change settings               | `SettingsState.getInstance()` field modification |
| Undo/redo                     | `UndoManager.getInstance(project).undo()`        |
| Execute any IDE action        | `ActionManager.getInstance().getAction(id).actionPerformed()` |

---

## 6. Limitations and Caveats

### 6.1 Rhino JavaScript, Not Modern JS
The JS engine is Mozilla Rhino, which supports **ES5** (not ES6+). No arrow functions, no `let`/
`const`, no template literals, no destructuring. Use `var` and `function(){}` everywhere.

### 6.2 Return Values from `/js/retrieveAny`
The `bytes` field contains Java-serialized data. Returning a `String` (e.g. via `JSON.stringify()`)
is the most reliable approach — the agent can then parse the JSON from the deserialized string.
Returning complex Java objects requires Java deserialization on the client side.

### 6.3 EDT Threading
Most IDE operations must run on the Event Dispatch Thread (`runInEdt: true`). Long-running
operations on EDT will freeze the UI. For operations that need both background work and UI
access, use `ApplicationManager.getApplication().invokeLater()` inside the script.

### 6.4 Write Actions
Document modifications require a write action wrapper:
```javascript
com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(
  new java.lang.Runnable({ run: function() { /* modify document */ } })
);
```

### 6.5 Timing
There is no built-in mechanism to wait for async operations (like an HTTP completion response
from llama.cpp). The agent must poll by repeatedly reading state with appropriate delays.

### 6.6 Action ID Discovery
The exact action ID for `RequestFimAction` depends on how it's registered in `plugin.xml`. The
agent can discover all registered actions with:
```javascript
var ids = com.intellij.openapi.actionSystem.ActionManager.getInstance().getActionIdList("");
```

---

## 7. Alternative: MCP Server (IntelliJ 2025.2+)

For comparison, IntelliJ's built-in MCP server provides ~27 tools over the MCP protocol for
file operations, code analysis, search, and refactoring. However:

- It **cannot** trigger code completions
- It **cannot** simulate keystrokes
- It **cannot** read plugin-specific state (FimSuggestionManager, DiffTracker, etc.)
- It **cannot** interact with editor popups, inlays, or ghost text

For debugging completamente's completion behavior, Remote Robot is the only viable approach.
The MCP server would be useful as a complement — for example, to run inspections or check
for errors after a completion is applied.

---

## 8. Next Steps

1. **Verify the recipes** — start the IDE with `./gradlew runIdeForUiTests` and test each curl
   command to confirm they work with the current codebase
2. **Refine JS scripts** — the Rhino scripts for reading `FimSuggestionManager` state may need
   adjustment depending on exact field names and visibility; some fields may need Kotlin property
   accessor names (e.g. `getCurrentEdit()` vs `currentEdit`)
3. **Build an agent tool wrapper** — create a small script or MCP server that wraps these curl
   calls into higher-level operations (e.g. `type_and_complete`, `read_suggestion_state`) for
   cleaner agent integration
4. **Consider a dedicated MCP server** — wrap the Remote Robot HTTP calls in an MCP server so
   Claude Code can use them as native tools during a debugging session
