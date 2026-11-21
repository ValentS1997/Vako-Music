package ru.rikko.music

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Only mounted cells request media; cancelled cells cancel their provider request. */
internal object VideoThumbnails {
    private val slots = Semaphore(3)
    private val memory = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private suspend fun load(context: Context, uri: Uri, width: Int, height: Int, version: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$uri:$width:$height:$version"
            memory.get(key)?.let { return@withContext it }
            slots.withPermit {
                val signal = CancellationSignal()
                val cancellation = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
                try {
                    context.contentResolver.loadThumbnail(uri, Size(width, height), signal).also {
                        memory.put(key, it)
                    }
                } catch (_: Exception) {
                    null // Missing or revoked media has a stable placeholder.
                } finally {
                    cancellation.dispose()
                }
            }
        }

    @Composable
    fun Artwork(uri: Uri, width: Int, height: Int, version: String, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val key = "$uri:$width:$height:$version"
        val bitmap by produceState<Bitmap?>(initialValue = memory.get(key), key1 = key) {
            value = load(context, uri, width, height, version)
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("▶", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
