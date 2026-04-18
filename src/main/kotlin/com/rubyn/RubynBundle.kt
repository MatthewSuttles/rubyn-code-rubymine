package com.rubyn

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.RubynBundle"

/**
 * Typed accessor for the Rubyn localization bundle.
 *
 * Usage:
 *   RubynBundle.message("action.open.chat")
 *   RubynBundle.message("notification.rubyn.process.failed", errorMessage)
 */
object RubynBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    @JvmStatic
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
