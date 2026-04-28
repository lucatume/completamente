package com.github.lucatume.completamente.uitest.settings

import com.github.lucatume.completamente.uitest.BaseCompletamenteUiTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDialogUiTest : BaseCompletamenteUiTest() {

    @Test
    fun testServerUrlSettingPersistsAfterApply() {
        val newUrl = "http://127.0.0.1:9999"

        robot.runJs(
            """
            var pluginId = com.intellij.openapi.extensions.PluginId.findId('com.github.lucatume.completamente')
            var pluginCl = com.intellij.ide.plugins.PluginManagerCore.findPlugin(pluginId).getPluginClassLoader()
            var stateClass = java.lang.Class.forName(
                'com.github.lucatume.completamente.services.SettingsState', true, pluginCl)
            var service = com.intellij.openapi.application.ApplicationManager.getApplication().getService(stateClass)
            service.setServerUrl('$newUrl')
            """.trimIndent(),
            runInEdt = true,
        )

        assertEquals(newUrl, readStringSetting("getServerUrl"))
    }
}
