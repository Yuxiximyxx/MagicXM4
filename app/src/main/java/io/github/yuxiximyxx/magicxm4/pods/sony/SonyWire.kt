/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 索尼 WH-1000XM4 SPP 私有协议 —— 帧层。
 *
 * 移植自 SonyHeadphonesClient（GPL-3.0-only，作者 Plutoberth 等）:
 *   https://github.com/Plutoberth/SonyHeadphonesClient
 *   源文件与函数：
 *     Client/Constants.h              —— START_MARKER=62、END_MARKER=60、DATA_TYPE 枚举
 *     Client/CommandSerializer.cpp    —— _escapeSpecials / _unescapeSpecials /
 *                                        _sumChecksum / packageDataForBt / unpackBtMessage
 *     Client/BluetoothWrapper.cpp     —— _waitForAck 里的「按 START/END 扫帧 + 拼半帧」逻辑
 *     Client/ByteMagic.cpp            —— intToBytesBE（4 字节大端长度）
 * 本文件为衍生作品，整体遵循 GPL-3.0-only。
 *
 * ── 线上帧格式（CommandSerializer.h:35 的注释原文）────────────────────────
 *   <START_MARKER> ESCAPE_SPECIALS(
 *        <DATA_TYPE:1><SEQ_NUMBER:1><BIG ENDIAN 4 BYTE SIZE OF UNESCAPED DATA>
 *        <DATA:n><CHECKSUM:1>
 *   ) <END_MARKER>
 *
 * 要点（照抄上游，不要凭直觉改）：
 *   1. START=0x3E('>')，END=0x3C('<')，ESC=0x3D('=')。「标记」本身在线路上是
 *      未转义的裸字节，只有「内容区」才做转义。
 *   2. 内容区里的三个特殊字节用 ESC + (原值 - 0x10) 表示：
 *        0x3C -> 0x3D 0x2C      0x3D -> 0x3D 0x2D      0x3E -> 0x3D 0x2E
 *      （上游常量 ESCAPED_60/61/62 = 44/45/46 = 0x2C/0x2D/0x2E。对这三个值
 *        「减 0x10」与「异或 0x10」结果相同，本实现按减 0x10 写。）
 *   3. CHECKSUM = 内容区（DATA_TYPE..DATA，不含 CHECKSUM 自己）所有字节之和 & 0xFF，
 *      并且在转义之前计算；然后把 CHECKSUM 一起参与转义。
 *   4. 4 字节长度是「未转义的 DATA 长度」，不是帧长、也不含 6 字节头。
 *
 * ── 与上游的两处刻意差异（都是上游 bug，已在测试里锁定正确行为）──────────
 *   a. CommandSerializer.cpp:139-152 的 unpackBtMessage 先把内容 unescape 到
 *      `unescaped`，随后却拿**转义后**的 src 去读 dataType/seq/校验 checksum
 *      （`src[0]`、`_sumChecksum(src...)`）。只要载荷里出现 0x3C/0x3D/0x3E，
 *      上游就会算错校验和、读错 seq。本实现在 unescape 之后统一处理。
 *   b. 上游 _waitForAck 遇到「连续两个 START 而没有 END」直接抛异常并断开连接。
 *      在 Android 上重连代价很高，这里改成「就地重同步」到后一个 START，
 *      并把被跳过的字节通过 onDiscard 交给上层记日志。
 */
package io.github.yuxiximyxx.magicxm4.pods.sony

import java.io.ByteArrayOutputStream

object SonyWire {

    /** 帧起始符 '>'（Constants.h:6）。 */
    const val START: Int = 0x3E

    /** 帧结束符 '<'（Constants.h:7）。 */
    const val END: Int = 0x3C

    /** 转义引导符 '='（CommandSerializer.cpp:3）。 */
    const val ESCAPE: Int = 0x3D

    // 被转义字节的「低半字节」编码（CommandSerializer.cpp:4-6）。
    private const val ESCAPED_END = 0x2C      // 代表 0x3C
    private const val ESCAPED_ESCAPE = 0x2D   // 代表 0x3D
    private const val ESCAPED_START = 0x2E    // 代表 0x3E

    /** 单帧上限，超过按噪声丢弃（Constants.h:5 MAX_BLUETOOTH_MESSAGE_SIZE）。 */
    const val MAX_FRAME_SIZE: Int = 2048

    /**
     * 内容区最小长度：type(1)+seq(1)+len(4)+checksum(1)。
     * 对应上游 `if (src.size() < 7) throw`。
     */
    const val MIN_INNER_SIZE: Int = 7

    /** DATA_TYPE(1) + SEQ(1) + LEN(4) = 6 字节定长头。 */
    private const val HEADER_SIZE: Int = 6

    /**
     * 一条消息：DATA_TYPE + SEQ + 未转义载荷。
     *
     * 注意 [payload] 是数组，data class 默认按引用比较，这里改成按内容比较，
     * 否则单测里的往返断言永远为假。
     */
    data class Message(val type: Int, val seq: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Message && type == other.type && seq == other.seq && payload.contentEquals(other.payload)

        override fun hashCode(): Int = (type * 31 + seq) * 31 + payload.contentHashCode()
    }

    // ── 转义 ─────────────────────────────────────────────────────

    /** 逐字节转义三个特殊值（CommandSerializer.cpp:11-42 _escapeSpecials）。 */
    fun escape(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size + src.size / 2 + 2)
        for (b in src) {
            when (b.toInt() and 0xFF) {
                END -> { out.write(ESCAPE); out.write(ESCAPED_END) }
                ESCAPE -> { out.write(ESCAPE); out.write(ESCAPED_ESCAPE) }
                START -> { out.write(ESCAPE); out.write(ESCAPED_START) }
                else -> out.write(b.toInt() and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * 转义还原（CommandSerializer.cpp:44-85 _unescapeSpecials）。
     * 遇到「ESC 结尾没有后续」「ESC 后面不是 2C/2D/2E」就抛异常——上游也是抛异常，
     * 调用方（[Framer]、[parse]）会把它当成坏帧丢掉。
     */
    fun unescape(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            val curr = src[i].toInt() and 0xFF
            if (curr != ESCAPE) {
                out.write(curr)
                i++
                continue
            }
            // 上游原文错误信息：「No data left for escaped byte data」
            require(i + 1 < src.size) { "转义符 0x3D 后面没有数据" }
            when (src[i + 1].toInt() and 0xFF) {
                ESCAPED_END -> out.write(END)
                ESCAPED_ESCAPE -> out.write(ESCAPE)
                ESCAPED_START -> out.write(START)
                else -> throw IllegalArgumentException("非法转义序列 0x3D ${hex(src.copyOfRange(i, i + 2))}")
            }
            i += 2
        }
        return out.toByteArray()
    }

    /** [unescape] 的非抛异常版本，流式解帧时用它丢坏帧。 */
    fun unescapeOrNull(src: ByteArray): ByteArray? = try {
        unescape(src)
    } catch (_: IllegalArgumentException) {
        null
    }

    // ── 校验和 ───────────────────────────────────────────────────

    /**
     * 校验和 = 字节累加，自然截断到 8 位（CommandSerializer.cpp:87-95 _sumChecksum）。
     * 上游函数名叫 _sumChecksum，就是朴素和，不是 CRC、也不是反码和。
     */
    fun checksum(bytes: ByteArray, size: Int = bytes.size): Int {
        var sum = 0
        val n = minOf(size, bytes.size)
        for (i in 0 until n) sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        return sum
    }

    // ── 组帧 / 解帧 ──────────────────────────────────────────────

    /**
     * 组一整帧（CommandSerializer.cpp:102-134 packageDataForBt）。
     *
     * @param type DATA_TYPE
     * @param seq  序号，只取低 8 位（上游把它当 `unsigned char` 塞进帧）
     * @param payload 未转义载荷
     */
    fun build(type: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val inner = ByteArray(HEADER_SIZE + payload.size + 1)
        inner[0] = type.toByte()
        inner[1] = seq.toByte()
        writeU32Be(inner, 2, payload.size)
        payload.copyInto(inner, HEADER_SIZE)
        inner[inner.size - 1] = checksum(inner, inner.size - 1).toByte()

        val escaped = escape(inner)
        val frame = ByteArray(escaped.size + 2)
        frame[0] = START.toByte()
        escaped.copyInto(frame, 1)
        frame[frame.size - 1] = END.toByte()

        // 上游对超长帧直接 throw（并且明确声明不支持分块），这里保持同样的态度。
        require(frame.size <= MAX_FRAME_SIZE) { "帧长 ${frame.size} 超过 $MAX_FRAME_SIZE" }
        return frame
    }

    fun build(msg: Message): ByteArray = build(msg.type, msg.seq, msg.payload)

    /**
     * 解帧 → [Message]；任何结构/校验失败都返回 null（上游抛异常）。
     *
     * 接受的输入有两种形态，便于同时服务「原始线路字节」和「[Framer] 输出」：
     *   - 带 START/END 的完整线路帧；
     *   - 已经去过转义的内容区（[Framer] 返回的就是这个）。
     * 判定方式看首尾是否正好是 START/END。DATA_TYPE 的取值范围（0..45）里没有 0x3E，
     * 所以两种形态不会混淆。
     */
    fun parse(frame: ByteArray): Message? {
        if (frame.size < MIN_INNER_SIZE) return null

        val data = if ((frame[0].toInt() and 0xFF) == START &&
            frame.size > 2 &&
            (frame[frame.size - 1].toInt() and 0xFF) == END
        ) {
            unescapeOrNull(frame.copyOfRange(1, frame.size - 1)) ?: return null
        } else {
            frame
        }

        if (data.size < MIN_INNER_SIZE) return null
        val payloadLen = readU32Be(data, 2)
        // 长度域与实际长度必须对得上，否则就是「把半帧当整帧」的典型症状。
        if (payloadLen != data.size - MIN_INNER_SIZE) return null
        if ((data[data.size - 1].toInt() and 0xFF) != checksum(data, data.size - 1)) return null

        return Message(
            type = data[0].toInt() and 0xFF,
            seq = data[1].toInt() and 0xFF,
            payload = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen),
        )
    }

    /**
     * 调试辅助：`3E 0C 00 ...` 形式，日志和测试都用它。
     *
     * 手写而不是 String.format("%02X")：后者跟随系统 Locale，阿拉伯语等区域会
     * 输出本地数字，日志对不上、单测在那种环境下也会挂。日志工具不该有环境依赖。
     */
    fun hex(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val table = "0123456789ABCDEF"
        val sb = StringBuilder(data.size * 3 - 1)
        for (i in data.indices) {
            if (i > 0) sb.append(' ')
            val v = data[i].toInt() and 0xFF
            sb.append(table[v ushr 4]).append(table[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 流 → 帧。处理噪声、半帧、粘包。
     *
     * 与 BluetoothWrapper.cpp:60-97 `_waitForAck` 的对应关系：
     *   - 上游用两个游标（messageStart/messageEnd）在 recv 缓冲区里找一对 START/END，
     *     跨 recv 时把片段 append 进 msgBytes 继续收；本类等价于把「残留片段」留在
     *     [pending] 里等下一次 append，功能一致。
     *   - 输出是**已去转义的内容区**（type|seq|len4|payload|checksum），
     *     交给 [parse] 即可，不必再解一遍转义。
     */
    class Framer {

        /** 尚未构成整帧的尾巴（可能只有半个帧，也可能是一个孤立的 START）。 */
        private var pending = ByteArray(0)

        /** 被判定不是帧的字节（噪声、坏帧）会原样回调，方便上层打日志。 */
        var onDiscard: ((ByteArray) -> Unit)? = null

        fun append(buffer: ByteArray, length: Int): List<ByteArray> {
            if (length <= 0 || buffer.isEmpty()) return emptyList()
            val take = minOf(length, buffer.size)
            pending += buffer.copyOfRange(0, take)

            val frames = ArrayList<ByteArray>()
            while (pending.isNotEmpty()) {
                val start = indexOfByte(pending, START, 0)
                if (start < 0) {
                    // 整段都不是帧：一次性丢掉，别让它无限增长。
                    discard(pending)
                    pending = ByteArray(0)
                    break
                }
                if (start > 0) {
                    discard(pending.copyOfRange(0, start))
                    pending = pending.copyOfRange(start, pending.size)
                }

                val end = indexOfByte(pending, END, 1)
                val nextStart = indexOfByte(pending, START, 1)

                if (end < 0) {
                    // 还没收到 END：要么是半帧（等下一次 append），要么是垃圾。
                    if (pending.size > MAX_FRAME_SIZE) {
                        discard(pending.copyOfRange(0, 1))
                        pending = pending.copyOfRange(1, pending.size)
                        continue
                    }
                    break
                }
                if (nextStart in 1 until end) {
                    // 旧的 START 之后又冒出一个 START 且之前没有 END：
                    // 上游在这里抛异常；我们直接放弃前一段，从新的 START 重新对齐。
                    discard(pending.copyOfRange(0, nextStart))
                    pending = pending.copyOfRange(nextStart, pending.size)
                    continue
                }

                val candidate = pending.copyOfRange(1, end)
                val inner = unescapeOrNull(candidate)
                val wellFormed = inner != null &&
                    inner.size >= MIN_INNER_SIZE &&
                    readU32Be(inner, 2) == inner.size - MIN_INNER_SIZE &&
                    (inner[inner.size - 1].toInt() and 0xFF) == checksum(inner, inner.size - 1)

                // 无论好坏都消费掉「START..END」这一整段，避免在同一位点反复打转。
                val consumed = end + 1
                if (wellFormed) {
                    frames += inner!!
                    pending = pending.copyOfRange(consumed, pending.size)
                } else {
                    // 例如「噪声里恰好有个 0x3E、后面又恰好有个 0x3C」，或校验和不对。
                    // 注意这里丢的是 START..END 整段，但被丢弃的内容里可能还藏着
                    // 一个真 START，所以下一步循环会重新扫 pending。
                    discard(pending.copyOfRange(0, consumed))
                    pending = pending.copyOfRange(consumed, pending.size)
                }
            }
            return frames
        }

        private fun discard(bytes: ByteArray) {
            if (bytes.isNotEmpty()) onDiscard?.invoke(bytes)
        }
    }

    // ── 字节序小工具（ByteMagic.cpp:13-21 intToBytesBE）──────────

    private fun writeU32Be(dst: ByteArray, at: Int, value: Int) {
        dst[at] = ((value ushr 24) and 0xFF).toByte()
        dst[at + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 3] = (value and 0xFF).toByte()
    }

    private fun readU32Be(src: ByteArray, at: Int): Int {
        if (at + 3 >= src.size) return -1
        return ((src[at].toInt() and 0xFF) shl 24) or
            ((src[at + 1].toInt() and 0xFF) shl 16) or
            ((src[at + 2].toInt() and 0xFF) shl 8) or
            (src[at + 3].toInt() and 0xFF)
    }

    private fun indexOfByte(src: ByteArray, value: Int, from: Int): Int {
        var i = maxOf(from, 0)
        val v = value.toByte()
        while (i < src.size) {
            if (src[i] == v) return i
            i++
        }
        return -1
    }
}
