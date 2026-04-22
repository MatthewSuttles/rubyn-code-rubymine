package com.rubyn.settings

/**
 * Persistent state for the Rubyn plugin settings.
 *
 * Stored by [RubynSettingsService] in rubyn-code.xml under the IDE config
 * directory. All fields must have default values so that XmlSerializer can
 * round-trip the state cleanly on first run (no saved file yet).
 *
 * @property executablePath  Absolute path to the rubyn-code binary.
 * @property provider        AI provider identifier (e.g. "anthropic", "openai").
 * @property model           Model identifier within the selected provider.
 * @property tokenBudget     Maximum tokens per session (0 = unlimited).
 * @property costBudget      Maximum USD cost per session (0.0 = unlimited).
 * @property permissionMode  Permission mode sent to rubyn-code on connect
 *                           ("default", "acceptEdits", "bypassPermissions", "planOnly").
 */
data class RubynSettingsState(
    var executablePath: String = "",
    var provider: String = "anthropic",
    var model: String = "claude-sonnet-4-5",
    var tokenBudget: Int = 0,
    var costBudget: Double = 0.0,
    var permissionMode: String = "default",
) {
    companion object {
        /** Provider → ordered list of model identifiers shown in the combo box. */
        val MODELS_BY_PROVIDER: Map<String, List<String>> = mapOf(
            "anthropic" to listOf(
                "claude-opus-4-5",
                "claude-sonnet-4-5",
                "claude-haiku-4-5",
            ),
            "openai" to listOf(
                "gpt-4o",
                "gpt-4o-mini",
                "o1",
                "o3-mini",
            ),
            "google" to listOf(
                "gemini-2.0-flash",
                "gemini-2.0-pro",
            ),
        )

        val PROVIDERS: List<String> = MODELS_BY_PROVIDER.keys.toList()

        val PERMISSION_MODES: List<String> = listOf("default", "acceptEdits", "bypassPermissions", "planOnly")
    }
}
