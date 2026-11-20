package ru.rikko.music

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {
    @Test fun fileDecryptionWorksAcrossBufferBoundaries() {
        val plain = ByteArray(130_001) { (it * 13).toByte() }
        val key = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        val file = File.createTempFile("encrypted-audio", ".part")
        try {
            file.writeBytes(cipher.doFinal(plain))
            val hexKey = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            Crypto.decryptAesCtrFileInPlace(file, hexKey)
            assertArrayEquals(plain, file.readBytes())
        } finally { file.delete() }
    }
}
