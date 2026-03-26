package org.cygnusx1.openbu.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class BambuFtpsClient(
    private val ip: String,
    private val accessCode: String,
    private val port: Int = 990,
) {
    private var controlSocket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    @Volatile
    private var activeDataSocket: SSLSocket? = null

    private val sslContext: SSLContext by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
    }

    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(ip, port), 10_000)
        rawSocket.soTimeout = 30_000

        Log.d(TAG, "Creating SSL socket to $ip:$port")
        val ssl = sslContext.socketFactory.createSocket(rawSocket, ip, port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = null }
        Log.d(TAG, "Enabled protocols: ${ssl.enabledProtocols.joinToString()}")
        Log.d(TAG, "Enabled cipher suites: ${ssl.enabledCipherSuites.joinToString()}")
        Log.d(TAG, "Starting TLS handshake...")
        ssl.startHandshake()
        val session = ssl.session
        Log.d(TAG, "TLS handshake complete: protocol=${session.protocol}, cipher=${session.cipherSuite}")

        controlSocket = ssl
        reader = BufferedReader(InputStreamReader(ssl.inputStream))
        writer = ssl.outputStream

        readResponse() // welcome banner

        sendCommand("USER bblp")
        val userResp = readResponse()
        if (userResp.code == 331 || userResp.code == 230) {
            if (userResp.code == 331) {
                sendCommand("PASS $accessCode")
                val passResp = readResponse()
                if (passResp.code != 230) throw IOException("FTP login failed: ${passResp.text}")
            }
        } else {
            throw IOException("FTP USER failed: ${userResp.text}")
        }

        sendCommand("TYPE I")
        readResponse()
    }

    suspend fun listDirectory(path: String): List<FtpFileEntry> = withContext(Dispatchers.IO) {
        sendCommand("CWD $path")
        val cwdResp = readResponse()
        if (cwdResp.code != 250) throw IOException("CWD failed: ${cwdResp.text}")

        val dataSocket = openDataConnection()
        sendCommand("LIST")
        val listResp = readResponse()
        if (listResp.code != 150 && listResp.code != 125) {
            dataSocket.close()
            throw IOException("LIST failed: ${listResp.text}")
        }

        val dataReader = BufferedReader(InputStreamReader(dataSocket.inputStream))
        val lines = mutableListOf<String>()
        var line = dataReader.readLine()
        while (line != null) {
            lines.add(line)
            line = dataReader.readLine()
        }
        dataSocket.close()

        readResponse() // 226 Transfer complete

        lines.mapNotNull { parseFtpListLine(it) }
    }

    suspend fun downloadFile(
        remotePath: String,
        output: OutputStream,
        totalSize: Long = -1,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val dataSocket = openDataConnection()
        activeDataSocket = dataSocket
        try {
            sendCommand("RETR $remotePath")
            val resp = readResponse()
            if (resp.code != 150 && resp.code != 125) {
                dataSocket.close()
                throw IOException("RETR failed: ${resp.text}")
            }

            val input = dataSocket.inputStream
            val buf = ByteArray(8192)
            var totalRead = 0L
            var n = input.read(buf)
            while (n != -1) {
                output.write(buf, 0, n)
                totalRead += n
                onProgress?.invoke(totalRead, totalSize)
                n = input.read(buf)
            }
            dataSocket.close()
            output.flush()

            readResponse() // 226 Transfer complete
        } finally {
            activeDataSocket = null
        }
    }

    suspend fun uploadFile(
        remotePath: String,
        input: InputStream,
        totalSize: Long = -1,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val dataSocket = openDataConnection()
        activeDataSocket = dataSocket
        try {
            sendCommand("STOR $remotePath")
            val resp = readResponse()
            if (resp.code != 150 && resp.code != 125) {
                dataSocket.close()
                throw IOException("STOR failed: ${resp.text}")
            }

            val output = dataSocket.outputStream
            val buf = ByteArray(8192)
            var written = 0L
            var n = input.read(buf)
            while (n != -1) {
                output.write(buf, 0, n)
                written += n
                onProgress?.invoke(written, totalSize)
                n = input.read(buf)
            }
            output.flush()
            dataSocket.close()

            readResponse() // 226 Transfer complete
        } finally {
            activeDataSocket = null
        }
    }

    suspend fun deleteFile(remotePath: String): Unit = withContext(Dispatchers.IO) {
        sendCommand("DELE $remotePath")
        val resp = readResponse()
        if (resp.code != 250) throw IOException("DELE failed: ${resp.text}")
    }

    fun cancelTransfer() {
        try { activeDataSocket?.close() } catch (_: Exception) {}
        activeDataSocket = null
    }

    suspend fun pwd(): String = withContext(Dispatchers.IO) {
        sendCommand("PWD")
        val resp = readResponse()
        // Extract path from response like: 257 "/" is the current directory
        val match = Regex("\"(.+?)\"").find(resp.text)
        match?.groupValues?.get(1) ?: "/"
    }

    fun close() {
        try {
            writer?.let {
                it.write("QUIT\r\n".toByteArray())
                it.flush()
            }
        } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        reader = null
        writer = null
    }

    private fun sendCommand(cmd: String) {
        Log.d(TAG, ">>> $cmd")
        val w = writer ?: throw IOException("Not connected")
        w.write("$cmd\r\n".toByteArray())
        w.flush()
    }

    private fun readResponse(): FtpResponse {
        val r = reader ?: throw IOException("Not connected")
        val firstLine = r.readLine() ?: throw IOException("Connection closed")
        Log.d(TAG, "<<< $firstLine")

        val code = firstLine.take(3).toIntOrNull() ?: throw IOException("Invalid FTP response: $firstLine")
        val sb = StringBuilder(firstLine)

        // Multi-line response: first line has "code-", continues until "code "
        if (firstLine.length > 3 && firstLine[3] == '-') {
            while (true) {
                val next = r.readLine() ?: break
                Log.d(TAG, "<<< $next")
                sb.appendLine().append(next)
                if (next.length >= 3 && next.take(3).toIntOrNull() == code && (next.length == 3 || next[3] == ' ')) {
                    break
                }
            }
        }

        return FtpResponse(code, sb.toString())
    }

    private fun openDataConnection(): SSLSocket {
        sendCommand("PASV")
        val resp = readResponse()
        if (resp.code != 227) throw IOException("PASV failed: ${resp.text}")

        // Parse: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        val match = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(resp.text)
            ?: throw IOException("Cannot parse PASV response: ${resp.text}")
        val parts = match.groupValues.drop(1).map { it.toInt() }
        val dataHost = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
        val dataPort = parts[4] * 256 + parts[5]

        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(dataHost, dataPort), 10_000)
        rawSocket.soTimeout = 30_000

        // Implicit FTPS: data channel must also be SSL
        val ssl = sslContext.socketFactory.createSocket(rawSocket, dataHost, dataPort, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = null }
        ssl.startHandshake()
        return ssl
    }

    private fun parseFtpListLine(line: String): FtpFileEntry? {
        // Unix-style: drwxr-xr-x 2 user group 4096 Jan  1 12:00 dirname
        // Or: -rw-r--r-- 1 user group 12345 Jan  1 12:00 filename
        if (line.isBlank()) return null
        val isDir = line.startsWith('d')
        val parts = line.split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val size = parts[4].toLongOrNull() ?: 0
        val date = "${parts[5]} ${parts[6]} ${parts[7]}"
        val name = parts[8]
        if (name == "." || name == "..") return null
        return FtpFileEntry(name = name, size = size, lastModified = date, isDirectory = isDir)
    }

    private data class FtpResponse(val code: Int, val text: String)

    companion object {
        private const val TAG = "BambuFtps"
    }
}
