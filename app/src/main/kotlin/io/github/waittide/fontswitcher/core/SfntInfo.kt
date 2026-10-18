package io.github.waittide.fontswitcher.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 最小 SFNT (TrueType/OpenType) 头信息解析，用于两件事：
 *  1. 在把这个文件写进系统字体槽之前校验它是真字体（拒绝坏文件/wrong file pick）；
 *  2. 从 name 表里读出人类可读的族名，展示在界面上。
 *
 * 只依赖 java.nio，不引入第三方库。
 */
data class SfntInfo(
    val version: String,
    val familyName: String?,
    val fullName: String?,
    val postScriptName: String?,
    val numGlyphs: Int?,
    val isCollection: Boolean,
    val fileSize: Long,
) {
    /** 中文/等宽等附加判断，仅供展示 */
    val summary: String
        get() = buildString {
            append(familyName ?: fullName ?: "未知字体")
            if (isCollection) append("  [TTC 集合]")
            append("  ·  ").append(version)
            numGlyphs?.let { append("  ·  ").append(it).append(" 字形") }
            append("  ·  ").append(formatSize(fileSize))
        }

    companion object {
        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        private const val TAG_TRUETYPE = 0x00010000
        private const val TAG_TRUE = 0x74727565 // 'true'
        private const val TAG_OTTO = 0x4F54544F // 'OTTO'
        private const val TAG_TTCF = 0x74746366 // 'ttcf'

        /** 只读头 4 字节判断是不是 SFNT 家族的文件。 */
        fun looksLikeFont(file: File): Boolean {
            if (!file.isFile || file.length() < 12) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val tag = raf.readInt()
                    tag == TAG_TRUETYPE || tag == TAG_TRUE || tag == TAG_OTTO || tag == TAG_TTCF
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun parse(file: File): SfntInfo? {
            if (!looksLikeFont(file)) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val tag = raf.readInt()
                    val isCollection = tag == TAG_TTCF
                    var fontOffset = 0L
                    if (isCollection) {
                        raf.seek(8)
                        val numFonts = raf.readInt()
                        if (numFonts <= 0) return null
                        // 第一个子字体的绝对偏移
                        fontOffset = raf.readInt().toLong() and 0xFFFFFFFFL
                    }
                    readTable(raf, fontOffset, tag, file.length(), isCollection)
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun readTable(
            raf: RandomAccessFile,
            fontOffset: Long,
            tag: Int,
            fileSize: Long,
            isCollection: Boolean,
        ): SfntInfo? {
            raf.seek(fontOffset)
            val sfntVersion = raf.readInt()
            val versionName = when (sfntVersion) {
                TAG_TRUETYPE, TAG_TRUE -> "TrueType"
                TAG_OTTO -> "OpenType/CFF"
                else -> "未知 0x" + Integer.toHexString(sfntVersion)
            }
            raf.readUnsignedShort() // numTables
            raf.readUnsignedShort() // searchRange
            raf.readUnsignedShort() // entrySelector
            raf.readUnsignedShort() // rangeShift
            val numTables = readU16At(raf, fontOffset + 4)
            if (numTables <= 0 || numTables > 512) return null

            var nameOffset = -1L
            var nameLength = 0L
            var numGlyphs: Int? = null

            for (i in 0 until numTables) {
                val recOff = fontOffset + 12 + i.toLong() * 16
                raf.seek(recOff)
                val tableTag = raf.readInt()
                raf.readInt() // checksum
                val off = raf.readInt().toLong() and 0xFFFFFFFFL
                val len = raf.readInt().toLong() and 0xFFFFFFFFL
                if (off < 0 || len <= 0 || off + len > fileSize + 16) continue
                when (tableTag) {
                    0x6E616D65 -> { // 'name'
                        nameOffset = off
                        nameLength = len
                    }
                    0x6D617870 -> { // 'maxp'
                        raf.seek(off + 4)
                        numGlyphs = raf.readUnsignedShort()
                    }
                }
            }

            var family: String? = null
            var full: String? = null
            var ps: String? = null
            if (nameOffset >= 0 && nameLength > 6) {
                val names = readNames(raf, nameOffset, nameLength)
                family = names[1] ?: names[16]
                full = names[4]
                ps = names[6]
            }

            return SfntInfo(
                version = versionName,
                familyName = family,
                fullName = full,
                postScriptName = ps,
                numGlyphs = numGlyphs,
                isCollection = isCollection,
                fileSize = fileSize,
            )
        }

        private fun readU16At(raf: RandomAccessFile, pos: Long): Int {
            raf.seek(pos)
            return raf.readUnsignedShort()
        }

        /** 解析 name 表，优先返回 Windows(3)/Unicode 平台下的英文或 UTF-16 记录。 */
        private fun readNames(raf: RandomAccessFile, off: Long, len: Long): Map<Int, String> {
            val out = HashMap<Int, String>()
            val bytes = ByteArray(len.toInt().coerceAtMost(4 * 1024 * 1024))
            raf.seek(off)
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (bytes.size < 6) return out
            val count = buf.getShort(2).toInt() and 0xFFFF
            val stringOffset = buf.getShort(4).toInt() and 0xFFFF
            if (count <= 0 || count > 2048) return out
            // 先收集所有候选，按平台优先级排序
            data class Cand(val id: Int, val score: Int, val text: String)
            val cands = ArrayList<Cand>()
            for (i in 0 until count) {
                val rec = 6 + i * 12
                if (rec + 12 > bytes.size) break
                val platformId = buf.getShort(rec).toInt() and 0xFFFF
                val encodingId = buf.getShort(rec + 2).toInt() and 0xFFFF
                val languageId = buf.getShort(rec + 4).toInt() and 0xFFFF
                val nameId = buf.getShort(rec + 6).toInt() and 0xFFFF
                val strLen = buf.getShort(rec + 8).toInt() and 0xFFFF
                val strOff = buf.getShort(rec + 10).toInt() and 0xFFFF
                val start = stringOffset + strOff
                if (start < 0 || start + strLen > bytes.size || strLen <= 0) continue
                if (nameId !in intArrayOf(1, 4, 6, 16)) continue
                val score = when {
                    platformId == 3 && encodingId == 1 && languageId == 0x0409 -> 100 // Windows en-US
                    platformId == 3 && encodingId == 1 -> 90                           // Windows UTF-16
                    platformId == 3 && encodingId == 10 -> 85                          // Windows UCS-4
                    platformId == 0 -> 80                                              // Unicode
                    platformId == 1 && languageId == 0 -> 60                            // Mac Roman English
                    platformId == 1 -> 50
                    else -> 10
                }
                val text = try {
                    if (platformId == 1) {
                        String(bytes, start, strLen, Charsets.ISO_8859_1)
                    } else {
                        String(bytes, start, strLen, Charsets.UTF_16BE)
                    }
                } catch (_: Throwable) {
                    continue
                }.replace("\u0000", "").trim()
                if (text.isEmpty()) continue
                cands += Cand(nameId, score, text)
            }
            for (c in cands.sortedByDescending { it.score }) {
                out.putIfAbsent(c.id, c.text)
            }
            return out
        }
    }
}
