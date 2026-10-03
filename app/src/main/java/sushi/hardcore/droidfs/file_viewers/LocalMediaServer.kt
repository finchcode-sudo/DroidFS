package sushi.hardcore.droidfs.file_viewers

import sushi.hardcore.droidfs.filesystems.EncryptedVolume
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * mpv 是原生进程，不能直接读取 Kotlin 里的 EncryptedVolume 解密接口。
 * 这里在本机（127.0.0.1）起一个只监听本地回环地址的最小 HTTP 服务器，
 * 把加密卷里的文件解密后通过 HTTP Range 请求喂给 mpv。
 * mpv/ffmpeg 的 http 协议本身支持 Range，这样也顺带解决了 TS 文件无法拖动、
 * 没有总时长的问题（跟直接访问一个允许 Range 的 HTTP 视频链接效果一样）。
 *
 * 注意：这个服务器只监听 127.0.0.1，外部设备/App 访问不到。
 */
class LocalMediaServer(
    private val encryptedVolume: EncryptedVolume,
    private val filePath: String
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var running = false

    /** 启动服务器，返回可以交给 mpv 的 URL，例如 http://127.0.0.1:12345/video */
    fun start(): String {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running = true
        executor.execute {
            while (running) {
                try {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/video"
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
        executor.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val fileSize = encryptedVolume.getAttr(filePath)?.size ?: run {
                writeSimpleResponse(client.getOutputStream(), 404, "Not Found")
                return
            }

            var start = 0L
            var end = fileSize - 1
            var isPartial = false
            val rangeHeader = headers["range"]
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isPartial = true
                val range = rangeHeader.removePrefix("bytes=").split("-")
                if (range.isNotEmpty() && range[0].isNotEmpty()) start = range[0].toLong()
                if (range.size > 1 && range[1].isNotEmpty()) end = range[1].toLong()
                if (end >= fileSize) end = fileSize - 1
                if (start > end) {
                    writeSimpleResponse(client.getOutputStream(), 416, "Range Not Satisfiable")
                    return
                }
            }

            val contentLength = end - start + 1
            val out = BufferedOutputStream(client.getOutputStream())
            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            out.write((statusLine + "\r\n").toByteArray())
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Content-Type: video/mp2t\r\n".toByteArray())
            out.write("Content-Length: $contentLength\r\n".toByteArray())
            if (isPartial) {
                out.write("Content-Range: bytes $start-$end/$fileSize\r\n".toByteArray())
            }
            out.write("Connection: close\r\n".toByteArray())
            out.write("\r\n".toByteArray())

            if (!requestLine.startsWith("HEAD")) {
                streamRange(out, start, contentLength)
            }
            out.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { client.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun streamRange(out: OutputStream, start: Long, length: Long) {
        val fileHandle = encryptedVolume.openFileReadMode(filePath)
        if (fileHandle == -1L) return
        try {
            val bufferSize = 256 * 1024
            val buffer = ByteArray(bufferSize)
            var offset = start
            var remaining = length
            while (remaining > 0) {
                val toReadInt = min(bufferSize.toLong(), remaining).toInt()
                // 参数类型按 EncryptedVolumeDataSource.kt 里已确认能跑的调用方式来传 (都是 Long)
                val read = encryptedVolume.read(fileHandle, offset, buffer, 0L, toReadInt.toLong())
                // 不确定 read() 声明的返回类型是 Int 还是 Long, 两者都有 toInt(), 统一转成 Int 更安全
                val readInt = read.toInt()
                if (readInt <= 0) break
                out.write(buffer, 0, readInt)
                offset += readInt
                remaining -= readInt
            }
        } finally {
            encryptedVolume.closeFile(fileHandle)
        }
    }

    private fun writeSimpleResponse(out: OutputStream, code: Int, message: String) {
        out.write("HTTP/1.1 $code $message\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prevCr = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prevCr && sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            prevCr = b == '\r'.code
            sb.append(b.toChar())
        }
    }
}
