# MagicXM4 计划书（暂定名，可改）

> 目标：让小乐的**索尼 WH-1000XM4**（头戴式）在荣耀 MagicOS 11 的设备中心里像荣耀自家头戴耳机一样用——设备球、服务卡切降噪、看电量、详情页。路线与 MagicFree4 相同：LSPosed 模块，荣耀侧冒充原生设备，协议层换成索尼私有协议。
> 执行：DSH。基线机：荣耀 Magic7 Pro（PTP-AN00）/ MagicOS 11.0.0.110 / KernelSU + LSPosed（与 MagicFree4 同一台）。

## 为什么这次起点高

MagicFree4 已收官（v2.9.0 正式版），它的荣耀侧注入链是真机逐环验证过的。本项目**从 MagicFree4 代码库 fork**，只换两层：

1. **设备身份**：耳塞（OPPO Enco Free4）→ 头戴耳机（WH-1000XM4）
2. **协议层**：欢律（pods/hem）→ 索尼协议（新建 pods/sony）

MagicFree4 踩过的坑**不许重踩**（都是已结案的 FIX 单）：
- MAC 必须蓝牙进程识别命中时写 `Settings.Global["magicorig_mac"]` 动态发布，禁止写死（FIX-001）
- A2DP 广播 action 用系统常量 `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`，禁止字面量（FIX-002：上游曾多写 `.connection` 导致兜底永不触发）
- 触发做三源冗余：A2dpService hook + 广播 + 已连接回补枚举
- 伪造 DeviceInfo 构造第 5 参 isSelf=false 且显式 setIsShow(true)，否则 createDeviceBall 返回 null、球位是死占位（FIX-003）
- 本机构建注册表类是 `b2.v` 不是上游 `c2.s`；hook 点位按特征定位，不迷信类名
- 服务卡要能流转需向注册表在线表补 LinkDevice（DeviceProductProfile.getDeviceByUdid 路线，见 MagicFree4 v2.0.5 实现）

## 索尼协议层（pods/sony）

参考实现已克隆在 `~/workspace/SonyHeadphonesClient`（Plutoberth，GPL-3.0，1362 star / 114 fork）：

- **传输**：蓝牙 RFCOMM，服务 UUID `96CC203E-5068-46ad-B32D-E316F5E069BA`（Constants.h:11，源码实证）
- **帧格式**：`>`(0x3E) 起始、`<`(0x3C) 结束，载荷特殊字节用 0x3D 转义，含 1 字节校验和；编解码见 `Client/CommandSerializer.cpp`、命令层见 `Client/Headphones.cpp/.h`
- **XM4 支持状态**：上游标注 XM4 "Partially works"（电量、降噪开关等基础功能可用），114 个 fork 中有补全 XM4 的实现；移植时以 XM4 实测为准，必要时对照 issue #29 的 XM4 记录与活跃 fork
- **核心命令**（v1 范围）：降噪/环境声模式设置与查询（NC_ASM 类命令）、电量查询、连接初始化握手
- **ANC 档位映射**（荣耀服务卡三档 ↔ 索尼语义）：
  - 荣耀「关闭」→ 索尼 NC 关闭、环境声关闭
  - 荣耀「通透」→ 索尼环境声开启（等级默认 20，可做设置项 1–20）
  - 荣耀「降噪」→ 索尼 NC 开启（环境声关闭）
  - 索尼的风噪降低、自适应环境声 v1 不映射，留详情页开关（可选）
- **电量**：XM4 只有单一总电量（无左右耳、无耳机仓）。荣耀侧喂数时按头戴品类处理：球/卡电量用总电量；若沿用双耳喂入路径，L/R 同值并注明，ability 位按单电量设备设（以 MagicFree4 的喂入实现为参照改）

## 与 Free4 的功能取舍差异

| 功能 | Free4 | XM4 处理 |
|---|---|---|
| 设备球/服务卡 | ✓ | ✓（头戴品类身份） |
| 降噪切换 | 四档 | 三档映射（见上） |
| 电量 | 双耳+仓 | 单总电量 |
| 响铃查找 | ✓ | **无此功能**（XM4 不支持响铃），卡上该入口隐藏/置灰处理，不要点了没反应 |
| 详情页 EQ | 自定义 EQ | XM4 支持 EQ 预设/自定义，可做（二期） |
| 手势 | 触控手势读写 | XM4 是触控面板手势（不可编程映射为主），一期不做手势页，详情页只保留概览+噪声+电量 |
| 设备双连 | ✓ | XM4 本身支持多点连接，但控制通道在多点下可能被占，二期评估 |

## 身份适配（荣耀侧）

- DeviceInfo 品类从耳塞改头戴：设备类型枚举、布局（REMOTE 布局里头戴对应的 config）、图标资源都按头戴走；prodId 选**本机构建 controlcenter 产品配置里真实存在的荣耀头戴耳机型号**（沿用 MagicFree4 FIX-003 的字段 dump 方法确认，不要凭空编 prodId）
- 设备名 "Sony WH-1000XM4"（显示名可讨论用原文还是"WH-1000XM4"）
- 包名：`io.github.yuxiximyxx.magicxm4`；广播 action、Settings.Global 键名沿用 MagicFree4 约定（magicorig_* 前缀可保留，减少改动面；或统一改 magicxm4_*，二选一，计划评审时定）

## 阶段划分

1. **阶段 0 基线**：从 MagicFree4 v2.9.0 fork 改名建仓；换头戴身份与设备名；协议层先留 Free4 的假实现/空实现，验证球+卡在头戴品类下能渲染（复用已通链，预期最快）
2. **阶段 1 索尼协议移植**：pods/sony 帧编解码 + 握手 + 电量/ANC 查询，配 JVM 单测（帧样本可从 SonyHeadphonesClient 的测试/抓包记录构造）；真机 SPP 连通、能读回电量与当前 ANC 档
3. **阶段 2 桥接映射**：服务卡三档 ↔ 索尼命令互通（点卡耳机真响应、耳机侧按键切档后卡上高亮回读同步）；电量喂入球与卡
4. **阶段 3 详情页裁剪**：概览（连接态+电量）+ 噪声控制；响铃等不存在的入口移除；EQ 预设二期
5. **阶段 4 收尾发布**：README/酷安文案、GPL-3.0 LICENSE + NOTICE（MagicOriG/MagicFree4/QuickBuds/SonyHeadphonesClient 四方归属）、正式版 Release

## 验收清单（小乐真机）

- [ ] 设备中心出现带名头戴球，点得动、能换位（不是死占位）
- [ ] 服务卡三档可切且 XM4 真实响应（降噪/通透体感变化）
- [ ] 耳机上触控切档后，卡上高亮能回读同步
- [ ] 球与卡电量与 Sony 官方 App 显示 ±5% 内
- [ ] 断开后球变灰/自清，重连自动恢复
- [ ] 与 Free4 的 MagicFree4 模块共存不冲突（两台设备各归各的 MAC 桥；注意 SPP 抢占与 Settings.Global 键名别互相覆盖——若沿用同键，必须按 MAC 区分或两模块协商，这点阶段 0 就要定）

## 风险

- **与 MagicFree4 共存**是本项目特有风险：两个模块 hook 同一批进程、写同一批 Settings.Global 键。阶段 0 必须先定键名/作用域隔离方案再动代码
- XM4 多点连接时控制通道归属：若 XM4 同时连着电脑，手机侧 SPP 命令可能无响应，联调时先单连手机
- Sony 官方 App（Sound Connect）若在运行可能占用 RFCOMM，联调环境保持其未运行
- 索尼协议 XM4 与 XM3 有细微差异（"Partially works" 的由来），命令以 XM4 真机回读验证为准，不照 XM3 文档想当然

---
计划书 v1，2026-11-02。执行中每阶段完成打勾回传；荣耀侧链路问题先查 MagicFree4 的 FIX-001/002/003 再开新单。
