package dev.gbcode.app.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the pairing seed) with an Android Keystore AES-GCM
 * key. The key never leaves the TEE/StrongBox; the ciphertext goes to
 * app-private storage. Production hardening for S02.01.
 */
object CredentialStore {
    private const val KEY_ALIAS = "gbcode-pairing-seed"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (!ks.containsAlias(KEY_ALIAS)) {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            gen.generateKey()
        }
        return ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    /** Encrypt to iv+ciphertext bytes, base64url-ready for prefs. */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv
        val out = ByteArray(4 + iv.size + encrypted.size)
        out[0] = (iv.size ushr 24).toByte(); out[1] = (iv.size ushr 16).toByte()
        out[2] = (iv.size ushr 8).toByte(); out[3] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 4, iv.size)
        System.arraycopy(encrypted, 0, out, 4 + iv.size, encrypted.size)
        return out
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
                ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        val iv = blob.copyOfRange(4, 4 + ivLen)
        val encrypted = blob.copyOfRange(4 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
