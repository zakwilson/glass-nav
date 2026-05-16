package dev.glass.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Streams an HTTP body to a file, reporting progress via [onProgress]. The download writes to
 * `<destFile>.tmp` first; on success the file is atomically renamed to `destFile`. On exception
 * or coroutine cancellation, the `.tmp` is deleted.
 *
 * `onProgress(bytesRead, totalBytes)` is throttled to at most ~4 Hz. `totalBytes` is -1 if the
 * server omitted `Content-Length`.
 */
class DataDownloader(baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.parentFile, destFile.name + ".tmp")
        tmp.delete()

        val req = Request.Builder().url(url).build()
        val call = client.newCall(req)

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IOException("HTTP ${resp.code} fetching $url")
                        }
                        val body = resp.body ?: throw IOException("empty body for $url")
                        val total = body.contentLength()
                        val source = body.source()
                        tmp.sink().buffer().use { sink ->
                            val buf = okio.Buffer()
                            var read = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = source.read(buf, 64 * 1024L)
                                if (n == -1L) break
                                sink.write(buf, n)
                                read += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 250) {
                                    onProgress(read, total)
                                    lastReport = now
                                }
                            }
                            sink.flush()
                            onProgress(read, total)
                        }
                    }
                    cont.resume(Unit)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
            if (!tmp.renameTo(destFile)) {
                throw IOException("failed to rename ${tmp.absolutePath} → ${destFile.absolutePath}")
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
