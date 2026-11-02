/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 能力位解析（0x8100 握手回复）。
 * 表来自 QuickBuds（GPL-3.0）protocol/Capabilities.kt:18-47：
 * payload = `00 <bitmap>`，LSB 起第 n 位表示固件接受哪些命令。
 */
package io.github.yuxiximyxx.magicxm4.pods.hem

object HemCapabilities {

    private val BIT_COMMANDS: Array<IntArray> = arrayOf(
        intArrayOf(0x0105), intArrayOf(0x0106), intArrayOf(0x0107), intArrayOf(0x0108, 0x0401, 0x0416),
        intArrayOf(0x0109), intArrayOf(0x0400), intArrayOf(0x0402), intArrayOf(0x0403),
        intArrayOf(0x010C, 0x0404), intArrayOf(0x0405), intArrayOf(0x0406, 0x010F), intArrayOf(0x0407),
        intArrayOf(), intArrayOf(0x0408), intArrayOf(0x0409), intArrayOf(), intArrayOf(),
        intArrayOf(0x0114), intArrayOf(), intArrayOf(0x040E, 0x040D, 0x0115, 0x0116),
        intArrayOf(0x040F), intArrayOf(0x0410, 0x0119), intArrayOf(0x0205), intArrayOf(0x0F00),
        intArrayOf(), intArrayOf(0x0118, 0x0411), intArrayOf(0x011A, 0x0412),
        intArrayOf(0x011C, 0x0413), intArrayOf(), intArrayOf(0x0112, 0x040B),
        intArrayOf(0x011E, 0x011F, 0x0415), intArrayOf(0x040D), intArrayOf(),
        intArrayOf(0x0121, 0x0417), intArrayOf(0x0122, 0x0418), intArrayOf(0x0419),
        intArrayOf(0x011D, 0x0414), intArrayOf(0x0123, 0x041A), intArrayOf(0x0124, 0x041B),
        intArrayOf(0x0125, 0x041C, 0x0127, 0x041D, 0x041F),
        intArrayOf(0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129), intArrayOf(0xEF01),
        intArrayOf(0xEF02), intArrayOf(0xEF03, 0x041E), intArrayOf(0x0420), intArrayOf(0x001C),
        intArrayOf(), intArrayOf(0x0422, 0x012A), intArrayOf(0xEF04), intArrayOf(0x0423, 0x012B),
        intArrayOf(), intArrayOf(0x0424), intArrayOf(0xEF06), intArrayOf(), intArrayOf(),
        intArrayOf(0x0425, 0x012E, 0x0426), intArrayOf(0x012F), intArrayOf(0x0427, 0x0130),
        intArrayOf(0x0131, 0x0428), intArrayOf(0x0429, 0x0132), intArrayOf(0x0014),
        intArrayOf(0x042D, 0x0133), intArrayOf(0x042E), intArrayOf(0xEF07), intArrayOf(0xEF08, 0x0136, 0x0433),
        intArrayOf(0xEF09), intArrayOf(0x0431, 0x0134), intArrayOf(0x0432, 0x0135, 0x0435), intArrayOf(0x0117)
    )

    private val ALWAYS = setOf(
        0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04
    )

    /** `0x8100` payload `00 <bitmap>` → 支持的命令集合；格式不对返回 null。 */
    fun parse(payload: ByteArray): Set<Int>? {
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != 0) return null
        val out = HashSet(ALWAYS)
        val maxBits = minOf((payload.size - 1) * 8, BIT_COMMANDS.size)
        for (bit in 0 until maxBits) {
            if ((payload[1 + bit / 8].toInt() shr (bit % 8)) and 1 == 1) {
                BIT_COMMANDS[bit].forEach { out.add(it) }
            }
        }
        return out
    }
}
