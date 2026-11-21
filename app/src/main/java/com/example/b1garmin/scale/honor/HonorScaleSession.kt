package com.example.b1garmin.scale.honor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.b1garmin.scale.ScaleMeasurement
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 荣耀体脂秤 2（LUP-B19）华为私协议实测工具。
 *
 * 协议已用官方运动健康的 btsnoop 抓包逐字节验证，要点：
 *  - 帧：[hdr][len=数据长+3][seq=((总帧数-1)<<4)|序号][data≤15B][CRC16-XMODEM 低字节在前]
 *    hdr：0xDB 明文 / 0xDC 加密（手机→秤）；0xBD 明文 / 0xCD 加密（秤→手机）
 *  - 加密体 = IV(16) || AES-CTR(key, IV, 明文)
 *  - 鉴权：先往 02b2a08e 写明文空帧 `DB 03 00 C1 40`，秤回 randA(16B)；
 *    回 randB || HMAC(HMAC(CAK||"1123", K), K)（K = randA||randB）到 32330a04；
 *    秤回 32B 校验值，期望 = HMAC(HMAC(CAK||"9856", K), K)
 *  - 工作密钥：写 IV || AES-CTR(rootKey, IV, workKey16) 到 a3d330f8，之后数据都走 workKey
 *  - 绑定 42596cbe / 用户信息 8cc61d7d 都是 workKey 加密的明文帧
 *  - 测量记录（已按官方 HwWspMeasureDataParser 反编译结果校正）：
 *      偏移 0..1  体重 LE16 / 100 kg
 *      偏移 2..3  体脂率 LE16 / 10 %
 *      偏移 4..10 年 LE16, 月, 日, 时, 分, 秒
 *      偏移 12..23 6 个阻抗值（LE16，单位 0.1Ω）
 *      偏移 24..25 秤端附加字段
 *      实时记录长度 26/38，历史记录长度 27/28/39/40
 */
class HonorScaleSession(
    private val appContext: Context,
    private val onLog: (String) -> Unit,
    private val onRecord: (ScaleMeasurement) -> Unit,
) {

    companion object {
        private const val TAG = "HonorScale"

        private const val CAK_HEX = "90B96ECA297EF78717E66E491084D3F8"
        private const val K1_HEX = "CA4946D061C9FE534F6044F930EBB69B"
        private const val K2_HEX = "FBCE6E2B4BAF80ED969BA26B4A4B9325"

        /** 官方账号 ID（19 位）：绑定/用户信息都用它，秤端记录靠它归属 */
        private const val DEFAULT_USER_ID = "2850086000376600906"

        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val CH_AUTH_REQ = UUID.fromString("02b2a08e-f8b0-4047-b1fd-f4e0efeee679")
        private val CH_AUTH_TOKEN = UUID.fromString("32330a04-15d9-421a-91c5-2a2d5c7525c9")
        private val CH_WORK_KEY = UUID.fromString("a3d330f8-b84f-4f48-a78c-f8d1e33b597a")
        private val CH_BIND = UUID.fromString("42596cbe-d291-4da3-8ca6-d1ae5d1c9174")
        private val CH_USER_INFO = UUID.fromString("8cc61d7d-66c0-4802-89c3-38c5a163592e")
        private val CH_OPEN_STATUS = UUID.fromString("ba216311-1787-472b-bef6-3eb29e62293e")
        private val CH_TIME = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
        private val CH_DEV_INFO = UUID.fromString("1f5d3d5c-496d-4290-af03-c7a8d5419741")
        private val CH_CTRL1 = UUID.fromString("7e6dbc73-42e7-45b9-a6ec-6aa2d7834695")
        private val CH_RT_WEIGHT = UUID.fromString("46797c17-d639-488d-9476-4789e8472878")
        private val CH_BODY = UUID.fromString("0212f42a-5f19-4bc1-ba52-d7ec7ccb71a4")
        private val CH_HEARTBEAT = UUID.fromString("bfc36f6e-4150-4a4b-9052-3d359e52962e")

        /** 官方订阅顺序（bfc36f6e 只写不订阅） */
        private val SUBSCRIBE = listOf(
            CH_AUTH_REQ, CH_AUTH_TOKEN, CH_WORK_KEY, CH_BIND, CH_USER_INFO,
            CH_TIME, CH_DEV_INFO, CH_CTRL1, CH_OPEN_STATUS, CH_BODY, CH_RT_WEIGHT,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var deadline = 0L
    private var target = ""

    private var randB: ByteArray? = null
    private var randA: ByteArray? = null
    private var workKey: ByteArray? = null
    private var authed = false
    private var ready = false
    /** 订阅全部完成（CCCD 都写完了）才允许发 token，否则写入会被 CCCD 操作挤慢 */
    private var subscribed = false
    /** 已为当前 randA 发出 token */
    private var tokenSent = false
    private var authTries = 0
    private var workKeySent = false
    private var bindSent = false
    private var userInfoSent = false

    private val subQueue = ArrayDeque<UUID>()
    private val writeQueue = ArrayDeque<Pair<UUID, ByteArray>>()
    private var writing = false

    private val secureRandom = SecureRandom()
    private val assemblies = HashMap<UUID, Assembly>()
    private val history = ArrayList<Record>()
    private var historyPolls = 0
    private var rtPolls = 0
    private var lastRtPoll = 0L
    private var lastBodyPoll = 0L
    private var lastHeartbeat = 0L
    private val reported = HashSet<String>()
    private var anyRecord = false
    /** 实时记录先缓存 2.5s，等秤把最终值推完再上报，避免半程重量被当成结果 */
    private var pendingRt: Record? = null
    private var connectAtSec = 0L

    private class Assembly {
        var total = 0
        val parts = arrayOfNulls<ByteArray>(16)
        fun reset(total: Int) {
            this.total = total
            for (i in parts.indices) parts[i] = null
        }
    }

    /** 一条测量记录：kg + 秒级时间戳（体重取自偏移 0，体脂/阻抗仅用于日志） */
    private class Record(
        val tsSec: Long,
        val kg: Double,
        val fatRate: Double,
        val impedance: Int,
        val heartRate: Int,
        val fromHistory: Boolean,
    ) {}

    /**
     * 连上指定秤，最多等 [seconds] 秒。
     * 每拿到一条记录就通过构造时的 [onRecord] 回调抛出；调用方拿到后调 [stop] 收尾。
     */
    fun start(address: String, seconds: Int) {
        deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        reported.clear()
        connect(address)
    }

    /** 结束当前会话并断开（可重复调用） */
    fun stop() {
        deadline = 0L
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        ready = false
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        val context = appContext
        if (SystemClock.elapsedRealtime() > deadline) {
            log("结束")
            return
        }
        target = address
        val device = runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.getRemoteDevice(address)
        }.getOrNull() ?: return
        generation++
        gatt?.let { runCatching { it.close() } }
        gatt = null
        writing = false
        writeQueue.clear()
        subQueue.clear()
        assemblies.clear()
        randA = null
        randB = null
        workKey = null
        authed = false
        ready = false
        authTries = 0
        subscribed = false
        tokenSent = false
        workKeySent = false
        bindSent = false
        userInfoSent = false
        history.clear()
        historyPolls = 0
        rtPolls = 0
        anyRecord = false
        pendingRt = null
        connectAtSec = System.currentTimeMillis() / 1000
        lastRtPoll = 0L
        lastBodyPoll = 0L
        lastHeartbeat = 0L
        // 绝不主动 createBond：华为秤走应用层加密，配对会弹 PIN 且失败
        log("连接 $address bond=${device.bondState}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private var generation = 0

    private val charMap = HashMap<UUID, BluetoothGattCharacteristic>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            log("连接状态 status=$status state=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                gatt = null
                runCatching { g.close() }
                // 断开前把还没上报的实时记录交出去
                if (pendingRt != null) {
                    handler.removeCallbacks(reportRt)
                    reportRt.run()
                }
                handler.removeCallbacksAndMessages(null)
                if (SystemClock.elapsedRealtime() < deadline && !anyRecord) {
                    handler.postDelayed({ connect(target) }, 1000)
                } else {
                    log("结束")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            log("服务发现 status=$status")
            charMap.clear()
            g.services.forEach { s ->
                s.characteristics.forEach { c ->
                    charMap[c.uuid] = c
                    Log.i(TAG, "CHAR ${c.uuid} props=${c.properties}")
                }
            }
            if (!charMap.containsKey(CH_AUTH_REQ)) {
                log("✗ 没找到荣耀私有服务，可能不是这台秤")
                return
            }
            subQueue.clear()
            subQueue.addAll(SUBSCRIBE)
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            Log.i(TAG, "CCCD ${descriptor.characteristic.uuid} status=$status")
            enableNext(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            Log.i(TAG, "写 ${ch.uuid} status=$status")
            writing = false
            writeNext(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (g !== gatt) return
            onFrame(ch.uuid, value)
        }

        @Deprecated("API < 33 回调")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (g !== gatt) return
            @Suppress("DEPRECATION")
            onFrame(ch.uuid, ch.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNext(g: BluetoothGatt) {
        val uuid = subQueue.removeFirstOrNull()
        if (uuid == null) {
            subscribed = true
            log("订阅完成，开始华为握手（rootKey=${hex(rootKey(target))}）")
            // 秤订阅后 400ms 左右会主动推 randA；先等它，没等到再写空帧要
            handler.postDelayed({
                if (gatt == null || authed) return@postDelayed
                if (randA != null) sendToken() else requestChallenge()
            }, 400)
            return
        }
        val ch = charMap[uuid]
        if (ch == null) {
            Log.i(TAG, "缺特征值 $uuid")
            enableNext(g)
            return
        }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) {
            enableNext(g)
            return
        }
        val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun enqueue(uuid: UUID, frames: List<ByteArray>) {
        frames.forEach { writeQueue.addLast(uuid to it) }
        gatt?.let { writeNext(it) }
    }

    /** 往 02b2a08e 写空帧请求鉴权挑战，等不到响应就重发 */
    private fun requestChallenge() {
        if (gatt == null) return
        enqueue(CH_AUTH_REQ, buildFrames(ByteArray(0), false, null))
        handler.postDelayed({
            if (gatt != null && !authed && randA == null && authTries < 6) {
                authTries++
                log("挑战无响应，重发（第 $authTries 次）")
                requestChallenge()
            }
        }, 3000)
    }

    /** 用当前的 randA 发 token（必须等订阅全部完成，否则写入会被 CCCD 挤慢导致秤组帧超时） */
    private fun sendToken() {
        if (!subscribed || authed || tokenSent) return
        val ra = randA ?: return
        val rb = randB ?: return
        tokenSent = true
        val k = ra + rb
        val token = hmac(hmac(hexToBytes(CAK_HEX) + "1123".toByteArray(), k), k)
        enqueue(CH_AUTH_TOKEN, buildFrames(rb + token, false, null))
    }

    @SuppressLint("MissingPermission")
    private fun writeNext(g: BluetoothGatt) {
        if (writing) return
        val (uuid, data) = writeQueue.removeFirstOrNull() ?: return
        val ch = charMap[uuid]
        if (ch == null) {
            Log.i(TAG, "写失败，找不到 $uuid")
            writeNext(g)
            return
        }
        writing = true
        Log.i(TAG, "TX $uuid = ${hex(data)}")
        // 官方 App 全部用 Write Command（无响应）：多帧消息必须在几十毫秒内连发完，
        // 否则秤侧的组帧会超时把后面的帧当成新消息。
        val ok: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var code = g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (code != 0) {
                Log.i(TAG, "无响应写入被拒($code)，退回写请求")
                code = g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            code == 0
        } else {
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            val r = g.writeCharacteristic(ch)
            if (!r) {
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            } else r
        }
        if (!ok) {
            writing = false
            handler.postDelayed({ writeNext(g) }, 300)
        } else {
            // 无响应写入的完成回调很快；万一没回也兜底，避免队列卡死
            handler.postDelayed({ if (writing) { writing = false; writeNext(g) } }, 1500)
        }
    }

    // ---------------- 收帧 / 组帧 ----------------

    private fun onFrame(uuid: UUID, v: ByteArray) {
        if (v.size < 5) return
        val hdr = v[0].toInt() and 0xFF
        val len = v[1].toInt() and 0xFF
        val seq = v[2].toInt() and 0xFF
        val dataLen = len - 3
        if (dataLen < 0 || 3 + dataLen > v.size) return
        val data = v.copyOfRange(3, 3 + dataLen)
        val total = (seq shr 4) + 1
        val idx = seq and 0x0F
        val a = assemblies.getOrPut(uuid) { Assembly() }
        if (idx == 0 || a.total != total) a.reset(total)
        a.parts[idx] = data
        Log.i(TAG, "RX $uuid hdr=%02X seq=%02X".format(hdr, seq))
        if ((0 until total).any { a.parts[it] == null }) return
        assemblies.remove(uuid)
        val out = ByteArrayOutputStream()
        for (i in 0 until total) out.write(a.parts[i]!!)
        handleMessage(uuid, hdr, out.toByteArray())
    }

    private fun handleMessage(uuid: UUID, hdr: Int, raw: ByteArray) {
        val encrypted = hdr == 0xCD || hdr == 0xDC
        val p = if (encrypted) {
            val key = workKey
            if (key == null) {
                log("✗ 收到加密数据但还没有工作密钥 $uuid")
                return
            }
            decrypt(raw, key)
        } else raw
        Log.i(TAG, "MSG $uuid ${hex(p)}")

        when (uuid) {
            CH_AUTH_REQ -> {
                if (authed || p.size < 16) return
                if (tokenSent) {
                    log("忽略重复挑战 ${hex(p)}")
                    return
                }
                randA = p.copyOfRange(0, 16)
                randB = ByteArray(16).also { secureRandom.nextBytes(it) }
                log("randA=${hex(randA!!)} randB=${hex(randB!!)}")
                // 订阅没完成前不发，等 enableNext 收尾时统一发
                if (subscribed) sendToken()
            }

            CH_AUTH_TOKEN -> {
                val ra = randA ?: return
                val rb = randB ?: return
                if (authed) return
                val k = ra + rb
                val expect = hmac(hmac(hexToBytes(CAK_HEX) + "9856".toByteArray(), k), k)
                val pass = p.contentEquals(expect)
                log("鉴权校验 ${if (pass) "PASS" else "FAIL"}（期望 ${hex(expect)}）")
                if (!pass) {
                    // 这次挑战没对上，重新要一个（秤每次挑战都会换随机数）
                    authTries++
                    randA = null
                    randB = null
                    tokenSent = false
                    if (authTries <= 3) {
                        log("重新请求挑战（第 $authTries 次）")
                        handler.postDelayed({ if (gatt != null && !authed) requestChallenge() }, 500)
                    } else {
                        log("✗ 鉴权重试 3 次仍失败")
                    }
                    return
                }
                authed = true
                val wk = ByteArray(16).also { secureRandom.nextBytes(it) }
                workKey = wk
                workKeySent = true
                log("工作密钥=${hex(wk)}")
                enqueue(CH_WORK_KEY, buildFrames(wk, true, rootKey(target)))
            }

            CH_WORK_KEY -> {
                if (!workKeySent) {
                    log("忽略工作密钥通道的 ${hex(p)}（尚未发送密钥）")
                    return
                }
                workKeySent = false
                log("工作密钥应答 ${hex(p)}")
                bindSent = true
                enqueue(CH_BIND, buildFrames(bindPayload(), true, workKey))
            }

            CH_BIND -> {
                if (!bindSent) {
                    log("忽略绑定通道的 ${hex(p)}（尚未发送绑定）")
                    return
                }
                bindSent = false
                log("绑定应答 ${hex(p)}")
                handler.postDelayed({
                    if (gatt != null && !userInfoSent) {
                        userInfoSent = true
                        enqueue(CH_USER_INFO, buildFrames(userInfoPayload(), true, workKey))
                    }
                }, 500)
            }

            CH_USER_INFO -> {
                if (!userInfoSent) {
                    log("忽略用户信息通道的 ${hex(p)}（尚未发送用户信息）")
                    return
                }
                userInfoSent = false
                log("用户信息应答 ${hex(p)}")
                onReady()
            }

            CH_RT_WEIGHT -> {
                val rec = parseRecord(p, false)
                if (rec == null) {
                    Log.i(TAG, "空/无效实时记录 ${hex(p)}")
                } else {
                    anyRecord = true
                    log("实时记录 %s %.2fkg 秤体脂%.1f%% 阻抗%d%s".format(
                        fmtTime(rec.tsSec), rec.kg, rec.fatRate, rec.impedance,
                        if (rec.heartRate > 0) " 心率${rec.heartRate}bpm" else ""))
                    pendingRt = rec
                    handler.removeCallbacks(reportRt)
                    handler.postDelayed(reportRt, 2500)
                }
            }

            CH_BODY -> {
                val rec = parseRecord(p, true)
                if (rec == null) {
                    if (p.isEmpty()) log("历史记录读取完毕（${history.size} 条）")
                    else log("未知历史数据 ${hex(p)}")
                    finishHistory()
                } else {
                    anyRecord = true
                    history.add(rec)
                    log("历史记录 %s %.2fkg 体脂%.1f%%".format(fmtTime(rec.tsSec), rec.kg, rec.fatRate))
                    ackBody()
                }
            }

            else -> Log.i(TAG, "忽略 $uuid")
        }
    }

    // ---------------- 握手后的收尾 ----------------

    private fun onReady() {
        if (ready) return
        ready = true
        log("✓ 握手完成，开始心跳 + 实时/历史数据")
        lastHeartbeat = 0L
        lastRtPoll = 0L
        handler.post(ticker)
        // 拉历史记录
        handler.postDelayed({
            if (gatt != null && historyPolls == 0) {
                historyPolls++
                enqueue(CH_BODY, buildFrames(ByteArray(0), false, null))
            }
        }, 500)
    }

    private fun ackBody() {
        if (gatt == null) return
        historyPolls++
        if (historyPolls > 60) {
            finishHistory()
            return
        }
        enqueue(CH_BODY, buildFrames(byteArrayOf(0), false, null))
    }

    private fun finishHistory() {
        if (history.isEmpty()) return
        // 历史通道里都是秤离线缓存的记录，只收本次连接前后 10 分钟内的，避免把旧数据当新称重上传
        val limit = (connectAtSec - 600).coerceAtLeast(0)
        val newest = history.filter { it.tsSec >= limit }.maxByOrNull { it.tsSec }
        history.clear()
        if (newest != null) report("历史最新", newest, true)
        else log("历史记录都早于本次称重，忽略")
    }

    /** 实时记录延迟 2.5s 上报：秤在称重过程中可能连续推多条，最终值才是结果 */
    private val reportRt = object : Runnable {
        override fun run() {
            val rec = pendingRt ?: return
            pendingRt = null
            report("实时", rec, false)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            if (SystemClock.elapsedRealtime() > deadline) {
                log("到时结束")
                runCatching { g.disconnect() }
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastHeartbeat > 3000) {
                lastHeartbeat = now
                enqueue(CH_HEARTBEAT, buildFrames(ByteArray(0), false, null))
            }
            if (!anyRecord && ready) {
                // 实时通道：秤在收到空帧请求后，称完会把记录推过来
                if (rtPolls < 60 && now - lastRtPoll > 5000) {
                    lastRtPoll = now
                    rtPolls++
                    enqueue(CH_RT_WEIGHT, buildFrames(ByteArray(0), false, null))
                }
                // 文件通道兜底：万一实时推送没来，历史里也能捞到刚称的那条
                if (historyPolls < 20 && now - lastBodyPoll > 15000) {
                    lastBodyPoll = now
                    historyPolls++
                    enqueue(CH_BODY, buildFrames(ByteArray(0), false, null))
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun report(tag: String, rec: Record, fromHistory: Boolean) {
        val key = "${rec.tsSec}-${Math.round(rec.kg * 1000)}"
        if (!reported.add(key)) return
        // 秤给的阻抗单位是 0.1Ω，换算成 Ω 交给体成分推算（100..1500 才算有效）
        val ohm = if (rec.impedance in 1000..15000) rec.impedance / 10.0 else null
        val hr = if (rec.heartRate > 0) rec.heartRate else null
        log("✓ [$tag] %s 体重 %.2fkg 体脂 %.1f%%%s%s".format(
            fmtTime(rec.tsSec), rec.kg, rec.fatRate,
            ohm?.let { " 阻抗%.1fΩ".format(it) } ?: "",
            hr?.let { " 心率${it}bpm" } ?: ""))
        val fat = if (rec.fatRate in 1.0..70.0) rec.fatRate else null
        appContext.getSharedPreferences("b1_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("last_scale_kg", rec.kg.toFloat()).apply()
        val measurement = ScaleMeasurement(
            timestampSec = rec.tsSec,
            weightKg = rec.kg,
            resistanceOhm = ohm,
            fromHistory = fromHistory,
            heartRateBpm = hr,
            scaleFatPercent = fat,
        )
        handler.post { onRecord(measurement) }
    }

    // ---------------- 载荷 ----------------

    /** 绑定：19 位数字 ID + 11 个 0x00（官方抓包 30 字节） */
    private fun bindPayload(): ByteArray = ByteArray(30).also {
        val id = DEFAULT_USER_ID.toByteArray()
        System.arraycopy(id, 0, it, 0, minOf(id.size, 19))
    }

    /** 用户信息：同一 ID + 0 填充 + 尾部资料（官方抓包 69 字节） */
    private fun userInfoPayload(): ByteArray {
        // 官方会把「上一次的体重」带给秤，秤据此算体脂；没有历史时沿用抓包里的 78.00kg
        val savedKg = appContext
            .getSharedPreferences("b1_prefs", Context.MODE_PRIVATE)
            .getFloat("last_scale_kg", 0f)
        val raw = if (savedKg > 20f) Math.round(savedKg * 100) else 7800
        val tail = byteArrayOf(
            0x01, 0x20, 0xB3.toByte(), 0x00,
            (raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte(), 0x00)
        return ByteArray(69).also {
            val id = DEFAULT_USER_ID.toByteArray()
            System.arraycopy(id, 0, it, 0, minOf(id.size, 19))
            System.arraycopy(tail, 0, it, it.size - tail.size, tail.size)
        }
    }

    private fun parseRecord(p: ByteArray, historyRecord: Boolean): Record? {
        // 官方校验：实时 26/38 字节，历史 27/28/39/40 字节
        val okLen = if (historyRecord) {
            p.size == 27 || p.size == 28 || p.size == 39 || p.size == 40
        } else {
            p.size == 26 || p.size == 38
        }
        if (!okLen) {
            Log.i(TAG, "记录长度不符（${p.size}B，${if (historyRecord) "历史" else "实时"}）")
            return null
        }
        val y = u16(p, 4)
        val mo = p[6].toInt() and 0xFF
        val d = p[7].toInt() and 0xFF
        val h = p[8].toInt() and 0xFF
        val mi = p[9].toInt() and 0xFF
        val s = p[10].toInt() and 0xFF
        val weight = u16(p, 0) / 100.0
        val fatRate = u16(p, 2) / 10.0
        val impedance = u16(p, 12)
        // 偏移 24..25 是秤端附加项（官方日志里叫 heart）。生理范围内才认作心率
        val rawHeart = u16(p, 24)
        val heartRate = if (rawHeart in 30..220) rawHeart else 0
        if (y < 2015 || mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..59) {
            return null
        }
        // 体脂秤有效量程（空记录体重为 0）
        if (weight < 20.0 || weight > 300.0) return null
        val cal = Calendar.getInstance()
        cal.set(y, mo - 1, d, h, mi, s)
        cal.set(Calendar.MILLISECOND, 0)
        return Record(cal.timeInMillis / 1000, weight, fatRate, impedance, heartRate, historyRecord)
    }

    private fun u16(p: ByteArray, off: Int): Int =
        (p[off].toInt() and 0xFF) or ((p[off + 1].toInt() and 0xFF) shl 8)

    private fun fmtTime(sec: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(java.util.Date(sec * 1000))

    // ---------------- 协议编码 ----------------

    private fun buildFrames(payload: ByteArray, encrypted: Boolean, key: ByteArray?): List<ByteArray> {
        val body = if (encrypted) encryptBlock(payload, key!!) else payload
        val chunks = if (body.isEmpty()) 1 else (body.size + 14) / 15
        val out = ArrayList<ByteArray>(chunks)
        for (i in 0 until chunks) {
            val off = i * 15
            val len = minOf(15, body.size - off).coerceAtLeast(0)
            val f = ByteArray(len + 5)
            f[0] = if (encrypted) 0xDC.toByte() else 0xDB.toByte()
            f[1] = (len + 3).toByte()
            f[2] = ((((chunks - 1) and 0x0F) shl 4) or (i and 0x0F)).toByte()
            if (len > 0) System.arraycopy(body, off, f, 3, len)
            val crc = crc16(f)
            f[f.size - 2] = (crc and 0xFF).toByte()
            f[f.size - 1] = ((crc ushr 8) and 0xFF).toByte()
            out.add(f)
        }
        return out
    }

    /** 加密体 = IV(16) || AES-CTR(key, IV, 明文)，与官方抓包一致 */
    private fun encryptBlock(payload: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(16).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = cipher.doFinal(payload)
        return iv + enc
    }

    private fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        if (blob.size <= 16) return ByteArray(0)
        val iv = blob.copyOfRange(0, 16)
        val ct = blob.copyOfRange(16, blob.size)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    /** CRC16/XMODEM（初值 0），计算时把末尾两个 0x00 也算进去 */
    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun rootKey(mac: String): ByteArray {
        val k1 = hexToBytes(K1_HEX)
        val k2 = hexToBytes(K2_HEX)
        val mix = ByteArray(16) { i ->
            (((k1[i].toInt() shl 4) xor k2[i].toInt()) and 0xFF).toByte()
        }
        val h1 = sha256(mix).copyOf(16)
        // c2 的 hex 文本每字节右移 6 位，再与 ascii(MAC 去冒号 + "0000") 异或
        val hx = h1.joinToString("") { "%02x".format(it) }
        val salt = (mac.replace(":", "") + "0000").toByteArray()
        val t = ByteArray(16) { i ->
            (((hx.substring(i * 2, i * 2 + 2).toInt(16)) ushr 6) xor (salt[i].toInt() and 0xFF)).toByte()
        }
        return sha256(t).copyOf(16)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hexToBytes(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }
}
