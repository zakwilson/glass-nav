package dev.glass.phone.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DataDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var downloader: DataDownloader

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = DataDownloader(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `streams body to file with atomic rename`() = runBlocking<Unit> {
        val payload = ByteArray(200_000) { (it and 0xff).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val dest = File(tmp.root, "sub/out.bin")
        val seen = mutableListOf<Long>()
        downloader.download(server.url("/x").toString(), dest) { read, _ -> seen += read }

        assertThat(dest.exists()).isTrue
        assertThat(dest.readBytes()).isEqualTo(payload)
        assertThat(File(dest.parentFile, dest.name + ".tmp").exists()).isFalse
        assertThat(seen.last()).isEqualTo(payload.size.toLong())
    }

    @Test fun `404 throws and leaves no tmp file`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))

        val dest = File(tmp.root, "missing.bin")
        runCatching {
            downloader.download(server.url("/x").toString(), dest) { _, _ -> }
        }.also { result ->
            assertThat(result.isFailure).isTrue
            assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        }
        assertThat(dest.exists()).isFalse
        assertThat(File(dest.parentFile, dest.name + ".tmp").exists()).isFalse
    }

    @Test fun `coroutine cancellation cleans up partial tmp`() = runBlocking<Unit> {
        // Send a slow body the consumer can interrupt mid-stream.
        val payload = ByteArray(2_000_000) { 0x42 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(payload))
                .throttleBody(8 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )

        val dest = File(tmp.root, "cancel.bin")
        val job = async {
            try { downloader.download(server.url("/x").toString(), dest) { _, _ -> } } catch (_: Throwable) {}
        }
        delay(150)
        job.cancel()
        job.join()

        assertThat(dest.exists()).isFalse
        assertThat(File(dest.parentFile, dest.name + ".tmp").exists()).isFalse
    }
}
