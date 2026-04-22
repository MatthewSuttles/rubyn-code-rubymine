package com.rubyn.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.messages.Topic

private val LOG = logger<RubynSettingsService>()

/**
 * Application-level settings service for the Rubyn plugin.
 *
 * Persists [RubynSettingsState] to `rubyn-code.xml` in the IDE config
 * directory. Registered as an application-level service in plugin.xml so
 * there is exactly one instance per IDE process.
 *
 * Observers can listen for settings changes via the [TOPIC] message bus:
 *
 * ```kotlin
 * ApplicationManager.getApplication().messageBus
 *     .connect(disposable)
 *     .subscribe(RubynSettingsService.TOPIC, listener)
 * ```
 */
@State(
    name = "RubynSettings",
    storages = [Storage("rubyn-code.xml")],
)
class RubynSettingsService : PersistentStateComponent<RubynSettingsState> {

    /** In-memory copy of the current settings. Never null after construction. */
    private var state: RubynSettingsState = RubynSettingsState()

    // ── PersistentStateComponent ──────────────────────────────────────────

    override fun getState(): RubynSettingsState = state

    override fun loadState(loaded: RubynSettingsState) {
        state = loaded
        LOG.info("RubynSettings loaded: provider=${state.provider} model=${state.model}")
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of the current settings.
     * Callers that need to react to changes should subscribe to [TOPIC].
     */
    fun settings(): RubynSettingsState = state.copy()

    /**
     * Applies [newState] as the current settings and notifies all [TOPIC]
     * subscribers on the application message bus.
     *
     * Call this from [RubynConfigurable.apply] so that downstream services
     * (e.g. RubynProjectService in Task 3) can react without polling.
     */
    fun applySettings(newState: RubynSettingsState) {
        val previous = state
        state = newState.copy()
        LOG.info(
            "RubynSettings applied: provider=${state.provider} model=${state.model} " +
                "executablePath=${state.executablePath}"
        )
        notifyListeners(previous, state)
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun notifyListeners(previous: RubynSettingsState, current: RubynSettingsState) {
        try {
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            app.messageBus.syncPublisher(TOPIC).onSettingsChanged(previous, current)
        } catch (e: Exception) {
            LOG.warn("Failed to publish settings change notification", e)
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────

    companion object {

        /**
         * Message bus topic for settings change notifications.
         *
         * Payload: [SettingsListener] with the previous and current state.
         */
        @JvmField
        val TOPIC: Topic<SettingsListener> = Topic.create(
            "RubynSettingsChanged",
            SettingsListener::class.java,
        )

        /** Retrieves the singleton instance from the application service registry. */
        fun getInstance(): RubynSettingsService =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(RubynSettingsService::class.java)
    }

    // ── Listener interface ────────────────────────────────────────────────

    /**
     * Implement this interface and subscribe via [TOPIC] to receive callbacks
     * whenever settings are saved.
     */
    fun interface SettingsListener {
        fun onSettingsChanged(previous: RubynSettingsState, current: RubynSettingsState)
    }
}
