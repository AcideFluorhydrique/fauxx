package com.fauxx.network.dns

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS without the S, for a resolver on the user's own network (#334): a self-hosted
 * `doh-server` behind their VPN, say, where a certificate is more trouble than it is worth.
 *
 * Why not OkHttp, like [DohHostResolver]: the app's network security config forbids cleartext
 * (`cleartextTrafficPermitted="false"`), and OkHttp honours it on Android, so an `http://` URL
 * fails before a byte is sent, which the fail-open breaker would then quietly route to the system
 * resolver. That config cannot be relaxed: it is also what keeps the WebView's plain-http requests
 * off the CONNECT-only loopback proxy. A network security config cannot name an address range
 * either, only fixed domains. So this is a minimal RFC 8484 client on a plain socket, which the
 * config does not govern: one `POST` of an `application/dns-message` per query, HTTP/1.1,
 * `Connection: close`.
 *
 * Only ever to a private address. The URL was validated as private ([DohPresets.isValidCustomUrl]),
 * but a name is not proof, so every address it resolves to is checked again with
 * [AddressScope.isPrivateNetwork] and anything public is refused. Sending the browsing list in the
 * clear across the internet is exactly what this setting must never do.
 *
 * Reaching the server: [serverAddress] (the user's "server IP") when given, else the URL's host if
 * it is an IP literal, else the host resolved through [endpointResolver]. That is the system
 * resolver normally, since only the local network's DNS knows names like `pihole.lan`, and null in
 * strict mode, where the system resolver is off limits and the user must give an address.
 */
class CleartextDohResolver(
    url: String,
    private val serverAddress: InetAddress? = null,
    private val endpointResolver: HostResolver? = SystemHostResolver,
    deadlineMs: Long = 2_500L,
) : MessageResolver(deadlineMs) {

    private val url: HttpUrl = url.trim().toHttpUrl()

    init {
        require(!this.url.isHttps) { "use DohHostResolver for https" }
    }

    private class Endpoint(val addresses: List<InetAddress>, val expiresAtNanos: Long)

    @Volatile private var endpoint: Endpoint? = null

    /**
     * Drop the cached server address. Called on every network change: a private name, or the
     * private range it resolves into, can mean a different machine on a different network.
     */
    fun forgetEndpoint() {
        endpoint = null
    }

    override fun exchange(question: Question, deadline: Long): DnsMessage {
        // ID 0, as RFC 8484 recommends: it keeps answers cacheable by HTTP caches in between.
        val query = DnsMessage.builder().setQuestion(question).setRecursionDesired(true).setId(0).build()
        val body = query.toArray()
        var lastError: IOException? = null
        for (address in endpointAddresses()) {
            if (remainingMs(deadline) <= 0) break
            try {
                val reply = DnsMessage(post(address, body, deadline))
                if (!matches(query, reply)) throw IOException("DoH reply did not match the query")
                return reply
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: SocketTimeoutException("no time left to reach the DoH server")
    }

    /** Where the server is, every address private (see the class KDoc), cached for a minute. */
    private fun endpointAddresses(): List<InetAddress> {
        endpoint?.let { if (System.nanoTime() < it.expiresAtNanos) return it.addresses }
        val found = when {
            serverAddress != null -> listOf(serverAddress)
            else -> LoopbackProxy.parseIpLiteral(url.host)?.let(::listOf)
                ?: when (val r = endpointResolver?.resolve(url.host)) {
                    is Resolution.Addresses -> r.addresses
                    null -> throw IOException("A plain http:// resolver needs its IP address here")
                    Resolution.NoSuchHost -> throw IOException("DoH server ${url.host} did not resolve")
                    is Resolution.Failure -> throw IOException("DoH server ${url.host} could not be resolved", r.cause)
                }
        }
        val private = found.filter(AddressScope::isPrivateNetwork)
        if (private.isEmpty()) throw IOException("Refusing unencrypted DNS to a public address")
        endpoint = Endpoint(private, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENDPOINT_TTL_MS))
        return private
    }

    private fun post(address: InetAddress, body: ByteArray, deadline: Long): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, url.port), remainingMs(deadline).coerceAtLeast(1))
            val head = buildString {
                append("POST ").append(url.encodedPath)
                url.encodedQuery?.let { append('?').append(it) }
                append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader()).append("\r\n")
                append("Content-Type: application/dns-message\r\n")
                append("Accept: application/dns-message\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
            return readResponse(socket, deadline)
        }
    }

    private fun hostHeader(): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    }

    private fun readResponse(socket: Socket, deadline: Long): ByteArray {
        val input = socket.getInputStream()
        val statusLine = readLine(socket, input, deadline, MAX_HEAD_BYTES)
        val headerLines = mutableListOf<String>()
        var headBytes = statusLine.length
        while (true) {
            val line = readLine(socket, input, deadline, MAX_HEAD_BYTES)
            if (line.isEmpty()) break
            headBytes += line.length
            if (headBytes > MAX_HEAD_BYTES) throw IOException("DoH response head too large")
            headerLines += line
        }
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (!statusLine.startsWith("HTTP/1.") || code == null) throw IOException("Not an HTTP response from the DoH server")
        if (code != 200) throw IOException("DoH server answered HTTP $code")
        val headers = headerLines.mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        headers["content-type"]?.let {
            if (!it.lowercase().startsWith(DNS_MESSAGE)) throw IOException("DoH server sent $it, not a DNS message")
        }
        return when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(socket, input, deadline)
            headers["content-length"] != null -> {
                val length = headers["content-length"]!!.toIntOrNull()?.takeIf { it in 0..MAX_BODY_BYTES }
                    ?: throw IOException("Bad Content-Length from the DoH server")
                readExactly(socket, input, deadline, length)
            }
            else -> readToEnd(socket, input, deadline)
        }
    }

    private fun readChunked(socket: Socket, input: InputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(socket, input, deadline, MAX_CHUNK_LINE_BYTES).substringBefore(';').trim()
            val size = sizeLine.toIntOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("Bad chunk size from the DoH server")
            if (size == 0) return out.toByteArray()
            // Subtract rather than add: a chunk size near Int.MAX_VALUE would overflow the sum.
            if (size > MAX_BODY_BYTES - out.size()) throw IOException("DoH answer too large")
            out.write(readExactly(socket, input, deadline, size))
            readLine(socket, input, deadline, MAX_CHUNK_LINE_BYTES) // the CRLF after each chunk
        }
    }

    private fun readExactly(socket: Socket, input: InputStream, deadline: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = readTimed(socket, deadline) { input.read(buffer, read, length - read) }
            if (n < 0) throw IOException("DoH answer cut short")
            read += n
        }
        return buffer
    }

    private fun readToEnd(socket: Socket, input: InputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val n = readTimed(socket, deadline) { input.read(buffer) }
            if (n < 0) return out.toByteArray()
            if (n > MAX_BODY_BYTES - out.size()) throw IOException("DoH answer too large")
            out.write(buffer, 0, n)
        }
    }

    /** One CRLF-terminated line, without the CRLF. Byte by byte, so nothing past it is consumed. */
    private fun readLine(socket: Socket, input: InputStream, deadline: Long, max: Int): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = readTimed(socket, deadline) { input.read() }
            if (b < 0) throw IOException("DoH response cut short")
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            if (out.size() >= max) throw IOException("DoH response line too long")
            out.write(b)
        }
    }

    /** Every read is bounded by what is left of the lookup's deadline, so a trickling server cannot stall it. */
    private inline fun readTimed(socket: Socket, deadline: Long, read: () -> Int): Int {
        val remaining = remainingMs(deadline)
        if (remaining <= 0) throw SocketTimeoutException("DoH server too slow")
        socket.soTimeout = remaining
        return read()
    }

    private fun remainingMs(deadline: Long): Int =
        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val DNS_MESSAGE = "application/dns-message"
        const val MAX_HEAD_BYTES = 8 * 1024
        const val MAX_CHUNK_LINE_BYTES = 256
        /** A DNS message cannot exceed 65 535 bytes (RFC 8484 section 6). */
        const val MAX_BODY_BYTES = 65_535
        const val ENDPOINT_TTL_MS = 60_000L
    }
}
