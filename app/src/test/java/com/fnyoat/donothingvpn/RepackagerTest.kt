package com.fnyoat.donothingvpn

import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackagerTest {

    private fun resource(name: String): File =
        File(File("src/test/resources"), name).absoluteFile

    private fun readResourceBytes(name: String): ByteArray =
        File(resource(name)).readBytes()

    private fun containsSequence(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > hay.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun repack_matches_golden_and_changes_label() {
        val goldenText = File(resource("golden.json")).readText()
        val golden = Regex("\"(META-INF/CERT\\.[A-Z]+)\": \"([A-Za-z0-9+/=]+)\"")
            .findAll(goldenText)
            .associate { it.groupValues[1] to it.groupValues[2] }

        val out = File.createTempFile("repack-", ".apk")
        out.deleteOnExit()
        Repackager.buildWithKey(
            resource("sample.apk"),
            out,
            "CoolVPN",
            readResourceBytes("repack.pk8"),
            readResourceBytes("repack.cer")
        )

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(out.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                entries[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        for (name in listOf("META-INF/CERT.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA")) {
            val actual = Base64.getEncoder().encodeToString(entries.getValue(name))
            assertEquals("$name mismatch vs Python golden", golden.getValue(name), actual)
        }

        val manifest = entries.getValue("AndroidManifest.xml")
        assertTrue(
            "new label must be present in manifest bytes",
            containsSequence(manifest, "CoolVPN".toByteArray(Charsets.UTF_16LE))
        )
        assertTrue(
            "template label must no longer be present",
            !containsSequence(manifest, "DoNothingVPN".toByteArray(Charsets.UTF_16LE))
        )
    }
}