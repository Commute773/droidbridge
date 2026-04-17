package com.commute773.droidbridge

import android.content.Context

data class AuthSettings(
    val bearerToken: String,
    val allowAnonymous: Boolean
)

object BridgePreferences {
    private const val PREFS_NAME = "droidbridge_prefs"
    private const val KEY_BEARER_TOKEN = "bearer_token"
    private const val KEY_ALLOW_ANONYMOUS = "allow_anonymous"

    fun getAuthSettings(context: Context): AuthSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AuthSettings(
            bearerToken = prefs.getString(KEY_BEARER_TOKEN, "")?.trim().orEmpty(),
            allowAnonymous = prefs.getBoolean(KEY_ALLOW_ANONYMOUS, false)
        )
    }

    fun saveAuthSettings(context: Context, bearerToken: String, allowAnonymous: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BEARER_TOKEN, bearerToken.trim())
            .putBoolean(KEY_ALLOW_ANONYMOUS, allowAnonymous)
            .apply()
    }
}
