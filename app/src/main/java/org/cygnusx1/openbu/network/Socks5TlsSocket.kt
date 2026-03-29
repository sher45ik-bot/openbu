package org.cygnusx1.openbu.network

import org.cygnusx1.openbu.data.ProxyConfig
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Creates a TCP socket tunneled through a SOCKS5-over-TLS proxy.
 *
 * Java's built-in Proxy(Proxy.Type.SOCKS, ...) does NOT support TLS to the proxy,
 * so we manually implement the SOCKS5 handshake (RFC 1928 + RFC 1929) over an SSLSocket.
 */
object Socks5TlsSocket {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Connects to [targetHost]:[targetPort] through the SOCKS5+TLS proxy described by [proxy].
     * Returns a socket whose input/output streams are tunneled to the target.
     * The caller can layer additional TLS on top for the target's own encryption.
     */
    fun connect(
        proxy: ProxyConfig,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 10_000,
    ): Socket {
        // 1. TLS connection to the GOST proxy
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllManager), null)
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(proxy.host, proxy.port), connectTimeoutMs)
        val tlsSocket = sslContext.socketFactory.createSocket(
            rawSocket, proxy.host, proxy.port, true
        ) as SSLSocket
        tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = null
        }
        tlsSocket.startHandshake()

        val out = tlsSocket.outputStream
        val inp = tlsSocket.inputStream

        // 2. SOCKS5 greeting (RFC 1928): offer username/password auth (method 0x02)
        out.write(byteArrayOf(0x05, 0x01, 0x02))
        out.flush()

        val greeting = readExactly(inp, 2)
        if (greeting[0] != 0x05.toByte()) {
            throw IOException("Not a SOCKS5 proxy (version=${greeting[0]})")
        }
        if (greeting[1] != 0x02.toByte()) {
            throw IOException("Proxy did not accept username/password auth (method=${greeting[1]})")
        }

        // 3. Username/password sub-negotiation (RFC 1929)
        val userBytes = proxy.username.toByteArray(Charsets.UTF_8)
        val passBytes = proxy.password.toByteArray(Charsets.UTF_8)
        val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
        authPacket[0] = 0x01 // sub-negotiation version
        authPacket[1] = userBytes.size.toByte()
        System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
        authPacket[2 + userBytes.size] = passBytes.size.toByte()
        System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
        out.write(authPacket)
        out.flush()

        val authResp = readExactly(inp, 2)
        if (authResp[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 authentication failed (status=${authResp[1]})")
        }

        // 4. CONNECT request
        val ipBytes = InetAddress.getByName(targetHost).address
        val addrType: Byte = if (ipBytes.size == 4) 0x01 else 0x04 // IPv4 or IPv6
        val connectPacket = ByteArray(6 + ipBytes.size)
        connectPacket[0] = 0x05 // version
        connectPacket[1] = 0x01 // CONNECT command
        connectPacket[2] = 0x00 // reserved
        connectPacket[3] = addrType
        System.arraycopy(ipBytes, 0, connectPacket, 4, ipBytes.size)
        connectPacket[4 + ipBytes.size] = (targetPort shr 8).toByte()
        connectPacket[5 + ipBytes.size] = targetPort.toByte()
        out.write(connectPacket)
        out.flush()

        // Read CONNECT response: 4 bytes header, then address (variable), then 2 bytes port
        val connHeader = readExactly(inp, 4)
        if (connHeader[1] != 0x00.toByte()) {
            val errorMsg = when (connHeader[1].toInt() and 0xFF) {
                1 -> "general SOCKS server failure"
                2 -> "connection not allowed by ruleset"
                3 -> "network unreachable"
                4 -> "host unreachable"
                5 -> "connection refused"
                6 -> "TTL expired"
                7 -> "command not supported"
                8 -> "address type not supported"
                else -> "unknown error ${connHeader[1]}"
            }
            throw IOException("SOCKS5 CONNECT failed: $errorMsg")
        }
        // Consume the bound address + port from the response
        when (connHeader[3].toInt() and 0xFF) {
            0x01 -> readExactly(inp, 4 + 2) // IPv4 + port
            0x04 -> readExactly(inp, 16 + 2) // IPv6 + port
            0x03 -> { // domain name
                val domainLen = readExactly(inp, 1)[0].toInt() and 0xFF
                readExactly(inp, domainLen + 2)
            }
        }

        return tlsSocket // now tunneled to targetHost:targetPort
    }

    private fun readExactly(input: java.io.InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) throw IOException("SOCKS5 proxy closed connection unexpectedly")
            offset += n
        }
        return buf
    }
}
