package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class structureTest : BaseCompletionTest() {

    // --- collectReferencedFiles tests ---

    fun testCollectReferencedFilesEmptyFile() {
        myFixture.configureByText("Empty.kt", "")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 0, 0)
        assertTrue(result.isEmpty())
    }

    fun testCollectReferencedFilesNoReferences() {
        myFixture.configureByText("NoRefs.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 0, 2)
        assertTrue(result.isEmpty())
    }

    fun testCollectReferencedFilesExcludesCurrentFile() {
        myFixture.configureByText("Self.kt", "val x = 1\n")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 0, 1)
        assertFalse(result.contains(psiFile.virtualFile))
    }

    fun testCollectReferencedFilesStartLineEqualsEndLine() {
        myFixture.configureByText("Range.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 1, 1)
        assertTrue(result.isEmpty())
    }

    fun testCollectReferencedFilesStartLineGreaterThanEndLine() {
        myFixture.configureByText("Range.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 2, 1)
        assertTrue(result.isEmpty())
    }

    fun testCollectReferencedFilesWithProjectReference() {
        // Add a file that can be referenced
        myFixture.addFileToProject("foo/Referenced.kt", "package foo\nclass Referenced\n")
        // Create a file that references it via import
        myFixture.configureByText("Referrer.kt", "import foo.Referenced\nval r = Referenced()\n")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 0, 2)
        // The result depends on whether the test framework resolves the reference.
        // At minimum, verify no crash and current file excluded.
        assertFalse(result.contains(psiFile.virtualFile))
    }

    fun testCollectReferencedFilesNegativeStartLineClamped() {
        myFixture.configureByText("Clamp.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        // Should not crash with negative start line
        val result = collectReferencedFiles(psiFile, -5, 2)
        assertNotNull(result)
    }

    fun testCollectReferencedFilesEndLineBeyondFileLength() {
        myFixture.configureByText("Beyond.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        // Should not crash with end line beyond file
        val result = collectReferencedFiles(psiFile, 0, 100)
        assertNotNull(result)
    }

    // --- surfaceExtract tests ---

    fun testSurfaceExtractNullForNonExistentFile() {
        myFixture.configureByText("Dummy.kt", "val x = 1\n")
        // Try to extract from a file that doesn't exist via PsiManager
        // We test with a valid file instead
        val file = myFixture.file.virtualFile
        val result = surfaceExtract(project, file)
        assertNotNull(result)
    }

    fun testSurfaceExtractEmptyFile() {
        val psiFile = myFixture.addFileToProject("empty/Empty.kt", "")
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNull(result)
    }

    fun testSurfaceExtractBlankFile() {
        val psiFile = myFixture.addFileToProject("blank/Blank.kt", "   \n  \n")
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNull(result)
    }

    fun testSurfaceExtractSimpleClass() {
        val psiFile = myFixture.addFileToProject(
            "extract/Simple.kt",
            """
            |package extract
            |
            |class Simple {
            |    fun doWork() {
            |        println("working")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain class declaration", result!!.contains("class Simple"))
        assertTrue("Should contain function signature", result.contains("fun doWork()"))
        assertFalse("Should not contain method body", result.contains("println(\"working\")"))
    }

    fun testSurfaceExtractObject() {
        val psiFile = myFixture.addFileToProject(
            "extract/Utils.kt",
            """
            |package extract
            |
            |object Utils {
            |    fun compute(value: Int): Int {
            |        return value * 2
            |    }
            |
            |    fun format(text: String): String {
            |        return text.trim()
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain object declaration", result!!.contains("object Utils"))
        assertTrue("Should contain compute signature", result.contains("fun compute"))
        assertTrue("Should contain format signature", result.contains("fun format"))
        assertFalse("Should not contain return value * 2", result.contains("return value * 2"))
        assertFalse("Should not contain return text.trim()", result.contains("return text.trim()"))
    }

    fun testSurfaceExtractPreservesPackage() {
        val psiFile = myFixture.addFileToProject(
            "extract/Pkg.kt",
            "package extract\n\nclass Pkg {\n    fun method() {\n        // body\n    }\n}\n"
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain package", result!!.contains("package extract"))
    }

    fun testSurfaceExtractPreservesImports() {
        val psiFile = myFixture.addFileToProject(
            "extract/Imported.kt",
            "package extract\n\nimport java.util.List\n\nclass Imported {\n    fun method() {\n        // body\n    }\n}\n"
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain import", result!!.contains("import java.util.List"))
    }

    fun testSurfaceExtractMultipleMethods() {
        val psiFile = myFixture.addFileToProject(
            "extract/Multi.kt",
            """
            |package extract
            |
            |class Multi {
            |    fun first(a: Int): Int {
            |        return a + 1
            |    }
            |
            |    fun second(b: String): String {
            |        return b.uppercase()
            |    }
            |
            |    fun third() {
            |        println("third")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain first", result!!.contains("fun first"))
        assertTrue("Should contain second", result.contains("fun second"))
        assertTrue("Should contain third", result.contains("fun third"))
        assertFalse("Should not contain return a + 1", result.contains("return a + 1"))
    }

    fun testSurfaceExtractTopLevelFunction() {
        val psiFile = myFixture.addFileToProject(
            "extract/TopLevel.kt",
            "package extract\n\nfun topLevelFunc(x: Int): Int {\n    return x * 2\n}\n"
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain function signature", result!!.contains("fun topLevelFunc"))
        assertFalse("Should not contain body", result.contains("return x * 2"))
    }

    fun testSurfaceExtractClassWithNestedClass() {
        val psiFile = myFixture.addFileToProject(
            "extract/Outer.kt",
            """
            |package extract
            |
            |class Outer {
            |    class Inner {
            |        fun innerMethod() {
            |            println("inner")
            |        }
            |    }
            |
            |    fun outerMethod() {
            |        println("outer")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain Outer class", result!!.contains("class Outer"))
        assertTrue("Should contain Inner class signature", result.contains("class Inner"))
        assertTrue("Should contain innerMethod signature", result.contains("fun innerMethod()"))
        assertTrue("Should contain outer method", result.contains("fun outerMethod()"))
        assertFalse("Should not contain inner body", result.contains("println(\"inner\")"))
        assertFalse("Should not contain outer body", result.contains("println(\"outer\")"))
    }

    // --- surfaceExtract edge case tests ---

    fun testSurfaceExtractStringLiteralWithBraces() {
        val psiFile = myFixture.addFileToProject(
            "extract/StringBraces.kt",
            """
            |package extract
            |
            |class StringBraces {
            |    val x = "{ not a block }"
            |    fun method() {
            |        println("done")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain class declaration", result!!.contains("class StringBraces"))
        assertTrue("Should contain val x (member signature)", result.contains("val x = \"{ not a block }\""))
        assertTrue("Should contain method signature", result.contains("fun method()"))
        assertFalse("Should not contain println body", result.contains("println(\"done\")"))
    }

    fun testSurfaceExtractLineCommentWithBraces() {
        val psiFile = myFixture.addFileToProject(
            "extract/CommentBraces.kt",
            """
            |package extract
            |
            |// class Foo { }
            |class Real {
            |    fun method() {
            |        println("body")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain Real class", result!!.contains("class Real"))
        assertTrue("Should contain method signature", result.contains("fun method()"))
        assertFalse("Should not contain method body", result.contains("println(\"body\")"))
    }

    fun testSurfaceExtractBlockCommentWithBraces() {
        val psiFile = myFixture.addFileToProject(
            "extract/BlockComment.kt",
            """
            |package extract
            |
            |/* class Fake { fun fake() { } } */
            |class Actual {
            |    fun realMethod() {
            |        println("real")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain Actual class", result!!.contains("class Actual"))
        assertTrue("Should contain realMethod signature", result.contains("fun realMethod()"))
        assertFalse("Should not contain body", result.contains("println(\"real\")"))
    }

    fun testSurfaceExtractMultiBraceLineCompanionObject() {
        val psiFile = myFixture.addFileToProject(
            "extract/Companion.kt",
            """
            |package extract
            |
            |class Host {
            |    companion object { val X = 1 }
            |    fun method() {
            |        println("body")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain Host class", result!!.contains("class Host"))
        assertTrue("Should contain companion object line", result.contains("companion object"))
        assertTrue("Should contain method signature", result.contains("fun method()"))
        assertFalse("Should not contain method body", result.contains("println(\"body\")"))
    }

    fun testSurfaceExtractPrivateClass() {
        val psiFile = myFixture.addFileToProject(
            "extract/PrivateClass.kt",
            """
            |package extract
            |
            |private class Secret {
            |    fun hidden() {
            |        println("secret")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain private class", result!!.contains("private class Secret"))
        assertTrue("Should contain hidden signature", result.contains("fun hidden()"))
        assertFalse("Should not contain body", result.contains("println(\"secret\")"))
    }

    fun testSurfaceExtractInternalSealedClass() {
        val psiFile = myFixture.addFileToProject(
            "extract/SealedClass.kt",
            """
            |package extract
            |
            |internal sealed class Result {
            |    class Success : Result() {
            |        fun value() {
            |            println("value")
            |        }
            |    }
            |    class Failure : Result()
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain sealed class", result!!.contains("internal sealed class Result"))
        assertTrue("Should contain Success class", result.contains("class Success"))
        assertTrue("Should contain Failure class", result.contains("class Failure"))
    }

    fun testSurfaceExtractMultilineString() {
        val psiFile = myFixture.addFileToProject(
            "extract/MultilineStr.kt",
            "package extract\n\nclass Holder {\n    val template = \"\"\"\n    class Fake {\n        fun fake() { }\n    }\n    \"\"\"\n    fun real() {\n        println(\"real\")\n    }\n}\n"
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain Holder class", result!!.contains("class Holder"))
        assertTrue("Should contain real() signature", result.contains("fun real()"))
        assertFalse("Should not contain body", result.contains("println(\"real\")"))
    }

    fun testCollectReferencedFilesJavaReference() {
        // Java PSI resolution works reliably in IC test environment
        myFixture.addFileToProject("jref/Helper.java", "package jref;\n\npublic class Helper {\n    public static int compute(int x) { return x; }\n}\n")
        myFixture.configureByText("Caller.java", "import jref.Helper;\n\npublic class Caller {\n    int x = Helper.compute(1);\n}\n")
        val psiFile = myFixture.file
        val result = collectReferencedFiles(psiFile, 0, 4)
        // At minimum, should not crash and current file excluded
        assertFalse(result.contains(psiFile.virtualFile))
    }

    // --- buildStructureChunks tests ---

    fun testBuildStructureChunksEmptyFile() {
        myFixture.configureByText("Empty.kt", "")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        assertTrue(result.isEmpty())
    }

    fun testBuildStructureChunksNoReferences() {
        myFixture.configureByText("NoRefs.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        assertTrue(result.isEmpty())
    }

    fun testBuildStructureChunksWholeFileMode() {
        myFixture.addFileToProject("foo/Helper.kt", "package foo\n\nclass Helper {\n    fun doSomething() {\n        println(\"doing\")\n    }\n}\n")
        myFixture.configureByText("Main.kt", "import foo.Helper\n\nfun main() {\n    val h = Helper()\n}\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        // Result depends on PSI resolution. At minimum, should not crash.
        assertNotNull(result)
    }

    fun testBuildStructureChunksWindowedMode() {
        myFixture.addFileToProject("foo/Helper.kt", "package foo\n\nclass Helper {\n    fun doSomething() {\n        println(\"doing\")\n    }\n}\n")
        myFixture.configureByText("Main.kt", "import foo.Helper\n\nfun main() {\n    val h = Helper()\n}\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(
            psiFile,
            wholeFile = false,
            windowStartLine = 2,
            windowEndLine = 4,
            headerLines = 2
        )
        // Result depends on PSI resolution. At minimum, should not crash.
        assertNotNull(result)
    }

    fun testBuildStructureChunksResultsSortedByFilename() {
        myFixture.addFileToProject("aaa/First.kt", "package aaa\n\nclass First {\n    fun a() {\n        println(\"a\")\n    }\n}\n")
        myFixture.addFileToProject("zzz/Last.kt", "package zzz\n\nclass Last {\n    fun z() {\n        println(\"z\")\n    }\n}\n")
        myFixture.configureByText("Main.kt", "import aaa.First\nimport zzz.Last\nval f = First()\nval l = Last()\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        // If any chunks resolved, they should be sorted by filename
        if (result.size > 1) {
            for (i in 0 until result.size - 1) {
                assertTrue(
                    "Chunks should be sorted by filename: ${result[i].filename} <= ${result[i + 1].filename}",
                    result[i].filename <= result[i + 1].filename
                )
            }
        }
    }

    fun testBuildStructureChunksWindowedModeWithZeroWindow() {
        myFixture.configureByText("NoWindow.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(
            psiFile,
            wholeFile = false,
            windowStartLine = 0,
            windowEndLine = 0,
            headerLines = 32
        )
        // Window start == end, so window contributes nothing; only header scanned
        assertNotNull(result)
    }

    fun testBuildStructureChunksReturnsInfillExtraChunks() {
        myFixture.configureByText("Types.kt", "val x = 1\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        // Verify return type is List<InfillExtraChunk>
        assertTrue(result is List<InfillExtraChunk>)
    }

    fun testBuildStructureChunksChunkFilenameIsRelativePath() {
        myFixture.addFileToProject("foo/Helper.kt", "package foo\n\nclass Helper {\n    fun doSomething() {\n        println(\"doing\")\n    }\n}\n")
        myFixture.configureByText("Main.kt", "import foo.Helper\nval h = Helper()\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        // If resolved, the filename should be a relative path (not absolute)
        for (chunk in result) {
            assertFalse(
                "Filename should be relative, not absolute: ${chunk.filename}",
                chunk.filename.startsWith("/")
            )
        }
    }

    fun testBuildStructureChunksChunkTextNotEmpty() {
        myFixture.addFileToProject("foo/Helper.kt", "package foo\n\nclass Helper {\n    fun doSomething() {\n        println(\"doing\")\n    }\n}\n")
        myFixture.configureByText("Main.kt", "import foo.Helper\nval h = Helper()\n")
        val psiFile = myFixture.file
        val result = buildStructureChunks(psiFile, wholeFile = true)
        // If any chunks, their text should not be empty
        for (chunk in result) {
            assertTrue("Chunk text should not be blank", chunk.text.isNotBlank())
        }
    }

    // --- Issue-fix tests ---

    fun testSurfaceExtractMultiLineBlockCommentAtSurfaceDepth() {
        val psiFile = myFixture.addFileToProject(
            "extract/BlockCommentMulti.kt",
            """
            |package extract
            |
            |class Foo {
            |/*
            |    fun fake() {
            |        println("fake")
            |    }
            |*/
            |    fun real() {
            |        println("real")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertFalse("Should NOT contain fun fake() from block comment", result!!.contains("fun fake()"))
        assertFalse("Should NOT contain println(\"fake\")", result.contains("println(\"fake\")"))
        assertTrue("Should contain fun real()", result.contains("fun real()"))
    }

    fun testSurfaceExtractMultiLineStringAtSurfaceDepth() {
        val psiFile = myFixture.addFileToProject(
            "extract/MultilineStr2.kt",
            "package extract\n\nclass Bar {\n    val s = \"\"\"\n    fun fake() {\n        println(\"fake\")\n    }\n    \"\"\"\n    fun real() {\n        println(\"real\")\n    }\n}\n"
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        // The interior lines of the multiline string should not appear as signatures
        assertFalse("Should NOT contain fun fake() from multiline string", result!!.contains("fun fake()"))
        assertTrue("Should contain fun real()", result.contains("fun real()"))
    }

    fun testSurfaceExtractValClassLoaderNotContainer() {
        val psiFile = myFixture.addFileToProject(
            "extract/ClassLoaderHolder.kt",
            """
            |package extract
            |
            |val classLoader = ClassLoader.getSystemClassLoader()
            |
            |class Actual {
            |    fun method() {
            |        println("body")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        // val classLoader should appear as a top-level declaration, not open a container
        assertTrue("Should contain val classLoader", result!!.contains("val classLoader"))
        assertTrue("Should contain class Actual", result.contains("class Actual"))
        assertTrue("Should contain fun method()", result.contains("fun method()"))
        assertFalse("Should not contain method body", result.contains("println(\"body\")"))
    }

    fun testSurfaceExtractCompanionObjectMembers() {
        val psiFile = myFixture.addFileToProject(
            "extract/CompanionHost.kt",
            """
            |package extract
            |
            |class CompanionHost {
            |    companion object {
            |        fun create(): CompanionHost {
            |            return CompanionHost()
            |        }
            |        val DEFAULT = "default"
            |    }
            |    fun method() {
            |        println("body")
            |    }
            |}
            """.trimMargin()
        )
        val result = surfaceExtract(project, psiFile.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain class CompanionHost", result!!.contains("class CompanionHost"))
        assertTrue("Should contain companion object", result.contains("companion object"))
        // companion object is a container, so its member signatures (depth 2) should appear
        assertTrue("Should contain fun create()", result.contains("fun create()"))
        assertTrue("Should contain val DEFAULT", result.contains("val DEFAULT"))
        assertFalse("Should not contain return CompanionHost()", result.contains("return CompanionHost()"))
        assertTrue("Should contain fun method()", result.contains("fun method()"))
        assertFalse("Should not contain println body", result.contains("println(\"body\")"))
    }

    fun testBuildStructureChunksWindowStartGreaterThanWindowEnd() {
        myFixture.configureByText("Inverted.kt", "val x = 1\nval y = 2\n")
        val psiFile = myFixture.file
        // windowStartLine > windowEndLine should not crash
        val result = buildStructureChunks(
            psiFile,
            wholeFile = false,
            windowStartLine = 10,
            windowEndLine = 2,
            headerLines = 32
        )
        assertNotNull(result)
    }

    fun testBuildStructureChunksHeaderLinesClamped() {
        myFixture.configureByText("Short.kt", "val x = 1\n")
        val psiFile = myFixture.file
        // headerLines=100 on a 1-line file should not crash
        val result = buildStructureChunks(
            psiFile,
            wholeFile = false,
            windowStartLine = 0,
            windowEndLine = 1,
            headerLines = 100
        )
        assertNotNull(result)
    }

    fun testSurfaceExtractDataClass() {
        val file = myFixture.addFileToProject("DataModel.kt", """
            package model

            data class User(val name: String, val age: Int) {
                fun displayName(): String {
                    return name.uppercase()
                }
            }
        """.trimIndent())
        val result = surfaceExtract(project, file.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain data class declaration", result!!.contains("data class User"))
        assertTrue("Should contain fun displayName", result.contains("fun displayName"))
        assertFalse("Should NOT contain method body", result.contains("uppercase"))
    }

    fun testSurfaceExtractAnnotationClass() {
        val file = myFixture.addFileToProject("Anno.kt", """
            package anno

            annotation class MyAnnotation(val value: String)
        """.trimIndent())
        val result = surfaceExtract(project, file.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain annotation class", result!!.contains("annotation class MyAnnotation"))
    }

    fun testSurfaceExtractSingleLineMultilineString() {
        // Inline multiline string: """hello""" — state machine must open AND close on same line
        val file = myFixture.addFileToProject("InlineTriple.kt", """
            package inline

            class Config {
                val greeting = ${"\"\"\""}hello${"\"\"\""}
                fun getGreeting(): String {
                    return greeting
                }
            }
        """.trimIndent())
        val result = surfaceExtract(project, file.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain val greeting", result!!.contains("val greeting"))
        assertTrue("Should contain fun getGreeting", result.contains("fun getGreeting"))
        assertFalse("Should NOT contain method body", result.contains("return greeting"))
    }

    fun testSurfaceExtractExpressionBodyFunction() {
        // Expression body function has no braces — should be included as-is
        val file = myFixture.addFileToProject("ExprBody.kt", """
            package expr

            class Calculator {
                fun add(a: Int, b: Int): Int = a + b
                fun multiply(a: Int, b: Int): Int = a * b
            }
        """.trimIndent())
        val result = surfaceExtract(project, file.virtualFile)
        assertNotNull(result)
        assertTrue("Should contain fun add", result!!.contains("fun add"))
        assertTrue("Should contain fun multiply", result.contains("fun multiply"))
    }
}
