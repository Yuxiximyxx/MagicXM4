/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 欢律（OPPO / HeyMelody）命令表。
 *
 * 移植自 QuickBuds（GPL-3.0）:
 *   https://github.com/spizganed/QuickBuds
 *   源文件：protocol/Protocol.kt、protocol/Commands.kt、protocol/Capabilities.kt
 * 命令号与 payload 形状均为逆向所得，证据见 QuickBuds/docs/PROTOCOL.md。
 *
 * 本项目只用其中最小集合：握手、产品 ID、降噪读写、电量、佩戴、事件订阅。
 */
package io.github.yuxiximyxx.magicxm4.pods.hem

import java.util.concurrent.atomic.AtomicInteger

object HemCommands {

    // ── 命令号 ──────────────────────────────────────────────
    const val CMD_HANDSHAKE = 0x0100
    const val CMD_QUERY_PRODUCT_ID = 0x0103
    const val CMD_QUERY_BATTERY = 0x0106          // seq 固定 0xF0
    const val CMD_QUERY_WEARING = 0x0109          // seq 固定 0xF2
    const val CMD_QUERY_ANC = 0x010C
    const val CMD_QUERY_KEY_FUNCTION = 0x0108
    const val CMD_SET_KEY_FUNCTION = 0x0401
    const val CMD_FIT_TEST = 0x0405
    const val CMD_QUERY_DEVICES = 0x0112
    const val CMD_QUERY_PNOISE = 0x011A
    const val RSP_PNOISE = 0x811A
    const val RSP_DEVICES = 0x8112
    const val RSP_KEY_FUNCTION = 0x8108
    const val CMD_ACTIVE_REPORT = 0x0204          // 主动上报，payload[0] = subType
    const val CMD_REGISTER_NOTIFY = 0x0205        // 订阅主动上报
    const val CMD_SET_ANC = 0x0404

    // ── 回复号 ──────────────────────────────────────────────
    const val RSP_HANDSHAKE = 0x8100
    const val RSP_PRODUCT_ID = 0x8103
    const val RSP_BATTERY = 0x8106
    const val RSP_WEARING = 0x8109
    const val RSP_ANC = 0x810C

    // ── 0x0204 上报子类型 ────────────────────────────────────
    const val EVT_BATTERY = 0x01
    const val EVT_WEARING = 0x02
    const val EVT_ANC = 0x03

    // ── 固定序号 ────────────────────────────────────────────
    const val SEQ_BATTERY = 0xF0
    const val SEQ_WEARING = 0xF2

    /** 通用序号：0x01..0xBF 循环（引擎专用区间 0xC0..0xDF 不用）。 */
    private val seqCounter = AtomicInteger(0x10)

    fun nextSeq(): Int {
        while (true) {
            val cur = seqCounter.get()
            val next = if (cur >= 0xBF) 0x01 else cur + 1
            if (seqCounter.compareAndSet(cur, next)) return next
        }
    }

    // ── 报文构造 ────────────────────────────────────────────

    fun handshake(): ByteArray = HemWire.build(CMD_HANDSHAKE, nextSeq())

    fun queryProductId(): ByteArray = HemWire.build(CMD_QUERY_PRODUCT_ID, nextSeq())

    /**
     * 订阅主动上报。payload = [count][id...]（QuickBuds 实测：count 在前，
     * 写成 01 01 02 02 会被当成「只订阅电量」，佩戴推送永远不来）。
     */
    fun registerNotifications(ids: List<Int>): ByteArray {
        val payload = ByteArray(1 + ids.size)
        payload[0] = ids.size.toByte()
        ids.forEachIndexed { i, id -> payload[1 + i] = id.toByte() }
        return HemWire.build(CMD_REGISTER_NOTIFY, nextSeq(), payload)
    }

    /** 读当前降噪档：payload 01 01 → 回复 0x810C。 */
    fun queryAnc(): ByteArray = HemWire.build(CMD_QUERY_ANC, nextSeq(), byteArrayOf(0x01, 0x01))

    fun queryBattery(): ByteArray = HemWire.build(CMD_QUERY_BATTERY, SEQ_BATTERY)

    fun queryWearing(): ByteArray = HemWire.build(CMD_QUERY_WEARING, SEQ_WEARING)

    // ── 功能开关 / 版本 / EQ / 提示音音量（FIX-009）────────────
    const val CMD_QUERY_FIRMWARE = 0x0105
    const val CMD_QUERY_STATUS = 0x010D      // payload = 功能 id 列表
    const val CMD_QUERY_EQ = 0x010F
    const val CMD_QUERY_ALERT_VOLUME = 0x0130
    const val CMD_SET_FEATURE = 0x0403
    const val CMD_SET_EQ = 0x0406
    const val CMD_QUERY_EQ_ALL = 0x0122
    const val CMD_SAVE_EQ = 0x0418
    const val RSP_EQ_ALL = 0x8122
    const val CMD_SET_ALERT_VOLUME = 0x0427

    const val RSP_STATUS = 0x810D            // [count] (id, value)...
    const val RSP_FIRMWARE = 0x8105
    const val RSP_EQ = 0x810F
    const val RSP_ALERT_VOLUME = 0x8130

    /**
     * XM4 的 statusQuery（models.json:108）：05 04 0B 11 18 06 1B 1C
     * 末尾追加几个候选能力位（听力优化 38、防风噪 1A、省电 17、自适应音量 30、睡眠暂停 3A、
     * 个性化降噪 0C）——不支持的固件会直接忽略，用于探测哪些功能真的能开。
     */
    val FREE4_STATUS_IDS = byteArrayOf(
        0x05, 0x04, 0x0B, 0x11, 0x18, 0x06, 0x1B, 0x1C,
        0x38, 0x1A, 0x17, 0x30, 0x3A, 0x0C
    )

    fun queryStatus(ids: ByteArray = FREE4_STATUS_IDS): ByteArray =
        HemWire.build(CMD_QUERY_STATUS, nextSeq(), ids)

    fun queryFirmware(): ByteArray = HemWire.build(CMD_QUERY_FIRMWARE, nextSeq())

    fun queryEq(): ByteArray = HemWire.build(CMD_QUERY_EQ, nextSeq())

    /** 自定义 EQ 列表：空 payload → 0x8122。 */
    fun queryEqAll(): ByteArray = HemWire.build(CMD_QUERY_EQ_ALL, nextSeq())

    /** 自定义 EQ 写入：`<action> <tag> <id> <nameLen> <name> <bandCount> <bands...>`。 */
    fun saveEq(action: Int, preset: HemEq.Preset): ByteArray =
        HemWire.build(CMD_SAVE_EQ, nextSeq(), HemEq.encode(action, preset))

    /** 读取当前按键绑定：回复 0x8108。 */
    fun queryGestures(): ByteArray = HemWire.build(CMD_QUERY_KEY_FUNCTION, nextSeq())

    /** 贴合度检测：0x0405 [01 开始 / 00 停止]，结果走 0x0204 subType 0x04。 */
    /** 已配对/已连接设备列表：空 payload → 0x8112。 */
    fun queryDevices(): ByteArray = HemWire.build(CMD_QUERY_DEVICES, nextSeq())

    /** 个性化降噪：是否已有测试结果（`00 <exist>`）。 */
    fun queryPersonalNoise(): ByteArray = HemWire.build(CMD_QUERY_PNOISE, nextSeq())

    fun fitTest(on: Boolean): ByteArray =
        HemWire.build(CMD_FIT_TEST, nextSeq(), byteArrayOf(if (on) 0x01 else 0x00))

    /**
     * 写回按键绑定：payload `<count> [deviceType, button, buttonAction, function]...`。
     * 必须整表写回（不是增量），写完再读回比对。
     */
    fun setGestures(entries: List<IntArray>): ByteArray {
        val payload = ByteArray(1 + entries.size * 4)
        payload[0] = entries.size.toByte()
        entries.forEachIndexed { i, e ->
            val o = 1 + i * 4
            payload[o] = e[0].toByte()
            payload[o + 1] = e[1].toByte()
            payload[o + 2] = e[2].toByte()
            payload[o + 3] = e[3].toByte()
        }
        return HemWire.build(CMD_SET_KEY_FUNCTION, nextSeq(), payload)
    }

    fun queryAlertVolume(): ByteArray = HemWire.build(CMD_QUERY_ALERT_VOLUME, nextSeq())

    /** 0x0403 payload [id, on?1:0] */
    fun setFeature(id: Int, on: Boolean): ByteArray =
        HemWire.build(CMD_SET_FEATURE, nextSeq(), byteArrayOf(id.toByte(), if (on) 0x01 else 0x00))

    /** 0x0406 payload [presetId]（26 至臻原音 / 28 纯享人声 / 29 澎湃低音 / 34 活力动感） */
    fun setEqPreset(id: Int): ByteArray =
        HemWire.build(CMD_SET_EQ, nextSeq(), byteArrayOf(id.toByte()))

    /** 0x0427 payload [level 1..10] */
    fun setAlertVolume(level: Int): ByteArray =
        HemWire.build(CMD_SET_ALERT_VOLUME, nextSeq(), byteArrayOf(level.coerceIn(1, 10).toByte()))

    /**
     * 写降噪档：payload 01 01 <bit mask>，mask 为单个 bit（bit 序号见 [HemAnc]）。
     * 注意：写入本身会让耳机回一帧 0x0204 subType 03 的「回声」，其 payload 是
     * `03 02 01 <mask>`，不是真事件 —— 解析时必须校验 [1]==[2]==0x01。
     */
    fun setAnc(bit: Int): ByteArray {
        val mask = ByteArray(2 + bit / 8 + 1)
        mask[0] = 0x01
        mask[1] = 0x01
        mask[2 + bit / 8] = (1 shl (bit % 8)).toByte()
        return HemWire.build(CMD_SET_ANC, nextSeq(), mask)
    }
}
