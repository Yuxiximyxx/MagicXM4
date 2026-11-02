/*
 * 设备身份发布点（FIX-002）。
 *
 * 为什么需要它：设备中心注入链以 MAC 为主键，而蓝牙进程的 `connectPod` 要等
 * SPP/RFCOMM 连上才跑；一旦 SPP 链路没建立（广播未到、主 hook 未回调），
 * 真实 MAC 就永远发布不出去，设备中心只能回落幽灵 MAC。
 *
 * 因此把「发布真实 MAC」这一步**前移到设备识别命中点**：
 * 只要模块认出「这是我们的耳机」，就立刻写 Settings.Global["magicxm4_mac"]，
 * 不再依赖连接流程是否成功。connectPod 内的写入保留作双保险。
 */
package io.github.yuxiximyxx.magicxm4.utils

import android.content.Context
import io.github.yuxiximyxx.magicxm4.hook.Log

object DeviceIdentity {

    private const val TAG = "MagicXM4-Identity"

    /** 跨进程桥的键名，设备中心进程（controlcenter）按此读取。 */
    const val MAC_KEY = "magicxm4_mac"

    @Volatile private var lastPublished: String? = null

    /** 拿到宿主进程的 Application（hook 早期可能为 null，调用方自行重试）。 */
    fun currentApp(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    /**
     * 发布真实耳机 MAC。同值重复调用只做内存去重，不重复写 Settings。
     * @param address BluetoothDevice.address
     * @param context 可选的宿主 Context，缺省用 currentApplication()
     */
    fun publish(address: String?, context: Context? = null) {
        val mac = address?.trim().orEmpty()
        if (mac.isEmpty()) return
        if (mac.equals(lastPublished, ignoreCase = true)) return
        val ctx = context ?: currentApp() ?: return
        runCatching {
            android.provider.Settings.Global.putString(ctx.contentResolver, MAC_KEY, mac)
            lastPublished = mac
            Log.i(TAG, "已发布 $MAC_KEY=$mac（设备识别命中，不依赖 SPP 连接）")
        }.onFailure {
            Log.w(TAG, "发布 $MAC_KEY 失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
