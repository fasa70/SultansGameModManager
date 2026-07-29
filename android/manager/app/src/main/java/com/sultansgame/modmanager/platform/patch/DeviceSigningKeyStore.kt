package com.sultansgame.modmanager.platform.patch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sultansgame.modmanager.model.DeviceSigningKeyState
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

data class DeviceSigningIdentity(
    val privateKey: PrivateKey,
    val certificateChain: Array<Certificate>,
    val certificateSha256: String,
)

class DeviceSigningKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun state(): DeviceSigningKeyState {
        val entry = entryOrNull()
        if (entry != null) return DeviceSigningKeyState.Ready
        return if (preferences.contains(KEY_MIGRATED_CERTIFICATE)) {
            DeviceSigningKeyState.MissingAfterMigration
        } else {
            DeviceSigningKeyState.NotCreated
        }
    }

    fun getOrCreate(): DeviceSigningIdentity {
        check(state() != DeviceSigningKeyState.MissingAfterMigration) {
            "设备签名密钥已丢失。请卸载此前迁移的游戏后重新迁移。"
        }
        return entryOrNull()?.toIdentity() ?: generate()
    }

    fun markMigrationCompleted(transactionId: String, identity: DeviceSigningIdentity) {
        require(identity.certificateSha256 == certificateSha256()) { "设备签名证书不匹配" }
        preferences.edit()
            .putString(KEY_MIGRATED_CERTIFICATE, identity.certificateSha256)
            .putString(KEY_LAST_TRANSACTION, transactionId)
            .apply()
    }

    fun certificateSha256(): String? = entryOrNull()?.toIdentity()?.certificateSha256

    fun lastMigrationTransaction(): String? = preferences.getString(KEY_LAST_TRANSACTION, null)

    private fun entryOrNull(): KeyStore.PrivateKeyEntry? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
    }

    private fun generate(): DeviceSigningIdentity {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                    )
                    .setCertificateSubject(X500Principal("CN=Sultan's Game Manager Device Signing"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date())
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + CERTIFICATE_VALIDITY_MILLIS))
                    .build(),
            )
        }.generateKeyPair()
        return requireNotNull(entryOrNull()).toIdentity()
    }

    private fun KeyStore.PrivateKeyEntry.toIdentity(): DeviceSigningIdentity = DeviceSigningIdentity(
        privateKey = privateKey,
        certificateChain = certificateChain,
        certificateSha256 = certificateChain.first().encoded.sha256(),
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFERENCES_NAME = "device-signing-identity"
        const val KEY_ALIAS = "sultans-game-manager.device-signing.v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 4096
        const val CERTIFICATE_VALIDITY_MILLIS = 30L * 365 * 24 * 60 * 60 * 1000
        const val KEY_MIGRATED_CERTIFICATE = "migrated-certificate"
        const val KEY_LAST_TRANSACTION = "last-transaction"
    }
}
