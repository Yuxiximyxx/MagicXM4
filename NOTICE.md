# NOTICE — 第三方作品与许可归属

MagicXM4 是若干件上游作品的下游衍生品。本文件记录归属，**不得删除**。

一句话版本：**本项目整体以 GPL-3.0 发布**，因为 `pods/sony/` 是从
SonyHeadphonesClient（GPL-3.0）移植的衍生作品；荣耀侧注入框架来自 MagicOriG（MIT，兼容）。

---

## 1. MagicOriG（MIT）— 荣耀侧注入框架

- 上游：https://github.com/yingjfd/MagicOriG
- 用途：项目的基座（MagicOS 侧 hook 框架、设备中心注入链、广播契约、模块 App 骨架）。
- 许可：MIT（上游 README §License 声明）。
- 版权归原作者（yingjfd）所有。

```
MIT License

Copyright (c) yingjfd (https://github.com/yingjfd/MagicOriG)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. MagicFree4（GPL-3.0）— 直接 fork 来源

- 上游：https://github.com/Yuxiximyxx/MagicFree4
- 用途：本仓库从它 fork，继承其荣耀侧全部已真机验证的注入链、
  广播契约、`magicxm4_*` 跨进程键约定与详情页 UI。
- 许可：GPL-3.0（它本身是 MagicOriG + QuickBuds 的衍生品）。
- 本项目把它当作上游并保留其署名。

> `pods/hem/` 目录是 MagicFree4 遗留的欢律协议实现。WH-1000XM4 不使用它，
> 保留仅为减少本次 fork 的改动面（部分枚举仍被 UI/桥接代码引用）。

---

## 3. SonyHeadphonesClient（GPL-3.0）— 索尼协议层移植来源

- 上游：https://github.com/Plutoberth/SonyHeadphonesClient
- 用途：`pods/sony/` 的帧编解码与命令构造，是它的 Kotlin 衍生作品。

  | 本仓库文件 | 上游来源 |
  |---|---|
  | `pods/sony/SonyWire.kt` | `Client/CommandSerializer.cpp`、`Client/ByteMagic.cpp`、`Client/Constants.h` |
  | `pods/sony/SonyCommands.kt` | `Client/Headphones.cpp`、`Client/Constants.h` |
  | `pods/sony/SonyState.kt` | 应答解析（我方设计，上游未提供解析层） |

- 许可：**GPL-3.0**。全文见仓库根 `LICENSE`。
- 版权归其作者（Plutoberth）及贡献者所有。

### 移植时修正的上游缺陷

1. `unpackBtMessage` 用**转义后**的字节做校验和与字段读取，载荷里一旦出现
   `0x3C/0x3D/0x3E` 必然解析错误 —— 本实现改为先去转义再解析。
2. `_waitForAck` 遇到「连续两个 START 而无 END」直接抛异常断开连接 ——
   本实现改为就地重同步，跳过字节走 `onDiscard` 日志。

---

## 4. Gadgetbridge（AGPL-3.0）— **仅协议事实参考，未复制代码**

- 上游：https://github.com/Freeyourgadget/Gadgetbridge
- 用途：其 Sony V1 协议实现（`service/devices/sony/headphones/protocol/impl/v1/`）
  是第二个独立参考。**命令编号、字节偏移、常量数值属于互操作所需的协议事实，
  本项目据此自行重写了 Kotlin 实现，未复制任何 Gadgetbridge 源代码。**

  | 从 Gadgetbridge 取得的协议事实 | 用在哪 |
  |---|---|
  | 命令号表（电量 `0x10/0x11/0x13`、降噪 `0x66/0x67/0x68/0x69`、固件 `0x04/0x05`） | `SonyCommands.kt` |
  | 电量请求/应答字节布局 | `SonyCommands.kt`、`SonyState.kt` |
  | 降噪/环境声请求/应答字节布局 | `SonyCommands.kt`、`SonyState.kt` |
  | `MessageType.COMMAND_1 = 0x0C` 与 SonyHeadphonesClient 的 `DATA_MDR = 12` 互证 | `SonyCommands.kt` |

- 许可：AGPL-3.0。因**未包含其代码**，本项目不因此变成 AGPL；
  若日后复制其任何代码，本项目整体必须改为 AGPL-3.0。
- 版权归其作者（José Rebelo 等）所有。

---

## 5. 合规要点

1. 分发本项目的任何二进制或源码，必须同时提供 GPL-3.0 全文（见 `LICENSE`）与完整源代码。
2. 不得移除 `pods/sony/*.kt` 内的 `SPDX-License-Identifier: GPL-3.0-only` 头部与来源注释。
3. MagicOriG 的 MIT 声明必须保留（本文件即为其载体）。
4. 上游商标（Sony、WH-1000XM4、荣耀、MagicOS、OPPO、Enco 等）归各自权利人所有，
   本项目与其无任何隶属或授权关系。
