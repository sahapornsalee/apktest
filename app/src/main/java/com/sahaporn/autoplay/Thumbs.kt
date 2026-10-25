package com.sahaporn.autoplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

/** Loads small preview frames of video files, cached in memory and on disk. */
object Thumbs {
    private const val WIDTH = 320

    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.synchronizedSet(mutableSetOf<String>())
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, uri: String, into: ImageView) {
        into.tag = uri
        val cached = memory.get(uri)
        if (cached != null) {
            into.setImageBitmap(cached)
            return
        }
        into.setImageDrawable(null)
        if (uri in failed) return
        val app = ctx.applicationContext
        executor.execute {
            val file = cacheFile(app, uri)
            val bmp = BitmapFactory.decodeFile(file.path) ?: extract(app, uri)?.also { save(it, file) }
            if (bmp == null) failed.add(uri) else memory.put(uri, bmp)
            main.post { if (into.tag == uri && bmp != null) into.setImageBitmap(bmp) }
        }
    }

    private fun cacheFile(ctx: Context, uri: String): File {
        val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    private fun save(bmp: Bitmap, file: File) {
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun extract(ctx: Context, uri: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.parse(uri))
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // 10% in, so the frame is usually past black openings; capped at 2 minutes.
            val atUs = (durationMs * 100).coerceAtMost(120_000_000L)
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(atUs, option, WIDTH, WIDTH * 9 / 16)
            } else {
                r.getFrameAtTime(atUs, option)?.let { scale(it) }
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // Nothing left to clean up.
            }
        }
    }

    private fun scale(b: Bitmap): Bitmap {
        if (b.width <= WIDTH) return b
        return Bitmap.createScaledBitmap(b, WIDTH, WIDTH * b.height / b.width, true)
    }
}
