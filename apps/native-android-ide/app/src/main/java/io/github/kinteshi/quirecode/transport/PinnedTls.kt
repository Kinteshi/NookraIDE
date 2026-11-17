package io.github.kinteshi.quirecode.transport

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A single WebSocket route and optional pinned certificate fingerprint (`null` = public CAs, G37). */
data class ConnectionRouteSpec(val endpoint: String, val fingerprint: String? = null)

/**
 * What `quirecode-daemon pair --remote HOST` or `--tunnel HOST` prints:
 * `avf://HOST:PORT?fp=<sha256 hex or "public">&code=XXXX-XXXX[&alt=HOST:PORT...][&tunnel=HOST:PORT]`.
 * A hex [fingerprint] pins the host's self-signed certificate; a null [fingerprint] (`fp=public`,
 * for Cloudflare Tunnel or a public certificate) verifies with Android's trusted Root CAs.
 */
data class ConnectionString(
    val host: String,
    val port: Int,
    val fingerprint: String?,
    val code: String,
    val alternateRoutes: List<ConnectionRouteSpec> = emptyList(),
) {
    val endpoint: String get() = formatWssEndpoint(host, port)

    fun allRoutes(): List<ConnectionRouteSpec> =
        (listOf(ConnectionRouteSpec(endpoint, fingerprint)) + alternateRoutes).distinctBy { it.endpoint }
}

private fun formatWssEndpoint(host: String, port: Int): String =
    "wss://${if (':' in host) "[$host]" else host}:$port/ws"

private fun parseHostAndPort(raw: String, defaultPort: Int): Pair<String, Int>? {
    val uri = runCatching { java.net.URI("avf://${raw.trim()}") }.getOrNull() ?: return null
    val host = uri.host?.trim('[', ']')?.takeIf(String::isNotEmpty) ?: return null
    return host to if (uri.port > 0) uri.port else defaultPort
}

fun parseConnectionString(text: String): ConnectionString? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("avf://")) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    val host = uri.host?.trim('[', ']')?.takeIf(String::isNotEmpty) ?: return null
    val queryPairs = uri.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
        pair.split('=', limit = 2).takeIf { it.size == 2 }
            ?.let { (key, value) -> key to java.net.URLDecoder.decode(value, "UTF-8") }
    }
    val rawFp = queryPairs.firstOrNull { it.first == "fp" }?.second?.lowercase() ?: return null
    val publicTls = rawFp == "public"
    val fingerprint = if (publicTls) {
        null
    } else {
        rawFp.takeIf { it.length == 64 && it.all { ch -> ch in "0123456789abcdef" } } ?: return null
    }
    val code = queryPairs.firstOrNull { it.first == "code" }?.second?.takeIf(String::isNotBlank) ?: return null
    val defaultPort = if (publicTls) 443 else 19870
    val primaryPort = if (uri.port > 0) uri.port else defaultPort
    val primaryEndpoint = formatWssEndpoint(host, primaryPort)
    val alternates = buildList {
        for ((key, value) in queryPairs) {
            when (key) {
                "alt" -> parseHostAndPort(value, 19870)?.let { (altHost, altPort) ->
                    val ep = formatWssEndpoint(altHost, altPort)
                    if (ep != primaryEndpoint) add(ConnectionRouteSpec(ep, fingerprint))
                }
                "tunnel" -> {
                    val clean = value.trim().removePrefix("https://").removePrefix("wss://").removeSuffix("/ws").trimEnd('/')
                    parseHostAndPort(clean, 443)?.let { (tHost, tPort) ->
                        val ep = formatWssEndpoint(tHost, tPort)
                        if (ep != primaryEndpoint) add(ConnectionRouteSpec(ep, null))
                    }
                }
            }
        }
    }.distinctBy { it.endpoint }
    return ConnectionString(host, primaryPort, fingerprint, code, alternates)
}

/** Trusts exactly one certificate: the one whose DER SHA-256 is [fingerprint] (hex). */
class PinnedCertificateTrustManager(fingerprint: String) : X509TrustManager {
    private val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("The host sent no certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(actual, expected)) {
            throw CertificateException("The host's certificate doesn't match the paired fingerprint")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** [base] for one pinned host: certificate pinning replaces CA and host-name checks. */
fun OkHttpClient.pinnedTo(fingerprint: String): OkHttpClient {
    val trust = PinnedCertificateTrustManager(fingerprint)
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return newBuilder()
        .sslSocketFactory(tls.socketFactory, trust)
        .hostnameVerifier { _, _ -> true }
        .build()
}
