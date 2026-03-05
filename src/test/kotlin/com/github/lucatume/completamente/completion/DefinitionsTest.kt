package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class DefinitionsTest : BaseCompletionTest() {

    fun testCollectReferencedFilesFromHeaderReturnsEmptyForEmptyFile() {
        val psiFile = myFixture.configureByText("Empty.java", "")
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromHeader(project, psiFile, currentPath)
        assertTrue("Empty file should produce no referenced files", result.isEmpty())
    }

    fun testCollectReferencedFilesFromHeaderReturnsEmptyForPlainText() {
        val psiFile = myFixture.configureByText(
            "plain.txt",
            "This is just plain text with no references at all."
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromHeader(project, psiFile, currentPath)
        assertTrue("Plain text should produce no referenced files", result.isEmpty())
    }

    fun testCollectReferencedFilesFromHeaderNoExceptionOnUnresolvableReferences() {
        val psiFile = myFixture.configureByText(
            "Unresolvable.java",
            """
            import com.nonexistent.SomeMissingClass;
            public class Unresolvable {
                void run() {
                    SomeMissingClass.missing();
                }
            }
            """.trimIndent()
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromHeader(project, psiFile, currentPath)
        assertTrue("Unresolvable references should produce no files", result.isEmpty())
    }

    fun testCollectReferencedFilesFromHeaderSkipsSameFile() {
        val psiFile = myFixture.configureByText(
            "SelfRef.java",
            """
            public class SelfRef {
                SelfRef ref;
            }
            """.trimIndent()
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromHeader(project, psiFile, currentPath)
        val selfRefs = result.filter { it.virtualFile?.path == currentPath }
        assertTrue("Should not include the current file itself", selfRefs.isEmpty())
    }

    fun testResolveStructureFilesReturnsEmptyForNoReferences() {
        val psiFile = myFixture.configureByText(
            "NoRefs.java",
            """
            public class NoRefs {
                void doStuff() {}
            }
            """.trimIndent()
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val (chunks, _) = resolveStructureFiles(project, psiFile, currentPath)
        assertTrue("No references should produce no structure file chunks", chunks.isEmpty())
    }

    fun testCollectReferencedFilesFromWindowReturnsEmptyForEmptyFile() {
        val psiFile = myFixture.configureByText("Empty.java", "")
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromWindow(
            project, psiFile, currentPath,
            windowStartLine = 0, windowEndLine = 0
        )
        assertTrue("Empty file should produce no referenced files from window", result.isEmpty())
    }

    fun testCollectReferencedFilesFromWindowExcludesHeaderPaths() {
        val depFile = myFixture.configureByText("Dep.java", "public class Dep {}")
        val depPath = depFile.virtualFile.path
        val psiFile = myFixture.configureByText(
            "Main.java",
            """
            public class Main {
                Dep dep;
            }
            """.trimIndent()
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromWindow(
            project, psiFile, currentPath,
            windowStartLine = 0, windowEndLine = 2,
            excludePaths = setOf(depPath)
        )
        val depRefs = result.filter { it.virtualFile?.path == depPath }
        assertTrue("Dep should be excluded via excludePaths", depRefs.isEmpty())
    }

    fun testCollectReferencedFilesFromWindowSkipsHeaderRegion() {
        val psiFile = myFixture.configureByText(
            "Short.java",
            """
            import java.util.List;
            public class Short {
                List<String> items;
            }
            """.trimIndent()
        )
        val project = myFixture.project
        val currentPath = psiFile.virtualFile.path
        val result = collectReferencedFilesFromWindow(
            project, psiFile, currentPath,
            windowStartLine = 0, windowEndLine = 3
        )
        assertTrue("File shorter than MAX_HEADER_LINES should produce empty scan region", result.isEmpty())
    }
}
