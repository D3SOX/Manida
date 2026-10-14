package io.github.d3sox.musicplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.d3sox.musicplayer.sftp.SftpAuthentication
import io.github.d3sox.musicplayer.sftp.SftpFiles
import io.github.d3sox.musicplayer.sftp.SftpProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSftpFilesTest {
    @Test
    fun androidCanAuthenticateWithAnSshKeyAndReadTheOriginalServerFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val properties = Properties().apply {
            File("/data/local/tmp/manida-sftp-fixture.properties").inputStream().use { load(it) }
        }
        val profile = SftpProfile("127.0.0.1", 14534, properties.getProperty("username"),
            properties.getProperty("musicDirectory"), properties.getProperty("fingerprint"))
        val authentication = SftpAuthentication(privateKey = File("/data/local/tmp/manida-sftp-client").readText())
        val received = File(instrumentation.targetContext.cacheDir, "sftp-${UUID.randomUUID()}.flac")
        try {
            SftpFiles(profile, authentication).download("Fixture Artist/Fixture Album/01 Northern Light.flac", received)
            val original = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
            assertArrayEquals(original, received.readBytes())
        } finally { received.delete() }
    }
}
