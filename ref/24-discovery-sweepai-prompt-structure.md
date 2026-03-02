# Discovery: Sweep AI — Prompt Structure and Context Placement

## Overview

This document captures findings from examining the Sweep AI blog posts and the open-source
`sweepai/vscode-nes` GitHub repository to understand how clipboard content and open buffer/file
chunks are structured in the API request and placed in the model prompt.

---

## 1. Prompt Templates (from blog post)

The blog post "Open sourcing a 1.5B parameter Next-Edit Autocomplete" shows two prompt formats.
Neither explicitly labels clipboard vs file chunks — the structured request gets flattened into
the prompt server-side.

### Sweep's fine-tuned model format

Uses `<|file_sep|>` special tokens:

```
<|file_sep|>{file_path_1}
{file_content_1}
<|file_sep|>{changed_file_1}.diff
original:
{before_changes_of_diff}
updated:
{after_changes_of_diff}
<|file_sep|>original/{file_path}
{contents_prior_to_most_recent_change}
<|file_sep|>current/{file_path}
{current_state_of_contents}
```

### Qwen base model prompt

Uses XML-style tags:

```
<|im_start|>user
Here are contents of the user's file before any changes were made:
<current_file>
{original_file}
</current_file>

The user recently made the following changes:
<recent_changes>
{recent_changes}
</recent_changes>

Here's the section to edit:
<code_block>
{current_section}
</code_block>
```

---

## 2. API Request Schema (from GitHub repo)

The client sends a structured JSON payload — not a flat prompt. The full schema from
`src/api/schemas.ts`:

```typescript
export const AutocompleteRequestSchema = z.object({
    debug_info: z.string(),
    repo_name: z.string(),
    branch: z.string().optional(),
    file_path: z.string(),
    file_contents: z.string(),
    original_file_contents: z.string(),
    cursor_position: z.number(),
    recent_changes: z.string(),
    changes_above_cursor: z.boolean(),
    multiple_suggestions: z.boolean(),
    file_chunks: z.array(FileChunkSchema),
    retrieval_chunks: z.array(FileChunkSchema),
    editor_diagnostics: z.array(EditorDiagnosticSchema),
    recent_user_actions: z.array(UserActionSchema),
    use_bytes: z.boolean(),
});
```

Where `FileChunkSchema` is:

```typescript
export const FileChunkSchema = z.object({
    file_path: z.string(),
    start_line: z.number(),
    end_line: z.number(),
    content: z.string(),
    timestamp: z.number().optional(),
});
```

---

## 3. Context Placement by Source

### Clipboard → `retrieval_chunks[]`

Clipboard content is placed into the `retrieval_chunks` array with a synthetic path
`"clipboard.txt"`:

```typescript
private async buildClipboardChunks(): Promise<FileChunk[]> {
    const clipboard = (await vscode.env.clipboard.readText()).trim();
    if (!clipboard) return [];
    const lines = clipboard.split(/\r?\n/).slice(0, MAX_CLIPBOARD_LINES); // MAX = 20
    const content = lines.join("\n").trim();
    if (!content) return [];
    return [{
        file_path: "clipboard.txt",
        start_line: 1,
        end_line: lines.length,
        content,
        timestamp: Date.now(),
    }];
}
```

### Open buffers → `file_chunks[]`

Open/visible editor buffers and recently edited files go into the `file_chunks` array with their
real file paths (max 3 files, up to 60 lines each):

```typescript
private buildFileChunks(buffers: RecentBuffer[]): FileChunk[] {
    return buffers
        .filter((buffer) => !isFileTooLarge(buffer.content))
        .slice(0, 3)
        .map((buffer) => {
            if (buffer.startLine !== undefined && buffer.endLine !== undefined) {
                return {
                    file_path: toUnixPath(buffer.path),
                    start_line: buffer.startLine,
                    end_line: buffer.endLine,
                    content: buffer.content,
                    ...(buffer.mtime !== undefined ? { timestamp: buffer.mtime } : {}),
                };
            }
            const lines = buffer.content.split("\n");
            const endLine = Math.min(30, lines.length);
            return {
                file_path: toUnixPath(buffer.path),
                start_line: 0,
                end_line: endLine,
                content: lines.slice(0, endLine).join("\n"),
                timestamp: buffer.mtime,
            };
        });
}
```

### Retrieval chunks assembly

The `retrieval_chunks` array combines four sources:

```typescript
private async buildRetrievalChunks(...): Promise<FileChunk[]> {
    const [definitionChunks, usageChunks, clipboardChunks] = await Promise.all([
        this.buildDefinitionChunks(document, position),
        this.buildUsageChunks(document, position),
        this.buildClipboardChunks(),
    ]);

    const chunks = [
        ...this.buildDiagnosticsTextChunk(currentFilePath, diagnostics),
        ...clipboardChunks,
        ...usageChunks,
        ...definitionChunks,
    ]
        .filter((chunk) => chunk.file_path !== currentFilePath)
        .map((chunk) => truncateRetrievalChunk(chunk, MAX_RETRIEVAL_CHUNK_LINES))
        .filter((chunk) => chunk.content.trim().length > 0);

    return fuseAndDedupRetrievalSnippets(chunks).slice(-MAX_RETRIEVAL_CHUNKS); // max 16
}
```

---

## 4. Summary Table

| Context source              | Request field         | `file_path`      | Max items               |
|-----------------------------|-----------------------|------------------|-------------------------|
| Clipboard                   | `retrieval_chunks[]`  | `"clipboard.txt"`| 1 chunk, 20 lines       |
| Open/visible editor buffers | `file_chunks[]`       | real path        | 3 chunks, 60 lines each |
| Recently edited files       | `file_chunks[]`       | real path        | (included in the 3)     |
| Go-to-definition (LSP)      | `retrieval_chunks[]`  | real path        | 6 chunks                |
| Find-references (LSP)       | `retrieval_chunks[]`  | real path        | 6 chunks                |
| Diagnostics (as text)       | `retrieval_chunks[]`  | `"diagnostics"`  | 1 chunk, 50 items       |
| Diagnostics (structured)    | `editor_diagnostics[]`| n/a              | 50                      |

---

## 5. Key Observations

- **Clipboard and file chunks are separate fields** in the JSON request (`retrieval_chunks` vs
  `file_chunks`), but share the same `FileChunk` schema.
- **The client does not build the final text prompt.** The local Python server receives the
  structured JSON and assembles it into the `<|file_sep|>` prompt format. The exact placement of
  clipboard vs file chunk content in the final prompt is determined server-side and is not visible
  in the open-source client code.
- **Clipboard is mixed with LSP results** in `retrieval_chunks`, distinguished only by its
  synthetic `file_path` of `"clipboard.txt"`.

---

## Sources

### Source Code
- [github.com/sweepai/vscode-nes](https://github.com/sweepai/vscode-nes)
  - `src/api/schemas.ts` — Request/response Zod schemas
  - `src/api/client.ts` — API client, request building, chunk assembly

### Blog Posts
- [Open sourcing a 1.5B parameter Next-Edit Autocomplete](https://blog.sweep.dev/posts/oss-next-edit)
- [Building next-edit autocomplete for JetBrains](https://blog.sweep.dev/posts/next-edit-jetbrains)
- [Autocomplete Context](https://blog.sweep.dev/posts/autocomplete-context)
