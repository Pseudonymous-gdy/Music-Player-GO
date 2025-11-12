package com.iven.musicplayergo.utils

import android.content.Context
import android.net.Uri
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 将 filePaths（支持 file 路径 或 content:// URI 字符串）复制到 app cache 并压缩成 zip 文件。
 * 返回生成的 zip File（位于 context.cacheDir）。
 */
@Throws(IOException::class)
fun createZipFromFiles(context: Context, filePaths: List<String>, zipName: String = "music_batch.zip"): File {
    val zipFile = File(context.cacheDir, zipName)
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        filePaths.forEachIndexed { idx, path ->
            val inputStream = getInputStreamForPath(context, path)
            val entryName = "song_${idx}_" + File(path).name
            zos.putNextEntry(ZipEntry(entryName))
            inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    zos.write(buffer, 0, read)
                }
            }
            zos.closeEntry()
        }
    }
    return zipFile
}

/** 支持 content:// 或 file:// URI，也支持普通文件路径，返回 InputStream */
@Throws(FileNotFoundException::class)
fun getInputStreamForPath(context: Context, path: String): InputStream {
    return if (path.startsWith("content://") || path.startsWith("file://")) {
        val uri = Uri.parse(path)
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $path")
    } else {
        FileInputStream(File(path))
    }
}