package sushi.hardcore.droidfs.file_operations

import java.security.MessageDigest

/**
 * 按内容查找重复文件: 先按大小分组, 再比较开头 [HEAD_SIZE] 字节的哈希, 最后比较整个文件的 SHA-256。
 * 不依赖 Android。
 */
object DuplicateFinder {
    const val HEAD_SIZE = 64 * 1024L

    class Entry<T>(val item: T, val path: String, val size: Long)

    class Result<T>(
        /** 每组至少两个内容相同的文件, 按可节省空间从大到小排列 */
        val groups: List<List<Entry<T>>>,
        val unreadable: List<String>,
    )

    /**
     * @param files 路径不能重复, 否则同一个文件会被当成它自己的副本
     * @param hash 返回文件前 length 字节的哈希, 没能完整读出这么多字节时返回 null。
     * onBytes 的参数是这个文件目前已读的字节数。
     * @param onStage 每个阶段开始时调用
     * @param onFile 每读一个文件前调用
     */
    suspend fun <T> find(
        files: List<Entry<T>>,
        hash: suspend (entry: Entry<T>, length: Long, onBytes: (Long) -> Unit) -> ByteArray?,
        onStage: (fileCount: Int, totalBytes: Long) -> Unit = { _, _ -> },
        onFile: (path: String) -> Unit = {},
        onBytes: (stageBytes: Long) -> Unit = {},
    ): Result<T> {
        val unreadable = ArrayList<String>()

        suspend fun refine(groups: List<List<Entry<T>>>, length: (Entry<T>) -> Long): List<List<Entry<T>>> {
            onStage(groups.sumOf { it.size }, groups.sumOf { group -> group.sumOf(length) })
            var done = 0L
            val result = ArrayList<List<Entry<T>>>()
            for (group in groups) {
                val byHash = LinkedHashMap<String, MutableList<Entry<T>>>()
                for (entry in group) {
                    onFile(entry.path)
                    val len = length(entry)
                    val digest = hash(entry, len) { onBytes(done + it) }
                    done += len
                    onBytes(done)
                    if (digest == null) {
                        unreadable.add(entry.path)
                    } else {
                        byHash.getOrPut(digest.joinToString("") { "%02x".format(it) }) { ArrayList() }.add(entry)
                    }
                }
                byHash.values.filterTo(result) { it.size > 1 }
            }
            return result
        }

        // 空文件没有比较的意义
        val sameSize = files.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        val sameHead = refine(sameSize) { minOf(it.size, HEAD_SIZE) }
        // 不超过 HEAD_SIZE 的文件, 上一步已经比较了全部内容
        val (small, large) = sameHead.partition { it[0].size <= HEAD_SIZE }
        val groups = small + refine(large) { it.size }
        return Result(groups.sortedByDescending { it[0].size * (it.size - 1) }, unreadable)
    }

    /** @param read 从 offset 处读最多 length 字节到 buffer 开头, 返回读到的字节数, <=0 表示读不出来了 */
    suspend fun sha256(
        length: Long,
        buffer: ByteArray,
        read: (offset: Long, length: Int) -> Int,
        onBytes: suspend (Long) -> Unit,
    ): ByteArray? {
        val md = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < length) {
            val n = read(offset, minOf(buffer.size.toLong(), length - offset).toInt())
            if (n <= 0) return null
            md.update(buffer, 0, n)
            offset += n
            onBytes(offset)
        }
        return md.digest()
    }
}
