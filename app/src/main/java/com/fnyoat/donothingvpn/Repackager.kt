package com.fnyoat.donothingvpn

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.LinkedHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Repackager {
    private const val TEMPLATE_LABEL = "DoNothingVPN"

    fun build(context: Context, sourceApk: File, output: File, newLabel: String): Boolean {
        val pk8 = context.assets.open("repack.pk8").use { it.readBytes() }
        val cer = context.assets.open("repack.cer").use { it.readBytes() }
        return try {
            buildWithKey(sourceApk, output, newLabel, pk8, cer)
            true
        } catch (e: Exception) {
            e.printStackTrace(System.err)
            false
        }
    }

    fun buildWithKey(sourceApk: File, output: File, newLabel: String, pk8: ByteArray, cer: ByteArray) {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(sourceApk.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.name.startsWith("META-INF/")) {
                    entries[e.name] = zip.readBytes()
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val manifest = entries["AndroidManifest.xml"]
            ?: throw IllegalStateException("no AndroidManifest.xml in apk")
        val patched = patchManifest(manifest, newLabel)
        entries["AndroidManifest.xml"] = patched

        val mf = buildManifest(entries)
        val sf = buildSigFile(mf, entries)
        val rsa = buildSignatureFile(sf, pk8, cer)

        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { out ->
            out.setLevel(1)
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
            }
            out.putNextEntry(ZipEntry("META-INF/CERT.MF")); out.write(mf); out.closeEntry()
            out.putNextEntry(ZipEntry("META-INF/CERT.SF")); out.write(sf); out.closeEntry()
            out.putNextEntry(ZipEntry("META-INF/CERT.RSA")); out.write(rsa); out.closeEntry()
        }
    }

    // ---------- AXML manifest patch ----------

    private fun patchManifest(data: ByteArray, newLabel: String): ByteArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val xmlType = buf.short.toInt() and 0xffff
        val xmlHeaderSize = buf.short.toInt() and 0xffff
        val xmlChunkSize = buf.int
        if (xmlType != 3) throw IllegalStateException("not an xml chunk")
        val poolStart = xmlHeaderSize
        buf.position(poolStart)
        val poolType = buf.short.toInt() and 0xffff
        val poolHeader = buf.short.toInt() and 0xffff
        val poolChunkSize = buf.int
        if (poolType != 1) throw IllegalStateException("not a string pool")
        val stringCount = buf.int
        val styleCount = buf.int
        val poolFlags = buf.int
        val stringsStartRel = buf.int
        buf.int // stylesStart
        val isUtf8 = (poolFlags and 0x100) != 0
        val dataStart = poolStart + stringsStartRel
        val poolEnd = poolStart + poolChunkSize

        val offsets = IntArray(stringCount)
        buf.position(poolStart + poolHeader)
        for (i in 0 until stringCount) offsets[i] = buf.int

        fun stringAt(index: Int): String? {
            if (index < 0 || index >= stringCount) throw IllegalStateException("pool index out of range")
            val start = dataStart + offsets[index]
            val end = if (index + 1 < stringCount) dataStart + offsets[index + 1] else poolEnd
            if (start < 0 || end <= start || end > data.size) throw IllegalStateException("pool offset out of range")
            return decodeString(data, start, end, isUtf8)
        }

        val count = stringCount
        val mods = mutableMapOf<Int, String>()
        for (i in 0 until count) {
            val s = stringAt(i) ?: continue
            if (s == TEMPLATE_LABEL) {
                mods[i] = newLabel
            }
        }
        if (mods.isEmpty()) throw IllegalStateException("label not found in manifest")

        val newStringValues = Array(count) { i -> mods[i] ?: stringAt(i) ?: "" }
        val pool = buildStringPool(newStringValues, poolFlags)
            ?: throw IllegalStateException("string too long for pool")

        val oldPoolEndAbs = poolStart + poolChunkSize
        val rest = data.copyOfRange(oldPoolEndAbs, data.size)
        val newBuf = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(3.toShort())
        header.putShort(8.toShort())
        header.putInt(8 + pool.size + rest.size)
        newBuf.write(header.array())
        newBuf.write(pool)
        newBuf.write(rest)
        return newBuf.toByteArray()
    }

    private fun decodeString(data: ByteArray, start: Int, end: Int, isUtf8: Boolean): String? {
        return try {
            if (isUtf8) {
                null
            } else {
                val hasBigLen = (data[start].toInt() and 0xff) or ((data[start + 1].toInt() and 0xff) shl 8)
                var len: Int
                var dataStart: Int
                if ((hasBigLen and 0x8000) != 0) {
                    len = (hasBigLen and 0x7fffffff) or
                            ((data[start + 2].toInt() and 0xff) shl 16) or ((data[start + 3].toInt() and 0xff) shl 24)
                    dataStart = start + 4
                    if (len >= 0x7fffffff) return null
                } else {
                    len = hasBigLen
                    dataStart = start + 2
                }
                if (dataStart + len * 2 > end + 2 || start + 2 > data.size) return null
                val chars = CharArray(len)
                for (i in 0 until len) {
                    chars[i] = ((data[dataStart + i * 2].toInt() and 0xff) or
                            ((data[dataStart + i * 2 + 1].toInt() and 0xff) shl 8)).toChar()
                }
                String(chars)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildStringPool(strings: Array<String>, oldFlags: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var pos = 0
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            val s = strings[i]
            if (s.length > 0x7fff) return null
            val bytes = s.toByteArray(Charsets.UTF_16LE)
            val byteLen = s.length * 2
            while ((pos + 2 + byteLen) % 4 != 0) {
                out.write(0); pos++
            }
            offsets[i] = pos
            out.write(s.length and 0xff); out.write((s.length shr 8) and 0xff)
            out.write(bytes)
            pos += 2 + byteLen
        }
        while (pos % 4 != 0) {
            out.write(0); pos++
        }
        val poolBytes = out.toByteArray()
        val stringsStart = 28 + strings.size * 4
        val chunkSize = stringsStart + poolBytes.size
        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(1.toShort())
        header.putShort(28.toShort())
        header.putInt(chunkSize)
        header.putInt(strings.size)
        header.putInt(0) // styleCount
        header.putInt(oldFlags and 0x100.inv())
        header.putInt(stringsStart)
        header.putInt(0) // stylesStart
        val res = ByteArrayOutputStream()
        res.write(header.array())
        for (off in offsets) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(off)
            res.write(b.array())
        }
        res.write(poolBytes)
        return res.toByteArray()
    }

    // ---------- v1 signing ----------

    private fun buildManifest(entries: Map<String, ByteArray>): ByteArray {
        val sb = StringBuilder()
        sb.append("Manifest-Version: 1.0\r\n")
        sb.append("Created-By: 1.0 (DoNothingVPN Repack)\r\n")
        sb.append("\r\n")
        for ((name, bytes) in entries) {
            sb.append("Name: ").append(name).append("\r\n")
            sb.append("SHA1-Digest: ").append(b64(sha1(bytes))).append("\r\n")
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildSigFile(mf: ByteArray, entries: Map<String, ByteArray>): ByteArray {
        val sb = StringBuilder()
        sb.append("Signature-Version: 1.0\r\n")
        sb.append("Created-By: 1.0 (DoNothingVPN Repack)\r\n")
        sb.append("SHA1-Digest-Manifest: ").append(b64(sha1(mf))).append("\r\n")
        sb.append("\r\n")
        for ((name, bytes) in entries) {
            sb.append("Name: ").append(name).append("\r\n")
            sb.append("SHA1-Digest: ").append(b64(sha1(bytes))).append("\r\n")
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildSignatureFile(sf: ByteArray, pk8: ByteArray, cer: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("RSA")
        val key: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(pk8))
        val xCert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cer)) as X509Certificate
        val issuer = xCert.issuerX500Principal.encoded
        val serial = xCert.serialNumber

        val sha256 = sha256(sf)
        val attrs = derSet(
            derSeq(derOid("1.2.840.113549.1.9.3"), derSet(derOid("1.2.840.113549.1.7.1"))),
            derSeq(derOid("1.2.840.113549.1.9.4"), derSet(derOctet(sha256)))
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(key)
        sig.update(attrs)
        val signed = sig.sign()

        val digestAlg = derSeq(derOid("2.16.840.1.101.3.4.2.1"), derNull())
        val contentInfo = derSeq(derOid("1.2.840.113549.1.7.1"))
        val certSet = derBytes(0xa0, cer)
        val signerInfo = derSeq(
            derInt(1L),
            derSeq(issuer, derInt(serial)),
            digestAlg,
            derBytes(0xa0, attrs),
            derSeq(derOid("1.2.840.113549.1.1.1"), derNull()),
            derOctet(signed)
        )
        return derSeq(
            derInt(1L),
            derSet(digestAlg),
            contentInfo,
            certSet,
            derSet(signerInfo)
        )
    }

    // ---------- DER helpers ----------

    private fun derLen(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val tmp = ByteArrayOutputStream()
        var v = n
        val stack = ByteArrayOutputStream()
        while (v > 0) {
            stack.write(v and 0xff)
            v = v shr 8
        }
        val lenBytes = stack.toByteArray()
        tmp.write(0x80 or lenBytes.size)
        for (i in lenBytes.indices.reversed()) tmp.write(lenBytes[i].toInt())
        return tmp.toByteArray()
    }

    private fun derBytes(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(derLen(body.size))
        out.write(body)
        return out.toByteArray()
    }

    private fun derSeq(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (b in items) body.write(b)
        return derBytes(0x30, body.toByteArray())
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (b in items) body.write(b)
        return derBytes(0x31, body.toByteArray())
    }

    private fun derOctet(data: ByteArray): ByteArray = derBytes(0x04, data)

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derInt(v: Long): ByteArray {
        var n = v
        val stack = ByteArrayOutputStream()
        while (n != 0L) {
            stack.write((n and 0xffL).toInt())
            n = n shr 8
        }
        val raw = stack.toByteArray()
        val bytes = if (raw.isEmpty() || (raw.last().toInt() and 0x80) != 0) {
            ByteArrayOutputStream().run {
                write(0)
                write(raw)
                toByteArray()
            }
        } else raw
        return derBytes(0x02, bytes)
    }

    private fun derInt(v: BigInteger): ByteArray = derBytes(0x02, v.toByteArray())

    private fun derOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toLong() }
        val body = ByteArrayOutputStream()
        body.write(((parts[0] * 40 + parts[1]).toInt()))
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ByteArrayOutputStream()
            stack.write((v and 0x7fL).toInt())
            v = v shr 7
            while (v > 0) {
                stack.write(((v and 0x7fL) or 0x80L).toInt())
                v = v shr 7
            }
            val b = stack.toByteArray()
            for (j in b.indices.reversed()) body.write(b[j].toInt())
        }
        return derBytes(0x06, body.toByteArray())
    }

    private fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
}