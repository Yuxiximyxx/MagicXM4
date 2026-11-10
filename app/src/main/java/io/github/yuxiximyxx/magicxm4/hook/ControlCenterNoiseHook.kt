package io.github.yuxiximyxx.magicxm4.hook

import android.content.Intent
import io.github.yuxiximyxx.magicxm4.utils.MagicXm4Action

/**
 * ControlCenterNoiseHook — 作用域 com.hihonor.controlcenter（设备中心/控制中心降噪卡）。
 *
 * 逆向结论（discovery/cc/dn/classes.dex）：
 * - DeviceServiceCardDataMgr.createEarphoneNoiseSection(LinkDevice) 无条件建卡（唯一退出=LinkDevice null）
 *   数据来自 EarphoneNoiseData.fromBatteryData(BatteryData)
 *   返回 null → supportedModes=空 + currentMode=UNKNOWN → 按钮全灰（我们的耳机现状）
 * - 点击 EarphoneNoiseSectionController.onNoiseModeClick(NoiseMode)：
 *     4个早退（section禁用/isSetting/佩戴禁用/已是当前模式）
 *     → ncValue = EarphoneNoiseData.toNcModeValue(mode)
 *     → PropertyUtils.setNoiseCtrlMode(deviceId, ncValue, cb)   ← 下发接口
 *
 * ncValue 语义（由 toNcModeValue 推定）：1=降噪 2=透传(AWARENESS) 0=关闭 3=自适应
 *
 * 本 hook 做两件事：
 * 1. fromBatteryData 返回 null 时注入（支持 噪声/感知/关闭 三模式 + 按 last_anc 恢复当前档）
 *    —— 原返回非 null 时用原值，不影响荣耀自家耳机
 * 2. setNoiseCtrlMode 拦截 → 映射为本模块 status → 广播 ACTION_ANC_SELECT
 *    → 复用已打通的 bluetooth 进程 RfcommController → SPP 0x4E 链路
 */
object ControlCenterNoiseHook : HookContext() {
    private const val TAG = "MagicXM4-CtrlCenter"
    @Volatile private var lastClickStackAt = 0L
    @Volatile private var lastAncQueryAt = 0L
    private const val MODE_CLS = "com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseData\$NoiseMode"
    private const val DATA_CLS = "com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseData"

    override fun onHook() {
        // ── 1) 按钮点亮：fromBatteryData 为 null 时注入数据 ──
        runCatching {
            val dataCls = findClass(DATA_CLS)
            val batCls = findClass("com.hihonor.controlviewnew.info.BatteryData")
            val fb = dataCls.getDeclaredMethod("fromBatteryData", batCls).apply { isAccessible = true }
            module.hook(fb).intercept { chain ->
                val orig = chain.proceed()
                if (orig != null) return@intercept orig   // 有真实数据 → 不干预
                // 构造注入对象
                val data = dataCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                // ★ 必须用 findClass（宿主 ClassLoader）：Class.forName 走模块 loader 会 CNFE
                //   （实测: ClassNotFoundException EarphoneNoiseData$NoiseMode，DexPathList 含模块 apk）
                val modeCls = findClass(MODE_CLS)
                val consts = modeCls.enumConstants
                if (consts == null) {
                    Log.w(TAG, "NoiseMode 非 enum？跳过注入")
                    return@intercept data
                }
                // FIX-010：荣耀的 NoiseMode 枚举里本来就有 ADAPTIVE（ordinal 3 → ncValue 3），
                // 上游只注入了三档，这里补上第四档「自适应」。
                val wanted = setOf("NOISE_CANCELLATION", "AWARENESS", "OFF", "ADAPTIVE")
                val modes = java.util.ArrayList<Any>()
                for (c in consts) {
                    if (c.toString() in wanted) modes.add(c)
                }
                dataCls.getDeclaredMethod("setSupportedModes", List::class.java)
                    .apply { isAccessible = true }.invoke(data, modes)
                // ★ 当前档位三级优先（让 UI 显示耳机真实状态，而不是固定默认值 ——
                //   否则用户会误以为"一打开就被自动切到降噪"）：
                //   1) magicxm4_current_anc —— bluetooth 进程下发成功后记录的真实模式
                //   2) magicxm4_last_anc     —— 用户在 App 里设的默认档位
                //   3) 兜底 NOISE_CANCELLATION
                val app0 = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                }.getOrNull()
                fun g(key: String): String? = runCatching {
                    app0?.let { android.provider.Settings.Global.getString(it.contentResolver, key) }
                }.getOrNull()
                fun map(status: String?): String = when (status) {
                    "1" -> "OFF"
                    "2" -> "AWARENESS"
                    "7" -> "ADAPTIVE"
                    "3", "4", "5" -> "NOISE_CANCELLATION"
                    else -> ""
                }
                val cur = map(g("magicxm4_current_anc")).ifEmpty {
                    map(g("magicxm4_last_anc")).ifEmpty { "NOISE_CANCELLATION" }
                }
                val curMode = consts.firstOrNull { it.toString() == cur }
                if (curMode != null) {
                    dataCls.getDeclaredMethod("setCurrentMode", modeCls)
                        .apply { isAccessible = true }.invoke(data, curMode)
                }
                Log.i(TAG, "fromBatteryData 注入成功: modes=$modes current=$cur (orig=null)")
                data
            }
            Log.i(TAG, "installed: EarphoneNoiseData.fromBatteryData [按钮点亮]")
        }.onFailure {
            Log.w(TAG, "install fromBatteryData failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 2) 下发桥：setNoiseCtrlMode → 广播 → 已打通 SPP 链路 ──
        runCatching {
            val pu = findClass("com.hihonor.controlviewnew.utils.PropertyUtils")
            val m = pu.declaredMethods.first { it.name == "setNoiseCtrlMode" }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val deviceId = chain.args.getOrNull(0)
                val nc = (chain.args.getOrNull(1) as? Int) ?: -1
                // ncValue → 模块 status(非空 Int)：降噪=用户默认档位(5/4/3)  透传=2  关闭=1  自适应忽略
                // ★ 必须是 Int：曾用 String 导致 bluetooth 端 getIntExtra("status",0) 读不到
                //   （类型不符返回默认 0，0 不在映射表 → 不发 SPP 帧 → 点击无效）
                val status: Int = when (nc) {
                    1 -> (readLastAnc() ?: "5").toIntOrNull() ?: 5
                    2 -> 2
                    0 -> 1
                    3 -> 7          // ADAPTIVE → 本模块扩展状态 7（欢律 bit 11）
                    else -> -1
                }
                Log.w(TAG, "★ setNoiseCtrlMode(deviceId=$deviceId, nc=$nc) → status=$status")
                // 诊断"进设备中心自动切降噪"：抓调用栈（10s 限频）
                val nowS = System.currentTimeMillis()
                if (nowS - lastClickStackAt > 10000) {
                    lastClickStackAt = nowS
                    val st = Thread.currentThread().stackTrace
                    val frames = st.drop(3).take(10)
                        .filterNot { it.className.startsWith("io.github.yuxiximyxx.magicxm4") || it.className.contains("xposed") || it.className.contains("lsposed") || it.className.contains("LSPosed") }
                        .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
                    Log.w(TAG, "  △ setNoiseCtrlMode 调用栈:\n    $frames")
                }
                var handled = false
                if (status in 0..9) {
                    runCatching {
                        val app = Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                        app?.sendBroadcast(
                            Intent("io.github.yuxiximyxx.magicxm4.ACTION_ANC_SELECT").putExtra("status", status)
                        )
                        Log.w(TAG, "→→ 已广播 ANC status=$status (设备中心)")
                    }.onFailure { Log.w(TAG, "广播失败: ${it.javaClass.simpleName}") }
                    // ★ 荣耀 MBB 对第三方设备的 setNoiseCtrlMode 永不回调（onResult/onError 都不来）
                    //   → onNoiseModeClick 的 isSetting 永真 → 点击一次后所有模式被早退（卡死）。
                    //   这里直接模拟成功回调（$1.onResult 的参数不参与逻辑，只 post V 复位+高亮），
                    //   并跳过原 MBB 调用。
                    val cb = chain.args.getOrNull(2)
                    if (cb != null) {
                        runCatching {
                            cb.javaClass.getMethod("onResult", String::class.java).invoke(cb, "")
                            handled = true
                            Log.w(TAG, "→→ 已模拟 onResult 回调（跳过 MBB 原调用）")
                        }.onFailure { Log.w(TAG, "模拟回调失败: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                } else {
            Log.w(TAG, "nc=$nc 不支持，跳过广播")
                }
                if (handled) {
                    return@intercept null   // setNoiseCtrlMode 返回 void
                }
                // 回退路径：仍走原调用（依赖其回调）
                chain.proceed()
            }
            Log.i(TAG, "installed: PropertyUtils.setNoiseCtrlMode [设备中心下发桥]")
        }.onFailure {
            Log.w(TAG, "install setNoiseCtrlMode failed (可能本进程无此类): ${it.javaClass.simpleName}")
        }

        // ── 3) 观察点击（4个早退排障用） ──
        runCatching {
            val ctlCls = findClass("com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseSectionController")
            val m = ctlCls.getDeclaredMethods().first {
                it.name == "onNoiseModeClick" && it.parameterCount == 1
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                Log.w(TAG, "onNoiseModeClick mode=${chain.args.getOrNull(0)}")
                // 抓调用栈定位"打开页面就自动点击"的触发者（限频：每 5 秒最多 1 条）
                val now = System.currentTimeMillis()
                if (now - lastClickStackAt > 5000) {
                    lastClickStackAt = now
                    val st = Thread.currentThread().stackTrace
                    val frames = st.drop(3).take(8)
                        .filterNot { it.className.startsWith("io.github.yuxiximyxx.magicxm4") || it.className.startsWith("java.lang.reflect") || it.className == "de.robv.android.xposed" || it.className.contains("xposed") || it.className.contains("LSPosed") || it.className.contains("lsposed") }
                        .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
                    Log.w(TAG, "  △ 调用栈:\n    $frames")
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: onNoiseModeClick [观察]")
        }.onFailure {
            Log.w(TAG, "install onNoiseModeClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 4) 设备列表注入：耳机不在 devicemanager.getDevices() 时构造 LinkDevice JSON ──
        // getDeviceList() 唯一来源是 DeviceManager.getDevices()（nearby 提供），
        // 我们的耳机不在其中 → 设备中心不显示（用户反馈"没添加进去"的根因）。
        // LinkDevice 序列化字段（Gson）：deviceId/deviceName/hnDeviceType/deviceNodeId/
        //   macAddress/btMacAddress/prodId/isSelfDevice/hnOnLineTypeList
        // 类型枚举：Third_EarPhone（第三方耳机）
        // 纯字符串操作，不构造 AIDL 对象；已含本机则跳过，避免重复。
        val provCls = runCatching {
            findClass("com.hihonor.controlcenter.superdevice.ControlCenterProvider")
        }.getOrNull()
        if (provCls == null) {
            // 该 hook 只对 controlcenter 进程有意义；其他进程没有这个类，静默跳过（原先刷 3 次 W 噪音）
            Log.d(TAG, "getDeviceList: 宿主无 ControlCenterProvider，跳过")
        } else runCatching {
            val m = provCls.declaredMethods.first {
                it.name == "getDeviceList" && it.parameterCount == 0
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    if (result == null) return@runCatching
                    val ext = result.javaClass.getMethod("getExt").invoke(result) as? String
                        ?: return@runCatching
                    if (ext.contains(ourMac(), ignoreCase = true) || ext.contains(OUR_NAME)) {
                        Log.i(TAG, "设备列表已含WH-1000XM4，跳过注入")
                        return@runCatching
                    }
                    val trimmed = ext.trim()
                    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                        Log.w(TAG, "ext 非数组，跳过: ${trimmed.take(80)}")
                        return@runCatching
                    }
                    // FIX-003 任务1：把上游返回的 ext JSON 全量打出来（与本机真实设备 diff 用）
                    if (isVerboseDump()) Log.i(TAG, "DUMP ext=${ext.take(4000)}")
                    // hnOnLineTypeList 给空数组（Gson 空数组不校验元素类型，安全）
                    val mac = ourMac()
                    val prod = ourProd()
                    val our = "{\"deviceId\":\"$mac\"," +
                            "\"deviceName\":\"$OUR_NAME\"," +
                            "\"hnDeviceType\":\"${HonorDeviceTypeName.current()}\"," +
                            "\"deviceNodeId\":\"$mac\"," +
                            "\"macAddress\":\"$mac\"," +
                            "\"prodId\":\"$prod\"," +
                            "\"isSelfDevice\":false," +
                            "\"hnOnLineTypeList\":[]}"
                    val merged = if (trimmed.length <= 2) "[$our]"
                                 else trimmed.dropLast(1) + ",$our]"
                    result.javaClass.getMethod("setExt", String::class.java)
                        .invoke(result, merged)
                    Log.w(TAG, "★ 设备列表已注入 $OUR_NAME (${HonorDeviceTypeName.current()})")
                }.onFailure {
                    Log.w(TAG, "getDeviceList 注入失败: ${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }
            Log.i(TAG, "installed: ControlCenterProvider.getDeviceList [设备注入]")
        }.onFailure {
            Log.w(TAG, "install getDeviceList failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 5) 球列表注入：设备中心（NewControlHomeActivity/控制中心）用的是
        //    DeviceBallManager 的球体系，不走 getDeviceList（实测 hook 装上但零触发）。
        //    在 getAllDeviceInfo/getDeviceInfoList 返回前 append 我们的 DeviceInfo，
        //    batteryData 保持 null → fromBatteryData 注入 hook 兜底点亮按钮。
        runCatching {
            val mgrCls = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
            val infoCls = findClass("com.hihonor.controlviewnew.info.DeviceInfo")
            val bdCls = findClass("com.hihonor.controlviewnew.info.BatteryData")   // 双耳电量
            val typeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
            // 类型名唯一取值点（可由 magicxm4_device_type 覆盖），顺便 dump 全表供真机选型
            HonorDeviceTypeName.dumpOnce(typeCls.enumConstants)
            val thirdEar = HonorDeviceTypeName.pick(typeCls.enumConstants)
            val ctor = infoCls.declaredConstructors.firstOrNull {
                it.parameterCount == 7 && it.parameterTypes[0] == String::class.java
            }
            if (thirdEar == null || ctor == null) throw IllegalStateException("DeviceInfo 构造/类型不可用")
            ctor.isAccessible = true

            fun buildOur(): Any? = runCatching {
                // ★ FIX-003 根因：7 参构造签名是
                //   (id, name, type, shapeFeature, isSelf, isOnline, isShow)
                //   上游把 isSelf 传成了 true → SortUtils.DeviceSortUtils() 过滤 !isSelf()，
                //   我们的条目进不了 gridIndexMap → createDeviceBall 第一道门直接 return null
                //   （真机日志 14 次 NULL 的唯一原因）。这里必须传 false。
                // 在线态跟随真实连接状态（离线时球变灰，不是消失）
                val onlineNow = isBtConnectedNow()
                // 顺手把桥值校准，避免「蓝牙已断但 magicxm4_connected 还是 1」导致卡片一直亮
                runCatching {
                    currentApp()?.let {
                        android.provider.Settings.Global.putString(
                            it.contentResolver, "magicxm4_connected", if (onlineNow) "1" else "0"
                        )
                    }
                }
                diag("online", "live=$onlineNow bridge=${isOurConnected()}")
                val info = ctor.newInstance(ourMac(), OUR_NAME, thirdEar, 0, false, onlineNow, true)
                // 7参构造不含 prodId/btMacAddress —— 球渲染按 prodId 查图标资源，
                // null 会静默跳过渲染（列表有数据、UI 不显示的根因）
                runCatching { infoCls.getMethod("setProdId", String::class.java).invoke(info, ourProd()) }
                runCatching { infoCls.getMethod("setBtMacAddress", String::class.java).invoke(info, ourMac()) }
                runCatching { infoCls.getMethod("setName", String::class.java).invoke(info, OUR_NAME) }
                runCatching { infoCls.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(info, 1) }
                // createDeviceBall 第二道门是 deviceInfo.isShow()，显式置真，不依赖构造参数语义
                runCatching {
                    infoCls.getMethod("setIsShow", Boolean::class.javaPrimitiveType).invoke(info, true)
                }
                // 双耳电量注入（列表构建路径）
                feedDualBattery(info)
                info
            }.getOrNull()

            var injectedCount = 0
            // getAllDeviceInfo/getDeviceInfoList=全量，getShowRemoteDeviceInfoList=UI 展示过滤出口
            for (mName in listOf("getAllDeviceInfo", "getDeviceInfoList", "getShowRemoteDeviceInfoList")) {
                val m = mgrCls.declaredMethods.firstOrNull { it.name == mName } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val result = chain.proceed() as? java.util.List<*>
                    // FIX-003 任务1：dump 列表里每个 DeviceInfo 的全字段（含本机真实设备），用于 diff
                    result?.let { dumpDeviceInfoList(mName, it) }
                    if (result != null) {
                        val exists = result.any {
                            runCatching { isOurMac(it?.javaClass?.getMethod("getId")?.invoke(it)?.toString()) }.getOrDefault(false)
                        }
                        // ★ FIX-005：曾经「仅在线时注入」→ 断开后球直接消失（用户反馈）。
                        //   现在改为**只要知道 MAC 就注入**，在线/离线由 DeviceInfo.isOnline 表达，
                        //   这样断开后球会变灰留存，而不是凭空消失。
                        if (!exists) {
                            buildOur()?.let { info ->
                                // List<*> 为只读视图，反射调 add；视图可能不可 add（unmodifiable）→ 全程保护
                                runCatching {
                                    result.javaClass.getMethod("add", Any::class.java)
                                        .invoke(result, info)
                                    injectedCount++
                                    Log.w(TAG, "★ 球列表已注入 $OUR_NAME → $mName (总数=${result.size})")
                                    diag("list", "$mName size=${result.size} injected=$injectedCount")
                                    dumpFields("injected[$mName]", info)
                                }.onFailure {
                                    Log.w(TAG, "$mName add 失败(视图只读?): ${it.javaClass.simpleName}")
                                }
                            }
                        }
                    }
                    result
                }
                Log.i(TAG, "installed: DeviceBallManager.$mName [球列表注入]")
            }
            // 空态由数量方法决定（"未发现其他设备"）：原值0且我们已注入球 → 至少1
            mgrCls.declaredMethods.firstOrNull { it.name == "getShowRemoteDeviceNum" }?.let { m ->
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val n = chain.proceed() as? Int ?: 0
                    val fixed = if (n <= 0 && isOurConnected()) 1 else n
                    if (fixed != n) Log.w(TAG, "★ getShowRemoteDeviceNum $n → $fixed")
                    fixed
                }
                Log.i(TAG, "installed: DeviceBallManager.getShowRemoteDeviceNum [数量修正]")
            }
            // ★ 根上修：DeviceBallManager.deviceInfoMap 无我们的设备 → getDeviceInfoById 返 null
            //   → updateDeviceOnlineStatus 空转 / createEarphoneNoiseSection 拿不到 batteryData /
            //   createDeviceServiceCard 电量查询断链。统一在查询出口兜底（缓存单例）。
            var ourInfoCache: Any? = null
            mgrCls.declaredMethods.firstOrNull { it.name == "getDeviceInfoById" }?.let { m ->
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val id = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    if (isOurMac(id?.toString()) && ret == null) {
                        val cached = ourInfoCache ?: buildOur()?.also { ourInfoCache = it }
                        if (cached != null) {
                            Log.w(TAG, "★ getDeviceInfoById(${ourMac()}) null → 兜底 DeviceInfo")
                        }
                        cached
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: DeviceBallManager.getDeviceInfoById [map 兜底]")
            }

            // ── 决定性观察：球创建到哪一步（id / 是否返回 null） ──
            runCatching {
                val lmCls = findClass("com.hihonor.controlviewnew.widget.DeviceBallLayoutManager")
                val cmb = lmCls.declaredMethods.firstOrNull {
                    it.name == "createDeviceBall" && it.parameterCount >= 1
                }
                if (cmb != null) {
                    cmb.isAccessible = true
                    module.hook(cmb).intercept { chain ->
                        val info = chain.args.getOrNull(0)
                        val id = runCatching { info?.javaClass?.getMethod("getId")?.invoke(info) }.getOrNull()
                        // ★ FIX-003 双保险：本机 createDeviceBall 的第一道门是
                        //   gridIndexMap.containsKey(id)；该表由 relayoutRemainingRemoteDeviceBalls()
                        //   用 SortUtils.DeviceSortUtils(getShowRemoteDeviceInfoList()) 重建，
                        //   任何时序/过滤差异都会让我们缺席。这里建球前直接补槽位。
                        if (isOurMac(id?.toString()) && chain.thisObject != null) {
                            runCatching {
                                val f = chain.thisObject!!.javaClass
                                    .getDeclaredField("gridIndexMap").apply { isAccessible = true }
                                @Suppress("UNCHECKED_CAST")
                                val map = f.get(chain.thisObject) as? MutableMap<Any?, Any?>
                                if (map != null && !map.containsKey(id)) {
                                    var idx = runCatching {
                                        info?.javaClass?.getMethod("getIndex")?.invoke(info) as? Int ?: -1
                                    }.getOrDefault(-1)
                                    if (idx < 0) idx = map.size
                                    map[id] = idx
                                    Log.w(TAG, "★ gridIndexMap 补槽位 id=$id → index=$idx (size=${map.size})")
                                }
                            }.onFailure { Log.w(TAG, "补 gridIndexMap 失败: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        val ret = chain.proceed()
                        // 在线态分流（决定电量/降噪 section 走 online 路径）在
                        // BasicDeviceBallView.isOnline()，由 devicemanager 推送驱动，
                        // 第三方耳机无此推送 → 恒 false → 走离线卡（无电量无降噪）。
                        // 球创建成功后直接把 adapter 置在线（setIsOnline 会同步到 ballView）。
                        if (isOurMac(id?.toString()) && ret != null) {
                            runCatching {
                                ret.javaClass.getMethod("setIsOnline", Boolean::class.javaPrimitiveType)
                                    .invoke(ret, isBtConnectedNow())
                                Log.w(TAG, "★ setIsOnline(${isBtConnectedNow()}) on adapter ($OUR_NAME)")
                            }.onFailure { Log.w(TAG, "setIsOnline 失败: ${it.javaClass.simpleName}: ${it.message}") }
                            // 缓存 adapter：响铃高亮/熄灭要用它调 notifyStatusChangeWithSpecialDev
                            ourAdapterRef = ret
                            // ★ 主动驱动在线刷新：updateDeviceOnlineStatus 是 DeviceBallManager
                            //   的公开状态入口（devicemanager 推送也走它），会同步 ballView.isOnline
                            runCatching {
                                val mCls = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                                val inst = mCls.getMethod("getInstance").invoke(null)
                                mCls.getMethod(
                                    "updateDeviceOnlineStatus",
                                    String::class.java,
                                    Boolean::class.javaPrimitiveType
                                ).invoke(inst, ourMac(), isBtConnectedNow())
                                Log.w(TAG, "★ updateDeviceOnlineStatus(${ourMac()}, ${isBtConnectedNow()})")
                            }.onFailure { Log.w(TAG, "updateDeviceOnlineStatus 失败: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        if (isOurMac(id?.toString()) || ret == null) {
                            Log.w(TAG, "▶ createDeviceBall(id=$id) → ${if (ret == null) "NULL(失败)" else "OK"} prodId=${ourProd()}")
                            // FIX-003：球建不出来时，把传进来的 DeviceInfo 全字段打出来（供与本机真实设备 diff）
                            if (isOurMac(id?.toString())) {
                                diag("ball", "id=$id ok=${ret != null} prod=${ourProd()}")
                            }
                            // ★ 换头戴图标：荣耀按 prodId 查自己的图标表，借用的机型码没有图标
                            //   就回落成耳塞。这里在球建好后直接把图标换成我们自绘的头戴图标。
                            // 注意：createDeviceBall 返回的是 **BasicDeviceBallViewAdapter**，不是 View，
                            // 换图标要等球视图自己的 hook（见下面 updateEarphoneUIByOnlineType）。
                            if (isOurMac(id?.toString()) && ret != null) {
                                diag("ball_class", ret.javaClass.name)
                            }
                            if (isOurMac(id?.toString()) && (ret == null || isVerboseDump())) {
                                dumpFields("createDeviceBall.args[0]", info)
                                runCatching { ret?.let { dumpFields("createDeviceBall.ret", it) } }
                            }
                        }
                        ret
                    }
                    Log.i(TAG, "installed: DeviceBallLayoutManager.createDeviceBall [球创建观察+在线态]")
                } else {
                    Log.w(TAG, "createDeviceBall 未找到")
                }
            }.onFailure { Log.w(TAG, "createDeviceBall 观察失败: ${it.javaClass.simpleName}") }

            // ── 6) 布局修正（关键门③）：
            //    createSections 里 {电量,降噪} section 的前置条件是
            //    DeviceLayoutConfig.hasSection(...)，即 selectLayout(type).layoutMask 含位。
            //    REMOTE_EARPHONE 的 mask 明确含 EARPHONE_BATTERY+EARPHONE_NOISE（clinit or 运算），
            //    但 selectLayout 的 switch 可能把 Third_EarPhone 落到 default → REMOTE_PHONE
            //    （与"卡片只有响铃/连接、无电量无降噪"实拍吻合）→ 强制归位。
            runCatching {
                val cfgCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceLayoutConfig")
                val sel = cfgCls.declaredMethods.first {
                    it.name == "selectLayout" && it.parameterCount >= 1
                }.apply { isAccessible = true }
                val remoteEar = cfgCls.enumConstants?.firstOrNull { it.toString() == "REMOTE_EARPHONE" }
                    ?: throw IllegalStateException("REMOTE_EARPHONE 枚举不存在")
                module.hook(sel).intercept { chain ->
                    val typeName = chain.args.getOrNull(0)?.toString()
                    val ret = chain.proceed()
                    if (typeName == HonorDeviceTypeName.current() && ret !== remoteEar) {
                        Log.w(TAG, "★ selectLayout($typeName) $ret → REMOTE_EARPHONE")
                        remoteEar
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: DeviceLayoutConfig.selectLayout [布局修正→REMOTE_EARPHONE]")
            }.onFailure {
                Log.w(TAG, "selectLayout hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 7) section 创建：观察 + ★ 3.0.11 离线数据层否决 ──
            //   原为纯观察（区分"门③拒绝"vs"创建返回null"）。3.0.11 起在同一
            //   挂点上做数据层否决：设备是我们的、且真实连接态为离线时，
            //   直接返回 null 不让宿主生成电量/噪声段——create*Section 返回
            //   null 是宿主自身的既有退出形态（LinkDevice 为 null 时它就
            //   这么返回，gen 按「无此段」继续装配），调用方认账。卡片数据
            //   在视图创建前就是离线形态，展开动画第一帧即目标高度，
            //   不再需要视图层事后隐藏（见第 8/11/12 节的退役说明）。
            runCatching {
                val mgr2 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val mNoise = mgr2.declaredMethods.filter { it.name == "createEarphoneNoiseSection" }
                val mBat = mgr2.declaredMethods.filter { it.name == "createEarphoneBatterySection" }
                for (m in mNoise + mBat) {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val dev = chain.args.getOrNull(0)
                        val devId = runCatching { dev?.javaClass?.getMethod("getDeviceId")?.invoke(dev) }.getOrNull()
                        if (devId != null && isOurMac(devId.toString()) && !isOurConnected()) {
                            Log.w(TAG, "★ ${m.name}(deviceId=$devId) 真实离线 → null（数据层不装配此段）")
                            diag("section", "${m.name} dev=$devId vetoed-offline（3.0.11）")
                            return@intercept null
                        }
                        val ret = chain.proceed()
                        Log.w(TAG, "▶ ${m.name}(deviceId=$devId) → ${if (ret == null) "null" else "SEC(" + ret.hashCode() + ")"}")
                        if (devId == null || isOurMac(devId.toString())) {
                            diag("section", "${m.name} dev=$devId ret=${ret != null}")
                        }
                        ret
                    }
                }
                Log.i(TAG, "installed: createEarphoneNoise/BatterySection [创建观察+离线否决]")
            }.onFailure {
                Log.w(TAG, "section 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 7b) createSections 观察：config 布局 + 返回装配数（定位 sections=null 环节） ──
            runCatching {
                val mgr4 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val cs = mgr4.declaredMethods.first {
                    it.name == "createSections" && it.parameterCount >= 3
                }.apply { isAccessible = true }
                module.hook(cs).intercept { chain ->
                    val config = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    val size = (ret as? List<*>)?.size
                    Log.w(TAG, "▶ createSections(config=$config) → size=$size")
                    diag("sections", "size=$size cls=" + (ret as? List<*>)?.joinToString(",") { it?.javaClass?.simpleName ?: "null" })
                    // 【3.0.11 退役】原先装配完成后用 0/250/700/1500ms 的
                    //   postDelayed 链对窗口根视图反复跑 cleanupCard +
                    //   applyOfflineVisibility 做离线收缩——视图层事后隐藏与
                    //   展开动画赛跑，正是断连开卡「先撑成大片空白高卡、再
                    //   缩回」的来源之一。离线形态已改由数据层保证（第 7 节
                    //   create*Section 离线否决 + 第 8 节 postFixSections
                    //   摘段），这里只保留装配观察，不再延迟碰任何视图。
                    ret
                }
                Log.i(TAG, "installed: DeviceServiceCardDataMgr.createSections [装配观察]")
            }.onFailure {
                Log.w(TAG, "createSections 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 9) ★ 最后一环：genDeviceServiceInfo 开头 c2.s.o(deviceId) 查 LinkDevice，
            //    查不到 → log("linkDevice is null") → return null → createSections 永不执行
            //    （sections=null 根因）。在 c2/s 注册表查询出口兜底注入 LinkDevice。
            runCatching {
                val sCls = findClass("c2.s")
                val o = sCls.declaredMethods.first {
                    it.name == "o" && it.parameterCount == 1 &&
                            it.returnType.name.endsWith("LinkDevice")
                }.apply { isAccessible = true }
                val ldCls = findClass("com.hihonor.controlcenter_aar.bean.LinkDevice")
                val typeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
                HonorDeviceTypeName.dumpOnce(typeCls.enumConstants)
                val third = HonorDeviceTypeName.pick(typeCls.enumConstants)
                    ?: throw IllegalStateException("HnDeviceProductType 枚举为空")
                val ctor = ldCls.declaredConstructors.firstOrNull {
                    it.parameterCount == 7 && it.parameterTypes[0] == String::class.java
                }?.apply { isAccessible = true }
                    ?: throw IllegalStateException("LinkDevice 7参构造不存在")
                var ldCache: Any? = null
                module.hook(o).intercept { chain ->
                    val id = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    if (isOurMac(id?.toString()) && ret == null) {
                        val ld = ldCache ?: runCatching {
                            // (udid, name, type, nodeId, isLocal, onlineList, prodId)
                            ctor.newInstance(
                                ourMac(), OUR_NAME, third, ourMac(),
                                false, java.util.ArrayList<Any>(), OUR_PROD
                            )
                        }.getOrNull()?.also { ldCache = it }
                        if (ld != null) Log.w(TAG, "★ c2.s.o(${ourMac()}) null → 注入 LinkDevice")
                        ld
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: c2.s.o [LinkDevice 注册表兜底]")
            }.onFailure {
                // FIX-003 任务3.1：本机构建里 c2.s 已换名，这条 hook 只是「注册表兜底」，
                // 而 sections=5 的链路在本机已实证能走通 → 降为 debug 日志，不再刷噪音。
                Log.d(TAG, "c2.s.o hook 跳过（本机构建类名不同）: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11a) ★ FIX-005：连接动作文案 —— 本机用 BLE 连接态判断，经典蓝牙恒 false ──
            //   DeviceServiceCardDataMgr:335 用
            //     AudioUtils.getInstance().isDeviceBleConnected(btMac) ? 断开连接 : 去连接
            //   XM4 走经典蓝牙，BLE 查询永远 false → 连着也显示「去连接」。
            //   这里对该方法做匹配替换：是我们且已连接 → true（显示「断开连接」）。
            runCatching {
                val audioCls = findClass("com.hihonor.controlviewnew.utils.AudioUtils")
                val bleConnected = audioCls.declaredMethods.first {
                    it.name == "isDeviceBleConnected" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(bleConnected).intercept { chain ->
                    val mac = chain.args.getOrNull(0)?.toString()
                    if (isOurMac(mac) && isOurConnected()) {
                        diag("blehook", "isDeviceBleConnected($mac) → true（经典蓝牙伪装）")
                        true
                    } else chain.proceed()
                }
                Log.i(TAG, "installed: AudioUtils.isDeviceBleConnected [去连接/断开连接]")
                diag("hook_ble", "installed")
            }.onFailure {
                Log.w(TAG, "isDeviceBleConnected hook 失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("hook_ble", "fail: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11i) ★ 卡片出现时机（唯一稳定的一处）──
            //   服务卡的展开不走 updateEarphoneUIByOnlineType，createSections 在断连时也可能不跑，
            //   所以挂在 View.onAttachedToWindow 上：只有当这个 View 的子树里同时有
            //   `device_name` == WH-1000XM4 时才算「我们的卡片刚挂上」，再做离线收缩与图标替换。
            runCatching {
                val attached = android.view.View::class.java.getDeclaredMethod("onAttachedToWindow")
                module.hook(attached).intercept { chain ->
                    val ret = chain.proceed()
                    runCatching {
                        val v = chain.thisObject as? android.view.ViewGroup
                        if (v != null && v.childCount > 0) {
                            // ★ 3.0.10：球图标不在这里事后换了 —— 改走宿主选图入口的
                            //   同步改结果（onHook 第 12 节）。原先 attach 后
                            //   0/120/400/1000ms 的 postDelayed 换图全撤：迟到的
                            //   换图撞上展开 morph 的测量/飞行，正是「先变大
                            //   再缩小」的来源。
                            val nameView = findByIdName(v, "device_name") as? android.widget.TextView
                            if (nameView?.text?.toString()?.trim() == OUR_NAME) {
                                // 【3.0.11 退役】原先在 attach 后 0/200/600/1400ms
                                //   反复跑 cleanupCard + applyOfflineVisibility——
                                //   延迟隐藏落在展开动画中途，卡片先按含电量/
                                //   噪声段的高度展开、隐藏落地后又缩回。离线
                                //   形态已由数据层保证（第 7/8 节），attach
                                //   时只记一笔诊断，不再动任何视图。
                                diag("card", "card attached online=${isOurConnected()}")
                            }
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: View.onAttachedToWindow [卡片时机]")
            }.onFailure {
                Log.w(TAG, "onAttachedToWindow hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11h) 【3.0.8 已拆除】设备球图标的自绘替换 ──
            //   原先 hook ImageView.setImageDrawable/setImageResource 把球图标换成模块
            //   自绘的 ic_headphone 矢量图，大小/位置/动画都与宿主原生对不上。3.0.8 起
            //   球图标改由 fixBallIcon 在视图树上换成宿主自带的 device_icon_default_earphone
            //   （原生头戴位图），此处不再拦截任何 ImageView 设置入口。
            //   【3.0.10 追记】fixBallIcon 亦已退役：球图标改在第 12 节的选图
            //   入口同步改结果，模块从不在绘制后换球的图。

            // ── 11c) ★ FIX-005：用真实连接态替换宿主的在线态入参 ──
            //   DeviceServiceCardAdapter.updateEarphoneUIByOnlineType(boolean) 会按在线态
            //   驱动卡片状态（连接文案等）。耳机是通过 SPP 在线的，这套在线态
            //   判断链在本机恒 false，故把入参换成真实连接态。历史上它还驱动
            //   响铃查找的 NORMAL/DISABLED；响铃查找自 3.0.12 起在数据层整项
            //   摘除、卡上已无此行，这里不再涉及。
            runCatching {
                // BasicDeviceBallView.updateEarphoneUIByOnlineType(z) 一处驱动两件事：
                //   adapter.updateEarphoneUIByOnlineType(z)  → 响铃查找 NORMAL/DISABLED
                //   adapter.updateConnectAction(container, z) → 「断开连接 / 去连接」文案
                // 本机这套在线判断恒 false（耳机是 SPP 在线、蓝牙栈视角不同），故用真实状态替换入参。
                val ballCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val upd = ballCls.declaredMethods.first {
                    it.name == "updateEarphoneUIByOnlineType" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(upd).intercept { chain ->
                    val real = isOurConnected()
                    chain.args[0] = real
                    diag("finddevice", "updateEarphoneUIByOnlineType($real)")
                    val ret = chain.proceed()
                    // 响铃查找（WH-1000XM4 没有响铃功能）自 3.0.12 起在数据层
                    //   去掉（postFixSections 按 bsId 整项摘除 ServiceCardInfo，
                    //   连接/离线都摘），卡片装配时即无此行，视图层不再按文案
                    //   隐藏。此处只剩缓存我方球 View 的本职（见下方 ourBallRef）。
                    runCatching {
                        val self = chain.thisObject as? android.view.View
                        if (self != null) {
                            if (findTextView(self, OUR_NAME) != null) {
                                ourBallRef = java.lang.ref.WeakReference(self)
                                // ★ 3.0.10：宿主刷新球状态重设图标时，选图 hook 会在它
                                //   取图的那一次调用内同步改回头戴图，不再需要这里
                                //   postDelayed 事后补换（原 0/150/500ms 已全撤）。
                            }
                            // 【3.0.11 退役】原先此处在状态刷新后 0/300/900/2000ms
                            //   反复对 rootView 跑 cleanupCard +
                            //   applyOfflineVisibility——与 11i/7b 同属视图层赛跑
                            //   补救（断连开卡先撑高再缩回的来源之一），离线形态
                            //   改由数据层保证（第 7 节否决 + 第 8 节摘段）后整链
                            //   删除。本 hook 的本职（用真实连接态替换入参，驱动
                            //   连接文案等状态）是数据层行为，保持不变。
                        } else {
                            diag("hide", "thisObject 不是 View: ${chain.thisObject?.javaClass?.name}")
                        }
                    }.onFailure { Log.w(TAG, "清理卡片失败: ${it.message}") }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.updateEarphoneUIByOnlineType [连接文案]")
                diag("hook_finddevice", "installed")
            }.onFailure {
                Log.w(TAG, "updateEarphoneUIByOnlineType hook 失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("hook_finddevice", "fail: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 12) ★ 3.0.10：球图标改在宿主选图入口同步改结果（弃视图树手术）──
            //   3.0.8/3.0.9 的 fixBallIcon 靠 attach / 状态刷新的 postDelayed
            //   （0/120/400/1000ms、0/150/500ms）在绘制后换 drawable —— 展开
            //   卡片的 morph 链（点球 → 球飞行放大成卡片）全程按宿主 initIcon
            //   选定的那张图做测量与飞行，迟到的换图一律在飞行中被重新测量，
            //   就是真机录屏里的「点开球还是先变大再缩小」（3.0.9 把判定收窄
            //   到球本体、加了动画窗口跳过也没堵住：判定再准，换图时机本身
            //   就是错的）。
            //   MagicPods v2.10.6 资源潜行实证：球的图是宿主
            //   BasicDeviceBallView.initIcon 用资源 API（ImageView.setImage
            //   Resource / Resources.getDrawable）在 device_icon_default_
            //   earphone / device_icon_in_ear_earphone_shadow /
            //   device_icon_semi_in_ear_earphone_shadow 三张里选的。
            //   本版改为 hook 这批交付入口（详见 installBallIconHook）：调用
            //   属于 XM4 的球时，在同一次调用内把资源换成宿主头戴图
            //   device_icon_default_earphone —— 绘制前同步生效，宿主的测量、
            //   缓存、morph 动画从头到尾只见过这一张图；模块从不在绘制后
            //   换球的图。initIcon 的确切签名/取图组合不在静态材料里
            //   （潜行只记录到「经由资源 API 取三张图」），故三个入口全挂，
            //   并在装载时把球的图标相关方法面打进 diag 备查。
            //   【3.0.13 追记】其中 getDrawable 两层（Resources / Context）
            //   已被真机证伪并整体撤除：没有视图上下文，靠调用栈 + 在场
            //   登记的作用域限定分不清在给哪颗球取图，球阵里别家耳机的
            //   球全被误换成头戴图。本版只留 ImageView 的
            //   setImageResource / setImageDrawable 两入口，沿父链找到
            //   球根、读 DeviceInfo 正向验明是 XM4 的球才换（详见
            //   installBallIconHook 函数头注）。
            runCatching {
                installBallIconHook()
                Log.i(TAG, "installed: 球图标选图同步改结果 [3.0.10/3.0.13 验球正身]")
            }.onFailure {
                Log.w(TAG, "球图标选图 hook 装载失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("hook_ballicon", "fail: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 12b) ★ 3.0.14：全屏球阵页 XM4 球图【定位探针】（只观察、绝不改图）──
            //   现象（真机两张截图先后证实）：下拉面板里的球 3.0.13 的门
            //   工作正常；但从面板点进的全屏球阵页里，XM4 的球在 3.0.13
            //   变回耳塞图（3.0.10 的全局 getDrawable 在世时它曾是头戴图，
            //   且当时全阵列误伤——证明该页与面板共用同一套取图资源、
            //   且本 hook 的代码在该页所在进程里运行）。
            //   本仓静态材料能定的：球由 controlviewnew 的
            //   DeviceBallLayoutManager.createDeviceBall 按 DeviceInfo
            //   逐颗创建（BasicDeviceBallViewAdapter → BasicDeviceBallView），
            //   全屏页与面板是同一套球系统；XM4 的球由本模块注入
            //   DeviceInfo（MAC/WH-1000XM4/Third）产生，别家球是宿主原生
            //   数据。静态材料定不了的：该页里耳塞图递进图标位的那一次
            //   调用，receiver 的祖先链上球根的确切类名/层数、球根在该页
            //   构图下还读不读得到 DeviceInfo。故本版先装探针把这一次调用
            //   的实况钉死（祖先链类名序列 + 球根命中形态 + 名牌锚点），
            //   下一版再按实况装带视图本人的严格门。探针只记不改：
            //   不换任何图、不动任何返回值，控制面与 3.0.13 逐行一致；
            //   全局 getDrawable hook 不复活。
            runCatching {
                installAamBallGridProbe()
            }.onFailure {
                Log.w(TAG, "球阵探针装载失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("gridprobe", "fail: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11d) ★ FIX-005：「更多设置」点了没反应 —— 自己拉起蓝牙详情页 ──
            //   jumpToMoreSettings: DeviceBallManager.getDeviceInfoById(id) 为 null 时只打日志不 return，
            //   紧接着就调 deviceInfoById.getDeviceType() → NPE，点击被吞掉。
            //   直接拦截：是我们的设备就发 Honor 蓝牙详情页 intent。
            runCatching {
                val mgrCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val jump = mgrCls.declaredMethods.first {
                    it.name == "jumpToMoreSettings" && it.parameterCount == 2 &&
                        it.parameterTypes[1] == String::class.java
                }.apply { isAccessible = true }
                module.hook(jump).intercept { chain ->
                    val ctx = chain.args.getOrNull(0) as? android.content.Context
                    val id = chain.args.getOrNull(1)?.toString()
                    if (ctx != null && isOurMac(id)) {
                        runCatching {
                            // 优先打开 QuickBuds（用户偏好：更多设置 = 完整的耳机控制界面）；
                            // 未安装时回落到 Honor 蓝牙详情页。
                            // 打开本模块自带的耳机详情页：不依赖 QuickBuds / 系统页面，
                            // 数据走同一个 Settings.Global 桥 + 同一条 ACTION_ANC_SELECT 下发通道。
                            // 注意：ctx 是 controlcenter 的 Context，setClassName(ctx, ...) 会拼成
                            //   com.hihonor.controlcenter/io.github... → ActivityNotFound。
                            //   必须显式给出包名。
                            // 优先 QuickBuds（功能最全）。注意：controlcenter 受包可见性限制，
                            // getLaunchIntentForPackage 会返回 null，所以这里用显式组件 + 失败回落。
                            val qbIntent = android.content.Intent().setClassName(
                                "com.spizganed.quickbuds",
                                "com.spizganed.quickbuds.ui.MainActivity"
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            val startedQb = runCatching { ctx.startActivity(qbIntent); true }.getOrDefault(false)
                            if (!startedQb) {
                                ctx.startActivity(
                                    android.content.Intent()
                                        .setClassName(
                                            "io.github.yuxiximyxx.magicxm4",
                                            "io.github.yuxiximyxx.magicxm4.EarphoneDetailActivity"
                                        )
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            diag("more", (if (startedQb) "已跳转 QuickBuds" else "已跳转模块详情页") + " id=$id")
                        }.onFailure { diag("more", "跳转失败: ${it.javaClass.simpleName}: ${it.message}") }
                        null
                    } else chain.proceed()
                }
                Log.i(TAG, "installed: jumpToMoreSettings [更多设置兜底]")
            }.onFailure {
                Log.w(TAG, "更多设置 hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11g) ★ FIX-008：在 DeviceServiceCardDataMgr 层接管动作 ──
            //   AudioUtils 那一层 hook 实测不生效（点击根本没走到），而本类的其它 hook
            //   （genDeviceServiceInfo / jumpToMoreSettings）都稳定生效 → 就在这层接管。
            runCatching {
                val dmgr = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")

                // 断开
                dmgr.declaredMethods.firstOrNull {
                    it.name == "toDisConnectDev" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }?.let { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val id = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(id)) {
                            btA2dpAction(id!!, connect = false)
                            diag("act", "disconnect id=$id")
                            "disconnect_device"
                        } else chain.proceed()
                    }
                    Log.i(TAG, "installed: toDisConnectDev [断开]")
                }

                // 重连（离线卡的「去连接」）
                dmgr.declaredMethods.firstOrNull {
                    it.name == "toConnectOfflineDev" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }?.let { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val id = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(id)) {
                            btA2dpAction(id!!, connect = true)
                            diag("act", "connect id=$id")
                            "to_connect_device"
                        } else chain.proceed()
                    }
                    Log.i(TAG, "installed: toConnectOfflineDev [连接]")
                }

                // 响铃查找
                dmgr.declaredMethods.firstOrNull {
                    it.name == "findDevice" && it.parameterCount == 3 &&
                        it.parameterTypes[1] == String::class.java
                }?.let { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val id = chain.args.getOrNull(1)?.toString()
                        if (isOurMac(id)) {
                            // ★ FIX-010：做成开关 —— 第一次点开始响并高亮，再点停止并熄灭
                            ringOn = !ringOn
                            requestFindBuds(ringOn)
                            highlightFind(id, chain.thisObject, dmgr, ringOn)
                            diag("act", "find id=$id on=$ringOn")
                            ""
                        } else chain.proceed()
                    }
                    Log.i(TAG, "installed: findDevice [响铃开关]")
                }

                // 延迟写一条安装回执：hook 安装发生在 Application 之前，此刻 diag 写不出去
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    diag("hook_mgr", "installed（disconnect/connect/find 已接管）")
                }, 10_000L)
            }.onFailure {
                Log.w(TAG, "manager 动作接管失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11f) ★ FIX-007：接管三个真实动作 ──
            //   响铃查找：荣耀是「把手机音频路由到耳机」，虚拟设备没路由 → 改为发欢律 0x0400
            //   断开/重连：自己驱动 A2DP+HFP，绕开对本机设备表/回调链的依赖
            runCatching {
                val audioCls2 = findClass("com.hihonor.controlviewnew.utils.AudioUtils")

                val startFind = audioCls2.declaredMethods.firstOrNull {
                    it.name == "startFindDevice" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (startFind != null) {
                    startFind.isAccessible = true
                    module.hook(startFind).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { requestFindBuds(true); true } else chain.proceed()
                    }
                }

                val stopFind = audioCls2.declaredMethods.firstOrNull {
                    it.name == "stopFindDevice" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (stopFind != null) {
                    stopFind.isAccessible = true
                    module.hook(stopFind).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { requestFindBuds(false); null } else chain.proceed()
                    }
                }

                val disc = audioCls2.declaredMethods.firstOrNull {
                    it.name == "disconnect" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (disc != null) {
                    disc.isAccessible = true
                    module.hook(disc).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { btA2dpAction(mac!!, connect = false); null } else chain.proceed()
                    }
                }

                val recon = audioCls2.declaredMethods.firstOrNull {
                    it.name == "reconnect" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (recon != null) {
                    recon.isAccessible = true
                    module.hook(recon).intercept { chain ->
                        val mac = chain.args.getOrNull(0)?.toString()
                        if (isOurMac(mac)) { btA2dpAction(mac!!, connect = true); null } else chain.proceed()
                    }
                }
                Log.i(TAG, "installed: AudioUtils start/stopFindDevice + disconnect/reconnect [实动作接管]")
            }.onFailure {
                Log.w(TAG, "AudioUtils 动作接管失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11e) ★ FIX-005：「透传」→「通透」──
            //   文案来自 R.string.earphone_noise_control_awareness，在 initModeButton 里 setText(int)。
            //   initModeButton(View, int, int) 执行后，把按钮文字改掉即可。
            runCatching {
                val ctlCls2 = findClass("com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseSectionController")
                val initBtn = ctlCls2.declaredMethods.first {
                    it.name == "initModeButton" && it.parameterCount == 3
                }.apply { isAccessible = true }
                module.hook(initBtn).intercept { chain ->
                    val ret = chain.proceed()
                    runCatching {
                        val v = chain.args.getOrNull(0) as? android.view.View
                        val tv = v?.findViewById(
                            v.resources.getIdentifier("noise_mode_text", "id", "com.hihonor.controlcenter")
                        ) as? android.widget.TextView
                        if (tv != null && tv.text?.toString() == "透传") {
                            tv.text = "通透"
                            diag("label", "透传 → 通透")
                        }
                        // ★ 去掉「自适应」：WH-1000XM4 没有自适应降噪，卡片却按 4 档渲染
                        //   （uiconfig 里 noiseSwitchNum=4）。直接隐藏这颗按钮，
                        //   比让它点了没反应、或偷偷映射到降噪都诚实。
                        if (v != null && tv != null) {
                            val label = tv.text?.toString().orEmpty()
                            if (label.contains("自适应")) {
                                v.visibility = android.view.View.GONE
                                diag("label", "自适应 已隐藏")
                            }
                        }
                        // 卡片每次重建都会重置可见性，所以借这个「每颗按钮初始化」的时机
                        // 对整个窗口重跑一次清理（带若干次延迟重试，覆盖布局晚到的行）。
                        if (v != null) {
                            // 注意：必须在延迟回调里**重新取** rootView。
                            // initModeButton 触发时按钮往往还没 attach，此刻的 rootView 就是按钮自己，
                            // 拿它去搜「自适应」永远搜不到。
                            listOf(0L, 150L, 400L, 900L, 1800L).forEach { d ->
                                v.postDelayed({ cleanupCard(v.rootView) }, d)
                            }
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: initModeButton [透传→通透]")
            }.onFailure {
                Log.w(TAG, "透传改名 hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11b) ★ FIX-004：本机构建的真实注册表链 ──
            //   反编译实证：genDeviceServiceInfo 第一行是
            //     LinkDevice t8 = b2.v.s().t(deviceId);
            //     if (t8 == null) { log("linkDevice is null"); return null; }
            //   而 b2.v.s().t(id) 的实现就是
            //     DeviceProductProfile.getInstance().getDeviceByUdid(id)
            //   后者只是一次 mOnLineLinkDevicesMap.get(id)。
            //   → LinkDevice 不在表里，服务卡就永远返回 null（「暂无服务可流转」的真因）。
            //   这里把伪造的 LinkDevice 在查询出口塞进它的在线表。
            runCatching {
                val dppCls = findClass("com.hihonor.controlcenter.superdevice.deviceproduct.DeviceProductProfile")
                val getById = dppCls.declaredMethods.first {
                    it.name == "getDeviceByUdid" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }.apply { isAccessible = true }
                val mapField = dppCls.getDeclaredField("mOnLineLinkDevicesMap").apply { isAccessible = true }

                val ldCls = findClass("com.hihonor.controlcenter_aar.bean.LinkDevice")
                val prodTypeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
                HonorDeviceTypeName.dumpOnce(prodTypeCls.enumConstants)
                val thirdEarLd = HonorDeviceTypeName.pick(prodTypeCls.enumConstants)
                // LinkDevice(String deviceId, String deviceName, HnDeviceProductType type,
                //            String deviceNodeId, boolean isSelfDevice, List<HnOnlineType>, String prodId)
                val ldCtor = ldCls.declaredConstructors.firstOrNull {
                    it.parameterCount == 7 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[2].isEnum &&
                        it.parameterTypes[4] == Boolean::class.javaPrimitiveType
                }?.apply { isAccessible = true }
                if (thirdEarLd == null || ldCtor == null) throw IllegalStateException("LinkDevice 构造签名不匹配")

                fun buildLinkDevice(mac: String): Any? = runCatching {
                    ldCtor.newInstance(mac, OUR_NAME, thirdEarLd, mac, false, java.util.ArrayList<Any>(), ourProd())
                }.getOrNull()

                module.hook(getById).intercept { chain ->
                    val id = chain.args.getOrNull(0)?.toString()
                    val ret = chain.proceed()
                    if (ret == null && isOurMac(id)) {
                        val mac = id ?: return@intercept ret
                        val built = buildLinkDevice(mac)
                        if (built == null) {
                            diag("linkdevice", "构造失败 id=$mac")
                            ret
                        } else {
                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                val map = mapField.get(chain.thisObject) as? MutableMap<Any?, Any?>
                                val before = map?.size ?: -1
                                map?.put(mac, built)
                                diag("linkdevice", "已注入 id=$mac mapSize=$before→${map?.size}")
                            }.onFailure { diag("linkdevice", "塞表失败: ${it.javaClass.simpleName}") }
                            Log.w(TAG, "★ LinkDevice 已注入 DeviceProductProfile id=$mac")
                            built
                        }
                    } else ret
                }
                Log.i(TAG, "installed: DeviceProductProfile.getDeviceByUdid [LinkDevice 注册表]")
            }.onFailure {
                Log.w(TAG, "LinkDevice 注入安装失败: ${it.javaClass.simpleName}: ${it.message}")
                diag("linkdevice", "安装失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 9b) 任务：双耳电量 —— getDeviceInfoById 出口补喂 ──
            //    球列表常被持久化缓存（buildOur 只在首建跑一次），而电量 section 的数据源
            //    正是 DeviceBallManager.getDeviceInfoById(devId).getBatteryData() ——
            //    在此出口对我们的设备补喂真实 L/R，每次取数都拿到新鲜电量。
            runCatching {
                val m = mgrCls.declaredMethods.first {
                    it.name == "getDeviceInfoById" && it.parameterCount == 1
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    if (isOurMac(chain.args.getOrNull(0)?.toString()) && ret != null) {
                        feedDualBattery(ret)
                    }
                    ret
                }
                Log.i(TAG, "installed: getDeviceInfoById [双耳电量出口补喂]")
            }.onFailure {
                Log.w(TAG, "getDeviceInfoById hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 10) ★ 在线态断根：createDeviceServiceCard 每次开卡都读 ballView.isOnline()
            //    决定走在线卡（gen 5-section 含噪声控制）还是离线卡（只有响铃/连接）。
            //    我们在球创建时设的 true 会被荣耀属性监听（onPropertyInfoChange）重置回 false
            //    → 卡片时而在线时而离线。直接在读取口对我们的球恒返回 true。
            runCatching {
                val bvCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val m = bvCls.getDeclaredMethods().first {
                    it.name == "isOnline" && it.parameterCount == 0 &&
                            it.returnType == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed() as? Boolean ?: false
                    if (!ret) {
                        val receiver = chain.thisObject
                        val id = runCatching {
                            receiver?.javaClass?.getMethod("getDeviceId")?.invoke(receiver)
                        }.getOrNull()
                        if (isOurMac(id?.toString())) {
                            Log.w(TAG, "★ ballView.isOnline() false → true ($OUR_NAME)")
                            return@intercept true
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.isOnline [在线态断根]")
            }.onFailure {
                Log.w(TAG, "isOnline hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11) 任务2：卡片电量 —— getBatteryLevel 对我们的球返回跨进程真实电量 ──
            //    （默认数据链 queryBatteryInfo 对第三方设备无回调 → 卡片无电量）
            runCatching {
                val bvCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val m = bvCls.getDeclaredMethods().first {
                    it.name == "getBatteryLevel" && it.parameterCount == 0 &&
                            it.returnType == Int::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed() as? Int ?: -1
                    val receiver = chain.thisObject
                    val id = runCatching {
                        receiver?.javaClass?.getMethod("getDeviceId")?.invoke(receiver)
                    }.getOrNull()
                    if (isOurMac(id?.toString())) {
                        val bat = batteryLevelForCard()
                        if (bat != null && bat != ret) {
                            Log.w(TAG, "★ ballView.getBatteryLevel $ret → $bat ($OUR_NAME)")
                            return@intercept bat
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.getBatteryLevel [电量显示]")
            }.onFailure {
                Log.w(TAG, "getBatteryLevel hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 8) 全景观察：genDeviceServiceInfo 返回的 sections 构成（一次看清全卡） ──
            runCatching {
                val mgr3 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val gen = mgr3.declaredMethods.first {
                    it.name == "genDeviceServiceInfo" && it.parameterCount >= 2
                }.apply { isAccessible = true }
                module.hook(gen).intercept { chain ->
                    val ret = chain.proceed()
                    runCatching {
                        val secs = ret?.javaClass?.getMethod("getServiceSections")?.invoke(ret) as? List<*>
                        val types = secs?.joinToString(",") {
                            it?.javaClass?.simpleName?.take(28) ?: "null"
                        } ?: "?"
                        val devId = chain.args.getOrNull(0)
                        Log.w(TAG, "▶ genDeviceServiceInfo(dev=$devId) sections=${secs?.size} [$types]")
                        diag("sections", "dev=$devId n=${secs?.size} types=$types")
                        // ★ 任务2：上游打桩 —— 不依赖 createDeviceServiceCard 的电量段分支
                        //   （该段受 isOnline/info查询等多重条件控制，实测从未走到 007d）。
                        //   gen 是在线卡数据的必经点，直接把跨进程电量写进 info。
                        if (isOurMac(devId?.toString()) && ret != null) {
                            // ★ FIX-006：数据级修正（连接文案；响铃查找自 3.0.12 起改为整项摘除）
                            val onlineNow = isOurConnected()
                            postFixSections(ret, onlineNow)
                            // ★ 3.0.11：电量打桩只在真实连接时做。离线时跨进程
                            //   电量是断连前的残值，写进 info 会让
                            //   hasBatteryInfo()=true → 卡头小电池照常渲染，
                            //   与原生离线卡（无电量）不符；离线形态应由数据
                            //   层从一开始就不带电量（在线路径行为不变）。
                            val bat = if (onlineNow) batteryLevelForCard() else null
                            if (bat != null) {
                                runCatching {
                                    ret.javaClass.getMethod("setBatteryLevel", Int::class.javaPrimitiveType)
                                        .apply { isAccessible = true }.invoke(ret, bat)
                                    Log.w(TAG, "★★ genDeviceServiceInfo.setBatteryLevel($bat) ($OUR_NAME)")
                                }.onFailure { Log.w(TAG, "setBatteryLevel 失败: ${it.javaClass.simpleName}") }
                            }
                        }
                    }.onFailure { Log.w(TAG, "gen 读取失败: ${it.javaClass.simpleName}") }
                    ret
                }
                Log.i(TAG, "installed: genDeviceServiceInfo [全景观察]")
            }.onFailure {
                Log.w(TAG, "gen 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }.onFailure {
            Log.w(TAG, "install DeviceBallManager 注入失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 12) 任务2诊断：createDeviceServiceCard 的电量段（007d getBatteryLevel）
        //    实测零触发 —— 打出 ballView 的真实 deviceId（断根/电量 hook 都按
        //    ourMac() 匹配，若 id 不匹配则两个 hook 全部空转）与 isOnline 实况。
        // ★ FIX-002：这个观察钩子预期在 controlcenter 进程运行；
        //   SystemUI 进程里没有 DeviceServiceCardDataMgr，findClass 会抛 CNFE 并打成 W 日志（噪音）。
        //   先把类查出来，查不到就静默跳过，不再制造误导性的告警。
        val cardMgrCls = runCatching {
            findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
        }.getOrNull()
        if (cardMgrCls == null) {
            Log.d(TAG, "createDeviceServiceCard: 宿主无此类（非 controlcenter 进程），跳过观察")
        } else runCatching {
            val mgr = cardMgrCls
            val m = mgr.declaredMethods.first {
                it.name == "createDeviceServiceCard" && it.parameterCount == 5
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val ball = chain.args.getOrNull(1)
                val id = runCatching { ball?.javaClass?.getMethod("getDeviceId")?.invoke(ball) }.getOrNull()
                val online = runCatching { ball?.javaClass?.getMethod("isOnline")?.invoke(ball) }.getOrNull()
                val bid = runCatching { ball?.javaClass?.getMethod("getId")?.invoke(ball) }.getOrNull()
                Log.w(TAG, "▶ createDeviceCard ball.id=$id getId=$bid isOnline=$online (匹配OUR=${isOurMac(id?.toString())} mac=${ourMac()})")
                // ★ bug修复：进入设备中心（开卡）时向 bluetooth 发**只读查询**（ACTION_REFRESH_STATUS
                //   → queryStatus() → QUERY_ANC 查询帧，不是调模式包）→ 耳机回当前模式 →
                //   changeUIAncStatus 写 magicxm4_current_anc → 卡片显示**耳机本体真实模式**。
                if (isOurMac(id?.toString())) {
                    val nowQ = System.currentTimeMillis()
                    if (nowQ - lastAncQueryAt > 5000) {
                        lastAncQueryAt = nowQ
                        runCatching {
                            val app = Class.forName("android.app.ActivityThread")
                                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                            app?.sendBroadcast(Intent("io.github.yuxiximyxx.magicxm4.ACTION_REFRESH_STATUS"))
                            Log.w(TAG, "→→ 已发只读查询 ACTION_REFRESH_STATUS（不发调模式包）")
                        }.onFailure { Log.w(TAG, "查询广播失败: ${it.javaClass.simpleName}") }
                    }
                }
                val cardRet = chain.proceed()
                if (isOurMac(id?.toString())) {
                    diag("card", "id=$id online=$online ok=${cardRet != null} retCls=${cardRet?.javaClass?.simpleName}")
                    // ★ 卡片视图到手 —— 这是最稳定的时机：离线收缩 + 图标替换都在这里驱动。
                    //   注意 online 用的是球自己的 isOnline（被我们强制成 true 了），
                    //   真实在线态必须读 magicxm4_connected。
                    val cardView = cardRet as? android.view.View
                    if (cardView != null) {
                        // ★ 3.0.11：离线收缩改走数据层（第 7 节否决 + 第 8 节摘段），
                        //   卡片到手时数据已是离线形态。这里【删除】了原先整套
                        //   视图层赛跑补救：alpha=0 遮一帧 + 同步 cleanup/
                        //   applyOfflineVisibility + post{} 再清 + 400ms 补清——
                        //   宿主展开动画按装配高度先展开、隐藏陆续落地后又缩
                        //   回，正是断连开卡「底部大片空白高卡 → 缩回正常高
                        //   度」的成因。仅保留一次同步 cleanupCard：它现在只管
                        //   「自适应」一行（XM4 没有这个模式、在线离线都要
                        //   隐藏），在卡片 attach/动画开始前同步执行完，不
                        //   涉及电量/噪声段的高度可见性。响铃查找自 3.0.12
                        //   起已在数据层（postFixSections）整项摘除，不再
                        //   经视图层隐藏。
                        runCatching { cleanupCard(cardView) }
                    }
                }
                cardRet
            }
            Log.i(TAG, "installed: createDeviceServiceCard [电量段诊断]")
        }.onFailure {
            Log.w(TAG, "createDeviceCard 观察失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 13) 任务2 最后保险：DeviceServiceInfo.setBatteryLevel 兜底 ──
        //    电量段若执行且 ballView.getBatteryLevel 断根匹配失败会写入 -1/0，
        //    把 gen 阶段打桩的真实电量覆盖掉 → hasBatteryInfo()=false → 不显示。
        //    这里对 <=0 的写入统一替换为跨进程真实电量（本机正常值 >0 不干预）。
        runCatching {
            val infoCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceInfo")
            val m = infoCls.getDeclaredMethods().first {
                it.name == "setBatteryLevel" && it.parameterCount == 1
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                // ★ 只干预我们设备的 info —— 否则荣耀自家耳机低电(0)时会被
                //   替换成我们的电量（串台显示）
                val did = runCatching {
                    chain.thisObject?.javaClass?.getMethod("getDeviceId")?.invoke(chain.thisObject)
                }.getOrNull()
                if (!isOurMac(did?.toString())) return@intercept chain.proceed()
                val v = chain.args.getOrNull(0) as? Int ?: -1
                // ★ 3.0.11：兜底替换只在真实连接时做——它的职责是保住在线卡
                //   的电量打桩不被宿主的 ≤0 写入覆盖；离线时也替换会让残值
                //   电量复活、hasBatteryInfo() 变 true，与数据层离线形态
                //   （第 7/8 节：不装配电量段、不打桩）冲突。
                val bat = if (isOurConnected()) batteryLevelForCard() else null
                if (v <= 0 && bat != null && bat > 0) {
                    Log.w(TAG, "★★ setBatteryLevel($v) → $bat 兜底 ($OUR_NAME)")
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = bat
                    return@intercept chain.proceed(newArgs)
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: DeviceServiceInfo.setBatteryLevel [电量兜底]")
        }.onFailure {
            Log.w(TAG, "setBatteryLevel 兜底失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 上游作者本人那副耳机的 MAC，仅作「从未连接过、读不到真实值」时的兜底。 */
    private const val OUR_MAC = "18:5C:A1:52:10:36"
    private const val OUR_NAME = "WH-1000XM4"

    /**
     * 同一台手机上其它设备的显示名。用来判断「这个视图子树到底是不是我们那张卡片」——
     * 球列表把所有设备名放在同一棵子树里，不排除就会误伤别人的卡片。
     */
    private val OTHER_DEVICE_NAMES = listOf("Enco Free4", "FreeBuds", "MacBook", "LKY")
    private const val OUR_PROD = "00VVD9"

    /** 我们那颗球的 View（弱引用）。卡片打开时没有其它稳定的 View 入口，靠它拿窗口根视图。 */
    @Volatile
    private var ourBallRef: java.lang.ref.WeakReference<android.view.View>? = null

    // ── 真实设备 MAC（跨进程桥）──────────────────────────────
    // 上游把作者自己那副耳机的 MAC 写死在整条注入链里（26 处引用）。
    // 换一副耳机后，设备中心被注入的其实是一台不存在的「幽灵设备」：
    // 布局给它留了位（用户看到空位），但球渲染不出来，真实耳机也从没被认领。
    // 修复：蓝牙进程连接时把真实地址写进 Settings.Global，本进程按需读取。

    private const val MAC_KEY = "magicxm4_mac"

    @Volatile private var cachedMac: String? = null
    @Volatile private var cachedMacAt = 0L
    @Volatile private var warnedNoMac = false

    private fun currentApp(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
    }.getOrNull()

    /** 真实耳机 MAC：读到就用；从未连过（值为空）才回落常量并告警一次。 */
    private fun ourMac(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedMac?.let { if (now - cachedMacAt < 5_000L) return it }
        val stored = runCatching {
            currentApp()?.let {
                android.provider.Settings.Global.getString(it.contentResolver, MAC_KEY)
            }
        }.getOrNull()?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            cachedMac = stored
            cachedMacAt = now
            return stored
        }
        if (!warnedNoMac) {
            warnedNoMac = true
            Log.w(TAG, "$MAC_KEY 为空（本机从未连过耳机），暂用上游常量 $OUR_MAC；连接一次后自动纠正")
        }
        return OUR_MAC
    }

    /**
     * 直接驱动 A2DP / HFP 的连接与断开（FIX-007）。
     *
     * 为什么自己来：荣耀的「断开连接」最终调 AudioUtils.disconnect()，它依赖
     * getProfileProxy 的回调链；而「响铃查找」更狠 —— 它是把手机音频**路由**到耳机上播
     * （MediaRouter2），虚拟设备没有路由，所以点了永远没声音。
     */
    private fun btA2dpAction(mac: String, connect: Boolean) {
        runCatching {
            val ctx = currentApp() ?: return@runCatching
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return@runCatching
            val dev = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return@runCatching
            val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile?) {
                    runCatching {
                        // connect/disconnect 是隐藏 API，编译期不可见 —— 反射调用
                        val m = proxy?.javaClass?.getMethod(
                            if (connect) "connect" else "disconnect",
                            android.bluetooth.BluetoothDevice::class.java
                        )
                        val ret = m?.invoke(proxy, dev)
                        diag("bt_action", "profile=$profile ${if (connect) "connect" else "disconnect"} → $ret")
                    }.onFailure {
                        diag("bt_action", "call fail: ${it.javaClass.simpleName}: ${it.message}")
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            adapter.getProfileProxy(ctx, listener, android.bluetooth.BluetoothProfile.A2DP)
            adapter.getProfileProxy(ctx, listener, android.bluetooth.BluetoothProfile.HEADSET)
            diag("bt_action", "mac=$mac connect=$connect")
            Log.i(TAG, "btA2dpAction mac=$mac connect=$connect")
        }.onFailure { diag("bt_action", "fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 我们的 adapter（球创建时缓存），响铃高亮要用。 */
    @Volatile private var ourAdapterRef: Any? = null

    /** 响铃开关状态。 */
    @Volatile private var ringOn = false

    /**
     * 驱动「响铃查找」按钮的高亮/熄灭。
     * 走 DeviceServiceCardDataMgr.notifyStatusChangeWithSpecialDev(adapter, businessId, isSrc, highlight, tag)。
     */
    private fun highlightFind(deviceId: String?, mgrInstance: Any?, mgrCls: Class<*>?, on: Boolean) {
        if (deviceId == null) return
        runCatching {
            // 球适配器 → 球视图 BasicDeviceBallView → 它自己的 notifyStatusChangeWithSpecialDev
            // （4 参重载内部会解析 DeviceServiceCardAdapter，避免我们传错类型）
            val ballAdapter = ourAdapterRef ?: runCatching {
                val bm = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                val bmi = bm.getMethod("getInstance").invoke(null)
                val info = bm.getMethod("getDeviceInfoById", String::class.java).invoke(bmi, deviceId)
                info?.javaClass?.getMethod("getBasicDeviceBallViewAdapter")?.invoke(info)
            }.getOrNull() ?: return@runCatching

            val ballView = ballAdapter.javaClass
                .getMethod("getBasicDeviceBallView")
                .invoke(ballAdapter) ?: return@runCatching

            val localId = runCatching {
                val bm = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                val bmi = bm.getMethod("getInstance").invoke(null)
                val li = bm.getMethod("getLocalDeviceInfo").invoke(bmi)
                li?.javaClass?.getMethod("getId")?.invoke(li) as? String
            }.getOrNull().orEmpty()

            val m = ballView.javaClass.getMethod(
                "notifyStatusChangeWithSpecialDev",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
            m.invoke(ballView, "DccFindIotDevice", true, on, localId)
            diag("ring", "highlight=$on localId=$localId view=${ballView.javaClass.simpleName}")
        }.onFailure { diag("ring", "highlight fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 让耳机自己响（欢律 0x0400），走蓝牙进程下发。 */
    private fun requestFindBuds(on: Boolean) {
        runCatching {
            val sent = runCatching {
                currentApp()?.sendBroadcast(
                    Intent(MagicXm4Action.ACTION_FIND_BUDS)
                        .putExtra("on", on)
                        .setPackage("com.android.bluetooth")
                )
                true
            }.getOrDefault(false)
            diag("find", "已请求响铃 on=$on sent=$sent")
        }.onFailure { diag("find", "失败: ${it.javaClass.simpleName}") }
    }

    /**
     * 数据级修正服务卡 sections（FIX-006）：
     *  - QuickActionData.serviceId == to_connect_device / disconnect_device → 按真实连接态互换（含文案）
     *  - ★ 3.0.12：ServiceCardInfo.bsId 含 Find（响铃查找）→ 整项从段的
     *    数据列表里摘除（连接/离线都摘）。响铃查找不是独立 section：
     *    createSections 只经 create*Section 生成电量/噪声两段，响铃是混在
     *    某段 sectionDataList 里的一个 ServiceCardInfo 数据项，没有生成
     *    方法可否决——gen 返回后、卡片视图创建前摘掉数据项，就是它在
     *    数据层最上游的关点。本函数的历史行为是把它置 NORMAL 放行可点
     *    （FIX-006），3.0.12 按用户点名（XM4 没有响铃查找功能）改为摘除。
     *  - ★ 3.0.11：真实离线时按 sectionType 摘除电量段/噪声段（兜底清扫，
     *    主路径是第 7 节 create*Section 的离线否决）
     * 只改数据、不碰 View，也不依赖上游「在线态通知」方法是否被调用。
     */
    private fun postFixSections(info: Any, connected: Boolean) {
        runCatching {
            val secs = info.javaClass.getMethod("getServiceSections").invoke(info) as? List<*> ?: return
            // ★ 3.0.11 离线摘段（数据层兜底）：真实离线时，把电量段/噪声段
            //   从 sections 里整段摘除。主路径是第 7 节 create*Section 的
            //   离线否决（宿主压根不生成这两段）；这里防的是装配路径有
            //   变、段仍被生成出来的情况。段靠 sectionType 识别（反编译
            //   实证：createEarphone*Section 生成时 setSectionType 的就是
            //   EARPHONE_BATTERY / EARPHONE_NOISE 两个枚举）。本函数在 gen
            //   返回后、卡片视图创建前执行，摘完的列表即动画第一帧的
            //   数据。在线时整段跳过，在线行为零改动。
            if (!connected) {
                val before = secs.size
                var sawType = false
                val kept = secs.filterNot { sec ->
                    val t = runCatching {
                        sec?.javaClass?.getMethod("getSectionType")?.invoke(sec)?.toString()
                    }.getOrNull()
                    if (t != null) sawType = true
                    t == "EARPHONE_BATTERY" || t == "EARPHONE_NOISE"
                }
                if (!sawType) {
                    diag("sections_sweep", "getSectionType 全空，摘段未生效（getter 名待核）")
                } else if (kept.size != before) {
                    var applied = false
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val m = secs as MutableList<Any?>
                        m.clear()
                        m.addAll(kept)
                        applied = true
                    }
                    if (!applied) runCatching {
                        info.javaClass.declaredMethods.firstOrNull {
                            it.name == "setServiceSections" && it.parameterCount == 1
                        }?.apply { isAccessible = true }?.invoke(info, kept)
                        applied = true
                    }
                    diag("sections_sweep", "离线摘段 $before→${kept.size} applied=$applied（3.0.11）")
                }
            }
            // ★ 3.0.12 响铃查找整项摘除（无条件，连接/离线都摘）：
            //   重新从 info 读 sections（离线摘段可能已换过列表对象），逐段
            //   把 bsId 含 Find 的 ServiceCardInfo 从该段数据列表里摘掉。
            //   落地三档：① 数据列表原地改；② 段的 setSectionDataList；
            //   ③ 某段被摘空（原本只有响铃项）→ 整段从 sections 摘除
            //   （复用离线摘段的原地改/setServiceSections 写法）。
            //   摘完回读校验并打 diag，日志里能直接看出是否生效。
            runCatching {
                val curSecs = info.javaClass.getMethod("getServiceSections").invoke(info) as? List<*>
                if (curSecs != null) {
                    fun isFindItem(d: Any?): Boolean {
                        if (d == null) return false
                        val b = runCatching {
                            d.javaClass.getMethod("getBsId").invoke(d) as? String
                        }.getOrNull()
                        return b != null && b.contains("Find", ignoreCase = true)
                    }
                    var removedItems = 0
                    val emptiedSecs = mutableListOf<Any>()
                    for (sec in curSecs) {
                        if (sec == null) continue
                        val data = runCatching {
                            sec.javaClass.getMethod("getSectionDataList").invoke(sec) as? List<*>
                        }.getOrNull() ?: continue
                        if (data.none { isFindItem(it) }) continue
                        val keptData = data.filterNot { isFindItem(it) }
                        val n = data.size - keptData.size
                        var appliedData = false
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val m = data as MutableList<Any?>
                            m.clear()
                            m.addAll(keptData)
                            appliedData = true
                        }
                        if (!appliedData) runCatching {
                            sec.javaClass.declaredMethods.firstOrNull {
                                it.name == "setSectionDataList" && it.parameterCount == 1
                            }?.apply { isAccessible = true }
                                ?.invoke(sec, java.util.ArrayList(keptData))
                            appliedData = true
                        }
                        if (appliedData) {
                            removedItems += n
                            if (keptData.isEmpty()) emptiedSecs.add(sec)
                        } else {
                            diag("find_sweep", "某段数据列表不可改且无 setter，响铃项未摘（类=${sec.javaClass.simpleName}）")
                        }
                    }
                    if (emptiedSecs.isNotEmpty()) {
                        val keptSecs = curSecs.filterNot { it in emptiedSecs }
                        var appliedSecs = false
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val m = curSecs as MutableList<Any?>
                            m.clear()
                            m.addAll(keptSecs)
                            appliedSecs = true
                        }
                        if (!appliedSecs) runCatching {
                            info.javaClass.declaredMethods.firstOrNull {
                                it.name == "setServiceSections" && it.parameterCount == 1
                            }?.apply { isAccessible = true }?.invoke(info, keptSecs)
                            appliedSecs = true
                        }
                        diag("find_sweep", "摘空段 ${emptiedSecs.size} 个整段移除 applied=$appliedSecs（3.0.12）")
                    }
                    // 回读校验：还剩几个响铃项
                    var remaining = 0
                    val verifySecs = runCatching {
                        info.javaClass.getMethod("getServiceSections").invoke(info) as? List<*>
                    }.getOrNull()
                    for (sec in verifySecs ?: emptyList<Any?>()) {
                        if (sec == null) continue
                        val data = runCatching {
                            sec.javaClass.getMethod("getSectionDataList").invoke(sec) as? List<*>
                        }.getOrNull() ?: continue
                        remaining += data.count { isFindItem(it) }
                    }
                    diag("find_sweep", "响铃项摘除 removed=$removedItems remaining=$remaining（3.0.12）")
                }
            }
            var changed = 0
            for (sec in secs) {
                if (sec == null) continue
                val data = runCatching {
                    sec.javaClass.getMethod("getSectionDataList").invoke(sec) as? List<*>
                }.getOrNull() ?: continue
                for (d in data) {
                    if (d == null) continue
                    val cls = d.javaClass

                    val sid = runCatching { cls.getMethod("getServiceId").invoke(d) as? String }.getOrNull()
                    if (sid == "to_connect_device" || sid == "disconnect_device") {
                        val want = if (connected) "disconnect_device" else "to_connect_device"
                        val text = if (connected) "断开连接" else "去连接"
                        if (sid != want) {
                            runCatching {
                                cls.declaredMethods.firstOrNull {
                                    it.name == "setServiceId" && it.parameterCount == 1
                                }?.invoke(d, want)
                                cls.declaredMethods.firstOrNull {
                                    it.name == "setTitle" && it.parameterCount == 1
                                }?.invoke(d, text)
                                changed++
                            }
                        }
                    }

                    // 响铃查找（bsId 含 Find 的 ServiceCardInfo）已在上方
                    //   3.0.12 的摘除环节整项移除，这里不再有它的处理；
                    //   FIX-006 时代「置 NORMAL 放行可点」的分支随之删除。
                }
            }
            diag("sections_fix", "changed=$changed connected=$connected")
        }.onFailure { diag("sections_fix", "fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 实时连接态：直接问蓝牙栈（A2DP），比跨进程桥更及时可靠。
     * 桥值 [isOurConnected] 只作兜底（例如本进程没有 BLUETOOTH_CONNECT 权限时）。
     */
    private fun isBtConnectedNow(): Boolean = runCatching {
        val ctx = currentApp() ?: return@runCatching isOurConnected()
        val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?: return@runCatching isOurConnected()
        val dev = bm.adapter?.getRemoteDevice(ourMac()) ?: return@runCatching isOurConnected()
        bm.getConnectionState(dev, android.bluetooth.BluetoothProfile.A2DP) ==
            android.bluetooth.BluetoothProfile.STATE_CONNECTED
    }.getOrDefault(isOurConnected())

    /** id 是否就是我们的耳机（大小写不敏感）。 */
    private fun isOurMac(id: String?): Boolean =
        id != null && id.equals(ourMac(), ignoreCase = true)

    // ── prodId 运行期覆盖（FIX-003 任务 2 的 A/B 开关）────────────
    // 本机构建可能没有 00VVD9 的图标/产品配置 → createDeviceBall 建不出球（返回 null）。
    // 允许不改代码直接换 prodId 验证：
    //   adb shell settings put global magicxm4_prodid <本机存在的耳机 prodId>
    //   adb shell settings delete global magicxm4_prodid     # 还原
    private const val PROD_KEY = "magicxm4_prodid"

    @Volatile private var cachedProd: String? = null
    @Volatile private var cachedProdAt = 0L

    private fun ourProd(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedProd?.let { if (now - cachedProdAt < 5_000L) return it }
        val stored = runCatching {
            currentApp()?.let {
                android.provider.Settings.Global.getString(it.contentResolver, PROD_KEY)
            }
        }.getOrNull()?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            cachedProd = stored
            cachedProdAt = now
            return stored
        }
        return OUR_PROD
    }

    // ── 字段 diff 取证（FIX-003 任务 1，临时；定位后收敛）──────────
    //   adb shell settings put global magicxm4_debug_dump 1
    private const val DUMP_KEY = "magicxm4_debug_dump"

    private fun isVerboseDump(): Boolean =
        io.github.yuxiximyxx.magicxm4.BuildConfig.DEBUG || runCatching {
            currentApp()?.let {
                android.provider.Settings.Global.getString(it.contentResolver, DUMP_KEY) == "1"
            } == true
        }.getOrDefault(false)

    /** 把对象（含父类）的全部字段打成一行，字段名=值，便于与本机真实设备逐字段 diff。 */
    private fun dumpFields(label: String, obj: Any?) {
        if (obj == null) { Log.i(TAG, "DUMP $label = null"); return }
        val sb = StringBuilder()
        var cls: Class<*>? = obj.javaClass
        val seen = HashSet<String>()
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    if (!seen.add(f.name)) return@runCatching
                    val v = f.get(obj)
                    sb.append(f.name).append('=')
                        .append(if (v is Array<*>) v.contentToString() else v?.toString())
                        .append("; ")
                }
            }
            cls = cls.superclass
        }
        Log.i(TAG, "DUMP $label (${obj.javaClass.name}): $sb")
    }

    private fun dumpDeviceInfoList(label: String, list: java.util.List<*>) {
        if (!isVerboseDump()) return
        Log.i(TAG, "DUMP $label size=${list.size}")
        list.take(8).forEachIndexed { i, o -> dumpFields("$label[$i]", o) }
    }

    /**
     * 诊断回读通道：把关键环节的结果写进 Settings.Global。
     * 本机 logcat 被 Honor 加密、模块日志又只在 LSPosed 里，读写 Settings 是最省事的闭环验证方式。
     * 用法：adb shell settings get global magicxm4_diag_<key>
     */
    private fun diag(key: String, value: Any?) {
        runCatching {
            val ctx = currentApp() ?: return@runCatching
            android.provider.Settings.Global.putString(
                ctx.contentResolver,
                "magicxm4_diag_$key",
                (value?.toString() ?: "null").take(600)
            )
        }
    }


    /** 读 Settings.Global["magicxm4_last_anc"]（bluetooth 进程写入的用户默认档位） */
    /**
     * 任务6：耳机是否在线。bluetooth 进程在电量数据到达时写 "1"、断连时写 "0"
     * （Settings.Global["magicxm4_connected"]）。默认/无记录 = false —— 断连后球不注入。
     */
    private fun isOurConnected(): Boolean = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return false
        android.provider.Settings.Global.getString(app.contentResolver, "magicxm4_connected") == "1"
    }.getOrDefault(false)

    /** 任务2：设备中心卡片电量 —— 读跨进程电量（L90,R85）取双耳平均，无数据返回 null */
    /** 读跨进程电量原始对 "L90,R80" → (90,80)；无数据返回 null */
    private fun readBatteryRaw(): Pair<Int, Int>? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicxm4_battery")
            ?: return null
        if (!raw.contains(",")) return null
        val l = raw.substringBefore(',').removePrefix("L").toIntOrNull()
        val r = raw.substringAfter(',').removePrefix("R").toIntOrNull()
        if (l == null || r == null) null else Pair(l, r)
    }.getOrNull()

    /**
     * 任务：设备中心显示双耳电量 —— 给 DeviceInfo 喂 BatteryData（left/right/powerAbility）。
     * batteryData 为 null 时电量 section 走默认（integrated=false、隐藏不显示）；
     * 而球列表常被持久化缓存（buildOur 只在首建跑一次），故也在 getDeviceInfoById
     * 出口对我们的设备补喂（电量 section 正是经它取数据）。
     */
    private fun feedDualBattery(info: Any) = runCatching {
        val lr = readBatteryRaw() ?: return@runCatching
        val bdCls = findClass("com.hihonor.controlviewnew.info.BatteryData")
        // 构造器非 public —— setAccessible 后 newInstance
        val bd = bdCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        bdCls.getMethod("setLeftPower", Int::class.javaPrimitiveType).invoke(bd, lr.first)
        bdCls.getMethod("setRightPower", Int::class.javaPrimitiveType).invoke(bd, lr.second)
        var ability = 0
        for (cn in listOf("POWER_ABILITY_LEFT", "POWER_ABILITY_RIGHT")) {
            ability = ability or bdCls.getDeclaredField(cn).apply { isAccessible = true }.getInt(null)
        }
        bdCls.getMethod("setPowerAbility", Int::class.javaPrimitiveType).invoke(bd, ability)
        info.javaClass.getMethod("setBatteryData", bdCls).invoke(info, bd)
        Log.w(TAG, "★★ 双耳电量喂入: 左${lr.first}% 右${lr.second}% (ability=0x${"%X".format(ability)})")
    }.onFailure { Log.w(TAG, "双耳电量喂入失败: ${it.javaClass.simpleName}: ${it.message}") }

    private fun batteryLevelForCard(): Int? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicxm4_battery")
            ?: return null
        if (!raw.contains(",")) return null
        val l = raw.substringBefore(',').removePrefix("L").toIntOrNull()
        val r = raw.substringAfter(',').removePrefix("R").toIntOrNull()
        when {
            l != null && r != null -> (l + r) / 2
            l != null -> l
            r != null -> r
            else -> null
        }
    }.getOrNull()

    private fun readLastAnc(): String? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        android.provider.Settings.Global.getString(app.contentResolver, "magicxm4_last_anc")
    }.getOrNull()
    /**
     * 按文案找到卡片里的一行并隐藏它（连同「只装着它」的那层容器）。
     *
     * 为什么要连容器一起隐藏：荣耀服务卡里每一行都是「外框 + 图标 + 文案」三层，
     * 只把 TextView 设 GONE 会留下一个空框和图标，看起来像界面坏了。
     * 这里向上找到「只有一个子 View 的祖先」再隐藏，正好对应那种单纯包裹型的容器。
     */
    /**
     * 把卡片上 WH-1000XM4 不支持的入口隐藏掉：自适应（没有这个模式）。
     * 响铃查找自 3.0.12 起在数据层（postFixSections 按 bsId 摘除数据项）整项
     * 去掉，不再经视图层隐藏。
     *
     * 为什么按文案而不是按 View id：这两个入口的 id 是 controlcenter 私有的，换个 MagicOS
     * 版本就可能变；文案是从字符串资源来的，反而更稳。匹配到之后连同「只装着它一个子 View」
     * 的外层容器一起 GONE —— 只隐藏 TextView 会留下一个空框和图标。
     */
    /**
     * 断连时隐藏「噪声控制」整段 —— 对齐原生华为耳机的卡片行为。
     *
     * 实测：HUAWEI FreeBuds 5 未连接时卡片上**没有**噪声控制；而我们注入的第三方设备
     * 因为强制走了「在线」渲染，断开后仍然显示一排点了没用的档位按钮。
     * 这里按「标题文案 → 上一层容器」定位整段，离线隐藏、在线恢复。
     */
    private fun applyNoiseSectionVisibility(root: android.view.View?, online: Boolean) {
        if (root == null) return
        val title = findTextView(root, "噪声控制") ?: return
        val scope = ancestorContainingAlso(title, OUR_NAME, OTHER_DEVICE_NAMES) ?: return
        val section = title.parent as? android.view.View ?: return
        val target = if (section === scope) title else section
        target.visibility = if (online) android.view.View.VISIBLE else android.view.View.GONE
        diag("noise_sec", "online=$online -> ${if (online) "VISIBLE" else "GONE"}")
    }

    // ── ★ 球图标选图 hook 的状态（3.0.10 建、3.0.13 收紧）─────
    // BALL_ICON_GUARD：交付入口自己也会再次进入被 hook 的入口（hook 内以
    //   头戴图 id 重新 proceed 时），靠它防递归（资源 id 判定本身也会跳过
    //   头戴图，双保险）。
    // BALL_ICON_SEEN / BALL_ICON_TOTAL：命中日志按 (入口|资源名) 去重封顶。
    // 【3.0.13】3.0.10 的 BALL_REGISTRY 弱引用登记表已随 getDrawable
    //   两层 hook 一并撤除（登记表是那两层的作用域限定依据；没有视图
    //   上下文的全局入口不再参与换图，登记表失去唯一用途）。
    //   earbudStates：耳塞两图的 ConstantState 缓存（见 earbudIndexOf）。
    private val BALL_ICON_GUARD = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }
    private val BALL_ICON_SEEN: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private val BALL_ICON_TOTAL = java.util.concurrent.atomic.AtomicInteger(0)
    // ── ★ 3.0.14 全屏球阵定位探针的状态（只观察不改图，见
    //   installAamBallGridProbe）：命中签名去重 + 总量封顶，避免球阵
    //   每次重绘都刷日志/diag。 ──
    private val GRID_PROBE_SEEN: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private val GRID_PROBE_ICON_CALLS = java.util.concurrent.atomic.AtomicInteger(0)
    private val GRID_PROBE_NAME_CALLS = java.util.concurrent.atomic.AtomicInteger(0)
    // 宿主三张球图的资源 id（按 entry 名从宿主资源表查，不写死数值；
    //   资源 id 全进程通用，首次查到后缓存）。[0]=default（头戴目标图）
    //   [1]=in_ear [2]=semi_in_ear —— 后两者命中才需要改。
    @Volatile private var ballIconIds: IntArray? = null

    private fun ballIconIds(res: android.content.res.Resources): IntArray? {
        ballIconIds?.let { return it }
        val pkg = "com.hihonor.controlcenter"
        val names = arrayOf(
            "device_icon_default_earphone",
            "device_icon_in_ear_earphone_shadow",
            "device_icon_semi_in_ear_earphone_shadow"
        )
        val ids = IntArray(3)
        for (i in names.indices) {
            ids[i] = res.getIdentifier(names[i], "drawable", pkg)
            if (ids[i] == 0) return null
        }
        ballIconIds = ids
        return ids
    }

    /**
     * ★ 3.0.10：这颗球（BasicDeviceBallView 实例）是不是我们 XM4 的 —— 供
     * 选图 hook 在宿主取图的那一次调用内同步判定。判据与模块注入 DeviceInfo
     * 的身份一致（initIcon 执行时球的 device_ball_name 可能还没绑定，故
     * 不以文案为主判据）：
     *   1. 球所持 DeviceInfo（先扫球本类的 DeviceInfo 类型字段，再走
     *      getBasicDeviceBallViewAdapter 链上的 getDeviceInfo /
     *      getBasicDeviceInfo 方法名候选）：id 命中 [isOurMac]，或名字
     *      字段 == OUR_NAME；
     *   2. 兜底：球子树里的 device_ball_name 文案 == OUR_NAME（绑名之后
     *      的再次选图也能命中）。
     * 任一命中即是。全程 runCatching：宿主结构有变时退化为「不是我们的
     * 球」→ 不干预，绝不影响宿主原生流程。
     */
    private fun isOurBallView(ball: Any?): Boolean {
        if (ball == null) return false
        runCatching {
            val infoCls = findClass("com.hihonor.controlviewnew.info.DeviceInfo")
            var info: Any? = null
            for (f in ball.javaClass.declaredFields) {
                if (f.type == infoCls) {
                    f.isAccessible = true
                    info = f.get(ball)
                    if (info != null) break
                }
            }
            if (info == null) {
                val adapter = runCatching {
                    ball.javaClass.methods.firstOrNull {
                        it.name == "getBasicDeviceBallViewAdapter" && it.parameterCount == 0
                    }?.invoke(ball)
                }.getOrNull()
                if (adapter != null) {
                    for (mn in listOf("getDeviceInfo", "getBasicDeviceInfo")) {
                        info = runCatching {
                            adapter.javaClass.methods.firstOrNull {
                                it.name == mn && it.parameterCount == 0
                            }?.invoke(adapter)
                        }.getOrNull()
                        if (info != null) break
                    }
                }
            }
            if (info != null) {
                val id = runCatching {
                    info.javaClass.getMethod("getId").invoke(info)?.toString()
                }.getOrNull()
                if (isOurMac(id)) return true
                val name = runCatching {
                    info.javaClass.getMethod("getName").invoke(info)?.toString()
                }.getOrNull()
                if (name?.trim() == OUR_NAME) return true
            }
        }
        val v = ball as? android.view.View ?: return false
        val nameView = findByIdName(v, "device_ball_name") as? android.widget.TextView
        return nameView?.text?.toString()?.trim() == OUR_NAME
    }

    /**
     * 沿 ImageView 的父链向上找它所属的 BasicDeviceBallView（球根视图）
     * —— ★ 3.0.13 换图的唯一作用域判据：只认「这张图正被设进哪颗球」
     *   本身。找不到（不在任何球里）返回 null，调用方一律原样放行。
     */
    private fun findBallOf(view: android.view.View): android.view.View? {
        var node: android.view.View? = view
        var depth = 0
        while (node != null && depth < 12) {
            if (node.javaClass.name ==
                "com.hihonor.controlviewnew.widget.BasicDeviceBallView"
            ) return node
            node = node.parent as? android.view.View
            depth++
        }
        return null
    }

    // 耳塞两图的 ConstantState 缓存（setImageDrawable 入口认图用）。
    @Volatile
    private var earbudStates: Array<android.graphics.drawable.Drawable.ConstantState?>? = null

    /**
     * 递进 setImageDrawable 的 Drawable 是耳塞两图里的哪一张：返回
     * ballIconIds 的下标（1=in_ear、2=semi_in_ear），认不出返回 -1。
     * 认法是 ConstantState 身份比对 —— 同一 Resources 下资源 Drawable
     * 的 ConstantState 是缓存共享的同一实例。宿主若把图包了一层
     * Wrapper、或换了 Theme，比对不上 → 认不出 → 原样放行：宁可漏换
     * XM4 自己的球（还有 setImageResource 主路径兜底），绝不误伤
     * 别人的球 —— 这是 3.0.13 的总原则。
     */
    private fun earbudIndexOf(
        view: android.widget.ImageView,
        drawable: android.graphics.drawable.Drawable,
        ids: IntArray
    ): Int {
        val cs = runCatching { drawable.constantState }.getOrNull() ?: return -1
        val states = earbudStates ?: run {
            val arr = arrayOfNulls<android.graphics.drawable.Drawable.ConstantState>(2)
            for (i in 0..1) {
                arr[i] = runCatching {
                    view.resources.getDrawable(ids[i + 1], view.context?.theme)
                        ?.constantState
                }.getOrNull()
            }
            earbudStates = arr
            arr
        }
        for (i in states.indices) {
            val s = states[i]
            if (s != null && s === cs) return i + 1
        }
        return -1
    }

    private fun logBallIconHit(where: String, fromName: String) {
        val sig = "$where|$fromName"
        if (BALL_ICON_SEEN.add(sig) && BALL_ICON_TOTAL.incrementAndGet() <= 20) {
            Log.w(TAG, "★ 球图标选图同步改: $where $fromName → device_icon_default_earphone")
            diag("icon", "$where $fromName -> default_earphone（3.0.10 同步改结果）")
        }
    }

    /**
     * 装载球图标选图 hook —— 在宿主给球设图的那一次调用内同步把图
     * 换成宿主头戴图 device_icon_default_earphone（3.0.10 机制与来由
     * 见 onHook 第 12 节）。fixBallIcon 的视图树手术 + 全部
     * postDelayed 触发已在 3.0.10 整体退役：模块从不在绘制后换球的图。
     *
     * ★ 3.0.13 收紧 —— 只留两个**带 receiver 视图**的交付入口，且
     *   必须沿 ImageView 父链找到所属球根视图、读其 DeviceInfo 正向
     *   命中 XM4（isOurBallView）才换；认不出、读不到、非本人，一律
     *   原样放行：
     *   a) ImageView.setImageResource(int)：入参是 in_ear / semi_in_ear
     *      两张耳塞图之一、且所属球验明正身，才把入参换成头戴图 id
     *      再 proceed —— 主路径；
     *   b) ImageView.setImageDrawable(Drawable)：兜住宿主「先用资源
     *      API 取到 Drawable、再 set 进图标位」的路径。递进来的图先
     *      按 ConstantState 身份认出是耳塞两图之一（earbudIndexOf），
     *      再同样验球正身，才换成头戴图 Drawable 再 proceed。
     * 3.0.10 曾挂的 Resources.getDrawable(int)/(int, Theme) 与
     *   Context.getDrawable(int) 两层已整体撤除：全局入口没有视图
     *   上下文，当年的作用域限定（调用栈里有 BasicDeviceBallView +
     *   登记表里在场有 XM4 的球）分不清在给哪颗球取图 —— 球阵里
     *   XM4 的球常驻在场、别家耳机的球取自己的耳塞图时栈上同样有
     *   BasicDeviceBallView，两条限定全过，真机证伪为全阵列误伤
     *   （FreeBuds / Enco 的球全被换成头戴图）。
     * 装载时把球的图标相关方法面打进 diag 备查（initIcon 的确切
     * 签名不在静态材料里，以真机日志为准）。
     * 单点失败各自记日志，不拖垮其他 hook。
     */
    private fun installBallIconHook() {
        val ballCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")

        // 备查：球的图标相关方法面（initIcon 的确切签名以真机此行为准）。
        runCatching {
            val sb = StringBuilder()
            for (m in ballCls.declaredMethods.sortedBy { it.name }) {
                val n = m.name.lowercase()
                if (n.contains("icon") || n.contains("image") || n.contains("init")) {
                    sb.append(m.name).append('(')
                        .append(m.parameterTypes.joinToString(",") { it.simpleName })
                        .append(");")
                }
            }
            diag("initicon_surface", sb.toString())
        }

        // a) ImageView.setImageResource(int)
        runCatching {
            val m = android.widget.ImageView::class.java
                .getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                if (BALL_ICON_GUARD.get()) return@intercept chain.proceed()
                val view = chain.thisObject as? android.widget.ImageView
                val resIdArg = (chain.args.getOrNull(0) as? Int) ?: 0
                if (view != null && resIdArg != 0) {
                    val ids = runCatching { ballIconIds(view.resources) }.getOrNull()
                    if (ids != null && resIdArg != ids[0] &&
                        (resIdArg == ids[1] || resIdArg == ids[2])
                    ) {
                        val ball = findBallOf(view)
                        if (ball != null && isOurBallView(ball)) {
                            BALL_ICON_GUARD.set(true)
                            try {
                                val fromName = runCatching {
                                    view.resources.getResourceEntryName(resIdArg)
                                }.getOrDefault("id$resIdArg")
                                logBallIconHit("setImageResource", fromName)
                                val newArgs = chain.args.toTypedArray()
                                newArgs[0] = ids[0]
                                return@intercept chain.proceed(newArgs)
                            } finally {
                                BALL_ICON_GUARD.set(false)
                            }
                        }
                    }
                }
                chain.proceed()
            }
            Log.i(TAG, "球图标选图: ImageView.setImageResource OK")
        }.onFailure {
            Log.w(TAG, "球图标选图: setImageResource 装载失败: ${it.javaClass.simpleName}: ${it.message}")
        }

        // b) ★ 3.0.13：ImageView.setImageDrawable(Drawable) —— 兜住宿主
        //   「先取图、再 setDrawable 进图标位」的路径（原 getDrawable
        //   两层 hook 已撤，见函数头注）。receiver 就是图标位本人：
        //   ConstantState 认图 + 沿父链验球正身，双中才换。
        runCatching {
            val m = android.widget.ImageView::class.java
                .getDeclaredMethod(
                    "setImageDrawable",
                    android.graphics.drawable.Drawable::class.java
                )
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                if (BALL_ICON_GUARD.get()) return@intercept chain.proceed()
                val view = chain.thisObject as? android.widget.ImageView
                val drawable = chain.args.getOrNull(0)
                    as? android.graphics.drawable.Drawable
                if (view != null && drawable != null) {
                    val ids = runCatching { ballIconIds(view.resources) }.getOrNull()
                    val earIdx = if (ids != null) {
                        runCatching { earbudIndexOf(view, drawable, ids) }
                            .getOrDefault(-1)
                    } else {
                        -1
                    }
                    if (ids != null && earIdx > 0) {
                        val ball = findBallOf(view)
                        if (ball != null && isOurBallView(ball)) {
                            val head = runCatching {
                                view.resources.getDrawable(ids[0], view.context?.theme)
                            }.getOrNull()
                            if (head != null) {
                                BALL_ICON_GUARD.set(true)
                                try {
                                    val fromName = runCatching {
                                        view.resources.getResourceEntryName(ids[earIdx])
                                    }.getOrDefault("id${ids[earIdx]}")
                                    logBallIconHit("setImageDrawable", fromName)
                                    val newArgs = chain.args.toTypedArray()
                                    newArgs[0] = head
                                    return@intercept chain.proceed(newArgs)
                                } finally {
                                    BALL_ICON_GUARD.set(false)
                                }
                            }
                        }
                    }
                }
                chain.proceed()
            }
            Log.i(TAG, "球图标选图: ImageView.setImageDrawable OK")
        }.onFailure {
            Log.w(TAG, "球图标选图: setImageDrawable 装载失败: ${it.javaClass.simpleName}: ${it.message}")
        }
        diag("hook_ballicon", "installed（3.0.13 仅 ImageView 两入口、验球正身）")
    }

    /**
     * ★ 3.0.14 全屏球阵定位探针 —— 【只观察、绝不改图】。
     *
     * 为什么先探针、后修法（证据链）：
     *  - 下拉面板的球：MagicPods v2.10.6 资源潜行实证 + 本模块 3.0.13
     *    真机表现 —— BasicDeviceBallView.initIcon 经 ImageView 交付
     *    入口在三张图里选，3.0.13 的「父链找球根 + DeviceInfo 验正身」
     *    在面板里命中正常（用户截图：面板/展开卡侧无回归报告）。
     *  - 全屏球阵页：3.0.10 全局 getDrawable 在世时 XM4 球是头戴图、
     *    别家球全误伤 → 该页与面板共用同一批 controlcenter 图资源、
     *    且本模块 hook 代码在该页进程里运行（本 hook 只随
     *    ControlCenterNoiseHook 装进 controlcenter / systemui 两进程；
     *    AAM 进程只装 HonorEarphoneHook，没有本文件的任何代码）。
     *  - 3.0.13 撤掉全局层后该页 XM4 球变回耳塞图 → 该页里耳塞图递进
     *    图标位的调用没被两入口的门逮住：球根类名不同 / 图标位不在
     *    球根 12 层父链内 / 该页构图下球根读不到 DeviceInfo，三者必
     *    居其一 —— 静态材料（本仓代码与注释）定不了是哪一个。
     * 本探针把那一次调用的实况钉死，供下一版按实况装门：
     *  a) ImageView.setImageResource / setImageDrawable：入参是耳塞
     *     两图之一时，记录 receiver 的祖先链类名序列（≤16 层）、
     *     findBallOf 命中形态（ball / 仅名牌祖先 / 无）、该球名牌
     *     文案与是否验明 XM4 —— 只记不改，一律原样 proceed；
     *  b) TextView.setText：文案命中 XM4 或别家设备名时作「球名锚点」
     *     记录 —— 名牌 TextView 的祖先链就是该页球根的真实形状
     *     （名牌必然长在球身上，不受图标位构图影响）。
     * 命中按签名去重、总量封顶（图标 24 / 名牌 12），结果写进
     * Settings.Global 的 magicxm4_diag_gridprobe（图标）与
     * magicxm4_diag_gridprobe_name（名牌），并同步打模块日志。
     * 全程 runCatching 包裹：探针自身出任何问题都不许影响宿主
     * 设图/改名主流程。
     */
    private fun installAamBallGridProbe() {
        var installed = 0
        // a1) ImageView.setImageResource(int)：耳塞图递进图标位时记录。
        runCatching {
            val m = android.widget.ImageView::class.java
                .getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType)
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                if (!BALL_ICON_GUARD.get()) {
                    val view = chain.thisObject as? android.widget.ImageView
                    val resId = (chain.args.getOrNull(0) as? Int) ?: 0
                    if (view != null && resId != 0) {
                        val ids = runCatching { ballIconIds(view.resources) }.getOrNull()
                        if (ids != null && resId != ids[0] &&
                            (resId == ids[1] || resId == ids[2])
                        ) {
                            val fromName = runCatching {
                                view.resources.getResourceEntryName(resId)
                            }.getOrDefault("id$resId")
                            gridProbeIcon(view, "setImageResource", fromName)
                        }
                    }
                }
                chain.proceed()
            }
            installed++
        }.onFailure {
            Log.w(TAG, "球阵探针 setImageResource 装载失败: ${it.javaClass.simpleName}")
        }
        // a2) ImageView.setImageDrawable(Drawable)：同上，认图走
        //   ConstantState 身份（与 3.0.13 b 入口同一认法）。
        runCatching {
            val m = android.widget.ImageView::class.java.getDeclaredMethod(
                "setImageDrawable",
                android.graphics.drawable.Drawable::class.java
            )
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                if (!BALL_ICON_GUARD.get()) {
                    val view = chain.thisObject as? android.widget.ImageView
                    val drawable = chain.args.getOrNull(0)
                        as? android.graphics.drawable.Drawable
                    if (view != null && drawable != null) {
                        val ids = runCatching { ballIconIds(view.resources) }.getOrNull()
                        val earIdx = if (ids != null) {
                            runCatching { earbudIndexOf(view, drawable, ids) }
                                .getOrDefault(-1)
                        } else {
                            -1
                        }
                        if (ids != null && earIdx > 0) {
                            val fromName = runCatching {
                                view.resources.getResourceEntryName(ids[earIdx])
                            }.getOrDefault("id${ids[earIdx]}")
                            gridProbeIcon(view, "setImageDrawable", fromName)
                        }
                    }
                }
                chain.proceed()
            }
            installed++
        }.onFailure {
            Log.w(TAG, "球阵探针 setImageDrawable 装载失败: ${it.javaClass.simpleName}")
        }
        // b) TextView.setText(CharSequence)：球名锚点。文案命中我方或
        //   别家设备名才记（廉价 contains 先行，命不中不取任何状态）。
        runCatching {
            val m = android.widget.TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java
            )
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val view = chain.thisObject as? android.widget.TextView
                val text = chain.args.getOrNull(0) as? CharSequence
                if (view != null && text != null) {
                    val t = text.toString().trim()
                    if (t == OUR_NAME || OTHER_DEVICE_NAMES.any { t.contains(it) }) {
                        gridProbeName(view)
                    }
                }
                chain.proceed()
            }
            installed++
        }.onFailure {
            Log.w(TAG, "球阵探针 setText 装载失败: ${it.javaClass.simpleName}")
        }
        diag("gridprobe", "installed=$installed/3（3.0.14 定位探针，只记不改）")
        Log.i(TAG, "installed: 全屏球阵定位探针 [3.0.14 只观察]")
    }

    /** 球阵探针的图标记录：receiver 祖先链 + 球根命中形态 + 名牌文案。 */
    private fun gridProbeIcon(
        view: android.widget.ImageView,
        kind: String,
        argDesc: String
    ) {
        runCatching {
            val ball = findBallOf(view)
            val chainSb = StringBuilder()
            var node: android.view.View? = view
            var depth = 0
            var nameTxt: String? = null
            while (node != null && depth < 16) {
                if (depth > 0) chainSb.append('<')
                chainSb.append(node.javaClass.simpleName)
                if (nameTxt == null && node is android.view.ViewGroup) {
                    nameTxt = runCatching {
                        (findByIdName(node, "device_ball_name")
                            as? android.widget.TextView)?.text?.toString()
                    }.getOrNull()
                }
                node = node.parent as? android.view.View
                depth++
            }
            val root = when {
                ball != null -> "ball"
                nameTxt != null -> "namedAncestor"
                else -> "none"
            }
            val our = when {
                ball != null -> isOurBallView(ball)
                nameTxt != null -> nameTxt.trim() == OUR_NAME
                else -> false
            }
            val sig = "icon|$kind|$argDesc|$root|$our|${chainSb.take(120)}"
            if (GRID_PROBE_SEEN.add(sig) &&
                GRID_PROBE_ICON_CALLS.incrementAndGet() <= 24
            ) {
                Log.w(
                    TAG,
                    "▶ 球阵探针 icon $kind arg=$argDesc root=$root our=$our " +
                        "name=${nameTxt ?: "-"} chain=$chainSb"
                )
                diag(
                    "gridprobe",
                    "icon $kind arg=$argDesc root=$root our=$our " +
                        "name=${nameTxt ?: "-"} chain=${chainSb.take(300)}"
                )
            }
        }
    }

    /** 球阵探针的名牌锚点记录：名牌 TextView 的祖先链 = 球根真实形状。 */
    private fun gridProbeName(view: android.widget.TextView) {
        runCatching {
            val txt = view.text?.toString()?.trim() ?: return
            val idName = if (view.id != android.view.View.NO_ID) {
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            } else {
                null
            }
            val ball = findBallOf(view)
            val chainSb = StringBuilder()
            var node: android.view.View? = view
            var depth = 0
            while (node != null && depth < 16) {
                if (depth > 0) chainSb.append('<')
                chainSb.append(node.javaClass.simpleName)
                node = node.parent as? android.view.View
                depth++
            }
            val sig = "name|$txt|$idName|${ball != null}|${chainSb.take(120)}"
            if (GRID_PROBE_SEEN.add(sig) &&
                GRID_PROBE_NAME_CALLS.incrementAndGet() <= 12
            ) {
                Log.w(
                    TAG,
                    "▶ 球阵探针 name text=$txt idName=${idName ?: "-"} " +
                        "inBall=${ball != null} chain=$chainSb"
                )
                diag(
                    "gridprobe_name",
                    "text=$txt idName=${idName ?: "-"} inBall=${ball != null} " +
                        "chain=${chainSb.take(300)}"
                )
            }
        }
    }

    /**
     * 断连时按原生华为耳机的样子收缩卡片：**不显示电量、不显示噪声控制**。
     *
     * 电量段的标题 `earphone_battery_title` 文案是空的，所以按 id 找；
     * 噪声控制段按文案找。两处都要求「所在子树里有我方设备名、没有别的设备名」，
     * 避免误伤同时挂着的 OPPO/华为卡片。
     */
    private fun applyOfflineVisibility(root: android.view.View?, online: Boolean) {
        if (root == null) return
        var changed = 0

        // 标题右侧那颗小电池（device_battery_in_card 所在容器）—— 原生耳机断开时也没有
        findByIdName(root, "battery_info_container")?.let { container ->
            if (belongsToUs(container)) {
                val want = if (online) android.view.View.VISIBLE else android.view.View.GONE
                if (container.visibility != want) { container.visibility = want; changed++ }
            }
        }

        findByIdName(root, "earphone_battery_title")?.let { title ->
            if (belongsToUs(title)) {
                (title.parent as? android.view.View)?.let { section ->
                    val want = if (online) android.view.View.VISIBLE else android.view.View.GONE
                    if (section.visibility != want) { section.visibility = want; changed++ }
                }
            }
        }

        findTextView(root, "噪声控制")?.let { title ->
            if (belongsToUs(title)) {
                (title.parent as? android.view.View)?.let { section ->
                    val want = if (online) android.view.View.VISIBLE else android.view.View.GONE
                    if (section.visibility != want) { section.visibility = want; changed++ }
                }
            }
        }

        if (changed > 0) diag("offline", "online=$online 调整 $changed 段")
    }

    /** 这个节点所在的卡片是不是我们的（子树里有我方设备名、且没有别的设备名）。 */
    private fun belongsToUs(node: android.view.View): Boolean =
        ancestorContainingAlso(node, OUR_NAME, OTHER_DEVICE_NAMES) != null

    /** 按资源 id 名在整个窗口里找 View（id 是宿主的，所以只能用名字查）。 */
    private fun findByIdName(root: android.view.View, name: String): android.view.View? {
        val target = root.resources.getIdentifier(name, "id", "com.hihonor.controlcenter")
        if (target == 0) return null
        if (root.id == target) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findByIdName(root.getChildAt(i), name)?.let { return it }
            }
        }
        return null
    }

    private fun cleanupCard(root: android.view.View?) {
        if (root == null) return
        // ★ 3.0.10：球图标不在这里换了 —— fixBallIcon 视图树手术整体退役，
        //   改走宿主选图入口同步改结果（onHook 第 12 节）。卡片清理只管它
        //   本来的事（隐藏无用行等），不再碰任何图标。
        // ★ 3.0.12：响铃查找已改走数据层整项摘除（postFixSections 按 bsId
        //   摘 ServiceCardInfo 数据项，连接/离线都摘），卡片装配时即无此
        //   行，这里不再按文案藏它——原先为它写的「上两层 = 整张子卡」
        //   定位与 diag 视图树 dump 随之删除。本函数只剩「自适应」一颗
        //   噪声档位按钮的隐藏（与 11e initModeButton 里的直接隐藏互为
        //   兜底：卡片重建会重置可见性，两处谁先碰到谁生效）。
        // 卡片真的画出来了 = 宿主进程已经活透，此时拿 Context 最稳。
        HonorAssetsDump.dump(currentApp())
        var hidden = 0
        val target = findTextView(root, "自适应")
        if (target != null) {
            // ★ 判定「这一处是不是我们的卡片」：从目标文案往上找，第一个包含我方设备名的祖先
            //   就是这张卡片的容器；如果这个容器里还有**别的**设备名，说明爬到了球列表那一层
            //   （球列表里所有设备名都在同一棵子树里），必须放弃。
            //   不这么做的话，会把 MagicFree4（Enco Free4）的卡片一起改坏 —— 实测踩过。
            val scope = ancestorContainingAlso(target, OUR_NAME, OTHER_DEVICE_NAMES)
            if (scope != null) {
                // 层级实测（2026-11-02 dump 真机视图树）：
                //   噪声档位 = HwTextView#noise_mode_text → LinearLayout[2](图标+文案) → 档位按钮根
                // 自适应只取上一层 = 这一颗按钮本身；取上两层会把整排四个档位
                // 按钮的容器藏掉、连「关闭/降噪/通透」一起误伤（实测踩过）。
                val upOne = target.parent as? android.view.View
                val node: android.view.View = upOne ?: target
                if (node.visibility != android.view.View.GONE) {
                    node.visibility = android.view.View.GONE
                    hidden++
                }
            }
        }
        if (hidden > 0) diag("hide", "已隐藏 $hidden 项（${OUR_NAME}）")

    }

    /**
     * 从 [node] 往上找第一个「同时包含 [required] 且不包含任何 [forbidden]」的祖先。
     * 找不到返回 null（表示这一处不是我们该动的卡片）。
     */
    private fun ancestorContainingAlso(
        node: android.view.View,
        required: String,
        forbidden: List<String>,
    ): android.view.View? {
        var cur: android.view.View? = node.parent as? android.view.View
        while (cur != null) {
            val hasOurs = findTextView(cur, required) != null
            if (hasOurs) {
                val hasOther = forbidden.any { findTextView(cur, it) != null }
                return if (hasOther) null else cur
            }
            cur = cur.parent as? android.view.View
        }
        return null
    }

    private fun findTextView(view: android.view.View, text: String): android.view.View? {
        if (view is android.widget.TextView && view.visibility == android.view.View.VISIBLE &&
            view.text?.toString()?.contains(text) == true
        ) {
            return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextView(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun dumpTree(v: android.view.View, depth: Int, sb: StringBuilder) {
        if (depth > 18 || sb.length > 9000) return
        sb.append("  ".repeat(depth)).append(v.javaClass.simpleName)
        val id = v.id
        if (id != android.view.View.NO_ID) {
            val name = runCatching { v.resources.getResourceEntryName(id) }.getOrNull()
            sb.append('#').append(name ?: id)
        }
        if (v is android.widget.TextView) sb.append(" \"").append(v.text).append('"')
        if (v is android.view.ViewGroup) sb.append(" [").append(v.childCount).append(']')
        sb.append('\n')
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, sb)
        }
    }

    private fun readGlobal(key: String): String? = runCatching {
        val ctx = io.github.yuxiximyxx.magicxm4.utils.DeviceIdentity.currentApp() ?: return null
        android.provider.Settings.Global.getString(ctx.contentResolver, key)
    }.getOrNull()

    private fun setGlobal(key: String, value: String) = runCatching {
        val ctx = io.github.yuxiximyxx.magicxm4.utils.DeviceIdentity.currentApp() ?: return false
        android.provider.Settings.Global.putString(ctx.contentResolver, key, value)
        true
    }.getOrDefault(false)

    private fun ran(@Suppress("UNUSED_PARAMETER") ok: Boolean) = Unit

}
