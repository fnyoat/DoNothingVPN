package com.fnyoat.donothingvpn

import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackagerTest {

    private fun resource(name: String): File =
        File(File("src/test/resources"), name).absoluteFile

    @Test
    fun repack_matches_golden_and_changes_label() {
        val sample = resource("sample.apk")
        val out = File.createTempFile("repack-", ".apk")
        out.deleteOnExit()
        val pk8 = javaClass.classLoader.getResourceAsStream("repack.pk8")!!.use { it.readBytes() }
        val cer = javaClass.classLoader.getResourceAsStream("repack.cer")!!.use { it.readBytes() }

        Repackager.buildWithKey(sample, out, "CoolVPN", pk8, cer)

        val golden = JSONObject(
            javaClass.classLoader.getResourceAsStream("golden.json")!!.use { it.readText() }
        )
        val entriesByBase64 = mutableMapOf<String, ByteArray>()
        ZipInputStream(out.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                entriesByBase64[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        for (name in listOf("META-INF/CERT.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA")) {
            val actual = Base64.getEncoder().encodeToString(entriesByBase64.getValue(name))
            assertEquals("$name mismatch vs Python golden", golden.getString(name), actual)
        }

        val manifest = entriesByBase64.getValue("AndroidManifest.xml")
        val utf16Name = "CoolVPN".toByteArray(Charsets.UTF_16LE)
        assertTrue("new label must be present in manifest bytes", manifest.contains(utf16Name))
        assertTrue(
            "template label must no longer be present",
            !manifest.contains("DoNothingVPN".toByteArray(Charsets.UTF_16LE))
        )
    }
}