package com.nierduolong.morningbell.dailylog.lan

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** 无云账号设备身份：私钥只存在 Android Keystore，局域网协议只发送公钥与其指纹。 */
object DeviceIdentity {
    private const val KEY_ALIAS = "nierduolong_nearby_log_identity_v1"

    data class PublicIdentity(
        val deviceId: String,
        val publicKeyBase64: String,
    )

    fun getOrCreate(): PublicIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKeyPair()
            }
        }
        val publicBytes = store.getCertificate(KEY_ALIAS).publicKey.encoded
        val deviceId =
            MessageDigest.getInstance("SHA-256")
                .digest(publicBytes)
                .take(16)
                .joinToString("") { "%02x".format(it) }
        return PublicIdentity(deviceId, Base64.encodeToString(publicBytes, Base64.NO_WRAP))
    }

    fun signRequest(
        method: String,
        target: String,
        timestamp: Long,
        body: ByteArray?,
    ): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = store.getKey(KEY_ALIAS, null)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(canonicalRequest(method, target, timestamp, body))
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun verifyRequest(
        publicKeyBase64: String,
        method: String,
        target: String,
        timestamp: Long,
        body: ByteArray?,
        signatureBase64: String,
    ): Boolean =
        runCatching {
            val publicBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(canonicalRequest(method, target, timestamp, body))
            signature.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        }.getOrDefault(false)

    fun deviceIdForPublicKey(publicKeyBase64: String): String? =
        runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(Base64.decode(publicKeyBase64, Base64.NO_WRAP))
                .take(16)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()

    private fun canonicalRequest(
        method: String,
        target: String,
        timestamp: Long,
        body: ByteArray?,
    ): ByteArray {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body ?: ByteArray(0)).joinToString("") { "%02x".format(it) }
        return "${method.uppercase()}\n$target\n$timestamp\n$bodyHash".toByteArray(Charsets.UTF_8)
    }
}
