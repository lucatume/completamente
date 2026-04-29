package com.github.lucatume.completamente.uitest

import com.github.lucatume.completamente.uitest.support.ArtefactRecorder
import com.github.lucatume.completamente.uitest.support.FakeAgentCli
import com.github.lucatume.completamente.uitest.support.FakeLlamaServer
import com.github.lucatume.completamente.uitest.support.SandboxProject
import com.github.lucatume.completamente.uitest.support.SummaryWriter
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

/**
 * JUnit 4 base class for UI tests. Boots the fake llama server, configures the
 * plugin to point at it and at the fake agentic CLI, opens a sandbox project,
 * and writes structured failure artefacts on test failure.
 */
abstract class BaseCompletamenteUiTest {

    protected val robot: RemoteRobot = RemoteRobot(robotUrl)

    @get:Rule val artefactRule: TestWatcher = object : TestWatcher() {
        override fun starting(description: Description) {
            SummaryWriter.started(description.className, description.methodName)
        }
        override fun succeeded(description: Description) {
            SummaryWriter.passed(description.className, description.methodName)
        }
        override fun failed(e: Throwable, description: Description) {
            recorder.captureFailure(description.className, description.methodName, e)
            SummaryWriter.failed(description.className, description.methodName, e.message)
        }
        override fun skipped(e: org.junit.AssumptionViolatedException, description: Description) {
            SummaryWriter.ignored(description.className, description.methodName)
        }
    }

    @Before
    open fun baseSetUp() {
        ensureIdeReady()
        if (sandboxPath == null) sandboxPath = SandboxProject.materialize()
        openSandboxProject(sandboxPath!!)
        configureSettings(
            llamaUrl = llamaServer.baseUrl,
            order89CliCommand = fakeAgent.buildOrder89Command(fixture = "empty.txt"),
            walkthroughCliCommand = fakeAgent.buildWalkthroughCommand(fixture = "empty.txt"),
        )
        llamaServer.reset()
    }

    @After
    open fun baseTearDown() {
        runCatching {
            robot.runJs(
                """
                importPackage(com.intellij.openapi.fileEditor)
                importPackage(com.intellij.openapi.project)
                var projects = ProjectManager.getInstance().getOpenProjects()
                for (var i = 0; i < projects.length; i++) {
                    FileEditorManager.getInstance(projects[i]).closeAllFiles()
                }
                """.trimIndent(),
                runInEdt = true,
            )
        }
    }

    /** Set Order 89 fixture by rebuilding the cli command and pushing it into Settings. */
    protected fun useFakeAgentFixture(fixture: String) {
        configureSettings(
            llamaUrl = llamaServer.baseUrl,
            order89CliCommand = fakeAgent.buildOrder89Command(fixture),
            walkthroughCliCommand = fakeAgent.buildWalkthroughCommand(fixture = "empty.txt"),
        )
    }

    /** Set the Walkthrough fixture by rebuilding its CLI command and pushing it into Settings. */
    protected fun useFakeWalkthroughFixture(fixture: String) {
        configureSettings(
            llamaUrl = llamaServer.baseUrl,
            order89CliCommand = fakeAgent.buildOrder89Command(fixture = "empty.txt"),
            walkthroughCliCommand = fakeAgent.buildWalkthroughCommand(fixture),
        )
    }

    /** Stage the next /infill response from fake llama. */
    protected fun stageInfill(content: String, stop: List<String> = emptyList()) {
        llamaServer.enqueueInfill(content, stop)
    }

    /** Replace the next /infill response with a custom status (e.g. 500). */
    protected fun stageInfillError(status: Int, body: String = "{\"error\":\"stub\"}") {
        llamaServer.enqueueRaw("/infill", body, status)
    }

    private fun configureSettings(llamaUrl: String, order89CliCommand: String, walkthroughCliCommand: String) {
        val script = """
            var pluginId = com.intellij.openapi.extensions.PluginId.findId('com.github.lucatume.completamente')
            var pluginCl = com.intellij.ide.plugins.PluginManagerCore.findPlugin(pluginId).getPluginClassLoader()
            var stateClass = java.lang.Class.forName(
                'com.github.lucatume.completamente.services.SettingsState', true, pluginCl)
            var service = com.intellij.openapi.application.ApplicationManager.getApplication().getService(stateClass)
            service.setServerUrl('${escapeJs(llamaUrl)}')
            service.setOrder89CliCommand('${escapeJs(order89CliCommand)}')
            service.setWalkthroughCliCommand('${escapeJs(walkthroughCliCommand)}')
        """.trimIndent()
        robot.runJs(script, runInEdt = true)
    }

    /** Read a String setting back from the IDE-side service, by Java getter name (e.g. "getServerUrl"). */
    protected fun readStringSetting(getter: String): String =
        robot.callJs<String>(
            """
            var pluginId = com.intellij.openapi.extensions.PluginId.findId('com.github.lucatume.completamente')
            var pluginCl = com.intellij.ide.plugins.PluginManagerCore.findPlugin(pluginId).getPluginClassLoader()
            var stateClass = java.lang.Class.forName(
                'com.github.lucatume.completamente.services.SettingsState', true, pluginCl)
            var service = com.intellij.openapi.application.ApplicationManager.getApplication().getService(stateClass)
            String(service.${getter}())
            """.trimIndent(),
            runInEdt = true,
        )

    private fun openSandboxProject(path: Path) {
        val script = """
            var ProjectUtil = com.intellij.ide.impl.ProjectUtil
            var Paths       = java.nio.file.Paths
            ProjectUtil.openOrImport(Paths.get('${escapeJs(path.toAbsolutePath().toString())}'), null, true)
        """.trimIndent()
        robot.runJs(script, runInEdt = true)
        Thread.sleep(2000)
    }

    private fun ensureIdeReady() {
        val deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis()
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val outcome = runCatching { robot.runJs("/* ping */", runInEdt = false) }
            if (outcome.isSuccess) return
            lastError = outcome.exceptionOrNull()
            Thread.sleep(500)
        }
        error("Robot-server did not respond to JS within 60s; last error: $lastError")
    }

    private fun escapeJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private val robotUrl: String get() =
            System.getProperty("robot.server.url", "http://127.0.0.1:8082")

        @JvmStatic protected val llamaServer: FakeLlamaServer = FakeLlamaServer()
        @JvmStatic protected val fakeAgent: FakeAgentCli = FakeAgentCli.locate()
        @JvmStatic protected val recorder: ArtefactRecorder by lazy {
            val reportsDir = Path.of(System.getProperty("ui.test.reports.dir", "build/reports/uiTest"))
            Files.createDirectories(reportsDir)
            ArtefactRecorder(
                reportsDir = reportsDir,
                screenshotProvider = {
                    runCatching {
                        val img = RemoteRobot(robotUrl).getScreenshot()
                        ByteArrayOutputStream().use { out ->
                            ImageIO.write(img, "png", out)
                            out.toByteArray()
                        }
                    }.getOrDefault(ByteArray(0))
                },
                hierarchyProvider = {
                    runCatching {
                        RemoteRobot(robotUrl)
                            .findAll(ContainerFixture::class.java, byXpath("//*"))
                            .joinToString("\n") { it.javaClass.simpleName }
                    }.getOrDefault("")
                },
                ideaLogPath = locateIdeaLog(),
            )
        }
        @JvmStatic private var sandboxPath: Path? = null

        @BeforeClass
        @JvmStatic
        fun bootHarness() {
            llamaServer.start()
        }

        @AfterClass
        @JvmStatic
        fun shutdownHarness() {
            llamaServer.stop()
        }

        private fun locateIdeaLog(): Path? {
            val sandbox = System.getProperty("idea.sandbox.dir")
                ?: return Path.of("build/idea-sandbox/IC-2024.3.6/system/log/idea.log").takeIf { Files.exists(it) }
            return Path.of(sandbox, "system", "log", "idea.log").takeIf { Files.exists(it) }
        }
    }
}
