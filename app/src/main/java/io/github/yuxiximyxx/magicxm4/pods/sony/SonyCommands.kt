/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 索尼 WH-1000XM4 SPP 私有协议 —— 命令表与载荷布局。
 *
 * 本文件是两个独立实现的「协议事实」交叉验证后的重写版。代码全部由本项目自行编写，
 * 只取用命令编号、字节偏移、常量数值这类接口规范；**没有复制任何参考实现的代码**。
 *
 * ── 参考一：SonyHeadphonesClient（GPL-3.0-only）──────────────────────────
 *   https://github.com/Plutoberth/SonyHeadphonesClient
 *   Client/Constants.h            —— DATA_TYPE / NC_ASM_* / ASM_* / COMMAND_TYPE / ASM_ID
 *   Client/CommandSerializer.cpp  —— getDualSingleForAsmLevel、serializeNcAndAsmSetting、
 *                                    serializeVPTSetting
 *   Client/Headphones.cpp         —— setChanges()：把「想要的 NC/ASM 状态」翻译成一条命令
 *   Client/BluetoothWrapper.cpp   —— sendCommand()：出站 DATA_TYPE=DATA_MDR(12)，seq 自增，
 *                                    收到应答后采用设备 seq
 *   已知缺陷（本文件据此做了修正）：它只做「设置」方向，没有查询/电量/应答解析。
 *
 * ── 参考二：Gadgetbridge（AGPL-3.0）—— 仅取协议事实，未复制代码 ──────────
 *   https://codeberg.org/Freeyourgadget/Gadgetbridge
 *   app/.../sony/headphones/protocol/impl/v1/PayloadTypeV1.java   —— 命令号总表
 *   app/.../sony/headphones/protocol/impl/v1/SonyProtocolImplV1.java
 *       · setAmbientSoundControl / getAmbientSoundControl / getBattery /
 *         getFirmwareVersion / handleAmbientSoundControl / handleBattery /
 *         handleFirmwareVersion / supportsWindNoiseCancelling
 *   app/.../sony/headphones/protocol/MessageType.java、Message.java —— 帧格式（交叉验证）
 *   app/.../sony/headphones/SonyHeadphonesProtocol.java、SonyHeadphonesIoThread.java
 *       —— ACK 语义、INIT 请求、请求排队
 *   app/.../devices/sony/headphones/coordinators/SonyWH1000XM4Coordinator.java
 *       —— XM4 的能力集合（BatterySingle + AmbientSoundControl + WindNoiseReduction）
 *
 * ── 两个参考的冲突点（必须显式处理，真机一次 A/B 就能定）─────────────────
 *   NCASM 设置载荷的第 4 个字节（0 基下标 3）语义不同：
 *     · HeadphonesClient：0x01 = NC_ASM_SETTING_TYPE.LEVEL_ADJUSTMENT，
 *       第 5 字节（下标 4）= 由环境声等级反推的 dualSingle（OFF/SINGLE/DUAL）。
 *     · Gadgetbridge：0x02 = 「支持风噪降低」能力标志，
 *       第 5 字节（下标 4）= 模式位（环境声 0 / 风噪降低 1 / 降噪 2）。
 *   两者只差下标 3 的语义，所以这里做成 [NcAsmLayout]，默认取 XM4 的 WIND_CAPABLE
 *   （Gadgetbridge 的 XM4 coordinator 明确声明 WindNoiseReduction），
 *   但保留 LEGACY_DUAL_SINGLE 以便真机对照。
 */
package io.github.yuxiximyxx.magicxm4.pods.sony

import io.github.yuxiximyxx.magicxm4.pods.sony.SonyWire.Message

object SonyCommands {

    // ── 命令号总表（载荷第 0 字节）──────────────────────────────
    //
    // 出处：Gadgetbridge PayloadTypeV1.java（AGPL-3.0，仅取编号事实）。
    // 与 SonyHeadphonesClient 的命令号对照关系见各组注释——两边一致的项可信度最高。
    object Cmd {
        /** INIT_REQUEST：连接后探测协议版本（V1/V2）。载荷 `00 00`。 */
        const val INIT_REQUEST = 0x00

        /** INIT_REPLY：载荷 `01 ...`，长度 4 = V1（XM4/WH-1000XM3），长度 8 = V2。 */
        const val INIT_REPLY = 0x01

        /** FW_VERSION_REQUEST：固件版本查询。载荷 `04 02`。 */
        const val FW_VERSION_REQUEST = 0x04

        /** FW_VERSION_REPLY：载荷 `05 02 <len> <ASCII 版本串>`。 */
        const val FW_VERSION_REPLY = 0x05

        /** INIT_2_REQUEST：第二次 init（本项目不使用）。 */
        const val INIT_2_REQUEST = 0x06

        /** INIT_2_REPLY。 */
        const val INIT_2_REPLY = 0x07

        /** 电量查询。载荷 `10 <batteryType>`；XM4 用 `10 00`（单总电量）。 */
        const val BATTERY_REQUEST = 0x10

        /** 电量应答：`11 <type> <level> <charging>`。 */
        const val BATTERY_REPLY = 0x11

        /** 电量主动通知：布局与 [BATTERY_REPLY] 完全相同。 */
        const val BATTERY_NOTIFY = 0x13

        /** 降噪/环境声查询。载荷 `66 02`。 */
        const val AMBIENT_SOUND_CONTROL_GET = 0x66

        /** 降噪/环境声应答（8 字节，见 SonyParser）。 */
        const val AMBIENT_SOUND_CONTROL_RET = 0x67

        /**
         * 降噪/环境声设置（8 字节）。
         *
         * 与 SonyHeadphonesClient `COMMAND_TYPE::NCASM_SET_PARAM = 104` 是同一个号，
         * 两个独立实现一致 —— 这条命令的编号可以放心。
         */
        const val AMBIENT_SOUND_CONTROL_SET = 0x68

        /** 降噪/环境声主动通知：布局与 [AMBIENT_SOUND_CONTROL_RET] 相同。 */
        const val AMBIENT_SOUND_CONTROL_NOTIFY = 0x69

        /**
         * 环绕声/声场系列。本项目暂不使用，仅留全表以便将来扩展。
         *
         * 注意：SonyHeadphonesClient 的 `COMMAND_TYPE::VPT_SET_PARAM = 72 = 0x48`
         * 与这里的 SET 号完全重合 —— 又一处理论层面的交叉验证。
         */
        const val SOUND_POSITION_OR_MODE_GET = 0x46
        const val SOUND_POSITION_OR_MODE_RET = 0x47
        const val SOUND_POSITION_OR_MODE_SET = 0x48
        const val SOUND_POSITION_OR_MODE_NOTIFY = 0x49

        // 音量命令组。本项目不实现音量控制，但 XM4 会**主动**推 VOLUME_NOTIFY（实测约每秒一次），
        // 不显式识别就会被当成「未识别命令」反复刷日志，把真正的异常淹掉。
        const val VOLUME_GET = 0xA6
        const val VOLUME_RET = 0xA7
        const val VOLUME_SET = 0xA8
        const val VOLUME_NOTIFY = 0xA9
    }

    // ── DATA_TYPE（帧头第 1 字节）──────────────────────────────

    /**
     * 帧头第一个字节。取值逐字对应 SonyHeadphonesClient Constants.h:22-41。
     *
     * 交叉验证：Gadgetbridge MessageType.java 只有 ACK(0x01)、COMMAND_1(0x0c)、
     * COMMAND_2(0x0e) 三个值 —— 其中 ACK 与 DATA_MDR 与我们完全对应，
     * 说明「出站一律 DATA_MDR」这个结论是两边一致的。
     */
    enum class DataType(val v: Int) {
        DATA(0),
        ACK(1),
        DATA_MC_NO1(2),
        DATA_ICD(9),
        DATA_EV(10),
        DATA_MDR(12),
        DATA_COMMON(13),
        DATA_MDR_NO2(14),
        SHOT(16),
        SHOT_MC_NO1(18),
        SHOT_ICD(25),
        SHOT_EV(26),
        SHOT_MDR(28),
        SHOT_COMMON(29),
        SHOT_MDR_NO2(30),
        LARGE_DATA_COMMON(45),
        UNKNOWN(-1);

        companion object {
            fun from(v: Int): DataType = entries.firstOrNull { it.v == v } ?: UNKNOWN
        }
    }

    /** 出站帧一律 DATA_MDR(12) = Gadgetbridge 的 COMMAND_1(0x0C)。 */
    val OUTGOING_DATA_TYPE: DataType = DataType.DATA_MDR

    /** ACK 的 DATA_TYPE：无载荷，seq 语义见文件末尾说明。 */
    const val DATA_TYPE_ACK: Int = 1

    // ── 电量类型（电池槽位）────────────────────────────────────

    /**
     * 电量查询/应答里的 batteryType。
     *
     * 编码事实取自 Gadgetbridge decodeBatteryType / encodeBatteryType（0x00/0x01/0x02）。
     * 注意：Gadgetbridge 的枚举里还有个 `DUAL2`，但它在线上的编解码分支里**没有**对应编号，
     * 所以这里不收录，避免造一个不存在的值。
     *
     * XM4 的能力集合是 `BatterySingle` → 固定用 [SINGLE]。
     */
    enum class BatteryType(val v: Int) {
        /** 单总电量（XM4 / 头戴式）。 */
        SINGLE(0x00),

        /** 双电量（TWS 左右）。 */
        DUAL(0x01),

        /** 充电盒。 */
        CASE(0x02);

        companion object {
            fun from(v: Int): BatteryType? = entries.firstOrNull { it.v == v }
        }
    }

    // ── 降噪模式（比 SonyHeadphonesClient 多一档「风噪降低」）────

    /**
     * 降噪/环境声的工作模式。
     *
     * SonyHeadphonesClient 只有「开/关 + 等级」的概念；Gadgetbridge 在 XM4 这类
     * 支持风噪降低的机型上把模式拆成四态。本项目采用四态，因为 XM4 的 coordinator
     * 确实声明了 WindNoiseReduction 能力。
     */
    enum class NoiseMode {
        /** 全关。 */
        OFF,

        /** 环境声（可调等级 1..20）。 */
        AMBIENT_SOUND,

        /** 风噪降低（XM4 特有档位）。 */
        WIND_NOISE_REDUCTION,

        /** 降噪。 */
        NOISE_CANCELLING,
    }

    // ── 两种设置载荷布局 ───────────────────────────────────────

    /**
     * NCASM 设置载荷的下标 3 / 下标 4 语义选择。
     *
     * 这是本项目唯一「两套参考互相矛盾」的地方，做成可配置是因为真机一次 A/B 就能定论，
     * 现在赌任何一个都没有收益。
     *
     * @property capabilityByte 写进载荷下标 3 的值
     * @property offLevel 关闭时写进下标 7 的等级字节（两个参考在此不一致，各自沿用原有行为）
     */
    enum class NcAsmLayout(val capabilityByte: Int, val offLevel: Int, val label: String) {
        /**
         * 支持风噪降低（XM4 应走这条）：下标 3 = 0x02 能力标志，
         * 下标 4 = 模式位（环境声 0 / 风噪降低 1 / 降噪 2）。
         * 关闭时等级字节按 Gadgetbridge 的行为写 0x00。
         */
        WIND_CAPABLE(0x02, 0x00, "风噪能力布局（Gadgetbridge / XM4）"),

        /**
         * 旧 dualSingle 布局（SonyHeadphonesClient，XM3 实机验证过）：
         * 下标 3 = 0x01（NC_ASM_SETTING_TYPE.LEVEL_ADJUSTMENT），
         * 下标 4 = 由等级反推的 dualSingle（0/1/2）。
         * 关闭时等级字节写 0xFF（ASM_LEVEL_DISABLED），这也是 SonyHeadphonesClient 的实证行为。
         */
        LEGACY_DUAL_SINGLE(0x01, 0xFF, "旧 dualSingle 布局（SonyHeadphonesClient / XM3）"),
    }

    // ── 数值常量 ────────────────────────────────────────────────

    /** 关闭环境声时的「等级无效值」（SonyHeadphonesClient CommandSerializer.h:10）。 */
    const val ASM_LEVEL_DISABLED: Int = 0xFF

    /** 聚焦人声的最低环境声等级（SonyHeadphonesClient CommandSerializer.h:9）。 */
    const val MIN_VOICE_FOCUS_STEP: Int = 2

    /**
     * 环境声等级上限。
     *
     * Gadgetbridge handleAmbientSoundControl 明确校验 `0..20`（XM4 就是 20 档）；
     * SonyHeadphonesClient 的常量叫 MAX_STEPS_WH_1000_XM3 = 19（XM3 档位），
     * 且对 >19 直接抛异常，所以**上游代码在 XM4 的第 20 档会失败**。这里取 20。
     */
    const val MAX_ASM_LEVEL: Int = 20

    // ── 序号 ────────────────────────────────────────────────────

    /**
     * 1 字节序号发生器。
     *
     * 两个参考在序号策略上完全一致：
     *   · SonyHeadphonesClient BluetoothWrapper.cpp:28/:96 —— 发命令用当前 seq 并 +1，
     *     收到应答后用设备回显的 seq 覆盖；断连归零。
     *   · Gadgetbridge SonyHeadphonesProtocol —— `sequenceNumber = message.getSequenceNumber()`。
     * 另外 Gadgetbridge 对「设备发来的消息」回 ACK 时把 seq 取反（`1 - seq`），
     * 本项目把这件事留给传输层，不在协议层自动生成。
     */
    class SeqCounter(start: Int = 0) {
        var value: Int = start and 0xFF
            private set

        fun next(): Int {
            val current = value
            value = (value + 1) and 0xFF
            return current
        }

        fun adopt(deviceSeq: Int) {
            value = deviceSeq and 0xFF
        }

        /**
         * 取当前序号但**不递增**。
         *
         * 2026-11-02 真机实证：XM4 只认设备自己回执过的那个 seq。我们一开始按「每发一条 +1」
         * 递增到 3/4/5/6，结果所有 INIT 都收不到 ACK，查询也一条不回；而计数器还在 0/1 时
         * INIT 是能拿到应答的。Gadgetbridge 的做法就是「全程用同一个 sequenceNumber，
         * 只在收到 ACK 时采纳设备回的号」——所以对外发送一律用这个函数。
         */
        fun current(): Int = value

        fun reset() {
            value = 0
        }
    }

    // ── 载荷构造：NC/ASM 设置 ───────────────────────────────────

    /**
     * 由环境声等级反推 dualSingle（SonyHeadphonesClient getDualSingleForAsmLevel，逐行移植）。
     *
     * 只在 [NcAsmLayout.LEGACY_DUAL_SINGLE] 布局下使用。
     *   level == 1 -> SINGLE(1)
     *   level == 0 -> DUAL(2)
     *   其它（含 0xFF 无效值、2..20）-> OFF(0)
     * 不要因为「看起来不合理」就去改，这是唯一有源码依据的行为。
     */
    fun dualSingleForAsmLevel(asmLevel: Int): Int = when (asmLevel) {
        1 -> 0x01
        0 -> 0x02
        else -> 0x00
    }

    /**
     * NCASM 设置载荷，8 字节定长：
     *
     *   [0] 0x68  AMBIENT_SOUND_CONTROL_SET
     *   [1] 0x02  对象：降噪 + 环境声组合
     *   [2]       0x00 = 关闭；0x11 = 开启（含「正在调整 / 调整完成」的语义）
     *   [3]       布局相关，见 [NcAsmLayout.capabilityByte]
     *   [4]       布局相关：风噪能力布局 = 模式位；旧布局 = dualSingle
     *   [5] 0x01  固定（两个参考都写 0x01，含义未标注，照抄）
     *   [6]       聚焦人声 0x00/0x01
     *   [7]       环境声等级；降噪/风噪降低时为 0，关闭时见 [NcAsmLayout.offLevel]
     *
     * @param ambientLevel 仅 AMBIENT_SOUND / OFF 有效；AMBIENT_SOUND 时要求 1..20
     */
    fun ncAsmSetPayload(
        mode: NoiseMode,
        layout: NcAsmLayout = NcAsmLayout.WIND_CAPABLE,
        focusOnVoice: Boolean = false,
        ambientLevel: Int = 0,
    ): ByteArray {
        if (mode == NoiseMode.AMBIENT_SOUND) {
            require(ambientLevel in 1..MAX_ASM_LEVEL) {
                "环境声模式要求等级 1..$MAX_ASM_LEVEL，收到 $ambientLevel"
            }
        }

        // 等级字节：降噪/风噪降低恒为 0（两个参考一致）。
        val levelByte = when (mode) {
            NoiseMode.AMBIENT_SOUND -> ambientLevel
            NoiseMode.NOISE_CANCELLING, NoiseMode.WIND_NOISE_REDUCTION -> 0x00
            NoiseMode.OFF -> layout.offLevel
        }

        // 下标 4：风噪能力布局写模式位，旧布局写由等级反推的 dualSingle。
        val modeOrDual = when (layout) {
            NcAsmLayout.WIND_CAPABLE -> when (mode) {
                NoiseMode.AMBIENT_SOUND -> 0x00
                NoiseMode.WIND_NOISE_REDUCTION -> 0x01
                NoiseMode.NOISE_CANCELLING -> 0x02
                NoiseMode.OFF -> 0x00
            }

            NcAsmLayout.LEGACY_DUAL_SINGLE ->
                dualSingleForAsmLevel(if (levelByte == ASM_LEVEL_DISABLED) -1 else levelByte)
        }

        return byteArrayOf(
            Cmd.AMBIENT_SOUND_CONTROL_SET.toByte(),
            0x02,
            (if (mode == NoiseMode.OFF) 0x00 else 0x11).toByte(),
            layout.capabilityByte.toByte(),
            modeOrDual.toByte(),
            0x01,
            (if (focusOnVoice) 0x01 else 0x00).toByte(),
            levelByte.toByte(),
        )
    }

    /**
     * 把应答里的（下标 3 能力标志，下标 4 模式位）反解成 [NoiseMode]。
     *
     * 依据 Gadgetbridge handleAmbientSoundControl：
     *   下标 3 == 0x00（只支持 ANC/环境声）: 下标 4 0=环境声 1=降噪
     *   下标 3 == 0x02（支持风噪降低）    : 下标 4 0=环境声 1=风噪降低 2=降噪
     * 其它组合返回 null（调用方按 Unknown 处理，绝不猜）。
     */
    fun decodeNoiseMode(capabilityByte: Int, modeByte: Int): NoiseMode? = when (capabilityByte) {
        0x00 -> when (modeByte) {
            0x00 -> NoiseMode.AMBIENT_SOUND
            0x01 -> NoiseMode.NOISE_CANCELLING
            else -> null
        }

        0x02 -> when (modeByte) {
            0x00 -> NoiseMode.AMBIENT_SOUND
            0x01 -> NoiseMode.WIND_NOISE_REDUCTION
            0x02 -> NoiseMode.NOISE_CANCELLING
            else -> null
        }

        else -> null
    }

    // ── 命令构造（可直接发送的 Message）─────────────────────────

    private fun message(
        payload: ByteArray,
        seq: Int,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message = Message(type = dataType.v, seq = seq and 0xFF, payload = payload)

    /** 连接后探测协议版本的 INIT 请求。载荷 `00 00`。 */
    fun initRequest(seq: Int, dataType: DataType = OUTGOING_DATA_TYPE): Message =
        message(byteArrayOf(Cmd.INIT_REQUEST.toByte(), 0x00), seq, dataType)

    /** 电量查询。载荷 `10 <batteryType>`；XM4 用默认 `10 00`。 */
    fun batteryRequest(
        seq: Int,
        batteryType: BatteryType = BatteryType.SINGLE,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message = message(
        byteArrayOf(Cmd.BATTERY_REQUEST.toByte(), batteryType.v.toByte()),
        seq,
        dataType,
    )

    /** 固件版本查询。载荷 `04 02`。 */
    fun firmwareRequest(seq: Int, dataType: DataType = OUTGOING_DATA_TYPE): Message =
        message(byteArrayOf(Cmd.FW_VERSION_REQUEST.toByte(), 0x02), seq, dataType)

    /**
     * 降噪/环境声查询。载荷 `66 02`。
     *
     * 第二个字节 0x02 与设置命令的下标 1 同值（组合对象），两个参考一致。
     */
    fun queryNoiseState(seq: Int, dataType: DataType = OUTGOING_DATA_TYPE): Message =
        message(byteArrayOf(Cmd.AMBIENT_SOUND_CONTROL_GET.toByte(), 0x02), seq, dataType)

    /** 关闭降噪与环境声。 */
    fun setOff(
        seq: Int,
        layout: NcAsmLayout = NcAsmLayout.WIND_CAPABLE,
        focusOnVoice: Boolean = false,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message = message(
        ncAsmSetPayload(NoiseMode.OFF, layout, focusOnVoice),
        seq,
        dataType,
    )

    /** 开环境声并设定等级（1..20）。 */
    fun setAmbientSound(
        seq: Int,
        level: Int,
        focusOnVoice: Boolean = false,
        layout: NcAsmLayout = NcAsmLayout.WIND_CAPABLE,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message {
        require(level in 1..MAX_ASM_LEVEL) { "环境声等级必须在 1..$MAX_ASM_LEVEL，收到 $level" }
        // 上游 isFocusOnVoiceAvailable()：等级必须 > 2 才允许聚焦人声，否则设备会忽略。
        val voice = focusOnVoice && level > MIN_VOICE_FOCUS_STEP
        return message(
            ncAsmSetPayload(NoiseMode.AMBIENT_SOUND, layout, voice, level),
            seq,
            dataType,
        )
    }

    /** 开降噪。 */
    fun setNoiseCancelling(
        seq: Int,
        focusOnVoice: Boolean = false,
        layout: NcAsmLayout = NcAsmLayout.WIND_CAPABLE,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message = message(
        ncAsmSetPayload(NoiseMode.NOISE_CANCELLING, layout, focusOnVoice),
        seq,
        dataType,
    )

    /** 开风噪降低（XM4 特有档位）。 */
    fun setWindNoiseReduction(
        seq: Int,
        focusOnVoice: Boolean = false,
        layout: NcAsmLayout = NcAsmLayout.WIND_CAPABLE,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message = message(
        ncAsmSetPayload(NoiseMode.WIND_NOISE_REDUCTION, layout, focusOnVoice),
        seq,
        dataType,
    )

    /** 通用入口：按模式设置，环境声模式下需要传等级。 */
    fun setNoiseMode(
        seq: Int,
        mode: NoiseMode,
        level: Int = 0,
        focusOnVoice: Boolean = false,
        layout: NcAsmLayout = NcAsmLayout.WIND_CAPABLE,
        dataType: DataType = OUTGOING_DATA_TYPE,
    ): Message = message(
        ncAsmSetPayload(mode, layout, focusOnVoice, level),
        seq,
        dataType,
    )

    // ── 连接后的初始命令 ────────────────────────────────────────

    /**
     * 连接后的初始化命令序列。
     *
     * 两个参考在这一点上**不一致**，所以做成开关而不是替用户决定：
     *   · SonyHeadphonesClient：不发 INIT，连上后第一条业务命令就是第一条 SPP 写，
     *     在 XM3/XM4 上都能工作（这也是默认关闭它的原因——少一步就少一个失败点）。
     *   · Gadgetbridge：连接后立刻发 `00 00`（INIT_REQUEST，失败重试 2 次），
     *     用应答载荷长度区分协议版本（4 字节 = V1，XM4 走 V1；8 字节 = V2）。
     *
     * @param sendInit 是否包含 INIT 请求，默认 false（保持 SonyHeadphonesClient 的行为）
     */
    fun initSequence(sendInit: Boolean = false): List<Message> =
        if (sendInit) listOf(initRequest(0)) else emptyList()

    /** 连接后建议刷新的状态查询序列：降噪/环境声 → 电量 → 固件版本。 */
    fun initialQueries(startSeq: Int = 0): List<Message> {
        val seq = startSeq and 0xFF
        return listOf(
            queryNoiseState(seq),
            batteryRequest((seq + 1) and 0xFF),
            firmwareRequest((seq + 2) and 0xFF),
        )
    }

    /*
     * ── ACK 语义（传输层要用，记录在此以免丢失）─────────────────────────
     * 出处：Gadgetbridge SonyHeadphonesProtocol / SonyHeadphonesIoThread。
     *   · ACK 消息 = DATA_TYPE 0x01，载荷为空（长度 0），seq = 被确认请求的 seq。
     *   · 收到 ACK 后：`sequenceNumber = ack.seq`，然后放行队列里的下一条请求
     *     （Gadgetbridge 一次只发一条，等 ACK 再发下一条）。
     *   · 反过来，若设备主动发来非 ACK 消息，手机要回一个 seq = `1 - seq` 的空 ACK。
     * 本项目协议层不自动生成 ACK，交给传输层；SonyParser 会把 ACK 归到 SonyEvent.Ack。
     *
     * ── 仍未实现/未验证的项（不是漏了）───────────────────────────────────
     * 1. 固件版本以外的身份信息（型号、序列号）。
     * 2. 环绕声/声场（0x46..0x49，SonyHeadphonesClient 的 VPT）—— 未接线。
     * 3. 均衡器、音量、触控、自动关机、语音提示等 —— 参考实现有，本项目不需要。
     * 4. JSON（0xc4）通道 —— Gadgetbridge 里也标着 TODO。
     * 5. 超长帧分块：两个参考都不做，本实现按 2048 上限直接拒绝。
     * 6. 双电量（DUAL）应答的左右布局 —— XM4 是单总电量，用不到，见 SonyParser。
     */
}
