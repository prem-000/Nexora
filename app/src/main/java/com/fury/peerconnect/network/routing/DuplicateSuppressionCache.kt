package com.fury.peerconnect.network.routing

import java.util.Collections
import java.util.LinkedHashMap

class DuplicateSuppressionCache(private val maxEntries: Int = 1000) {

    private val seenMessageIds: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > maxEntries
            }
        }
    )

    fun isDuplicate(messageId: String): Boolean {
        if (seenMessageIds.containsKey(messageId)) {
            return true
        }
        seenMessageIds[messageId] = System.currentTimeMillis()
        return false
    }

    fun markProcessed(messageId: String) {
        seenMessageIds[messageId] = System.currentTimeMillis()
    }

    fun contains(messageId: String): Boolean {
        return seenMessageIds.containsKey(messageId)
    }

    fun clear() {
        seenMessageIds.clear()
    }

    val size: Int get() = seenMessageIds.size
}
