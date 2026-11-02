/*
 * 把宿主 APK 的 assets 配置 dump 出来。
 *
 * 为什么需要：荣耀的设备中心按「机型码 → assets 里的机型表 / uiconfig」决定
 * 设备球和卡片长什么样（有没有降噪栏、档位数、图标）。我们在做 WH-1000XM4
 * 的头戴形态适配，需要知道本机这份表里到底有哪些机型、分别是耳塞还是头戴。
 * 这些 json 在 /system/app 下，没有 root 读不到；但模块跑在宿主进程里，
 * 用宿主自己的 AssetManager 就能读 —— 读出来写进 Settings.Global，adb 直接看。
 *
 * 触发：模块在对应进程安装 hook 时调用一次（已有结果就跳过）。
 * 查看：adb shell settings get global magicxm4_diag_assets_1 / _2 / _3
 */
package io.github.yuxiximyxx.magicxm4.hook

import android.content.Context
import android.provider.Settings

object HonorAssetsDump {

    private const val TAG = "MagicXM4-Assets"

    /** 机型表候选文件名（不同 MagicOS 版本叫法略有差异，全都试一遍）。 */
    private val TABLE_FILES = listOf(
        "baseSupportDeviceList.json",
        "earphone_support_list_builtin.json",
        "earphone_support_list.json",
        "supportDeviceList.json",
        "support_device_list.json",
    )

    private const val CHUNK = 3000
    private const val MAX_CHUNKS = 8

    fun dump(context: Context?) {
        if (context == null) return
        runCatching {
            val assets = context.assets
            val sb = StringBuilder()
            sb.append("PKG=").append(context.packageName).append('\n')

            // 先把整棵 assets 树列出来（深度 3）：机型表在不同 MagicOS 版本里位置不一样，
            // 靠猜文件名容易一直被 null 打脸，直接看树最快。
            walk(assets, "", 0, sb, 2400)

            // 再把表内容和 uiconfig 目录单独贴出来（能找到就贴）。
            val uiConfig = runCatching { assets.list("uiconfig")?.toList().orEmpty() }
                .getOrDefault(emptyList())
            sb.append("UICONFIG(").append(uiConfig.size).append(")=")
                .append(uiConfig.joinToString(",")).append('\n')

            for (name in TABLE_FILES) {
                val text = runCatching {
                    assets.open(name).bufferedReader().use { it.readText() }
                }.getOrNull() ?: continue
                sb.append("=== ").append(name).append(" (").append(text.length).append(") ===\n")
                sb.append(text).append('\n')
            }

            writeChunked(context, sb.toString())
            Log.i(TAG, "assets 已 dump pkg=" + context.packageName + " 长度=" + sb.length)
        }.onFailure { Log.w(TAG, "assets dump 失败: " + it.javaClass.simpleName + ": " + it.message) }
    }

    /** 递归列 assets 路径（只列文件名，不读内容），受 [budget] 条数限制。 */
    private fun walk(
        assets: android.content.res.AssetManager,
        dir: String,
        depth: Int,
        sb: StringBuilder,
        budget: Int,
    ) {
        if (depth > 3 || sb.length > budget) return
        val children = runCatching { assets.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
        for (name in children) {
            val path = if (dir.isEmpty()) name else "$dir/$name"
            sb.append(path).append(if (name.contains('.')) "" else "/").append('\n')
            if (!name.contains('.')) walk(assets, path, depth + 1, sb, budget)
        }
    }

    /**
     * 分块写进 Settings.Global。
     * 单个 Settings 值太长不保险（而且 `settings get` 输出也会难读），
     * 所以切成若干块，每块一个键。
     */
    private fun writeChunked(context: Context, text: String) {
        val chunks = text.chunked(CHUNK).take(MAX_CHUNKS)
        for ((index, chunk) in chunks.withIndex()) {
            runCatching {
                Settings.Global.putString(
                    context.contentResolver,
                    "magicxm4_diag_assets_${index + 1}",
                    chunk,
                )
            }
        }
    }
}
