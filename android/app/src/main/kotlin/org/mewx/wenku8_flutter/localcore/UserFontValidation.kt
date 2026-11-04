package org.mewx.wenku8_flutter

import java.io.File
import java.io.RandomAccessFile

/** Kotlin port of lib/user_fonts.dart::_validateSfnt; same SFNT tables and 32MiB limit. */
internal object UserFontValidation {
    const val MAX_BYTES = 32L * 1024 * 1024
    fun validate(file: File) {
        require(file.length() in 12..MAX_BYTES) { "字体文件需要在 32 MiB 以内，且包含完整文件头" }
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val magic = input.readInt()
            val count = input.readUnsignedShort()
            input.skipBytes(6)
            require(magic in listOf(0x00010000, 0x4f54544f, 0x74727565) && count in 1..4096 && 12L + count * 16 <= size) {
                "不是有效的 TTF / OTF 字体，不支持字体合集"
            }
            val tags = mutableSetOf<Int>()
            repeat(count) {
                tags += input.readInt()
                input.readInt() // checksum
                val offset = input.readInt().toLong() and 0xffffffffL
                val length = input.readInt().toLong() and 0xffffffffL
                require(offset >= 12L + count * 16 && offset + length <= size) { "字体文件损坏，字形数据不完整" }
            }
            require(tags.containsAll(listOf(0x68656164, 0x636d6170, 0x6d617870)) && tags.any { it in listOf(0x676c7966, 0x43464620, 0x43464632) }) { "字体缺少必要的字形表" }
        }
    }
}
