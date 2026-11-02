/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * SonyWire / SonyCommands / SonyParser 的纯 JVM 单测。
 *
 * 期望值来源：
 *   · 帧层（3E/3C/3D + 校验和）—— SonyHeadphonesClient 的 C++ 实现，手算推导写在注释里；
 *   · 命令号与载荷偏移 —— Gadgetbridge（AGPL-3.0，仅取协议事实，未复制代码）与
 *     SonyHeadphonesClient 交叉验证后的结论，见 SonyCommands / SonyState 头部注释。
 * 所有断言都是**手算的十六进制字面量**，不是「跑一遍把输出抄回来」——
 * 后者只能证明代码没变，不能证明代码对。
 *
 * 帧格式回顾（CommandSerializer.cpp）：
 *   3E | ESCAPE(DATA_TYPE SEQ LEN[4]BE DATA CHECKSUM) | 3C
 *   CHECKSUM = 内容区所有字节（不含自己）之和 & 0xFF，转义前计算。
 */
package io.github.yuxiximyxx.magicxm4.pods.sony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.yuxiximyxx.magicxm4.pods.sony.SonyCommands.BatteryType
import io.github.yuxiximyxx.magicxm4.pods.sony.SonyCommands.DataType
import io.github.yuxiximyxx.magicxm4.pods.sony.SonyCommands.NcAsmLayout
import io.github.yuxiximyxx.magicxm4.pods.sony.SonyCommands.NoiseMode

class SonyWireTest {

    // ── 小工具 ──────────────────────────────────────────────────

    /** 用十六进制字面量拼 ByteArray，避免满屏 `.toByte()`。 */
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** 帧 → 去掉 START/END 并转义还原后的内容区。 */
    private fun innerOf(frame: ByteArray): ByteArray =
        SonyWire.unescape(frame.copyOfRange(1, frame.size - 1))

    /** 直接用「命令号 + 载荷」解析，省掉组帧细节（帧层另有专门用例）。 */
    private fun event(payload: ByteArray, seq: Int = 0, type: Int = 12): SonyEvent =
        SonyParser.parse(SonyWire.Message(type, seq, payload))

    // ── 1. 帧层往返 ─────────────────────────────────────────────

    @Test
    fun `build 后 parse 得到等价消息`() {
        val payload = bytes(0x68, 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00)
        val msg = SonyWire.Message(type = 12, seq = 7, payload = payload)

        val frame = SonyWire.build(msg)
        val parsed = SonyWire.parse(frame)

        assertNotNull(parsed)
        // Message.equals 是 contentEquals，所以这里是真正的字节级往返。
        assertEquals(msg, parsed)
        assertEquals(12, parsed!!.type)
        assertEquals(7, parsed.seq)
        assertEquals(payload.size, parsed.payload.size)
    }

    @Test
    fun `空载荷也能往返（ACK 形态）`() {
        val msg = SonyWire.Message(type = 1, seq = 0, payload = ByteArray(0))
        assertEquals(msg, SonyWire.parse(SonyWire.build(msg)))
    }

    // ── 2. 转义 ─────────────────────────────────────────────────

    @Test
    fun `载荷含 3C 3D 3E 时按 ESC 加 原值减 0x10 转义并能还原`() {
        // type=12(0x0C), seq=1, payload = 3C 3D 3E
        //   内容区（转义前）：0C 01 00 00 00 03 3C 3D 3E C7
        //   CHECKSUM = 0x0C+0x01+0x00+0x00+0x00+0x03+0x3C+0x3D+0x3E
        //            = 12+1+0+0+0+3+60+61+62 = 199 = 0xC7
        //   转义后：0C 01 00 00 00 03 3D 2C 3D 2D 3D 2E C7
        //   整帧：3E 0C 01 00 00 00 03 3D 2C 3D 2D 3D 2E C7 3C（15 字节）
        val msg = SonyWire.Message(type = 12, seq = 1, payload = bytes(0x3C, 0x3D, 0x3E))

        val expectedFrame = bytes(
            0x3E, 0x0C, 0x01, 0x00, 0x00, 0x00, 0x03,
            0x3D, 0x2C, 0x3D, 0x2D, 0x3D, 0x2E, 0xC7, 0x3C,
        )
        assertEquals(SonyWire.hex(expectedFrame), SonyWire.hex(SonyWire.build(msg)))
        assertEquals(msg, SonyWire.parse(expectedFrame))
        assertEquals("3C 3D 3E", SonyWire.hex(SonyWire.parse(expectedFrame)!!.payload))
    }

    @Test
    fun `单独验证 escape unescape 是互逆的`() {
        val raw = bytes(0x00, 0x2C, 0x3C, 0x3D, 0x3E, 0x7F, 0xFF)
        val escaped = SonyWire.escape(raw)

        // 三个特殊字节各膨胀成两字节，其余原样：长度 7 + 3 = 10。
        assertEquals(10, escaped.size)
        assertEquals("00 2C 3D 2C 3D 2D 3D 2E 7F FF", SonyWire.hex(escaped))
        assertEquals(SonyWire.hex(raw), SonyWire.hex(SonyWire.unescape(escaped)))
    }

    @Test
    fun `畸形转义返回 null 而不是抛异常`() {
        assertNull(SonyWire.unescapeOrNull(bytes(0x01, 0x3D)))
        assertNull(SonyWire.unescapeOrNull(bytes(0x01, 0x3D, 0x2B)))
    }

    // ── 3. checksum ─────────────────────────────────────────────

    @Test
    fun `checksum 与 C++ _sumChecksum 手算一致（LEGACY 关闭布局）`() {
        // 用例：SonyCommands.setOff(seq = 0, layout = LEGACY_DUAL_SINGLE)
        //   payload = 68 02 00 01 00 01 00 FF（8 字节）
        //   内容区（不含 checksum）= 0C 00 | 00 00 00 08 | payload
        //   逐项相加：
        //     0x0C(12) + 0x00 + 0x00 + 0x00 + 0x00 + 0x08(8)
        //   + 0x68(104) + 0x02(2) + 0x00 + 0x01(1) + 0x00 + 0x01(1) + 0x00 + 0xFF(255)
        //   = 12 + 8 + 104 + 2 + 1 + 1 + 255 = 383 = 0x17F
        //   383 & 0xFF = 0x7F
        val inner = bytes(0x0C, 0x00, 0x00, 0x00, 0x00, 0x08, 0x68, 0x02, 0x00, 0x01, 0x00, 0x01, 0x00, 0xFF)
        assertEquals(0x7F, SonyWire.checksum(inner))

        // 同一条命令的完整帧：3E | 内容区 | 7F | 3C
        val expectedFrame = bytes(
            0x3E, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x08,
            0x68, 0x02, 0x00, 0x01, 0x00, 0x01, 0x00, 0xFF,
            0x7F, 0x3C,
        )
        val built = SonyWire.build(SonyCommands.setOff(seq = 0, layout = NcAsmLayout.LEGACY_DUAL_SINGLE))
        assertEquals(SonyWire.hex(expectedFrame), SonyWire.hex(built))

        // 反过来：把帧尾 CHECKSUM 改掉必须被拒。
        val corrupted = built.copyOf()
        corrupted[corrupted.size - 2] = 0x7E
        assertNull(SonyWire.parse(corrupted))
    }

    @Test
    fun `checksum 手算（WIND 降噪布局完整帧）`() {
        // payload = 68 02 11 02 02 01 00 00
        // 内容区 = 0C 00 | 00 00 00 08 | payload
        // CHECKSUM = 12 + 8 + 104 + 2 + 17 + 2 + 2 + 1 + 0 + 0 = 148 = 0x94
        val expectedFrame = bytes(
            0x3E, 0x0C, 0x00, 0x00, 0x00, 0x00, 0x08,
            0x68, 0x02, 0x11, 0x02, 0x02, 0x01, 0x00, 0x00,
            0x94, 0x3C,
        )
        assertEquals(
            SonyWire.hex(expectedFrame),
            SonyWire.hex(SonyWire.build(SonyCommands.setNoiseCancelling(seq = 0))),
        )
    }

    @Test
    fun `checksum 溢出按 8 位截断`() {
        // FF + FF + FF = 765 = 0x2FD -> 0xFD
        assertEquals(0xFD, SonyWire.checksum(bytes(0xFF, 0xFF, 0xFF)))
    }

    // ── 4. Framer ───────────────────────────────────────────────

    @Test
    fun `半帧分两次喂能拼出完整帧`() {
        val frame = SonyWire.build(SonyCommands.setOff(seq = 0, layout = NcAsmLayout.LEGACY_DUAL_SINGLE))
        val framer = SonyWire.Framer()

        val first = framer.append(frame, 5)      // 3E 0C 00 00 00，只有 START
        assertTrue("半帧不该产出帧", first.isEmpty())

        val second = framer.append(frame.copyOfRange(5, frame.size), frame.size - 5)
        assertEquals(1, second.size)
        assertEquals(SonyWire.hex(innerOf(frame)), SonyWire.hex(second[0]))
        assertEquals(SonyWire.parse(frame), SonyWire.parse(second[0]))
    }

    @Test
    fun `粘包两帧能切成两帧`() {
        val a = SonyWire.build(SonyCommands.setOff(seq = 0))
        val b = SonyWire.build(SonyCommands.batteryRequest(seq = 1))
        val framer = SonyWire.Framer()

        val frames = framer.append(a + b, a.size + b.size)
        assertEquals(2, frames.size)
        assertEquals(SonyWire.hex(innerOf(a)), SonyWire.hex(frames[0]))
        assertEquals(SonyWire.hex(innerOf(b)), SonyWire.hex(frames[1]))
    }

    @Test
    fun `帧前带噪声字节时能吃掉噪声并切出帧`() {
        val noise = bytes(0x00, 0x12, 0x34, 0x55)
        val frame = SonyWire.build(SonyCommands.setNoiseCancelling(seq = 3))
        val discarded = ArrayList<String>()

        val framer = SonyWire.Framer().also { it.onDiscard = { d -> discarded += SonyWire.hex(d) } }
        val frames = framer.append(noise + frame, noise.size + frame.size)

        assertEquals(1, frames.size)
        assertEquals(SonyWire.hex(innerOf(frame)), SonyWire.hex(frames[0]))
        assertEquals(listOf("00 12 34 55"), discarded)
    }

    @Test
    fun `纯噪声（没有 START）全部走 onDiscard`() {
        val discarded = ArrayList<String>()
        val framer = SonyWire.Framer().also { it.onDiscard = { d -> discarded += SonyWire.hex(d) } }

        assertTrue(framer.append(bytes(0x01, 0x02, 0x03), 3).isEmpty())
        assertEquals(listOf("01 02 03"), discarded)
    }

    @Test
    fun `半帧后紧跟新 START 时重同步到新帧`() {
        val orphan = bytes(0x3E, 0x0C, 0x00)
        val good = SonyWire.build(SonyCommands.setOff(seq = 9))
        val framer = SonyWire.Framer()

        val frames = framer.append(orphan + good, orphan.size + good.size)
        assertEquals(1, frames.size)
        assertEquals(SonyWire.parse(good), SonyWire.parse(frames[0]))
    }

    @Test
    fun `两帧之间夹着含 0x3E 的噪声时仍能恢复`() {
        // 上游 _waitForAck 在这种情况下会抛 "Multiple start markers" 并断连；
        // 本实现丢掉噪声段、从新 START 重新对齐。
        val a = SonyWire.build(SonyCommands.setOff(seq = 1))
        val b = SonyWire.build(SonyCommands.setNoiseCancelling(seq = 2))
        val noise = bytes(0x3E, 0x00, 0x11)
        val framer = SonyWire.Framer()

        val frames = framer.append(a + noise + b, a.size + noise.size + b.size)
        assertEquals(2, frames.size)
        assertEquals(SonyWire.hex(innerOf(a)), SonyWire.hex(frames[0]))
        assertEquals(SonyWire.hex(innerOf(b)), SonyWire.hex(frames[1]))
    }

    @Test
    fun `噪声里凑出一个假帧时被丢弃但后面的真帧不受影响`() {
        val a = SonyWire.build(SonyCommands.setOff(seq = 1))
        val fake = bytes(0x3E, 0x00, 0x3C)       // 形状像帧，内容区只有 1 字节
        val b = SonyWire.build(SonyCommands.setNoiseCancelling(seq = 2))
        val discarded = ArrayList<String>()
        val framer = SonyWire.Framer().also { f -> f.onDiscard = { d -> discarded += SonyWire.hex(d) } }

        val frames = framer.append(a + fake + b, a.size + fake.size + b.size)
        assertEquals(2, frames.size)
        assertEquals(SonyWire.hex(innerOf(b)), SonyWire.hex(frames[1]))
        assertEquals(listOf("3E 00 3C"), discarded)
    }

    @Test
    fun `校验和错误的帧被丢弃且不产出`() {
        val frame = SonyWire.build(SonyCommands.setOff(seq = 0)).copyOf()
        frame[frame.size - 2] = 0x00
        val discarded = ArrayList<String>()
        val framer = SonyWire.Framer().also { d -> d.onDiscard = { discarded += SonyWire.hex(it) } }

        assertTrue(framer.append(frame, frame.size).isEmpty())
        assertEquals(1, discarded.size)
        assertEquals(SonyWire.hex(frame), discarded[0])
    }

    @Test
    fun `模糊测试：随机帧加随机分片不丢帧不串帧`() {
        val rnd = kotlin.random.Random(20261102)
        repeat(200) { round ->
            val msgs = (0 until rnd.nextInt(1, 5)).map {
                SonyWire.Message(
                    type = rnd.nextInt(0, 46),
                    seq = rnd.nextInt(0, 256),
                    payload = ByteArray(rnd.nextInt(0, 24)) { rnd.nextInt(0, 256).toByte() },
                )
            }
            val expected = msgs.map { SonyWire.hex(innerOf(SonyWire.build(it))) }
            val stream = msgs.fold(ByteArray(0)) { acc, m -> acc + SonyWire.build(m) }
            val framer = SonyWire.Framer()
            val got = ArrayList<String>()
            var pos = 0
            while (pos < stream.size) {
                // 1..8 字节一片，刻意切断转义对
                val chunk = minOf(rnd.nextInt(1, 9), stream.size - pos)
                framer.append(stream.copyOfRange(pos, pos + chunk), chunk).forEach { got += SonyWire.hex(it) }
                pos += chunk
            }
            assertEquals("第 $round 轮帧数不符", expected, got)
        }
    }

    // ── 5. 请求构造（命令号 + 载荷）──────────────────────────────

    @Test
    fun `电量请求载荷 10 00`() {
        assertEquals("10 00", SonyWire.hex(SonyCommands.batteryRequest(seq = 0).payload))
        // 完整帧手算：0C 00 | 00 00 00 02 | 10 00
        //   CHECKSUM = 12+0+0+0+0+2+16+0 = 30 = 0x1E
        assertEquals(
            "3E 0C 00 00 00 00 02 10 00 1E 3C",
            SonyWire.hex(SonyWire.build(SonyCommands.batteryRequest(seq = 0))),
        )
        assertEquals(
            "10 01",
            SonyWire.hex(SonyCommands.batteryRequest(seq = 0, batteryType = BatteryType.DUAL).payload),
        )
        assertEquals(
            "10 02",
            SonyWire.hex(SonyCommands.batteryRequest(seq = 0, batteryType = BatteryType.CASE).payload),
        )
    }

    @Test
    fun `降噪查询载荷 66 02`() {
        assertEquals("66 02", SonyWire.hex(SonyCommands.queryNoiseState(seq = 0).payload))
    }

    @Test
    fun `固件请求载荷 04 02`() {
        assertEquals("04 02", SonyWire.hex(SonyCommands.firmwareRequest(seq = 0).payload))
        // 完整帧：0C 00 | 00 00 00 02 | 04 02 -> CHECKSUM = 12+2+4+2 = 20 = 0x14
        assertEquals(
            "3E 0C 00 00 00 00 02 04 02 14 3C",
            SonyWire.hex(SonyWire.build(SonyCommands.firmwareRequest(seq = 0))),
        )
    }

    @Test
    fun `INIT 请求载荷 00 00`() {
        assertEquals("00 00", SonyWire.hex(SonyCommands.initRequest(seq = 0).payload))
    }

    // ── 6. 设置载荷：两种布局 ───────────────────────────────────

    @Test
    fun `降噪设置载荷两种布局逐字节对照`() {
        // Gadgetbridge（风噪能力布局）：68 02 11 02 02 01 00 00
        assertEquals(
            "68 02 11 02 02 01 00 00",
            SonyWire.hex(SonyCommands.setNoiseCancelling(seq = 0).payload),
        )
        // SonyHeadphonesClient（旧 dualSingle 布局）：68 02 11 01 02 01 00 00
        // 下标 3 由 02 变 01，下标 4 恰好也是 02（level=0 -> dualSingle=DUAL）
        assertEquals(
            "68 02 11 01 02 01 00 00",
            SonyWire.hex(
                SonyCommands.setNoiseCancelling(seq = 0, layout = NcAsmLayout.LEGACY_DUAL_SINGLE).payload,
            ),
        )
    }

    @Test
    fun `环境声设置载荷两种布局逐字节对照`() {
        // 风噪能力布局：下标 4 是模式位（环境声 = 00），下标 7 是等级
        assertEquals(
            "68 02 11 02 00 01 00 0F",
            SonyWire.hex(SonyCommands.setAmbientSound(seq = 0, level = 15).payload),
        )
        // 旧布局：下标 4 是 dualSingle（等级 15 -> OFF = 00）
        assertEquals(
            "68 02 11 01 00 01 00 0F",
            SonyWire.hex(
                SonyCommands.setAmbientSound(seq = 0, level = 15, layout = NcAsmLayout.LEGACY_DUAL_SINGLE).payload,
            ),
        )
        // 等级 1 在旧布局下 dualSingle = SINGLE(01)，这一档最能区分两种布局
        assertEquals(
            "68 02 11 01 01 01 00 01",
            SonyWire.hex(
                SonyCommands.setAmbientSound(seq = 0, level = 1, layout = NcAsmLayout.LEGACY_DUAL_SINGLE).payload,
            ),
        )
        assertEquals(
            "68 02 11 02 00 01 00 01",
            SonyWire.hex(SonyCommands.setAmbientSound(seq = 0, level = 1).payload),
        )
    }

    @Test
    fun `关闭载荷两种布局逐字节对照`() {
        // Gadgetbridge 关闭时等级字节写 0x00
        assertEquals("68 02 00 02 00 01 00 00", SonyWire.hex(SonyCommands.setOff(seq = 0).payload))
        // SonyHeadphonesClient 关闭时等级字节写 0xFF（ASM_LEVEL_DISABLED）
        assertEquals(
            "68 02 00 01 00 01 00 FF",
            SonyWire.hex(SonyCommands.setOff(seq = 0, layout = NcAsmLayout.LEGACY_DUAL_SINGLE).payload),
        )
    }

    @Test
    fun `风噪降低载荷`() {
        assertEquals(
            "68 02 11 02 01 01 00 00",
            SonyWire.hex(SonyCommands.setWindNoiseReduction(seq = 0).payload),
        )
        // 旧布局没有风噪这一档：等级 0 -> dualSingle=DUAL(02)，设备只会理解成降噪
        assertEquals(
            "68 02 11 01 02 01 00 00",
            SonyWire.hex(
                SonyCommands.setWindNoiseReduction(seq = 0, layout = NcAsmLayout.LEGACY_DUAL_SINGLE).payload,
            ),
        )
    }

    @Test
    fun `聚焦人声写入下标 6`() {
        // 等级 15 > 2，voice 允许保留
        assertEquals(
            "68 02 11 02 00 01 01 0F",
            SonyWire.hex(SonyCommands.setAmbientSound(seq = 0, level = 15, focusOnVoice = true).payload),
        )
        // 等级 2 不满足 MIN_VOICE_FOCUS_STEP，voice 被丢弃（上游 isFocusOnVoiceAvailable 语义）
        assertEquals(
            "68 02 11 02 00 01 00 02",
            SonyWire.hex(SonyCommands.setAmbientSound(seq = 0, level = 2, focusOnVoice = true).payload),
        )
    }

    @Test
    fun `dualSingleForAsmLevel 与上游一致`() {
        assertEquals(0x02, SonyCommands.dualSingleForAsmLevel(0))
        assertEquals(0x01, SonyCommands.dualSingleForAsmLevel(1))
        assertEquals(0x00, SonyCommands.dualSingleForAsmLevel(2))
        assertEquals(0x00, SonyCommands.dualSingleForAsmLevel(19))
        assertEquals(0x00, SonyCommands.dualSingleForAsmLevel(20))
        assertEquals(0x00, SonyCommands.dualSingleForAsmLevel(-1))   // 0xFF 无效值
    }

    @Test
    fun `等级越界会拒绝`() {
        assertTrue(runCatching { SonyCommands.setAmbientSound(seq = 0, level = 0) }.isFailure)
        assertTrue(runCatching { SonyCommands.setAmbientSound(seq = 0, level = 21) }.isFailure)
        // 20 档必须能用（上游 XM3 常量在 20 档会抛异常，这里已放宽）
        assertEquals(
            "68 02 11 02 00 01 00 14",
            SonyWire.hex(SonyCommands.setAmbientSound(seq = 0, level = 20).payload),
        )
    }

    // ── 7. 电量应答解析 ─────────────────────────────────────────

    @Test
    fun `解析电量应答 85 未充电`() {
        // 帧：3E 0C 05 | 00 00 00 04 | 11 00 55 00 | 7B | 3C
        //   内容区（不含 checksum）= 0C 05 00 00 00 04 11 00 55 00
        //   CHECKSUM = 12+5+0+0+0+4+17+0+85+0 = 123 = 0x7B
        val frame = bytes(
            0x3E, 0x0C, 0x05, 0x00, 0x00, 0x00, 0x04,
            0x11, 0x00, 0x55, 0x00,
            0x7B, 0x3C,
        )
        val parsed = SonyWire.parse(frame)
        assertNotNull(parsed)

        val ev = SonyParser.parse(parsed!!)
        assertTrue("应为电量事件，实际 $ev", ev is SonyEvent.Battery)
        ev as SonyEvent.Battery
        assertEquals(SonyBattery(level = 85, charging = false), ev.battery)
        assertEquals(12, ev.dataType)
        assertEquals(5, ev.seq)
        assertEquals("11 00 55 00", SonyWire.hex(ev.raw))
    }

    @Test
    fun `解析电量应答 100 充电中`() {
        // 内容区 = 0C 06 00 00 00 04 11 00 64 01
        // CHECKSUM = 12+6+4+17+0+100+1 = 140 = 0x8C
        val frame = bytes(
            0x3E, 0x0C, 0x06, 0x00, 0x00, 0x00, 0x04,
            0x11, 0x00, 0x64, 0x01,
            0x8C, 0x3C,
        )
        val ev = SonyParser.parse(SonyWire.parse(frame)!!) as SonyEvent.Battery
        assertEquals(SonyBattery(level = 100, charging = true), ev.battery)
    }

    @Test
    fun `电量主动通知 0x13 与应答同布局`() {
        val ev = event(bytes(0x13, 0x00, 0x2A, 0x00)) as SonyEvent.Battery
        assertEquals(SonyBattery(level = 42, charging = false), ev.battery)
    }

    @Test
    fun `电量应答异常值一律 Unknown`() {
        assertTrue(event(bytes(0x11, 0x00, 0xFF, 0x00)) is SonyEvent.Unknown)          // 等级越界
        assertTrue(event(bytes(0x11, 0x00, 0x55)) is SonyEvent.Unknown)                 // 长度不足
        assertTrue(event(bytes(0x11, 0x03, 0x55, 0x00)) is SonyEvent.Unknown)          // 未知类型
        // 双电量布局（TWS 才有，XM4 不解析）
        assertTrue(event(bytes(0x11, 0x01, 0x50, 0x00, 0x4B, 0x00)) is SonyEvent.Unknown)
    }

    // ── 8. 降噪/环境声应答解析 ──────────────────────────────────

    @Test
    fun `解析降噪应答：全关`() {
        // 内容区 = 0C 04 00 00 00 08 67 02 00 02 00 01 00 00
        // CHECKSUM = 12+4+8+103+2+0+2+0+1+0+0 = 132 = 0x84
        val frame = bytes(
            0x3E, 0x0C, 0x04, 0x00, 0x00, 0x00, 0x08,
            0x67, 0x02, 0x00, 0x02, 0x00, 0x01, 0x00, 0x00,
            0x84, 0x3C,
        )
        val state = (SonyParser.parse(SonyWire.parse(frame)!!) as SonyEvent.NoiseState).state
        assertEquals(NoiseMode.OFF, state.mode)
        assertTrue(state.off)
        assertFalse(state.ncOn)
        assertFalse(state.ambientOn)
    }

    @Test
    fun `解析降噪应答：风噪能力分支的环境声 15 档`() {
        // 内容区 = 0C 02 00 00 00 08 67 02 01 02 00 01 00 0F
        // CHECKSUM = 12+2+8+103+2+1+2+0+1+0+15 = 146 = 0x92
        //   [2]=01 启用、[3]=02 支持风噪、[4]=00 环境声、[6]=00、[7]=0F 等级 15
        val frame = bytes(
            0x3E, 0x0C, 0x02, 0x00, 0x00, 0x00, 0x08,
            0x67, 0x02, 0x01, 0x02, 0x00, 0x01, 0x00, 0x0F,
            0x92, 0x3C,
        )
        val ev = SonyParser.parse(SonyWire.parse(frame)!!) as SonyEvent.NoiseState
        assertEquals(
            SonyNoiseState(NoiseMode.AMBIENT_SOUND, ambientLevel = 15, focusOnVoice = false),
            ev.state,
        )
        assertTrue(ev.state.ambientOn)
        assertFalse(ev.state.ncOn)
    }

    @Test
    fun `解析降噪应答：风噪能力分支的降噪`() {
        // 内容区 = 0C 03 00 00 00 08 67 02 01 02 02 01 00 00
        // CHECKSUM = 12+3+8+103+2+1+2+2+1+0+0 = 134 = 0x86
        val frame = bytes(
            0x3E, 0x0C, 0x03, 0x00, 0x00, 0x00, 0x08,
            0x67, 0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x00,
            0x86, 0x3C,
        )
        val state = (SonyParser.parse(SonyWire.parse(frame)!!) as SonyEvent.NoiseState).state
        assertEquals(NoiseMode.NOISE_CANCELLING, state.mode)
        assertTrue(state.ncOn)
        assertFalse(state.ambientOn)
    }

    @Test
    fun `解析降噪应答：无风噪分支（3=00 4=01）也是降噪`() {
        val state = (event(bytes(0x67, 0x02, 0x01, 0x00, 0x01, 0x01, 0x00, 0x00)) as SonyEvent.NoiseState).state
        assertEquals(NoiseMode.NOISE_CANCELLING, state.mode)
    }

    @Test
    fun `解析降噪应答：风噪降低档位`() {
        val state = (event(bytes(0x67, 0x02, 0x01, 0x02, 0x01, 0x01, 0x00, 0x00)) as SonyEvent.NoiseState).state
        assertEquals(NoiseMode.WIND_NOISE_REDUCTION, state.mode)
        assertTrue(state.windReductionOn)
        assertFalse(state.ncOn)
    }

    @Test
    fun `聚焦人声需要环境声模式且等级大于 2`() {
        fun stateWith(level: Int, voice: Int, mode: Int = 0x00) =
            (event(bytes(0x67, 0x02, 0x01, 0x02, mode, 0x01, voice, level)) as SonyEvent.NoiseState).state

        assertTrue(stateWith(15, 0x01).focusOnVoice)
        assertFalse("等级 2 不够", stateWith(2, 0x01).focusOnVoice)
        assertFalse(stateWith(15, 0x00).focusOnVoice)
        // 降噪模式下 voice 位即使为 1 也不生效（上游 isFocusOnVoiceAvailable 语义）
        assertFalse(stateWith(15, 0x01, mode = 0x02).focusOnVoice)
    }

    @Test
    fun `降噪主动通知 0x69 与应答同布局`() {
        val state = (event(bytes(0x69, 0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x00)) as SonyEvent.NoiseState).state
        assertEquals(NoiseMode.NOISE_CANCELLING, state.mode)
    }

    @Test
    fun `降噪应答异常值一律 Unknown`() {
        // 长度不是 8
        assertTrue(event(bytes(0x67, 0x02, 0x01, 0x02, 0x00, 0x01, 0x00)) is SonyEvent.Unknown)
        // 启用标志非 0/1
        assertTrue(event(bytes(0x67, 0x02, 0x02, 0x02, 0x00, 0x01, 0x00, 0x00)) is SonyEvent.Unknown)
        // 能力标志未知（0x01）
        assertTrue(event(bytes(0x67, 0x02, 0x01, 0x01, 0x00, 0x01, 0x00, 0x00)) is SonyEvent.Unknown)
        // 风噪能力分支下模式位 3 不存在
        assertTrue(event(bytes(0x67, 0x02, 0x01, 0x02, 0x03, 0x01, 0x00, 0x00)) is SonyEvent.Unknown)
        // 无风噪分支下模式位 2 不存在
        assertTrue(event(bytes(0x67, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00, 0x00)) is SonyEvent.Unknown)
        // 聚焦人声标志非 0/1
        assertTrue(event(bytes(0x67, 0x02, 0x01, 0x02, 0x00, 0x01, 0x02, 0x00)) is SonyEvent.Unknown)
        // 等级超出 0..20
        assertTrue(event(bytes(0x67, 0x02, 0x01, 0x02, 0x00, 0x01, 0x00, 0x15)) is SonyEvent.Unknown)
    }

    // ── 9. 固件应答解析 ─────────────────────────────────────────

    @Test
    fun `解析固件应答 2_0_1`() {
        // payload = 05 02 05 "2.0.1"
        // 内容区 = 0C 07 00 00 00 08 05 02 05 32 2E 30 2E 31
        // CHECKSUM = 12+7+8+5+2+5+0x32+0x2E+0x30+0x2E+0x31
        //          = 12+7+8+5+2+5+50+46+48+46+49 = 278 -> 278-256 = 22 = 0x16
        val frame = bytes(
            0x3E, 0x0C, 0x07, 0x00, 0x00, 0x00, 0x08,
            0x05, 0x02, 0x05, 0x32, 0x2E, 0x30, 0x2E, 0x31,
            0x16, 0x3C,
        )
        val ev = SonyParser.parse(SonyWire.parse(frame)!!)
        assertTrue("应为固件事件，实际 $ev", ev is SonyEvent.Firmware)
        ev as SonyEvent.Firmware
        assertEquals("2.0.1", ev.version)
        assertEquals(7, ev.seq)
    }

    @Test
    fun `固件应答异常值一律 Unknown`() {
        // 下标 1 不是 0x02
        assertTrue(event(bytes(0x05, 0x03, 0x03, 0x31, 0x2E, 0x30)) is SonyEvent.Unknown)
        // 声明长度与实际不符
        assertTrue(event(bytes(0x05, 0x02, 0x09, 0x31, 0x2E, 0x30)) is SonyEvent.Unknown)
        // 非法字符（0x21 = '!'）
        assertTrue(event(bytes(0x05, 0x02, 0x03, 0x31, 0x21, 0x30)) is SonyEvent.Unknown)
        // 太短
        assertTrue(event(bytes(0x05, 0x02, 0x01)) is SonyEvent.Unknown)
    }

    // ── 10. ACK 与 Unknown ─────────────────────────────────────

    @Test
    fun `ACK 归到 Ack 事件且带 seq`() {
        val ack = SonyParser.parse(SonyWire.Message(type = 1, seq = 42, payload = ByteArray(0)))
        assertTrue("应为 Ack，实际 $ack", ack is SonyEvent.Ack)
        assertEquals(42, (ack as SonyEvent.Ack).seq)
    }

    @Test
    fun `未知命令与空载荷返回 Unknown`() {
        assertTrue(event(bytes(0x99, 0x01)) is SonyEvent.Unknown)
        // 非 ACK 类型但空载荷
        assertTrue(event(ByteArray(0), type = 12) is SonyEvent.Unknown)
        // INIT 应答有专门事件（真机实测 WH-1000XM4 回 01 00 70 00，长度 4 → V1）：
        // 它是「设备进入可查询状态」的信号，连接后要靠它再发查询序列，所以不能当 Unknown 丢掉。
        val init = event(bytes(0x01, 0x00, 0x01, 0x00))
        assertTrue("应为 Init，实际 $init", init is SonyEvent.Init)
        assertEquals(1, (init as SonyEvent.Init).version)

        // 音量通知：已识别但不消费（XM4 每秒推一条，归到 Ignored 才不会刷爆日志）
        assertTrue(event(bytes(0xA9, 0x01, 0x20, 0x08)) is SonyEvent.Ignored)
    }

    // ── 11. 出站帧头与序号 ─────────────────────────────────────

    @Test
    fun `出站帧 DATA_TYPE 是 DATA_MDR_12`() {
        // 两个参考一致：COMMAND_1 = 0x0C
        assertEquals(12, SonyCommands.setOff(seq = 0).type)
        assertEquals(DataType.DATA_MDR.v, SonyCommands.setOff(seq = 0).type)
        assertEquals(0x0C, SonyWire.build(SonyCommands.setOff(seq = 0))[1].toInt() and 0xFF)
    }

    @Test
    fun `SeqCounter 按 8 位回绕并支持采用设备序号`() {
        val counter = SonyCommands.SeqCounter(253)
        assertEquals(253, counter.next())
        assertEquals(254, counter.next())
        assertEquals(255, counter.next())
        assertEquals(0, counter.next())
        counter.adopt(42)
        assertEquals(42, counter.next())
        counter.reset()
        assertEquals(0, counter.next())
    }

    @Test
    fun `initSequence 默认不发 INIT 但可开启`() {
        // SonyHeadphonesClient 不发；Gadgetbridge 发 00 00 探测协议版本
        assertTrue(SonyCommands.initSequence().isEmpty())
        val withInit = SonyCommands.initSequence(sendInit = true)
        assertEquals(1, withInit.size)
        assertEquals("00 00", SonyWire.hex(withInit[0].payload))
    }

    @Test
    fun `初始查询序列为 降噪 电量 固件`() {
        val queries = SonyCommands.initialQueries(startSeq = 254)
        assertEquals(3, queries.size)
        assertEquals(254, queries[0].seq)
        assertEquals(255, queries[1].seq)
        assertEquals(0, queries[2].seq)      // 8 位回绕
        assertEquals("66 02", SonyWire.hex(queries[0].payload))
        assertEquals("10 00", SonyWire.hex(queries[1].payload))
        assertEquals("04 02", SonyWire.hex(queries[2].payload))
    }

    @Test
    fun `hex 辅助输出大写空格分隔且不受 Locale 影响`() {
        assertEquals("", SonyWire.hex(ByteArray(0)))
        assertEquals("00 0F FF", SonyWire.hex(bytes(0x00, 0x0F, 0xFF)))
    }
}
