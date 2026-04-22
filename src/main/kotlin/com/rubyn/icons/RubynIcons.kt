package com.rubyn.icons

import com.intellij.openapi.util.IconLoader

/**
 * Icon constants for the Rubyn plugin.
 *
 * All icons are loaded from /icons/ in the plugin resources directory.
 * Two sizes are required by the IntelliJ platform:
 *   - 16×16 for menus, status bar, and most UI chrome
 *   - 13×13 for tool-window tab strip (supplied as *@13.svg suffix)
 *
 * Retina variants are picked up automatically when a file named
 * *@2x.svg exists alongside the base file.
 */
object RubynIcons {

    /** 16×16 icon shown on the tool-window tab strip and action toolbar. */
    @JvmField
    val ToolWindowIcon = IconLoader.getIcon("/icons/rubyn-toolwindow.svg", RubynIcons::class.java)

    /** 16×16 icon used on action menu items. */
    @JvmField
    val ActionIcon = IconLoader.getIcon("/icons/rubyn-action.svg", RubynIcons::class.java)

    /** 13×13 icon used in the status bar widget. */
    @JvmField
    val StatusBarIcon = IconLoader.getIcon("/icons/rubyn-statusbar.svg", RubynIcons::class.java)

    /** 16×16 green checkmark used in the diff viewer Accept toolbar action. */
    @JvmField
    val AcceptEdit = IconLoader.getIcon("/icons/rubyn-accept-edit.svg", RubynIcons::class.java)

    /** 16×16 red X used in the diff viewer Reject toolbar action. */
    @JvmField
    val RejectEdit = IconLoader.getIcon("/icons/rubyn-reject-edit.svg", RubynIcons::class.java)
}
