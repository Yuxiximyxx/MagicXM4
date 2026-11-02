/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 自定义 EQ 编解码。
 * 移植自 QuickBuds（GPL-3.0）protocol/Eq.kt:1-92，证据见其 PROTOCOL.md §17。
 *
 * 列表 `0x8122`：`<status 00> <count> <entry>...`
 *   条目：`flag(01=选中) tag(2B) id nameLen name bandCount [freq u16 LE, gain s8]...`
 * 写入 `0x0418`：`<action> <tag> <id> <nameLen> <name> <bandCount> <bands...>`
 *   action：1 新建（id 传 0，耳机分配）/ 2 保存并选中 / 3 删除
 */
package io.github.yuxiximyxx.magicxm4.pods.hem

object HemEq {

    const val ACTION_CREATE = 0x01
    const val ACTION_SAVE = 0x02
    const val ACTION_DELETE = 0x03

    const val GAIN_MIN = -6
    const val GAIN_MAX = 6

    val DEFAULT_TAG = byteArrayOf(GAIN_MIN.toByte(), GAIN_MAX.toByte())
    /** 机型没有给频段时的缺省（HeyMelody 的 6 段）。 */
    val DEFAULT_FREQS = listOf(62, 250, 1000, 4000, 8000, 16000)

    data class Preset(
        val id: Int,
        val name: String,
        val freqs: List<Int>,
        val gains: List<Int>,
        val selected: Boolean,
        val tag: ByteArray,
    )

    fun parseList(payload: ByteArray): List<Preset>? {
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != 0) return null
        val count = payload[1].toInt() and 0xFF
        var o = 2
        val out = ArrayList<Preset>(count)
        repeat(count) {
            if (o + 5 > payload.size) return null
            val flag = payload[o].toInt() and 0xFF
            val tag = payload.copyOfRange(o + 1, o + 3)
            val id = payload[o + 3].toInt() and 0xFF
            val nameLen = payload[o + 4].toInt() and 0xFF
            o += 5
            if (o + nameLen + 1 > payload.size) return null
            val name = String(payload, o, nameLen, Charsets.UTF_8)
            o += nameLen
            val bands = payload[o].toInt() and 0xFF
            o += 1
            if (o + bands * 3 > payload.size) return null
            val freqs = ArrayList<Int>(bands)
            val gains = ArrayList<Int>(bands)
            repeat(bands) {
                freqs += (payload[o].toInt() and 0xFF) or ((payload[o + 1].toInt() and 0xFF) shl 8)
                gains += payload[o + 2].toInt()
                o += 3
            }
            out += Preset(id, name, freqs, gains, flag == 1, tag)
        }
        return out
    }

    fun encode(action: Int, p: Preset): ByteArray {
        val name = p.name.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(action)
        out.write(p.tag, 0, p.tag.size)
        out.write(p.id)
        out.write(name.size)
        out.write(name, 0, name.size)
        out.write(p.freqs.size)
        for (i in p.freqs.indices) {
            out.write(p.freqs[i] and 0xFF)
            out.write((p.freqs[i] shr 8) and 0xFF)
            out.write(p.gains.getOrElse(i) { 0 })
        }
        return out.toByteArray()
    }

    /** 序列化成跨进程字符串：`id|name|sel|freq:gain,...`，多条用 `;` 分隔。 */
    fun serialize(list: List<Preset>): String = list.joinToString(";") { p ->
        val bands = p.freqs.indices.joinToString(",") { "${p.freqs[it]}:${p.gains.getOrElse(it) { 0 }}" }
        "${p.id}|${p.name}|${if (p.selected) 1 else 0}|$bands"
    }

    fun deserialize(raw: String): List<Preset> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val name = parts[1]
            val sel = parts[2] == "1"
            val freqs = ArrayList<Int>()
            val gains = ArrayList<Int>()
            parts[3].split(',').forEach { band ->
                val kv = band.split(':')
                if (kv.size == 2) {
                    kv[0].toIntOrNull()?.let { freqs.add(it) }
                    kv[1].toIntOrNull()?.let { gains.add(it) }
                }
            }
            if (freqs.isEmpty()) return@mapNotNull null
            Preset(id, name, freqs, gains, sel, DEFAULT_TAG)
        }
    }

    fun newPreset(name: String, freqs: List<Int> = DEFAULT_FREQS) =
        Preset(0, name, freqs, List(freqs.size) { 0 }, false, DEFAULT_TAG)
}
