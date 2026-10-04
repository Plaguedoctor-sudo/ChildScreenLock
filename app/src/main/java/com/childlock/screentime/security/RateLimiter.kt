package com.childlock.screentime.security

import android.content.Context
import android.content.SharedPreferences

class RateLimiter(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("sovereign_rate_limits", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val BASE_COOLDOWN_MS = 60_000L // 1 minute
    }

    data class RateLimitStatus(
        val isLockedOut: Boolean,
        val remainingCooldownSeconds: Long,
        val remainingAttempts: Int
    )

    fun checkStatus(): RateLimitStatus {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (now < lockoutUntil) {
            val remainingSec = (lockoutUntil - now) / 1000L
            return RateLimitStatus(true, remainingSec, 0)
        }

        val failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        val remaining = MAX_FAILED_ATTEMPTS - failedAttempts
        return RateLimitStatus(false, 0L, remaining.coerceAtLeast(0))
    }

    fun recordSuccess() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun recordFailure(): RateLimitStatus {
        var failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val now = System.currentTimeMillis()

        if (failed >= MAX_FAILED_ATTEMPTS) {
            // Engage progressive cooldown
            val multiplier = (failed - MAX_FAILED_ATTEMPTS + 1).coerceAtMost(5)
            val lockoutDuration = BASE_COOLDOWN_MS * multiplier
            val lockoutUntil = now + lockoutDuration

            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, failed)
                .putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
                .apply()

            return RateLimitStatus(true, lockoutDuration / 1000L, 0)
        } else {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, failed)
                .apply()
            return RateLimitStatus(false, 0L, MAX_FAILED_ATTEMPTS - failed)
        }
    }
}
