package io.ntnl.cybion.worker

import android.content.Context

/** Application context holder for code paths that have no Activity. */
object AppContext {
    @Volatile
    var context: Context? = null
}

object Prefs {
    private const val NAME = "cybion_worker"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
