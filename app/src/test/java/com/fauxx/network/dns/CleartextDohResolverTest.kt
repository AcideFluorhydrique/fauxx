package com.fauxx.network.dns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CleartextDohResolverTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val answer = byteArrayOf(10, 0, 0, 7)

    private enum class Framing { CONTENT_LENGTH, CHUNKED, CLOSE }

    /**
     * An RFC 8484 server on loopback: reads a POST, answers A questions with [answer] (or [rcode]),
     * AAAA with no records. Records each request head for the assertions.
     */
    private inner class FakeDohServer(
        val status: Int = 200,
        val contentType: String = "application/dns-message",
        val framing: Framing = Framing.CONTENT_LENGTH,
        val rcode: Int = 0,
    ) {
        val server = ServerSocket(0, 50, loopback)
        val port: Int get() = server.localPort
        val heads = CopyOnWriteArrayList<String>()
        val connections = AtomicInteger(0)

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) { client.use { serve(it) } }
                }
            }
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream()
            val head = readHead(input)
            heads += head
            val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)!!.groupValues[1].toInt()
            val query = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
            val body = reply(query)
            val out = client.getOutputStream()
            val headers = StringBuilder("HTTP/1.1 $status X\r\nContent-Type: $contentType\r\n")
            when (framing) {
                Framing.CONTENT_LENGTH -> headers.append("Content-Length: ${body.size}\r\n\r\n")
                Framing.CHUNKED -> headers.append("Transfer-Encoding: chunked\r\n\r\n")
                Framing.CLOSE -> headers.append("Connection: close\r\n\r\n")
            }
            out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            if (framing == Framing.CHUNKED) {
                val half = body.size / 2
                for (part in listOf(body.copyOfRange(0, half), body.copyOfRange(half, body.size))) {
                    out.write("${part.size.toString(16)}\r\n".toByteArray()); out.write(part); out.write("\r\n".toByteArray())
                }
                out.write("0\r\n\r\n".toByteArray())
            } else {
                out.write(body)
            }
            out.flush()
        }

        private fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (!out.toString().endsWith("\r\n\r\n")) out.write(input.read())
            return out.toString()
        }

        /** The query echoed back as a response: QR set, [rcode], one A answer for A questions. */
        private fun reply(query: ByteArray): ByteArray {
            var i = 12
            while (query[i].toInt() != 0) i += (query[i].toInt() and 0xFF) + 1
            val qtype = ((query[i + 1].toInt() and 0xFF) shl 8) or (query[i + 2].toInt() and 0xFF)
            val question = query.copyOfRange(12, i + 5)
            val withAnswer = qtype == 1 && rcode == 0
            val out = ByteArrayOutputStream()
            out.write(query, 0, 2)
            out.write(byteArrayOf(0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, if (withAnswer) 1 else 0, 0, 0, 0, 0))
            out.write(question)
            if (withAnswer) {
                out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
                out.write(answer)
            }
            return out.toByteArray()
        }

        fun close() = server.close()
    }

    private val servers = mutableListOf<FakeDohServer>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun server(
        status: Int = 200,
        contentType: String = "application/dns-message",
        framing: Framing = Framing.CONTENT_LENGTH,
        rcode: Int = 0,
    ) = FakeDohServer(status, contentType, framing, rcode).also { servers += it }

    private fun resolverFor(s: FakeDohServer, path: String = "/dns-query") =
        CleartextDohResolver("http://127.0.0.1:${s.port}$path", deadlineMs = 2_000)

    private val expected = Resolution.Addresses(listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))))

    @Test
    fun `an answer with a Content-Length comes back as addresses`() {
        assertEquals(expected, resolverFor(server()).resolve("example.com"))
    }

    @Test
    fun `chunked and read-to-close answers work too`() {
        assertEquals(expected, resolverFor(server(framing = Framing.CHUNKED)).resolve("example.com"))
        assertEquals(expected, resolverFor(server(framing = Framing.CLOSE)).resolve("example.com"))
    }

    @Test
    fun `the request is an RFC 8484 POST to the URL's path and query`() {
        val s = server()
        resolverFor(s, path = "/dns-query?profile=1").resolve("example.com")
        val head = s.heads.first()
        assertTrue(head, head.startsWith("POST /dns-query?profile=1 HTTP/1.1\r\n"))
        assertTrue(head, head.contains("Content-Type: application/dns-message\r\n"))
        assertTrue(head, head.contains("Host: 127.0.0.1:${s.port}\r\n"))
    }

    @Test
    fun `NXDOMAIN is the server's answer, not a failure`() {
        assertEquals(Resolution.NoSuchHost, resolverFor(server(rcode = 3)).resolve("nope.example.com"))
    }

    @Test
    fun `an HTTP error or a body that is not a DNS message is a failure`() {
        assertTrue(resolverFor(server(status = 500)).resolve("example.com") is Resolution.Failure)
        assertTrue(resolverFor(server(contentType = "text/html")).resolve("example.com") is Resolution.Failure)
    }

    @Test
    fun `a server that is not there is a failure`() {
        val port = ServerSocket(0, 1, loopback).use { it.localPort } // closed again: nothing listens
        val r = CleartextDohResolver("http://127.0.0.1:$port/dns-query", deadlineMs = 1_000).resolve("example.com")
        assertTrue(r is Resolution.Failure)
    }

    @Test
    fun `a name that resolves to a public address is never sent a query`() {
        val s = server()
        val publicAnswer = HostResolver { Resolution.Addresses(listOf(InetAddress.getByName("9.9.9.9"))) }
        val r = CleartextDohResolver("http://dns.lan:${s.port}/dns-query", endpointResolver = publicAnswer, deadlineMs = 1_000)
            .resolve("example.com")
        assertTrue(r is Resolution.Failure)
        assertEquals(0, s.connections.get())
    }

    @Test
    fun `a private name is resolved, then queried at its private address`() {
        val s = server()
        val local = HostResolver { Resolution.Addresses(listOf(loopback)) }
        val r = CleartextDohResolver("http://dns.lan:${s.port}/dns-query", endpointResolver = local, deadlineMs = 2_000)
            .resolve("example.com")
        assertEquals(expected, r)
        assertTrue(s.heads.first().contains("Host: dns.lan:${s.port}\r\n"))
    }

    @Test
    fun `the server IP is used instead of looking the name up`() {
        val s = server()
        var looked = false
        val r = CleartextDohResolver(
            "http://dns.lan:${s.port}/dns-query",
            serverAddress = loopback,
            endpointResolver = HostResolver { looked = true; Resolution.NoSuchHost },
            deadlineMs = 2_000,
        ).resolve("example.com")
        assertEquals(expected, r)
        assertEquals(false, looked)
    }

    @Test
    fun `a chunk size near Int MAX_VALUE is refused, not allocated`() {
        // The size check used to ADD the chunk size to the bytes read so far, which wraps negative
        // for 0x7fffffff and then tried to allocate a 2 GB buffer.
        val server = ServerSocket(0, 50, loopback)
        try {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        client.use {
                            val input = it.getInputStream()
                            val head = ByteArrayOutputStream()
                            while (!head.toString().endsWith("\r\n\r\n")) head.write(input.read())
                            it.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\n" +
                                    "Transfer-Encoding: chunked\r\n\r\n1\r\nA\r\n7fffffff\r\n").toByteArray(),
                            )
                            it.getOutputStream().flush()
                            Thread.sleep(2_000)
                        }
                    }
                }
            }
            val r = CleartextDohResolver("http://127.0.0.1:${server.localPort}/dns-query", deadlineMs = 1_000).resolve("example.com")
            // Refused by the size check (an IOException), not an OutOfMemoryError surfacing
            // through the executor as an ExecutionException.
            val cause = (r as Resolution.Failure).cause
            assertTrue("$cause", cause is java.io.IOException && cause.message!!.contains("too large"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `forgetting the endpoint makes the next lookup resolve the server's name again`() {
        val s = server()
        val lookups = AtomicInteger(0)
        val local = HostResolver { lookups.incrementAndGet(); Resolution.Addresses(listOf(loopback)) }
        val r = CleartextDohResolver("http://dns.lan:${s.port}/dns-query", endpointResolver = local, deadlineMs = 2_000)
        r.resolve("example.com")
        val afterFirst = lookups.get()
        r.resolve("example.org")
        assertEquals("cached within one network", afterFirst, lookups.get())
        r.forgetEndpoint() // the network changed
        r.resolve("example.net")
        assertTrue(lookups.get() > afterFirst)
    }

    @Test
    fun `with no endpoint resolver (strict mode) a name without a server IP fails`() {
        val s = server()
        val r = CleartextDohResolver("http://dns.lan:${s.port}/dns-query", endpointResolver = null, deadlineMs = 1_000)
            .resolve("example.com")
        assertTrue(r is Resolution.Failure)
        assertEquals(0, s.connections.get())
    }
}
