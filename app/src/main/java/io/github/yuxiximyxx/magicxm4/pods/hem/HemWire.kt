/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 欢律（OPPO / HeyMelody）SPP 帧层。
 *
 * 移植自 QuickBuds（GPL-3.0）:
 *   https://github.com/spizganed/QuickBuds
 *   源文件：app/src/main/kotlin/com/spizganed/quickbuds/protocol/Wire.kt
 * 证据：docs/PROTOCOL.md §2（帧格式）。本文件为衍生作品，整体遵循 GPL-3.0。
 *
 * 帧结构（PROTOCOL.md §2）：
 *   AA | TotalLen(LEB128, 7bit/字节) | slice | 00 | [fsn] | data
 * 整帧无校验字节（PROTOCOL.md:89 "No checksum."）。
 * data（整帧消息）= Cmd(2 LE) | Seq(1) | PayLen(2 LE) | Payload
 * 规整后固定 9 字节头：AA | len | 00 | 00 | cmd(2LE) | seq | payLen(2LE) | payload
 */
package io.github.yuxiximyxx.magicxm4.pods.hem

import java.io.ByteArrayOutputStream

object HemWire {

    /** 帧起始字节。 */
    const val START: Int = 0xAA

    /** 单帧最大数据预算（QuickBuds 设备实测值）。 */
    const val BUDGET: Int = 660

    /** 帧长度上限，超过视为噪声。 */
    private const val MAX_FRAME_LEN = 2048

    /** 一条消息：cmd + seq + payload。 */
    data class Message(val cmd: Int, val seq: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Message && cmd == other.cmd && seq == other.seq && payload.contentEquals(other.payload)

        override fun hashCode(): Int = (cmd * 31 + seq) * 31 + payload.contentHashCode()
    }

    /** 构造一整帧（不分片：本项目用到的命令 payload 都很短）。 */
    fun build(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val totalLen = 7 + payload.size
        val out = ByteArrayOutputStream(totalLen + 4)
        out.write(START)
        var v = totalLen
        do {
            val b = v and 0x7F
            v = v ushr 7
            out.write(if (v != 0) b or 0x80 else b)
        } while (v != 0)
        out.write(0)                 // slice = 0，整条消息
        out.write(0)                 // 固定 0x00
        out.write(cmd and 0xFF)
        out.write((cmd shr 8) and 0xFF)
        out.write(seq and 0xFF)
        out.write(payload.size and 0xFF)
        out.write((payload.size shr 8) and 0xFF)
        out.write(payload, 0, payload.size)
        return out.toByteArray()
    }

    fun u16(data: ByteArray, at: Int): Int {
        if (at + 1 >= data.size) return 0
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    fun hex(data: ByteArray): String =
        data.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

    /**
     * 流 → 帧。处理半帧、粘包、帧头前的噪声，并把分片（slice 1/2/3）拼回整帧。
     * 输出统一为「9 字节头 + payload」的规整帧，供 [parse] 读取。
     */
    class Framer {

        private var pending = ByteArray(0)
        private var slices: ByteArray? = null

        var onDiscard: ((ByteArray) -> Unit)? = null

        fun append(buffer: ByteArray, length: Int): List<ByteArray> {
            if (length <= 0) return emptyList()
            pending += buffer.copyOfRange(0, length)
            val frames = ArrayList<ByteArray>()

            while (pending.isNotEmpty()) {
                val start = pending.indexOf(START.toByte())
                if (start < 0) {
                    onDiscard?.invoke(pending)
                    pending = ByteArray(0)
                    break
                }
                if (start > 0) {
                    onDiscard?.invoke(pending.copyOfRange(0, start))
                    pending = pending.copyOfRange(start, pending.size)
                }
                val lengthInfo = readLength(pending)
                if (lengthInfo == null) {
                    // 连续 3 个长度字节都没有终止位：不可能是帧头，丢掉这个 AA 继续扫。
                    if (pending.size > 3) {
                        onDiscard?.invoke(pending.copyOfRange(0, 1))
                        pending = pending.copyOfRange(1, pending.size)
                        continue
                    }
                    break
                }
                val (totalLen, lenBytes) = lengthInfo
                if (pending.size < 2 + lenBytes) break

                val slice = pending[1 + lenBytes].toInt() and 0xFF
                val sliced = slice in 1..3
                val frameLen = 1 + lenBytes + totalLen
                if (totalLen < (if (sliced) 4 else 7) || frameLen > MAX_FRAME_LEN) {
                    onDiscard?.invoke(pending.copyOfRange(0, 1))
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < frameLen) break

                val frame = pending.copyOfRange(0, frameLen)
                pending = pending.copyOfRange(frameLen, pending.size)
                if (sliced) {
                    join(slice, frame.copyOfRange(1 + lenBytes + 3, frameLen))?.let { frames += it }
                } else {
                    frames += normalise(frame)
                }
            }
            return frames
        }

        private fun join(slice: Int, data: ByteArray): ByteArray? {
            if (slice == 1) slices = data
            else slices = (slices ?: return null) + data
            if (slice != 3) return null
            val msg = slices ?: return null
            slices = null
            if (msg.size < 5) return null
            val payloadLen = minOf(u16(msg, 3), msg.size - 5)
            val fixed = msg.copyOfRange(0, 3) +
                byteArrayOf(payloadLen.toByte(), (payloadLen shr 8).toByte()) +
                msg.copyOfRange(5, 5 + payloadLen)
            return byteArrayOf(START.toByte(), ((2 + fixed.size) and 0x7F).toByte(), 0, 0) + fixed
        }
    }

    /** TotalLen 及其占用的字节数；帧还不够长时返回 null。 */
    fun readLength(frame: ByteArray): Pair<Int, Int>? {
        var value = 0
        for (i in 1 until minOf(frame.size, 4)) {
            val b = frame[i].toInt() and 0xFF
            value = value or ((b and 0x7F) shl (7 * (i - 1)))
            if (b and 0x80 == 0) return value to i
        }
        return null
    }

    /** 多字节长度域压回单字节，让后续代码只认一种布局。 */
    fun normalise(frame: ByteArray): ByteArray {
        if (frame.isEmpty() || (frame[0].toInt() and 0xFF) != START) return frame
        val lenBytes = readLength(frame)?.second ?: return frame
        if (lenBytes == 1) return frame
        return byteArrayOf(frame[0], (frame[1].toInt() and 0x7F).toByte()) +
            frame.copyOfRange(1 + lenBytes, frame.size)
    }

    /** 规整帧 → Message；太短返回 null。 */
    fun parse(frame: ByteArray): Message? {
        if (frame.size < 9) return null
        if ((frame[0].toInt() and 0xFF) != START) return null
        val payloadLen = u16(frame, 7)
        val available = frame.size - 9
        return Message(
            cmd = u16(frame, 4),
            seq = frame[6].toInt() and 0xFF,
            payload = frame.copyOfRange(9, 9 + minOf(payloadLen, available))
        )
    }
}
