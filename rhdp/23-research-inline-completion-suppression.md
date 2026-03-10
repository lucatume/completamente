# Inline Completion Provider Suppression in IntelliJ

**Question**: How does the InlineCompletionProvider API work, and how can we ensure that only
completamente's FIM/NEP completions show — suppressing both "Full Line Code Completion" and
cloud-based completions (JetBrains AI Assistant)?

---

## Short Answer

**This is already implemented.** The plugin registers a `NoOpInlineCompletionProvider` with
`order="first"` that claims all inline completion events and returns empty suggestions. This
suppresses every other inline completion provider — Full Line, cloud, and third-party.

---

## How the InlineCompletionProvider Framework Works

IntelliJ's inline completion system uses an extension point:

```xml
<inline.completion.provider
    id="..."
    order="first|last|<numeric>"
    implementation="..."/>
```

The framework evaluates providers **in order**. It stops at the first provider whose `isEnabled()`
returns `true` and calls its `getSuggestion()`. No further providers are consulted.

### Key API Types

| Type | Role |
|------|------|
| `InlineCompletionProvider` | Interface: `id`, `isEnabled(event)`, `getSuggestion(request)` |
| `InlineCompletionProviderID` | Unique string identifier for registration |
| `InlineCompletionEvent` | Trigger event (typing, explicit invocation, etc.) |
| `InlineCompletionRequest` | Context for generating a suggestion (editor, offset, document) |
| `InlineCompletionSingleSuggestion` | Response object, built via DSL builder |

### Provider Priority

- `order="first"` — evaluated before all others
- `order="last"` — evaluated after all others
- Numeric values for fine-grained ordering
- **First provider with `isEnabled() == true` wins; others are never called.**

---

## What completamente Already Does

### The Suppression Layer

**File**: `src/main/kotlin/com/github/lucatume/completamente/fim/NoOpInlineCompletionProvider.kt`

```kotlin
class NoOpInlineCompletionProvider : InlineCompletionProvider {
    override val id = InlineCompletionProviderID("...NoOpInlineCompletionProvider")
    override fun isEnabled(event: InlineCompletionEvent): Boolean = true
    override suspend fun getSuggestion(request: InlineCompletionRequest) =
        InlineCompletionSingleSuggestion.build { }
}
```

**Registered in** `plugin.xml`:

```xml
<inline.completion.provider
    id="com.github.lucatume.completamente.NoOpInlineCompletionProvider"
    order="first"
    implementation="com.github.lucatume.completamente.fim.NoOpInlineCompletionProvider"/>
```

**Effect**: Every inline completion event is claimed by this provider. It returns an empty suggestion,
so no inline completion UI appears from IntelliJ's framework. This suppresses:

1. **Full Line Code Completion** (the "Enable local Full Line completion suggestions" setting) — this
   is itself an `InlineCompletionProvider` registered by IntelliJ's core. Since completamente claims
   events first, Full Line's provider is never consulted.
2. **Cloud completions / JetBrains AI Assistant** — also registered as an `InlineCompletionProvider`.
   Same suppression mechanism applies.
3. **Any third-party inline completion plugin** — unless it bypasses the framework entirely.

### The Display Layer (Separate from InlineCompletionProvider)

Completamente does **not** use the `InlineCompletionProvider` framework to display its own
suggestions. Instead, it uses a completely independent rendering pipeline:

| Component | File | Role |
|-----------|------|------|
| `GhostTextRenderer` | `fim/GhostTextRenderer.kt` | Renders suggestions via `EditorCustomElementRenderer` inlays |
| `FimSuggestionManager` | `fim/FimSuggestionManager.kt` | Orchestrates FIM requests to llama.cpp, manages suggestion state |
| `FimTypingListener` | `fim/FimTypingListener.kt` | Auto-triggers suggestions on typing (300ms debounce) |
| `FimTabHandler` / `FimEscapeHandler` | `fim/FimActionHandlers.kt` | Intercepts Tab (accept) and Escape (dismiss) |

This separation is key: the `NoOpInlineCompletionProvider` blocks the framework's UI, while the
plugin's own inlay-based rendering operates entirely outside the framework.

---

## What About the IDE Settings?

### "Enable local Full Line completion suggestions"

- **Location**: Settings → Editor → General → Inline Completion
- **Effect**: Toggles the built-in Full Line provider's `isEnabled()` return value
- **Relevance when completamente is active**: None. The setting becomes moot because completamente's
  `order="first"` provider claims events before the Full Line provider is ever checked.
- **Does the plugin need to manipulate this setting?** No. The architectural suppression is sufficient.

### Cloud completion / AI Assistant settings

- **Location**: Settings → Tools → AI Assistant (if installed)
- **Relevance**: Same as above — cloud providers are never reached due to ordering.
- **Does the plugin manipulate these?** No, and it doesn't need to.

---

## Could Anything Bypass the Suppression?

| Scenario | Suppressed? | Why |
|----------|-------------|-----|
| Full Line Code Completion | Yes | It's an `InlineCompletionProvider`; completamente claims first. |
| JetBrains AI Assistant inline | Yes | Same framework, same suppression. |
| Third-party InlineCompletionProvider | Yes | Same framework. |
| Standard code completion popup (Ctrl+Space) | **No** | Different system (`CompletionContributor`), not affected. |
| Live Templates | **No** | Different system, not related to inline completion. |
| Postfix completion | **No** | Different system. |

The standard code completion popup (the dropdown list) is intentionally **not** suppressed — it uses
a completely different API (`CompletionContributor`) and is expected to coexist with FIM/NEP
suggestions.

---

## Conclusion

No additional work is needed. The current architecture already ensures that only completamente's
FIM/NEP completions appear as inline suggestions. The `NoOpInlineCompletionProvider` with
`order="first"` is the correct and sufficient mechanism. The plugin does not need to programmatically
toggle IDE settings or registry keys.

---

## Sources

- `src/main/kotlin/com/github/lucatume/completamente/fim/NoOpInlineCompletionProvider.kt`
- `src/main/resources/META-INF/plugin.xml` (lines 20-23)
- `src/main/kotlin/com/github/lucatume/completamente/fim/GhostTextRenderer.kt`
- `src/main/kotlin/com/github/lucatume/completamente/fim/FimSuggestionManager.kt`
- [IntelliJ Platform SDK: Code Completion](https://plugins.jetbrains.com/docs/intellij/code-completion.html)
- [IntelliJ Platform SDK: Inline Completion](https://plugins.jetbrains.com/docs/intellij/inline-completion.html)
