/*
 * 荣耀侧「设备类型」单一取值点。
 *
 * 为什么要有它：
 *  - `HnDeviceProductType` 是荣耀 controlcenter 内部的 16 项枚举，名字只能从
 *    **本机构建** 的枚举常量里读，写死任何一个都可能换机型就失效。
 *  - MagicFree4 真机验证通过的是 `Third_EarPhone`（第三方耳机）。WH-1000XM4 是头戴，
 *    但本项目没有在真机上确认过「头戴」对应哪个枚举常量 —— 所以默认**沿用已验证的那个**，
 *    同时提供 `Settings.Global["magicxm4_device_type"]` 覆盖点，让真机上一次改动就能试，
 *    不用重编 APK。
 *  - 顺带把枚举常量全表 dump 到 `magicxm4_diag_hn_types`，这是决定「到底该用哪个」的
 *    唯一证据来源（模块设置页会显示它）。
 */
package io.github.yuxiximyxx.magicxm4.hook

import io.github.yuxiximyxx.magicxm4.utils.DeviceIdentity

object HonorDeviceTypeName {

    private const val TAG = "MagicXM4-DeviceType"

    /** 覆盖点：`adb shell settings put global magicxm4_device_type <枚举名>` */
    const val OVERRIDE_KEY = "magicxm4_device_type"

    /** 枚举全表 dump 的键（模块设置页读取展示）。 */
    const val DUMP_KEY = "magicxm4_diag_hn_types"

    /** MagicFree4 在 Magic7 Pro / MagicOS 11 上真机验证过能过渲染门的那一项。 */
    const val VERIFIED_DEFAULT = "Third_EarPhone"

    @Volatile private var cachedName: String? = null
    @Volatile private var cachedAt: Long = 0L

    /** 缓存时长：既避免 getDeviceList 每次查询都读一次 Settings，又不用重启进程就能改。 */
    private const val CACHE_TTL_MS = 5_000L

    /** 当前要用的类型名：优先用户覆盖，否则回到已验证的默认值。 */
    fun current(): String {
        val now = System.currentTimeMillis()
        cachedName?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        val override = runCatching {
            val ctx = DeviceIdentity.currentApp() ?: return@runCatching null
            android.provider.Settings.Global.getString(ctx.contentResolver, OVERRIDE_KEY)
        }.getOrNull()?.trim()
        val name = if (!override.isNullOrEmpty()) override else VERIFIED_DEFAULT
        cachedName = name
        cachedAt = now
        return name
    }

    /**
     * 从枚举常量表里按 [current] 挑出对应项。
     *
     * 找不到时回退 [VERIFIED_DEFAULT]，再回退第一个常量 —— 绝不返回 null 让整条注入链断掉，
     * 因为一个"类型名打错"导致设备球彻底消失，比显示成"耳机"糟得多。
     */
    fun pick(constants: Array<*>?): Any? {
        if (constants == null || constants.isEmpty()) return null
        val wanted = current()
        val byWanted = constants.firstOrNull { it.toString() == wanted }
        if (byWanted != null) return byWanted
        val byDefault = constants.firstOrNull { it.toString() == VERIFIED_DEFAULT }
        if (byWanted == null && wanted != VERIFIED_DEFAULT) {
            Log.w(TAG, "枚举里没有 '$wanted'，回退 $VERIFIED_DEFAULT（可选值见 $DUMP_KEY）")
        }
        return byDefault ?: constants.first()
    }

    /** 把枚举全表写进 Settings.Global，供模块设置页 / adb 读取。同一个值只写一次。 */
    fun dumpOnce(constants: Array<*>?) {
        if (constants == null || constants.isEmpty()) return
        val text = constants.joinToString(",") { it.toString() }
        if (text == lastDumped) return
        val ctx = DeviceIdentity.currentApp() ?: return
        runCatching {
            android.provider.Settings.Global.putString(ctx.contentResolver, DUMP_KEY, text)
            lastDumped = text
            Log.i(TAG, "设备类型枚举 = $text")
        }.onFailure { Log.w(TAG, "dump 设备类型失败: ${it.message}") }
    }

    @Volatile
    private var lastDumped: String? = null
}
