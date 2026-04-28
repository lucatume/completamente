package com.github.lucatume.completamente.uitest.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * Copies the bundled sandbox project to a temp directory so each test class
 * gets a clean, mutable workspace.
 */
object SandboxProject {

    fun materialize(): Path {
        val target = Files.createTempDirectory("completamente-uitest-")
        copyResource("sandbox-project", target)
        // .idea/ is .gitignored globally, so it doesn't ship as a classpath
        // resource. Generate it here so IntelliJ recognizes the directory as
        // an existing project and skips the import wizard.
        writeIdeaFiles(target)
        return target
    }

    private fun copyResource(rootResource: String, target: Path) {
        val resourceUri = SandboxProject::class.java.classLoader
            .getResource(rootResource)?.toURI()
            ?: error("$rootResource resources missing")
        val source = Path.of(resourceUri)
        Files.walk(source).use { stream ->
            stream.collect(Collectors.toList()).forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun writeIdeaFiles(projectDir: Path) {
        val idea = projectDir.resolve(".idea").also { Files.createDirectories(it) }
        Files.writeString(
            idea.resolve("misc.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectRootManager" version="2" languageLevel="JDK_21" project-jdk-name="21" project-jdk-type="JavaSDK" />
            </project>
            """.trimIndent(),
        )
        Files.writeString(
            idea.resolve("modules.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectModuleManager">
                <modules>
                  <module fileurl="file://${'$'}PROJECT_DIR${'$'}/.idea/sandbox.iml" filepath="${'$'}PROJECT_DIR${'$'}/.idea/sandbox.iml" />
                </modules>
              </component>
            </project>
            """.trimIndent(),
        )
        Files.writeString(
            idea.resolve("sandbox.iml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <module type="JAVA_MODULE" version="4">
              <component name="NewModuleRootManager" inherit-compiler-output="true">
                <exclude-output />
                <content url="file://${'$'}MODULE_DIR${'$'}/..">
                  <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/../src" isTestSource="false" />
                </content>
                <orderEntry type="inheritedJdk" />
                <orderEntry type="sourceFolder" forTests="false" />
              </component>
            </module>
            """.trimIndent(),
        )
    }
}
