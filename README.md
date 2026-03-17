# completamente

<!-- Plugin description -->
Code writing helper from local models.
"Completamente" means "completely" in Italian and it's also the union of the words "complete" and "mind".

This plugin provides fill-in-the-middle (FIM) inline completions for IntelliJ IDEs using a local [llama.cpp](https://github.com/ggml-org/llama.cpp) server's `/infill` endpoint. Designed around the [SweepAI 1.5B](https://huggingface.co/sweepai/sweep-next-edit-1.5B) FIM model but works with any model served via llama.cpp.

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
| Server URL | `http://127.0.0.1:8012` | URL of the llama.cpp `/infill` endpoint |
| Context size | `32768` | Total token budget |
| Max predicted tokens | `128` | Tokens per completion |
| Automatic suggestions | On | Show FIM suggestions while typing |
| Number of chunks | 16 | Max ring buffer chunks as extra context (0 = disabled) |
| Chunk size (lines) | 64 | Lines per ring buffer chunk |
| Max queued chunks | 16 | Max chunks in the queue |

### Completions

Completions appear as ghost text in the editor. Press **Tab** to accept, or continue typing to dismiss.

The plugin sends a request to the llama.cpp `/infill` endpoint containing:

1. **Current file** -- split at the cursor into prefix and suffix (windowed for large files)
2. **Structure chunks** -- cross-file symbol definitions resolved via PSI references
3. **Ring buffer chunks** -- recent code from files you opened, saved, or copied from

A token budget (`contextSize - nPredict - 20`) ensures the prompt fits. File content gets first priority; remaining budget is filled greedily with structure chunks, then ring buffer chunks.

### Order 89

Inspired by [ThePrimeagen/99](https://github.com/ThePrimeagen/99). Select text, hit `Ctrl+Alt+8` (`Opt+Cmd+8` on macOS), type a prompt, and the selection is piped through a shell command and replaced with the output.

The default command is:

```
cat {{prompt_file}} | claude --dangerously-skip-permissions --print --output-format text
```

`{{prompt_file}}` is replaced at runtime with a temp file containing the selected text and your prompt.

**Caveat emptor:** the default command runs Claude Code with `--dangerously-skip-permissions`, which means it will execute without asking for confirmation. Consider running inside a sandbox (Docker, a VM, macOS Sandbox, Flatpak, etc.) or replacing the command with something more constrained. The command is fully configurable in settings.

| Setting | Default | Description |
|---------|---------|-------------|
| Order 89 command | (see above) | Shell command to execute. `{{prompt_file}}` is the only placeholder |

## Support

None.
This project is a personal tool I'm actively developing for my own understanding and learning.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[1]: https://github.com/ggml-org/llama.vim
