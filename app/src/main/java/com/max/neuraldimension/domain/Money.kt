package com.max.neuraldimension.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** INR parsing in exact decimal arithmetic - never binary float money math. */
object Money {
    /**
     * Parses a user-typed rupee string ("12", "12.5", "12.50") into paise.
     * Rounds half-up to the nearest paisa. Returns null for unparseable input.
     */
    fun parseRupeesToCents(input: String): Long? {
        val bd = input.trim().toBigDecimalOrNull() ?: return null
        val cents = bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP)
        return runCatching { cents.longValueExact() }.getOrNull()
    }
}
