package com.childlock.screentime.security

object CredentialPolicyValidator {

    private val BANNED_PINS = setOf(
        "0000", "1111", "2222", "3333", "4444", "5555",
        "6666", "7777", "8888", "9999", "1234", "4321",
        "2580", "0852", "1212", "6969", "1357", "2468",
        "1122", "1313", "1004", "2000", "1999", "1998"
    )

    data class ValidationResult(val isValid: Boolean, val reason: String? = null)

    fun validatePin(pin: String): ValidationResult {
        val clean = pin.trim()
        if (clean.length < 4) {
            return ValidationResult(false, "PIN must be at least 4 digits.")
        }
        if (!clean.all { it.isDigit() }) {
            return ValidationResult(false, "PIN must contain only digits.")
        }
        if (BANNED_PINS.contains(clean)) {
            return ValidationResult(false, "Cannot use predictable or default PINs (e.g. 1234, 0000).")
        }
        // Reject all identical digits
        if (clean.all { it == clean[0] }) {
            return ValidationResult(false, "Cannot use repetitive numbers.")
        }
        // Reject sequential ascending
        if (isSequentialAscending(clean)) {
            return ValidationResult(false, "Cannot use sequential numbers (e.g. 1234, 2345).")
        }
        // Reject sequential descending
        if (isSequentialDescending(clean)) {
            return ValidationResult(false, "Cannot use sequential numbers (e.g. 4321, 5432).")
        }
        return ValidationResult(true)
    }

    private fun isSequentialAscending(s: String): Boolean {
        for (i in 0 until s.length - 1) {
            if (s[i + 1] - s[i] != 1) return false
        }
        return true
    }

    private fun isSequentialDescending(s: String): Boolean {
        for (i in 0 until s.length - 1) {
            if (s[i] - s[i + 1] != 1) return false
        }
        return true
    }
}
