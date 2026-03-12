# How the 99 Neovim Plugin Uses Claude Code

## Question

How does ThePrimeagen's "99" Neovim plugin invoke Claude Code, and what can we learn from its
approach for the Order 89 feature in completamente?

## Overview

99 is an agentic Neovim plugin that supports multiple AI CLI backends (OpenCode, Claude Code,
Cursor Agent, Kiro, Gemini CLI). It uses Claude Code as a non-interactive subprocess via
`vim.system()`, passing a fully assembled prompt as a positional argument and reading structured
results from a temporary file.

## Provider Architecture

99 defines a `BaseProvider` class with a `make_request(query, context, observer)` method that all
providers share. Each provider only needs to implement `_build_command(query, context)` to return a
command array.

**Source**: `/Users/lucatume/repos/99/lua/99/providers.lua`

### ClaudeCodeProvider command

```lua
function ClaudeCodeProvider._build_command(_, query, context)
  return {
    "claude",
    "--dangerously-skip-permissions",
    "--model",
    context.model,
    "--print",
    query,
  }
end
```

Key flags:
- **`--dangerously-skip-permissions`**: Bypasses all permission prompts. This is the nuclear option
  — Claude Code won't ask for confirmation for any file read/write/shell operation. Note: this is
  different from `--permission-mode auto` which still respects allowlists.
- **`--print`**: Non-interactive mode. Claude processes the query and prints the result to stdout,
  then exits. No conversation loop.
- **`--model`**: Overrides the default model. Default for the provider is `claude-sonnet-4-5`.
- The query (full prompt text) is passed as a positional argument directly on the command line.

### How the request flows

1. **Prompt assembly** (`prompt.lua`, `prompt-settings.lua`): The prompt is built by concatenating
   agent context pieces — markdown rule files walked up from the current file's directory, the
   visual selection or search context, file location metadata, and a `<TEMP_FILE>` path. The prompt
   tells Claude to write its output to the temp file rather than stdout.

2. **Execution** (`providers.lua:76`): `vim.system(command, opts, on_exit)` spawns the process.
   stdout and stderr are captured via callbacks. An observer pattern notifies the UI of progress.

3. **Result retrieval** (`providers.lua:32-54`): On successful exit, the provider reads the temp
   file (`vim.fn.readfile(tmp)`) rather than parsing stdout. This is because Claude Code in
   `--print` mode may include conversational output on stdout, but the prompt instructs it to write
   structured results to the temp file.

4. **Cancellation** (`prompt.lua:321-338`): The process can be killed via SIGTERM if the user
   cancels.

## Prompt Structure

For a **visual replacement** operation (closest to Order 89):

```
[AGENT.md files walked up from current file to project root]
[Visual selection prompt with XML tags]
  <SELECTION_LOCATION>line range</SELECTION_LOCATION>
  <SELECTION_CONTENT>selected code</SELECTION_CONTENT>
  <FILE_CONTAINING_SELECTION>full file</FILE_CONTAINING_SELECTION>
[File location metadata]
[Function text of the range]
<TEMP_FILE>/path/to/tmp</TEMP_FILE>
<MustObey>
  NEVER alter any file other than TEMP_FILE.
  never provide the requested changes as conversational output. Return only the code.
  ONLY provide requested changes by writing the change to TEMP_FILE
  never attempt to read TEMP_FILE. It is purely for output.
  After writing TEMP_FILE once you should be done.
</MustObey>
[User prompt via <Context> and <Prompt> tags]
```

The key insight: 99 tells Claude to write results to a temp file rather than stdout, and wraps
critical instructions in `<MustObey>` tags.

## Comparison with Order 89

| Aspect | 99 (Neovim) | Order 89 (completamente) |
|--------|-------------|-------------------------|
| CLI invocation | `claude --dangerously-skip-permissions --print` | `claude --print --output-format text --permission-mode auto` |
| Prompt delivery | Positional arg on command line | Piped via stdin (`printf ... \| claude`) |
| Output capture | Temp file (Claude writes to it) | stdout (`--print` + `--output-format text`) |
| Permissions | Skip all checks | Auto-approve (safer than skip) |
| Context | Full file, selection, AGENT.md files, XML-tagged | Selected text, language, user prompt only |
| Model selection | Configurable, default `claude-sonnet-4-5` | Not specified (uses user's default) |

## Key Takeaways

1. **`--dangerously-skip-permissions` vs `--permission-mode auto`**: 99 uses the more dangerous
   flag. Our `--permission-mode auto` is safer — it auto-approves known-safe operations but still
   enforces guardrails. This is a better default for a plugin setting.

2. **Temp file output vs stdout**: 99 directs Claude to write to a temp file, which avoids parsing
   issues if Claude includes conversational text on stdout. Our approach of using
   `--output-format text` and reading stdout is simpler and works well for the "replace selection"
   use case where we just want code back.

3. **Richer context**: 99 sends the full file contents, selection location metadata, and walked
   AGENT.md context files. Our Order 89 currently sends only the selected text, language, and
   prompt. Adding file context and location metadata could improve results.

4. **Prompt via positional arg vs stdin pipe**: 99 passes the prompt as a command-line argument.
   We pipe via stdin with `printf`. Both work; stdin avoids command-line length limits for large
   prompts.

5. **Observer/async pattern**: 99 uses `vim.system()` with async callbacks and an observer pattern
   for progress updates. Our `ProcessBuilder` + `Future` approach in Order89Executor is
   conceptually similar.
