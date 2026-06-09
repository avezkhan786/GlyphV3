package com.glyph.glyph_v3.utils

import android.telephony.PhoneNumberUtils

object PhoneNumberUtil {

    /**
     * Aggressively normalizes a phone number to its last 10 digits.
     * This is the definitive strategy for matching numbers regardless of country code,
     * formatting, or leading zeros.
     *
     * @param phone The phone number string to normalize.
     * @return The last 10 digits of the phone number.
     */
    fun normalizeToLast10Digits(phone: String): String {
        // Step 1: Remove all non-numeric characters, and trim the start/end spaces.
        val digitsOnly = phone.replace(Regex("[^0-9]"), "").trim()
        
        // Step 2: Return the last 10 digits.
        // If the number is shorter than 10 digits (e.g., an office extension), it returns the whole thing.
        // This ensures a valid 10-digit match is possible only against another 10-digit number.
        return digitsOnly.takeLast(10)
    }

    fun formatAsE164(
        rawPhone: String,
        regionIso: String? = null,
        fallbackE164Phone: String? = null
    ): String {
        val cleaned = rawPhone.trim().replace(Regex("[^0-9+]"), "")
        if (cleaned.isEmpty()) return ""

        if (cleaned.startsWith("+")) {
            val digits = cleaned.drop(1).filter(Char::isDigit)
            return if (digits.isNotEmpty()) "+$digits" else ""
        }

        if (cleaned.startsWith("00")) {
            val digits = cleaned.drop(2).filter(Char::isDigit)
            return if (digits.isNotEmpty()) "+$digits" else ""
        }

        val normalizedDigits = cleaned.filter(Char::isDigit)
        if (normalizedDigits.isEmpty()) return ""

        val e164FromPlatform = regionIso
            ?.trim()
            ?.takeIf { it.length == 2 }
            ?.uppercase()
            ?.let { iso ->
                runCatching {
                    PhoneNumberUtils.formatNumberToE164(normalizedDigits, iso)
                }.getOrNull()
            }

        if (!e164FromPlatform.isNullOrBlank()) {
            return e164FromPlatform
        }

        val fallbackCallingCode = extractCallingCode(fallbackE164Phone)
        val regionCallingCode = regionIso
            ?.trim()
            ?.takeIf { it.length == 2 }
            ?.uppercase()
            ?.let(::resolveCountryCallingCode)

        if (normalizedDigits.length > 10) {
            if (!fallbackCallingCode.isNullOrBlank() && normalizedDigits.startsWith(fallbackCallingCode)) {
                return "+$normalizedDigits"
            }
            if (!regionCallingCode.isNullOrBlank() && normalizedDigits.startsWith(regionCallingCode)) {
                return "+$normalizedDigits"
            }
        }

        val nationalDigits = normalizedDigits.trimStart('0').ifEmpty { normalizedDigits }
        val resolvedCallingCode = fallbackCallingCode ?: regionCallingCode

        if (!resolvedCallingCode.isNullOrBlank() && nationalDigits.length in 6..12) {
            return "+$resolvedCallingCode$nationalDigits"
        }

        return if (normalizedDigits.length in 8..15) {
            "+$normalizedDigits"
        } else {
            cleaned
        }
    }

    fun formatForDisplay(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        if (digits.length < 10) return rawPhone

        return when (digits.length) {
            10 -> "+91 ${digits.substring(0, 5)} ${digits.substring(5)}"
            12 -> {
                val cc = digits.substring(0, 2)
                val p1 = digits.substring(2, 7)
                val p2 = digits.substring(7)
                "+$cc $p1 $p2"
            }
            else -> if (rawPhone.startsWith("+")) rawPhone else "+$rawPhone"
        }
    }

    private fun extractCallingCode(e164Phone: String?): String? {
        val digits = e164Phone
            .orEmpty()
            .trim()
            .replace(Regex("[^0-9+]"), "")
            .removePrefix("+")

        if (digits.length < 8) return null

        return knownCallingCodes
            .sortedByDescending { it.length }
            .firstOrNull { code ->
                digits.startsWith(code) && (digits.length - code.length) >= 6
            }
    }

    private fun resolveCountryCallingCode(regionIso: String): String? = countryCallingCodesByRegion[regionIso]

    private val countryCallingCodesByRegion = mapOf(
        "IN" to "91",
        "US" to "1",
        "CA" to "1",
        "GB" to "44",
        "PK" to "92",
        "BD" to "880",
        "AE" to "971",
        "SA" to "966",
        "EG" to "20",
        "DE" to "49",
        "FR" to "33",
        "ES" to "34",
        "IT" to "39",
        "NL" to "31",
        "SE" to "46",
        "NO" to "47",
        "DK" to "45",
        "PL" to "48",
        "TR" to "90",
        "RU" to "7",
        "UA" to "380",
        "BR" to "55",
        "MX" to "52",
        "AR" to "54",
        "ZA" to "27",
        "NG" to "234",
        "KE" to "254",
        "AU" to "61",
        "NZ" to "64",
        "SG" to "65",
        "MY" to "60",
        "ID" to "62",
        "TH" to "66",
        "VN" to "84",
        "PH" to "63",
        "JP" to "81",
        "KR" to "82",
        "CN" to "86"
    )

    private val knownCallingCodes = countryCallingCodesByRegion.values.toSet()
}
