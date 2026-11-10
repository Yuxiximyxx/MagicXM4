package io.github.yuxiximyxx.magicxm4.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.github.yuxiximyxx.magicxm4.utils.SystemApisUtils.setIconVisibility
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * SystemUIHeadsetIconHook — 在 SystemUI 中管理耳机图标显示。
 *
 * 核心安全原则（防止炸机）：
 * 1. 所有反射调用必须用 runCatching 包裹，任何异常都不应抛出到 hook 链
 * 2. 回调必须用 WeakReference 存储，防止 context 泄漏
 * 3. 所有耗时操作必须在后台线程执行，不在主线程上反射
 * 4. registerReceiver 必须在 try-catch 内，系统可能在某些 Android 版本拒绝注册
 * 5. 图标更新用 iconOwned 状态机，避免重复 setIcon 导致资源竞争
 * 6. 每次操作都有最大重试次数限制，无限重试会卡死 SystemUI
 */
@SuppressLint("MissingPermission")
object SystemUIHeadsetIconHook : HookContext() {
    private const val TAG = "MagicXM4-SystemUI"

    // ★ 3.0.16 更正：原 readBatteryLine（把通知里的设备名行改写成电量行，
    // 先 TWS 双耳拼法、后单值「电量 x%」）已整段删除 —— 用户点名这条通知
    // 只显示设备名、不带任何电量信息，两处改写入口（apply 遍历 / setText
    // 终防线）均已停掉名字行分支。跨进程电量键本身保留：设备卡与详情页
    // 仍在读 magicxm4_battery / magicxm4_battery_single，不受影响。

    /**
     * ★ 3.0.8 连接门控：文案改写只在 XM4 本人是当前已连接的 A2DP 设备时才许动。
     * 信号 = 本 hook 在 SystemUI 进程内维护的 connectedAddresses —— 由 A2DP
     * 连接广播 + 开机回补枚举喂入，每条都过 HeadsetStateDispatcher.isOriGPod
     * （按设备名/别名认定 WH-1000XM4 本人），与状态栏图标显隐共用同一份状态，
     * 不新增跨进程键。没有这道门时，XM4 没连接也会把音频通知的占位标题盖成
     * WH-1000XM4，抢掉同时在跑的 MagicPods（Free4）那条通知（2026-11-10 A/B 实锤）。
     */
    private fun isXm4Connected(): Boolean = connectedAddresses.isNotEmpty()
    private const val SLOT = "wireless_headset"
    private const val MAX_RECONNECT_ATTEMPTS = 3

    // ── 安全状态 ──────────────────────────────────────────────────
    // 用 stateGeneration 保证状态机不会被过期回调覆盖
    private var stateGeneration = 0
    private val connectedAddresses = mutableSetOf<String>()
    private var iconOwned = false

    // ── 延迟初始化：避免 class 加载时引发 NPE ─────────────────────
    private var statusBarManager: StatusBarManager? = null
    private var statusBarManagerInitialized = false

    // ── 线程安全：所有回调必须在主线程执行 ────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        // 0. ★ 任务4：通知文案在 SystemUI 渲染端改写（RemoteViews.apply/reapply 返回的
        //    View 树 = 渲染前最后一刻）。sws 进程内的 set 层改写实测全部被荣耀混淆
        //    渲染类（QuaentCrearsaisSusts，动态 classloader 加载、无法 hook）覆盖，
        //    apply 也发生在 SystemUI 而非 sws —— 只有这里改写必然生效。
        //    文案匹配足够精确（只有音频设备通知含这两行），误伤面为零。
        runCatching {
            val rvCls = Class.forName("android.widget.RemoteViews")
            var cnt = 0
            for (mn in arrayOf("apply", "reapply")) {
                val m = rvCls.declaredMethods.firstOrNull {
                    it.name == mn && it.parameterCount >= 1 &&
                            android.view.View::class.java.isAssignableFrom(it.returnType)
                } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    if (ret is android.view.View && isXm4Connected()) {
                        var changed = 0
                        fun walk(v: android.view.View) {
                            if (v is android.widget.TextView) {
                                // ★ 3.0.16 更正（用户点名）：名字行不再改写成
                                // 电量行 —— 这条通知的目标形态就是显示设备名
                                // 本身、不带任何电量信息。只保留占位标题 →
                                // 设备名一处改写，结果标题/内容都是名字。
                                if ((v.text?.toString() ?: "") == "当前音频输入输出设备") {
                                    v.text = "WH-1000XM4"; changed++
                                }
                            } else if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                            }
                        }
                        walk(ret)
                        if (changed > 0) Log.w(TAG, "★★★ SystemUI 通知改写 $changed 处（$mn）")
                    }
                    ret
                }
                cnt++
            }
            Log.i(TAG, "installed: RemoteViews.apply [SystemUI 通知文案改写] ($cnt)")
        }.onFailure {
            Log.w(TAG, "install SystemUI apply hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 0b. ★ 最终防线：hook TextView.setText（渲染的绝对终点）——
        //    实测 apply 层改写会改到 View 实例但通知栏仍显示原文（荣耀有独立的
        //    通知渲染/复用路径，apply 返回的实例未必是最终上屏的那个）。
        //    在 setText 处按精确文案改写，兜住一切渲染路径。
        //    匹配串极低频（只有音频设备通知含这两行），性能开销可忽略。
        runCatching {
            val m = Class.forName("android.widget.TextView").declaredMethods.first {
                it.name == "setText" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == CharSequence::class.java
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val t = (chain.args.getOrNull(0) as? CharSequence)?.toString()
                // ★ 3.0.16 更正：只改占位标题 → 设备名；设备名本身原样
                // 放行（此前会被改写成电量行，用户点名不要任何电量信息）。
                if (isXm4Connected() && t == "当前音频输入输出设备") {
                    Log.w(TAG, "★★★★ TextView.setText 改写: '$t' → 'WH-1000XM4'")
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = "WH-1000XM4"
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: TextView.setText [通知文案最终防线]")
        }.onFailure {
            Log.w(TAG, "install setText hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 0c. ★ 3.0.16：灵动岛胶囊图标换头戴（仅 XM4 本人）。
        //    现象：XM4 已连接时顶部胶囊（「已连接 / WH-1000XM4」+ 电量环）
        //    左侧图标是入耳式耳塞图。胶囊在 SystemUI 进程渲染、不走
        //    DeviceBallView，3.0.15 的球图门管不到；胶囊按设备资源键取图，
        //    XM4 注入的 DeviceInfo 与 Free4 同为 00VVD9 / Third_EarPhone，
        //    光靠键区分不了，必须按设备身份介入。做法（详见
        //    installCapsuleIconHook）：在 SystemUI 的 ImageView 设图入口，
        //    入参是耳塞家族图、且图标位所属视图树内有设备名 TextView ==
        //    WH-1000XM4 时，同一次调用内换成头戴图；名牌后绑定的时序下
        //    补一次 post 单帧复核（须同时有「已连接」+ 我方名牌才换）。
        //    XM4 未连接时拦截零动作；定不死的来源由探针落
        //    magicxm4_diag_capsuleprobe 供下一轮归因。
        runCatching {
            installCapsuleIconHook()
            Log.i(TAG, "installed: 胶囊图标换图 [3.0.16 名牌门]")
        }.onFailure {
            Log.w(TAG, "install capsule icon hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 1. 找 SystemUI 启动时机
        // MagicOS 的 SystemUI 里没有 CentralSurfacesImpl / SystemUIApplication / SystemUI
        // （真机 dexdump 确认），原来固定 hook CentralSurfacesImpl.start 必然 findClass 抛异常。
        // SystemUIService 是 Honor 保留下来的 AOSP 类，onCreate 是可靠的启动时机。
        val startTargets = listOf(
            "com.android.systemui.SystemUIService",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl"   // AOSP 回退
        )
        var installed = false
        for (cls in startTargets) {
            if (installed) break
            runCatching {
                hookAfter(findMethod(cls, "onCreate")) { safeStartHook(this) }
                installed = true
                Log.e(TAG, "startup hook installed on $cls.onCreate")
            }.onFailure {
                Log.i(TAG, "startup hook skipped for $cls: ${it.javaClass.simpleName}")
            }
        }

        // 2. hook StatusBarManager.setIconVisibility — 独立 hook
        runCatching {
            val smClass = findClass("android.app.StatusBarManager")
            hookAfter(smClass.method("setIconVisibility", String::class.java, Boolean::class.java)) {
                safeStatusBarHook(this)
            }
            Log.d(TAG, "StatusBarManager.setIconVisibility hook installed")
        }.onFailure { Log.d(TAG, "StatusBarManager hook skipped (may not be needed)") }
    }

    private fun safeStartHook(param: HookParam) {
        // 获取 context
        // SystemUIService 是 Service → ContextWrapper，实例本身就是 Context；
        // 跟 HeadsetStateDispatcher 同一个坑：继承链里没有 mContext 字段，
        // 反射取它必然失败 → 这里原先直接 return，整个图标 hook 静默不生效。
        val context: Context? = (param.instance as? Context)
            ?: runCatching { getObjectField(param.instance, "mContext") as? Context }.getOrNull()
            ?: runCatching { getObjectField(param.instance, "mBase") as? Context }.getOrNull()

        if (context == null) {
            Log.e(TAG, "context unavailable instance=${param.instance?.javaClass?.name}")
            return
        }
        Log.i(TAG, "startup hook fired, ctx=${context.javaClass.simpleName}")

        // 创建 receiver
        val receiver = createSafeReceiver(context)
        if (receiver == null) return  // 创建失败不阻塞

        // 注册 receiver 必须有 try-catch
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "A2DP broadcast receiver registered")
        }.onFailure { e ->
            Log.w(TAG, "failed to register A2DP receiver: ${e.message}")
            // 尝试降级方案：用 exported receiver
            runCatching {
                context.registerReceiver(
                    receiver,
                    IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED
                )
                Log.d(TAG, "A2DP receiver registered (fallback: EXPORTED)")
            }.onFailure { e2 ->
                Log.e(TAG, "both receiver registration methods failed", e2)
            }
        }

        // 系统启动时恢复图标状态
        restoreIconsOnBoot(context)
    }

    private fun createSafeReceiver(context: Context): BroadcastReceiver? {
        return try {
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    safeOnReceive(ctx, intent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create receiver", e)
            null
        }
    }

    private fun safeOnReceive(context: Context, intent: Intent) {
        try {
            val device = try {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } catch (e: Throwable) {
                // Android 14+ 可能需要不同的 extra key
                try {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (e2: Throwable) { null }
            } ?: return

            // 快速判断：非 OriG in 耳机直接返回，不处理
            if (!HeadsetStateDispatcher.isOriGPod(device)) return

            val state = try {
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            } catch (e: Throwable) { -1 }

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    safeAddConnected(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    safeRemoveConnected(device.address)
                }
            }
            stateGeneration++
            mainHandler.post { safeUpdateIcon(context) }
        } catch (e: Throwable) {
            Log.e(TAG, "onReceive failed", e)
        }
    }

    private fun safeAddConnected(address: String) {
        try { connectedAddresses.add(address) } catch (e: Throwable) { Log.w(TAG, "addConnected failed", e) }
    }

    private fun safeRemoveConnected(address: String) {
        try { connectedAddresses.remove(address) } catch (e: Throwable) { Log.w(TAG, "removeConnected failed", e) }
    }

    private fun restoreIconsOnBoot(context: Context) {
        // 蓝牙 adapter 获取可能失败，需捕获
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull() ?: return

        val generation = stateGeneration
        // getProfileProxy 回调必须在后台线程
        thread(name = "magicxm4-bt-proxy") {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            if (generation != stateGeneration) {
                                // 已过期，不更新
                                return
                            }
                            // 只处理 A2DP profile
                            if (profile != BluetoothProfile.A2DP) return
                            val freshConnected = mutableListOf<String>()
                            runCatching {
                                proxy.connectedDevices
                                    .filter { HeadsetStateDispatcher.isOriGPod(it) }
                                    .forEach { freshConnected.add(it.address) }
                            }.onFailure { Log.w(TAG, "connectedDevices query failed", it) }
                            connectedAddresses.clear()
                            connectedAddresses.addAll(freshConnected)
                            mainHandler.post { safeUpdateIcon(context) }
                        } catch (e: Throwable) {
                            Log.e(TAG, "onServiceConnected failed", e)
                        } finally {
                            try { adapter.closeProfileProxy(profile, proxy) } catch (e: Throwable) {
                                Log.w(TAG, "closeProfileProxy failed", e)
                            }
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) = Unit
                }, BluetoothProfile.A2DP)
            } catch (e: Throwable) {
                Log.e(TAG, "getProfileProxy failed", e)
            }
        }
    }

    private fun safeUpdateIcon(context: Context) {
        try {
            // 懒初始化 statusBarManager
            if (!statusBarManagerInitialized) {
                statusBarManager = runCatching {
                    context.getSystemService(StatusBarManager::class.java)
                }.getOrNull()
                statusBarManagerInitialized = true
            }
            val sm = statusBarManager ?: return

            val hasConnected = connectedAddresses.isNotEmpty()

            // 状态未变化则跳过
            if (hasConnected == iconOwned) return

            // setIconVisibility 必须在主线程（SystemUI 要求）
            runCatching {
                sm.setIconVisibility(SLOT, hasConnected)
                if (hasConnected) {
                    // 尝试设置图标 — 获取 drawable ID 可能失败
                    val drawableId = runCatching {
                        context.resources.getIdentifier("stat_sys_wireless_headset", "drawable", context.packageName)
                    }.getOrNull() ?: 0
                    if (drawableId != 0) {
                        runCatching {
                            callMethod(sm, "setIcon", SLOT, drawableId, 0, "MagicXM4")
                        }.onFailure { Log.w(TAG, "setIcon failed, visibility only", it) }
                    }
                }
                iconOwned = hasConnected
                Log.d(TAG, "icon updated: visible=$hasConnected owned=$iconOwned")
            }.onFailure { e ->
                Log.e(TAG, "setIconVisibility failed", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeUpdateIcon crashed", e)
        }
    }

    private fun safeStatusBarHook(param: HookParam) {
        // 拦截 setIconVisibility 确保图标不会意外被其他代码隐藏
        try {
            val slot = param.args[0] as? String
            val show = param.args[1] as? Boolean
            if (slot == SLOT && show == true) {
                // 确保 iconOwned 状态同步
                iconOwned = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeStatusBarHook failed", e)
        }
    }

    // ── ★ 3.0.16 胶囊图标换图的状态 ─────────────────────────────
    // CAPSULE_ICON_GUARD：换图时自己调 setImageDrawable 会再进拦截，靠它防递归。
    // CAPSULE_SEEN / CAPSULE_TOTAL：日志按签名去重封顶（沿用球阵探针纪律）。
    // CAPSULE_PENDING：已挂多帧复核的图标位（弱引用集合，防重复挂）。
    // CAPSULE_PROBE_PENDING：已挂整树探针扫描的锚点 TextView（同上）。
    // CAPSULE_SWAP_PENDING：已挂整树换图扫描的锚点 TextView（同上）。
    // ★ 3.0.17：胶囊探针补灵敏度 —— 新增 setText 触发的整树扫描
    //   （capsuleTreeProbe*）与多帧复核的落空原因记录；换图判据不变。
    // ★ 3.0.18：胶囊树扫描换图（capsuleTreeSwap*）—— 探针钉死图标位
    //   是位图交付，改在文本绑齐后对 HnCardLayout 子树内的位图位
    //   直接换图；探针与名牌门保留不动。
    // ★ 3.0.19：二选一判完 —— 3.0.18 换掉的 140x140 位图位不是
    //   肉眼可见的左图（真机证伪，它是环内小图/底图，撤换）；可见
    //   左图是 HnCardIconLayout 内的 RemotableAnimationImageView
    //   （AnimatedImageDrawable 200x200），换图目标改到它，并补
    //   延迟帧防宿主重设动画图覆盖（animRebound）。
    private val CAPSULE_ICON_GUARD = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }
    private val CAPSULE_SEEN: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private val CAPSULE_TOTAL = java.util.concurrent.atomic.AtomicInteger(0)
    private val CAPSULE_PENDING: MutableSet<android.view.View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    private val CAPSULE_PROBE_PENDING: MutableSet<android.view.View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    private val CAPSULE_SWAP_PENDING: MutableSet<android.view.View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    @Volatile private var capsuleCcResources: android.content.res.Resources? = null

    private val CAPSULE_EARBUD_NAMES = arrayOf(
        "device_icon_in_ear_earphone_shadow",
        "device_icon_semi_in_ear_earphone_shadow"
    )
    private const val CAPSULE_HEADPHONE_NAME = "device_icon_default_earphone"
    private const val CAPSULE_OUR_NAME = "WH-1000XM4"

    private fun capsuleDiag(value: String) {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return@runCatching
            android.provider.Settings.Global.putString(
                app.contentResolver, "magicxm4_diag_capsuleprobe", value.take(600)
            )
        }
    }

    private fun capsuleLog(sig: String, msg: String) {
        if (CAPSULE_SEEN.add(sig) && CAPSULE_TOTAL.incrementAndGet() <= 40) {
            Log.w(TAG, "▶ 胶囊图标 $msg")
            capsuleDiag(msg)
        }
    }

    private fun capsuleAncestorChain(view: android.view.View): String {
        val sb = StringBuilder()
        var node: android.view.View? = view
        var depth = 0
        while (node != null && depth < 16) {
            if (depth > 0) sb.append('<')
            sb.append(node.javaClass.simpleName)
            node = node.parent as? android.view.View
            depth++
        }
        return sb.toString()
    }

    /**
     * 身份判据：从图标位向上找到窗口根（≤20 层），在该子树内（≤240 节点）
     * 找文本恰为 target 的 TextView。胶囊是独立小窗、树很小；找不到即非我方。
     */
    private fun capsuleTreeContainsText(view: android.view.View, target: String): Boolean {
        var root: android.view.View = view
        var depth = 0
        while (depth < 20) {
            val par = root.parent as? android.view.View ?: break
            root = par
            depth++
        }
        var count = 0
        fun dfs(v: android.view.View): Boolean {
            if (++count > 240) return false
            if (v is android.widget.TextView && v.text?.toString()?.trim() == target) return true
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) if (dfs(v.getChildAt(i))) return true
            }
            return false
        }
        return runCatching { dfs(root) }.getOrDefault(false)
    }

    private fun capsuleResIdByName(
        res: android.content.res.Resources, name: String, defPackage: String
    ): Int = runCatching { res.getIdentifier(name, "drawable", defPackage) }.getOrDefault(0)

    private fun capsuleControlCenterResources(
        ctx: android.content.Context?
    ): android.content.res.Resources? {
        capsuleCcResources?.let { return it }
        val c = ctx ?: return null
        val r = runCatching {
            c.createPackageContext("com.hihonor.controlcenter", 0).resources
        }.getOrNull()
        if (r != null) capsuleCcResources = r
        return r
    }

    /**
     * 入参 drawable 是耳塞两图之一：ConstantState 身份比对。参考图分别从
     * 图标位自己的资源与控制中心包资源按名解析（胶囊图源包静态定不死，
     * 两边都比）；任一命中即认。认不出（位图直传等）返回 false，走探针。
     */
    private fun isCapsuleEarbudDrawable(
        view: android.widget.ImageView, d: android.graphics.drawable.Drawable
    ): Boolean {
        val cs = runCatching { d.constantState }.getOrNull() ?: return false
        val resList = mutableListOf(view.resources)
        capsuleControlCenterResources(view.context)?.let { resList.add(it) }
        val pkgs = listOfNotNull(
            runCatching { view.context?.packageName }.getOrNull(),
            "com.hihonor.controlcenter"
        )
        for (res in resList) {
            for (pkg in pkgs) {
                for (name in CAPSULE_EARBUD_NAMES) {
                    val id = capsuleResIdByName(res, name, pkg)
                    if (id != 0) {
                        val ref = runCatching { res.getDrawable(id, view.context?.theme) }.getOrNull()
                        if (ref != null && ref.constantState === cs) return true
                    }
                }
            }
        }
        return false
    }

    /** 头戴图：优先图标位自己资源包的同名资源，没有再从控制中心包资源加载。 */
    private fun loadCapsuleHeadphone(
        view: android.widget.ImageView
    ): android.graphics.drawable.Drawable? {
        runCatching {
            val pkg = view.context?.packageName ?: ""
            val id = capsuleResIdByName(view.resources, CAPSULE_HEADPHONE_NAME, pkg)
            if (id != 0) return view.resources.getDrawable(id, view.context?.theme)
        }
        val cc = capsuleControlCenterResources(view.context) ?: return null
        val id = capsuleResIdByName(cc, CAPSULE_HEADPHONE_NAME, "com.hihonor.controlcenter")
        if (id == 0) return null
        return runCatching { cc.getDrawable(id, view.context?.theme) }.getOrNull()
    }

    private fun installCapsuleIconHook() {
        var installed = 0
        // a) ImageView.setImageResource(int)：入参 id 的 entry 名即图名。
        runCatching {
            val m = android.widget.ImageView::class.java
                .getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val view = chain.thisObject as? android.widget.ImageView
                val resId = (chain.args.getOrNull(0) as? Int) ?: 0
                if (view != null && resId != 0 && !CAPSULE_ICON_GUARD.get() && isXm4Connected()) {
                    val entry = runCatching {
                        view.resources.getResourceEntryName(resId)
                    }.getOrNull()
                    if (entry != null && CAPSULE_EARBUD_NAMES.contains(entry)) {
                        if (capsuleTreeContainsText(view, CAPSULE_OUR_NAME)) {
                            val head = loadCapsuleHeadphone(view)
                            if (head != null) {
                                val ret = chain.proceed()
                                CAPSULE_ICON_GUARD.set(true)
                                try { view.setImageDrawable(head) } finally {
                                    CAPSULE_ICON_GUARD.set(false)
                                }
                                capsuleLog(
                                    "res|swap|$entry",
                                    "setImageResource arg=$entry swapped=headphone " +
                                        "gate=now chain=${capsuleAncestorChain(view)}"
                                )
                                return@intercept ret
                            }
                        } else {
                            capsuleDeferredRecheck(view, "setImageResource:$entry")
                            capsuleLog(
                                "res|miss|$entry",
                                "setImageResource arg=$entry gate=miss " +
                                    "chain=${capsuleAncestorChain(view)}"
                            )
                        }
                    }
                }
                chain.proceed()
            }
            installed++
        }.onFailure {
            Log.w(TAG, "胶囊图标 setImageResource 装载失败: ${it.javaClass.simpleName}")
        }
        // b) ImageView.setImageDrawable(Drawable)：认图走 ConstantState 身份。
        runCatching {
            val m = android.widget.ImageView::class.java.getDeclaredMethod(
                "setImageDrawable", android.graphics.drawable.Drawable::class.java
            )
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val view = chain.thisObject as? android.widget.ImageView
                val d = chain.args.getOrNull(0) as? android.graphics.drawable.Drawable
                if (view != null && d != null && !CAPSULE_ICON_GUARD.get() && isXm4Connected()) {
                    if (isCapsuleEarbudDrawable(view, d)) {
                        if (capsuleTreeContainsText(view, CAPSULE_OUR_NAME)) {
                            val head = loadCapsuleHeadphone(view)
                            if (head != null) {
                                capsuleLog(
                                    "drw|swap|${d.javaClass.simpleName}",
                                    "setImageDrawable arg=${d.javaClass.simpleName} " +
                                        "swapped=headphone gate=now " +
                                        "chain=${capsuleAncestorChain(view)}"
                                )
                                val newArgs = chain.args.toTypedArray()
                                newArgs[0] = head
                                return@intercept chain.proceed(newArgs)
                            }
                        } else {
                            capsuleDeferredRecheck(view, "setImageDrawable")
                            capsuleLog(
                                "drw|miss|${d.javaClass.simpleName}",
                                "setImageDrawable arg=${d.javaClass.simpleName} " +
                                    "gate=miss chain=${capsuleAncestorChain(view)}"
                            )
                        }
                    } else if (d is android.graphics.drawable.BitmapDrawable) {
                        // 位图直传且祖先链带 Capsule 字样才记（只记不换，供归因）。
                        val anc = capsuleAncestorChain(view)
                        if (anc.contains("Capsule", ignoreCase = true) ||
                            anc.contains("Island", ignoreCase = true)
                        ) {
                            capsuleLog(
                                "bmp|${anc.hashCode()}",
                                "setImageDrawable bitmap inCapsuleChain " +
                                    "nameFound=${capsuleTreeContainsText(view, CAPSULE_OUR_NAME)} " +
                                    "chain=$anc"
                            )
                        }
                    }
                }
                chain.proceed()
            }
            installed++
        }.onFailure {
            Log.w(TAG, "胶囊图标 setImageDrawable 装载失败: ${it.javaClass.simpleName}")
        }
        // c) ★ 3.0.17 兜底观察（只记不改）：16:05 真机日志实证 ——
        //    a/b 两入口在胶囊图标位上从未被触发（全日志零条
        //    capsuleLog），该图标位不是经 ImageView.setImageResource /
        //    setImageDrawable 设图的。但胶囊的名牌与「已连接」文本
        //    必经 TextView.setText（3.0.15 时代的旧改写正是靠这一路
        //    命中胶囊的，日志实锤）。故在此按这两串文本触发整树
        //    扫描，钉死图标位是什么 View、图怎么交付。拦截一律
        //    原样 proceed，绝不改文本（文案侧 0/0b 的逻辑不碰）。
        //    ★ 3.0.18 追记：探针已钉死图标位（见 capsuleTreeSwapScan
        //    头注），同一锚点除探针外再调度一轮树扫描换图。
        runCatching {
            val m = Class.forName("android.widget.TextView").declaredMethods.first {
                it.name == "setText" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == CharSequence::class.java
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val ret = chain.proceed()
                val t = (chain.args.getOrNull(0) as? CharSequence)?.toString()?.trim()
                val view = chain.thisObject as? android.widget.TextView
                if (view != null && (t == CAPSULE_OUR_NAME || t == "已连接")) {
                    capsuleTreeProbeSchedule(view, "setText")
                    capsuleTreeSwapSchedule(view, "setText")
                }
                ret
            }
            installed++
        }.onFailure {
            Log.w(TAG, "胶囊树探针 setText 装载失败: ${it.javaClass.simpleName}")
        }
        capsuleDiag("installed=$installed/3 (3.0.19 胶囊图标: 名牌门同步换 + 多帧复核 + setText 整树探针 + 树扫描换动效层)")
        Log.i(TAG, "installed: 胶囊图标换图入口 ($installed/3)")
    }

    /** ImageView 当前图的种类速记：耳塞/头戴按 ConstantState 身份认，其余记类名。 */
    private fun capsuleDrawableKind(
        view: android.widget.ImageView, d: android.graphics.drawable.Drawable?
    ): String {
        if (d == null) return "null"
        if (isCapsuleEarbudDrawable(view, d)) return "earbud"
        val cs = runCatching { d.constantState }.getOrNull()
        if (cs != null) {
            val head = loadCapsuleHeadphone(view)
            if (head != null && head.constantState === cs) return "headphone"
        }
        return d.javaClass.simpleName
    }

    /**
     * ★ 3.0.17 胶囊整树探针的调度：锚点（名牌 /「已连接」的 TextView）
     * 所在窗口整棵树，在当前帧 + 150ms + 500ms 各扫一次（图标位可能
     * 晚于文本绑定）。同一锚点只挂一轮（弱引用集合防重复挂）。
     */
    private fun capsuleTreeProbeSchedule(anchor: android.widget.TextView, via: String) {
        if (!CAPSULE_PROBE_PENDING.add(anchor)) return
        val delays = longArrayOf(0L, 150L, 500L)
        var postedAny = false
        for (i in delays.indices) {
            val last = i == delays.lastIndex
            val r = Runnable {
                capsuleTreeProbeScan(anchor, via, i)
                if (last) CAPSULE_PROBE_PENDING.remove(anchor)
            }
            val ok = runCatching {
                if (delays[i] <= 0L) anchor.post(r) else anchor.postDelayed(r, delays[i])
            }.getOrDefault(false)
            if (ok) postedAny = true
        }
        if (!postedAny) CAPSULE_PROBE_PENDING.remove(anchor)
    }

    /**
     * 整树扫描（只观察、绝不改图）：收集全部文本与全部 ImageView 的
     * 图信息。树内同时有「已连接」与我方名牌 → 记 treeprobe（图标位
     * 类名 / 图种类 / 尺寸 / 祖先链），钉死图标位是什么 View、图怎么
     * 交付；否则记 treemiss 并写明 nameFound / connectedFound，分清
     * 是胶囊根本不在这棵树、还是文本还没绑齐。
     */
    private fun capsuleTreeProbeScan(anchor: android.view.View, via: String, frame: Int) {
        runCatching {
            if (!anchor.isAttachedToWindow) {
                capsuleLog(
                    "treemiss|$via|detached",
                    "treeprobe miss via=$via frame=$frame reason=detached"
                )
                return@runCatching
            }
            var root: android.view.View = anchor
            var depth = 0
            while (depth < 20) {
                val par = root.parent as? android.view.View ?: break
                root = par
                depth++
            }
            val texts = mutableListOf<String>()
            val imgs = mutableListOf<String>()
            var count = 0
            fun dfs(v: android.view.View) {
                if (++count > 240) return
                if (v is android.widget.TextView) {
                    v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { texts.add(it.take(24)) }
                }
                if (v is android.widget.ImageView) {
                    val d = v.drawable
                    val size = if (d != null) "${d.intrinsicWidth}x${d.intrinsicHeight}" else "-"
                    val bmp = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        ?.let { " bmp=${it.width}x${it.height}" } ?: ""
                    imgs.add(
                        "${v.javaClass.simpleName}[kind=${capsuleDrawableKind(v, d)} " +
                            "size=$size$bmp chain=${capsuleAncestorChain(v).take(140)}]"
                    )
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) dfs(v.getChildAt(i))
                }
            }
            dfs(root)
            val nameFound = texts.any { it == CAPSULE_OUR_NAME }
            val connectedFound = texts.any { it == "已连接" }
            if (nameFound && connectedFound) {
                capsuleLog(
                    "tree|$via|${imgs.joinToString(",").hashCode()}",
                    "treeprobe via=$via frame=$frame root=${root.javaClass.simpleName} " +
                        "texts=${texts.take(6)} imgs(${imgs.size})=${imgs.take(6)}"
                )
            } else {
                capsuleLog(
                    "treemiss|$via|$nameFound|$connectedFound|${root.javaClass.simpleName}",
                    "treeprobe miss via=$via frame=$frame nameFound=$nameFound " +
                        "connectedFound=$connectedFound root=${root.javaClass.simpleName} " +
                        "texts=${texts.take(6)}"
                )
            }
        }
    }

    /**
     * ★ 胶囊树扫描换图的调度：与整树探针同一锚点、同一节奏
     * （当前帧 + 150ms + 500ms）。3.0.18 在文本绑齐后对位图位
     * 直接换图；3.0.19 换图目标改到动效层（见 capsuleTreeSwapScan
     * 注释），三帧兼作防覆盖复核。同一锚点只挂一轮（弱引用集合
     * 防重复挂）。swapped 记录本轮是否已换过一次，供复核帧区分
     * 首次换图与宿主重设动画图后的回弹再换。
     */
    private fun capsuleTreeSwapSchedule(anchor: android.widget.TextView, via: String) {
        if (!CAPSULE_SWAP_PENDING.add(anchor)) return
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val swapped = java.util.concurrent.atomic.AtomicBoolean(false)
        val slotRef =
            java.util.concurrent.atomic.AtomicReference<android.widget.ImageView?>(null)
        val delays = longArrayOf(0L, 150L, 500L)
        var postedAny = false
        for (i in delays.indices) {
            val last = i == delays.lastIndex
            val r = Runnable {
                capsuleTreeSwapScan(anchor, via, i, done, swapped, slotRef)
                if (last) CAPSULE_SWAP_PENDING.remove(anchor)
            }
            val ok = runCatching {
                if (delays[i] <= 0L) anchor.post(r) else anchor.postDelayed(r, delays[i])
            }.getOrDefault(false)
            if (ok) postedAny = true
        }
        if (!postedAny) CAPSULE_SWAP_PENDING.remove(anchor)
    }

    /**
     * 树扫描换图（3.0.19）：锚点所在窗口整棵树同时含「已连接」与
     * 我方名牌才动手 —— Free4 的胶囊树名牌是 OPPO Enco Free4，
     * 天然不命中；其余通知树不碰。换图目标是 HnCardIconLayout
     * 子树内的 RemotableAnimationImageView（drawable 为
     * AnimatedImageDrawable，探针实测 200x200）：3.0.18 真机实证
     * 它换掉的 140x140 BitmapDrawable 位不是肉眼可见的左图（那颗
     * 是 90% 电量环内小图/底图，本版撤换、不再碰），胶囊左图就是
     * 这颗动效层 —— 连接动画播完停在耳塞图那一帧。换成静态头戴
     * 图后，宿主可能再对该 View 重设动画图覆盖，故三帧扫描兼作
     * 复核：slotRef 记住图位，后续帧发现它又变回动画图就再换一次
     * 并记 animRebound；已是头戴图则收工。首次命中记 animSwap
     * （含图位祖先链与换前尺寸），仍落 magicxm4_diag_capsuleprobe。
     */
    private fun capsuleTreeSwapScan(
        anchor: android.view.View, via: String, frame: Int,
        done: java.util.concurrent.atomic.AtomicBoolean,
        swapped: java.util.concurrent.atomic.AtomicBoolean,
        slotRef: java.util.concurrent.atomic.AtomicReference<android.widget.ImageView?>
    ) {
        runCatching {
            if (done.get()) return@runCatching
            if (!anchor.isAttachedToWindow) {
                capsuleLog(
                    "swapmiss|$via|detached",
                    "treeSwap miss via=$via frame=$frame reason=detached"
                )
                return@runCatching
            }
            var root: android.view.View = anchor
            var depth = 0
            while (depth < 20) {
                val par = root.parent as? android.view.View ?: break
                root = par
                depth++
            }
            val texts = mutableListOf<String>()
            var animSlot: android.widget.ImageView? = null
            var animScore = -1
            var bitmapSeen = false
            var count = 0
            fun dfs(v: android.view.View, inIcon: Boolean) {
                if (++count > 240) return
                val insideIcon = inIcon || v.javaClass.simpleName == "HnCardIconLayout"
                if (v is android.widget.TextView) {
                    v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { texts.add(it) }
                }
                if (v is android.widget.ImageView) {
                    val d = v.drawable
                    if (d is android.graphics.drawable.BitmapDrawable) bitmapSeen = true
                    if (d is android.graphics.drawable.AnimatedImageDrawable) {
                        // HnCardIconLayout 内优先，其次类名 Remotable*。
                        val score = (if (insideIcon) 10 else 0) +
                            (if (v.javaClass.simpleName ==
                                "RemotableAnimationImageView"
                            ) 5 else 0)
                        if (score > animScore) {
                            animScore = score
                            animSlot = v
                        }
                    }
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) dfs(v.getChildAt(i), insideIcon)
                }
            }
            dfs(root, false)
            val nameFound = texts.any { it == CAPSULE_OUR_NAME }
            val connectedFound = texts.any { it == "已连接" }
            if (!nameFound || !connectedFound) {
                capsuleLog(
                    "swapmiss|$via|$nameFound|$connectedFound|${root.javaClass.simpleName}",
                    "treeSwap miss via=$via frame=$frame nameFound=$nameFound " +
                        "connectedFound=$connectedFound root=${root.javaClass.simpleName}"
                )
                return@runCatching
            }
            // 图位优先用 slotRef 里记住的那颗（换成头戴图后它不再
            // 持有动画图、DFS 就找不到了，复核帧靠它认）；本轮首次
            // 找到则记进 slotRef。
            val target = slotRef.get() ?: animSlot?.also { slotRef.set(it) }
            if (target == null) {
                capsuleLog(
                    "swapmiss|$via|noslot|${root.javaClass.simpleName}",
                    "animSwap miss via=$via frame=$frame reason=noAnimSlot " +
                        "root=${root.javaClass.simpleName} bitmapSeen=$bitmapSeen"
                )
                return@runCatching
            }
            val cur = target.drawable
            // 已是头戴图（本轮换过且未被覆盖，或宿主本就给的头戴图）→ 收工。
            if (capsuleDrawableKind(target, cur) == "headphone") {
                done.set(true)
                return@runCatching
            }
            // 不是动画图也不是头戴图（空图/其他图）→ 本帧不动，等下一帧。
            if (cur !is android.graphics.drawable.AnimatedImageDrawable) {
                return@runCatching
            }
            val head = loadCapsuleHeadphone(target)
            if (head == null) {
                capsuleLog(
                    "swapmiss|$via|nohead",
                    "animSwap miss via=$via frame=$frame reason=headphoneLoadFail"
                )
                return@runCatching
            }
            val preSize = "${cur.intrinsicWidth}x${cur.intrinsicHeight}"
            CAPSULE_ICON_GUARD.set(true)
            try { target.setImageDrawable(head) } finally {
                CAPSULE_ICON_GUARD.set(false)
            }
            if (swapped.getAndSet(true)) {
                // 本轮已换过一次、又变回动画图 = 宿主重设覆盖 → 回弹再换。
                capsuleLog(
                    "animrebound|$via",
                    "animRebound via=$via frame=$frame slot=AnimatedImageDrawable " +
                        "size=$preSize swapped=headphone " +
                        "chain=${capsuleAncestorChain(target)}"
                )
            } else {
                capsuleLog(
                    "animswap|$via",
                    "animSwap via=$via frame=$frame slot=AnimatedImageDrawable " +
                        "size=$preSize swapped=headphone " +
                        "chain=${capsuleAncestorChain(target)}"
                )
            }
            // 不 set done：后续帧继续复核，防宿主重设动画图覆盖。
        }
    }

    /**
     * 名牌后绑时序的多帧复核（3.0.17 由单帧改多帧）：post 当前帧 +
     * 150ms + 500ms 各查一次，树内同时有我方名牌与「已连接」、且
     * 当前图仍是耳塞图才换。每一帧落空都记一行原因（nameFound /
     * connectedFound / 当前图类名），分清是时序未到还是判据根本
     * 不齐；判据不齐一律不动图。
     */
    private fun capsuleDeferredRecheck(view: android.widget.ImageView, via: String) {
        if (!CAPSULE_PENDING.add(view)) return
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun attempt(frame: Int, last: Boolean) {
            runCatching {
                if (done.get()) {
                    if (last) CAPSULE_PENDING.remove(view)
                    return@runCatching
                }
                if (!view.isAttachedToWindow) {
                    capsuleLog(
                        "defer|miss|$via|$frame|detached",
                        "deferredMiss via=$via frame=$frame reason=detached"
                    )
                    if (last) CAPSULE_PENDING.remove(view)
                    return@runCatching
                }
                val nameFound = capsuleTreeContainsText(view, CAPSULE_OUR_NAME)
                val connectedFound = capsuleTreeContainsText(view, "已连接")
                val cur = view.drawable
                val earbud = cur != null && isCapsuleEarbudDrawable(view, cur)
                if (nameFound && connectedFound && earbud) {
                    val head = loadCapsuleHeadphone(view)
                    if (head != null) {
                        CAPSULE_ICON_GUARD.set(true)
                        try { view.setImageDrawable(head) } finally {
                            CAPSULE_ICON_GUARD.set(false)
                        }
                        done.set(true)
                        capsuleLog(
                            "defer|swap|$via",
                            "deferredSwap via=$via frame=$frame " +
                                "chain=${capsuleAncestorChain(view)}"
                        )
                        if (last) CAPSULE_PENDING.remove(view)
                        return@runCatching
                    }
                }
                capsuleLog(
                    "defer|miss|$via|$frame|$nameFound|$connectedFound|" +
                        "${cur?.javaClass?.simpleName}|$earbud",
                    "deferredMiss via=$via frame=$frame nameFound=$nameFound " +
                        "connectedFound=$connectedFound earbud=$earbud " +
                        "drawable=${cur?.javaClass?.simpleName ?: "null"} " +
                        "chain=${capsuleAncestorChain(view)}"
                )
                if (last) CAPSULE_PENDING.remove(view)
            }
        }
        val delays = longArrayOf(0L, 150L, 500L)
        var postedAny = false
        for (i in delays.indices) {
            val last = i == delays.lastIndex
            val r = Runnable { attempt(i, last) }
            val ok = runCatching {
                if (delays[i] <= 0L) view.post(r) else view.postDelayed(r, delays[i])
            }.getOrDefault(false)
            if (ok) postedAny = true
        }
        if (!postedAny) CAPSULE_PENDING.remove(view)
    }
}
