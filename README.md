# MagicXM4

让 **Sony WH-1000XM4**（头戴式）在荣耀 **MagicOS 11** 的设备中心里像荣耀自家耳机一样用：
设备球、服务卡切降噪、看电量、详情页。

一个 LSPosed 模块。荣耀侧注入链 fork 自 [MagicFree4](https://github.com/Yuxiximyxx/MagicFree4)
（再往上是 [MagicOriG](https://github.com/yingjfd/MagicOriG)，MIT），
协议层换成**索尼私有 SPP 协议**——移植自
[SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient)（GPL-3.0）。

- 包名：`io.github.yuxiximyxx.magicxm4`
- 版本：3.0.0
- 适用：MagicOS 11 + Sony WH-1000XM4

> ## ⚠️ 当前状态：协议层已写完，但**尚未真机验证**
>
> 本项目与 MagicFree4 不同：Free4 的每一条结论都是真机逐环打出来的，
> 而 XM4 这一版是在**没有耳机在手**的情况下完成的——代码按两套独立参考实现
> （SonyHeadphonesClient + Gadgetbridge 的协议事实）交叉验证，配 49 个 JVM 单测，
> 但**没有一行是在 XM4 上跑出来的**。
>
> 首次上机请按 [§首次上机要确认的事](#首次上机要确认的事) 逐条核对。

## 功能

### 系统集成

- **设备中心球位 + 服务卡**：像原厂耳机一样出现在控制中心
- **三档噪声控制**：关闭 / 降噪 / 通透，写后回读校验（XM4 只有三档语义，没有「自适应」）
- **单总电量**：XM4 只有一颗总电量（无左右耳、无充电仓），服务卡与详情页同步
- **QS 磁贴**：控制中心快捷切档
- **SWS 通知**：音频切换通知追加电量，断开自动清除
- **记忆式 ANC**：重连后自动恢复你上次选的档位
- **低电量提醒**：电量 ≤20% 时通知（30 分钟限频）

### 耳机详情页（桌面图标 / 设置 → 更多设置）

顶部渐变模糊 / 卡片布局 / 圆形返回 / 跟随深色模式：

| 区块 | 内容 |
|---|---|
| 电量 | 单颗总电量环（充电中自动标注） |
| 噪声控制 | 关闭 / 降噪 / 通透 三档切换 |
| 设置 | 电量历史、关于耳机（型号 / 固件 / 协议 / 模块设置入口） |

### XM4 没有、所以做不了的功能（明确不做，不留假入口）

| 功能 | 为什么 |
|---|---|
| 响铃查找 | XM4 不支持响铃，服务卡上该入口不存在 |
| 手势编程 | XM4 的触控面板不可编程映射成荣耀那套按键表 |
| 自定义 EQ | 索尼有 EQ（`0x56/0x57/0x58`），但属二期 |
| 设备双连管理 | XM4 支持多点，但控制通道在多点下可能被占，二期评估 |
| 「自适应」降噪档 | XM4 没有自适应降噪，第 4 档凭空出现只会误导 |

## 安装

1. 手机需已 root 并装好 **LSPosed**（MagicOS 11 / Magic7 Pro 实测环境）。
2. 安装 `MagicXM4-3.0.0-release.apk`。
3. 在 LSPosed 里启用模块，**作用域保持默认全选**（`com.android.bluetooth`、
   `com.android.systemui`、`com.hihonor.audioaccessorymanager`、
   `com.hihonor.imedia.sws`、`com.hihonor.controlcenter`），然后**重启手机**。
4. 首次连接耳机后，模块会把真实 MAC 发布到 `Settings.Global["magicxm4_mac"]`，
   设备中心据此渲染设备球。

> **和 MagicFree4 能共存吗？** 设计上可以，但**没有实机两模块同装验证过**。
>
> - 键空间已隔离：本项目用 `magicxm4_*`，MagicFree4（v2.x）用 `magicorig_*`，互不覆盖。
> - 包名不同（`...magicxm4` / `...magicfree4`），广播 action 也各自带包前缀。
> - **真正的风险是 SPP 抢占**：两个模块都会给各自的耳机建 RFCOMM 连接。
>   同时只连一台耳机时没问题；两台都在线、且都想抢同一条链路时才可能互相踢。
>   建议先只启用一个模块，确认真机稳定后再试共存。

## 首次上机要确认的事

按优先级排，每条都写清了「不对的话改哪里」：

1. **降噪能不能切**（最关键）。两个参考实现在设置载荷的下标 3/4 上矛盾：
   - 默认走 `WIND_CAPABLE`：`68 02 11 02 02 01 00 00`（降噪）
   - 若无反应，切旧布局：`adb shell settings put global magicxm4_sony_layout 1`
     → 变成 `68 02 11 01 02 01 00 00`（SonyHeadphonesClient 在 XM3 实测过的形状）
2. **耳机侧切档能否回读同步**。日志关键字：`降噪回读 mode=... → status=...`。
   若一直没出现，说明 `0x66 02` 查询没被应答 —— 那时砍掉连接时的查询序列，
   只靠写后回读。
3. **电量**。查询 `10 00`、应答 `11 00 <level> <charging>`。若设备没回，
   对照 Sony 官方 App 的数值，必要时改用 `10 01`（DUAL 槽）。
4. **通透档的环境声等级**。默认 15（1..20）：`adb shell settings put global magicxm4_ambient_level 20`。
5. **头戴形态**。注入类型默认沿用 MagicFree4 真机验证过的 `Third_EarPhone`。
   模块第一次在 controlcenter 里跑过之后，会把本机 `HnDeviceProductType` 的**全部枚举名**
   写进 `Settings.Global["magicxm4_diag_hn_types"]`（模块设置页也会显示）。
   看完名字想试别的：
   ```bash
   adb shell settings put global magicxm4_device_type <枚举名>
   adb shell am force-stop com.hihonor.controlcenter   # 让它重新注入
   ```
   枚举里没有的名字会自动回退 `Third_EarPhone`，不会把设备球弄没。
6. 日志：`adb logcat -s MagicXM4-Rfcomm`，SPP 收发十六进制都打了。

> 上面 1/4/5 三项都可以直接在 **模块 App 首页** 里改（不用 adb）：
> 「索尼协议（真机 A/B）」卡片里有载荷布局单选、环境声等级加减；
> 设备类型名和枚举清单也在那张卡片里显示。

## 协议速查（`pods/sony`）

**帧**：`3E | <内容区> | 3C`，内容区 = `类型(1) 序号(1) 长度(4 BE) 载荷(n) 校验和(1)`；
内容区里 `3C/3D/3E` 转义成 `3D 2C / 3D 2D / 3D 2E`；校验和是先算再单独转义的字节和。

**命令号**

| 用途 | 请求 | 应答 | 通知 |
|---|---|---|---|
| 初始化（本项目不发） | `00` | `01` | — |
| 固件版本 | `04` | `05` | — |
| 电量 | `10` | `11` | `13` |
| 降噪查询 | `66` | `67` | — |
| 降噪设置 | `68` | — | `69` |

**关键载荷**

```
电量查询      10 00                      (SINGLE；01=DUAL 02=CASE)
电量应答      11 <type> <level> <charging>
降噪查询      66 02
降噪应答      67 02 <启用> <能力> <模式> 01 <人声> <等级>
              能力=00: 模式 0=环境声 1=降噪
              能力=02: 模式 0=环境声 1=风噪降低 2=降噪
降噪设置      68 02 <effect 00|11> <能力> <模式> 01 <人声> <等级>
```

**荣耀三档 ↔ 索尼模式**

| 荣耀 status | 索尼 |
|---|---|
| 1 关闭 | effect=`00` |
| 2 通透 | effect=`11`，模式=环境声，等级=`magicxm4_ambient_level`（默认 15） |
| 5 降噪 | effect=`11`，模式=降噪 |

反向：关→1、环境声→2、降噪→5、风噪降低→5（归并但会写原始模式日志）。

## 运行期开关（`Settings.Global`）

真机上不确定的东西**不写死在代码里**，全部做成运行期可改，改完不用重编 APK：

| 键 | 默认 | 作用 |
|---|---|---|
| `magicxm4_sony_layout` | `0` | 降噪设置载荷布局：`0`=风噪能力布局（Gadgetbridge），`1`=旧 dualSingle（SonyHeadphonesClient） |
| `magicxm4_ambient_level` | `15` | 「通透」档对应的索尼环境声等级 1..20 |
| `magicxm4_device_type` | `Third_EarPhone` | 注入用的荣耀设备类型枚举名；非法值自动回退默认值 |
| `magicxm4_diag_hn_types` | 自动写入 | 本机 `HnDeviceProductType` 全部枚举名（逗号分隔），选型的证据来源 |

模块 App 首页的「索尼协议（真机 A/B）」卡片可以直接改前两项。

## 项目结构

```
app/src/main/java/io/github/yuxiximyxx/magicxm4/
├── pods/sony/        索尼协议层（纯 JVM，可单测）
│   ├── SonyWire.kt       帧编解码 + 流式解帧 Framing
│   ├── SonyCommands.kt   命令号表、载荷构造、SeqCounter、两种布局
│   └── SonyState.kt      应答解析 → SonyEvent
├── pods/hem/         MagicFree4 遗留的欢律协议（XM4 不用，保留以减少改动面）
├── pods/RfcommController.kt   SPP 连接、轮询、状态桥接
├── hook/             荣耀侧注入链（设备球、服务卡、QS 磁贴、SWS 通知）
└── EarphoneDetailActivity.kt  详情页（Compose）
```

单测：`./gradlew :app:testDebugUnitTest`（49 个用例，全部纯 JVM）。

## 许可

GPL-3.0（全文见 `LICENSE`）。上游归属见 `NOTICE.md`：

- [MagicOriG](https://github.com/yingjfd/MagicOriG)（MIT）— 荣耀侧注入框架
- [MagicFree4](https://github.com/Yuxiximyxx/MagicFree4)（GPL-3.0）— 直接 fork 来源
- [SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient)（GPL-3.0）— 索尼协议层
- [Gadgetbridge](https://github.com/Freeyourgadget/Gadgetbridge)（AGPL-3.0）— **仅取协议事实，未复制代码**

本项目与索尼公司无任何隶属或授权关系，Sony、WH-1000XM4 为其各自权利人商标。
