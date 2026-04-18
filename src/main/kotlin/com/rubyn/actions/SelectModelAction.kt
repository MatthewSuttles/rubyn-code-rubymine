package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.rubyn.RubynBundle
import com.rubyn.settings.RubynSettingsService
import com.rubyn.settings.RubynSettingsState

private val LOG = logger<SelectModelAction>()

/**
 * Action: choose the AI model used for future prompts.
 *
 * Displays a [JBPopupFactory] list popup showing all models for the currently
 * configured provider. Selecting a model persists it via [RubynProjectService.selectModel]
 * and notifies the bridge.
 *
 * Available in any Ruby project — no editor required.
 */
class SelectModelAction : AbstractRubynAction(
    RubynBundle.message("action.select.model")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val settings = RubynSettingsService.getInstance().settings()
        val models = modelsForProvider(settings)

        if (models.isEmpty()) {
            LOG.warn("SelectModelAction: no models available for provider '${settings.provider}'")
            return
        }

        val currentModel = settings.model
        val popup = JBPopupFactory.getInstance().createListPopup(
            ModelPopupStep(
                title = RubynBundle.message("popup.select.model.title"),
                models = models,
                current = currentModel,
                onSelected = { model ->
                    val service = getProjectService(project) ?: return@ModelPopupStep
                    service.selectModel(model)
                    LOG.info("SelectModelAction: model changed to '$model' for project '${project.name}'")
                },
            )
        )

        // Show relative to mouse location when available, otherwise under focus owner.
        val component = event.inputEvent?.component
        if (component != null) {
            popup.showUnderneathOf(component)
        } else {
            popup.showInBestPositionFor(event.dataContext)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Returns the ordered model list for the provider in [settings].
     * Falls back to all known models across all providers when the provider
     * is not recognised.
     */
    private fun modelsForProvider(settings: RubynSettingsState): List<String> =
        RubynSettingsState.MODELS_BY_PROVIDER[settings.provider]
            ?: RubynSettingsState.MODELS_BY_PROVIDER.values.flatten()

    // ── Popup step ────────────────────────────────────────────────────────

    private class ModelPopupStep(
        title: String,
        models: List<String>,
        private val current: String,
        private val onSelected: (String) -> Unit,
    ) : BaseListPopupStep<String>(title, models) {

        override fun getTextFor(value: String): String = if (value == current) "$value ✓" else value

        override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
            if (finalChoice) onSelected(selectedValue)
            return FINAL_CHOICE
        }

        override fun isSpeedSearchEnabled(): Boolean = true
    }
}
