package org.cygnusx1.openbu.network

import org.cygnusx1.openbu.data.ProxyConfig
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * An SSLSocketFactory that routes connections through a SOCKS5+TLS proxy,
 * then layers TLS on top for the target (e.g., RTSPS to a Bambu printer).
 *
 * Designed for use with ExoPlayer's RtspMediaSource.Factory.setSocketFactory().
 */
class Socks5TlsSslSocketFactory(
    private val proxyConfig: ProxyConfig,
    private val targetSslContext: SSLContext,
) : SSLSocketFactory() {

    private val delegate: SSLSocketFactory = targetSslContext.socketFactory

    override fun createSocket(host: String, port: Int): Socket {
        val tunnelSocket = Socks5TlsSocket.connect(proxyConfig, host, port)
        return delegate.createSocket(tunnelSocket, host, port, true)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress!!, port)

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port)

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, host, port, autoClose)

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
}
