package com.handcats.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Private, bounded photo cache. The session is part of every cache key. */
internal class PhotoCache(private val directory: File, scope: CoroutineScope) {
    private val decoded = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val bitmaps = SingleFlight<String, Bitmap?>(scope)
    private val downloads = SingleFlight<String, ByteArray?>(scope)
    private val diskLock = Mutex()
    private val networkSlots = Semaphore(3)
    @Volatile private var generation = 0L

    suspend fun get(session: String, path: String, preview: Boolean, download: () -> ByteArray?): Bitmap? {
        val version = generation
        val key = digest("$session|$path")
        val bitmapKey = "$key|$preview"
        decoded.get(bitmapKey)?.let { return it }
        return bitmaps.get(bitmapKey) {
            withContext(Dispatchers.IO) {
                decoded.get(bitmapKey)?.let { return@withContext it }
                val bytes = downloads.get(key) {
                    withContext(Dispatchers.IO) {
                        val cached = diskLock.withLock {
                            val file = File(directory, key)
                            if (file.isFile && file.length() in 1..MAX_PHOTO_BYTES &&
                                System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) {
                                runCatching { file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
                            } else null
                        }
                        cached ?: networkSlots.withPermit {
                            download()?.also { data ->
                                if (data.size > MAX_PHOTO_BYTES) throw IOException("Photo too large")
                                diskLock.withLock {
                                    if (version == generation) {
                                        // Cache eviction or a full disk must not prevent displaying a download.
                                        runCatching {
                                            directory.mkdirs()
                                            File(directory, key).writeBytes(data)
                                            trimDisk()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val edge = if (preview) 320 else 1600
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > edge) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also {
                    if (version == generation) decoded.put(bitmapKey, it)
                }
            }
        }.takeIf { version == generation }
    }

    fun clearMemory() {
        generation++
        bitmaps.clear()
        downloads.clear()
        decoded.evictAll()
    }

    suspend fun remove(session: String, path: String) = withContext(Dispatchers.IO) {
        clearMemory()
        diskLock.withLock { File(directory, digest("$session|$path")).delete() }
        Unit
    }

    private fun trimDisk() {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= MAX_DISK_BYTES) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PHOTO_BYTES = 12L * 1024 * 1024
        const val MAX_DISK_BYTES = 64L * 1024 * 1024
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
