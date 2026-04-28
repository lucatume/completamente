# completamente

<!-- Plugin description -->
Code writing helper from local models.
"Completamente" means "completely" in Italian and it's also the union of the words "complete" and "mind".

This plugin provides fill-in-the-middle (FIM) inline completions powered by a local [llama.cpp][3] server, plus an Order 89 code-transformation command that delegates to a user-configured agentic CLI. No cloud APIs, no telemetry from the plugin — everything you run is under your control.

Inspired by two great projects:
- [llama.vim][1] — the FIM completion engine this plugin ports to the IntelliJ platform
- [ThePrimeagen/99][2] — the code transformation workflow behind Order 89

All the good ideas in this code come from these projects, all the bad ideas are my mistakes.
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

### Server

The FIM completion side of the plugin needs a local server exposing the llama.cpp `/infill` endpoint. [llama.cpp][3] is the recommended option. For example:

```
llama-server --port 8012 --fim-qwen-30b-default
```

Point the plugin at it by setting the **Server URL** (default: `http://127.0.0.1:8012`) in `Settings/Preferences` -> `Tools` -> `completamente`.

Order 89 does not use this server. It shells out to a configurable agentic CLI of your choice — see [Order 89](#order-89) below.

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

Inspired by [ThePrimeagen/99][2]. Select text (or place your cursor for insertion), hit `Ctrl+Alt+8` (`Opt+Cmd+8` on macOS), type a prompt, and the selection is replaced with code returned by a configurable agentic CLI.

The plugin builds a prompt containing:

1. **Your instruction** — the prompt you type in the dialog
2. **The file under edit** — referenced by absolute POSIX path; for files larger than 200k characters only a 50k window each side of the selection is embedded
3. **The selection** — its text and its `start_line:start_col`–`end_line:end_col` range
4. **Explicit instruction to return the modified selection as a single fenced code block**, never to edit files directly (concurrent invocations on overlapping ranges would otherwise corrupt the buffer through line drift)

The prompt is written to a temp file. The configured CLI is launched with its working directory set to the project root, the temp file path substituted into the command, and its STDOUT post-processed (fenced-block extraction, prose stripping, indentation matching, trailing-newline preservation) before being spliced back into the editor. The temp file is always cleaned up.

ESC during a running invocation destroys the CLI process (force-killed after a 250 ms grace period) and discards the temp file.

| Setting | Default | Description |
|---------|---------|-------------|
| Order 89 CLI command | `pi --tools read,grep,find,ls @"%%prompt_file%%" -p "Execute the instructions in the files"` | Shell-tokenized command. `%%prompt_file%%` is replaced at runtime with the absolute path to the generated prompt file. The process runs with its working directory set to the current project root. |

Any agentic CLI that reads a file path argument and writes the answer to STDOUT will work — `pi` is just the default.

The configured command is executed through your login shell (`$SHELL -lc`), so PATH and other init from `~/.zprofile`/`~/.bash_profile` are picked up just as in your interactive terminal. Standard shell quoting applies — wrap paths with spaces in `"..."` and the shell will keep them intact.

## Running the FIM server

The FIM completion side of the plugin needs a llama.cpp server reachable on the configured **Server URL**.

```bash
llama-server --fim-qwen-30b-default
```

Order 89 does not connect to this server, so a single llama.cpp instance with default sizing (the preset's `--fit` auto-sizing) is all you need. If you previously ran a second llama.cpp server for Order 89 you can shut it down.

## Support

None.
This project is a personal tool I'm actively developing for my own understanding and learning.

---
Plugin based on the [IntelliJ Platform Plugin Template][5].

[1]: https://github.com/ggml-org/llama.vim
[2]: https://github.com/ThePrimeagen/99
[3]: https://github.com/ggml-org/llama.cpp
[4]: https://huggingface.co/Qwen/Qwen3-Coder-30B-A3B-Instruct
[5]: https://github.com/JetBrains/intellij-platform-plugin-template
