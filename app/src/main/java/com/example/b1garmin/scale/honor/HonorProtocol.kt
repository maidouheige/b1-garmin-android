package com.example.b1garmin.scale.honor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.b1garmin.scale.ScaleMeasurement
import com.example.b1garmin.scale.ScaleProtocol
import com.example.b1garmin.scale.ScaleUser
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 华为/荣耀通道。
 *
 * [HonorScaleSession] 是事件驱动的（连上→鉴权→订阅→秤主动推记录），
 * 这里把它适配成统一的「一次 measure 返回记录列表」接口：
 * 拿到第一条记录就收尾断开，和华为运动健康的「称一次就结束」一致。
 */
object HonorProtocol : ScaleProtocol {
    override val id = "HONOR"

    /** 等用户上秤的上限；到点没数据就结束，避免一直占着蓝牙 */
    private const val TIMEOUT_SEC = 180

    override suspend fun measure(
        context: Context,
        address: String,
        name: String,
        user: ScaleUser,
        standby: Boolean,
        onLog: (String) -> Unit,
        onLive: (kg: Double, locked: Boolean) -> Unit,
        onImpedance: (ohm: Double) -> Unit,
    ): List<ScaleMeasurement> = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val results = mutableListOf<ScaleMeasurement>()
        val done = AtomicBoolean(false)
        lateinit var session: HonorScaleSession

        fun finish() {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            session.stop()
            if (cont.isActive) cont.resume(results.toList())
        }

        session = HonorScaleSession(context, onLog) { m ->
            results.add(m)
            onLive(m.weightKg, true)
            m.resistanceOhm?.let { onImpedance(it) }
            finish()
        }
        session.start(address, TIMEOUT_SEC)
        handler.postDelayed({ finish() }, TIMEOUT_SEC * 1000L)

        cont.invokeOnCancellation {
            done.set(true)
            handler.removeCallbacksAndMessages(null)
            session.stop()
        }
    }
}
