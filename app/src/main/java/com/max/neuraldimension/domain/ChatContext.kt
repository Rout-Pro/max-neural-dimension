package com.max.neuraldimension.domain

/**
 * Blueprint Part 13: context management. One bounded policy for how much
 * conversation history goes to the provider: a turn cap and a total character
 * budget, oldest turns dropped first. The newest turn is always kept; if it
 * alone exceeds the budget it is truncated from the tail so the request stays
 * bounded. Remembered facts and preferences are NOT part of this window (they
 * are injected separately by the backend client).
 */
object ChatContext {

    const val MAX_TURNS = 24
    const val MAX_CHARS = 16_000

    fun window(turns: List<ChatTurn>, maxTurns: Int = MAX_TURNS, maxChars: Int = MAX_CHARS): List<ChatTurn> {
        val kept = turns.takeLast(maxTurns).toMutableList()
        while (kept.size > 1 && kept.sumOf { it.text.length } > maxChars) {
            kept.removeAt(0)
        }
        if (kept.size == 1 && kept[0].text.length > maxChars) {
            kept[0] = kept[0].copy(text = kept[0].text.take(maxChars))
        }
        return kept
    }
}
