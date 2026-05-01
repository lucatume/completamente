package com.github.lucatume.completamente.settings

import com.github.lucatume.completamente.BaseCompletionTest
import com.github.lucatume.completamente.services.DEFAULT_ORDER89_CLI_COMMAND_CLAUDE
import com.github.lucatume.completamente.services.DEFAULT_ORDER89_CLI_COMMAND_PI
import com.github.lucatume.completamente.services.DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE
import com.github.lucatume.completamente.services.DEFAULT_WALKTHROUGH_CLI_COMMAND_PI
import com.github.lucatume.completamente.services.SettingsState
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea

class SettingsConfigurableTest : BaseCompletionTest() {

    override fun tearDown() {
        try {
            // Restore the persisted state to defaults via the same copy used at apply time, so
            // adding a new field to SettingsState doesn't introduce a fresh "leaks between tests"
            // hole in this teardown.
            com.intellij.util.xmlb.XmlSerializerUtil.copyBean(SettingsState(), SettingsState.getInstance())
        } finally {
            super.tearDown()
        }
    }

    fun testDisplayName() {
        val configurable = SettingsConfigurable()
        assertEquals("completamente", configurable.displayName)
    }

    fun testIsModifiedReturnsFalseInitially() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        assertFalse(configurable.isModified)
    }

    fun testApplyWithNonNumericRingNChunksUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("ringNChunks")
        field.isAccessible = true
        field.set(configurable, "not_a_number")

        configurable.apply()

        assertEquals(16, state.ringNChunks)
    }

    fun testApplyWithNonNumericContextSizeUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("contextSize")
        field.isAccessible = true
        field.set(configurable, "not_a_number")

        configurable.apply()

        assertEquals(32768, state.contextSize)
    }

    fun testApplyWithNonNumericNPredictUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("nPredict")
        field.isAccessible = true
        field.set(configurable, "xyz")

        configurable.apply()

        assertEquals(128, state.nPredict)
    }

    fun testApplyWithNonNumericRingChunkSizeUsesDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("ringChunkSize")
        field.isAccessible = true
        field.set(configurable, "abc")

        configurable.apply()

        assertEquals(64, state.ringChunkSize)
    }

    fun testApplyWithValidContextSize() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("contextSize")
        field.isAccessible = true
        field.set(configurable, "4096")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals(4096, state.contextSize)
    }

    fun testApplyWithValidNPredict() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("nPredict")
        field.isAccessible = true
        field.set(configurable, "256")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals(256, state.nPredict)
    }

    fun testApplyWithAutoSuggestionsUnchecked() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("autoSuggestions")
        field.isAccessible = true
        field.set(configurable, false)

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertFalse(state.autoSuggestions)
    }

    fun testApplyWithDebugLoggingEnabled() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("debugLogging")
        field.isAccessible = true
        field.set(configurable, true)

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertTrue(state.debugLogging)
    }

    fun testResetRestoresDebugLogging() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        state.debugLogging = false
        configurable.reset()

        val field = SettingsConfigurable::class.java.getDeclaredField("debugLogging")
        field.isAccessible = true
        assertFalse(field.get(configurable) as Boolean)
    }

    // -- Order 89 CLI command field --

    fun testOrder89CliCommandFieldDefaultsToBundledCommand() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CliCommand")
        field.isAccessible = true
        assertEquals(DEFAULT_ORDER89_CLI_COMMAND_PI, field.get(configurable) as String)
    }

    fun testApplyWritesOrder89CliCommandToState() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CliCommand")
        field.isAccessible = true
        field.set(configurable, "claude --prompt-file %%prompt_file%%")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals("claude --prompt-file %%prompt_file%%", state.order89CliCommand)
    }

    fun testApplyBlankOrder89CliCommandFallsBackToDefault() {
        val configurable = SettingsConfigurable()
        configurable.createComponent()
        val state = SettingsState.getInstance()

        val field = SettingsConfigurable::class.java.getDeclaredField("order89CliCommand")
        field.isAccessible = true
        field.set(configurable, "   ")

        val method = SettingsConfigurable::class.java.getDeclaredMethod("applyToState")
        method.isAccessible = true
        method.invoke(configurable)

        assertEquals(DEFAULT_ORDER89_CLI_COMMAND_PI, state.order89CliCommand)
    }

    // -- pi / claude code template buttons --

    fun testTemplateButtonsResetTextareaToTheirDefaults() {
        val cases = listOf(
            Triple("order89.textarea", "order89.pi-button", DEFAULT_ORDER89_CLI_COMMAND_PI),
            Triple("order89.textarea", "order89.claude-button", DEFAULT_ORDER89_CLI_COMMAND_CLAUDE),
            Triple("walkthrough.textarea", "walkthrough.pi-button", DEFAULT_WALKTHROUGH_CLI_COMMAND_PI),
            Triple("walkthrough.textarea", "walkthrough.claude-button", DEFAULT_WALKTHROUGH_CLI_COMMAND_CLAUDE),
        )
        for ((areaId, buttonId, expected) in cases) {
            val configurable = SettingsConfigurable()
            val component = configurable.createComponent() as Container
            val area = findById<JTextArea>(component, areaId)
            area.text = "sentinel-$buttonId"

            findById<JButton>(component, buttonId).doClick()

            assertEquals("$buttonId must reset $areaId", expected, area.text)
        }
    }

    private inline fun <reified T : javax.swing.JComponent> findById(root: Container, id: String): T {
        val matches = walkComponents(root).filterIsInstance<T>()
            .filter { it.getClientProperty(SETTINGS_COMPONENT_ID) == id }.toList()
        assertEquals("Expected exactly one ${T::class.simpleName} with id=$id", 1, matches.size)
        return matches.single()
    }

    private fun walkComponents(root: Container): Sequence<java.awt.Component> = sequence {
        yield(root)
        for (child in root.components) {
            if (child is Container) yieldAll(walkComponents(child))
            else yield(child)
        }
    }
}
