# IntelliJ API: File-Level Symbol Reference Resolution

**Question**: How do you use the IntelliJ Platform API to collect all project files referenced by the symbols in a given file, excluding built-in/SDK files?

**Purpose**: Include referenced project files as extra context in FIM/NEP prompts.

---

## Core Approach

Given a `PsiFile`, walk every PSI element in its tree, resolve each element's references, and collect the containing files of the resolved targets. Filter to project-only files.

This is a **"references FROM a file"** operation (outgoing dependencies: what does this file use?), not a "references TO a file" search. It is local to the file's PSI tree and does not require a global index scan.

## 1. Walking PSI Elements

### PsiRecursiveElementWalkingVisitor (recommended)

```kotlin
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
    override fun visitElement(element: PsiElement) {
        // process element.references here
        super.visitElement(element)
    }
})
```

Key feature: `stopWalking()` method for early termination once enough results are gathered.

### PsiTreeUtil.processElements (alternative, concise)

```kotlin
PsiTreeUtil.processElements(psiFile) { element ->
    // process element.references here
    true // return true to continue, false to stop
}
```

Internally identical to the visitor — same performance.

## 2. Resolving References

```
PsiElement.getReferences() → Array<PsiReference>
PsiReference.resolve()     → PsiElement?        // the declaration
PsiElement.containingFile  → PsiFile?
PsiFile.virtualFile        → VirtualFile?
```

For poly-variant references (symbols resolving to multiple targets), use `multiResolve`:

```kotlin
for (ref in element.references) {
    val targets = if (ref is PsiPolyVariantReference) {
        ref.multiResolve(false).mapNotNull { it.element }
    } else {
        listOfNotNull(ref.resolve())
    }
    for (resolved in targets) {
        val vf = resolved.containingFile?.virtualFile ?: continue
        // collect vf
    }
}
```

The `false` argument to `multiResolve` means "only valid/complete results".

## 3. Filtering to Project Files

Use `ProjectFileIndex` to exclude library/SDK files:

```kotlin
import com.intellij.openapi.roots.ProjectFileIndex

val fileIndex = ProjectFileIndex.getInstance(project)
```

| Method | Meaning |
|--------|---------|
| `isInContent(vf)` | Under a project content root (source, resource, test), not excluded |
| `isInSource(vf)` | Under a source/test/resource root |
| `isInLibrary(vf)` | Part of a library (classes or sources) |

**Use `isInContent(vf)`** — it returns `true` for project files and `false` for SDK/library files (e.g. built-in PHP language definitions, JDK classes).

Lightweight pre-filter: `vf.isInLocalFileSystem` — excludes JARs and virtual filesystems before the more expensive `ProjectFileIndex` check.

## 4. Complete Implementation

```kotlin
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

/**
 * Collects all project files referenced by symbols in [psiFile].
 * Excludes library/SDK files and the file itself.
 * Must be called within a read action in smart mode.
 */
fun collectReferencedProjectFiles(
    project: Project,
    psiFile: PsiFile,
    maxFiles: Int = 10
): Set<VirtualFile> {
    val currentVf = psiFile.virtualFile ?: return emptySet()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val result = mutableSetOf<VirtualFile>()

    psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            if (result.size >= maxFiles) {
                stopWalking()
                return
            }
            try {
                for (ref in element.references) {
                    try {
                        val targets = if (ref is PsiPolyVariantReference) {
                            ref.multiResolve(false).mapNotNull { it.element }
                        } else {
                            listOfNotNull(ref.resolve())
                        }
                        for (resolved in targets) {
                            val vf = resolved.containingFile?.virtualFile ?: continue
                            if (vf == currentVf) continue
                            if (!vf.isInLocalFileSystem) continue
                            if (!fileIndex.isInContent(vf)) continue
                            result.add(vf)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            super.visitElement(element)
        }
    })

    return result
}
```

## 5. Filtering: Type Dependencies vs File References

Not all resolved references are useful as completion context. A reference to a class definition (type dependency) provides valuable context about available methods/properties. A reference to an included file (file reference) typically does not.

### The `PsiFileSystemItem` check

The PSI hierarchy makes this distinction straightforward:

```
PsiElement
  → PsiNamedElement
    → PsiFileSystemItem    ← files and directories land here
      → PsiFile
      → PsiDirectory
```

Class, method, function, and property declarations do **not** extend `PsiFileSystemItem`. So:

```kotlin
val resolved = ref.resolve() ?: continue
if (resolved is PsiFileSystemItem) continue  // skip include/require/file-path refs
```

| Reference kind | Example | `resolve()` returns | `is PsiFileSystemItem` |
|---|---|---|---|
| Property access | `$this->db` | Property declaration | **false** → include |
| Method call | `->get_posts()` | Method declaration | **false** → include |
| Class reference | `new Db()` | Class declaration | **false** → include |
| Function call | `get_post_titles()` | Function declaration | **false** → include |
| File include | `include 'file.php'` | `PsiFile` | **true** → skip |
| Directory ref | `__DIR__` | `PsiDirectory` | **true** → skip |

This is fully language-agnostic — works for PHP `include`/`require`, JS/TS file-path imports, Python path imports, etc.

**Important**: `PsiNamedElement` is **not** the right check. `PsiFile` implements `PsiNamedElement`, so both class declarations and file references pass `resolved is PsiNamedElement`.

### Following a property to its type's defining file

For `$this->db`, `resolve()` returns the property declaration element. The containing file is typically the class that declares the property — which is often what you want anyway (the `Db` class file if `$db` is declared in the `Db` class, or the current class file if `$db` is a property of the current class).

To follow the type chain further (property → type → type's defining file), there is **no universal cross-language API**. Each language plugin has its own type system:

- **Java/Kotlin**: `PsiField.type` → `PsiClassType.resolve()` → `PsiClass`
- **PHP**: `PhpTypedElement.type` → `PhpType`, then `PhpIndex.getClassesByFQN()`
- **JS/TS**: `JSTypeEvaluator` or TypeScript service

For a multi-language plugin, the generic `resolve()` approach (without type-following) is sufficient — it already captures the files that define the symbols used in the current file.

### Additional marker interfaces

| Interface | Covers | Use for |
|---|---|---|
| `PsiFileSystemItem` | Files + directories | Negative filter (skip file/dir refs) |
| `PsiQualifiedNamedElement` | Elements with FQN (classes, interfaces) | Positive filter for "important" declarations |

`PsiQualifiedNamedElement` is not consistently implemented across all language plugins, so the `PsiFileSystemItem` negative filter is more reliable.

### Updated implementation

The complete implementation from §4 with the filter applied:

```kotlin
fun collectReferencedProjectFiles(
    project: Project,
    psiFile: PsiFile,
    maxFiles: Int = 10
): Set<VirtualFile> {
    val currentVf = psiFile.virtualFile ?: return emptySet()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val result = mutableSetOf<VirtualFile>()

    psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            if (result.size >= maxFiles) {
                stopWalking()
                return
            }
            try {
                for (ref in element.references) {
                    try {
                        val targets = if (ref is PsiPolyVariantReference) {
                            ref.multiResolve(false).mapNotNull { it.element }
                        } else {
                            listOfNotNull(ref.resolve())
                        }
                        for (resolved in targets) {
                            if (resolved is PsiFileSystemItem) continue  // skip file/dir refs
                            val vf = resolved.containingFile?.virtualFile ?: continue
                            if (vf == currentVf) continue
                            if (!vf.isInLocalFileSystem) continue
                            if (!fileIndex.isInContent(vf)) continue
                            result.add(vf)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            super.visitElement(element)
        }
    })

    return result
}
```

## 6. Caching Strategy

### TTL-based caching

Resolved files change slowly — new imports/references are added infrequently compared to keystroke frequency. A time-based cache (e.g. 30–60 seconds) avoids re-resolving on every completion request while keeping results reasonably fresh.

```kotlin
data class CachedFileRefs(
    val files: Set<VirtualFile>,
    val timestamp: Long
)

private val cache = ConcurrentHashMap<VirtualFile, CachedFileRefs>()
private const val CACHE_TTL_MS = 30_000L

fun getReferencedProjectFiles(
    project: Project,
    psiFile: PsiFile
): Set<VirtualFile> {
    val vf = psiFile.virtualFile ?: return emptySet()
    val now = System.currentTimeMillis()
    val cached = cache[vf]
    if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
        return cached.files
    }
    val files = collectReferencedProjectFiles(project, psiFile)
    cache[vf] = CachedFileRefs(files, now)
    return files
}
```

### Prompt ordering for KV cache reuse

Referenced files are the most stable context — they change only when imports are added/removed. Placing them **first** in the prompt maximizes llama.cpp KV cache reuse across successive completions:

```
[referenced file chunks]  ← stable, cached across requests
[recent diff chunks]      ← semi-stable
[ring buffer chunks]      ← changes with cursor movement
[current file context]    ← changes on every keystroke
```

This ordering means the KV cache prefix remains valid even as the user types, since only the tail of the prompt changes.

## 7. Threading and Read Access


All PSI access requires a read action. Reference resolution depends on indexes (smart mode).

### Modern coroutine API (2024.1+, recommended)

```kotlin
import com.intellij.openapi.application.smartReadAction

val files = smartReadAction(project) {
    collectReferencedProjectFiles(project, psiFile)
}
```

`smartReadAction` suspends until smart mode, acquires a read lock, and auto-retries if cancelled by a write action.

### Legacy API

```kotlin
ReadAction.nonBlocking<Set<VirtualFile>> {
    collectReferencedProjectFiles(project, psiFile)
}
    .inSmartMode(project)
    .submit(AppExecutorUtil.getAppExecutorService())
    .onSuccess { files -> /* use */ }
```

## 8. Performance

Walking every PSI element and resolving every reference is **expensive**:

- Hundreds to thousands of elements per file
- Each `resolve()` may involve index lookups, scope resolution, type inference
- `resolve()` is documented as one of the most expensive PSI operations

### Mitigations

**a) Early termination** — stop once `maxFiles` is reached (shown above).

**b) TTL-based caching** — see §6 above. A 30–60s TTL avoids re-resolving on every completion request.

**c) Only walk leaves** — many parent elements aggregate child references, so processing only leaf nodes reduces work at the cost of missing some parent-level references:

```kotlin
override fun visitElement(element: PsiElement) {
    if (element.firstChild == null) {
        // leaf — process references
    }
    super.visitElement(element)
}
```

**d) Always run on a background thread** — never on the EDT for large files.

## 9. Relation to Existing Code

The project already has `collectDefinitionLocationsAtOffset()` in `definitions.kt` which resolves references from a **single cursor offset** (walking up 3 parent levels). The file-wide approach proposed here is complementary:

| Aspect | Existing (offset-based) | Proposed (file-wide) |
|--------|------------------------|---------------------|
| Scope | Single cursor position, 3 parent levels | Entire file PSI tree |
| Output | Definition locations with line numbers | Set of referenced VirtualFiles |
| Cost | Cheap (3 elements max) | Expensive (all elements) |
| Use case | Definition context around cursor | Full dependency graph for NEP context |

The file-wide approach could reuse `mergeAndExtractDefinitionChunks()` from `definitions.kt` to turn collected files into prompt chunks.

## 10. Language-Agnostic Behavior

The `element.references` / `ref.resolve()` approach is **fully language-agnostic**. It works for any language whose IntelliJ plugin provides reference resolution: Java, Kotlin, PHP, JavaScript/TypeScript, Python, Go, etc.

Caveats:
- Languages with minimal PSI support (plain text, Markdown) return empty references
- Implicit dependencies (PHP autoloading, Kotlin implicit imports) depend on language plugin quality
- Import-only analysis would be cheaper but requires language-specific PSI types (`PsiImportStatement`, `KtImportDirective`, `JSImportStatement`, etc.)

For a multi-language plugin targeting all IntelliJ IDEs, the generic approach is correct.

## 11. Gotchas

1. **Read action required** — PSI access without a read lock throws `IllegalStateException`
2. **Smart mode required** — `resolve()` during indexing may return null or throw `IndexNotReadyException`
3. **PSI validity** — elements can become invalid between read actions; within a single read action this is not an issue
4. **Null returns from resolve()** — completely normal for unresolved symbols, broken code, missing deps
5. **VirtualFile can be null** — `PsiFile.virtualFile` is null for in-memory files
6. **Exception handling** — some language plugins throw during resolution for malformed code; wrap individual `resolve()` calls in try-catch

## Sources

- [PSI References | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/psi-references.html)
- [References and Resolve | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/references-and-resolve.html)
- [Navigating the PSI | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/navigating-psi.html)
- [PSI Performance | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)
- [Threading Model | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Coroutine Read Actions | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html)
- [ProjectFileIndex API](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/roots/ProjectFileIndex.html)
