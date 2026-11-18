package com.example.b1garmin.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.b1garmin.protocol.AfuProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.UUID
import kotlin.coroutines.resume

/**
 * 一次完整的 B1 会话：连接 → 订阅 → 握手 → 读历史 / 实时称重 → 收最终记录。
 *
 * 为什么重写（2026-11-16 真机 v0.3.2 失败的根因）：
 *
 * 1. **GATT 一次只能有一个操作**。旧代码在 onServicesDiscovered 里连着写 4 条命令、
 *    而且是在写 CCCD 之后立刻写，导致后几条基本全被系统丢弃（返回 false 或静默不执行）。
 *    表现出来就是「秤一条数据都不回」。现在改成串行队列，每条等 onCharacteristicWrite 回调
 *    再发下一条。
 * 2. **收到的帧全都看不见**。旧代码只解析已知类型，未知类型/校验不符的帧只写 Log.d，
 *    界面上完全没有 → 无法判断「是没收到」还是「收到了但没认出来」。
 *    现在每条帧都原样打成 hex 显示。
 * 3. **B1 需要完整状态机**：上秤后要回 ACK、锁定后要重新同步并请求分块，才会吐出 0x54
 *    最终记录（含阻抗）。旧代码发完命令就干等，所以拿不到体成分。
 */
@SuppressLint("MissingPermission")
class ScaleSession(
    private val context: Context,
    private val device: ScaleReader.Device,
    private val onLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ScaleSession"
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val WRITE_CHAR = UUID.fromString(AfuProtocol.WRITE_CHAR_UUID)

        /** 两条命令之间的间隔，给秤留处理时间 */
        private const val OP_GAP_MS = 110L

        /** 单条 GATT 操作超过这么久没回调就跳过，避免卡死 */
        private const val OP_TIMEOUT_MS = 3000L

        /** 连上后这么久一条帧都没收到，判定握手失败 */
        private const val FIRST_FRAME_TIMEOUT_MS = 25_000L

        /** 整个会话上限（要留时间给用户上秤） */
        private const val SESSION_TIMEOUT_MS = 100_000L
    }

    data class Measurement(
        val timestampSec: Long,
        val weightKg: Double,
        val resistanceOhm: Double?,
        /** true = 从秤的历史记录读出来的，false = 本次实时称重 */
        val fromHistory: Boolean,
    )

    private sealed class Op {
        class Desc(val ch: BluetoothGattCharacteristic, val value: ByteArray) : Op()
        class Char(val ch: BluetoothGattCharacteristic, val data: ByteArray) : Op()
    }

    /**
     * 跑一次称重会话。
     *
     * @param onLive 实时体重帧回调（人站上秤后会连续推送），运行在高优先级的 Binder 线程，
     *               调用方若要更新 UI 必须自行切主线程。
     * @param onImpedance 拿到有效阻抗时回调一次（同样在 Binder 线程）。
     *
     * 两个回调都有默认值，老调用方不受影响。
     */
    suspend fun run(
        age: Int, male: Boolean, targetKg: Double,
        onLive: (kg: Double, locked: Boolean) -> Unit = { _, _ -> },
        onImpedance: (ohm: Double) -> Unit = { _ -> },
        /**
         * 待机监听模式：连上秤后长时间保持连接、等人上秤，而不是 20 秒没人就断开。
         * 用于「打开 App 就自动待命、随时站上去称」的体验。上传完成后由调用方决定是否重连。
         */
        standby: Boolean = false,
    ): List<Measurement> =
        suspendCancellableCoroutine { cont ->

            val results = LinkedHashMap<String, Measurement>()
            val queue = ArrayDeque<Op>()
            val handler = Handler(Looper.getMainLooper())

            var gatt: BluetoothGatt? = null
            var writeChar: BluetoothGattCharacteristic? = null
            var opBusy = false
            var opSeq = 0
            var finished = false

            var descPending = 0
            var handshakeStarted = false

            var sawAnyFrame = false
            var lastFrameAt = System.currentTimeMillis()
            val sessionStart = System.currentTimeMillis()
            val sessionEpoch = sessionStart / 1000

            /**
             * 收到过「本次测量」的最终记录（0x54）。
             *
             * 秤的历史记录和最终记录用的是同一个 wire type，只能靠时间戳区分：
             * 时间戳落在本次会话前后 10 分钟内的才算「刚才这次」。
             */
            var gotFinal = false

            /**
             * 收到过「本次测量」的阻抗（0x52 有效帧，或带阻抗的 0x54 记录）。
             *
             * v0.11.1 的教训：只看 gotFinal 判定收工，但如果那条 0x54 没阻抗、
             * 而 0x52 帧还在路上，2.5 秒静默一到就断连，阻抗永远拿不到。
             * 收工必须改为「拿到阻抗」或「等满上限」二选一。
             */
            var gotImpedance = false

            /** 所有日志都带上「相对会话开始的秒数」，方便一眼看出时间花在哪一步 */
            fun out(s: String) {
                val t = (System.currentTimeMillis() - sessionStart) / 1000.0
                onLog("[+%.1fs] %s".format(t, s))
            }

            var lastLiveGrams = 0
            var lastResistance = 0
            var sentInitial = false
            var lockedAt = 0L
            var committed = false

            fun shortUuid(u: UUID): String = u.toString().take(8)

            /**
             * 连接失败的常见原因翻译成人话。
             * 133 / 8 在这台秤上最常见的原因就是：**阿福 App 还占着连接**
             * （BLE 外设同一时间只能被一个中心连）。
             */
            fun disconnectHint(status: Int): String = when (status) {
                133, 8 -> "连不上秤（status=$status）：多半是阿福 App 还占着连接。" +
                        "彻底关掉阿福（后台也要划掉），再点一次。"
                0 -> "秤断开了连接"
                else -> "连接失败 status=$status"
            }

            fun props(c: BluetoothGattCharacteristic): String {
                val p = c.properties
                val sb = StringBuilder()
                if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) sb.append("读 ")
                if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) sb.append("写 ")
                if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) sb.append("无响应写 ")
                if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) sb.append("通知 ")
                if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) sb.append("指示 ")
                return sb.toString().trim().ifBlank { "-" }
            }

            // ---------------- 结束会话 ----------------

            fun finish(reason: String) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                try { gatt?.disconnect() } catch (e: Exception) {}
                try { gatt?.close() } catch (e: Exception) {}
                gatt = null

                // 有实时体重、但没拿到最终记录时，至少把体重交出去（没阻抗就没有体成分）
                // 注意：不能只在「一条记录都没有」时才补 —— 秤常常先吐历史记录，
                // 那样会把用户**这次**的实时称重整条丢掉。
                if (!gotFinal && lastLiveGrams > 0) {
                    results["live"] = Measurement(
                        System.currentTimeMillis() / 1000,
                        lastLiveGrams / 1000.0,
                        if (lastResistance > 0) lastResistance.toDouble() else null,
                        false,
                    )
                }
                out(reason)
                if (cont.isActive) cont.resume(results.values.sortedBy { it.timestampSec })
            }

            // ---------------- GATT 操作队列 ----------------

            // 局部函数不能前向引用，用这个中转一下（pump 里可能要触发握手）
            var maybeHandshake: () -> Unit = {}

            fun pump() {
                if (finished || opBusy) return
                val g = gatt ?: return
                val op = queue.pollFirst() ?: return
                opBusy = true
                val myId = ++opSeq
                var ok = false
                try {
                    when (op) {
                        is Op.Desc -> {
                            val d = op.ch.getDescriptor(CCCD)
                            if (d == null) {
                                out("  （${shortUuid(op.ch.uuid)} 没有 CCCD，跳过订阅）")
                                descPending--
                                ok = true
                            } else {
                                // API 33+ 的这个重载返回 Int（状态码），0 = 成功
                                ok = if (Build.VERSION.SDK_INT >= 33)
                                    g.writeDescriptor(d, op.value) == 0
                                else {
                                    d.value = op.value
                                    g.writeDescriptor(d)
                                }
                            }
                        }
                        is Op.Char -> {
                            ok = if (Build.VERSION.SDK_INT >= 33)
                                g.writeCharacteristic(
                                    op.ch, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                ) == 0
                            else {
                                op.ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                op.ch.value = op.data
                                g.writeCharacteristic(op.ch)
                            }
                        }
                    }
                } catch (e: Exception) {
                    out("  ✗ 写入异常：${e.message}")
                    ok = false
                }

                if (!ok) {
                    out("  ✗ 系统拒绝这次写入，跳过")
                    if (op is Op.Desc) descPending--
                    maybeHandshake()
                    opBusy = false
                    handler.postDelayed({ pump() }, OP_GAP_MS)
                    return
                }

                handler.postDelayed({
                    if (!finished && opBusy && myId == opSeq) {
                        out("  ⚠ 写入等不到回调（超时 3 秒），跳过这条继续")
                        opBusy = false
                        pump()
                    }
                }, OP_TIMEOUT_MS)
            }

            fun opDone() {
                opBusy = false
                handler.postDelayed({ pump() }, OP_GAP_MS)
            }

            fun send(label: String, bytes: ByteArray) {
                val wc = writeChar
                if (wc == null) {
                    out("✗ 没有可写特征值，无法发送 $label")
                    return
                }
                out("→ $label")
                queue.add(Op.Char(wc, bytes))
                pump()
            }

            // ---------------- 流程 ----------------

            fun startHandshake() {
                if (handshakeStarted || finished) return
                handshakeStarted = true
                val epoch = System.currentTimeMillis() / 1000
                out("开始握手（发时间同步 + 两条同步命令）…")
                send("时间同步 0x16", AfuProtocol.buildTimeSync(epoch))
                send("SYNC_1", AfuProtocol.buildSyncFirst())
                send("SYNC_2", AfuProtocol.buildSyncSecond())
                send("请求历史转储 0x8E", AfuProtocol.buildProfile("HISTORY_DUMP", epoch, 0, age, male, targetKg))
                out("握手指令已排队。现在请【光脚】站上秤、站稳别动。")
            }

            maybeHandshake = { if (descPending <= 0 && !handshakeStarted) startHandshake() }

            fun commit() {
                if (committed || finished) return
                committed = true
                val epoch = System.currentTimeMillis() / 1000
                out("体重已锁定 → 进入提交阶段（重新同步 + 请求最终体成分记录）…")
                send("时间同步 0x16", AfuProtocol.buildTimeSync(epoch))
                send("SYNC_1", AfuProtocol.buildSyncFirst())
                send("SYNC_2", AfuProtocol.buildSyncSecond())
                send("PROFILE 0x4B", AfuProtocol.buildProfile("GET_WEIGH_AGAIN_1", epoch, 0, age, male, targetKg))
                send("PROFILE 0x5B", AfuProtocol.buildProfile("GET_WEIGH_AGAIN_2", epoch, 0, age, male, targetKg))
                send("请求分块 0x14", AfuProtocol.buildChunkRequest())
            }

            fun onFrame(ch: BluetoothGattCharacteristic, data: ByteArray) {
                sawAnyFrame = true
                lastFrameAt = System.currentTimeMillis()
                val hex = AfuProtocol.toHex(data)
                val p = AfuProtocol.parse(data)

                when (p) {
                    is AfuProtocol.Parsed.LiveWeight -> {
                        lastLiveGrams = p.weightGrams
                        // 实时体重推给界面（Binder 线程回调，调用方负责切主线程）
                        onLive(p.weightGrams / 1000.0, p.locked)
                        out("← ${shortUuid(ch.uuid)} [$hex]")
                        out("   实时体重 ${"%.2f".format(p.weightGrams / 1000.0)} kg" +
                                if (p.locked) "  ✔ 已锁定" else "")
                        if (!sentInitial) {
                            sentInitial = true
                            send("PROFILE 0x3F（首次上秤）",
                                AfuProtocol.buildProfile("INITIAL", System.currentTimeMillis() / 1000,
                                    0, age, male, targetKg))
                        }
                        if (p.locked) {
                            lockedAt = System.currentTimeMillis()
                            out("   ⚠ 体重锁定，别下秤！保持光脚站稳、四个电极都踩到，" +
                                    "再等 8~12 秒测体脂")
                            send("ACK 0x51", AfuProtocol.buildAck(AfuProtocol.WIRE_LIVE_WEIGHT, 0))
                        }
                    }
                    is AfuProtocol.Parsed.Impedance -> {
                        if (p.valid) {
                            lastResistance = p.resistanceOhm
                            gotImpedance = true
                            // 有效阻抗推给界面，实时算体脂（Binder 线程回调）
                            onImpedance(p.resistanceOhm.toDouble())
                            // 阻抗帧比最终记录晚到的情况：把刚那条没阻抗的记录补上
                            if (gotFinal) {
                                // 只补「刚那次」且目前还缺阻抗的记录，别误补历史记录
                                val target = results.entries.lastOrNull { e ->
                                    Math.abs(e.value.timestampSec - sessionEpoch) < 600 &&
                                            e.value.resistanceOhm == null
                                }
                                if (target != null) {
                                    results[target.key] =
                                        target.value.copy(resistanceOhm = p.resistanceOhm.toDouble())
                                }
                            }
                        }
                        out("← ${shortUuid(ch.uuid)} [$hex]")
                        out("   阻抗 ${p.resistanceOhm} Ω" + if (p.valid) "" else "（无效：可能穿袜子/脚太干/没踩满电极）")
                        send("ACK 0x52", AfuProtocol.buildAck(AfuProtocol.WIRE_IMPEDANCE, 0))
                        // 收到有效阻抗就立刻索取最终 0x54 记录，不等 commit。
                        // 有些固件在锁定后马上发 0x52，若这时还没 commit，旧代码会
                        // 白白错过，导致阻抗有了却没写进上传记录。
                        if (p.valid) {
                            send("PROFILE 0x68（最终）",
                                AfuProtocol.buildProfile("FINAL", System.currentTimeMillis() / 1000,
                                    0, age, male, targetKg))
                        }
                    }
                    is AfuProtocol.Parsed.Stored -> {
                        if (p.weightGrams > 0) {
                            // 时间戳靠近本次会话 → 这是「刚才那次」的最终记录，不是历史
                            if (Math.abs(p.timestampEpoch - sessionEpoch) < 600) gotFinal = true
                            // 有些固件最终记录里不带阻抗，但刚才单独发过 0x52 阻抗帧
                            // → 用那个值补上，否则这条记录会被当成「没阻抗」而没法当校准锚点
                            val res = when {
                                p.resistanceOhm > 0 -> p.resistanceOhm
                                gotFinal -> lastResistance
                                else -> 0
                            }
                            if (gotFinal && res > 0) gotImpedance = true
                            val key = "${p.timestampEpoch}-${p.weightGrams}"
                            results[key] = Measurement(
                                p.timestampEpoch, p.weightGrams / 1000.0,
                                if (res > 0) res.toDouble() else null,
                                true,
                            )
                            val whenStr = java.text.SimpleDateFormat("MM-dd HH:mm")
                                .format(java.util.Date(p.timestampEpoch * 1000))
                            out("← ${shortUuid(ch.uuid)} [$hex]")
                            out("   ✔ 秤内记录 $whenStr  ${"%.2f".format(p.weightGrams / 1000.0)} kg" +
                                    "  R=${res}Ω  seq=${p.sequence}" +
                                    (if (p.resistanceOhm <= 0 && res > 0) "（取自阻抗帧）" else ""))
                        } else {
                            out("← ${shortUuid(ch.uuid)} [$hex]  （空记录，跳过）")
                        }
                        send("ACK 0x54", AfuProtocol.buildAck(AfuProtocol.WIRE_STORED, p.sequence))
                    }
                    is AfuProtocol.Parsed.Other -> {
                        out("← ${shortUuid(ch.uuid)} [$hex]  控制帧 0x%02X".format(p.wireType))
                    }
                    null -> {
                        out("← ${shortUuid(ch.uuid)} [$hex]")
                        out("   ⚠ 长度 ${data.size} 字节，不是标准 20 字节 AF45 帧")
                    }
                }
            }

            // ---------------- 心跳/看门狗 ----------------

            val ticker = object : Runnable {
                override fun run() {
                    if (finished) return
                    val now = System.currentTimeMillis()
                    val silence = now - lastFrameAt
                    val liveSeen = lastLiveGrams > 0

                    // 1. 锁定后静默 1.5 秒 → 进入提交
                    if (lockedAt > 0 && !committed && now - lockedAt > 1500) commit()

                    // 2. 提交之后：
                    //    - 拿到阻抗了 → 再等 2 秒让 0x54 记录落地就收工；
                    //    - 只拿到体重没拿到阻抗 → 继续等【满 16 秒】。
                    // 生物电阻抗要光脚站稳 8~12 秒才测得出来，v0.11.1 锁定后只等
                    // 6 秒（扣掉 commit 的 1.5 秒，实际只剩 4.5 秒），秤还没测完
                    // 就断连了 —— 这就是「每次都拿不到阻抗」的真正原因。
                    if (committed) {
                        if (gotImpedance && silence > 2000) { finish("✓ 会话完成（已拿到体成分）"); return }
                        val waited = if (lockedAt > 0) now - lockedAt else 0
                        if (!gotImpedance && waited > 16_000) {
                            finish("会话结束：等了 16 秒仍没测到阻抗（秤可能没发出阻抗帧）")
                            return
                        }
                    }

                    // 3. 上秤了但体重一直没锁定：静默 8 秒就收工（至少把体重带出去）
                    if (liveSeen && !committed && silence > 8000) {
                        finish("会话结束：体重没有锁定")
                        return
                    }

                    // 4. 只读到秤内历史、一直没人上秤：
                    //    - 普通模式等满 20 秒收工（避免用户还没站上去就被动等太久）
                    //    - 待机监听模式则一直保持连接等人上秤，只受下面的总超时约束
                    //    （旧逻辑是「有记录 + 静默 1.5 秒」就收工 —— 秤先吐历史的话，
                    //      用户还没站上去连接就断了，这次称重直接丢失。）
                    if (!standby && !liveSeen && results.isNotEmpty() && now - sessionStart > 20000) {
                        finish("会话结束：只读到秤里的历史记录，没等到你上秤")
                        return
                    }
                    // 待机模式下：连上后如果只吐历史、一直没人上秤，给一个无限长的等待上限
                    // 由外层监听循环的「有人上秤才处理」逻辑决定何时结束，这里不主动断。
                    // （真正没人上秤时保持连接，用户随时站上去都能立刻捕获到实时重量）

                    // 5. 一条帧都没收到（待机模式下也放宽，避免秤休眠时反复断连）
                    if (!sawAnyFrame && now - sessionStart >
                        (if (standby) 60_000L else FIRST_FRAME_TIMEOUT_MS)
                    ) {
                        finish("✗ ${(if (standby) 60 else FIRST_FRAME_TIMEOUT_MS / 1000)} 秒内一条帧都没收到：命令可能没被秤接受")
                        return
                    }
                    // 6. 总超时（待机模式放宽到 10 分钟，普通模式 100 秒）
                    val limit = if (standby) 600_000L else SESSION_TIMEOUT_MS
                    if (now - sessionStart > limit) {
                        finish("会话超时结束")
                        return
                    }
                    handler.postDelayed(this, 300)
                }
            }

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        out("✓ 已连接，正在发现服务…")
                        try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (e: Exception) {}
                        g.discoverServices()
                        return
                    }
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!finished) finish(disconnectHint(status))
                        return
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) finish(disconnectHint(status))
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish("发现服务失败 status=$status")
                        return
                    }
                    val fcLog = g.services.firstOrNull {
                        it.uuid.toString().lowercase().startsWith("0000fc50")
                    }
                    out("✓ 发现 ${g.services.size} 个服务" +
                            (fcLog?.let { "，FC50 里 ${it.characteristics.size} 个特征值" } ?: "（没有 FC50！）"))
                    fcLog?.characteristics?.forEach { c ->
                        out("   ${shortUuid(c.uuid)}…  ${props(c)}")
                    }

                    val all = g.services.flatMap { it.characteristics }
                    // 优先用协议里写死的写特征值；找不到时只在 FC50 服务里挑可写的，
                    // 免得选到标准服务（电池/设备信息）里的特征值，那样命令必然石沉大海
                    val fc50 = g.services.firstOrNull {
                        it.uuid.toString().lowercase().startsWith("0000fc50")
                    }
                    fun writable(c: BluetoothGattCharacteristic) =
                        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    writeChar = all.firstOrNull { it.uuid == WRITE_CHAR }
                        ?: fc50?.characteristics?.firstOrNull { writable(it) }
                        ?: all.firstOrNull { writable(it) }

                    if (writeChar == null) {
                        finish("✗ 这个设备没有任何可写特征值，可能不是 B1 秤")
                        return
                    }
                    out("写入特征值：${shortUuid(writeChar!!.uuid)}…")

                    val notifyChars = all.filter {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    }
                    if (notifyChars.isEmpty()) {
                        finish("✗ 没有通知特征值，收不到任何数据")
                        return
                    }
                    descPending = notifyChars.size
                    out("订阅 ${notifyChars.size} 个通知特征值（逐个开，等回调）…")
                    notifyChars.forEach { ch ->
                        g.setCharacteristicNotification(ch, true)
                        val v = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        queue.add(Op.Desc(ch, v))
                    }
                    pump()
                }

                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                    out("  订阅 ${shortUuid(d.characteristic.uuid)}… ${if (status == 0) "OK" else "失败($status)"}")
                    descPending--
                    if (descPending <= 0 && !handshakeStarted) startHandshake()
                    opDone()
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        out("  ⚠ 写入返回失败 status=$status")
                    }
                    opDone()
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    onFrame(c, c.value?.copyOf() ?: return)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
                ) {
                    onFrame(c, value.copyOf())
                }
            }

            try {
                val ad = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (ad == null) {
                    finish("✗ 本机蓝牙不可用")
                    return@suspendCancellableCoroutine
                }
                val dev: BluetoothDevice = ad.getRemoteDevice(device.address)
                out("正在连接 ${device.display} …")
                gatt = dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                handler.postDelayed(ticker, 300)
                handler.postDelayed({
                    if (!finished && !handshakeStarted) {
                        out("⚠ 订阅阶段超时，直接尝试握手")
                        startHandshake()
                    }
                }, 12_000)
            } catch (e: Exception) {
                Log.e(TAG, "connect error", e)
                finish("✗ 连接出错：${e.message}")
            }

            cont.invokeOnCancellation {
                finished = true
                handler.removeCallbacksAndMessages(null)
                try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
            }
        }
}
