package io.github.mangi.eta.local.subagent

import kotlin.math.abs

internal fun subagentPriorityOrder(
    eligibleIds: List<String>,
    savedIds: List<String>,
    ordinaryModelId: String?,
): List<String> {
    val eligible = eligibleIds.toSet()
    val saved = savedIds.filter { it in eligible }.distinct()
    val front = saved.ifEmpty { listOfNotNull(ordinaryModelId?.takeIf { it in eligible }) }
    return front + eligible.filterNot { it in front }
}

internal class SubagentPriorityDrag(private val source: List<String>, val id: String) {
    var order: List<String> = source
        private set
    var translationY = 0f
        private set

    private val start = source.indexOf(id)
    private var travelY = 0f
    private var target = start
    private var finished = false

    fun dragBy(deltaY: Float, rowHeights: Map<String, Int>) {
        if (finished || start < 0) return
        travelY += deltaY
        val direction = if (travelY > 0f) 1 else -1
        var index = start
        var crossed = 0f
        // Measure from the original order so returning to the origin undoes every
        // swap, even when neighbors have different heights or one event crosses several.
        while (index + direction in source.indices) {
            val next = index + direction
            val height = rowHeights[source[next]]?.takeIf { it > 0 } ?: break
            if (abs(travelY) <= crossed + height / 2f) break
            crossed += height
            index = next
        }
        if (index != target) {
            target = index
            order = if (index == start) source else source.toMutableList().apply { add(index, removeAt(start)) }
        }
        translationY = travelY - crossed * direction
    }

    fun finish(): List<String>? {
        if (finished) return null
        finished = true
        return order.takeUnless { it == source }
    }
}
