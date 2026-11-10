package com.handcats.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A scrolling list may stop awaiting an image without cancelling other consumers. */
internal class SingleFlight<K, V>(private val scope: CoroutineScope) {
    private val requests = mutableMapOf<K, Deferred<V>>()

    suspend fun get(key: K, load: suspend () -> V): V {
        val request = synchronized(requests) {
            requests[key] ?: run {
                lateinit var created: Deferred<V>
                created = scope.async(start = CoroutineStart.LAZY) {
                    try { load() }
                    finally {
                        synchronized(requests) {
                            if (requests[key] === created) requests.remove(key)
                        }
                    }
                }
                requests[key] = created
                created
            }
        }
        return request.await()
    }

    fun clear() {
        val old = synchronized(requests) {
            requests.values.toList().also { requests.clear() }
        }
        old.forEach { it.cancel() }
    }
}
