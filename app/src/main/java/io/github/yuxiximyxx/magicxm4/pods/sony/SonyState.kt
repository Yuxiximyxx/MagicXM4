/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 索尼 WH-1000XM4 SPP 私有协议 —— 状态模型与应答解析。
 *
 * 本文件是「两个独立实现交叉验证」后的重写版，Kotlin 代码全部自行编写，
 * 只取用命令编号、字节偏移这类协议事实；**没有复制任何参考实现的代码**。
 *
 * ── 参考一：SonyHeadphonesClient（GPL-3.0-only）──────────────────────────
 *   https://github.com/Plutoberth/SonyHeadphonesClient
 *   Client/Headphones.cpp —— _ambientSoundControl / _asmLevel / _focusOnVoice 的组合语义，
 *   以及 isFocusOnVoiceAvailable()（环境声等级必须 > 2）。**它不解析任何应答**。
 *
 * ── 参考二：Gadgetbridge（AGPL-3.0）—— 仅取协议事实，未复制代码 ──────────
 *   https://codeberg.org/Freeyourgadget/Gadgetbridge
 *   app/.../protocol/impl/v1/SonyProtocolImplV1.java:
 *     · handleBattery                —— 电量应答解析（下标 1 类型 / 2 等级 / 3 充电）
 *     · handleAmbientSoundControl    —— 降噪应答解析（固定 8 字节，下标 2 启用 / 3 能力 /
 *                                       4 模式 / 6 聚焦人声 / 7 环境声等级）
 *     · handleFirmwareVersion        —— 固件应答（下标 1 固定 0x02 / 2 长度 / 3.. ASCII）
 *   app/.../sony/headphones/SonyHeadphonesProtocol.java —— ACK 语义（类型 0x01、空载荷）
 *   app/.../devices/.../SonyWH1000XM4Coordinator.java —— XM4 = 单总电量 + 风噪降低
 *
 * 解析策略（刻意的保守设计）：
 *   • 先看 msg.type：类型 0x01 直接归为 ACK（空载荷，不带业务状态）。
 *   • 再看 payload[0]（命令号）决定分支；不认识、长度不对、取值越界一律返回 Unknown，
 *     绝不「猜一个差不多」的字节当状态 —— 错误的状态会直接驱动 UI 和自动化逻辑。
 *   • 每条事件都带 raw（原始载荷 + 帧头类型/序号），现场只能靠「原样打印这一帧」反推。
 *   • 所有分支都不抛异常：这些代码跑在 SPP 收包线程里，抛异常等于断连。
 */
package io.github.yuxiximyxx.magicxm4.pods.sony

import io.github.yuxiximyxx.magicxm4.pods.sony.SonyCommands.NoiseMode
import io.github.yuxiximyxx.magicxm4.pods.sony.SonyWire.Message

/**
 * 降噪 / 环境声的当前状态。
 *
 * 采用四态 [mode] 而不是「ncOn / ambientOn 两个 bool」，是因为 XM4 有「风噪降低」这个
 * 独立档位（Gadgetbridge 的 XM4 coordinator 声明了 WindNoiseReduction 能力），
 * 用两个布尔表达不了三选一。
 *
 * @property ambientLevel 环境声等级 0..20；非环境声模式时设备通常回 0
 * @property focusOnVoice 聚焦人声；上游要求环境声开着且等级 > 2 才有意义
 */
data class SonyNoiseState(
    val mode: NoiseMode,
    val ambientLevel: Int,
    val focusOnVoice: Boolean,
) {
    /** 降噪是否开启。 */
    val ncOn: Boolean get() = mode == NoiseMode.NOISE_CANCELLING

    /** 环境声（通透）是否开启。 */
    val ambientOn: Boolean get() = mode == NoiseMode.AMBIENT_SOUND

    /** 风噪降低是否开启。 */
    val windReductionOn: Boolean get() = mode == NoiseMode.WIND_NOISE_REDUCTION

    /** 完全关闭。 */
    val off: Boolean get() = mode == NoiseMode.OFF
}

/**
 * WH-1000XM4 是**单总电量**（coordinator 能力 = BatterySingle），所以只有一个 level。
 * 左/右/盒子的多槽位是 TWS 的事。
 */
data class SonyBattery(
    val level: Int,
    val charging: Boolean,
)

/**
 * 一条消息解析出的状态增量。
 *
 * 每个分支都保留 [raw]，因为固件版本/风噪这类字段的语义还没有实机样本可对照。
 */
sealed class SonyEvent {

    abstract val raw: ByteArray

    /** ACK：类型 0x01、载荷为空。传输层据此放行下一条请求并采用它的 seq。 */
    data class Ack(
        val seq: Int,
        override val raw: ByteArray,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is Ack && seq == other.seq && raw.contentEquals(other.raw)

        override fun hashCode(): Int = seq * 31 + raw.contentHashCode()
    }

    /** 降噪/环境声状态。 */
    data class NoiseState(
        val state: SonyNoiseState,
        val dataType: Int,
        val seq: Int,
        override val raw: ByteArray,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is NoiseState && state == other.state && dataType == other.dataType &&
                seq == other.seq && raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            ((state.hashCode() * 31 + dataType) * 31 + seq) * 31 + raw.contentHashCode()
    }

    /** 电量。 */
    data class Battery(
        val battery: SonyBattery,
        val dataType: Int,
        val seq: Int,
        override val raw: ByteArray,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is Battery && battery == other.battery && dataType == other.dataType &&
                seq == other.seq && raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            ((battery.hashCode() * 31 + dataType) * 31 + seq) * 31 + raw.contentHashCode()
    }

    /** 固件版本，例如 "2.0.1"。 */
    data class Firmware(
        val version: String,
        val dataType: Int,
        val seq: Int,
        override val raw: ByteArray,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is Firmware && version == other.version && dataType == other.dataType &&
                seq == other.seq && raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            ((version.hashCode() * 31 + dataType) * 31 + seq) * 31 + raw.contentHashCode()
    }

    /**
     * INIT 应答：设备的协议版本探测结果。
     *
     * 实测（WH-1000XM4，2026-11-02 真机）：载荷 `01 00 70 00`，长度 4 → **V1**。
     * 这条应答是「设备进入可查询状态」的信号 —— 我们用它在连接后**再**发查询序列。
     */
    data class Init(
        val version: Int,
        val dataType: Int,
        val seq: Int,
        override val raw: ByteArray,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is Init && version == other.version && dataType == other.dataType &&
                seq == other.seq && raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            ((version * 31 + dataType) * 31 + seq) * 31 + raw.contentHashCode()
    }

    /**
     * 已识别但本项目不消费的消息（音量通知、协议版本探测等）。
     *
     * 为什么单独一类而不并进 [Unknown]：XM4 实测会每秒推一条 `A9 01 00`（音量通知），
     * 归到 Unknown 就会把日志刷满，真正该看的异常反而看不见。
     */
    data class Ignored(
        val reason: String,
        val dataType: Int,
        val seq: Int,
        override val raw: ByteArray,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is Ignored && reason == other.reason && dataType == other.dataType &&
                seq == other.seq && raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            ((dataType * 31 + seq) * 31 + reason.hashCode()) * 31 + raw.hashCode()
    }

    /** 不认识 / 结构不合法：只留给日志，不影响任何状态。 */
    data class Unknown(
        val dataType: Int,
        val seq: Int,
        override val raw: ByteArray,
        /** 人类可读的原因，直接进日志。 */
        val reason: String,
    ) : SonyEvent() {
        override fun equals(other: Any?): Boolean =
            other is Unknown && dataType == other.dataType && seq == other.seq &&
                reason == other.reason && raw.contentEquals(other.raw)

        override fun hashCode(): Int =
            ((dataType * 31 + seq) * 31 + reason.hashCode()) * 31 + raw.contentHashCode()
    }
}

/**
 * Message → SonyEvent 的分派。
 *
 * 分派锚点是「帧头类型 + 载荷第 0 字节」：
 *   · 帧头类型 0x01 → ACK（无载荷）
 *   · 载荷[0] 0x11 / 0x13 → 电量（应答 / 主动通知，布局相同）
 *   · 载荷[0] 0x67 / 0x69 → 降噪环境声（应答 / 主动通知，布局相同）
 *   · 载荷[0] 0x05        → 固件版本
 */
object SonyParser {

    /** 电量载荷最小长度：命令号 + 类型 + 等级 + 充电标志。 */
    private const val BATTERY_LEN = 4

    /** 降噪应答固定 8 字节（Gadgetbridge 对该长度做了硬校验）。 */
    private const val NOISE_LEN = 8

    /** 固件载荷最小长度：命令号 + 0x02 + 长度 + 至少 1 字节版本。 */
    private const val FW_MIN_LEN = 4

    fun parse(msg: Message): SonyEvent {
        val p = msg.payload

        // ACK 不带业务状态，先短路，避免空载荷被当成「畸形业务帧」反复报警。
        if (msg.type == SonyCommands.DATA_TYPE_ACK) {
            return SonyEvent.Ack(msg.seq, p)
        }
        if (p.isEmpty()) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "空载荷但不是 ACK 类型")
        }

        return when (val command = p[0].toInt() and 0xFF) {
            SonyCommands.Cmd.BATTERY_REPLY,
            SonyCommands.Cmd.BATTERY_NOTIFY,
            -> parseBattery(msg)

            SonyCommands.Cmd.AMBIENT_SOUND_CONTROL_RET,
            SonyCommands.Cmd.AMBIENT_SOUND_CONTROL_NOTIFY,
            -> parseNoise(msg)

            SonyCommands.Cmd.FW_VERSION_REPLY -> parseFirmware(msg)

            // 4 字节 = V1（XM4），8 字节 = V2。
            SonyCommands.Cmd.INIT_REPLY -> SonyEvent.Init(
                version = if (p.size >= 8) 2 else 1,
                dataType = msg.type,
                seq = msg.seq,
                raw = p,
            )

            SonyCommands.Cmd.VOLUME_RET,
            SonyCommands.Cmd.VOLUME_NOTIFY,
            -> SonyEvent.Ignored("音量通知", msg.type, msg.seq, p)

            else -> SonyEvent.Unknown(msg.type, msg.seq, p, "未识别的命令号 0x" + hexByte(command))
        }
    }

    /** 便捷入口：直接吃一整帧原始线路字节（内部先过 [SonyWire.parse]）。 */
    fun parseFrame(frame: ByteArray): SonyEvent? = SonyWire.parse(frame)?.let { parse(it) }

    /**
     * 电量应答：`11 <type> <level> <charging>`（0x13 主动通知同布局）。
     *
     * 偏移依据 Gadgetbridge handleBattery：
     *   [1] BatteryType：0x00 单总电量 / 0x01 双电量 / 0x02 充电盒
     *   [2] 电量百分比（直接是 0..100）
     *   [3] 充电标志：1 = 充电中，其它 = 未充电
     *
     * XM4 只会走 SINGLE（单总电量）。CASE 与 SINGLE 同布局，一并接受；
     * DUAL 是 TWS 的左右双电量布局（等级在 [2]/[4]、充电标志在 [3]/[5]），
     * XM4 收不到，所以这里不解析，返回 Unknown 而不是硬套单电量偏移。
     */
    private fun parseBattery(msg: Message): SonyEvent {
        val p = msg.payload
        if (p.size < BATTERY_LEN) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "电量载荷只有 ${p.size} 字节，至少需要 $BATTERY_LEN")
        }
        val type = SonyCommands.BatteryType.from(p[1].toInt() and 0xFF)
            ?: return SonyEvent.Unknown(
                msg.type, msg.seq, p, "未知电量类型 0x" + hexByte(p[1].toInt() and 0xFF),
            )
        if (type == SonyCommands.BatteryType.DUAL) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "双电量（L/R）布局，XM4 是单总电量，不解析")
        }

        val level = p[2].toInt() and 0xFF
        if (level > 100) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "电量 $level 超出 0..100")
        }
        // 充电标志只认 1（参考实现的判定就是 ==1），其它值一律视为未充电。
        val charging = (p[3].toInt() and 0xFF) == 1

        return SonyEvent.Battery(
            battery = SonyBattery(level = level, charging = charging),
            dataType = msg.type,
            seq = msg.seq,
            raw = p,
        )
    }

    /**
     * 降噪/环境声应答：`67 ...`（0x69 主动通知同布局），固定 8 字节。
     *
     * 偏移依据 Gadgetbridge handleAmbientSoundControl：
     *   [0] 0x67 / 0x69
     *   [1] 0x02（对象：降噪 + 环境声组合）
     *   [2] 启用：0x00 = 全关，0x01 = 开启
     *   [3] 能力标志：0x00 = 只支持 ANC/环境声，0x02 = 支持风噪降低（XM4）
     *   [4] 模式位：能力=0x00 时 0=环境声 1=降噪；能力=0x02 时 0=环境声 1=风噪降低 2=降噪
     *   [5] 固定 0x01
     *   [6] 聚焦人声 0x00/0x01
     *   [7] 环境声等级 0..20
     */
    private fun parseNoise(msg: Message): SonyEvent {
        val p = msg.payload
        if (p.size != NOISE_LEN) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "降噪载荷应固定 $NOISE_LEN 字节，实际 ${p.size}")
        }

        val enabled = p[2].toInt() and 0xFF
        if (enabled != 0x00 && enabled != 0x01) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "未知启用标志 0x" + hexByte(enabled))
        }

        val mode = if (enabled == 0x00) {
            NoiseMode.OFF
        } else {
            SonyCommands.decodeNoiseMode(p[3].toInt() and 0xFF, p[4].toInt() and 0xFF)
                ?: return SonyEvent.Unknown(
                    msg.type, msg.seq, p,
                    "无法从 能力标志 0x" + hexByte(p[3].toInt() and 0xFF) +
                        " / 模式位 0x" + hexByte(p[4].toInt() and 0xFF) + " 判定降噪模式",
                )
        }

        // 参考实现只接受 0/1，其它值视为畸形（不是「非零即真」）。
        val voiceByte = p[6].toInt() and 0xFF
        if (voiceByte != 0x00 && voiceByte != 0x01) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "未知聚焦人声标志 0x" + hexByte(voiceByte))
        }

        val level = p[7].toInt() and 0xFF
        if (level > SonyCommands.MAX_ASM_LEVEL) {
            // 关闭时设备可能回 0xFF 之类的无效值，按「等级不可用」拒绝，但不影响 mode。
            return SonyEvent.Unknown(
                msg.type, msg.seq, p,
                "环境声等级 $level 超出 0..${SonyCommands.MAX_ASM_LEVEL}",
            )
        }

        return SonyEvent.NoiseState(
            state = SonyNoiseState(
                mode = mode,
                ambientLevel = level,
                // 上游 isFocusOnVoiceAvailable()：只有环境声模式 + 等级 > 2 才有意义。
                focusOnVoice = mode == NoiseMode.AMBIENT_SOUND &&
                    voiceByte == 0x01 &&
                    level > SonyCommands.MIN_VOICE_FOCUS_STEP,
            ),
            dataType = msg.type,
            seq = msg.seq,
            raw = p,
        )
    }

    /**
     * 固件版本应答：`05 02 <len> <ASCII 版本串>`。
     *
     * 偏移依据 Gadgetbridge handleFirmwareVersion：
     *   [1] 必须固定 0x02（否则不是固件应答，可能被别的 0x05 命令复用）
     *   [2] 版本串长度
     *   [3..] ASCII 版本串，字符集限制为数字 / 字母 / `.` `-` `_`
     */
    private fun parseFirmware(msg: Message): SonyEvent {
        val p = msg.payload
        if (p.size < FW_MIN_LEN) {
            return SonyEvent.Unknown(msg.type, msg.seq, p, "固件载荷只有 ${p.size} 字节，至少需要 $FW_MIN_LEN")
        }
        if ((p[1].toInt() and 0xFF) != 0x02) {
            return SonyEvent.Unknown(
                msg.type, msg.seq, p, "固件应答下标 1 应为 0x02，实际 0x" + hexByte(p[1].toInt() and 0xFF),
            )
        }
        val declared = p[2].toInt() and 0xFF
        val body = p.copyOfRange(3, p.size)
        if (body.size != declared) {
            return SonyEvent.Unknown(
                msg.type, msg.seq, p, "固件版本串长度 ${body.size} 与声明的 $declared 不符",
            )
        }
        if (!body.all { isVersionChar(it) }) {
            return SonyEvent.Unknown(
                msg.type, msg.seq, p, "固件版本串含非法字符：" + SonyWire.hex(body),
            )
        }

        return SonyEvent.Firmware(
            version = String(body, Charsets.US_ASCII),
            dataType = msg.type,
            seq = msg.seq,
            raw = p,
        )
    }

    /** 版本串允许的字符集，与参考实现的正则 `^[0-9.\-a-zA-Z_]+$` 等价。 */
    private fun isVersionChar(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in 0x30..0x39 ||          // 0-9
            c in 0x41..0x5A ||             // A-Z
            c in 0x61..0x7A ||             // a-z
            c == 0x2E || c == 0x2D || c == 0x5F   // . - _
    }

    /** 单字节十六进制，日志用；不用 String.format 是为了避开 Locale 影响。 */
    private fun hexByte(value: Int): String =
        "0123456789ABCDEF"[value shr 4 and 0x0F].toString() + "0123456789ABCDEF"[value and 0x0F]
}
