package com.rubyn.settings

import com.intellij.openapi.options.Configurable
import com.rubyn.RubynBundle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * JetBrains Settings UI entry point for Rubyn (Tools > Rubyn).
 *
 * Registered in plugin.xml as an applicationConfigurable under the "tools"
 * parent. Full settings panel implementation lives in Task 4
 * (Settings Service & Configurable). This stub satisfies the plugin.xml
 * registration so the plugin loads cleanly.
 */
class RubynConfigurable : Configurable {

    override fun getDisplayName(): String = RubynBundle.message("settings.rubyn.title")

    override fun createComponent(): JComponent {
        // TODO (Task 4): return the full settings panel
        val panel = JPanel()
        panel.add(JLabel("Rubyn settings — coming in Task 4"))
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        // TODO (Task 4): persist settings
    }
}
