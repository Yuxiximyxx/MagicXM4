/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 降噪档 ↔ 位掩码映射（WH-1000XM4 / 丹拿版）。
 *
 * 依据：
 *  - XM4 的机型表：QuickBuds/app/src/main/assets/models.json:108（普通版 068C10）
 *    与 :120（丹拿版 06C010），两版 ancModes 完全相同。
 *  - 上报/写入双表：QuickBuds/protocol/AncModes.kt:55-93（SET 用 slot，上报按树查）。
 *  - 「ANC 打开时停在上次档位」实测：QuickBuds/protocol/Anc.kt:22-43
 *    （0x0010 = Deep bit4，0x0020 = Medium bit5）。
 *
 * XM4 的 ANC 树（SET slot / 上报 bit）：
 *   Off            slot 0   （上报子位 3）
 *   Transparency   slot 2   （上报子位 8）
 *   ANC            slot 1   ← 父节点，上报 0x0002 时只表示「在降噪」
 *     ├ Deep       slot 4
 *     ├ Medium     slot 5
 *     ├ Light      slot 6
 *     └ Smart      slot 7
 *   Adaptive      slot 11
 *
 * 荣耀侧 status（MagicXM4 内部值，见 hook/ControlCenterNoiseHook.kt）：
 *   1 = 关闭, 2 = 通透, 3 = NORMAL, 4 = DEEP, 5 = EXPERIMENT（面板「降噪」）, 6 = 抗风噪
 */
package io.github.yuxiximyxx.magicxm4.pods.hem

object HemAnc {

    const val BIT_OFF = 0
    const val BIT_ANC_PARENT = 1
    const val BIT_TRANSPARENCY = 2
    const val BIT_ANC_DEEP = 4
    const val BIT_ANC_MEDIUM = 5
    const val BIT_ANC_LIGHT = 6
    const val BIT_ANC_SMART = 7
    const val BIT_ADAPTIVE = 11

    /** 上报专用的子位（不是额外的档位，只是同一档位的另一种上报值）。 */
    private const val RPT_OFF_CHILD = 3
    private const val RPT_TRANSPARENCY_CHILD = 8

    /**
     * 荣耀 status → 欢律 SET bit。
     *
     * 「降噪」走 Medium（bit 5）：QuickBuds 实测「ANC on」上报 0x0020 = Medium，
     * 是这套固件默认恢复到的那一档，听感上最接近荣耀面板的「降噪」。
     * 需要改成 Deep / Light 只改这一个地方。
     */
    fun bitForStatus(status: Int): Int? = when (status) {
        1 -> BIT_OFF
        2 -> BIT_TRANSPARENCY
        3, 5, 6 -> BIT_ANC_MEDIUM
        4 -> BIT_ANC_DEEP
        7 -> BIT_ADAPTIVE          // 自适应（本模块扩展状态，用于详情页四档）
        else -> null
    }

    /** 欢律上报值（位掩码 LE 解出的整数）→ 荣耀 status；未知值返回 null（不改状态）。 */
    fun statusForRaw(raw: Int): Int? {
        if (raw <= 0) return null
        val bit = Integer.numberOfTrailingZeros(raw)
        return when (bit) {
            BIT_OFF, RPT_OFF_CHILD -> 1
            BIT_TRANSPARENCY, RPT_TRANSPARENCY_CHILD -> 2
            BIT_ANC_DEEP -> 4
            BIT_ADAPTIVE -> 7           // 自适应单列一档
            BIT_ANC_PARENT, BIT_ANC_MEDIUM, BIT_ANC_LIGHT, BIT_ANC_SMART -> 5
            else -> null
        }
    }

    /** 仅用于日志。 */
    fun nameOfStatus(status: Int): String = when (status) {
        1 -> "关闭"
        2 -> "通透"
        3 -> "普通"
        4 -> "深度降噪"
        5 -> "降噪"
        6 -> "抗风噪"
        else -> "未知($status)"
    }
}
