@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.yuxiximyxx.magicxm4

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuxiximyxx.magicxm4.ui.MagicXM4Theme
import io.github.yuxiximyxx.magicxm4.utils.MagicXm4Action
import kotlinx.coroutines.delay

/**
 * 耳机详情页 —— 系统设置风格（纯黑 + 卡片 + 顶部渐变模糊 + 圆形返回 + 大标题）。
 *
 * 三个页面共用一套壳：
 *   0 概览（电量 / 噪声控制 / 功能开关 / 设置入口）
 *   1 均衡器（预设列表）
 *   2 耳机设置（佩戴检测 / 查找耳机 / 提示音音量 / 固件版本 / 手势占位）
 */
class EarphoneDetailActivity : ComponentActivity() {

    /** 色板：跟随系统深浅色。深色对齐系统设置截图，浅色用 Honor 浅色取值。 */
    private object P {
        var bg = Color(0xFF000000); private set
        var card = Color(0xFF1C1C1E); private set
        var cardAlt = Color(0xFF2C2C2E); private set
        var line = Color(0xFF38383A); private set
        var textPrimary = Color(0xFFFFFFFF); private set
        var textSecondary = Color(0xFF8E8E93); private set
        var textTertiary = Color(0xFF636366); private set
        var accent = Color(0xFF0A84FF); private set
        var success = Color(0xFF32D74B); private set
        var danger = Color(0xFFFF453A); private set

        fun apply(dark: Boolean) {
            if (dark) {
                bg = Color(0xFF000000); card = Color(0xFF1C1C1E); cardAlt = Color(0xFF2C2C2E)
                line = Color(0xFF38383A); textPrimary = Color(0xFFFFFFFF)
                textSecondary = Color(0xFF8E8E93); textTertiary = Color(0xFF636366)
                accent = Color(0xFF0A84FF); success = Color(0xFF32D74B); danger = Color(0xFFFF453A)
            } else {
                bg = Color(0xFFF2F2F7); card = Color(0xFFFFFFFF); cardAlt = Color(0xFFE5E5EA)
                line = Color(0xFFD1D1D6); textPrimary = Color(0xFF000000)
                textSecondary = Color(0xFF6B7280); textTertiary = Color(0xFF9CA3AF)
                accent = Color(0xFF007DFF); success = Color(0xFF1E9E4A); danger = Color(0xFFD93025)
            }
        }
    }

    private val FEATURES = listOf(
        Triple(0x06, "低延迟模式", "与视频保持同步"),
        Triple(0x18, "高音质音频", "提高比特率与采样率"),
        Triple(0x1B, "3D 音效", "带头部追踪的空间音频"),
        Triple(0x04, "佩戴检测", "摘下耳机时自动暂停"),
        Triple(0x0B, "黄金听感", "听力增强，补偿高频细节"),
    )

    /**
     * EQ 预设 = (0x0406 的 id, 名称)。
     * 注意：models.json 里写的是 `mode`（26/28/29/34），但 `0x0406` 要的是 **slot**（0/1/2/7）——
     * QuickBuds 的 builtInPresets() 就是 `mode.getInt("slot") to name`。传 mode 会静默无效。
     */
    private val EQ_PRESETS = listOf(
        0 to "至臻原音",
        1 to "纯享人声",
        2 to "澎湃低音",
        7 to "活力动感",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = 0x00000000
            window.navigationBarColor = 0xFF000000.toInt()
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        setContent {
            MagicXM4Theme {
                Surface(modifier = Modifier.fillMaxSize(), color = P.bg) { Root() }
            }
        }
    }

    private fun g(key: String): String? = runCatching {
        Settings.Global.getString(contentResolver, key)
    }.getOrNull()

    private fun send(action: String, fill: Intent.() -> Unit = {}) {
        runCatching {
            sendBroadcast(Intent(action).apply {
                fill()
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    private fun openBt(mac: String) {
        val ok = runCatching {
            startActivity(
                Intent("com.hihonor.intent.action.DEVICE_PROFILES_SETTINGS").putExtra("DEVICE_ID", mac)
            )
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    @Composable
    private fun Root() {
        P.apply(isSystemInDarkTheme())
        // 子页面时拦截返回（含侧滑手势），先回概览而不是直接退出
        var raw by remember { mutableStateOf("") }
        var ancRaw by remember { mutableStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var mac by remember { mutableStateOf("") }
        var features by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
        var firmware by remember { mutableStateOf("--") }
        var alertVol by remember { mutableStateOf(5) }
        var eq by remember { mutableStateOf(-1) }
        var ringing by remember { mutableStateOf(false) }
        var gestRaw by remember { mutableStateOf("") }
        var fitRaw by remember { mutableStateOf("") }
        var fitSentAt by remember { mutableStateOf(0L) }
        var caps by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var devRaw by remember { mutableStateOf("") }
        var eqCustomRaw by remember { mutableStateOf("") }
        var battHistory by remember { mutableStateOf("") }
        var pnoise by remember { mutableStateOf("") }
        var editId by remember { mutableStateOf(-1) }
        var editName by remember { mutableStateOf("") }
        var editGains by remember { mutableStateOf(mutableListOf<Int>()) }
        var editFreqs by remember { mutableStateOf<List<Int>>(listOf(62, 250, 1000, 4000, 8000, 16000)) }
        var eqDeleteId by remember { mutableStateOf(-1) }
        var renameFor by remember { mutableStateOf(-1) }
        var renameText by remember { mutableStateOf("") }
        var showVol by remember { mutableStateOf(false) }
        var showGestPick by remember { mutableStateOf<IntArray?>(null) }
        var page by remember { mutableStateOf(0) }

        // 侧滑/返回键先回上级页面，概览页才退出
        BackHandler(enabled = page != 0) { page = 0 }

        LaunchedEffect(Unit) {
            while (true) {
                raw = g("magicxm4_battery") ?: ""
                // 降噪高亮用「荣耀三档 status」（1 关闭 / 2 通透 / 5 降噪），
                // 不再用欢律的位掩码 —— 索尼协议的原始模式另有 magicxm4_anc_raw 记录。
                ancRaw = g("magicxm4_current_anc")?.toIntOrNull() ?: 0
                connected = g("magicxm4_connected") == "1"
                mac = g("magicxm4_mac") ?: ""
                features = parseFeatures(g("magicxm4_features"))
                firmware = g("magicxm4_firmware") ?: "--"
                alertVol = g("magicxm4_alert_vol")?.toIntOrNull() ?: 5
                eq = g("magicxm4_eq")?.toIntOrNull() ?: -1
                gestRaw = g("magicxm4_gestures") ?: ""
                fitRaw = g("magicxm4_fit") ?: ""
                caps = g("magicxm4_caps")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                    ?: emptySet()
                // 贴合度：15 秒没结果就提示重试，避免永远停在「检测中…」
                if (fitRaw == "检测中…" && fitSentAt > 0 &&
                    System.currentTimeMillis() - fitSentAt > 15_000
                ) {
                    fitRaw = "未收到结果，点击重试"
                }
                devRaw = g("magicxm4_devices") ?: ""
                eqCustomRaw = g("magicxm4_eq_custom") ?: ""
                battHistory = g("magicxm4_batt_history") ?: ""
                pnoise = g("magicxm4_pnoise") ?: ""
                delay(1000)
            }
        }

        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        Box(modifier = Modifier.fillMaxSize()) {
            // —— 可滚动内容 ——
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = statusBar + 56.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // 标题只保留在顶部栏（与系统设置一致），这里只放连接态
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(6.dp)
                                .background(if (connected) P.success else P.textTertiary, CircleShape),
                        )
                        Text(
                            if (connected) "已连接 · $mac" else "未连接 · $mac",
                            fontSize = 12.sp,
                            color = P.textSecondary,
                        )
                    }

                    when (page) {
                        0 -> OverviewPage(
                            battHistory = battHistory,
                            raw = raw, ancRaw = ancRaw, connected = connected,
                            features = features, eq = eq, alertVol = alertVol, firmware = firmware,
                            onNavigate = { page = it },
                            onOpenBt = { openBt(mac) },
                            onEq = { id -> send(MagicXm4Action.ACTION_EQ_PRESET) { putExtra("id", id) }; eq = id },
                            onFeature = { id, value ->
                                send(MagicXm4Action.ACTION_FEATURE_SET) {
                                    putExtra("id", id); putExtra("on", value != 1)
                                }
                                features = features.toMutableMap().apply { put(id, if (value == 1) 0 else 1) }
                            },
                            onFind = {
                                ringing = !ringing
                                send(MagicXm4Action.ACTION_FIND_BUDS) { putExtra("on", ringing) }
                            },
                            onVolume = { showVol = true },
                        )
                        1 -> EqPage(
                            eq = eq,
                            customRaw = eqCustomRaw,
                            onSelect = { id ->
                                send(MagicXm4Action.ACTION_EQ_PRESET) { putExtra("id", id) }
                                eq = id
                            },
                            onSelectCustom = { id ->
                                val c = parseCustomEq(eqCustomRaw).firstOrNull { it.id == id }
                                editId = id
                                editName = c?.name ?: "自定义"
                                editGains = (c?.gains ?: listOf(0, 0, 0, 0, 0, 0)).toMutableList()
                                editFreqs = c?.freqs ?: listOf(62, 250, 1000, 4000, 8000, 16000)
                                page = 5
                            },
                            onLongPress = { eqDeleteId = it },
                            onCreateCustom = {
                                editId = -1
                                editName = "自定义 ${System.currentTimeMillis() % 1000}"
                                editGains = mutableListOf(0, 0, 0, 0, 0, 0)
                                editFreqs = listOf(62, 250, 1000, 4000, 8000, 16000)
                                page = 5
                            },
                        )
                        3 -> GesturePage(gestRaw = gestRaw, onPick = { showGestPick = it })
                        6 -> AboutPage(
                            mac = mac, firmware = firmware, caps = caps,
                        )
                        7 -> BatteryHistoryPage(history = battHistory)
                        5 -> EqEditPage(
                            name = editName,
                            freqs = editFreqs,
                            gains = editGains,
                            isNew = editId < 0,
                            onGain = { i, v -> editGains = editGains.toMutableList().also { it[i] = v } },
                            onSave = {
                                send(MagicXm4Action.ACTION_EQ_CUSTOM) {
                                    putExtra("action", if (editId < 0) 1 else 2)
                                    if (editId >= 0) putExtra("id", editId)
                                    putExtra("name", editName)
                                    putExtra("gains", editGains.joinToString(","))
                                }
                                page = 1
                            },
                            onDelete = {
                                if (editId >= 0) {
                                    send(MagicXm4Action.ACTION_EQ_CUSTOM) {
                                        putExtra("action", 3)
                                        putExtra("id", editId)
                                    }
                                }
                                page = 1
                            },
                            onRename = {
                                renameFor = if (editId < 0) -2 else editId
                                renameText = editName
                            },
                        )
                        4 -> DevicesPage(
                            devRaw = devRaw,
                            connected = connected,
                            features = features,
                            onToggleDual = {
                                val v = features[0x11]
                                send(MagicXm4Action.ACTION_FEATURE_SET) {
                                    putExtra("id", 0x11)
                                    putExtra("on", v != 1)
                                }
                                features = features.toMutableMap().apply { put(0x11, if (v == 1) 0 else 1) }
                            },
                            onAddDevice = { openBt(mac) },
                            onRefresh = { send(MagicXm4Action.ACTION_QUERY_DEVICES) },
                        )
                        else -> SettingsPage(
                            onNavigate = { page = it },
                            connected = connected, features = features, firmware = firmware,
                            alertVol = alertVol, ringing = ringing,
                            onFeature = { id, value ->
                                send(MagicXm4Action.ACTION_FEATURE_SET) {
                                    putExtra("id", id); putExtra("on", value != 1)
                                }
                                features = features.toMutableMap().apply { put(id, if (value == 1) 0 else 1) }
                            },
                            onFind = {
                                ringing = !ringing
                                send(MagicXm4Action.ACTION_FIND_BUDS) { putExtra("on", ringing) }
                            },
                            onVolume = { showVol = true },
                            onOpenBt = { openBt(mac) },
                            fitRaw = fitRaw,
                            fitSupported = caps.isEmpty() || 0x0405 in caps,
                            pnoise = pnoise,
                            onFit = {
                                fitSentAt = System.currentTimeMillis()
                                fitRaw = "检测中…"
                                send(MagicXm4Action.ACTION_FIT_TEST) { putExtra("on", true) }
                            },
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }

            // —— 顶部渐变模糊遮罩（内容从标题下滚过时渐隐）——
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBar + 56.dp)
                    .background(
                        Brush.verticalGradient(
                            0.0f to P.bg,
                            0.55f to P.bg.copy(alpha = 0.92f),
                            0.85f to P.bg.copy(alpha = 0.55f),
                            1.0f to Color.Transparent,
                        )
                    ),
            )

            // —— 顶部栏：圆形返回 + 标题 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBar + 8.dp, start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(P.cardAlt, CircleShape)
                        .clickable { if (page == 0) finish() else page = 0 },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    when (page) {
                        0 -> getString(R.string.detail_device_name)
                        1 -> "均衡器"
                        3 -> "耳机手势"
                        4 -> "设备双连"
                        5 -> if (editId < 0) "新建均衡预设" else "编辑均衡预设"
                        6 -> "关于耳机"
                        7 -> "电量历史"
                        else -> "耳机设置"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = P.textPrimary,
                )
            }
        }

        val pick = showGestPick
        if (pick != null) {
            val onCall = pick[1] == 0x06
            val options = if (onCall) FUNCTIONS_ON_CALL else FUNCTIONS
            AlertDialog(
                onDismissRequest = { showGestPick = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = P.card,
                title = {
                    Text(
                        if (onCall) "通话中 · 设为功能" else "主按键 · 设为功能",
                        color = P.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    )
                },
                text = {
                    Column {
                        for ((fn, name) in options) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        send(MagicXm4Action.ACTION_GESTURE_SET) {
                                            putExtra("dev", pick[0])
                                            putExtra("btn", pick[1])
                                            putExtra("act", pick[2])
                                            putExtra("fn", fn)
                                        }
                                        showGestPick = null
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, fontSize = 15.sp, color = P.textPrimary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showGestPick = null }) { Text("取消", color = P.accent) }
                },
            )
        }

        if (eqDeleteId >= 0) {
            val target = parseCustomEq(eqCustomRaw).firstOrNull { it.id == eqDeleteId }
            AlertDialog(
                onDismissRequest = { eqDeleteId = -1 },
                shape = RoundedCornerShape(20.dp),
                containerColor = P.card,
                title = { Text("删除预设", color = P.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium) },
                text = {
                    Text(
                        "删除「${target?.name ?: eqDeleteId}」？此操作不可恢复。",
                        color = P.textSecondary, fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        send(MagicXm4Action.ACTION_EQ_CUSTOM) {
                            putExtra("action", 3)
                            putExtra("id", eqDeleteId)
                        }
                        eqDeleteId = -1
                    }) { Text("删除", color = P.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { eqDeleteId = -1 }) { Text("取消", color = P.textSecondary) }
                },
            )
        }

        if (renameFor != -1) {
            AlertDialog(
                onDismissRequest = { renameFor = -1 },
                shape = RoundedCornerShape(20.dp),
                containerColor = P.card,
                title = { Text("预设名称", color = P.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { if (it.length <= 20) renameText = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = P.cardAlt,
                            unfocusedContainerColor = P.cardAlt,
                            focusedTextColor = P.textPrimary,
                            unfocusedTextColor = P.textPrimary,
                            cursorColor = P.accent,
                            focusedIndicatorColor = P.accent,
                            unfocusedIndicatorColor = P.line,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        editName = renameText.ifBlank { editName }
                        if (renameFor >= 0) {
                            // 直接保存改名（沿用当前增益）
                            send(MagicXm4Action.ACTION_EQ_CUSTOM) {
                                putExtra("action", 2)
                                putExtra("id", renameFor)
                                putExtra("name", editName)
                                putExtra("gains", editGains.joinToString(","))
                            }
                        }
                        renameFor = -1
                    }) { Text("保存", color = P.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { renameFor = -1 }) { Text("取消", color = P.textSecondary) }
                },
            )
        }

        if (showVol) {
            AlertDialog(
                onDismissRequest = { showVol = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = P.card,
                title = { Text("提示音音量", color = P.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium) },
                text = {
                    Column {
                        Text("$alertVol / 10", fontSize = 18.sp, color = P.textPrimary)
                        Slider(
                            value = alertVol.toFloat(),
                            onValueChange = { alertVol = it.toInt().coerceIn(1, 10) },
                            valueRange = 1f..10f,
                            // 不用 steps：荣耀的滑杆是连续轨道 + 白色圆点，没有刻度点
                            steps = 0,
                            thumb = {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(Color.White, CircleShape)
                                )
                            },
                            track = { sliderState ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .background(P.cardAlt, CircleShape)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(sliderState.value / 10f)
                                            .height(4.dp)
                                            .background(P.accent, CircleShape)
                                    )
                                }
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        send(MagicXm4Action.ACTION_ALERT_VOLUME) { putExtra("level", alertVol) }
                        showVol = false
                    }) { Text("保存", color = P.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { showVol = false }) { Text("取消", color = P.textSecondary) }
                },
            )
        }
    }

    // ── 页面 0：概览 ────────────────────────────────────────

    @Composable
    private fun OverviewPage(
        battHistory: String,
        raw: String,
        ancRaw: Int,
        connected: Boolean,
        features: Map<Int, Int>,
        eq: Int,
        alertVol: Int,
        firmware: String,
        onNavigate: (Int) -> Unit,
        onOpenBt: () -> Unit,
        onEq: (Int) -> Unit,
        onFeature: (Int, Int) -> Unit,
        onFind: () -> Unit,
        onVolume: () -> Unit,
    ) {
        val bat = parseBattery(raw)
        val ancBit = ancBitOf(ancRaw)

        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            // WH-1000XM4 只有一颗总电量（无左右耳、无充电仓），所以只画一个环。
            Card20 {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BatteryRing("电量", bat[0])
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("噪声控制")
                Card20 {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModePill("关闭", ancBit == 0, connected, Modifier.weight(1f)) {
                            send(MagicXm4Action.ACTION_ANC_SELECT) { putExtra("status", 1) }
                        }
                        ModePill("降噪", ancBit == 5, connected, Modifier.weight(1f)) {
                            send(MagicXm4Action.ACTION_ANC_SELECT) { putExtra("status", 5) }
                        }
                        ModePill("通透", ancBit == 2, connected, Modifier.weight(1f)) {
                            send(MagicXm4Action.ACTION_ANC_SELECT) { putExtra("status", 2) }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("设置")
                Card20(padding = 0.dp) {
                    Column {
                        NavRow("电量历史", if (battHistory.isBlank()) "暂无记录" else "最近 ${battHistory.split(';').size} 条") { onNavigate(7) }
                        Line()
                        NavRow("关于耳机", "型号、固件、协议") { onNavigate(6) }
                    }
                }
            }

            Text(
                "降噪档位以耳机回报为准；触摸耳机切档后这里会自动同步。",
                fontSize = 12.sp,
                color = P.textTertiary,
            )
        }
    }

    // ── 页面 1：均衡器 ──────────────────────────────────────

    @Composable
    private fun EqPage(
        eq: Int,
        customRaw: String,
        onSelect: (Int) -> Unit,
        onSelectCustom: (Int) -> Unit,
        onCreateCustom: () -> Unit,
        onLongPress: (Int) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("推荐")
                Card20(padding = 0.dp) {
                    Column {
                        for ((idx, p) in EQ_PRESETS.withIndex()) {
                            val (id, name) = p
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable { onSelect(id) }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    name,
                                    fontSize = 15.sp,
                                    color = if (id == eq) P.accent else P.textPrimary,
                                )
                                if (id == eq) Text("✓", color = P.accent, fontSize = 16.sp)
                            }
                            if (idx != EQ_PRESETS.lastIndex) Line()
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("自定义")
                Card20(padding = 0.dp) {
                    Column {
                        val customs = parseCustomEq(customRaw)
                        customs.forEachIndexed { i, c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .combinedClickable(
                                        onClick = { onSelectCustom(c.id) },
                                        onLongClick = { onLongPress(c.id) },
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    c.name.ifBlank { "预设 ${c.id}" },
                                    fontSize = 15.sp,
                                    color = if (c.selected) P.accent else P.textPrimary,
                                )
                                if (c.selected) Text("✓", color = P.accent, fontSize = 16.sp)
                            }
                            if (i != customs.lastIndex) Line()
                        }
                        if (customs.isNotEmpty()) Line()
                        NavRow("新建自定义预设", "在耳机内创建一个平坦曲线") { onCreateCustom() }
                    }
                }
            }
            Text(
                "自定义预设上限由耳机固件决定（当前机型 3 个）；点预设可编辑增益或删除，保存走 0x0418。",
                fontSize = 12.sp,
                color = P.textTertiary,
            )
        }
    }

    // ── 页面 3：耳机手势 ────────────────────────────────────

    private val ACTIONS = mapOf(
        1 to "单击", 2 to "双击", 3 to "三击", 4 to "长按", 5 to "滑动", 6 to "超长按",
    )

    /** 通话中按键可绑的功能（QuickBuds 实测：0x1D 接听/挂断、0x1C 拒接）。 */
    private val FUNCTIONS_ON_CALL = listOf(
        0x00 to "无",
        0x1D to "接听 / 挂断",
        0x1C to "拒接",
    )

    private val FUNCTIONS = listOf(
        0x00 to "无",
        0x01 to "播放 / 暂停",
        0x05 to "上一首",
        0x06 to "下一首",
        0x03 to "语音助手",
        0x0B to "音量 +",
        0x0C to "音量 -",
        0x0A to "切换歌曲",
        0x0D to "切换设备",
        0x11 to "游戏模式",
        0x07 to "音量（上滑增 / 下滑减）",
        0x08 to "降噪循环",
    )

    @Composable
    private fun GesturePage(gestRaw: String, onPick: (IntArray) -> Unit) {
        val all = parseGestures(gestRaw)
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (all.isEmpty()) {
                Text("正在读取按键绑定…", fontSize = 14.sp, color = P.textSecondary)
            } else {
                // 左耳(dev=1) / 右耳(dev=2)，组内再分主按键(btn=1)与通话中(btn=6)
                for (dev in listOf(1, 2)) {
                    val devRows = all.filter { it[0] == dev }
                    if (devRows.isEmpty()) continue
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(if (dev == 1) "左耳" else "右耳")
                        Card20(padding = 0.dp) {
                            Column {
                                var first = true
                                for (btn in listOf(1, 6)) {
                                    val rows = devRows.filter { it[1] == btn }.sortedBy { it[2] }
                                    if (rows.isEmpty()) continue
                                    for (row in rows) {
                                        if (!first) Line()
                                        first = false
                                        val act = ACTIONS[row[2]] ?: "动作${row[2]}"
                                        val fn = funcName(btn, row[3])
                                        val prefix = if (btn == 6) "通话中 · " else ""
                                        NavRow(prefix + act, fn) { onPick(row) }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "主按键=听歌时的点按；通话中=打电话时的按键。写入按「耳别+按键组+动作」精确定位，" +
                        "整表回写后读回校验。功能 25 未在公开协议中定义，故不提供修改。",
                    fontSize = 12.sp,
                    color = P.textTertiary,
                )
            }
        }
    }

    private fun funcName(btn: Int, fn: Int): String {
        val list = if (btn == 0x06) FUNCTIONS_ON_CALL else FUNCTIONS
        return list.firstOrNull { it.first == fn }?.second ?: "未识别($fn)"
    }

    private fun parseGestures(raw: String): List<IntArray> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 4) null else p.map { it.trim().toIntOrNull() ?: 0 }.toIntArray()
        }
    }

    // ── 页面 6：关于耳机 ────────────────────────────────────

    @Composable
    private fun AboutPage(
        mac: String,
        firmware: String,
        caps: Set<Int>,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("设备")
                Card20(padding = 0.dp) {
                    Column {
                        InfoRow("型号", "Sony WH-1000XM4")
                        Line()
                        InfoRow("蓝牙地址", mac.ifBlank { "--" })
                        Line()
                        InfoRow("固件版本", firmware)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("协议")
                Card20(padding = 0.dp) {
                    Column {
                        InfoRow("协议", "索尼私有协议（SPP）")
                        Line()
                        InfoRow("固件支持命令", "${caps.size} 项")
                        Line()
                        InfoRow("连接方式", "SPP · 96CC203E-5068-46ad-B32D-E316F5E069BA")
                    }
                }
            }
            // 原「模块设置」NavRow 跳 MainActivity——MainActivity 自 3.0.12 起
            //   改成直达本页的跳板、会绕回本页，故一并去掉（同 MagicPods 的处理）。
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("开源许可")
                Card20 {
                    Text(
                        "本模块基于 MagicFree4（GPL-3.0）修改，索尼协议实现参考 SonyHeadphonesClient（GPL-3.0）。",
                        fontSize = 12.sp, color = P.textSecondary,
                    )
                }
            }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 15.sp, color = P.textPrimary)
            Text(value, fontSize = 14.sp, color = P.textSecondary)
        }
    }

    // ── 页面 7：电量历史 ────────────────────────────────────

    @Composable
    private fun BatteryHistoryPage(history: String) {
        val rows = history.split(';').filter { it.isNotBlank() }.reversed()
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (rows.isEmpty()) {
                Text("暂无电量记录（连接耳机后开始记录）", fontSize = 14.sp, color = P.textSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("最近 ${rows.size} 条")
                    Card20(padding = 0.dp) {
                        Column {
                            rows.forEachIndexed { i, row ->
                                val p = row.split(',')
                                if (p.size == 4) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(p[0], fontSize = 13.sp, color = P.textSecondary,
                                            modifier = Modifier.width(56.dp))
                                        Text("左 ${p[1]}%   右 ${p[2]}%   仓 ${p[3]}%",
                                            fontSize = 14.sp, color = P.textPrimary)
                                    }
                                    if (i != rows.lastIndex) Line()
                                }
                            }
                        }
                    }
                }
                Text("最多保留 40 条，同一分钟内相同电量不重复记录。",
                    fontSize = 12.sp, color = P.textTertiary)
            }
        }
    }

    // ── 页面 5：均衡预设编辑 ────────────────────────────────

    @Composable
    private fun EqEditPage(
        name: String,
        freqs: List<Int>,
        gains: List<Int>,
        isNew: Boolean,
        onGain: (Int, Int) -> Unit,
        onSave: () -> Unit,
        onDelete: () -> Unit,
        onRename: () -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Card20 {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = P.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isNew) "保存后会在耳机里创建这个预设" else "调整各频段增益后点保存",
                    fontSize = 12.sp, color = P.textSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("频段增益（-6 ~ +6 dB）")
                Card20(padding = 0.dp) {
                    Column {
                        freqs.forEachIndexed { i, f ->
                            val g = gains.getOrElse(i) { 0 }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (f >= 1000) "${f / 1000}kHz" else "${f}Hz",
                                    fontSize = 13.sp, color = P.textSecondary,
                                    modifier = Modifier.width(56.dp),
                                )
                                Slider(
                                    value = g.toFloat(),
                                    onValueChange = { onGain(i, it.toInt().coerceIn(-6, 6)) },
                                    valueRange = -6f..6f,
                                    steps = 11,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "%+d".format(g),
                                    fontSize = 13.sp, color = P.textPrimary,
                                    modifier = Modifier.width(40.dp),
                                )
                            }
                            if (i != freqs.lastIndex) Line()
                        }
                    }
                }
            }

            Card20(padding = 0.dp) {
                NavRow("重命名", name) { onRename() }
                Line()
                NavRow("保存", "") { onSave() }
                if (!isNew) {
                    Line()
                    NavRow("删除这个预设", "删除后不可恢复") { onDelete() }
                }
            }
        }
    }

    // ── 页面 4：设备双连 ────────────────────────────────────

    @Composable
    private fun DevicesPage(
        devRaw: String,
        connected: Boolean,
        features: Map<Int, Int>,
        onToggleDual: () -> Unit,
        onAddDevice: () -> Unit,
        onRefresh: () -> Unit,
    ) {
        val devs = parseDevices(devRaw)
        val dualOn = features[0x11] == 1
        // 本机蓝牙名（用于把手机那行标成「本机」）
        // 普通 App 往往拿不到本机蓝牙名（需要 BLUETOOTH_CONNECT/系统权限），多路兜底
        val localBtName = runCatching {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name
        }.getOrNull() ?: runCatching {
            android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
        }.getOrNull()

        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Card20(padding = 0.dp) {
                SwitchRow(
                    title = "设备双连",
                    subtitle = "开启后可与两台设备同时连接，实现便捷切换",
                    checked = dualOn,
                    enabled = connected,
                    onClick = { onToggleDual() },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("已连接设备")
                Card20(padding = 0.dp) {
                    Column {
                        if (devs.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("正在读取设备列表…", fontSize = 15.sp, color = P.textSecondary)
                            }
                        } else {
                            devs.forEachIndexed { i, d ->
                                val isLocal = !localBtName.isNullOrBlank() && d.second == localBtName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            d.second.ifBlank { d.first },
                                            fontSize = 15.sp,
                                            color = if (isLocal) P.accent else P.textPrimary,
                                        )
                                        Text(
                                            when {
                                                d.third && isLocal -> "已连接（本机）"
                                                d.third -> "已连接"
                                                else -> "未连接"
                                            },
                                            fontSize = 12.sp,
                                            color = if (d.third) P.success else P.textSecondary,
                                        )
                                    }
                                }
                                if (i != devs.lastIndex || true) Line()
                            }
                        }
                        NavRow("添加新设备", "") { onAddDevice() }
                        Line()
                        NavRow("刷新列表", "") { onRefresh() }
                    }
                }
            }
            Text(
                "列表来自耳机自身（0x8112）；「设备双连」开关走 0x0403 功能位 0x11。",
                fontSize = 12.sp,
                color = P.textTertiary,
            )
        }
    }

    private data class EqCustom(
        val id: Int,
        val name: String,
        val selected: Boolean,
        val freqs: List<Int> = listOf(62, 250, 1000, 4000, 8000, 16000),
        val gains: List<Int> = listOf(0, 0, 0, 0, 0, 0),
    )

    private fun parseCustomEq(raw: String): List<EqCustom> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val p = row.split('|')
            if (p.size < 4) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            val freqs = ArrayList<Int>()
            val gains = ArrayList<Int>()
            p[3].split(',').forEach { band ->
                val kv = band.split(':')
                if (kv.size == 2) {
                    kv[0].toIntOrNull()?.let { freqs.add(it) }
                    kv[1].toIntOrNull()?.let { gains.add(it) }
                }
            }
            EqCustom(id, p[1], p[2] == "1", freqs, gains)
        }
    }

    private fun parseDevices(raw: String): List<Triple<String, String, Boolean>> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            val p = row.split('|')
            if (p.size != 3) null else Triple(p[0], p[1], p[2] == "1")
        }
    }

    // ── 页面 2：耳机设置 ────────────────────────────────────

    @Composable
    private fun SettingsPage(
        onNavigate: (Int) -> Unit,
        connected: Boolean,
        features: Map<Int, Int>,
        firmware: String,
        alertVol: Int,
        ringing: Boolean,
        onFeature: (Int, Int) -> Unit,
        onFind: () -> Unit,
        onVolume: () -> Unit,
        onOpenBt: () -> Unit,
        fitRaw: String,
        fitSupported: Boolean,
        pnoise: String,
        onFit: () -> Unit,
    ) {
        val wear = features[0x04]
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("功能")
                Card20(padding = 0.dp) {
                    Column {
                        SwitchRow("佩戴检测", "摘下耳机时自动暂停", wear == 1, connected && wear != null) {
                            if (wear != null) onFeature(0x04, wear)
                        }
                        Line()
                        NavRow("耳机手势", "设置点按和滑动") { onNavigate(3) }
                        Line()
                        NavRow("设备双连", "开关与已连接设备") { onNavigate(4) }
                        Line()
                        NavRow(
                            "个性化降噪",
                            when (pnoise) {
                                "1" -> "已有个性化降噪结果"
                                "0" -> "暂无测试结果（需在欢律 App 完成测试）"
                                else -> "正在检测…"
                            },
                        ) { }
                        Line()
                        NavRow(
                            "贴合度检测",
                            when {
                                !fitSupported -> "本固件不支持该功能"
                                fitRaw.isEmpty() -> "检测左右耳佩戴贴合度"
                                else -> fitRaw
                            },
                        ) { if (fitSupported) onFit() }
                        Line()
                        NavRow(
                            "查找耳机",
                            if (ringing) "正在响铃，点击停止" else "让两只耳机响铃",
                        ) { onFind() }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("声音")
                Card20(padding = 0.dp) {
                    Column {
                        NavRow("提示音音量", "$alertVol / 10") { onVolume() }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("关于耳机")
                Card20(padding = 0.dp) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("固件版本", fontSize = 15.sp, color = P.textPrimary)
                            Text(firmware, fontSize = 14.sp, color = P.textSecondary)
                        }
                        Line()
                        NavRow("蓝牙连接设置", "") { onOpenBt() }
                    }
                }
            }
        }
    }

    // ── 组件 ───────────────────────────────────────────────

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, fontSize = 13.sp, color = P.textSecondary, modifier = Modifier.padding(start = 4.dp))
    }

    @Composable
    private fun Card20(padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(P.card, RoundedCornerShape(20.dp))
                .padding(padding),
            content = { content() },
        )
    }

    @Composable
    private fun Line() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
                .height(1.dp)
                .background(P.line),
        )
    }

    @Composable
    private fun BatteryRing(label: String, level: Int) {
        val pct = level.coerceIn(0, 100)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(72.dp)) {
                    val stroke = 6.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = P.cardAlt, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (level >= 0) {
                        drawArc(
                            color = if (pct <= 20) P.danger else P.success,
                            startAngle = -90f, sweepAngle = 360f * pct / 100f, useCenter = false,
                            topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    if (level >= 0) "$pct%" else "--",
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.textPrimary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(label, fontSize = 12.sp, color = P.textSecondary)
        }
    }

    @Composable
    private fun ModePill(
        label: String,
        active: Boolean,
        enabled: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                .height(56.dp)
                .background(
                    when {
                        !enabled -> P.cardAlt.copy(alpha = 0.4f)
                        active -> P.accent
                        else -> P.cardAlt
                    },
                    RoundedCornerShape(14.dp),
                )
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    !enabled -> P.textTertiary
                    active -> Color.White
                    else -> P.textPrimary
                },
            )
        }
    }

    @Composable
    private fun SwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = if (enabled) P.textPrimary else P.textTertiary)
                Text(subtitle, fontSize = 12.sp, color = P.textSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = { onClick() },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = P.accent,
                    uncheckedThumbColor = P.textSecondary,
                    uncheckedTrackColor = P.cardAlt,
                ),
            )
        }
    }

    @Composable
    private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = P.textPrimary)
                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = P.textSecondary)
            }
            Text("›", fontSize = 20.sp, color = P.textTertiary)
        }
    }

    // ── 数据 ───────────────────────────────────────────────

    private fun eqName(id: Int): String = EQ_PRESETS.firstOrNull { it.first == id }?.second ?: "未设置"

    /**
     * 荣耀三档 status → 概览页高亮用的档位标识。
     *   1 关闭 → 0、2 通透 → 2、5 降噪 → 5；6（风噪降低，被归并到降噪）也走高亮 5。
     */
    private fun ancBitOf(raw: Int): Int = when (raw) {
        1 -> 0
        2 -> 2
        5, 6 -> 5
        else -> 0
    }

    private fun parseFeatures(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',').mapNotNull { part ->
            val k = part.substringBefore('=').trim().toIntOrNull() ?: return@mapNotNull null
            val v = part.substringAfter('=', "").trim().toIntOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()
    }

    private fun parseBattery(raw: String): IntArray {
        val out = intArrayOf(-1, -1, -1)
        if (raw.isBlank()) return out
        raw.split(',').forEach { part ->
            val p = part.trim()
            if (p.length < 2) return@forEach
            val v = p.substring(1).toIntOrNull() ?: return@forEach
            when (p.first()) {
                'L' -> out[0] = v
                'R' -> out[1] = v
                'C' -> out[2] = v
            }
        }
        return out
    }
}
