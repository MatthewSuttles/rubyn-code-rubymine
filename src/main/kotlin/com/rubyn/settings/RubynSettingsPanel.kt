package com.rubyn.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Kotlin UI DSL v2 settings panel for the Rubyn plugin.
 *
 * Sections:
 *  - General        — executable path with file browser and inline validation
 *  - Provider & Model — provider combo drives dynamic model combo
 *  - Budget & Limits  — token and cost ceilings (0 = unlimited)
 *  - Status           — read-only diagnostics row (placeholder for Task 3 data)
 *
 * This class owns no I/O. [RubynConfigurable] reads from / writes to it via
 * [loadFrom] and [applyTo].
 */
class RubynSettingsPanel {

    // ── Component references needed for cross-field logic ─────────────────

    private lateinit var executableField: TextFieldWithBrowseButton
    private lateinit var providerCombo: ComboBox<String>
    private lateinit var modelCombo: ComboBox<String>
    private lateinit var tokenSpinner: JSpinner
    private lateinit var costSpinner: JSpinner
    private lateinit var permissionCombo: ComboBox<String>
    private lateinit var statusLabel: JLabel

    // ── Root panel (built once, reused) ───────────────────────────────────

    val root: JPanel = buildPanel()

    // ── Panel construction ────────────────────────────────────────────────

    private fun buildPanel(): JPanel = panel {

        // ── Section: General ─────────────────────────────────────────────
        group("General") {
            row("Executable path:") {
                executableField = TextFieldWithBrowseButton().also { field ->
                    field.addBrowseFolderListener(
                        "Select rubyn-code Executable",
                        "Choose the rubyn-code binary",
                        null,
                        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
                    )
                }
                cell(executableField)
                    .align(AlignX.FILL)
                    .comment("Full path to the rubyn-code CLI binary (e.g. /usr/local/bin/rubyn-code)")
                    .validationOnApply {
                        validateExecutablePath(executableField.text)
                    }
            }
        }

        // ── Section: Provider & Model ─────────────────────────────────────
        group("Provider & Model") {
            row("Provider:") {
                providerCombo = ComboBox(RubynSettingsState.PROVIDERS.toTypedArray())
                cell(providerCombo)
                    .comment("AI provider to use for code assistance")

                providerCombo.addActionListener {
                    refreshModelCombo(providerCombo.selectedItem as? String ?: "anthropic")
                }
            }
            row("Model:") {
                modelCombo = ComboBox()
                cell(modelCombo)
                    .comment("Model within the selected provider")
            }
        }

        // ── Section: Budget & Limits ──────────────────────────────────────
        group("Budget & Limits") {
            row("Token budget per session:") {
                tokenSpinner = JSpinner(SpinnerNumberModel(0, 0, Int.MAX_VALUE, 1000))
                cell(tokenSpinner)
                    .comment("Maximum tokens allowed per session. Set to 0 for unlimited.")
            }
            row("Cost budget per session (USD):") {
                costSpinner = JSpinner(SpinnerNumberModel(0.0, 0.0, 1_000.0, 0.5))
                cell(costSpinner)
                    .comment("Maximum USD spend per session. Set to 0.00 for unlimited.")
            }
            row("Permission mode:") {
                permissionCombo = ComboBox(RubynSettingsState.PERMISSION_MODES.toTypedArray())
                cell(permissionCombo)
                    .comment(
                        "default — prompt for each action  |  " +
                            "acceptEdits — auto-accept file edits  |  " +
                            "bypassPermissions — no prompts (use with caution)"
                    )
            }
        }

        // ── Section: Status ───────────────────────────────────────────────
        group("Status") {
            row {
                statusLabel = JLabel("Agent status will appear here once connected.")
                cell(statusLabel)
                    .comment("Live status is populated by RubynProjectService (Task 3).")
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Populates every control from [state].
     * Called by [RubynConfigurable.reset].
     */
    fun loadFrom(state: RubynSettingsState) {
        executableField.text = state.executablePath

        providerCombo.selectedItem = state.provider
        refreshModelCombo(state.provider)
        modelCombo.selectedItem = state.model

        tokenSpinner.value = state.tokenBudget
        costSpinner.value = state.costBudget
        permissionCombo.selectedItem = state.permissionMode
    }

    /**
     * Reads every control into [state] and returns it.
     * Called by [RubynConfigurable.apply].
     */
    fun applyTo(state: RubynSettingsState): RubynSettingsState {
        return state.copy(
            executablePath = executableField.text.trim(),
            provider = providerCombo.selectedItem as? String ?: "anthropic",
            model = modelCombo.selectedItem as? String ?: "",
            tokenBudget = tokenSpinner.value as Int,
            costBudget = costSpinner.value as Double,
            permissionMode = permissionCombo.selectedItem as? String ?: "default",
        )
    }

    /**
     * Returns true if the current panel values differ from [state].
     * Called by [RubynConfigurable.isModified].
     */
    fun isModified(state: RubynSettingsState): Boolean {
        return executableField.text.trim() != state.executablePath ||
            providerCombo.selectedItem != state.provider ||
            modelCombo.selectedItem != state.model ||
            tokenSpinner.value != state.tokenBudget ||
            costSpinner.value != state.costBudget ||
            permissionCombo.selectedItem != state.permissionMode
    }

    /**
     * Returns the preferred focus component for the configurable.
     */
    fun preferredFocusedComponent(): JComponent = executableField.textField

    // ── Validation ────────────────────────────────────────────────────────

    private fun validateExecutablePath(path: String): ValidationInfo? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return ValidationInfo("Executable path must not be empty", executableField)
        }
        val file = File(trimmed)
        if (!file.isAbsolute) {
            return ValidationInfo("Path must be absolute", executableField)
        }
        if (!file.exists()) {
            return ValidationInfo("File does not exist: $trimmed", executableField)
        }
        if (!file.canExecute()) {
            return ValidationInfo("File is not executable: $trimmed", executableField)
        }
        return null
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Rebuilds the model combo box items when the provider changes.
     * Preserves the previously selected model if it exists in the new list,
     * otherwise selects the first available model.
     */
    private fun refreshModelCombo(provider: String) {
        val models = RubynSettingsState.MODELS_BY_PROVIDER[provider]
            ?: RubynSettingsState.MODELS_BY_PROVIDER.values.first()

        val previousSelection = modelCombo.selectedItem as? String
        val newModel = DefaultComboBoxModel(models.toTypedArray())
        modelCombo.model = newModel

        if (previousSelection != null && models.contains(previousSelection)) {
            modelCombo.selectedItem = previousSelection
        } else {
            modelCombo.selectedIndex = 0
        }
    }
}
