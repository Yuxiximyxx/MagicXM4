package io.github.yuxiximyxx.magicxm4.utils

/**
 * 模块内广播 Action 常量。
 * 从 HyperOriG 完整移植，保留原始 Action 名称以确保广播兼容。
 */
object MagicXm4Action {
    const val ACTION_PODS_CONNECTED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_CONNECTED"
    const val ACTION_PODS_DISCONNECTED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_DISCONNECTED"
    const val ACTION_PODS_BATTERY_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_BATTERY_CHANGED"
    const val ACTION_PODS_ANC_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_ANC_CHANGED"
    const val ACTION_PODS_GAME_MODE_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_GAME_MODE_CHANGED"
    const val ACTION_PODS_LOW_LATENCY_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_LOW_LATENCY_CHANGED"
    const val ACTION_PODS_DUAL_CONN_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_DUAL_CONN_CHANGED"
    const val ACTION_PODS_EQ_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_EQ_CHANGED"
    const val ACTION_PODS_WIND_SUPPRESSION_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_WIND_SUPPRESSION_CHANGED"
    const val ACTION_PODS_IN_EAR_DETECTION_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_IN_EAR_DETECTION_CHANGED"
    const val ACTION_CONFIG_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_CONFIG_CHANGED"
    const val ACTION_REFRESH_STATUS = "io.github.yuxiximyxx.magicxm4.ACTION_REFRESH_STATUS"
    const val ACTION_GET_PODS_MAC = "io.github.yuxiximyxx.magicxm4.ACTION_GET_PODS_MAC"
    const val ACTION_PODS_MAC_RECEIVED = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_MAC_RECEIVED"
    const val ACTION_ANC_SELECT = "io.github.yuxiximyxx.magicxm4.ACTION_ANC_SELECT"

    /** 让耳机自己响铃（欢律 0x0400）。extra: on(Boolean) */
    const val ACTION_FIND_BUDS = "io.github.yuxiximyxx.magicxm4.ACTION_FIND_BUDS"

    /** 功能开关（低延迟/高音质/3D/佩戴检测/双设备…）。extra: id(Int), on(Boolean) */
    const val ACTION_FEATURE_SET = "io.github.yuxiximyxx.magicxm4.ACTION_FEATURE_SET"

    /** EQ 预设。extra: id(Int) */
    const val ACTION_EQ_PRESET = "io.github.yuxiximyxx.magicxm4.ACTION_EQ_PRESET"

    /** 自定义 EQ：action(1 新建/2 保存选中/3 删除)、id、name */
    const val ACTION_EQ_CUSTOM = "io.github.yuxiximyxx.magicxm4.ACTION_EQ_CUSTOM"

    /** 提示音音量。extra: level(Int 1..10) */
    const val ACTION_ALERT_VOLUME = "io.github.yuxiximyxx.magicxm4.ACTION_ALERT_VOLUME"

    /** 修改按键绑定。extra: index(Int 第几条), fn(Int 功能字节) */
    const val ACTION_GESTURE_SET = "io.github.yuxiximyxx.magicxm4.ACTION_GESTURE_SET"

    /** 贴合度检测开关。extra: on(Boolean) */
    const val ACTION_FIT_TEST = "io.github.yuxiximyxx.magicxm4.ACTION_FIT_TEST"

    /** 重新查询耳机连接过的设备列表 */
    const val ACTION_QUERY_DEVICES = "io.github.yuxiximyxx.magicxm4.ACTION_QUERY_DEVICES"
    const val ACTION_GAME_MODE_SET = "io.github.yuxiximyxx.magicxm4.ACTION_GAME_MODE_SET"
    const val ACTION_LOW_LATENCY_SET = "io.github.yuxiximyxx.magicxm4.ACTION_LOW_LATENCY_SET"
    const val ACTION_DUAL_CONN_SET = "io.github.yuxiximyxx.magicxm4.ACTION_DUAL_CONN_SET"
    const val ACTION_EQ_SET = "io.github.yuxiximyxx.magicxm4.ACTION_EQ_SET"
    const val ACTION_WIND_SUPPRESSION_SET = "io.github.yuxiximyxx.magicxm4.ACTION_WIND_SUPPRESSION_SET"
    const val ACTION_IN_EAR_DETECTION_SET = "io.github.yuxiximyxx.magicxm4.ACTION_IN_EAR_DETECTION_SET"
    const val ACTION_PODS_UI_INIT = "io.github.yuxiximyxx.magicxm4.ACTION_PODS_UI_INIT"
    const val ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED = "io.github.yuxiximyxx.magicxm4.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED"
    const val ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET = "io.github.yuxiximyxx.magicxm4.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET"
}
