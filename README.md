# completamente

<!-- Plugin description -->
Code writing helper from local models.
"Completamente" means "completely" in Italian and it's also the union of the words "complete" and "mind".

This plugin provides fill-in-the-middle (FIM) and next-edit prediction (NEP) completions for IntelliJ IDEs using a local [llama.cpp](https://github.com/ggml-org/llama.cpp) server with the [SweepAI 1.5B](https://huggingface.co/sweepai/sweep-next-edit-1.5B) model.

This plugin is my attempt at a port of the excellent [llama.vim][1] plugin to the IntelliJ platform.
All the good ideas in this code come from the original plugin, all the bad ideas are my mistakes.
<!-- Plugin description end -->

## Installation

The only way to install this plugin is to build it from the command-line.

```
git clone https://github.com/lucatume/completamente.git
cd completamente
./gradlew buildPlugin
```

Then install the plugin in the IDE using `Settings/Preferences` -> `Plugins` -> `Install plugin from disk...`.

## Usage

### Managed server

The plugin can start and manage a `llama-server` process for you. Open `Settings/Preferences` -> `Tools` -> `completamente` and click **Start Server**.

The default server command is:

```
llama-server --host {{host}} --port {{port}} -hf sweepai/sweep-next-edit-1.5B --ctx-size 8192 --parallel 1 --cache-prompt --temp 0.0
```

- `{{host}}` and `{{port}}` are replaced at runtime from the **Server URL** setting.
- The `-hf` flag downloads the model from HuggingFace on first run (this may take a few minutes).
- You can edit the command to add custom flags or use a different model.

When the server is managed, the Server URL field is locked to match the running server.

### External server

You can also run `llama-server` yourself and point the plugin at it by setting the **Server URL** (default: `http://localhost:8017`).

### Configuration

Plugin settings are at `Settings/Preferences` -> `Tools` -> `completamente`:

| Setting | Default | Description |
|---------|---------|-------------|
| Server URL | `http://localhost:8017` | URL of the llama.cpp completion server |
| Automatic suggestions | On | Show FIM suggestions while typing |
| Recent diffs | 10 | Max recent edit diffs sent as context |
| Server command | (see above) | Full command to start the managed server |
| Number of chunks | 16 | Max ring buffer chunks as extra context |
| Chunk size (lines) | 64 | Lines per ring buffer chunk |
| Max queued chunks | 16 | Max chunks in the queue |

### Completions

Completions appear as ghost text in the editor. Press **Tab** to accept the current sub-edit, or continue typing to dismiss.

The plugin sends a prompt to the server containing:

1. **Definition chunks** -- cross-file symbol definitions near the cursor
2. **Ring buffer chunks** -- recent code from other files you visited
3. **Recent diffs** -- your recent edits across the project
4. **Original file** -- the file content when it was opened
5. **Current file** -- the file content now (windowed around the cursor)

A token budget (8192 tokens total, 512 reserved for output) ensures the prompt fits within the model's context window. Definition chunks and ring buffer chunks are included only if space remains after the mandatory sections.

## Support

None.
This project is a personal tool I'm actively developing for my own understanding and learning.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[1]: https://github.com/ggml-org/llama.vim
