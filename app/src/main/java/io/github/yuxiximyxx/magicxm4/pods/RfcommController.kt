package io.github.yuxiximyxx.magicxm4.pods

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.content.*
import android.content.SharedPreferences
import io.github.yuxiximyxx.magicxm4.BuildConfig
import io.github.yuxiximyxx.magicxm4.hook.Log
import io.github.yuxiximyxx.magicxm4.utils.MediaControl
import io.github.yuxiximyxx.magicxm4.utils.MagicXm4Action
import io.github.yuxiximyxx.magicxm4.utils.huaweiStrongToast.HuaweiStrongToastUtil
import io.github.yuxiximyxx.magicxm4.utils.huaweiStrongToast.data.BatteryParams
import io.github.yuxiximyxx.magicxm4.utils.huaweiStrongToast.data.PodParams
import io.github.yuxiximyxx.magicxm4.pods.hem.*
import io.github.yuxiximyxx.magicxm4.pods.sony.*
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * RfcommController — RFCOMM SPP 连接控制器。
 *
 * 防炸机要点：
 * 1. 所有 socket 操作都有超时（30s connect timeout），避免无限等待卡死线程
 * 2. 用 AtomicLong generation 防止旧连接的回调污染新连接
 * 3. 断开连接时先取消所有协程再关闭 socket，避免资源泄漏
 * 4. 广播发送用 setPackage() 限定目标包，不被其他 app 截获
 * 5. 所有反射调用用 runCatching，任何异常只记录日志不影响主流程
 * 6. packet reader 循环有最大单次读取长度限制（1024 byte buffer）
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "MagicXM4-Rfcomm"
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 30_000L
    private const val RFCOMM_READ_TIMEOUT_MS = 5_000L

    /**
     * 索尼 SPP UUID（SonyHeadphonesClient Client/Constants.h:11 实证）。
     * XM4 只暴露这一个服务，Secure 连不上时先试 Insecure，最后兜底通道。
     */
    private val SPP_UUIDS: List<UUID> = listOf(
        UUID.fromString("96CC203E-5068-46ad-B32D-E316F5E069BA"),
    )
    /** UUID 失败时，反射取 RFCOMM 通道兜底。 */
    private const val RFCOMM_CHANNEL_FALLBACK = 15

    private val framer = SonyWire.Framer().also { f ->
        f.onDiscard = { Log.w(TAG, "丢弃非帧字节: ${SonyWire.hex(it)}") }
    }

    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    private lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    // ── 线程安全的状态计数 ──────────────────────────────────────
    private val connectionGeneration = AtomicLong(0)
    private val isConnected = AtomicBoolean(false)

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?
    )

    private var mShowedConnectedToast = false
    private var lastTempBatt = 0
    private var currentBatteryParams: BatteryParams? = null
    private var currentAnc: Int = 1
    private var lastRecordedAnc: Int = -1   // current_anc 去重（心跳响应防高频写）

    // ── 索尼协议状态 ────────────────────────────────────────────
    /** 出站序号：每发一条 +1；收到 ACK 后按设备的 seq 对齐（Gadgetbridge 的同款做法）。 */
    private val sonySeq = SonyCommands.SeqCounter(0)

    /**
     * 降噪/环境声设置载荷布局。
     *
     * 两个参考实现在「下标 3/4」上冲突：Gadgetbridge 认 4=风噪能力标志、5=模式位；
     * SonyHeadphonesClient 认 4=NV_ASM_SETTING_TYPE、5=dualSingle。
     * XM4 的 coordinator 声明了 WindNoiseReduction，所以默认走 WIND_CAPABLE；
     * 真机不对时用 `magicxm4_sony_layout=1` 切到 LEGACY，不用改代码重编。
     */
    @Volatile
    private var sonyLayout: SonyCommands.NcAsmLayout = SonyCommands.NcAsmLayout.WIND_CAPABLE

    /** 荣耀「通透」档 → 索尼环境声等级 1..20（`magicxm4_ambient_level` 可覆盖）。 */
    @Volatile
    private var ambientLevel: Int = 15
    @Volatile private var lastAncStackAt = 0L   // setANCMode 调用栈限频

    /** 自定义 EQ 列表内存缓存（0x8122 回包时更新；跨进程读 Settings 可能拿不到 resolver） */
    @Volatile
    private var eqCustomCache: List<HemEq.Preset> = emptyList()
    private var currentGameMode: Boolean = false
    private var currentLowLatency: Boolean = false
    private var currentDualConn: Boolean = false
    private var currentEq: EqMode = EqMode.BALANCED
    private var currentWindSuppression: Boolean = false
    private var currentInEarDetection: Boolean = false

    private var cachedLeftBattery: PodParams? = null
    private var cachedRightBattery: PodParams? = null
    private var cachedCaseBattery: PodParams? = null
    private val PREFS_NAME = "magicxm4_battery"

    // ── 协程作用域 ──────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            safeHandleBroadcast(p0, p1)
        }
    }

    private fun safeHandleBroadcast(context: Context?, intent: Intent?) {
        if (intent == null) return
        // 可见性：本 session 长期无法确认 receiver 是否注册/是否收到广播，这里强制打 I 级日志。
        Log.i(TAG, "★ 收到广播 action=${intent.action} ctx=${context?.javaClass?.simpleName}")
        try {
            when (intent.action) {
                MagicXm4Action.ACTION_GET_PODS_MAC -> {
                    Log.i(TAG, "→ ACTION_GET_PODS_MAC → 回复 PODS_MAC_RECEIVED")
                    safeSendBroadcast(context, MagicXm4Action.ACTION_PODS_MAC_RECEIVED) {
                        putExtra("mac", mDevice.address)
                    }
                }
                else -> {
                    Log.i(TAG, "→ 转入 safeHandleUIEvent action=${intent.action}")
                    safeHandleUIEvent(intent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "broadcast handler threw", e)
        }
    }

    private fun safeSendBroadcast(context: Context?, action: String, fill: Intent.() -> Unit = {}) {
        if (context == null) return
        try {
            Intent(action).apply {
                if (::mDevice.isInitialized) { putExtra("address", mDevice.address); putExtra("device_name", mDevice.name) }
                fill()
                `package` = "com.android.systemui"
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendBroadcast failed: $action", e)
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = currentBatteryParams,
            anc = currentAnc,
            transparencyVocalEnhancement = false,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name else null
        )
    }

    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int): String = when (anc) {
        5 -> "0103"; 6 -> "0101"; 7 -> "0100"; 8 -> "0102"
        2 -> "0200"; 3 -> "0200"; else -> "0000"
    }

    /** 定期轮询：只问「当前降噪档 + 电量」这两件本项目真需要的事。 */
    fun queryStatus() {
        scope.launch {
            try {
                sendSony { SonyCommands.queryNoiseState(it) }; delay(80)
                sendSony { SonyCommands.batteryRequest(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "queryStatus coroutine threw", e)
            }
        }
    }

    /**
     * 连接后的索尼协议初始化序列。
     *
     * 不发 INIT_REQUEST：SonyHeadphonesClient 从发握手、连上第一条业务命令就能工作
     * （Gadgetbridge 会发 `00 00`，但那是为了探测 V1/V2 协议版本，我们用不上）。
     * 少一步 = 少一个失败点，所以这里直接问三个状态：降噪 → 电量 → 固件。
     */
    private fun sonyInit() {
        scope.launch {
            try {
                delay(300)
                loadSonyPrefs()
                sendSony { SonyCommands.queryNoiseState(it) }
                delay(200); sendSony { SonyCommands.batteryRequest(it) }
                delay(200); sendSony { SonyCommands.firmwareRequest(it) }
                delay(300); sendSony { SonyCommands.queryNoiseState(it) }
                // 记忆式 ANC：连上后恢复上次用户选择的档位
                runCatching { restoreAncIfAny() }
                Log.i(TAG, "索尼初始化查询已下发（layout=$sonyLayout ambientLevel=$ambientLevel）")
            } catch (e: Throwable) {
                Log.e(TAG, "sonyInit failed", e)
            }
        }
    }

    /** 读运行期覆盖项：载荷布局、环境声等级。读不到就用默认值。 */
    private fun loadSonyPrefs() {
        val ctx = mContext ?: return
        runCatching {
            val layout = android.provider.Settings.Global
                .getString(ctx.contentResolver, "magicxm4_sony_layout")?.toIntOrNull()
            sonyLayout = when (layout) {
                1 -> SonyCommands.NcAsmLayout.LEGACY_DUAL_SINGLE
                0 -> SonyCommands.NcAsmLayout.WIND_CAPABLE
                else -> sonyLayout
            }
            val level = android.provider.Settings.Global
                .getString(ctx.contentResolver, "magicxm4_ambient_level")?.toIntOrNull()
            if (level != null) ambientLevel = level.coerceIn(1, SonyCommands.MAX_ASM_LEVEL)
        }.onFailure { Log.w(TAG, "loadSonyPrefs failed", it) }
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences) {
        writeGlobal("magicxm4_diag_connectpod", "enter dev=${device.address} isConnected=${isConnected.get()}")
        // 防止并发连接
        if (isConnected.compareAndSet(false, true)) {
            Log.d(TAG, "connectPod started gen=${connectionGeneration.incrementAndGet()} device=${device.address}")
        } else {
            Log.d(TAG, "connectPod skipped: already connecting")
            writeGlobal("magicxm4_diag_connectpod", "skipped: already connecting")
            return
        }

        mContext = context
        mDevice = device
        mPrefs = prefs

        // ★ 跨进程桥：把真实耳机 MAC 告诉设备中心（controlcenter 进程）。
        //   上游把作者自己的耳机 MAC 写死在注入链里，导致换耳机后注入的是「幽灵设备」；
        //   这里每次连接都写一次真实地址，断开时**不清除**（保留上次值，
        //   让设备中心能先行注入、连上后自然转在线）。
        runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver, "magicxm4_mac", device.address
            )
            Log.i(TAG, "已写入 magicxm4_mac=${device.address}（供设备中心识别真实设备）")
        }.onFailure { Log.w(TAG, "写入 magicxm4_mac 失败: ${it.javaClass.simpleName}: ${it.message}") }

        runCatching { initBatteryCache(context) }
            .onFailure { Log.w(TAG, "initBatteryCache failed", it) }

        // 注册广播 — 必须有 try-catch，注册失败不阻止连接
        runCatching {
            val filter = IntentFilter().apply {
                listOf(
                    MagicXm4Action.ACTION_ANC_SELECT,
                    MagicXm4Action.ACTION_FIND_BUDS,
                    MagicXm4Action.ACTION_FEATURE_SET,
                    MagicXm4Action.ACTION_EQ_PRESET,
                    MagicXm4Action.ACTION_EQ_CUSTOM,
                    MagicXm4Action.ACTION_ALERT_VOLUME,
                    MagicXm4Action.ACTION_GESTURE_SET,
                    MagicXm4Action.ACTION_FIT_TEST,
                    MagicXm4Action.ACTION_QUERY_DEVICES,
                    MagicXm4Action.ACTION_PODS_UI_INIT,
                    MagicXm4Action.ACTION_GET_PODS_MAC,
                    MagicXm4Action.ACTION_REFRESH_STATUS,
                    MagicXm4Action.ACTION_GAME_MODE_SET,
                    MagicXm4Action.ACTION_LOW_LATENCY_SET,
                    MagicXm4Action.ACTION_DUAL_CONN_SET,
                    MagicXm4Action.ACTION_EQ_SET,
                    MagicXm4Action.ACTION_WIND_SUPPRESSION_SET,
                    MagicXm4Action.ACTION_IN_EAR_DETECTION_SET
                ).forEach { addAction(it) }
            }
            context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "broadcast receiver registered")
        }.onFailure { e ->
            Log.w(TAG, "broadcast receiver registration failed: ${e.message}")
        }

        // 发送连接广播
        val deviceName = device.alias ?: device.name ?: device.address
        try {
            Intent(MagicXm4Action.ACTION_PODS_CONNECTED).apply {
                putExtra("device_name", deviceName)
                putExtra("address", mDevice.address)
                `package` = BuildConfig.APPLICATION_ID
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "send broadcast failed", e)
        }
        context.getSharedPreferences("magicxm4_device", Context.MODE_PRIVATE)
            .edit().putString("device_name", deviceName).apply()

        MediaControl.mContext = context

        // RFCOMM 连接在独立线程执行，避免阻塞蓝牙服务线程
        thread(name = "magicxm4-rfcomm-connect") {
            safeRfcommConnect(device)
        }

        // 电池轮询线程
        thread(name = "magicxm4-battery-poll") {
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            while (isConnected.get()) {
                try { Thread.sleep(BATTERY_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (isConnected.get()) queryStatus()
            }
        }
    }

    private fun safeRfcommConnect(device: BluetoothDevice) {
        val gen = connectionGeneration.get()
        var attempt = 0
        val maxAttempts = 2
        // A2DP 刚连上时耳机侧 SPP 可能还没就绪，给一点缓冲
        runCatching { Thread.sleep(1200) }

        while (attempt < maxAttempts && isConnected.get() && connectionGeneration.get() == gen) {
            attempt++
            try {
                // 先取消正在进行的配对/发现
                runCatching {
                    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                }.onFailure { Log.w(TAG, "cancelDiscovery failed", it) }

                // 先尝试标准 RFCOMM socket
                var sock: BluetoothSocket? = null
                for (uuid in SPP_UUIDS) {
                    if (sock != null) break
                    try {
                        sock = device.createRfcommSocketToServiceRecord(uuid)
                        sock?.connect()
                        Log.d(TAG, "RFCOMM connected (secure $uuid) gen=$gen")
                        writeGlobal("magicxm4_diag_spp", "connected secure $uuid")
                    } catch (e: IOException) {
                        Log.w(TAG, "Secure RFCOMM $uuid failed: ${e.message}", e)
                        sock = null
                        try {
                            sock = device.createInsecureRfcommSocketToServiceRecord(uuid)
                            sock?.connect()
                            Log.d(TAG, "RFCOMM connected (insecure $uuid) gen=$gen")
                            writeGlobal("magicxm4_diag_spp", "connected insecure $uuid")
                        } catch (e2: IOException) {
                            Log.w(TAG, "Insecure RFCOMM $uuid failed: ${e2.message}", e2)
                            sock = null
                        }
                    }
                }
                if (sock == null) {
                    runCatching {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val ch = m.invoke(device, RFCOMM_CHANNEL_FALLBACK) as BluetoothSocket
                        ch.connect()
                        sock = ch
                        Log.d(TAG, "RFCOMM connected (channel $RFCOMM_CHANNEL_FALLBACK) gen=$gen")
                        writeGlobal("magicxm4_diag_spp", "connected channel $RFCOMM_CHANNEL_FALLBACK")
                    }.onFailure { Log.w(TAG, "channel fallback failed: ${it.message}") }
                }
                if (sock == null) {
                    Log.e(TAG, "RFCOMM connect failed completely after $attempt attempts")
                    if (attempt < maxAttempts) {
                        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                        continue
                    }
                    break
                }

                if (sock != null) {
                    socket = sock
                    // 读取线程
                    thread(name = "magicxm4-rfcomm-reader") {
                        safePacketReader(sock!!.inputStream, gen)
                    }
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                    sonyInit()
                    return
                }
            } catch (e: Throwable) {
                Log.e(TAG, "rfcomm connection attempt $attempt failed", e)
                if (attempt >= maxAttempts) break
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }

        // 所有尝试失败，清理状态
        if (connectionGeneration.get() == gen) {
            Log.e(TAG, "All RFCOMM connection attempts failed, disconnecting")
            writeGlobal("magicxm4_diag_spp", "all attempts failed")
            safeDisconnect(device)
        }
    }

    private fun safePacketReader(inputStream: InputStream, gen: Long) {
        val buffer = ByteArray(1024)
        val dataBuffer = ByteArray(2048)
        var dataBufferPos = 0
        try {
            while (isConnected.get() && connectionGeneration.get() == gen) {
                val bytesRead = runCatching { inputStream.read(buffer) }.getOrNull() ?: continue
                if (connectionGeneration.get() != gen) break
                if (bytesRead > 0) {
                    val frames = framer.append(buffer, bytesRead)
                    for (frame in frames) {
                        runCatching { SonyWire.parse(frame)?.let { handleSonyPacket(it) } }
                            .onFailure { Log.w(TAG, "sony packet handle failed", it) }
                    }
                } else if (bytesRead == -1) {
                    break
                }
            }
        } catch (e: IOException) {
            if (isConnected.get() && connectionGeneration.get() == gen) {
                Log.e(TAG, "RFCOMM read error", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "packet reader unknown error", e)
        } finally {
            if (connectionGeneration.get() == gen && isConnected.get()) {
                safeDisconnect(mDevice)
            }
        }
    }

    /** 索尼报文分发。帧层（0x3E/0x3C/转义/校验和）在 SonyWire，这里只管语义。 */
    private fun handleSonyPacket(msg: SonyWire.Message) {
        if (BuildConfig.DEBUG) {
            Log.v(
                TAG,
                "RX type=0x%02X seq=0x%02X payload=%s".format(msg.type, msg.seq, SonyWire.hex(msg.payload))
            )
        }
        when (val event = SonyParser.parse(msg)) {
            is SonyEvent.Ack -> {
                // 设备确认了某条请求。把序号计数器对齐到「它的下一位」，
                // 否则长连接里两边各走各的、迟早错开（Gadgetbridge 同款处理）。
                sonySeq.adopt((event.seq + 1) and 0xFF)
                if (BuildConfig.DEBUG) Log.v(TAG, "ACK seq=${event.seq}")
            }

            is SonyEvent.NoiseState -> applySonyNoise(event.state)

            is SonyEvent.Battery -> onSonyBattery(event.battery)

            is SonyEvent.Firmware -> {
                writeGlobal("magicxm4_firmware", event.version)
                Log.i(TAG, "固件版本: ${event.version}")
            }

            is SonyEvent.Unknown -> {
                val cmd = event.raw.firstOrNull()?.let { "0x%02X".format(it.toInt() and 0xFF) } ?: "--"
                Log.i(
                    TAG,
                    "未消费的索尼报文 type=0x%02X cmd=%s：%s（hex=%s）"
                        .format(event.dataType, cmd, event.reason, SonyWire.hex(event.raw))
                )
            }
        }
    }

    /**
     * 索尼降噪状态 → 荣耀三档 status → UI。
     *
     * XM4 有四个模式（关 / 降噪 / 环境声 / 风噪降低），荣耀服务卡只有三档。
     * 风噪降低归到「降噪」：两者都是「压掉外界声音」，用户在面板上看到高亮
     * 落在「降噪」是符合直觉的；但不能悄悄归并，所以单独写一条原始模式记录。
     */
    private fun applySonyNoise(state: SonyNoiseState) {
        val status = when (state.mode) {
            SonyCommands.NoiseMode.OFF -> 1
            SonyCommands.NoiseMode.AMBIENT_SOUND -> 2
            SonyCommands.NoiseMode.NOISE_CANCELLING -> 5
            SonyCommands.NoiseMode.WIND_NOISE_REDUCTION -> 5
        }
        Log.i(
            TAG,
            "降噪回读 mode=${state.mode} level=${state.ambientLevel} voice=${state.focusOnVoice} → status=$status（${HemAnc.nameOfStatus(status)}）"
        )
        // 详情页与排障用：原始模式 + 环境声等级都要能看见
        writeGlobal("magicxm4_anc_raw", "${state.mode.name}:${state.ambientLevel}")
        if (state.mode == SonyCommands.NoiseMode.AMBIENT_SOUND && state.ambientLevel > 0) {
            ambientLevel = state.ambientLevel.coerceIn(1, SonyCommands.MAX_ASM_LEVEL)
        }
        currentAnc = status
        changeUIAncStatus(status)
    }

    /** 索尼电量 → 荣耀电量结构。 */
    private fun onSonyBattery(b: SonyBattery) {
        writeGlobal("magicxm4_battery_single", b.level.toString())
        // WH-1000XM4 只有一颗总电量（无左右耳、无充电仓）。荣耀侧的读取结构是「双耳 + 仓」，
        // 所以把同一个值喂给 L/R（计划书 §协议层：沿用双耳喂入路径时 L/R 同值并注明），
        // 充电仓留 null，避免面板画出第三个不存在的环。
        handleBatteryChanged(
            BatteryParser.BatteryResult(
                left = BatteryParser.BatteryInfo(b.level, b.charging),
                right = BatteryParser.BatteryInfo(b.level, b.charging),
                case = null,
            )
        )
    }

    private fun safeDisconnect(device: BluetoothDevice) {
        connectionGeneration.incrementAndGet()
        isConnected.set(false)
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        mContext = null
        MediaControl.mContext = null
        runCatching { mContext?.unregisterReceiver(broadcastReceiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver failed") }
        mShowedConnectedToast = false
        Log.d(TAG, "disconnected device=${device.address}")
    }

    private fun safeHandleUIEvent(intent: Intent) {
        try {
            when (intent.action) {
                MagicXm4Action.ACTION_PODS_UI_INIT -> {
                    Log.i(TAG, "UI Init")
                    val deviceName = mDevice.alias ?: mDevice.name ?: mDevice.address
                    safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", deviceName)
                        putExtra("address", mDevice.address)
                    }
                    mContext?.getSharedPreferences("magicxm4_device", Context.MODE_PRIVATE)
                        ?.edit()?.putString("device_name", deviceName)?.apply()
                    queryStatus()
                }
                MagicXm4Action.ACTION_ANC_SELECT -> setANCMode(intent.getIntExtra("status", 0))
                MagicXm4Action.ACTION_FIND_BUDS -> setFindBuds(intent.getBooleanExtra("on", true))
                MagicXm4Action.ACTION_FEATURE_SET ->
                    setFeature(intent.getIntExtra("id", -1), intent.getBooleanExtra("on", false))
                MagicXm4Action.ACTION_EQ_PRESET -> setEqPreset(intent.getIntExtra("id", -1))
                MagicXm4Action.ACTION_EQ_CUSTOM -> eqCustom(
                    intent.getIntExtra("action", -1),
                    intent.getIntExtra("id", -1),
                    intent.getStringExtra("name"),
                    intent.getStringExtra("gains")
                )
                MagicXm4Action.ACTION_ALERT_VOLUME -> setAlertVolume(intent.getIntExtra("level", 5))
                MagicXm4Action.ACTION_FIT_TEST -> setFitTest(intent.getBooleanExtra("on", true))
                // WH-1000XM4 是单点配对，没有「已连接设备列表」这种查询
                MagicXm4Action.ACTION_QUERY_DEVICES -> unsupported("设备列表查询")
                MagicXm4Action.ACTION_GESTURE_SET ->
                    setGestureBinding(
                        intent.getIntExtra("dev", -1),
                        intent.getIntExtra("btn", -1),
                        intent.getIntExtra("act", -1),
                        intent.getIntExtra("fn", 0)
                    )
                MagicXm4Action.ACTION_REFRESH_STATUS -> queryStatus()
                MagicXm4Action.ACTION_GAME_MODE_SET -> setGameMode(intent.getBooleanExtra("enabled", false))
                MagicXm4Action.ACTION_LOW_LATENCY_SET -> setLowLatency(intent.getBooleanExtra("enabled", false))
                MagicXm4Action.ACTION_DUAL_CONN_SET -> setDualConn(intent.getBooleanExtra("enabled", false))
                MagicXm4Action.ACTION_EQ_SET -> setEq(EqMode.fromValue(intent.getIntExtra("value", 0)))
                MagicXm4Action.ACTION_WIND_SUPPRESSION_SET -> setWindSuppression(intent.getBooleanExtra("enabled", false))
                MagicXm4Action.ACTION_IN_EAR_DETECTION_SET -> setInEarDetection(intent.getBooleanExtra("enabled", false))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "UI event handler threw", e)
        }
    }

    private fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        runCatching {
            if (result.left != null) {
                cachedLeftBattery = PodParams(result.left.level, result.left.isCharging, true, 0)
                saveBattery("left_battery", "left_charging", result.left.level, result.left.isCharging)
            }
            if (result.right != null) {
                cachedRightBattery = PodParams(result.right.level, result.right.isCharging, true, 0)
                saveBattery("right_battery", "right_charging", result.right.level, result.right.isCharging)
            }
            if (result.case != null) {
                cachedCaseBattery = PodParams(result.case.level, result.case.isCharging, true, 0)
                saveBattery("case_battery", "case_charging", result.case.level, result.case.isCharging)
            }
        }.onFailure { Log.e(TAG, "battery cache update failed", it) }

        val left = cachedLeftBattery ?: PodParams(result.left?.level ?: 0, result.left?.isCharging == true, result.left != null, 0)
        val right = cachedRightBattery ?: PodParams(result.right?.level ?: 0, result.right?.isCharging == true, result.right != null, 0)
        val case = cachedCaseBattery ?: PodParams(result.case?.level ?: 0, result.case?.isCharging == true, result.case != null, 0)

        if (BuildConfig.DEBUG) Log.v(TAG, "batt L${left.battery} R${right.battery} C${case.battery}")

        val shouldShowToast = !mShowedConnectedToast
        val hasValidData = (left.isConnected && left.battery > 0) || (right.isConnected && right.battery > 0)
        currentBatteryParams = BatteryParams(left, right, case)

        runCatching {
            val ctx = mContext ?: return@runCatching
            if (shouldShowToast && hasValidData) {
                HuaweiStrongToastUtil.showConnectedToast(ctx, currentBatteryParams!!)
                mShowedConnectedToast = true
            }
            HuaweiStrongToastUtil.showPodsBatteryToast(ctx, currentBatteryParams!!)
            // ★ 任务4：双耳电量通知迁移到「音频切换」app 的连接耳机通知（imedia.sws），
            //   格式「WH-1000XM4 左耳 xx% 右耳 xx%」—— 蓝牙进程不再独立弹电量通知。
            // HuaweiStrongToastUtil.showBtNotification(ctx, mDevice, currentBatteryParams!!)
            // 取消旧通知（迁移后清掉可能残留的历史通知）
            runCatching { HuaweiStrongToastUtil.cancelPodsNotification(ctx) }
            changeUIBatteryStatus(currentBatteryParams!!)
            // 跨进程电量通道：SharedPreferences 是 bluetooth 私有目录，Settings/NoticeFlow 读不到，
            // 转存 Settings.Global（全进程只读可见），供通知 hook 追加电量。
            runCatching {
                val res = ctx.contentResolver
                android.provider.Settings.Global.putString(
                    res, "magicxm4_battery",
                    "L${left.battery},R${right.battery},C${case.battery}"
                )
                // 连接态信号（任务6）：电量数据到达 = 耳机在线；断连时 disconnectedPod 置 0。
                // 设备中心球列表注入据此门控 —— 断连后球/卡片消失。
                android.provider.Settings.Global.putString(res, "magicxm4_connected", "1")
                Log.i(TAG, "电量已写入 Settings.Global: L${left.battery},R${right.battery}")
                runCatching {
                    historyAndLowBattery(
                        ctx,
                        left.battery, right.battery, case.battery,
                        left.isCharging, right.isCharging, case.isCharging
                    )
                }
            }.onFailure { Log.w(TAG, "Global 写入失败: ${it.javaClass.simpleName}") }
        }.onFailure { Log.e(TAG, "battery toast/notification failed", it) }

        lastTempBatt = when {
            left.isConnected && right.isConnected -> minOf(left.battery, right.battery)
            left.isConnected -> left.battery
            right.isConnected -> right.battery
            else -> -1
        }
    }

    /**
     * 电量快照：写入历史（供「电量历史」页）并在低电量时发一条通知。
     * 历史格式：`HH:mm,L,R,C;...`，最多保留 40 条。
     */
    private fun historyAndLowBattery(
        ctx: Context,
        l: Int, r: Int, c: Int,
        lCharging: Boolean, rCharging: Boolean, cCharging: Boolean,
    ) {
        val res = ctx.contentResolver
        val stamp = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val entry = "$stamp,$l,$r,$c"
        val old = runCatching {
            android.provider.Settings.Global.getString(res, "magicxm4_batt_history")
        }.getOrNull().orEmpty()
        val list = old.split(';').filter { it.isNotBlank() }.toMutableList()
        // 同一分钟内同值不重复记录
        if (list.lastOrNull()?.substringAfter(',') != "$l,$r,$c") {
            list.add(entry)
            while (list.size > 40) list.removeAt(0)
            android.provider.Settings.Global.putString(res, "magicxm4_batt_history", list.joinToString(";"))
        }

        // 低电量通知：耳机电量 ≤20% 且未充电
        val low = buildList {
            if (l in 1..20 && !lCharging) add("左耳 $l%")
            if (r in 1..20 && !rCharging) add("右耳 $r%")
        }
        if (low.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastLowBatteryAt < 30 * 60 * 1000L) return
        lastLowBatteryAt = now
        runCatching { postLowBatteryNotification(ctx, low.joinToString("、")) }
    }

    @Volatile
    private var lastLowBatteryAt = 0L

    private fun postLowBatteryNotification(ctx: Context, detail: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "magicxm4_low_battery"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "耳机电量提醒", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "耳机电量低于 20% 时提醒" }
            )
        }
        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("耳机电量偏低")
            .setContentText("$detail，请及时充电")
            .setAutoCancel(true)
            .build()
        nm.notify(0x4D46, n)
        Log.i(TAG, "低电量通知: $detail")
    }

    fun initBatteryCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt("left_battery", 0) > 0) cachedLeftBattery = PodParams(
            prefs.getInt("left_battery", 0), prefs.getBoolean("left_charging", false), true, 0
        )
        if (prefs.getInt("right_battery", 0) > 0) cachedRightBattery = PodParams(
            prefs.getInt("right_battery", 0), prefs.getBoolean("right_charging", false), true, 0
        )
        if (prefs.getInt("case_battery", 0) > 0) cachedCaseBattery = PodParams(
            prefs.getInt("case_battery", 0), prefs.getBoolean("case_charging", false), true, 0
        )
    }

    private fun saveBattery(keyBattery: String, keyCharging: String, battery: Int, isCharging: Boolean) {
        runCatching {
            mContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
                putInt(keyBattery, battery)
                putBoolean(keyCharging, isCharging)
                apply()
            }
        }.onFailure { Log.w(TAG, "saveBattery failed", it) }
    }

    /**
     * 发一条索尼协议消息。
     *
     * 调用方只提供「按序号构造消息」的 lambda，序号由 [sonySeq] 统一分发 ——
     * 这样任何新增命令都不可能忘记取号（索尼设备的 ACK 是带 seq 回显的）。
     */
    private fun sendSony(build: (Int) -> SonyWire.Message) {
        ensureLink()
        val msg = build(sonySeq.next())
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "TX type=${msg.type} seq=${msg.seq} payload=${SonyWire.hex(msg.payload)}")
        }
        sendPacketSafe(SonyWire.build(msg))
    }

    private fun sendPacketSafe(packet: ByteArray) {
        // 最后一环可见性：确认 SPP 帧真的写出去了（本 session 无法用 btm 日志验证数据包）
        Log.i(TAG, "→→ SPP 写出 len=${packet.size} hex=${packet.joinToString(" ") { String.format("%02X", it) }} socket=${socket != null}")
        try {
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
        } catch (e: Throwable) {
            Log.e(TAG, "sendPacketSafe unknown error", e)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_ANC_CHANGED) {
            putExtra("address", mDevice.address)
            putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(MagicXm4Action.ACTION_PODS_ANC_CHANGED) { putExtra("status", status) }
        // ★ 真实档位统一记录点（bug修复）：本函数是所有 currentAnc 更新的汇合点 ——
        //   ① 连接时 QUERY_ANC 查询帧的响应（AncModeParser 解析）
        //   ② 耳机本体触控切换（耳机主动上报）
        //   ③ 手机端 setANCMode 下发
        //   三条路径都会写 magicxm4_current_anc，设备中心/蓝牙页据此**显示**当前模式，
        //   查询/上报只是读取，不产生"调模式"数据包。
        //   ★ 去重：心跳响应会被 AncModeParser 反复解析出同值（实测每 12ms 一次），
        //     相同状态不再重复写 Settings/发日志（高频写入拖慢系统）。
        if (status != lastRecordedAnc) {
            lastRecordedAnc = status
            val recCtx = mContext ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            }.getOrNull()
            recCtx?.let { ctx ->
                runCatching {
                    android.provider.Settings.Global.putString(
                        ctx.contentResolver, "magicxm4_current_anc", status.toString()
                    )
                    Log.i(TAG, "真实 ANC 模式记录(汇合点): $status")
                }.onFailure { Log.w(TAG, "current_anc 写入失败: ${it.javaClass.simpleName}") }
            } ?: Log.w(TAG, "current_anc 未记录：context 均为 null")
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", mDevice.address)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(MagicXm4Action.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", mDevice.address)
            putBatteryExtras(status)
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        // ★ mContext 可能尚未初始化（实测静默 return → 外部广播从未发出 →
        //   Settings 侧收不到真实档位）—— 兜底 currentApplication。
        val ctx = mContext ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull() ?: run {
            Log.w(TAG, "sendExternalPodsStatusBroadcast 跳过：context 不可用 (action=$action)")
            return
        }
        val targets = listOf("com.android.bluetooth", "com.android.settings")
        targets.forEach { targetPackage ->
            try {
                Intent(action).apply {
                    if (::mDevice.isInitialized) { putExtra("address", mDevice.address); putExtra("device_name", mDevice.name) }
                    fill()
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    ctx.sendBroadcast(this)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "sendBroadcast to $targetPackage failed", e)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_GAME_MODE_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUILowLatencyStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_LOW_LATENCY_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIDualConnStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_DUAL_CONN_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIEqStatus(mode: EqMode) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_EQ_CHANGED) { putExtra("value", mode.value) }
    }
    private fun changeUIWindSuppressionStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_WIND_SUPPRESSION_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIInEarDetectionStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicXm4Action.ACTION_PODS_IN_EAR_DETECTION_CHANGED) { putExtra("enabled", enabled) }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        if (::mDevice.isInitialized && mDevice.address != device.address) return
        safeDisconnect(device)
        // 断联时移除电量通知 —— 此前 cancelPodsNotification 从未被调用，导致通知不消失。
        runCatching {
            HuaweiStrongToastUtil.cancelPodsNotification(context)
            // 任务6：断连置 0 → 设备中心球列表注入据此消失
            android.provider.Settings.Global.putString(context.contentResolver, "magicxm4_connected", "0")
            Log.i(TAG, "已移除电量通知（断联 ${device.address}）")
        }.onFailure { Log.w(TAG, "cancelPodsNotification failed", it) }
        try {
            Intent(MagicXm4Action.ACTION_PODS_DISCONNECTED).apply {
                if (::mDevice.isInitialized) putExtra("address", mDevice.address)
                `package` = BuildConfig.APPLICATION_ID
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect broadcast failed", e)
        }
    }

    // ── 控制命令 ────────────────────────────────────────────────
    // ── 原道协议独有、欢律侧未实现的功能 ─────────────────────────
    // 这些入口仍被上层 hook 调用，但发旧 0x4E 帧对 XM4 毫无意义（只会当噪声丢掉），
    // 因此明确做成 no-op 并打日志，保持「不撒谎」：不广播假状态。
    private fun unsupported(feature: String) {
        Log.i(TAG, "$feature 未在欢律协议中实现，忽略（不影响降噪/电量）")
    }

    fun setGameMode(enabled: Boolean) = unsupported("游戏模式")
    fun setLowLatency(enabled: Boolean) = unsupported("低延迟")
    fun setDualConn(enabled: Boolean) = unsupported("双设备连接")
    fun setEq(mode: EqMode) = unsupported("EQ 切换")
    fun setWindSuppression(enabled: Boolean) = unsupported("抗风噪")
    fun setInEarDetection(enabled: Boolean) = unsupported("入耳检测")
    /**
     * 按需重建 SPP：蓝牙栈重启后，A2DP 会回连但 SPP 不一定建立，
     * 此时任何命令都会静默失败。所有命令入口先调用它。
     */
    private fun ensureLink() {
        if (socket != null) return
        if (!::mDevice.isInitialized) return
        Log.i(TAG, "socket 为空 → 重建 SPP 链路")
        runCatching { writeGlobal("magicxm4_diag_link", "rebuild requested") }
        val ctx = mContext ?: return
        thread(name = "magicxm4-rfcomm-rebuild") {
            for (attempt in 1..3) {
                if (socket != null) break
                // 关键：safeRfcommConnect 的循环条件要求 isConnected=true，
                // 直接调用会「一次都不试就返回」→ 先清状态再走完整的 connectPod 流程。
                runCatching { safeDisconnect(mDevice) }
                runCatching { connectPod(ctx, mDevice, mPrefs) }
                    .onFailure { Log.w(TAG, "重建链路失败 attempt=$attempt", it) }
                runCatching { Thread.sleep(3000) }
            }
            runCatching {
                writeGlobal(
                    "magicxm4_diag_link",
                    if (socket != null) "rebuild ok" else "rebuild failed"
                )
            }
        }
    }

    /** 跨进程写 Settings.Global（holder 进程权限足够）。 */
    private fun writeGlobal(key: String, value: String) {
        runCatching {
            val ctx = mContext ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            }.getOrNull() ?: return@runCatching
            android.provider.Settings.Global.putString(ctx.contentResolver, key, value)
        }
    }

    /** 功能开关：0x0403 [id, on] */
    fun setFeature(id: Int, on: Boolean) {
        // WH-1000XM4 没有荣耀侧那套「功能位」（佩戴检测/黄金听感/双设备/高音质）
        if (true) { unsupported("功能位 0x%02X".format(id)); return }
        ensureLink()
        if (id < 0) return
        sendPacketSafe(HemCommands.setFeature(id, on))
        writeGlobal("magicxm4_diag_featset", "id=$id on=$on socket=${socket != null}")
        Log.i(TAG, "欢律 SET_FEATURE id=0x%02X on=$on".format(id))
        // 本地先记，耳机回报 0x810D 后会覆盖
        runCatching {
            val cur = android.provider.Settings.Global.getString(
                (mContext ?: return@runCatching).contentResolver, "magicxm4_features"
            ).orEmpty()
            val map = cur.split(',').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
            map[id.toString()] = if (on) "1" else "0"
            writeGlobal("magicxm4_features", map.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
    }

    /**
     * 改一条按键绑定：读当前表 → 替换指定条目的 function 字节 → 整表写回 → 读回校验。
     * 只改 function，保留 deviceType/button/buttonAction。
     */
    fun setGestureBinding(dev: Int, btn: Int, act: Int, function: Int) {
        // XM4 的触控手势不可编程映射，索尼协议里没有等价命令
        if (true) { unsupported("手势绑定"); return }
        ensureLink()
        if (dev <= 0 || btn <= 0 || act <= 0) return
        val cur = runCatching {
            android.provider.Settings.Global.getString(
                (mContext ?: return@runCatching null)?.contentResolver, "magicxm4_gestures"
            )
        }.getOrNull()
        if (cur.isNullOrBlank()) {
            Log.w(TAG, "没有手势表，先查询")
            sendPacketSafe(HemCommands.queryGestures())
            return
        }
        val rows = cur.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 4) null else p.map { it.trim().toIntOrNull() ?: 0 }.toIntArray()
        }.toMutableList()
        val idx = rows.indexOfFirst { it[0] == dev && it[1] == btn && it[2] == act }
        if (idx < 0) {
            Log.w(TAG, "手势表里没有 dev=$dev btn=$btn act=$act")
            writeGlobal("magicxm4_diag_gestset", "notfound dev=$dev btn=$btn act=$act")
            return
        }
        rows[idx] = intArrayOf(dev, btn, act, function)
        sendPacketSafe(HemCommands.setGestures(rows))
        writeGlobal("magicxm4_diag_gestset", "dev=$dev btn=$btn act=$act fn=$function socket=${socket != null}")
        Log.i(TAG, "手势写回 dev=$dev btn=$btn act=$act fn=$function")
        scope.launch { delay(500); sendPacketSafe(HemCommands.queryGestures()) }
    }

    /** 贴合度检测：0x0405 [01/00] */
    fun setFitTest(on: Boolean) {
        // XM4 是头戴式，没有入耳贴合度检测
        if (true) { unsupported("贴合度检测"); return }
        ensureLink()
        sendPacketSafe(HemCommands.fitTest(on))
        writeGlobal("magicxm4_diag_fit", "on=$on socket=${socket != null}")
        if (on) writeGlobal("magicxm4_fit", "检测中…")
        Log.i(TAG, "欢律 FIT_TEST on=$on")
    }

    /** EQ 预设：0x0406 [id] */
    fun setEqPreset(id: Int) {
        // EQ 是二期功能（索尼 0x56/0x57/0x58 命令组），v1 不下发
        if (true) { unsupported("EQ 预设"); return }
        ensureLink()
        if (id < 0) return
        sendPacketSafe(HemCommands.setEqPreset(id))
        writeGlobal("magicxm4_diag_eqset", "id=$id socket=${socket != null}")
        writeGlobal("magicxm4_eq", id.toString())
        Log.i(TAG, "欢律 SET_EQ id=$id")
        // 写后回读：以耳机回报的编号为准
        scope.launch { delay(400); sendPacketSafe(HemCommands.queryEq()) }
    }

    /**
     * 自定义 EQ：选中 / 新建 / 保存 / 删除。
     * @param action 1 新建 / 2 保存并选中 / 3 删除
     * @param gainsCsv 逗号分隔的增益（-6..6），新建与保存时使用；为空则沿用已有预设
     */
    fun eqCustom(action: Int, id: Int, newName: String?, gainsCsv: String?) {
        if (true) { unsupported("自定义 EQ"); return }
        ensureLink()
        val list = if (eqCustomCache.isNotEmpty()) {
            eqCustomCache
        } else {
            val cur = runCatching {
                android.provider.Settings.Global.getString(
                    (mContext ?: return@runCatching null)?.contentResolver, "magicxm4_eq_custom"
                )
            }.getOrNull().orEmpty()
            HemEq.deserialize(cur)
        }

        val base = if (action == HemEq.ACTION_CREATE) {
            HemEq.newPreset(newName ?: "自定义")
        } else {
            list.firstOrNull { it.id == id }
        }
        if (base == null) {
            writeGlobal("magicxm4_diag_eq", "not found id=$id")
            return
        }

        // 用 UI 传来的增益覆盖（频段沿用预设自身的）
        val gains = gainsCsv?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        val preset = if (gains != null && gains.isNotEmpty()) {
            base.copy(
                gains = base.freqs.indices.map { gains.getOrElse(it) { 0 }.coerceIn(HemEq.GAIN_MIN, HemEq.GAIN_MAX) }
            )
        } else base

        sendPacketSafe(HemCommands.saveEq(action, preset))
        writeGlobal(
            "magicxm4_diag_eq",
            "action=$action id=${preset.id} name=${preset.name} gains=${preset.gains.joinToString(",")} socket=${socket != null}"
        )
        Log.i(TAG, "EQ 写入 action=$action id=${preset.id} gains=${preset.gains}")
        scope.launch { delay(600); sendPacketSafe(HemCommands.queryEqAll()) }
    }

    /** 提示音音量：0x0427 [level] */
    fun setAlertVolume(level: Int) {
        if (true) { unsupported("提示音音量"); return }
        ensureLink()
        sendPacketSafe(HemCommands.setAlertVolume(level))
        writeGlobal("magicxm4_diag_volset", "level=$level socket=${socket != null}")
        writeGlobal("magicxm4_alert_vol", level.coerceIn(1, 10).toString())
        Log.i(TAG, "欢律 SET_ALERT_VOLUME level=$level")
    }

    /** 欢律 0x0400：让耳机自己响铃（对应荣耀设备中心的「响铃查找」）。 */
    fun setFindBuds(on: Boolean) {
        // WH-1000XM4 不支持响铃查找，服务卡上该入口必须置灰（计划书 §功能取舍）
        if (true) { unsupported("响铃查找"); return }
        ensureLink()
        val ctx = mContext ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull()
        fun stamp(v: String) {
            runCatching {
                ctx?.let {
                    android.provider.Settings.Global.putString(
                        it.contentResolver, "magicxm4_diag_findtx", v.take(200)
                    )
                }
            }
        }
        stamp("entered on=$on socket=${socket != null}")
        runCatching {
            val payload = byteArrayOf(if (on) 0x01 else 0x00)
            val packet = HemWire.build(0x0400, HemCommands.nextSeq(), payload)
            sendPacketSafe(packet)
            Log.i(TAG, "欢律 FIND_BUDS on=$on 已下发")
            stamp("sent=${socket != null} hex=${HemWire.hex(packet)}")
        }.onFailure { stamp("fail: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 用户最后一次选择的档位（-1 表示没选过）。 */
    private fun rememberAnc(mode: Int) {
        runCatching {
            val ctx = mContext ?: return@runCatching
            android.provider.Settings.Global.putString(
                ctx.contentResolver, "magicxm4_anc_last", mode.toString()
            )
        }
    }

    /** 重连后恢复上次档位（只在用户选过的情况下）。 */
    fun restoreAncIfAny() {
        val ctx = mContext ?: return
        val last = runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, "magicxm4_anc_last")
        }.getOrNull()?.toIntOrNull() ?: return
        if (last <= 0) return
        Log.i(TAG, "恢复上次 ANC 档位=$last")
        setANCMode(last)
    }

    fun setANCMode(mode: Int) {
        Log.i(TAG, "★ setANCMode(mode=$mode) socketReady=${::mDevice.isInitialized}")
        // 诊断"自动切降噪"：抓调用栈（10s 限频）
        val nowS = System.currentTimeMillis()
        if (nowS - lastAncStackAt > 10000) {
            lastAncStackAt = nowS
            val st = Thread.currentThread().stackTrace
            val frames = st.drop(3).take(10)
                .filterNot { it.className.startsWith("io.github.yuxiximyxx.magicxm4") }
                .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
            Log.w(TAG, "  △ setANCMode 调用栈:\n    $frames")
        }
        if (mode == currentAnc) {
            // 原本直接 return → currentAnc 初值与目标相同时永远不发包（实测 mode=3 早退，无 SPP 写出）。
            // 改为记录但仍下发，确保 UI 点击总能真正到达耳机。
            Log.i(TAG, "mode=$mode 与 currentAnc=$currentAnc 相同，仍强制下发")
        }
        // 荣耀三档 → 索尼命令。自适应（7）在 XM4 上没有对应模式，明确拒绝而不是假装成功。
        writeGlobal(
            "magicxm4_diag_ancset",
            "mode=$mode layout=$sonyLayout ambient=$ambientLevel socket=${socket != null}"
        )
        when (mode) {
            1 -> sendSony { SonyCommands.setOff(it, layout = sonyLayout) }
            2 -> sendSony { SonyCommands.setAmbientSound(it, ambientLevel, layout = sonyLayout) }
            5 -> sendSony { SonyCommands.setNoiseCancelling(it, layout = sonyLayout) }
            else -> {
                Log.w(TAG, "未知 ANC status=$mode，忽略（不发帧）")
                writeGlobal("magicxm4_diag_ancset", "mode=$mode unsupported")
                return
            }
        }
        rememberAnc(mode)
        currentAnc = mode
        Log.i(TAG, "索尼 SET_NOISE mode=$mode（${HemAnc.nameOfStatus(mode)}）layout=$sonyLayout")
        changeUIAncStatus(currentAnc)
        // 写后回读：以耳机回报的值为准，避免「点击的值」冒充真实档位
        scope.launch { delay(400); sendSony { SonyCommands.queryNoiseState(it) } }
        // 跨进程记录耳机**真实** ANC 模式 —— 设备中心卡片按它显示当前档位。
        // 否则 controlcenter 侧只能注入固定 NOISE_CANCELLATION，用户会误以为
        // "一打开界面就被自动切到降噪"（其实耳机没动，只是 UI 显示了默认值）。
        // ★ mContext 可能尚未初始化（实测为 null → let 不进入、静默丢失），
        //   兜底走 currentApplication，保证任何时点调用都能写入。
        val recCtx = mContext ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull()
        recCtx?.let { ctx ->
            runCatching {
                android.provider.Settings.Global.putString(
                    ctx.contentResolver, "magicxm4_current_anc", mode.toString()
                )
                Log.i(TAG, "真实 ANC 模式已跨进程记录: $mode")
            }.onFailure { Log.w(TAG, "current_anc 写入失败: ${it.javaClass.simpleName}") }
        } ?: Log.w(TAG, "current_anc 未记录：context 不可用（mContext/app 均为 null）")
    }
}
