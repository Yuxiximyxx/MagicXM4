/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 电量解析（WH-1000XM4 / 丹拿版）。
 *
 * 移植自 QuickBuds（GPL-3.0）protocol/Battery.kt:13-75。
 * 证据：PROTOCOL.md §电量章节；QuickBuds 代码注释给出了两条独立路径：
 *   查询回复 0x8106：payload = [status=00][count][idx, raw]...
 *   主动上报 0x0204 subType 0x01：payload = [01][count][idx, raw]...
 * 注意：主动上报**没有** status 字节，两条路径不能共用同一段解析。
 *
 * idx：1 = 左耳，2 = 右耳，3 = 仓
 * raw：level = raw & 0x7F；charging = (raw & 0x80) != 0；level 0 = 未知（按 null）
 */
package io.github.yuxiximyxx.magicxm4.pods.hem

object HemBattery {

    data class Info(val level: Int, val isCharging: Boolean)

    data class Result(val left: Info?, val right: Info?, val case: Info?) {
        val any: Boolean get() = left != null || right != null || case != null
    }

    /** 解析 0x8106 查询回复。 */
    fun parseQuery(msg: HemWire.Message): Result? {
        if (msg.cmd != HemCommands.RSP_BATTERY) return null
        val p = msg.payload
        if (p.size < 2) return null
        if ((p[0].toInt() and 0xFF) != 0x00) return null
        return parsePairs(p, 1)
    }

    /** 解析 0x0204 subType 0x01 主动上报。 */
    fun parseActive(msg: HemWire.Message): Result? {
        if (msg.cmd != HemCommands.CMD_ACTIVE_REPORT) return null
        val p = msg.payload
        if (p.size < 3) return null
        if ((p[0].toInt() and 0xFF) != HemCommands.EVT_BATTERY) return null
        return parsePairs(p, 1)
    }

    /** [start] 指向 count 字节，其后是 (idx, raw) 对。 */
    private fun parsePairs(p: ByteArray, start: Int): Result? {
        if (start >= p.size) return null
        val count = p[start].toInt() and 0xFF
        if (count == 0 || p.size < start + 1 + count * 2) return null

        var left: Info? = null
        var right: Info? = null
        var case: Info? = null
        for (i in 0 until count) {
            val idxPos = start + 1 + i * 2
            val idx = p[idxPos].toInt() and 0xFF
            val raw = p[idxPos + 1].toInt() and 0xFF
            val level = raw and 0x7F
            val info = if (level == 0) null else Info(level, (raw and 0x80) != 0)
            when (idx) {
                1 -> left = info
                2 -> right = info
                3 -> case = info
            }
        }
        return Result(left, right, case)
    }

    /**
     * 解析降噪上报/回读的值。
     *
     *  - 0x0204 subType 0x03：payload `03 01 01 <value LE 1-4B>`；`03 02 01 <mask>` 是写入回声，必须拒绝。
     *  - 0x810C 查询回复：payload `00 <what> <arg> <value LE>`。
     */
    fun ancRawValue(msg: HemWire.Message): Int {
        val p = msg.payload
        return when (msg.cmd) {
            HemCommands.CMD_ACTIVE_REPORT -> {
                if (p.size < 5) return -1
                if ((p[0].toInt() and 0xFF) != HemCommands.EVT_ANC) return -1
                if ((p[1].toInt() and 0xFF) != 0x01 || (p[2].toInt() and 0xFF) != 0x01) return -1  // 回声帧
                leValue(p, 3)
            }
            HemCommands.RSP_ANC -> {
                if (p.size < 4) return -1
                leValue(p, 3)
            }
            else -> -1
        }
    }

    private fun leValue(p: ByteArray, from: Int): Int {
        var value = 0
        val end = minOf(p.size, from + 4)
        for (i in end - 1 downTo from) value = (value shl 8) or (p[i].toInt() and 0xFF)
        return value
    }
}

/** 0x810D 功能状态 / 0x8105 固件 / 0x8130 提示音音量 的解析（FIX-009）。 */
object HemStatus {

    /**
     * `0x810D`：`<count>` 后跟 (id, value) 对。
     * 实测小米/荣耀固件有的会先带一个状态字节 00，故两种偏移都尝试。
     */
    fun features(msg: HemWire.Message): Map<Int, Int>? {
        if (msg.cmd != HemCommands.RSP_STATUS) return null
        val p = msg.payload
        for (start in intArrayOf(0, 1)) {
            if (p.size <= start) continue
            val count = p[start].toInt() and 0xFF
            if (count == 0 || p.size < start + 1 + count * 2) continue
            val out = LinkedHashMap<Int, Int>()
            for (i in 0 until count) {
                val pos = start + 1 + i * 2
                out[p[pos].toInt() and 0xFF] = p[pos + 1].toInt() and 0xFF
            }
            if (out.isNotEmpty()) return out
        }
        return null
    }

    /**
     * `0x8105`：`00 <count>` 后跟 UTF-8 的 `device,type,version` 三元组（逗号分隔）。
     * 取其中的 version 字段按顺序拼成版本串。
     */
    fun firmware(msg: HemWire.Message): String? {
        if (msg.cmd != HemCommands.RSP_FIRMWARE) return null
        val p = msg.payload
        if (p.size < 3 || (p[0].toInt() and 0xFF) != 0) return null
        val text = String(p, 2, p.size - 2, Charsets.UTF_8)
        val parts = text.split(',').map { it.trim() }
        val versions = parts.chunked(3)
            .filter { it.size == 3 }
            .mapNotNull { it.getOrNull(2) }
            .filter { it.isNotEmpty() && it != "00" && it != "01" }
        if (versions.isEmpty()) return text.ifBlank { null }
        return versions.joinToString(".")
    }

    /** 提示音音量：`00 <level>`。 */
    fun alertVolume(msg: HemWire.Message): Int? {
        if (msg.cmd != HemCommands.RSP_ALERT_VOLUME) return null
        val p = msg.payload
        val idx = if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == 0) 1 else 0
        if (p.size <= idx) return null
        return p[idx].toInt() and 0xFF
    }

    /** 当前 EQ 预设 id（`00 <id>`）；解析失败返回 null，由调用方忽略。 */
    fun eqPreset(msg: HemWire.Message): Int? {
        if (msg.cmd != HemCommands.RSP_EQ) return null
        val p = msg.payload
        val idx = if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == 0) 1 else 0
        if (p.size <= idx) return null
        return p[idx].toInt() and 0xFF
    }
}

/** 0x8108 按键绑定解析（FIX-011）。payload = <status> <count> [deviceType, button, buttonAction, function]... */
object HemGesture {
    /** (deviceType, button, buttonAction, function) */
    fun parse(msg: HemWire.Message): List<IntArray>? {
        if (msg.cmd != HemCommands.RSP_KEY_FUNCTION) return null
        val p = msg.payload
        if (p.size < 2) return null
        val count = p[1].toInt() and 0xFF
        val out = ArrayList<IntArray>(count)
        for (i in 0 until count) {
            val o = 2 + i * 4
            if (o + 3 >= p.size) break
            out.add(
                intArrayOf(
                    p[o].toInt() and 0xFF,
                    p[o + 1].toInt() and 0xFF,
                    p[o + 2].toInt() and 0xFF,
                    p[o + 3].toInt() and 0xFF
                )
            )
        }
        return out.ifEmpty { null }
    }
}

/** 0x8112 设备列表：`<count>` 后每条 `MAC(6) <byte6> <state> <flags> <nameLen> <name>`。 */
object HemDevices {
    data class Dev(val mac: String, val name: String, val connected: Boolean)

    fun parse(msg: HemWire.Message): List<Dev>? {
        if (msg.cmd != HemCommands.RSP_DEVICES) return null
        val p = msg.payload
        if (p.size < 2) return null
        // payload 前面可能带状态字节 00（QuickBuds 用 ok() 先剥掉），这里兼容两种偏移
        val start = if ((p[0].toInt() and 0xFF) == 0) 1 else 0
        val count = p[start].toInt() and 0xFF
        var i = start + 1
        val out = ArrayList<Dev>(count)
        repeat(count) {
            if (i + 10 > p.size) return@repeat
            val mac = (0 until 6).joinToString(":") { "%02X".format(p[i + it].toInt() and 0xFF) }
            val state = p[i + 7].toInt() and 0xFF
            val nameLen = p[i + 9].toInt() and 0xFF
            if (i + 10 + nameLen > p.size) return@repeat
            val name = String(p, i + 10, nameLen, Charsets.UTF_8)
            out.add(Dev(mac, name, state == 2))
            i += 10 + nameLen
        }
        return out.ifEmpty { null }
    }
}
