package com.rubyn.settings

import com.intellij.openapi.options.Configurable
import com.rubyn.RubynBundle
import javax.swing.JComponent

/**
 * JetBrains Settings UI entry point for Rubyn (File > Settings > Tools > Rubyn).
 *
 * Registered in plugin.xml as an applicationConfigurable under the "tools"
 * parent. Delegates UI construction to [RubynSettingsPanel] and persistence
 * to [RubynSettingsService].
 *
 * The configurable is stateless — it reads and writes through the panel and
 * service on each lifecycle call, so the IDE can safely create/destroy it.
 */
class RubynConfigurable : Configurable {

    private var panel: RubynSettingsPanel? = null

    // ── Configurable ──────────────────────────────────────────────────────

    override fun getDisplayName(): String = RubynBundle.message("settings.rubyn.title")

    override fun createComponent(): JComponent {
        val p = RubynSettingsPanel()
        panel = p
        p.loadFrom(currentState())
        return p.root
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        panel?.preferredFocusedComponent()

    override fun isModified(): Boolean =
        panel?.isModified(currentState()) ?: false

    override fun apply() {
        val p = panel ?: return
        val updated = p.applyTo(currentState().copy())
        RubynSettingsService.getInstance().applySettings(updated)
    }

    override fun reset() {
        panel?.loadFrom(currentState())
    }

    override fun disposeUIResources() {
        panel = null
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun currentState(): RubynSettingsState =
        RubynSettingsService.getInstance().settings()
}
