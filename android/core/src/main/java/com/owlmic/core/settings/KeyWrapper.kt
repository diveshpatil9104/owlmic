package com.owlmic.core.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals secrets (the identity key, pairing keys) before they reach the app's files. */
interface KeyWrapper {
    fun wrap(secret: ByteArray): ByteArray

    /** Null when the blob can't be opened, for example after the Keystore key was lost. */
    fun unwrap(blob: ByteArray): ByteArray?
}

/** AES-256-GCM with a key that never leaves the Android Keystore. Blob = 12-byte IV ‖ ciphertext and tag. */
class KeystoreWrapper : KeyWrapper {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(secret)
    }

    override fun unwrap(blob: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOf(12)))
            doFinal(blob, 12, blob.size - 12)
        }
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "owlmic-store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
