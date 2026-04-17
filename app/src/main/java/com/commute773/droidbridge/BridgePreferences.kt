package com.commute773.droidbridge

import android.content.Context
import java.security.SecureRandom

data class AuthSettings(
    val bearerToken: String,
    val allowAnonymous: Boolean
)

object BridgePreferences {
    private const val PREFS_NAME = "droidbridge_prefs"
    private const val KEY_BEARER_TOKEN = "bearer_token"
    private const val KEY_ALLOW_ANONYMOUS = "allow_anonymous"
    private const val TOKEN_LENGTH = 16

    private val random = SecureRandom()
    private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun getAuthSettings(context: Context): AuthSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bearerToken = if (prefs.contains(KEY_BEARER_TOKEN)) {
            prefs.getString(KEY_BEARER_TOKEN, "")?.trim().orEmpty()
        } else {
            val generated = generateBearerToken()
            prefs.edit().putString(KEY_BEARER_TOKEN, generated).apply()
            generated
        }
        return AuthSettings(
            bearerToken = bearerToken,
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

    private fun generateBearerToken(length: Int = TOKEN_LENGTH): String {
        return buildString(length) {
            repeat(length) {
                append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)])
            }
        }
    }
}
