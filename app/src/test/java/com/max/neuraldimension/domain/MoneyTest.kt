package com.max.neuraldimension.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun wholeRupees() = assertEquals(1200L, Money.parseRupeesToCents("12"))
    @Test fun oneDecimal() = assertEquals(1250L, Money.parseRupeesToCents("12.5"))
    @Test fun twoDecimals() = assertEquals(1250L, Money.parseRupeesToCents("12.50"))
    @Test fun roundsHalfUp() = assertEquals(1256L, Money.parseRupeesToCents("12.555"))
    @Test fun roundsDownBelowHalf() = assertEquals(1255L, Money.parseRupeesToCents("12.554"))
    @Test fun onePaisa() = assertEquals(1L, Money.parseRupeesToCents("0.01"))
    @Test fun exactDecimalNotFloat() = assertEquals(268L, Money.parseRupeesToCents("2.675"))
    @Test fun zeroIsZero() = assertEquals(0L, Money.parseRupeesToCents("0"))
    @Test fun garbageIsNull() = assertNull(Money.parseRupeesToCents("abc"))
    @Test fun emptyIsNull() = assertNull(Money.parseRupeesToCents(""))
    @Test fun loneDotIsNull() = assertNull(Money.parseRupeesToCents("."))
    @Test fun negativeParsesButCallerRejects() = assertEquals(-500L, Money.parseRupeesToCents("-5"))
}
