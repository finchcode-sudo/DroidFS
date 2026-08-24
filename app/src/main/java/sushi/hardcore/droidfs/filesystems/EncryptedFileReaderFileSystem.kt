package sushi.hardcore.droidfs.filesystems

import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Source
import okio.Timeout

fun unsupported(): Nothing = throw UnsupportedOperationException()

class EncryptedFileReaderFileSystem(private val encryptedVolume: EncryptedVolume) : FileSystem() {

    class EncryptedReadOnlyFileHandle(
        private val encryptedVolume: EncryptedVolume,
        private val path: String,
        private val fileHandle: Long
    ) : FileHandle(false) {
        override fun protectedClose() {
            encryptedVolume.closeFile(fileHandle)
        }

        override fun protectedRead(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int
        ): Int {
            return encryptedVolume.read(
                fileHandle, fileOffset, array, arrayOffset.toLong(),
                byteCount.toLong()
            )
        }

        override fun protectedSize(): Long {
            return (encryptedVolume.getAttr(path) ?: throw RuntimeException("getAttr() failed for $path")).size
        }

        override fun protectedResize(size: Long) = unsupported()
        override fun protectedWrite(
            fileOffset: Long,
            array: ByteArray,
            arrayOffset: Int,
            byteCount: Int
        ) = unsupported()
        override fun protectedFlush() = unsupported()
    }

    class EncryptedFileSource(
        private val encryptedVolume: EncryptedVolume,
        private val fileHandle: Long
    ) : Source {
        private var fileOffset = 0

        override fun close() {
            encryptedVolume.closeFile(fileHandle)
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            val buffer = ByteArray(byteCount.toInt())
            val read = encryptedVolume.read(fileHandle, fileOffset.toLong(), buffer, 0, byteCount)
            if (read <= 0) {
                return -1L // 已经读到文件末尾（或读取出错），必须返回 -1 表示流结束，
                           // 否则调用方会把这次当成"又读到 0 字节"死循环重试
            }
            sink.write(buffer, 0, read) // 只写入真正读到的字节数，不能整个 buffer 都写进去——
                                         // byteCount 通常比实际能读到的字节数大，
                                         // buffer 末尾没读到的部分是全零的垃圾数据，
                                         // 之前整个 buffer 写入会把这些垃圾字节混进图片数据流，
                                         // 导致图片解码失败或花屏
            fileOffset += read
            return read.toLong()
        }

        override fun timeout() = Timeout.NONE
    }

    private fun tryOpenReadOnly(path: String): Long {
        val fileHandle = encryptedVolume.openFileReadMode(path)
        if (fileHandle == -1L) {
            throw RuntimeException("Failed to open {$path} in read-only mode")
        }
        return fileHandle
    }

    override fun canonicalize(path: Path): Path {
        // 加密卷内是扁平的虚拟路径，不存在符号链接/相对路径解析的概念，
        // 只需要确认这个路径真的存在（Okio 约定：路径不存在时要抛 FileNotFoundException）
        metadataOrNull(path) ?: throw java.io.FileNotFoundException("No such file: $path")
        return path
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        val stat = encryptedVolume.getAttr(path.toString()) ?: return null
        return FileMetadata(
            isRegularFile = stat.type == Stat.S_IFREG,
            isDirectory = stat.type == Stat.S_IFDIR,
            size = stat.size,
            lastModifiedAtMillis = stat.mTime
        )
    }

    override fun openReadOnly(file: Path): FileHandle {
        val path = file.toString()
        return EncryptedReadOnlyFileHandle(encryptedVolume, path, tryOpenReadOnly(path))
    }

    override fun source(file: Path): Source {
        return EncryptedFileSource(encryptedVolume, tryOpenReadOnly(file.toString()))
    }

    override fun list(dir: Path) = unsupported()
    override fun listOrNull(dir: Path) = unsupported()
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean) = unsupported()
    override fun sink(file: Path, mustCreate: Boolean) = unsupported()
    override fun appendingSink(file: Path, mustExist: Boolean) = unsupported()
    override fun createDirectory(dir: Path, mustCreate: Boolean) = unsupported()
    override fun createSymlink(source: Path, target: Path) = unsupported()
    override fun delete(path: Path, mustExist: Boolean) = unsupported()
    override fun atomicMove(source: Path, target: Path) = unsupported()
}