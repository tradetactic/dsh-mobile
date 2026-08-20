package io.github.sayach.dshmobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.X509TrustManager

internal data class NativeSession(
    val instanceId: String,
    val deviceId: String,
    val deviceToken: String?,
    val deviceExpiresAt: Long?,
    val sessionToken: String,
    val csrfToken: String,
)

/** Uses platform TLS validation for native pairing and renewal. */
internal object NativeAuthClient {
    fun pair(origin: GatewayOrigin, token: String, caCertificate: ByteArray): NativeSession = post(
        origin,
        "/mobile-access/auth/native-pair",
        JSONObject().put("token", token).put("label", "DeepSeek Harness Android"),
        caCertificate,
    )

    fun renew(origin: GatewayOrigin, deviceToken: String, caCertificate: ByteArray): NativeSession = post(
        origin,
        "/mobile-access/auth/native-renew",
        JSONObject().put("deviceToken", deviceToken),
        caCertificate,
    )

    /** Fetches the public CA without credentials; the caller must fingerprint-bind it before use. */
    fun fetchPairingCa(origin: GatewayOrigin): ByteArray = bootstrapGet(origin, "/mobile-access/ca.cer", 16 * 1024)

    /** Compatibility discovery probe. Its metadata remains untrusted until pairing-key verification. */
    fun fetchDiscovery(origin: GatewayOrigin): JSONObject = JSONObject(
        bootstrapGet(origin, "/mobile-access/discovery", 8 * 1024).toString(Charsets.UTF_8),
    )

    private fun bootstrapGet(origin: GatewayOrigin, path: String, maxBytes: Int): ByteArray {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val connection = URL(origin.serialized + path).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = context.socketFactory
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("Bootstrap request failed (${connection.responseCode})")
            val length = connection.contentLengthLong
            if (length > maxBytes) error("Bootstrap response is too large")
            val body = connection.inputStream.use { it.readNBytes(maxBytes + 1) }
            if (body.size > maxBytes) error("Bootstrap response is too large")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun post(origin: GatewayOrigin, path: String, body: JSONObject, caCertificate: ByteArray): NativeSession {
        val connection = URL(origin.serialized + path).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = PinnedTls.socketFactory(caCertificate)
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Origin", origin.serialized)
            connection.setRequestProperty("Sec-Fetch-Site", "same-origin")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) error("Authentication failed (${connection.responseCode})")
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return NativeSession(
                instanceId = response.getString("instanceId"),
                deviceId = response.getString("deviceId"),
                deviceToken = response.optString("deviceToken").ifEmpty { null },
                deviceExpiresAt = response.optLong("deviceExpiresAt").takeIf { it > 0 },
                sessionToken = response.getString("sessionToken"),
                csrfToken = response.getString("csrfToken"),
            )
        } finally {
            connection.disconnect()
        }
    }
}
