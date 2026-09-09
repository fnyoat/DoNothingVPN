package com.fnyoat.donothingvpn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackagerTest {

    private val v2BlockId = 0x7109871a

    private fun resource(name: String): File =
        File(File("src/test/resources"), name).absoluteFile

    private fun readResourceBytes(name: String): ByteArray =
        readAllNoClose(FileInputStream(resource(name)))

    private fun readAllNoClose(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        ins.copyTo(out)
        return out.toByteArray()
    }

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

    private fun findEocd(data: ByteArray): Int {
        var i = data.size - 22
        while (i >= 0) {
            if (data[i] == 0x50.toByte() && data[i + 1] == 0x4b.toByte() &&
                data[i + 2] == 0x05.toByte() && data[i + 3] == 0x06.toByte()
            ) return i
            i--
        }
        throw IllegalStateException("no EOCD found")
    }

    private fun leInt(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xff) or
            ((data[off + 1].toInt() and 0xff) shl 8) or
            ((data[off + 2].toInt() and 0xff) shl 16) or
            ((data[off + 3].toInt() and 0xff) shl 24)

    private fun leLong(data: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[off + i].toLong() and 0xff)
        return v
    }

    private fun u32le(v: Int): ByteArray =
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /** (first length-prefixed field, remainder) */
    private fun lpField(buf: ByteArray): Pair<ByteArray, ByteArray> {
        val len = leInt(buf, 0)
        return buf.copyOfRange(4, 4 + len) to buf.copyOfRange(4 + len, buf.size)
    }

    private fun chunkedDigest(sections: List<ByteArray>): ByteArray {
        val chunkDigests = ByteArrayOutputStream()
        var chunkCount = 0
        for (sec in sections) {
            var off = 0
            while (off < sec.size) {
                val size = minOf(1 shl 20, sec.size - off)
                val md = MessageDigest.getInstance("SHA-256")
                md.update(0xa5.toByte())
                md.update(u32le(size))
                md.update(sec, off, size)
                chunkDigests.write(md.digest())
                chunkCount++
                off += size
            }
        }
        val agg = java.nio.ByteBuffer.allocate(5 + chunkCount * 32)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(0x5a.toByte())
            .putInt(chunkCount)
            .put(chunkDigests.toByteArray())
            .array()
        return MessageDigest.getInstance("SHA-256").digest(agg)
    }

    private fun repack(output: File) {
        Repackager.buildWithKey(
            resource("sample.apk"),
            output,
            "CoolVPN",
            readResourceBytes("repack.pk8"),
            readResourceBytes("repack.cer")
        )
        val fixed = File("app/build/repackage/repacked.apk").absoluteFile
        fixed.parentFile?.mkdirs()
        output.copyTo(fixed, overwrite = true)
    }

    @Test
    fun repack_has_verifiable_v2_signature() {
        val out = File.createTempFile("repack-", ".apk")
        out.deleteOnExit()
        repack(out)

        val data = out.readBytes()
        val eocdStart = findEocd(data)
        val cdirOffset = leInt(data, eocdStart + 16)
        val footerSize = leLong(data, cdirOffset - 24)
        val blockStart = (cdirOffset - (footerSize + 8)).toInt()
        assertTrue(blockStart > 0)
        assertTrue("magic", "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
            .contentEquals(data.copyOfRange(cdirOffset - 16, cdirOffset)))
        assertEquals("header/footer size", footerSize, leLong(data, blockStart))

        // find v2 block inside signing block
        val pairs = data.copyOfRange(blockStart + 8, cdirOffset - 24)
        var pos = 0
        var v2: ByteArray? = null
        while (pos < pairs.size) {
            val plen = leLong(pairs, pos).toInt()
            val id = leInt(pairs, pos + 8)
            if (id == v2BlockId) v2 = pairs.copyOfRange(pos + 12, pos + 8 + plen)
            pos += 8 + plen
        }
        val v2Block = v2 ?: error("no v2 block")

        // v2 block = LP(LP(signer)); signer = LP(signedData) LP(signatures) LP(publicKey)
        val signers = lpField(v2Block).first
        val signer = lpField(signers).first
        val signedData = lpField(signer).first
        val signaturesField = lpField(signer).second
        val signatures = lpField(signaturesField).first
        val publicKey = lpField(signaturesField).first

        // signedData = LP(digests) LP(certs) LP(attrs)
        val digestsField = lpField(signedData).first
        val certsRemainder = lpField(signedData).second
        val certsField = lpField(certsRemainder).first
        val attrsRemainder = lpField(certsRemainder).second
        val attrsContent = lpField(attrsRemainder).first
        assertTrue("attrs must be empty", attrsContent.isEmpty())
        val digestEntry = lpField(digestsField).first
        assertEquals("digest alg id", 0x0103, leInt(digestEntry, 0))
        val expectedDigest = lpField(digestEntry.copyOfRange(4, digestEntry.size)).first

        val certBytes = lpField(certsField).first

        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes))
        assertTrue("spki matches cert", cert.publicKey.encoded.contentEquals(publicKey))

        val signatureEntry = lpField(signatures).first
        assertEquals("signature alg id", 0x0103, leInt(signatureEntry, 0))
        val signatureBytes = lpField(signatureEntry.copyOfRange(4, signatureEntry.size)).first
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(cert.publicKey)
        verifier.update(signedData)
        assertTrue("RSA signature over signed-data must verify", verifier.verify(signatureBytes))

        // recompute chunked content digest over [0,blockStart) + cdir + eocd(offset->blockStart)
        val before = data.copyOfRange(0, blockStart)
        val cdir = data.copyOfRange(cdirOffset, eocdStart)
        val eocd = data.copyOfRange(eocdStart, data.size)
        val eocdMod = eocd.copyOf()
        System.arraycopy(u32le(blockStart), 0, eocdMod, 16, 4)
        assertTrue("content digest", expectedDigest.contentEquals(chunkedDigest(listOf(before, cdir, eocdMod))))
    }

    @Test
    fun repack_matches_golden_and_changes_label() {
        val goldenText = String(readResourceBytes("golden.json"), Charsets.UTF_8)
        val golden = Regex("\"(META-INF/CERT\\.[A-Z]+)\":\\s*\"([A-Za-z0-9+/=]+)\"")
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
                entries[e.name] = readAllNoClose(zip)
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