package io.github.yuxiximyxx.magicxm4.ui

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuxiximyxx.magicxm4.diag.ModuleDiagnostics

/**
 * 底部导航栏 — Material3 标准风格
 */
@Composable
private fun M3BottomBar(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        for (i in 0..1) {
            val label = if (i == 0) "首页" else "关于"
            NavigationBarItem(
                selected = i == selected,
                onClick = { onSelect(i) },
                icon = {
                    Text(
                        if (i == 0) "⚡" else "ℹ️",
                        fontSize = 20.sp
                    )
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}

/**
 * 首页 — 电量 + 降噪档位 + 自检
 */
@Composable
private fun HomePage(context: Context) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "MagicXM4",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "让 WH-1000XM4 接入 MagicOS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))

        // ── 电量卡片 ──
        // WH-1000XM4 只有一颗总电量：优先读索尼协议解析出的单值，
        // 没有时再退回欢律遗留的双耳字符串（兼容旧数据，不显示假的左右耳）。
        val singlePct = remember { g(context, "magicxm4_battery_single")?.toIntOrNull() ?: -1 }
        val batteryRaw = remember { g(context, "magicxm4_battery") ?: "" }
        val leftPct = if (singlePct >= 0) singlePct else if (batteryRaw.contains("L")) {
            batteryRaw.substringAfter("L").substringBefore(",").toIntOrNull() ?: -1
        } else -1

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("耳机状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BatteryGauge("电量", leftPct)
                }
            }
        }

        // ── 默认降噪档位（三档：实验性降噪 / 深度降噪 / 普通降噪）──
        // XM4 只有三档语义（索尼没有「自适应降噪」），所以这里只给三档。
        val ancOptions = listOf("关闭", "降噪", "通透")
        val ancStatus = mapOf("关闭" to "1", "降噪" to "5", "通透" to "2")
        val currentAnc = remember { g(context, "magicxm4_last_anc") ?: "5" }
        var selectedAnc by remember(currentAnc) {
            mutableStateOf(ancOptions.find { ancStatus[it] == currentAnc } ?: "降噪")
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("默认降噪档位", style = MaterialTheme.typography.titleMedium)
                Text(
                    "连上耳机后自动恢复这一档",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ancOptions.forEach { opt ->
                    val isSel = opt == selectedAnc
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAnc = opt
                                ancStatus[opt]?.let { status -> writeDefaultAnc(context, status) }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                selectedAnc = opt
                                ancStatus[opt]?.let { status -> writeDefaultAnc(context, status) }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(opt, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // ── 索尼协议调试（真机 A/B 用）──
        // 这一版协议层没有真机验证，两个参考实现在「降噪设置载荷的下标 3/4」上冲突。
        // 把它做成 App 里可切换，就是为了**不用重编 APK** 就能在手机上试出哪一个对。
        var layoutSel by remember { mutableStateOf(g(context, "magicxm4_sony_layout") ?: "0") }
        var ambient by remember {
            mutableStateOf((g(context, "magicxm4_ambient_level") ?: "15").toIntOrNull() ?: 15)
        }
        val deviceType = remember { g(context, "magicxm4_device_type") ?: "Third_EarPhone" }
        val hnTypes = remember { g(context, "magicxm4_diag_hn_types") }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("索尼协议（真机 A/B）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "降噪点了没反应时，先切下面的载荷布局再试一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                listOf("0" to "风噪能力布局（默认）", "1" to "旧 dualSingle 布局").forEach { (value, label) ->
                    val isSel = layoutSel == value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                layoutSel = value
                                putGlobal(context, "magicxm4_sony_layout", value)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                layoutSel = value
                                putGlobal(context, "magicxm4_sony_layout", value)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("通透档环境声等级：$ambient / 20", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "索尼环境声有 1..20 档，荣耀「通透」没有档位概念，这里选一个默认值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            ambient = (ambient - 1).coerceAtLeast(1)
                            putGlobal(context, "magicxm4_ambient_level", ambient.toString())
                        }
                    ) { Text("−") }
                    Spacer(Modifier.width(16.dp))
                    Text("$ambient", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(
                        onClick = {
                            ambient = (ambient + 1).coerceAtMost(20)
                            putGlobal(context, "magicxm4_ambient_level", ambient.toString())
                        }
                    ) { Text("+") }
                }

                Spacer(Modifier.height(14.dp))
                Text("荣耀设备类型：$deviceType", style = MaterialTheme.typography.bodySmall)
                Text(
                    "设备类型决定设备中心的球与卡片形态。默认 Third_EarPhone 是已验证能渲染的那一个；" +
                        "想试头戴形态就 adb 改 magicxm4_device_type（候选名见下方枚举清单）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hnTypes.isNullOrBlank()) {
                    Text(
                        "尚未探测到类型枚举（需要模块在 controlcenter 进程里跑过一次）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "已探测到的类型枚举：$hnTypes",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── 模块自检卡片 ──
        val diagReport = remember { runCatching { ModuleDiagnostics.collect(context) }.getOrNull() }
        val diagChecks = remember(diagReport) { diagReport?.checks() ?: emptyList() }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("模块识别自检", style = MaterialTheme.typography.titleMedium)
                if (diagReport == null) {
                    Text("读取失败", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        if (diagReport.recognized) "✓ LSPosed 可识别" else "✗ 不可识别",
                        color = if (diagReport.recognized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    diagChecks.forEach { check ->
                        val mark = if (check.ok == true) "✔" else if (check.ok == false) "✘" else "•"
                        val c = if (check.ok == true) MaterialTheme.colorScheme.primary
                                else if (check.ok == false) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            "$mark ${check.label}${check.detail?.let { " — $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 电量圆环
 */
@Composable
private fun BatteryGauge(label: String, pct: Int) {
    val color = when {
        pct < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        pct <= 20 -> MaterialTheme.colorScheme.error
        pct <= 50 -> MaterialTheme.colorScheme.tertiary   // 低电量用 M3 tertiary（warning 语义）
        else -> MaterialTheme.colorScheme.primary
    }
    val display = if (pct < 0) "?" else "$pct%"
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val stroke = 4.dp.toPx()
                drawCircle(
                    color = trackColor,
                    radius = size.minDimension / 2 - stroke / 2,
                    style = Stroke(width = stroke)
                )
                if (pct >= 0) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = (pct / 100f) * 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        size = Size(size.width - stroke, size.height - stroke),
                        topLeft = Offset(stroke / 2, stroke / 2)
                    )
                }
            }
            Text(display, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 关于页面 — 统一使用 M3 主题色
 */
@Composable
private fun AboutPage() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "关于 MagicXM4",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("开源仓库", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "https://github.com/yingjfd/MagicXM4",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("引用项目", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BulletText("HyperOriG — 原始 HyperOS 适配版（核心 RFCOMM/电量/ANC 逻辑）")
                BulletText("LibXposed API — 现代 LSPosed 接口")
                BulletText("Material3 — UI 主题风格")
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("技术栈", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BulletText("Kotlin / Jetpack Compose")
                BulletText("LSPosed / LibXposed (API 102)")
                BulletText("SPP RFCOMM · MBB 协议桥")
                BulletText("荣耀 MagicOS 11 (Android 17)")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "MIT License",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BulletText(text: String) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 主入口
 */
@Composable
fun MagicXM4AppCompose() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = { M3BottomBar(selected = tab, onSelect = { tab = it }) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomePage(context)
                1 -> AboutPage()
            }
        }
    }
}

/**
 * 读跨进程 Settings.Global。UI 里读不到就返回 null，绝不因为一次读失败崩掉页面。
 */
private fun g(context: Context, key: String): String? =
    runCatching { android.provider.Settings.Global.getString(context.contentResolver, key) }.getOrNull()

/** 写跨进程 Settings.Global，失败静默（宿主进程可能没有写权限，那是另一层的事）。 */
private fun putGlobal(context: Context, key: String, value: String) {
    runCatching { android.provider.Settings.Global.putString(context.contentResolver, key, value) }
}

/**
 * 写「默认降噪档位」。
 *
 * 模块里有两个记忆键，语义不同但都该一起更新，否则会出现「设了默认档却不生效」：
 *  · `magicxm4_anc_last` —— RfcommController 每次用户切档时写，重连后 restoreAncIfAny 读它；
 *  · `magicxm4_last_anc` —— 蓝牙设置页 hook 在面板就绪时读它兜底高亮。
 * 历史上 UI 只写后者、控制器只写前者，两边永远各自为政。
 */
private fun writeDefaultAnc(context: Context, status: String) {
    putGlobal(context, "magicxm4_anc_last", status)
    putGlobal(context, "magicxm4_last_anc", status)
}
