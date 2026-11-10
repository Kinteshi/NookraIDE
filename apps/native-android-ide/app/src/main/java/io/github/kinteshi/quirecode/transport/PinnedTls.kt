package io.github.kinteshi.quirecode.transport

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * What `quirecode-daemon pair --remote HOST` or `--tunnel HOST` prints:
 * `avf://HOST:PORT?fp=<sha256 hex or "public">&code=XXXX-XXXX`.
 * A hex [fingerprint] pins the host's self-signed certificate; a null [fingerprint] (`fp=public`,
 * for Cloudflare Tunnel or a public certificate) verifies with Android's trusted Root CAs.
 */
data class ConnectionString(val host: String, val port: Int, val fingerprint: String?, val code: String) {
    val endpoint: String get() = "wss://${if (':' in host) "[$host]" else host}:$port/ws"
}

fun parseConnectionString(text: String): ConnectionString? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("avf://")) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    val host = uri.host?.trim('[', ']')?.takeIf(String::isNotEmpty) ?: return null
    val query = uri.rawQuery?.split('&').orEmpty().mapNotNull { pair ->
        pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key to java.net.URLDecoder.decode(value, "UTF-8") }
    }.toMap()
    val rawFp = query["fp"]?.lowercase() ?: return null
    val publicTls = rawFp == "public"
    val fingerprint = if (publicTls) {
        null
    } else {
        rawFp.takeIf { it.length == 64 && it.all { ch -> ch in "0123456789abcdef" } } ?: return null
    }
    val code = query["code"]?.takeIf(String::isNotBlank) ?: return null
    val defaultPort = if (publicTls) 443 else 19870
    return ConnectionString(host, if (uri.port > 0) uri.port else defaultPort, fingerprint, code)
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
