# 变更记录

## 3.0.3 — 机型配置可切换（AirPods MAX）+ 断连隐藏噪声控制

### 找到卡片布局的真正来源

荣耀的卡片长什么样，由宿主 `assets/uiconfig/<机型码>.json` 决定，不看我们注入的 prodId：

| 机型码 | noiseSwitchNum | itemList |
|---|---|---|
| `FCPG`（AirPods 4 降噪版，之前用的） | 4 | 含 `find_earphone` |
| `X6MG`（AirPods MAX 2020） | **3** | **不含** `find_earphone` |
| `WEQW`（AirPods MAX 2024） | 3 | 不含 `find_earphone` |

XM4 正好是「三档 + 不支持响铃」，所以默认改用 **X6MG**：
`loadFcpgConfig` 现在先试 `magicxm4_uiconfig`（默认 `X6MG`），解析失败再回落 `FCPG`。
原则是「原生就是三档、没有响铃查找」，不必再去 DOM 里按文案偷藏控件。

> 说明：这一改动在 `com.hihonor.audioaccessorymanager` 进程生效。本轮实测该进程
> 没有加载到模块（日志里没有 `MagicXM4-Earphone`），所以**卡片目前仍走 DOM 隐藏那条路**。
> 两条路互不影响：DOM 隐藏只在我们自己的卡片上生效，Free4 的卡片不受影响。

### 断连时隐藏噪声控制（对齐原生华为耳机）

实测 HUAWEI FreeBuds 5 断开时卡片上**没有**噪声控制；我们注入的第三方设备因为强制走
「在线」渲染，断开后仍显示一排点了没用的档位按钮。现在按 `updateEarphoneUIByOnlineType`
拿到的真实在线态，离线隐藏整段、在线恢复。

### 其它

- 新增 `HonorAssetsDump`：把宿主 assets 里的机型表 / uiconfig 目录 / 配置内容
  分块写进 `magicxm4_diag_assets_1..8`，不用 root 就能看（这次就是靠它找到的网络机型表）
- 机型表实测：`AirPods MAX 2020 = 00X6MG`、`AirPods MAX 2024 = 00WEQW`
  （`AirPods MAX 2` 在本机 assets 里没有，可能是更新版本才有）

## 3.0.2 — 服务卡只删 XM4 的入口，不动别人的卡片

3.0.1 的隐藏逻辑是按文案全窗口搜的，结果把 **OPPO Enco Free4 的卡片也一起改了**
（那个模块还装着，两张卡共用同一个 controlcenter）。这一版把作用域收窄到「我们自己的卡片」。

### 怎么判断「这是我们自己的卡片」

从目标文案往上走，找到第一个包含 `WH-1000XM4` 的祖先；如果这个祖先里还出现别的设备名
（`Enco Free4` / `FreeBuds` / `MacBook` / `LKY`），说明爬到了设备球列表那一层 —— 直接放弃。
实测 dump 出来的视图树也印证了这点：球列表把所有设备名放在同一棵子树里。

### 隐藏层级（真机 dump 出来的结构）

```
HwTextView#service_name "响铃查找" → LinearLayout[2] → LinearLayout#service_card_root[3]  ← 藏这层
HwTextView#noise_mode_text "自适应" → LinearLayout[2](图标+文案)                            ← 藏这层
```

- 响铃查找：往上两层 = `service_card_root`，整张子卡消失（不再留下空框和铃铛图标）
- 自适应：往上**一**层 = 那一颗按钮。往上两层是整排四颗按钮的容器，
  会把「关闭/降噪/通透」一起藏掉（踩过，已回退）

### 真机结果

- WH-1000XM4 卡片：没有「响铃查找」，噪声控制正好三档（降噪 / 关闭 / 通透），电量 90%
- OPPO Enco Free4 卡片：**完全不受影响**，「响铃查找」和四档（含自适应）都在

### 另外

- 新增排障开关 `magicxm4_diag_dump_ui=1` → 把卡片视图树写进 `magicxm4_diag_ui`，
  用来看清某个入口在第几层（这次就是靠它定的层级）。平时关着。

## 3.0.1 — 真机联调收敛：协议全通 + 卡片去噪

> 本轮结论全部来自真机（PTP-AN10 / MagicOS 11.0.0.110 / WH-1000XM4 80:99:E7:B2:9C:42）。

### 协议层：三个真机坑

| 现象 | 真因 | 修法 |
|---|---|---|
| `66 02` / `10 00` / `04 02` 发出去**一条不回** | ① 设备每发一条 COMMAND 都等手机回一条 ACK，不回它就不再应答<br>② 出站序号必须跟随设备回执（实测 `1 - seq`，即 1,0,1,0 交替）；自增到 3/4/5/6 后设备直接无视 | 收到非 ACK 消息立即回 `Message(ACK, 1-seq, 空)`；出站改用 `SeqCounter.current()` 并在收到 ACK 时 `adopt` |
| 两条命令间隔几十毫秒时设备也不回 | 设备处理不了背靠背命令 | 相邻命令最小间隔 300ms + ACK 上限 800ms |
| 链路重建期间的切档命令丢失，UI 已乐观高亮 | 队列在 `isConnected=false` 时清空 | 改成等待重发，不丢队列 |

### 真机验证结果

- SPP `96cc203e-5068-46ad-b32d-e316f5e069ba` 连接成功
- INIT 应答 `01 00 70 00`（4 字节）→ 协议 **V1**
- 电量 `11 00 5A 00` → **90%**，与耳机一致
- 固件 `05 02 05 33 2E 30 2E 31` → **3.0.1**
- 降噪回读 `67 02 01 02 02 01 00 00` → 降噪；写后回读三档全部正确：
  - 降噪 → `NOISE_CANCELLING`、通透 → `AMBIENT_SOUND:15`、关闭 → `OFF`
- 默认的 `WIND_CAPABLE` 载荷布局**是对的**（无需切 `magicxm4_sony_layout`）
- 设备中心出现 WH-1000XM4 球，可点；服务卡显示 90% 电量

### 服务卡 UI 去噪

- **「响铃查找」整行隐藏**：XM4 不支持响铃。拿到窗口根视图后按文案定位该行，
  连同只有它一个子 View 的容器一起 `GONE`（只隐藏 TextView 会留下空框和图标）
- **「自适应」整颗按钮隐藏**：荣耀按 `noiseSwitchNum=4` 渲染四档，XM4 只有三档。
  与其让第 4 档点了没反应，不如直接不显示
- 其它 XM4 不存在的入口（手势/贴合度/功能位/EQ/提示音/设备列表）变成
  「打日志 + Toast 提示不支持」，不再静默吞点击
- 自适应若仍被下发（旧卡片缓存），`setANCMode(7)` 降级为降噪并写诊断留痕

### 诊断

新增 `magicxm4_diag_sony`（最近 16 条 TX/RX 载荷）与 `magicxm4_diag_raw`（最近 8 个原始帧），
不依赖日志级别、不依赖 root：

```bash
adb shell settings get global magicxm4_diag_sony
adb shell settings get global magicxm4_diag_raw
```

## 3.0.0 — 换设备：Sony WH-1000XM4（索尼协议层）

从 MagicFree4 v2.9.1 fork，换两层：设备身份（耳塞 → 头戴）与协议层（欢律 → 索尼 SPP）。

> ⚠️ **本版未经真机验证**。协议层按两套独立参考交叉验证 + 49 个 JVM 单测，
> 但没有一行是在 XM4 上跑出来的。上机核对清单见 README「首次上机要确认的事」。

### 改名与隔离

| 项 | 旧（MagicFree4） | 新（MagicXM4） |
|---|---|---|
| 包名 | `io.github.yuxiximyxx.magicfree4` | `io.github.yuxiximyxx.magicxm4` |
| 应用名 | MagicFree4 | MagicXM4 |
| Settings.Global 键 | `magicorig_*` | `magicxm4_*` |
| 设备名（注入） | OPPO Enco Free4 | WH-1000XM4 |
| 协议 | 欢律 `pods/hem` | 索尼 `pods/sony` |

键空间与包名都换了，所以**可以和已装的 MagicFree4 共存**（SPP 抢占风险见 README）。

### 新增 `pods/sony`

- `SonyWire.kt` — 帧层：`3E … 3C`、`3D` 转义、校验和、流式解帧 `Framer`
  （移植自 SonyHeadphonesClient，修掉上游两个 bug：转义后再校验、双 START 时重同步而非断连）
- `SonyCommands.kt` — 命令号表（电量 `10/11/13`、降噪 `66/67/68/69`、固件 `04/05`）、
  载荷构造、`SeqCounter`、两种设置布局 `NcAsmLayout`
- `SonyState.kt` — 应答解析 → `SonyEvent`（`Ack` / `NoiseState` / `Battery` / `Firmware` / `Unknown`）
- `SonyWireTest.kt` — 49 个纯 JVM 用例（帧往返、转义、手算校验和、模糊分片、命令载荷逐字节、三种应答解析）

### 接线

- SPP UUID 换成 `96CC203E-5068-46ad-B32D-E316F5E069BA`；保留 insecure + 通道兜底
- 连接后查询序列：降噪 → 电量 → 固件 → 再回读降噪（不发 INIT 握手）
- 出站序号统一由 `SeqCounter` 分发；收到 ACK 后把计数器对齐到 `ack.seq + 1`
- 降噪：荣耀三档 ↔ 索尼模式；「风噪降低」归并到「降噪」但写原始模式日志
- 电量：单总电量喂给 L/R 同值，充电仓留空
- 运行期开关：`magicxm4_sony_layout`（0=风噪布局 / 1=旧 dualSingle 布局）、
  `magicxm4_ambient_level`（通透档环境声等级，默认 15）

### 详情页裁剪

- 电量：三个环 → 一个「电量」环
- 噪声控制：四档 → 三档（去掉「自适应」，XM4 没有这个模式）
- 移除入口：均衡器、设备双连、耳机设置（手势/响铃/佩戴检测/提示音）
- 关于页：协议改为「索尼私有协议（SPP）」+ 正确 UUID
- 降噪高亮数据源从 `magicxm4_anc_raw`（位掩码）改为 `magicxm4_current_anc`（三档 status）

### 运行期开关（真机 A/B 不用重编）

协议层的不确定点全部做成 `Settings.Global` 可改，模块 App 首页新增
「索尼协议（真机 A/B）」卡片也能直接改：

| 键 | 默认 | 作用 |
|---|---|---|
| `magicxm4_sony_layout` | `0` | 降噪载荷布局：0=风噪能力布局，1=旧 dualSingle |
| `magicxm4_ambient_level` | `15` | 「通透」档 → 索尼环境声等级 1..20 |
| `magicxm4_device_type` | `Third_EarPhone` | 荣耀设备类型枚举名（头戴形态靠它试） |
| `magicxm4_diag_hn_types` | 自动 | 本机 `HnDeviceProductType` 全表，供选型 |

新增 `hook/HonorDeviceTypeName.kt` 作为设备类型的唯一取值点：
四处置类型的地方（JSON 注入、`DeviceInfo` 构造、`LinkDevice` 兜底、`selectLayout` 归位）
现在都走它，非法名字回退已验证的 `Third_EarPhone` 而不是让设备球消失。
类型名带 5 秒缓存，改完不用重启进程。

模块设置页同步修正：
- 电量从「左耳/右耳」两个环改成一个「电量」环（读 `magicxm4_battery_single`）
- 默认档位从「实验性/深度/普通降噪」改成「关闭/降噪/通透」（XM4 的真实三档）
- 修正两个记忆键不一致：UI 只写 `magicxm4_last_anc`、控制器只写 `magicxm4_anc_last`，
  现在两处一起写

### 明确做成 no-op 的入口（不撒谎、不广播假状态）

响铃查找、手势绑定、贴合度检测、功能位开关、EQ 预设/自定义、提示音音量、设备列表查询。

## 2.9.1 — 首页改为耳机详情页

- 启动入口（`activity-alias LauncherActivity`）从 `MainActivity` 改为 **`EarphoneDetailActivity`**：
  点桌面图标直接进耳机设置页，和系统「更多设置」同一个页面
- 模块自己的设置（作用域/日志/调试）移到 **关于耳机 → 模块设置**，随时可回
- 详情页在未连接时也能打开（显示未连接状态），不会白屏


## 2.8.1 — 六项新功能

| 功能 | 实现 | 验证 |
|---|---|---|
| **记忆式 ANC** | 每次用户选档写入 `magicorig_anc_last`；连上后 `restoreAncIfAny()` 自动重发 | 真机：设通透 → `anc_last=2`；重启蓝牙后自动重发 `mode=2 bit=2` ✅ |
| **低电量通知** | 耳机 ≤20% 且未充电 → 通知（30 分钟限频，独立渠道 `magicorig_low_battery`） | 代码就绪 |
| **电量历史** | 每次电量变化记 `HH:mm,L,R,C`，最多 40 条，同分钟同值不重复 | 真机：`21:33,100,100,80` ✅ |
| **浅色主题** | 色板改为可切换 `P.apply(dark)`，详情页跟随系统深浅色 | 代码就绪 |
| **关于耳机页** | 型号 / 蓝牙地址 / 固件 / 协议 / 固件支持命令数 / 开源许可 | 已接入 |
| **个性化降噪** | `0x011A` 探测是否已有测试结果，界面按结果显示 | 真机：`pnoise=0`（暂无结果） |

### 电量历史 / 关于 / 个性化降噪入口

设置页新增三项：电量历史、关于耳机、个性化降噪（只读，说明需在欢律 App 完成听力测试）。

### 说明

个性化降噪的「测试流程」需要耳机播放测试音并回传结果（`0x0412` + `0x0204` 事件 `0B`），
Free4 固件不回报该能力位，因此本模块只做结果查询与展示，不做测试流程。


## 2.7.5 — 黄金听感 + 固件能力探测结论

### 新增：黄金听感（`0x0403` 功能位 `0x0B`）

真机验证开关往返：
```
关 → features 11=0
开 → features 11=1
关 → features 11=0
```

### 固件能力探测（statusQuery 追加候选 id）

把 statusQuery 扩展到 `05 04 0B 11 18 06 1B 1C 38 1A 17 30 3A 0C`，
真机只回报：

```
4=1 佩戴检测 | 11=1 黄金听感 | 17=1 双设备 | 24=1 高音质 | 6=0 低延迟
```

**以下全部无回报 → 固件不支持，不做**：
听力优化 `0x38`、防风噪 `0x1A`、省电 `0x17`、自适应音量 `0x30`、
睡眠暂停 `0x3A`、个性化降噪 `0x0C`。

能力位里也没有 `0x0429`（切换主设备），所以设备双连只能看不能切。


## 2.7.3 — 保存/删除修好 + 长按删除 + 预设改名

### 根因：`not found id=4`

保存/删除失败不是协议问题，而是控制器**拿不到预设列表**：
它去读 `Settings.Global.magicorig_eq_custom`，但在蓝牙进程里 `mContext` 可能为 null（`safeDisconnect`
会把它置空），读出来是空字符串 → 反序列化得到空列表 → 直接 `not found`。

修法：收到 `0x8122` 回复时把列表缓存到内存（`eqCustomCache`），写入时优先用缓存，Settings 只做兜底。

### 真机验证（修复后）

```
保存 id=4 gains=1,0,0,0,0,0 → 列表 4|自定义 706|1|62:1,...    ✅ 且自动选中
删除 id=6                    → 列表 3 条变 2 条              ✅
还原 id=4 为平坦             → 62:0,...                      ✅
```

### 界面

- **长按自定义预设 → 直接弹删除确认**（不用先进编辑页）
- 编辑页新增「重命名」行 → 弹输入框改名，保存时随 `0x0418` 一起写入
- 预设名限制 20 字符（协议里名字长度是 1 字节，20 是本模块自定的合理上限）


## 2.7.2 — 均衡预设编辑页（调增益 / 保存 / 删除）

- 点「自定义」里的任一预设 → **编辑页**：6 个频段（62Hz / 250Hz / 1kHz / 4kHz / 8kHz / 16kHz）
  各一条 −6 ~ +6 dB 滑杆，右侧实时显示增益值
- 「新建自定义预设」不再直接创建：先打开编辑页（平坦曲线），调好后再保存
- 编辑页底部：**保存**（`0x0418` action=2 / 新建时 action=1）、**删除这个预设**（action=3）
- 写入时增益随 payload 一起下发（`<freq u16 LE, gain s8>`），保存后读回校验

### 关于「只能存三个」

这是**耳机固件的限制**，不是本模块或协议的限制（QuickBuds 里没有任何数量上限代码）。
App 侧无法突破固件上限；能做的只有：
- 在现有预设上编辑曲线（不新增条目）
- 删除不需要的预设腾出位置

界面上已标注「上限由耳机固件决定（当前机型 3 个）」。


## 2.7.1 — 自定义 EQ（读列表 / 选中 / 新建 / 删除）

### 协议

| 命令 | 用途 |
|---|---|
| `0x0122` → `0x8122` | 自定义预设列表：`<status> <count> <flag tag2 id nameLen name bandCount [freq u16 LE, gain s8]...>` |
| `0x0418` | 写入：`<action> <tag> <id> <nameLen> <name> <bandCount> <bands...>`；action 1 新建（id=0 由耳机分配）/ 2 保存并选中 / 3 删除 |

编解码移植自 QuickBuds `Eq.kt`（GPL-3.0），证据见其 PROTOCOL.md §17。

### 真机验证

1. **读列表**：
   `4 | Free4 高解析通透 | 选中 | 62:-3, 250:-2, 1000:-1, 4000:+1, 8000:+2, 16000:+1`
2. **新建**：发 action=1 name=测试预设 → 耳机分配 `id=5`，**平坦曲线并自动选中**，旧预设取消选中
3. **删除**：action=3 id=5 → 列表恢复只剩 id=4

（测试预设已删除，耳机状态已还原）

### UI

均衡器页现在分两块：
- 推荐（内置 slot 0/1/2/7）
- **自定义**（从耳机读到的预设，选中项打勾；底部「新建自定义预设」）

频段增益编辑界面（拖动各频段滑杆并保存）留待下一步。


## 2.7.0 — 能力位解析（判断固件支持）+ 滑块/图标调整

### 能力位（0x8100）

新增 `HemCapabilities`（表来自 QuickBuds `Capabilities.kt`）：握手回复 `00 <bitmap>` 解析成
「固件支持的命令集合」，写入 `magicorig_caps`。

**真机结果**：`0x0405`（贴合度检测）**在支持列表里** → 说明贴合度不是「不支持」，
之前一直「检测中」是因为耳机在盒里（未佩戴）或链路未就绪。

### 界面

| 项 | 处理 |
|---|---|
| 设备图标 | **已删除**（emoji 猜不准，直接不显示） |
| 提示音音量滑杆 | 改为荣耀风：连续轨道（无刻度点）+ 4dp 细轨 + 22dp 白色圆点 + 蓝色已填充 |
| 贴合度检测 | 依据能力位决定可用性：不支持则显示「本固件不支持该功能」并禁用 |


## 2.6.6 — 首页入口 + 设备图标 + 贴合度超时 + SPP 链路诊断

| 反馈 | 处理 |
|---|---|
| 设备双连要能首页进入 | 首页「设置」卡新增「设备双连」入口（页内含开关+列表） |
| 设备图标全是电脑 | 改为按名称特征分类（mac/book/laptop/pc/win/电脑 → 💻，pad/平板 → 📱，buds/enco/耳机 → 🎧，其余 📱）；本机名判定加 `Settings.Secure.bluetooth_name` 兜底 |
| 贴合度一直「检测中」 | 15 秒无结果自动提示「未收到结果，点击重试」，不再永久卡住 |

### SPP 链路（重点）

给链路全流程加了回执：
- `magicorig_diag_disp`（广播注册/命中/触发）
- `magicorig_diag_connectpod`（连接入口/跳过原因）
- `magicorig_diag_spp`（**连上哪个 UUID / 全部失败**）

真机结果：`spp = connected insecure 0000079a-…` —— 链路已建立。
另在连接前加了 1.2s 缓冲（A2DP 刚连上时耳机侧 SPP 可能未就绪）。

**已知**：贴合度命令发送成功（`diag = on=true socket=true`）但收不到结果，
下一步会用 `0x8100` 能力位判断该固件是否支持 `0x0405`（不支持则直接置灰）。


## 2.6.3 — 「设备双连」页合并（开关 + 列表一体）

原先把「双设备连接」开关放在「功能」列表、设备列表放在「设置」，两处重复。
按参考图合并为一个页面：

- 「功能」里移除 `0x11` 那行（避免重复入口）
- 设置 → **设备双连**，页内包含：
  - 顶部开关卡：设备双连（`0x0403` 功能位 `0x11`）+ 说明文案
  - 「已连接设备」卡：从 `0x8112` 读到的设备列表，手机那行标「已连接（本机）」
  - 「添加新设备」→ 系统蓝牙设置
  - 「刷新列表」→ 重新查询 `0x0112`


## 2.6.1 — 补 0x07 功能名 + 双设备列表页

### 修复

- 手势功能表**漏了 `0x07`（音量：上滑增 / 下滑减）**，导致滑动显示「未识别(7)」。已补。

### 新增：双设备连接页（0x0112 → 0x8112）

- 查询：`0x0112` 空 payload
- 回复：`<status 00> <count>` 后每条 `MAC(6) <byte6> <state> <flags> <nameLen> <name>`，`state==2` 视为已连接
- **真机验证**：
  ```
  61:7B:86:DA:86:34 | 立刻有 | 已连接
  AF:FE:E6:F8:1C:58 | LKY    | 未连接
  ```
- 设置页新增「双设备连接」入口，可查看列表并刷新

> 解析时踩了一坑：payload 前有一个状态字节（QuickBuds 用 `ok()` 先剥掉），首版按 count 在 [0] 解析导致列表为空。


## 2.5.6 — 手势按左右耳分组 + 通话按键 + 贴合度检测（协议接入）

### 手势按钮组与功能码（查证结论）

- `button 0x01` = **主按键（听歌）**，`button 0x06` = **通话中按键** → 同一「双击」有两套绑定，属正常
- 功能 29（0x1D）= **接听/挂断**（QuickBuds 真机通话实测）
- 功能 28（0x1C）= **拒接**
- 功能 25（0x19）= **未识别**（公开协议无定义，不提供修改）

手势页改为 **左耳 / 右耳 → 主按键 / 通话中** 分组；写入按 `(dev, btn, act)` 精确定位。
真机验证：`dev=1 btn=1 act=3 fn=25` 写回成功，右耳对应项保持不变 ✅

### 贴合度检测（0x0405）

- 命令 `0x0405 [01 开始 / 00 停止]`，结果走 `0x0204` subType `0x04`
- 结果状态：1 良好 / 0 一般 / 6 较差，写入 `magicorig_fit`
- 设置页新增「贴合度检测」入口

### 已知问题（未解决）

蓝牙栈重启后 **SPP 链路重建不成功**：`ensureLink()` 的重建线程会卡住（回执停在 `rebuild requested`），
导致此期间所有命令无效。需要下一轮专门处理（拟改为在蓝牙进程初始化阶段注册接收器 + 看门狗重连）。


## 2.5.1 — 手势页（读+写）+ 矢量返回图标 + 荣耀风格弹窗

### 手势协议（0x0108 读 / 0x0401 写）

- 读：`0x0108` → `0x8108`，payload = `<status> <count> [deviceType, button, buttonAction, function]...`
- 写：整表回写（只改目标条目的 function 字节），写完再读回校验
- buttAction：1 单击 / 2 双击 / 3 三击 / 4 长按 / 5 滑动 / 6 超长按
- function 字节（来自 QuickBuds 实测表）：`00 无 / 01 播放暂停 / 05 上一首 / 06 下一首 /
  03 语音助手 / 0B 音量+ / 0C 音量- / 07 音量调节 / 0A 切换歌曲 / 0D 切换设备 / 11 游戏模式 / 08 降噪循环`

**真机验证**：读回 18 条绑定；把第 3 条（三击）从 `0x05` 改为 `0x06`，读回确认为 `1,1,3,6` ✅

### 界面

- 返回按钮改为**矢量图标**（`ic_back.xml`，圆角圈 + 20dp 图标）
- 弹窗统一 17sp Medium 标题、20dp 圆角、`#1C1C1E` 容器、荣耀蓝操作按钮
- 提示音音量滑杆改用荣耀蓝轨道
- 手势页只显示主设备（deviceType=1）的一套绑定，按动作排序（避免 18 行重复）


## 2.4.6 — 一批真机反馈的修复

| 反馈 | 根因 | 修法 |
|---|---|---|
| 通透/关闭点击后能切换但按钮不变亮 | **上报位 ≠ SET 位**：关闭上报 `bit3`、通透上报 `bit8`，高亮却按尾零位直接比对 | `ancBitOf()` 归一：`0/3→关闭`、`2/8→通透`、`11→自适应`、其余→降噪 |
| 均衡器四预设切换无差异 | **payload 传错**：`0x0406` 要的是 `slot`（0/1/2/7），我们传了 `mode`（26/28/29/34）→ 静默无效 | 改用 slot，并补写后回读 `0x010F`；真机验证 `slot=0/1/2/7 → eq_cur=0/1/2/7` ✅ |
| 侧滑返回直接退出 | 没有拦截返回 | `BackHandler(enabled = page != 0)` 先回概览 |
| 蓝牙连接设置在两个页面重复 | 冗余 | 只保留在「耳机设置」页 |
| 蓝牙连接设置点不开 | Honor 私有 intent 可能不可达 | 失败回落到系统 `ACTION_BLUETOOTH_SETTINGS` |
| 3D 音效无法开启 | 固件未回报 `0x1B`（我们的 feature map 没有该项） | 开关改为「未被回报也可尝试」，副标题标注「固件未回报」 |
| 返回按钮质感差 | 用文本 `‹` | 待改进（下一步换矢量图标） |

**EQ 关键结论**：`models.json` 里的 `mode`（26/28/29/34）只是 HeyMelody 的命名空间，
下发要的是 `slot`（0/1/2/7）；`0x810F` 回读的也是 slot（0-2 built-in，4+ custom）。


## 2.4.2 — 去掉重复标题（ActionBar）

真机视图树 dump 发现页面顶部有两个同名文本节点：一个是我们的顶部栏，另一个是
`Theme.DeviceDefault.DayNight` 自带的 **ActionBar**（显示 Activity 的 `android:label`）。
改为 `Theme.DeviceDefault.NoActionBar` 后只剩一个标题，与系统设置一致。

## 2.4.1 — 顶部结构收敛

内容区不再重复渲染大标题（标题只在顶部栏），避免与顶部栏标题重叠。


## 2.4.0 — 详情页改为系统设置风格（渐变模糊顶部 + 子页面）

- **顶部渐变模糊遮罩**：内容从标题下方滚过时向上渐隐（`Brush.verticalGradient`，纯黑→透明三段过渡）
- **圆形返回键 + 大标题**：44dp 圆形按钮（`#2C2C2E`）+ 28sp Bold 标题，仿系统设置
- 色板全部改为系统深色取值：纯黑 `#000000` 底、`#1C1C1E` 卡片、`#38383A` 分隔线、
  `#8E8E93` 次要文字、`#0A84FF` 主操作色、`#32D74B` 成功色
- 卡片圆角 20dp、行高 ≥56dp、水平边距 16dp、区块间距 24dp
- 新增两个子页面：
  - **均衡器页**：四组预设（至臻原音/纯享人声/澎湃低音/活力动感），当前项打勾
  - **耳机设置页**：佩戴检测开关、查找耳机（开关）、提示音音量、固件版本、蓝牙设置；
    手势入口标注「待实现」


## 2.3.2 — 详情页按 MagicOS 设计规范重做

依据《Honor MagicOS 设计规范》（深色模式）：

| 维度 | 采用值 |
|---|---|
| 背景 / 卡片 / 三级背景 | `#111827` / `#1F2937` / `#374151` |
| 分隔线 / 主文字 / 次要文字 | `#4B5563` / `#F3F4F6` / `#8A919A` |
| 主操作色 / 成功色 / 错误色 | `#4D94FF` / `#5CAA7E` / `#E07065` |
| 字号 | 标题 24sp Bold、区块标题 15sp Medium、正文 15sp、说明 12sp |
| 圆角 | 卡片 16dp、弹窗 20dp、次按钮 12dp |
| 间距 | 8dp 栅格、水平 16dp、区块 24dp、列表行 ≥56dp |
| 动效/交互 | 触控区域 ≥48dp |

同时：
- 噪声控制四档（**新增自适应**，映射欢律 bit 11 / 内部 status 7）
- 「响铃查找」改为**开关**：点一下高亮+响，再点熄灭+停

## 2.3.1 — 响铃高亮修复

高亮走 `DeviceServiceCardDataMgr.notifyStatusChangeWithSpecialDev(adapter, "DccFindIotDevice", isSrc, highlight, localTag)`。
首次实现用了不存在的 `getInstance`（该类单例是私有字段 `sInstance`）→ `NoSuchMethodException`。
改为使用 hook 里的 `chain.thisObject` 作为 manager 实例，并从 `DeviceInfo.getBasicDeviceBallViewAdapter()` 兜底取 adapter。


## 2.2.4 — 修正接收器 IntentFilter（功能开关/EQ/音量真正生效）+ 自动回连

**关键 bug**：新增的 `ACTION_FEATURE_SET` / `ACTION_EQ_PRESET` / `ACTION_ALERT_VOLUME` 三个广播
只加进了 `when` 分支，**没加进 `IntentFilter` 列表** → 广播匹配不上，永远不触发。
ANC 能通是因为它本来就在列表里。

修复后真机实测（`magicorig_diag_*` 回执）：

| 动作 | 回执 | 结果 |
|---|---|---|
| 功能开关 id=6（低延迟） | `featset = id=6 on=true socket=true` | `magicorig_features` → `6=1` |
| EQ 预设 26 | `eqset = id=26 socket=true` | `magicorig_eq = 26` |
| 提示音音量 8 | `volset = level=8 socket=true` | `magicorig_alert_vol = 8` |
| 响铃 | `findtx = sent=true hex=AA 08 00 00 00 04 2C 01 00 01` | 指令已下发 |

**解析修正**（对照 QuickBuds `Decode.kt`）：

- `0x8105` 固件：`00 <count>` + UTF-8 三元组 → 正确显示 **138.138.105**（之前是一串 ASCII 码）
- `0x810D` 功能状态：兼容带状态字节的固件，正确解析 `4=1,11=1,17=1,24=1,6=1`
- `0x8130` 提示音音量：兼容 `00 <level>`
- `0x810F` EQ 回复不再覆盖本地值（编号空间与本模块预设不同）

**新增自动回连**：蓝牙栈重启后耳机常常不自动连回，`HeadsetStateDispatcher` 在 +5s/+20s
枚举已配对设备，A2DP 未连接则主动发起连接（隐藏 API 反射）。

**详情页**（2.2.0 起，本版真机可见）：三电量环、四档噪声控制、五个功能开关（不支持则置灰）、
均衡器/提示音音量/查找耳机/蓝牙设置/固件版本。


## 2.2.0 — 详情页对齐 QuickBuds + 功能开关/EQ/提示音/固件接入

### 链路争用（重要）

QuickBuds 与 MagicFree4 **不能同时持有 SPP**。实测：QuickBuds 连着时，设备中心里的响铃/降噪/断开全部空转（模块发不出帧），「更多设置」又优先跳 QuickBuds → 表现为「按钮全无效」。**要用模块就卸载/停用 QuickBuds。**

### 新增协议能力（`pods/hem`）

| 功能 | 命令 |
|---|---|
| 功能开关（低延迟/高音质/3D/佩戴检测/双设备） | `0x0403 [id, on]`，`0x010D` 查（payload 为 id 列表，Free4: `05 04 0B 11 18 06 1B 1C`） |
| 固件版本 | `0x0105` → `0x8105` |
| 均衡器 | `0x0406 [presetId]`，`0x010F` 查；Free4 预设 26/28/29/34 |
| 提示音音量 | `0x0427 [1..10]`，`0x0130` 查 |
| 自适应降噪 | `0x0404` bit 11（本模块扩展 status 7） |

新增跨进程桥：`magicorig_features`（`id=value,...`）、`magicorig_firmware`、`magicorig_alert_vol`、`magicorig_eq`、`magicorig_anc_raw`。

### 详情页重做（对齐 QuickBuds 版式）

- 顶栏：设备名 + 连接态 pill + 刷新/关闭
- 电量卡：左耳 / 右耳 / 充电仓三环
- 噪声控制：**关闭 / 降噪 / 自适应 / 通透** 四档（按原始位掩码高亮）
- 功能列表：低延迟、高音质、3D 音效、佩戴检测、双设备连接（开关，状态来自耳机回报）
- 设置入口：均衡器（预设对话框）、提示音音量（滑杆）、查找耳机、蓝牙连接设置、固件版本
- 不支持的功能自动置灰（靠 `magicorig_features` 是否含该 id 判断）

### 已知限制

- EQ 目前只做内置预设切换，自定义 EQ 曲线未实现
- 手势/贴合度检测/听力配置未实现（需要更多协议与 UI）
- 详情页未做真机验证（2.2.0 编译通过，待装机验证）


## 2.1.6 — 详情页重做 + 响铃/断开真正可用（真机验证）

### 真机定位出的关键教训

**LSPosed 模块更新后，蓝牙进程不会重启**。`com.android.bluetooth` 是个长寿进程，新广播 action 的接收端只有在它重启后才注册。前几版「响铃查找点了没反应、断开没反应」全部因此而来——直到 `svc bluetooth disable/enable` 之后才通。

排查手段：新增 `magicorig_diag_*` 诊断回读通道（写 Settings.Global，`adb settings get` 直读），绕开被 Honor 加密的 logcat。

### 这一轮的修复

| 问题 | 修法 | 验证 |
|---|---|---|
| 响铃查找没声音 | 接管 `AudioUtils.startFindDevice`（荣耀是把手机音频路由到耳机，虚拟设备没路由）→ 改发欢律 `0x0400`；蓝牙进程处理 `ACTION_FIND_BUDS` | ✅ 真机响了，发送回执 `AA 08 00 00 00 04 13 01 00 01` |
| 断开连接无效 | manager 层接管 `toDisConnectDev` → 自己驱动 A2DP/HFP（隐藏 API 用反射） | ✅ `bt_action: profile=1 disconnect → true`，connected→0 |
| 底部两按钮都变「断开连接」 | 上版 bug：把 `more_settings` 也一起改了；现在只改 `to_connect/disconnect` 两个 id | ✅ 「断开连接 + 更多设置」并存 |
| 响铃查找置灰 | 数据级：`ServiceCardInfo.setShowStatus(NORMAL)` | ✅ 可点 |
| 「更多设置」ActivityNotFound | `setClassName(ctx, ...)` 会拼宿主包名 → 改显式包名 | ✅ 能开 |
| 断开后球不消失/亮着 | 在线态改实时查 A2DP；`setIsOnline`/`updateDeviceOnlineStatus` 传真实值 | ✅ 变灰留存 |
| 详情页太简陋 | 重做为 tile 版式：连接态 pill + 三电量环（L/R/仓）+ 噪声控制 + 快捷操作（查找耳机/刷新/蓝牙设置）+ 深色状态栏 | ✅ 截图见 docs/evidence |

### 已知限制

- 「更多设置」优先打开 **QuickBuds**（若已安装），否则打开本模块详情页。
- 详情页的 EQ / 手势 / 游戏模式入口尚未实现（需要补协议层）。
- 响铃依赖耳机固件支持 `0x0400`（Free4 实测支持）。

## 2.1.0 — 动作接管上移到 manager 层

`AudioUtils` 那层 hook 在本机不生效（点击根本没走到），而 `DeviceServiceCardDataMgr` 层稳定生效 → `toDisConnectDev` / `toConnectOfflineDev` / `findDevice` 全部在该层接管。

## 2.0.6 — 数据级修正（连接文案 / 响铃可用）

`genDeviceServiceInfo` 返回前遍历 sections：按真实连接态互换 `to_connect_device` ↔ `disconnect_device`（含文案）；`bsId` 含 Find 的 `ServiceCardInfo` 置 `NORMAL`。


## 2.0.5 — 服务卡为空：LinkDevice 注册表注入（本机构建 `b2.v` → `DeviceProductProfile`）**（已真机验证）**

**现象**：球出现了（2.0.3 修好），但点开显示「暂无服务可流转到其他设备」，没有降噪/电量卡。

**真机诊断**（2.0.4 内置的 `magicorig_diag_*` 回读通道，adb 直读，不依赖日志）：

```
ball     = ok=true prod=00VVD9            ← 球 OK
sections = n=null                         ← genDeviceServiceInfo 返回 null
section  = null                           ← 降噪/电量 section 从未构造
card     = ok=false retCls=null           ← 服务卡本体 null
```

**根因**（反编译本机 controlcenter 实证）：`DeviceServiceCardDataMgr.genDeviceServiceInfo` 第一行

```java
LinkDevice t8 = b2.v.s().t(str);
if (t8 == null) { P1.c.e(TAG, "genDeviceServiceInfo, linkDevice is null"); return null; }
```

本机构建的注册表类是 **`b2.v`**（上游写死的是 `c2.s`，已换名 → 上游那条兜底 hook 一直 CNFE、从未生效）。链路：

```
b2.v.s().t(id) → DeviceProductProfile.getInstance().getDeviceByUdid(id)
                → mOnLineLinkDevicesMap.get(id)     // 我们的设备从未进表 → null
→ genDeviceServiceInfo 返回 null → createDeviceServiceCard 返回 null → 「暂无服务可流转」
```

**修复**（`hook/ControlCenterNoiseHook.kt`）：hook `DeviceProductProfile.getDeviceByUdid(String)`，结果为 null 且 id 是我方 MAC 时：

1. 反射构造 `LinkDevice(mac, "OPPO Enco Free4", Third_EarPhone, mac, false, [], prodId)`
2. 塞进 `mOnLineLinkDevicesMap`（实测 mapSize 4→5）
3. 返回该对象，让 `genDeviceServiceInfo` 继续往下走

**真机验证结果**（Magic7 Pro / MagicOS 11.0.0.110 / Free4）：

| 项 | 结果 |
|---|---|
| 设备球 | ✅ 带名 + 电量图标 |
| 服务卡 | ✅ 打开即显示：电量 100% / L 100% / R 100% / 响铃查找 / 噪声控制 / 去连接 / 更多设置 |
| 降噪三档 | ✅ 点「关闭」→ `magicorig_current_anc=1`；「透传」→ `=2`；「降噪」→ `=5`（均为耳机**回读值**），UI 高亮同步 |
| sections | ✅ n=5，`createEarphoneNoiseSection → true`，`card → DeviceServiceCardView` |

**未验证**：QS 磁贴、SWS 通知电量、断开清理、丹拿版。


## 2.0.3 — 设备球建不出来：`isSelf` 传错导致被 SortUtils 过滤（真机反编译实证）

**现象**：日志 `▶ createDeviceBall(id=…) → NULL(失败)` ×14，两个会话无一成功；列表已注入、布局留位，但球视图工厂返回 null → 空白占位。

**取证**（`adb pull` 本机 `/system/priv-app/DeviceControlCenter/DeviceControlCenter.apk`，jadx 反编译 2743 个类）：

`DeviceBallLayoutManager.createDeviceBall(DeviceInfo, long)` 只有两个 null 出口：

```java
if (!this.gridIndexMap.containsKey(deviceInfo.getId())) return null;   // ① 无轨迹槽位
if (!deviceInfo.isShow()) { ...hidden...; return null; }               // ② 设备被隐藏
```

而 `gridIndexMap` 由 `relayoutRemainingRemoteDeviceBalls()` 重建：

```java
this.gridIndexMap.putAll(SortUtils.DeviceSortUtils(showRemoteDeviceInfoList));
```

`SortUtils.DeviceSortUtils` 的第 100 行：

```java
if (deviceInfo != null && !deviceInfo.isSelf()) { arrayList.add(deviceInfo); }   // 过滤 isSelf
```

**根因**：`DeviceInfo` 7 参构造签名是

```java
(String id, String name, HnDeviceProductType type, int shapeFeature,
 boolean isSelf, boolean isOnline, boolean isShow)     // ← 第 5 个是 isSelf
```

上游传的是 `(mac, name, third, 0, true, false, true)` → **`isSelf = true`** → 我们的条目被 `SortUtils` 当成「本机自己」过滤 → `gridIndexMap` 无槽位 → `createDeviceBall` 返回 null。**这与设备中心版本漂移无关，是参数语义错。**

**修复**（`hook/ControlCenterNoiseHook.kt`）：

1. 构造参数改为 `(mac, name, third, 0, **false**, true, true)`：isSelf=false、isOnline=true、isShow=true
2. 显式 `setIsShow(true)`，不依赖构造参数语义
3. **双保险**：`createDeviceBall` hook 在 `chain.proceed()` 前反射检查 `gridIndexMap`，缺槽位就按 `deviceInfo.getIndex()`（或 `map.size`）补进去，并打日志

**同批落地（FIX-003 其余任务）**：

| 任务 | 状态 |
|---|---|
| 任务1 字段 diff 取证埋点 | ✅ `magicorig_debug_dump=1` 时 dump 球列表每个 DeviceInfo 全字段、ext JSON、createDeviceBall 入参/返回值 |
| 任务1 prodId A/B | ✅ `magicorig_prodid` 可运行期覆盖 prodId（默认 00VVD9），不用改代码 |
| 任务3.1 `c2.s` CNFE | ✅ 降为 debug 日志（sections=5 链路已实证可通，该 hook 仅兜底） |
| 任务3.2 `getDeviceList` CNFE ×3 | ✅ 类缺失时静默跳过 |
| 任务3.3 `MbbApi.getSmartConnect` | ⏳ 留待 FIX-004（详情页链路，次优先） |

**验证（真机）**：装新版 → 强停 controlcenter（或重启进程）→ 打开设备中心。日志判据：

```
★ gridIndexMap 补槽位 id=<MAC> → index=…      # 双保险生效时会打印
▶ createDeviceBall(id=<MAC>) → OK prodId=00VVD9
★ setIsOnline(true) on adapter
```

目视：球带名出现 → 点开出服务卡 → 降噪三档可切、电量 ≈100%。


## 2.0.2 — 触发链修复：广播 action 写错 + MAC 发布前移 + 第三触发源

**现象**（真机日志）：蓝牙进程重启后 `★ A2DP 系统广播 receiver 已注册` 出现，但 `A2DP 广播 state=` 一条都没有；`magicorig_mac` 始终为空 → 设备中心仍回落幽灵 MAC → 球空白。

### 根因 1（确诊，上游 bug）

`HeadsetStateDispatcher.kt` 注册 IntentFilter 时手写了字面量：

```
android.bluetooth.a2dp.profile.connection.action.CONNECTION_STATE_CHANGED   ← 多了 .connection
```

SDK 真值（`BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`）是：

```
android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED
```

模块注册的是一个**不存在的 action**，接收器永远收不到广播。上游作者真机上主 hook 一直正常，所以这个兜底从未被真正用过，bug 一直没被发现。

**修法**：改用 SDK 常量 `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`，不再手写字符串。

### 根因 2（未确诊，用冗余绕开）

`A2dpService.handleConnectionStateChanged` 的安装日志是 `Log.d`（LSPosed 默认过滤），其回调里的 `stateChange state=` 判据从未被确认。荣耀定制蓝牙栈上该方法是否还走连接状态分发不确定。**不再押注单点触发**，改为三源冗余。

### 修复清单

| # | 改动 | 文件 |
|---|---|---|
| 1 | 广播 action 改用 SDK 常量 | `hook/HeadsetStateDispatcher.kt` |
| 2 | 新增 `DeviceIdentity.publish()`：**设备识别命中即发布真实 MAC**（`isOriGPod`/`isOriGAddress` 三处调用），不再依赖 SPP 连接成功；同值内存去重 | `utils/DeviceIdentity.kt`（新增）、`hook/HeadsetStateDispatcher.kt`、`hook/BluetoothUpstreamHeadsetHook.kt` |
| 3 | **第三触发源**：hook 注册后 +5s / +20s 枚举 `bondedDevices`，命中且 A2DP 已连接则补一次 `connectPod` | `hook/HeadsetStateDispatcher.kt` |
| 4 | 日志升格：`hook installed`、`A2DP state:` 由 `Log.d` → `Log.i`；广播命中失败时补 `A2DP 广播忽略 dev=... name=...` | 同上 |
| 5 | `createDeviceServiceCard` 观察钩子在无此类进程（SystemUI）**静默跳过**，不再打 CNFE 的 W 日志 | `hook/ControlCenterNoiseHook.kt` |

`connectPod` 内的 `magicorig_mac` 写入保留，作为双保险。

### 验证（真机，按序）

1. 装新版 → 开关一次蓝牙（让蓝牙进程加载新代码）→ 耳机入仓关盖 10 秒 → 开盖重连
2. LSPosed 日志应依次出现：
   - `A2DP 广播 state=1/2 dev=<MAC>`
   - `★ A2DP 广播 CONNECTED → 触发 connectPod` 或 `★ 回补触发 connectPod`
   - `已发布 magicorig_mac=<MAC>` / `已写入 magicorig_mac=<MAC>`
3. 打开设备中心：出现带名设备球
4. 若广播仍无 state 行：导出 `com.android.bluetooth` 进程完整模块日志回传

**未真机验证**：本版仅编译 + 14 条单测通过。


## 2.0.1 — 修复「设备中心空位但不显示设备」（幽灵 MAC）

**现象**：LSPosed 已启用、5 个作用域全勾，连上 Free4 后设备中心多出一个**空位**，没有任何设备球，也无法控制耳机。

**根因**：上游 MagicOriG 把作者自己那副耳机的 MAC 写死成常量 `18:5C:A1:52:10:36`，整条注入链以 MAC 为主键（`ControlCenterNoiseHook.kt` 里 22 处引用）。移植时只改了名字没改 MAC 来源，于是注入进设备中心的是一台**本机不存在的幽灵设备**：

- 列表层注入成功 → 布局给它留了位置 → 你看到的「空位」
- 这台 MAC 在本机蓝牙里不存在 → 渲染与信息链拿不到数据 → 球出不来
- 真实 Free4 的 MAC 完全不同 → 从没被注入链认领 → 也就无法控制

**修复**（沿用模块已有的 Settings.Global 跨进程桥）：

| 侧 | 改动 |
|---|---|
| 蓝牙进程 `pods/RfcommController.kt` | 连接时写 `Settings.Global["magicorig_mac"] = device.address`；断开**不清除**，保留上次值供设备中心先行注入 |
| 设备中心 `hook/ControlCenterNoiseHook.kt` | 新增 `ourMac()`：读 Global 值（5 s 缓存），为空才回落上游常量并告警一次；新增 `isOurMac()` 做大小写不敏感比较；22 处引用全部改走这两个函数 |

**行为说明**：耳机从未连接过时 `magicorig_mac` 为空 → 回落常量 → 与修复前一致（不会更坏）。**连接一次后自动纠正**为真实 MAC，无需手工操作。

**验证**（真机，日志驱动）：

```
adb shell settings get global magicorig_mac        # 期望 = 你耳机的真实 MAC
# LSPosed 日志过滤 ControlCenterNoiseHook：
★ 设备列表已注入 OPPO Enco Free4                     # 注入 JSON 里的 MAC 应等于上面的值
★ 球列表已注入 OPPO Enco Free4
▶ createDeviceCard ... 匹配OUR=true mac=XX:XX:...
★ ballView.isOnline() false → true
```

**已知未验证**：本修复只做了编译与单测，**没有真机确认**球是否真的渲染出来。

## 2.0.0

首次发布：MagicOriG fork + 欢律（HeyMelody）协议层移植（`pods/hem/`）、设备身份改名、启动图标、单元测试、文档。
