package com.example.b1garmin

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.lifecycle.lifecycleScope
import com.example.b1garmin.ble.ScaleReader
import com.example.b1garmin.garmin.GarminAuth
import com.example.b1garmin.garmin.WebLogin
import com.example.b1garmin.protocol.BodyComp
import com.example.b1garmin.protocol.FitWriter
import com.example.b1garmin.scale.BodyCompStandard
import com.example.b1garmin.scale.ScaleCatalog
import com.example.b1garmin.scale.ScaleMeasurement
import com.example.b1garmin.scale.ScaleUser
import com.example.b1garmin.ui.CalibStore
import com.example.b1garmin.ui.HomeScreen
import com.example.b1garmin.ui.WeighStage
import com.example.b1garmin.ui.ProfileScreen
import com.example.b1garmin.ui.Profile
import com.example.b1garmin.ui.ProfileStore
import com.example.b1garmin.ui.ScaleCalib
import com.example.b1garmin.ui.SettingsScreen
import com.example.b1garmin.ui.records.RecordsScreen
import com.example.b1garmin.ui.theme.GarminTheme
import com.example.b1garmin.ui.theme.GTabBar
import com.example.b1garmin.ui.theme.ThemeStore
import com.example.b1garmin.sync.RetryWorker
import com.example.b1garmin.xiaomi.XiaomiCloudAuth
import com.example.b1garmin.ui.VerifyEntry
import com.example.b1garmin.ui.VerifyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 连续对得上几次就固化（以后按阿福的标准算，不用再校准） */
private const val CALIB_FREEZE_STREAK = 3

class MainActivity : ComponentActivity() {
    private lateinit var auth: GarminAuth
    private lateinit var scale: ScaleReader
    private val logs = mutableStateListOf<String>()

    /**
     * 佳明服务器区域：国际版 .com / 国内版 .cn，两套账号数据不互通。
     * 默认国际版（老用户零影响），用户的选择存 prefs，切换时重建 auth。
     */
    private val regionState = mutableStateOf(GarminAuth.Region.GLOBAL)

    /** 已上传记录账本：同一次称重不会重复写进 Garmin */
    private val ledger by lazy { getSharedPreferences("b1_ledger", MODE_PRIVATE) }

    /** 用户资料持久化，省得每次重输 */
    private val prefs by lazy { getSharedPreferences("b1_prefs", MODE_PRIVATE) }

    /** 蓝牙状态变化时自增，让 Compose 重新读状态 */
    private val btTick = mutableStateOf(0)

    /** 调试日志默认折叠；出错时自动展开 */
    private var showDebug by mutableStateOf(false)

    /** 「结果」卡片：只留最后一条关键结论（✓ / ✗ / ⚠ / 完成） */
    private var summary by mutableStateOf("")

    /**
     * 登录状态提到 Activity 级：启动静默续期失败时能把界面上的绿点关掉。
     */
    private val loggedInState = mutableStateOf(false)

    /**
     * 用户资料（身高/年龄/性别/目标体重）：在「资料设置」里改一次就够。
     *
     * ⚠ 这里**不能**直接 `mutableStateOf(ProfileStore.load(this))`：
     * 属性初始化器是在 Activity 构造函数里跑的，那时 attachBaseContext() 还没执行，
     * getSharedPreferences() 会因为 base context 为 null 而抛 NPE —— App 一打开就闪退。
     * 所以初值给默认的，真正去读放在 onCreate 里。
     */
    private val profileState = mutableStateOf(Profile())

    /**
     * 外观偏好（跟随系统/浅色/深色）。放 Activity 级，设置页切换时改这里，
     * GarminTheme 才能重组生效。初值给 SYSTEM，onCreate 里再读持久化值。
     */
    private val themeModeState = mutableStateOf(ThemeStore.SYSTEM)

    /**
     * 状态更新必须在主线程做。
     *
     * 蓝牙回调跑在 Binder 线程、OkHttp 跑在自己的线程池上，而
     * SnapshotStateList 不是线程安全的 —— 后台线程 add() 同时主线程在读，
     * 会抛 ConcurrentModificationException（扫描过程中最容易触发）。
     */
    private val ui = Handler(Looper.getMainLooper())

    /** 上次打开时的崩溃信息（有的话，下次启动直接显示出来） */
    private var lastCrash by mutableStateOf("")

    /** 自动更新：检查到的新版本（null = 没有 / 还没查）。放 Activity 级，跨重组保持。 */
    private var updateInfo by mutableStateOf<Updater.UpdateInfo?>(null)
    /** 是否弹出「发现新版本」对话框 */
    private var showUpdateDialog by mutableStateOf(false)
    /** 手动检查更新后的提示（已是最新 / 检查失败），null = 不弹。放 Activity 级跨重组保持。 */
    private var updateNotice by mutableStateOf<String?>(null)
    /** 下载中的进度文本（空 = 没在下载） */
    private var updateProgress by mutableStateOf("")

    // ---------------- 首页实时称重（打开 App 自动待机监听，站上秤就跳数字）----------------
    /** 最近一次实时体重（kg，0 = 还没上过秤）。跨重组保留，作为首页大卡片的主显示 */
    private var liveKg by mutableStateOf(0.0)
    /** 体重是否已锁定 */
    private var liveLocked by mutableStateOf(false)
    /** 最近拿到的阻抗（Ω，0 = 还没有） */
    private var liveResistance by mutableStateOf(0.0)
    /** 阻抗到手后用当前校准算出的体成分（null = 还没测出阻抗） */
    private var liveEstimate by mutableStateOf<BodyComp.Estimate?>(null)
    /** 当前阶段文字（待机/已连接/称量中/测量体脂中/完成/异常） */
    private var livePhase by mutableStateOf("待机中")
    /** 自动待机监听开关（首页可一键暂停，校准时要让位给阿福） */
    private var autoListen by mutableStateOf(true)
    /** 监听协程句柄，用于停止/重启 */
    private var listenJob: kotlinx.coroutines.Job? = null
    /** 本轮是否已经有人上过秤（用来判断新一次称重开始、清掉上一次的体成分） */
    private var hasLiveThisRound = false
    /** Garmin 账号头像（登录成功后从 socialProfile 拉取）；null = 没登录/加载中/加载失败 */
    private var garminAvatar by mutableStateOf<ImageBitmap?>(null)
    /** Garmin 账号显示名（头像旁的文字） */
    private var garminName by mutableStateOf("")
    private var profileJob: kotlinx.coroutines.Job? = null
    /** 进校准页前是否开着监听，返回时据此恢复 */
    private var resumeListenAfterCalib = false
    /** 进绑定页前是否开着监听，返回时据此恢复 */
    private var resumeListenAfterBind = false

    /** 把称重阶段的中文提示映射到首页徽标阶段（fork Garmin 风 UI 用） */
    private fun weighStageOf(phase: String, autoListen: Boolean): WeighStage = when {
        !autoListen || phase.contains("暂停") || phase.contains("停止") -> WeighStage.PAUSED
        phase.contains("出错") || phase.contains("重试") || phase.contains("异常") -> WeighStage.ERROR
        phase.contains("✓") || phase.contains("完成") -> WeighStage.DONE
        phase.contains("找秤") || phase.contains("在等它") || phase.contains("连接") -> WeighStage.CONNECTING
        phase.contains("连上") || phase.contains("就绪") || phase.contains("站上") -> WeighStage.WAITING
        else -> WeighStage.WAITING
    }

    private fun crashFile() = java.io.File(filesDir, "last_crash.txt")

    private fun readCrashFile(): String {
        return try {
            val f = crashFile()
            if (f.exists() && f.length() > 0) f.readText().take(3000) else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 装一个全局异常处理器：崩溃时把堆栈写到文件，下次打开就能看到。
     *
     * 手机上没有 logcat 也能诊断——用户把界面上那段文字发回来就行。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                crashFile().writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date()) +
                            "\n" + e.javaClass.name + ": " + e.message + "\n\n" +
                            Log.getStackTraceString(e)
                )
            } catch (_: Exception) {
            }
            prev?.uncaughtException(thread, e)
        }
    }

    private fun log(s: String) {
        Log.d("B1Garmin", s)
        if (Looper.myLooper() == Looper.getMainLooper()) appendLog(s)
        else ui.post { appendLog(s) }
    }

    private fun appendLog(s: String) {
        logs.add(s)
        while (logs.size > 250) logs.removeAt(0)
        // 关键结论提炼到「结果」卡片；失败时自动把调试日志展开，省得去找
        val t = s.trim()
        when {
            t.startsWith("✗") -> { summary = t; showDebug = true }
            t.startsWith("✓") || t.startsWith("⚠") || t.startsWith("完成") -> summary = t
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) log("⚠ 权限被拒绝：${denied.joinToString()}")
        btTick.value++
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        btTick.value++
        log(if (scale.isBluetoothReady()) "✓ 蓝牙已开启" else "✗ 蓝牙仍未开启")
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun versionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    // ------------------------------------------------------------------
    // 自动待机监听：打开 App 就连上秤待命，站上去实时跳体重、出阻抗就算体脂
    // ------------------------------------------------------------------

    /**
     * 把一次会话拿到的记录上传 Garmin（复用原来的去重/台账/校准/锚点逻辑，一个字没改）。
     * 实时称重和旧的手动按钮都走这里，保证两条路行为一致。
     * @return “成功上传数” to “跳过数”
     */
    private suspend fun uploadRecords(
        recs: List<ScaleMeasurement>,
        addr: String,
        targetName: String,
        /** 该型号的体成分口径：华为/荣耀不走阿福的阻抗提示与校准偏移 */
        standard: BodyCompStandard = BodyCompStandard.AFU,
    ): Pair<Int, Int> {
        val honor = standard == BodyCompStandard.HUAWEI
        val h = profileState.value.heightCm
        val a = profileState.value.age
        var okCount = 0
        var skipCount = 0
        log("本次拿到 ${recs.size} 条记录（带阻抗 ${recs.count { it.resistanceOhm != null }} 条），开始上传…")
        if (honor) {
            if (recs.all { it.resistanceOhm == null }) {
                log("⚠ 这次没测到阻抗：体脂率退回 BMI 公式估算（光脚踩满四个电极更准）")
            }
        } else if (recs.none { it.resistanceOhm != null }) {
            log("⚠ 这次没测到阻抗：体脂率只能按体重估算，反映不了真实体成分")
            log("   光脚、脚底微湿、四个电极都踩到、站直别动，再称一次更容易出阻抗")
        } else if (calibOf(addr).imp == null) {
            log("· 「${targetName.ifBlank { "这台秤" }}」没手动校准过，按内置阿福公式算（阻抗档 %+.1f）"
                .format(BodyComp.Offsets.AFU_FACTORY.imp ?: 0.0))
            log("   已默认对齐阿福；只有实测和阿福对不上时，才需要去「设置 → 体脂率校准」校准")
        }

        fun estimateOf(rec: ScaleMeasurement): BodyComp.Estimate? =
            if (honor) {
                BodyComp.estimateHuawei(
                    rec.weightKg, h, a, profileState.value.sexText, rec.resistanceOhm,
                    rec.scaleFatPercent)
            } else {
                BodyComp.estimate(
                    rec.weightKg, h, a, profileState.value.sexText,
                    rec.resistanceOhm, savedOffsets(addr))
            }

        // 本地记录仓库（旁路写入，任何异常都不能影响称重/Garmin 上传）
        val recordsRepo = try {
            com.example.b1garmin.data.MeasurementRepository.get(this)
        } catch (e: Exception) {
            Log.w("B1Garmin", "本地记录库初始化失败，本次不落地：${e.message}")
            null
        }
        // 型号解析：优先用绑定秤的型号（地址匹配时），否则按当前连上的广播名推断。
        // 之前误用了不存在的 key「cur_scale_model」，导致记录型号一直为空、筛选全归「未知秤」。
        val boundNow = scale.boundScale()
        val localModel = if (boundNow != null && boundNow.address.equals(addr, ignoreCase = true)) {
            ScaleCatalog.byId(boundNow.model)
        } else {
            ScaleCatalog.match(prefs.getString("cur_scale_name", "") ?: "")
        }

        // 旁路写入本地记录：任何异常都吞掉，绝不影响称重主流程和 Garmin 上传
        suspend fun recordLocal(
            rec: ScaleMeasurement,
            est: BodyComp.Estimate?,
            status: String,
            skipReason: String? = null,
            error: String? = null,
        ) {
            val repo = recordsRepo ?: return
            try {
                repo.record(
                    rec = rec,
                    est = est,
                    heightCm = h.toInt(),
                    age = a,
                    sex = profileState.value.sexText,
                    model = localModel,
                    scaleMac = addr,
                    status = status,
                    skipReason = skipReason,
                    error = error,
                )
            } catch (e: Exception) {
                Log.w("B1Garmin", "本地记录写入失败（不影响上传）：${e.message}")
            }
        }

        recs.forEach { rec ->
            val whenStr = java.text.SimpleDateFormat("MM-dd HH:mm")
                .format(java.util.Date(rec.timestampSec * 1000))
            val key = "${rec.timestampSec}-${Math.round(rec.weightKg * 1000)}"

            var uploaded = false
            var localSkipReason: String? = null
            if (ledger.getBoolean(key, false)) {
                log("$whenStr ${rec.weightKg}kg（已同步过，跳过上传）")
                skipCount++
                localSkipReason = "已同步过"
            } else if (!rec.fromHistory &&
                prefs.getFloat("last_live_kg", 0f).let { lastKg ->
                    lastKg > 0f &&
                        Math.abs(rec.weightKg - lastKg) < 0.03 &&
                        System.currentTimeMillis() / 1000 -
                        prefs.getLong("last_live_at", 0L) < 300
                }
            ) {
                log("$whenStr ${rec.weightKg}kg（5 分钟内刚同步过同一体重，跳过上传）")
                skipCount++
                localSkipReason = "短时间内重复"
            } else {
                uploaded = true
            }

            // 体成分估算（跳转/失败的记录也要算出来存着，记录页才看得见）
            val est = estimateOf(rec)

            // 无论是否真的上传，都把这次称重记进验证台账（阿福的校准台账，荣耀秤不参与）
            if (!honor) {
                val rawBfForVerify = BodyComp.rawBf(
                    rec.weightKg, h, a, profileState.value.sexText, rec.resistanceOhm
                ).first
                val estForVerify = BodyComp.estimate(
                    rec.weightKg, h, a, profileState.value.sexText,
                    rec.resistanceOhm, savedOffsets(addr)
                )
                if (estForVerify != null) {
                    saveVerifyEntry(
                        rec.timestampSec, rec.weightKg,
                        rec.resistanceOhm ?: 0.0, rawBfForVerify,
                        estForVerify.bodyFatPercent, addr
                    )
                }
            }

            if (!uploaded) {
                recordLocal(rec, est, com.example.b1garmin.data.UploadStatus.SKIPPED, localSkipReason)
                return@forEach
            }

            if (est == null) {
                log("$whenStr ${rec.weightKg}kg ✗ 体成分推算失败")
                recordLocal(rec, null, com.example.b1garmin.data.UploadStatus.FAILED,
                    error = "体成分推算失败")
                return@forEach
            }
            // 先打一行体成分明细：没登录也可以直接和手机厂商 App 对数
            log("体成分 %.2fkg/%.0fcm/%d岁：体脂 %.1f%% · 骨骼肌 %.1fkg · 水分 %.1f%% · 骨盐 %.2fkg · BMR %dkcal · 内脏脂肪 %d · BMI %.1f"
                .format(est.weightKg, h, a, est.bodyFatPercent, est.muscleMassKg,
                    est.percentHydration, est.boneMassKg, est.basalMet,
                    est.visceralFatRating, est.bmi))
            val fit = FitWriter.build(est, rec.timestampSec)
            val up = auth.uploadFit(fit, "weight_${rec.timestampSec}.fit")
            if (up.isSuccess) {
                ledger.edit().putBoolean(key, true).apply()
                prefs.edit()
                    .putFloat("last_live_kg", rec.weightKg.toFloat())
                    .putLong("last_live_at", System.currentTimeMillis() / 1000)
                    .putLong("last_up_ts", rec.timestampSec)
                    .putFloat("last_up_kg", rec.weightKg.toFloat())
                    .putFloat("last_up_res", (rec.resistanceOhm ?: 0.0).toFloat())
                    .putString("last_up_addr", addr)
                    .apply()
                saveAnchor(Anchor(rec.timestampSec, rec.weightKg,
                    rec.resistanceOhm ?: 0.0, addr))
                okCount++
                val muscleLabel = if (honor) "骨骼肌" else "肌肉"
                log("$whenStr ${rec.weightKg}kg ✓ 已上传（体脂 %.1f%% · $muscleLabel %.1fkg · 水分 %.1f%%%s）"
                    .format(est.bodyFatPercent, est.muscleMassKg, est.percentHydration,
                        rec.heartRateBpm?.let { " · 心率 ${it}bpm" } ?: ""))
                recordLocal(rec, est, com.example.b1garmin.data.UploadStatus.UPLOADED)
            } else {
                val err = up.exceptionOrNull()?.message ?: "上传失败"
                log("$whenStr ${rec.weightKg}kg ✗ $err")
                recordLocal(rec, est, com.example.b1garmin.data.UploadStatus.FAILED, error = err)
            }
            delay(300)
        }
        return okCount to skipCount
    }

    /** 启动自动监听（幂等） */
    private fun startListen() {
        if (listenJob?.isActive == true) return
        listenJob = lifecycleScope.launch { autoWeighLoop() }
    }

    /** 停止自动监听，让出蓝牙给阿福 */
    private fun stopListen() {
        listenJob?.cancel()
        listenJob = null
        livePhase = "已暂停（点继续恢复自动称重）"
    }

    /**
     * 自动监听主循环：找秤 → 连上待命 → 人站上去实时跳体重 → 阻抗到就算体脂 → 上传 → 断开 → 重来。
     * 每轮之间留 2 秒，避免疯狂重连；蓝牙/权限/登录没就绪时会等待，不打扰用户。
     */
    private suspend fun autoWeighLoop() {
        /** 单次称重型号（华为/荣耀/小米）是正常收尾，不要把完成提示覆盖成「已停止」 */
        var finishedSingleShot = false
        while (autoListen) {
            // 蓝牙或权限没就绪就等着，10 秒后再试
            val diag = scale.diagnose()
            if (diag != null) {
                livePhase = if (!scale.isBluetoothReady()) "请打开手机蓝牙" else "请授予蓝牙权限"
                delay(3000)
                continue
            }

            // 小米秤：绑定后走被动广播通道（不建 GATT 连接），findScale 靠名字认不出它，
            // 必须在找秤之前分流。这里只处理已绑定的小米秤；未绑定的小米秤走正常扫描流程（通常认不出）。
            val boundNow = scale.boundScale()
            val boundModel = boundNow?.let { ScaleCatalog.byId(it.model) }
            if (boundModel?.id?.startsWith("XIAOMI_") == true && boundNow != null) {
                try {
                    val hM = profileState.value.heightCm
                    val aM = profileState.value.age
                    val maleM = profileState.value.male
                    val userM = ScaleUser(age = aM, male = maleM, heightCm = hM.toInt(),
                        targetKg = profileState.value.targetKg.takeIf { it > 0.0 } ?: (hM * hM * 22.0 / 10000.0))
                    ui.post { livePhase = "小米秤已就绪，光脚站上去（测量约需 15 秒）" }
                    val recs = boundModel.protocol.measure(
                        context = this@MainActivity,
                        address = boundNow.address,
                        name = boundNow.name,
                        user = userM,
                        standby = false,
                        onLog = { log(it) },
                        onLive = { kg, locked ->
                            ui.post {
                                if (!hasLiveThisRound) { hasLiveThisRound = true; liveEstimate = null; liveResistance = 0.0 }
                                liveKg = kg; liveLocked = locked
                                livePhase = if (locked) "✓ 体重 $kg kg，等阻抗…" else "正在读小米秤…"
                            }
                        },
                        onImpedance = { ohm -> ui.post { liveResistance = ohm } },
                    )
                    if (recs.isNotEmpty()) {
                        val (ok, _) = uploadRecords(recs, boundNow.address, boundNow.name, boundModel.bodyComp)
                        ui.post { livePhase = if (ok > 0) "✓ 已同步 $ok 条到 Garmin" else "✓ 称重完成" }
                    }
                } catch (e: Exception) {
                    log("✗ 小米秤监听出错：${e.message}")
                    ui.post { livePhase = "小米秤读取出错，点「称重」重试" }
                }
                autoListen = false
                finishedSingleShot = true
                ui.post {
                    livePhase = if (hasLiveThisRound || liveKg > 0) "✓ 称重完成（点「称重」再称一次）"
                                else "没读到数据，请点「称重」重试"
                }
                break
            }

            // 找秤（记住的优先，扫到就停）
            val target = try {
                scale.findScale(4000) { }
            } catch (e: Exception) {
                delay(2000)
                null
            }
            if (target == null) {
                livePhase = "在找秤…（轻踩一下秤唤醒它）"
                delay(2000)
                continue
            }
            if (!autoListen) break

            // 已绑定：只认这台 MAC，别的秤一律不连（官方阿福"先绑定再用"的逻辑）。
            // 没绑定过时保持原样，任何人扫到的候选秤都能连。
            val bound = scale.boundScale()
            if (bound != null && !bound.address.equals(target.address, ignoreCase = true)) {
                livePhase = "已绑定「${bound.name.ifBlank { bound.address.take(8) }}」，在等它…"
                delay(2000)
                continue
            }

            scale.remember(target)
            val addr = target.address
            prefs.edit()
                .putString("cur_scale_addr", addr)
                .putString("cur_scale_name", target.name.ifBlank { "这台秤" })
                .apply()
            if (calibOf(addr).name.isBlank()) {
                saveCalibOf(addr, calibOf(addr).copy(name = target.name.ifBlank { addr.take(8) }))
            }
            hasLiveThisRound = false
            livePhase = "已连上秤，光脚站上去就开始称重"

            val h = profileState.value.heightCm
            val a = profileState.value.age
            val male = profileState.value.male
            val targetKg = profileState.value.targetKg.takeIf { it > 0.0 }
                ?: (h * h * 22.0 / 10000.0)

            // 型号判定：已绑定就用绑定时定死的型号，未绑定才按广播名/服务识别（与扫描、绑定页同一张注册表）。
            // 注意：保留「未绑定也能用」的原有宽容行为——这是老用户更新后不重新绑定也能称的关键。
            val modelInfo = bound?.let { ScaleCatalog.byId(it.model) }
                ?: ScaleCatalog.match(target.name, target.hasAfuService, target.hasA1Service)
                ?: ScaleCatalog.B1
            val user = ScaleUser(age = a, male = male, heightCm = h.toInt(), targetKg = targetKg)

            // 实时回调：BLE 回调在 Binder 线程，必须切主线程更新 Compose 状态
            val onLiveCb: (Double, Boolean) -> Unit = { kg, locked ->
                ui.post {
                    if (!hasLiveThisRound) {
                        hasLiveThisRound = true
                        liveEstimate = null
                        liveResistance = 0.0
                    }
                    liveKg = kg
                    liveLocked = locked
                    livePhase = if (locked) "体重已锁定 · 光脚站稳别动，等体脂" else "正在称重…"
                }
            }
            val onImpCb: (Double) -> Unit = { ohm ->
                ui.post {
                    liveResistance = ohm
                    liveEstimate = if (modelInfo.bodyComp == BodyCompStandard.HUAWEI) {
                        BodyComp.estimateHuawei(liveKg, h, a, profileState.value.sexText, ohm, null)
                    } else {
                        BodyComp.estimate(liveKg, h, a, profileState.value.sexText, ohm, savedOffsets(addr))
                    }
                    livePhase = "✓ 体成分已算出，数据已更新"
                }
            }

            try {
                log("识别为 ${modelInfo.displayName}（${modelInfo.brand}${if (modelInfo.experimental) " · 实验性" else ""}）")
                if (modelInfo.bodyComp == BodyCompStandard.HUAWEI) {
                    ui.post { livePhase = "${modelInfo.brand}秤已连上，光脚站稳 15 秒（体重/体脂/心率一起测）" }
                }
                val recs = modelInfo.protocol.measure(
                    context = this@MainActivity,
                    address = addr,
                    name = target.name,
                    user = user,
                    standby = !modelInfo.singleShot,
                    onLog = { log(it) },
                    onLive = onLiveCb,
                    onImpedance = onImpCb,
                )

                if (recs.isNotEmpty()) {
                    // 华为/荣耀：首页体成分用秤端体脂口径，和上传时的算法保持一致
                    if (modelInfo.bodyComp == BodyCompStandard.HUAWEI) {
                        recs.firstOrNull { it.weightKg > 0 }?.let { r ->
                            ui.post {
                                liveKg = r.weightKg
                                liveLocked = true
                                r.resistanceOhm?.let { liveResistance = it }
                                liveEstimate = BodyComp.estimateHuawei(
                                    r.weightKg, h, a, profileState.value.sexText,
                                    r.resistanceOhm, r.scaleFatPercent
                                )
                            }
                        }
                    }
                    val (ok, _) = uploadRecords(recs, addr, target.name, modelInfo.bodyComp)
                    ui.post {
                        livePhase = if (ok > 0) "✓ 已同步 $ok 条到 Garmin" else "✓ 称重完成"
                    }
                } else if (hasLiveThisRound) {
                    ui.post { livePhase = "这次没拿到完整数据，请再站一次" }
                }

                if (modelInfo.singleShot) {
                    // 单次称重型号：称一次就结束，不在后台循环重连重复称
                    autoListen = false
                    finishedSingleShot = true
                    ui.post {
                        livePhase = if (hasLiveThisRound || recs.isNotEmpty())
                            "✓ 称重完成（点「称重」再称一次）"
                        else "没拿到完整数据，请点「称重」再试"
                    }
                    break
                }
            } catch (e: Exception) {
                log("✗ 监听出错：${e.message}")
                ui.post { livePhase = "连接出错，稍后自动重试" }
            }

            delay(2000) // 秤断开后缓一下再重连
        }
        if (!finishedSingleShot) ui.post { livePhase = "自动称重已停止" }
    }

    /** 检查是否有新版本。silent=true（启动自动查）失败/无新版都不打扰；手动查则一定有可见反馈。 */
    private suspend fun checkUpdate(silent: Boolean = false) {
        val result = Updater.check(versionCode())
        result.onSuccess { info ->
            if (info == null) {
                log("✓ 已是最新版本（v${versionName()}）")
                // 手动点「检查更新」时给个明确提示，别让用户点了没反应
                if (!silent) updateNotice = "✓ 当前已是最新版本（v${versionName()}），无需更新。"
            } else {
                updateInfo = info
                showUpdateDialog = true
                log("发现新版本 v${info.versionName}，当前 v${versionName()}")
            }
        }.onFailure { e ->
            // 静默检查失败很常见（没网/Gitee 抽风），别弹窗打扰；手动检查则提示
            log("检查更新失败（不影响使用）：${e.message}")
            if (!silent) updateNotice = "检查更新失败：${e.message ?: "未知错误"}\n（不影响正常使用，可稍后重试）"
        }
    }

    /** 下载并调起安装。进度写进 updateProgress，显示在对话框上。 */
    private suspend fun doUpdate(info: Updater.UpdateInfo) {
        updateProgress = "正在连接…"
        val result = Updater.download(this, info) { pct, bytes ->
            val kb = bytes / 1024
            updateProgress = if (pct >= 0) "正在下载… $pct%（${kb} KB）"
                             else "正在下载… ${kb} KB"
        }
        result.onSuccess { file ->
            updateProgress = "下载完成，正在打开安装界面…"
            log("✓ 新版已下载，调起安装（大小 ${file.length() / 1024} KB）")
            try {
                Updater.install(this, file)
                showUpdateDialog = false
                updateProgress = ""
            } catch (e: Exception) {
                updateProgress = ""
                log("✗ 调起安装失败：${e.message}")
            }
        }.onFailure { e ->
            updateProgress = ""
            log("✗ ${e.message}")
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                // 部分机型即使有 BLUETOOTH_SCAN 仍要求定位权限才返回扫描结果
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    /** 切换佳明区域：重建 auth（各自独立的 token），刷新登录态，持久化选择。 */
    private fun switchRegion(region: GarminAuth.Region) {
        if (regionState.value == region) return
        regionState.value = region
        prefs.edit().putString("garmin_region", region.name).apply()
        auth = GarminAuth(this, region)
        auth.setLogSink { log(it) }
        loggedInState.value = auth.load() != null
        log("已切换到佳明${region.label}（${region.domain}），${if (loggedInState.value) "读取到已有登录" else "尚未登录"}")
    }

    /** 拉 Garmin 账号资料（头像 + 显示名）。失败只记日志，不影响称重。 */
    private fun loadGarminProfile(force: Boolean = false) {
        if (auth.load() == null) {
            garminAvatar = null
            garminName = ""
            return
        }
        if (!force && profileJob?.isActive == true) return
        profileJob?.cancel()
        profileJob = lifecycleScope.launch {
            val p = auth.fetchSocialProfile().getOrElse {
                log("读取 Garmin 账号资料失败（不影响称重）：${it.message}")
                return@launch
            }
            if (p.displayName.isNotBlank()) garminName = p.displayName
            val url = p.imageUrl ?: return@launch
            auth.fetchImage(url).onSuccess { bytes ->
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) garminAvatar = bmp.asImageBitmap()
            }.onFailure { log("头像下载失败：${it.message}") }
        }
    }

    private fun askPermissions() {
        val need = requiredPermissions()
        if (need.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(need)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化主题：读偏好（不能在非 Composable 中调用 isSystemInDarkTheme）
        themeModeState.value = try { ThemeStore.load(this) } catch (e: Exception) { ThemeStore.SYSTEM }
        com.example.b1garmin.ui.theme.GColor.setDark(themeModeState.value == ThemeStore.DARK)

        val savedRegion = try {
            prefs.getString("garmin_region", GarminAuth.Region.GLOBAL.name)?.let {
                GarminAuth.Region.valueOf(it)
            } ?: GarminAuth.Region.GLOBAL
        } catch (e: Exception) { GarminAuth.Region.GLOBAL }
        regionState.value = savedRegion
        auth = GarminAuth(this, savedRegion)
        scale = ScaleReader(this)
        auth.setLogSink { log(it) }
        loggedInState.value = auth.load() != null
        // 资料要等 Context attach 之后才能读（见 profileState 的注释）。
        // 再包一层 try：万一数据文件坏了，也只是用默认值，不至于开不了 App。
        profileState.value = try {
            ProfileStore.load(this)
        } catch (e: Exception) {
            Log.w("B1Garmin", "读资料失败，用默认值：${e.message}")
            Profile()
        }
        // 上次打开如果崩了，把现场留下来，下次进来直接显示，省得抓不到 logcat
        lastCrash = readCrashFile()
        installCrashHandler()
        // 老版本的校准值是全局一份，升级后迁到「当前那台秤」名下，不用重新校准
        try {
            migrateLegacyCalibration()
        } catch (e: Exception) {
            Log.w("B1Garmin", "迁移旧校准值失败：${e.message}")
        }

        askPermissions()

        // 老版本写入记录时型号 key 写错，导致「未知型号/未知秤」。
        // 这里按当前绑定秤把同 MAC 的空型号历史记录补上，避免一条记录既在 B1 又在未知秤。
        lifecycleScope.launch {
            try {
                scale.boundScale()?.let { bound ->
                    ScaleCatalog.byId(bound.model)?.let { m ->
                        com.example.b1garmin.data.MeasurementRepository.get(this@MainActivity)
                            .backfillModel(bound.address, m.displayName, m.brand)
                    }
                }
            } catch (e: Exception) {
                Log.w("B1Garmin", "补型号失败：${e.message}")
            }
        }

        // 启动后台补传：每 6 小时有网时自动重试上传失败的记录
        RetryWorker.schedule(this)

        // 打开 App 就自动进入称重待机：连上秤、等人站上去。
        // 权限是异步授予的，autoWeighLoop 内部会等蓝牙/权限就绪，不会卡死。
        startListen()

        // 启动时静默续期：access_token 只有约 1 天寿命，用 refresh_token 自动换新的，
        // 这样只要偶尔开一次 App 就永远不会掉登录
        lifecycleScope.launch {
            delay(800)
            if (auth.load() == null) return@launch
            log("检查登录有效期…")
            val r = auth.validToken()
            if (r.isSuccess) {
                loggedInState.value = true
                log("✓ 登录状态有效（已自动续期）")
                loadGarminProfile()
            } else {
                // 本地有 token 不代表还能用，续期失败要把界面上的「已登录」灭掉
                loggedInState.value = false
                log("✗ 登录已过期，请重新登录：${r.exceptionOrNull()?.message}")
            }
        }

        // 启动时静默检查更新（失败不打扰用户，只在日志里记一句）
        lifecycleScope.launch {
            delay(1500)
            checkUpdate(silent = true)
        }

        setContent {
            val isDark = when (themeModeState.value) {
                ThemeStore.DARK -> true
                ThemeStore.LIGHT -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            GarminTheme(dark = isDark) {
                val scope = rememberCoroutineScope()
                var tabIndex by remember { mutableStateOf(0) }            // 主 tab：0=首页 1=记录 2=我的
                var showLogin by remember { mutableStateOf(false) }       // 网页登录 WebView（子页）
                var showLoginPage by remember { mutableStateOf(false) }   // 登录页（子页）
                var showSettings by remember { mutableStateOf(false) }    // 资料编辑页（子页）
                var showCalib by remember { mutableStateOf(false) }       // 校准（子页）
                var showBind by remember { mutableStateOf(false) }        // 绑定秤（子页，从「我的」进）
                // 首页「最近同步」列表：订阅本地 Room，称重落地后自动刷新
                val recent by androidx.compose.runtime.produceState<List<com.example.b1garmin.data.MeasurementEntity>?>(initialValue = null) {
                    com.example.b1garmin.data.MeasurementRepository.get(this@MainActivity)
                        .observeRecent(5).collect { value = it }
                }
                val loggedIn by loggedInState

                // 返回键：先关子页面；没子页时二级 tab 回首页；在首页才退出 App。
                BackHandler(enabled = showLogin || showLoginPage || showSettings ||
                        showCalib || showBind || tabIndex != 0) {
                    when {
                        showLogin -> showLogin = false
                        showLoginPage -> showLoginPage = false
                        showSettings -> showSettings = false
                        showCalib -> {
                            showCalib = false
                            if (resumeListenAfterCalib) startListen()
                        }
                        showBind -> {
                            showBind = false
                            if (resumeListenAfterBind) startListen()
                        }
                        tabIndex != 0 -> tabIndex = 0
                    }
                }

                if (showSettings) {
                    SettingsScreen(
                        initial = profileState.value,
                        onSave = { p ->
                            ProfileStore.save(this, p)
                            profileState.value = p
                            showSettings = false
                            log("✓ 资料已保存：身高 %.1fcm · %s · %d岁".format(p.heightCm, p.sexLabel, p.age))
                        },
                        onClose = { showSettings = false }
                    )
                } else if (showLogin) {
                    val currentRegion = regionState.value
                    val web = remember(currentRegion) {
                        WebLogin(
                            this,
                            auth = auth,
                            onTicket = { ticket ->
                                log("抓到 ticket（长度 ${ticket.length}），正在换取 token…")
                                showLogin = false
                                showLoginPage = false
                                scope.launch {
                                    val r = auth.exchangeTicket(ticket)
                                    loggedInState.value = r.isSuccess
                                    log(
                                        if (r.isSuccess) "✓ Garmin 登录成功"
                                        else "✗ ${r.exceptionOrNull()?.message}"
                                    )
                                    if (r.isSuccess) loadGarminProfile(force = true)
                                }
                            },
                            onLog = { log(it) }
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                        web.Screen(onClose = { showLogin = false })
                        FloatingActionButton(
                            onClick = { showLogin = false },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                } else if (showLoginPage) {
                    LoginScreen(
                        loggedIn = loggedIn,
                        loggedInState = loggedInState,
                        region = regionState.value,
                        onRegionChange = { switchRegion(it) },
                        onWebLogin = { showLogin = true },
                        onBack = { showLoginPage = false },
                    )
                } else if (showCalib) {
                    CalibScreen(
                        loggedIn = loggedIn,
                        onBack = {
                            showCalib = false
                            if (resumeListenAfterCalib) startListen()
                        },
                    )
                } else if (showBind) {
                    BindScaleScreen(
                        onBack = {
                            showBind = false
                            if (resumeListenAfterBind) startListen()
                        },
                    )
                } else {
                    // 主界面：三个 tab 常驻底部；子页面（登录/校准/绑定/资料）整屏盖在上面
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (tabIndex) {
                                    0 -> HomeScreen(
                                        avatar = garminAvatar,
                                        garminName = garminName,
                                        boundLabel = scale.boundScale()?.let {
                                            ScaleCatalog.byId(it.model)?.displayName
                                        },
                                        autoListen = autoListen,
                                        liveKg = liveKg,
                                        liveLocked = liveLocked,
                                        livePhase = livePhase,
                                        weighStage = weighStageOf(livePhase, autoListen),
                                        estimate = liveEstimate,
                                        summary = summary,
                                        logs = logs,
                                        showDebug = showDebug,
                                        lastCrash = lastCrash,
                                        recent = recent,
                                        onWeigh = {
                                            autoListen = true
                                            startListen()
                                            livePhase = "正在找秤…（轻踩一下秤唤醒）"
                                        },
                                        onToggleListen = {
                                            autoListen = !autoListen
                                            if (autoListen) {
                                                startListen()
                                                livePhase = "正在找秤…（轻踩一下秤唤醒）"
                                            } else {
                                                stopListen()
                                            }
                                        },
                                        onOpenProfile = { tabIndex = 2 },
                                        onOpenRecords = { tabIndex = 1 },
                                        onToggleDebug = { showDebug = !showDebug },
                                        onCopyLogs = {
                                            try {
                                                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cb.setPrimaryClip(ClipData.newPlainText("B1日志", logs.joinToString("\n")))
                                                ui.post { summary = "✓ 日志已复制，可粘贴发给别人" }
                                            } catch (e: Exception) {
                                                ui.post { summary = "复制失败：${e.message}" }
                                            }
                                        },
                                        onClearLogs = { logs.clear(); summary = "" },
                                        onClearCrash = {
                                            try { crashFile().delete(); lastCrash = "" } catch (_: Exception) {}
                                        },
                                    )
                                    1 -> RecordsScreen(onRetry = { entity ->
                                        if (auth.load() == null) {
                                            kotlin.Result.failure(Exception("请先登录 Garmin"))
                                        } else {
                                            com.example.b1garmin.data.RecordRetry.retry(
                                                entity, auth,
                                                com.example.b1garmin.data.MeasurementRepository.get(this@MainActivity),
                                                ledger,
                                            ) { log(it) }
                                        }
                                    })
                                    2 -> ProfileScreen(
                                        version = versionName(),
                                        loggedIn = loggedIn,
                                        regionLabel = regionState.value.label,
                                        avatar = garminAvatar,
                                        garminName = garminName,
                                        profile = profileState.value,
                                        recent = recent,
                                        autoListen = autoListen,
                                        boundLabel = scale.boundScale()?.let {
                                            ScaleCatalog.byId(it.model)?.displayName
                                        },
                                        btState = { btTick.value; scale.stateText() },
                                        btReady = { btTick.value; scale.isBluetoothReady() },
                                        btDiag = { btTick.value; scale.diagnose() },
                                        onOpenLogin = { showLoginPage = true },
                                        onOpenProfile = { showSettings = true },
                                        onOpenCalib = {
                                            resumeListenAfterCalib = autoListen
                                            stopListen()
                                            showCalib = true
                                        },
                                        onOpenBind = {
                                            resumeListenAfterBind = autoListen
                                            stopListen()
                                            showBind = true
                                        },
                                        onToggleListen = {
                                            autoListen = !autoListen
                                            if (autoListen) startListen() else stopListen()
                                        },
                                        onEnableBluetooth = {
                                            if (scale.isBluetoothReady()) {
                                                log("蓝牙已经是开着的")
                                            } else {
                                                log("正在打开蓝牙…")
                                                try {
                                                    enableBtLauncher.launch(scale.enableBluetoothIntent())
                                                } catch (e: Exception) {
                                                    log("✗ 无法打开蓝牙：${e.message}")
                                                }
                                            }
                                        },
                                        onRequestPermission = {
                                            askPermissions()
                                            log("已请求蓝牙权限，请在弹出的对话框里选「允许」")
                                        },
                                        onCheckUpdate = { scope.launch { checkUpdate(silent = false) } },
                                        themeMode = themeModeState.value,
                                        onThemeModeChange = { mode ->
                                            ThemeStore.save(this@MainActivity, mode)
                                            themeModeState.value = mode
                                            log("✓ 主题已切换为「${
                                                when (mode) {
                                                    ThemeStore.SYSTEM -> "跟随系统"
                                                    ThemeStore.LIGHT -> "浅色"
                                                    else -> "深色"
                                                }
                                            }」")
                                        },
                                    )
                                }
                            }
                            // 底部 tab 常驻，切页只换上面的内容区
                            GTabBar(selected = tabIndex, onSelect = { tabIndex = it })
                        }
                    }
                }

                // 更新弹窗放导航外层：在首页或设置页点「检查更新」都能看到
                if (showUpdateDialog && updateInfo != null) {
                    val info = updateInfo!!
                    AlertDialog(
                        onDismissRequest = {
                            if (updateProgress.isEmpty()) showUpdateDialog = false
                        },
                        title = { Text("发现新版本 v${info.versionName}") },
                        text = {
                            Column {
                                Text("当前版本 v${versionName()}")
                                Spacer(Modifier.height(8.dp))
                                Text("更新内容：", style = MaterialTheme.typography.bodySmall)
                                val lines = info.noteLines
                                if (lines.isEmpty()) {
                                    Text("（本次为常规更新）")
                                } else {
                                    lines.forEach { line ->
                                        Text("• $line")
                                        Spacer(Modifier.height(2.dp))
                                    }
                                }
                                if (updateProgress.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(updateProgress, color = MaterialTheme.colorScheme.primary)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(enabled = updateProgress.isEmpty(),
                                onClick = { scope.launch { doUpdate(info) } }
                            ) { Text("下载并更新") }
                        },
                        dismissButton = {
                            TextButton(enabled = updateProgress.isEmpty(),
                                onClick = { showUpdateDialog = false }
                            ) { Text("稍后") }
                        }
                    )
                }

                // 手动检查更新后的提示：已是最新 / 检查失败，都给用户看得见的回应
                val notice = updateNotice
                if (notice != null) {
                    AlertDialog(
                        onDismissRequest = { updateNotice = null },
                        title = { Text("检查更新") },
                        text = { Text(notice) },
                        confirmButton = {
                            TextButton(onClick = { updateNotice = null }) { Text("知道了") }
                        }
                    )
                }
            }
        }
    }

    // ====================================================================
    // 校准 & 修正已上传记录
    // ====================================================================

    /**
     * 某台秤的校准偏移（阻抗档 / BMI 档，未校准的档为 null）。
     *
     * **按秤分别存**：不同型号、不同批次的秤阻抗特性不一样，
     * 同一套偏移在 A 秤上对，换到 B 秤就偏。所以校准值跟着「哪台秤」走，
     * 换新秤只要重新校那台，之前的校准还留着。
     */
    private fun savedOffsets(addr: String): BodyComp.Offsets {
        val c = calibOf(addr)
        // 出厂已内置阿福标准：没手动校准过的档直接用出厂默认，开箱即和阿福对齐。
        // 真机校准过的值（c.imp / c.bmi）优先，不会被默认值覆盖。
        return BodyComp.Offsets(imp = c.imp, bmi = c.bmi).withFactoryDefault()
    }

    /** 校准状态（内存缓存 + 落盘） */
    private var calibCache: MutableMap<String, ScaleCalib>? = null

    private fun calibMap(): MutableMap<String, ScaleCalib> {
        if (calibCache == null) calibCache = CalibStore.load(this)
        return calibCache!!
    }

    private fun calibOf(addr: String): ScaleCalib = CalibStore.of(calibMap(), addr)

    private fun saveCalibOf(addr: String, c: ScaleCalib) {
        if (addr.isBlank()) return
        val map = calibMap()
        CalibStore.put(map, addr, c)
        CalibStore.save(this, map)
    }

    /** 当前用的那台秤（MAC + 名字） */
    private fun curScaleAddr(): String = prefs.getString("cur_scale_addr", "") ?: ""

    /**
     * 当前秤的地址：优先用本次连上的，没有就退回「上次连过的那台」。
     *
     * 兜底很重要 —— 校准值是按秤存的，地址空了就会存不进去。
     */
    private fun currentScaleAddr(): String =
        curScaleAddr().ifBlank { scale.lastDeviceAddress() ?: "" }
    private fun curScaleName(): String = prefs.getString("cur_scale_name", "这台秤") ?: "这台秤"

    /**
     * 老版本（0.9.x 及以前）的校准值是全局一份，迁到「当前那台秤」名下。
     * 这样升级后不用重新校准。
     */
    private fun migrateLegacyCalibration() {
        val legacyImp = if (prefs.contains("off_imp")) prefs.getFloat("off_imp", 0f).toDouble() else null
        val legacyBmi = if (prefs.contains("off_bmi")) prefs.getFloat("off_bmi", 0f).toDouble() else null
        if (legacyImp == null && legacyBmi == null) return
        val addr = currentScaleAddr().ifBlank { scale.lastDeviceAddress() ?: "" }
        if (addr.isBlank()) return   // 还不知道是哪台秤，等同步一次再迁
        val c = calibOf(addr)
        saveCalibOf(addr, c.copy(
            imp = legacyImp ?: c.imp,
            bmi = legacyBmi ?: c.bmi,
            streak = prefs.getInt("calib_streak", 0),
            frozen = prefs.getBoolean("calib_frozen", false),
            at = System.currentTimeMillis() / 1000,
        ))
        prefs.edit().remove("off_imp").remove("off_bmi")
            .remove("calib_streak").remove("calib_frozen").apply()
        log("✓ 已把旧版校准值迁到「${curScaleName()}」，不用重新校准")
    }

    private fun profileHeight() = profileState.value.heightCm
    private fun profileAge() = profileState.value.age
    private fun profileSex() = profileState.value.sexText

    // ------------------------------------------------------------------
    // 锚点库：本 App 上读到过、并且成功上传过的测量记录
    // ------------------------------------------------------------------

    /**
     * 一条带阻抗的测量记录，用作校准锚点。
     *
     * 为什么需要它：阿福那次称重，秤会存进自己的历史，我们的 App 读历史时
     * **同样能读到那条（含阻抗）**。所以只要拿阿福显示的体重去匹配，就能直接
     * 用那条当锚点 —— 不必在本 App 上再称一次。
     */
    private data class Anchor(
        val ts: Long,
        val kg: Double,
        val res: Double,
        /** 这条是哪台秤测的（换秤后校准标准不同，不能混用） */
        val addr: String = "",
    )

    private fun loadAnchors(): List<Anchor> {
        val raw = prefs.getString("anchors", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val fallbackAddr = currentScaleAddr()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Anchor(
                    o.optLong("ts"),
                    o.optDouble("kg", 0.0),
                    o.optDouble("res", 0.0),
                    o.optString("addr").ifBlank { fallbackAddr },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAnchor(a: Anchor) {
        val list = (loadAnchors().filter { it.ts != a.ts } + a).takeLast(40)
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject()
                .put("ts", it.ts).put("kg", it.kg).put("res", it.res).put("addr", it.addr))
        }
        prefs.edit().putString("anchors", arr.toString()).apply()
    }

    /**
     * 找与目标体重最接近、且**带阻抗**的锚点。
     * 体重差 0.6kg 以内才算「同一次测量」—— 差太多说明不是同一次数，套用会算错。
     * 只在**同一台秤**里找：别的秤的阻抗特性不一样，套用会算错。
     */
    private fun findAnchor(targetKg: Double, addr: String): Anchor? =
        loadAnchors()
            .filter { it.res > 0 && Math.abs(it.kg - targetKg) < 0.6 }
            .filter { addr.isBlank() || it.addr.isBlank() || it.addr == addr }
            .minByOrNull { Math.abs(it.kg - targetKg) }

    private fun anchorTime(ts: Long) =
        java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(ts * 1000))

    private fun nowSec() = System.currentTimeMillis() / 1000

    // ------------------------------------------------------------------
    // 验证台账：拿阿福的数值和我们当时算出来的值比
    // ------------------------------------------------------------------

    /**
     * 记下一次测量（标准公式的原始值 + 校准后的值），供以后和阿福比对。
     *
     * calcBf 是**测量当时**那套校准标准算出来的值，也就是真正传进 Garmin 的那个数。
     */
    private fun saveVerifyEntry(
        ts: Long, kg: Double, res: Double, rawBf: Double, calcBf: Double, addr: String,
    ) {
        val list = VerifyStore.load(this)
        list.removeAll { it.ts == ts }
        list.add(VerifyEntry(ts, kg, res, rawBf, calcBf, null, addr))
        VerifyStore.save(this, list)
    }

    /**
     * 拿阿福的数值和「我们当时算出来的值」比一次。
     *
     * 比的是测量那一刻存下来的 calcBf（当时那套标准），所以这是真正的**离样本**验证：
     * 如果拿校准后的新值重算一遍再比，必然是 0，等于自己骗自己。
     *
     * 连续几次都在容差内 → 说明现在的标准已经和阿福对齐 → 固化，以后不用再校准。
     */
    private fun verifyAgainstAfu(kg: Double, afuBf: Double, addr: String): List<String> {
        val lines = mutableListOf<String>()
        val list = VerifyStore.load(this)
        fun isSameScale(e: VerifyEntry) =
            addr.isBlank() || e.addr.isBlank() || e.addr == addr
        // 先找最近一条「同一台秤、体重接近、还没填过阿福数值」的。
        // 找不到时(v0.11.3)退一步：允许对最近一条体重接近的重新填（覆盖旧值）。
        // 否则用户连着称同一体重时，第二次对比会因为「都填过了」而报「没找到」。
        var idx = list.indexOfLast {
            it.afuBf == null && Math.abs(it.kg - kg) < 0.6 && isSameScale(it)
        }
        var refill = false
        if (idx < 0) {
            idx = list.indexOfLast { Math.abs(it.kg - kg) < 0.6 && isSameScale(it) }
            refill = idx >= 0
        }
        if (idx < 0) {
            lines += "✗ 没找到能对上的测量记录（体重 %.2f kg 那次还没同步过）".format(kg)
            lines += "   先点上面的同步按钮称一次，再回来对比"
            return lines
        }
        if (refill) {
            lines += "（这条之前对比过，这次按新填的阿福数值重算）"
        }
        val e = list[idx]
        val diff = afuBf - e.calcBf
        list[idx] = e.copy(afuBf = afuBf)
        VerifyStore.save(this, list)

        lines += "对比 ${anchorTime(e.ts)} 那次：我们 %.1f%% vs 阿福 %.1f%%，差 %+.1f 个百分点"
            .format(e.calcBf, afuBf, diff)
        if (e.res > 0) {
            lines += "   （这条带阻抗 %.0fΩ，走的是阻抗档的标准）".format(e.res)
        } else {
            lines += "   （这条没有阻抗，走的是按体重估算那一档）"
        }

        // 连续几次对得上：按时间倒着数，遇到一次对不上就断。
        // 只统计**这台秤**的样本 —— 别的秤标准不同，混在一起没有意义。
        val sameScale = list.filter { addr.isBlank() || it.addr.isBlank() || it.addr == addr }
        val (streak, maxDiff) = VerifyStore.streak(sameScale)
        val cur = calibOf(addr)
        saveCalibOf(addr, cur.copy(streak = streak, at = System.currentTimeMillis() / 1000))

        if (Math.abs(diff) > VerifyStore.TOLERANCE) {
            lines += "⚠ 这次差 %.1f 个百分点，偏大了：可能是体重变了，或者偏移该更新了"
                .format(Math.abs(diff))
            lines += "   点下面的「重新校准」用新的阿福数值更新偏移"
        }
        if (streak >= CALIB_FREEZE_STREAK) {
            saveCalibOf(addr, calibOf(addr).copy(frozen = true))
            lines += "✓ 连续 $streak 次与阿福数据一致（最大差值 %.1f 个百分点）".format(maxDiff)
            lines += "✓ 校准已固化：以后每次称重都按阿福的标准算，不用再校准了"
        } else {
            lines += "· 已连续 $streak 次对得上，再来 ${CALIB_FREEZE_STREAK - streak} 次就固化"
        }
        return lines
    }

    /**
     * 校准：用「阿福的体重 + 体脂率」当锚点，反推我们方程的偏差，按模型分档保存。
     *
     * 锚点优先取**阿福那次**在秤历史里留下的那条带阻抗记录（见 [findAnchor]），
     * 所以正常流程是：阿福称一次 → 关阿福 → 本 App 同步一次（读出那条历史）→
     * 填阿福的体重和体脂率 → 校准。中间不用再上一次秤。
     *
     * @return 日志行 + (阻抗档偏移, BMI档偏移) + 用到的锚点
     */
    private suspend fun doCalibrate(
        anchorKg: Double,
        targetBf: Double,
    ): Triple<List<String>, Pair<Float?, Float?>, Anchor?> {
        val lines = mutableListOf<String>()
        val h = profileHeight()
        val a = profileAge()
        val s = profileSex()

        // 有锚点就用它的阻抗（走 Segal 方程），没有就只能按体重估算
        val anchor = findAnchor(anchorKg, currentScaleAddr())
        val (raw, model) = BodyComp.rawBf(anchorKg, h, a, s, anchor?.res)
        val isImp = model.startsWith("segal")
        val off = targetBf - raw

        // 校准属于「锚点那台秤」；找不到锚点就属于当前那台
        val addr = anchor?.addr?.ifBlank { currentScaleAddr() } ?: currentScaleAddr()
        val cur = calibOf(addr)
        val oldOff = if (isImp) cur.imp else cur.bmi
        // 标准变了（偏移和原来不一样）→ 以前那些验证样本是用旧标准算的，不能再拿来比
        val standardChanged = oldOff == null || Math.abs(oldOff - off) > 0.05

        saveCalibOf(addr, if (isImp) cur.copy(imp = off, streak = 0, frozen = false, at = nowSec())
        else cur.copy(bmi = off, streak = 0, frozen = false, at = nowSec()))
        prefs.edit().putLong("last_live_at", 0L).apply()   // 解除"5 分钟内同体重跳过"的限制

        if (standardChanged) {
            // 标准换了，这台秤的旧样本全部作废（别的秤的样本留着）
            val list = VerifyStore.load(this)
            VerifyStore.save(this, list.filter { it.addr != addr })
        } else {
            // 标准没变：把这次校准对应的记录标记掉，别再当成验证样本
            val list = VerifyStore.load(this)
            var touched = false
            list.indices.forEach { i ->
                if (list[i].afuBf == null && Math.abs(list[i].kg - anchorKg) < 0.6 &&
                    (addr.isBlank() || list[i].addr.isBlank() || list[i].addr == addr)
                ) {
                    list[i] = list[i].copy(afuBf = targetBf)
                    touched = true
                }
            }
            if (touched) VerifyStore.save(this, list)
        }
        // 注意：**不能**清空去重账本 —— 那是秤里历史记录的去重依据，
        // 清了会让下次同步把全部历史又传一遍，Garmin 里堆出一堆重复体重。

        lines += "✓ 校准完成（${if (isImp) "阻抗档" else "BMI 估算档"}）"
        if (addr.isNotBlank()) {
            lines += "   这台秤：${curScaleName()}（$addr）"
        }
        if (anchor != null) {
            lines += "   锚点用 ${anchorTime(anchor.ts)} 那条记录：%.2f kg · 阻抗 %.0fΩ"
                .format(anchor.kg, anchor.res)
        }
        lines += "   %.2f kg：我们算 %.1f%% → 阿福 %.1f%%，偏移 %+.1f 个百分点"
            .format(anchorKg, raw, targetBf, off)
        if (!isImp) {
            lines += "   ⚠ 没找到带阻抗的同次记录，这次只校准了「按体重估算」那一档。"
            lines += "   正常同步走的是阻抗档，所以这一步暂时不起作用；"
            lines += "   先在本 App 同步一次（读到秤里那条带阻抗的记录）再回来校准。"
        }
        val now = calibOf(addr)
        return Triple(
            lines,
            Pair(
                if (isImp) off.toFloat() else now.imp?.toFloat(),
                if (isImp) now.bmi?.toFloat() else off.toFloat(),
            ),
            anchor
        )
    }

    /**
     * 用当前校准值，把某一条已上传的记录改成正确数值。
     *
     * 做法是「先删旧、再传新」：Garmin 的体重记录按 samplePk（毫秒主键）唯一，
     * 只重传不删会堆出两条同时间记录。删除失败时**放弃重传**，宁可不动也不留重复。
     */
    private suspend fun repushRecord(ts: Long, kg: Double, res: Double, addr: String): List<String> {
        val lines = mutableListOf<String>()
        val est = BodyComp.estimate(
            kg, profileHeight(), profileAge(), profileSex(),
            if (res > 0) res else null, savedOffsets(addr)
        ) ?: return listOf("✗ 重算体成分失败，跳过修正")

        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(ts * 1000))
        val whenStr = anchorTime(ts)

        when (val look = auth.findWeighInPk(dateStr, ts, kg)) {
            is GarminAuth.WeighInLookup.Found -> {
                val del = auth.deleteWeighIn(dateStr, look.samplePk)
                if (del.isFailure) {
                    lines += "✗ 删不掉旧记录（${del.exceptionOrNull()?.message}），为免重复这次没有重传"
                    lines += "   你可以到 Garmin Connect 里手动删掉 $whenStr 那条"
                    return lines
                }
                lines += "· 已删掉旧的 $whenStr 那条，改传校准后的数值"
            }
            GarminAuth.WeighInLookup.NotFound -> {
                lines += "· Garmin 上没找到 $whenStr 那条旧记录，按新数值新增一条"
            }
            is GarminAuth.WeighInLookup.Failed -> {
                // 查不到 ≠ 不存在：查询失败时如果照传，Garmin 里会多出一条重复体重
                lines += "✗ 查 Garmin 记录失败（${look.message}），为免重复这次没有重传"
                return lines
            }
        }
        val fit = FitWriter.build(est, ts)
        val up = auth.uploadFit(fit, "weight_${ts}.fit")
        return if (up.isSuccess) {
            ledger.edit().putBoolean("$ts-${Math.round(kg * 1000)}", true).apply()
            lines += "✓ 已修正 $whenStr 那条：体脂 %.1f%% · 肌肉 %.1fkg · 水分 %.1f%%"
                .format(est.bodyFatPercent, est.muscleMassKg, est.percentHydration)
            lines
        } else {
            lines += "✗ 重传失败：${up.exceptionOrNull()?.message}"
            lines
        }
    }

    /** 把「上一次已上传的那条」改成校准后的数值（用那台秤自己的校准标准）。 */
    private suspend fun repushLastUpload(): List<String> {
        val ts = prefs.getLong("last_up_ts", 0L)
        val kg = prefs.getFloat("last_up_kg", 0f).toDouble()
        val res = prefs.getFloat("last_up_res", 0f).toDouble()
        val addr = prefs.getString("last_up_addr", "") ?: currentScaleAddr()
        if (ts <= 0L || kg <= 0.0) {
            return listOf("（没有已上传的记录需要修正；下次同步会用校准后的数值）")
        }
        return repushRecord(ts, kg, res, addr)
    }

    /**
     * 把之前上传过的所有记录都按校准后的数值重传一遍。
     * 逐条「先删 Garmin 上旧的、再传新的」，删不掉的那条就跳过，不留重复。
     */
    private suspend fun repushAllUploaded(): List<String> {
        val lines = mutableListOf<String>()
        val anchors = loadAnchors().sortedBy { it.ts }.takeLast(20)
        if (anchors.isEmpty()) {
            lines += "（本机没有已上传记录的记录，没什么可重传）"
            return lines
        }
        lines += "开始重传 ${anchors.size} 条已上传记录…"
        var ok = 0
        var skip = 0
        anchors.forEach { a ->
            // 每条按它自己那台秤的校准标准重算
            val r = repushRecord(a.ts, a.kg, a.res, a.addr)
            r.forEach { lines += "   $it" }
            if (r.any { it.startsWith("✓") }) ok++ else skip++
            delay(400)
        }
        lines += "完成：重传 $ok 条，跳过 $skip 条"
        return lines
    }

    /** Garmin 头像：拿到图片就圆角显示，没有就用账号名首字母占位 */
    @Composable
    private fun GarminAvatar(avatar: ImageBitmap?, name: String, size: androidx.compose.ui.unit.Dp) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (avatar != null) {
                Image(
                    bitmap = avatar,
                    contentDescription = "Garmin 头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    name.trim().take(1).uppercase().ifBlank { "G" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    @Composable
    private fun MainScreen(
        version: String,
        avatar: ImageBitmap?,
        garminName: String,
        boundLabel: String?,
        autoListen: Boolean,
        onToggleListen: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---------------- 顶栏：头像 + 账号名 + 当前秤 ----------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GarminAvatar(avatar, garminName, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("阿福体脂秤 → Garmin", style = MaterialTheme.typography.titleLarge)
                        Text(
                            buildString {
                                append(if (garminName.isNotBlank()) garminName else "未登录 Garmin")
                                boundLabel?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                }
                Text("v$version · 打开就自动称重，站上秤即可", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                // ---------------- 实时称重大屏（首页最显眼）----------------
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 阶段状态 + 一个按钮（监听中点「暂停」，暂停时点「称重/继续」）
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(
                                livePhase,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onToggleListen,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(if (autoListen) "暂停" else "称重/继续",
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(6.dp))

                        // 大号体重数字，和秤同步跳动
                        Text(
                            if (liveKg > 0) "%.2f".format(liveKg) else "--.--",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("kg", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            if (liveLocked) {
                                Spacer(Modifier.width(8.dp))
                                Text("● 已锁定", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        // 拿到阻抗后：体脂率等算出来的数据
                        val est = liveEstimate
                        if (est != null) {
                            Spacer(Modifier.height(14.dp))
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("体脂率", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("%.1f%%".format(est.bodyFatPercent),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("BMI", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("%.1f".format(est.bmi),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("肌肉量", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("%.1f kg".format(est.muscleMassKg),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("体水分", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("%.1f%%".format(est.percentHydration),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("骨量", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("%.1f kg".format(est.boneMassKg),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("基础代谢", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    Text("${est.basalMet} kcal", style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            // 阻抗值放最后
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "阻抗 %.0f Ω".format(liveResistance),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        } else if (liveKg > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text("光脚站稳，等 8~12 秒测出阻抗后显示体成分",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 上次崩溃的话，把现场显示出来（手机上抓不到 logcat，只能靠这个）
                if (lastCrash.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "上次打开时崩了。把下面这段发给我就行：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                lastCrash,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            TextButton(onClick = {
                                try { crashFile().delete() } catch (e: Exception) {}
                                lastCrash = ""
                            }) { Text("清掉这段", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ---------------- 结果 / 调试日志 ----------------
                Text("结果", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            summary.ifBlank { "站上秤后数据会自动出现在这里，并同步到 Garmin。" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                summary.startsWith("✗") -> MaterialTheme.colorScheme.error
                                summary.startsWith("✓") -> MaterialTheme.colorScheme.primary
                                summary.startsWith("⚠") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        fun isNoise(line: String): Boolean {
                            var t = line.trimStart()
                            if (t.startsWith("[+")) {
                                val idx = t.indexOf(']')
                                if (idx > 0) t = t.substring(idx + 1).trimStart()
                            }
                            return t.startsWith("←") || t.startsWith("→") ||
                                    t.startsWith("·") || line.startsWith("   ")
                        }
                        val live = logs.lastOrNull { !isNoise(it) }
                        if (!live.isNullOrBlank() && live != summary) {
                            Spacer(Modifier.height(4.dp))
                            Text(live, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline, maxLines = 2)
                        }
                    }
                }
                TextButton(onClick = { showDebug = !showDebug },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (showDebug) "▲ 收起调试日志（${logs.size} 行）"
                        else "▼ 展开调试日志（${logs.size} 行）",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (showDebug) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(logs.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = {
                                    try {
                                        val cb = this@MainActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cb.setPrimaryClip(ClipData.newPlainText("B1日志", logs.joinToString("\n")))
                                        ui.post { summary = "✓ 日志已复制，可粘贴发给别人" }
                                    } catch (e: Exception) {
                                        ui.post { summary = "复制失败：${e.message}" }
                                    }
                                }) {
                                    Text("复制日志", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { logs.clear(); summary = "" }) {
                                    Text("清空日志", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    // ====================================================================
    // 设置中心：账户 / 蓝牙权限 / 个人资料 / 校准 / 关于，全部收进一个页面
    // ====================================================================
    @Composable
    private fun SettingsHubScreen(
        version: String,
        loggedIn: Boolean,
        regionLabel: String,
        avatar: ImageBitmap?,
        garminName: String,
        onBack: () -> Unit,
        onOpenProfile: () -> Unit,
        onOpenLogin: () -> Unit,
        onOpenCalib: () -> Unit,
        onOpenBind: () -> Unit,
        btState: () -> String,
        btReady: () -> Boolean,
        btDiag: () -> String?,
        onEnableBluetooth: () -> Unit,
        onRequestPermission: () -> Unit,
        onOpenAppSettings: () -> Unit,
        onCheckUpdate: () -> Unit,
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                    Text("设置", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))

                // ---------------- 账户 ----------------
                Text("账户", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GarminAvatar(avatar, garminName, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (loggedIn) (garminName.ifBlank { "Garmin 已登录" }) else "Garmin 未登录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (loggedIn) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    if (loggedIn) "● $regionLabel" else "○ $regionLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (loggedIn) MaterialTheme.colorScheme.outline
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenLogin, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Person, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (loggedIn) "管理账户 / 退出登录" else "登录 Garmin")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---------------- 蓝牙与权限 ----------------
                Text("蓝牙与权限", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "蓝牙：" + btState(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (btReady()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        val diag = btDiag()
                        Text(
                            diag ?: "✓ 蓝牙和权限都正常",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (diag == null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            OutlinedButton(onClick = onEnableBluetooth,
                                modifier = Modifier.weight(1f)) {
                                Text(if (btReady()) "蓝牙已开" else "打开蓝牙")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = onRequestPermission,
                                modifier = Modifier.weight(1f)) {
                                Text("授权权限")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onOpenAppSettings,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("权限被拒过？去系统设置手动开",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "一般不用动：打开 App 会自动连秤。若连不上，先彻底关掉阿福 App（后台也划掉），" +
                                    "它占着蓝牙时我们连不上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---------------- 绑定秤 ----------------
                Text("绑定秤", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val bound = scale.boundScale()
                        Text(
                            if (bound != null) "● 已绑定：${bound.display}" else "○ 未绑定（当前按广播名自动识别）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (bound != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenBind, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (bound != null) "重新绑定 / 解绑" else "绑定我的秤")
                        }
                        Text(
                            "像官方阿福一样先用一次定死型号：绑定 A1 以后只走 A1 通道，绑定 B1 只走 B1 通道。不绑定则维持原样。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---------------- 个人资料 ----------------
                Text("个人资料", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "身高 %.1fcm · %s · %d岁".format(
                                profileState.value.heightCm,
                                profileState.value.sexLabel,
                                profileState.value.age
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (profileState.value.targetKg > 0) {
                            Text(
                                "参考体重 %.1f kg（只发给秤用）".format(profileState.value.targetKg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("编辑身高 / 年龄 / 性别")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---------------- 体脂率校准 ----------------
                Text("体脂率校准（低频，平时不用管）", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "第一次使用、换秤、或发现体脂率跟阿福差得多时才需要进去。" +
                                    "校准时会自动暂停称重，让位给阿福。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenCalib, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("校准 / 验证 / 诊断工具")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---------------- 外观 ----------------
                Text("外观", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val isSysDark = androidx.compose.foundation.isSystemInDarkTheme()
                        val themeMode = themeModeState.value
                        val isDark = when {
                            themeMode == ThemeStore.DARK -> true
                            themeMode == ThemeStore.LIGHT -> false
                            else -> isSysDark
                        }
                        Text(
                            "当前主题：${if (isDark) "深色" else "浅色"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            listOf(ThemeStore.SYSTEM to "跟随系统", ThemeStore.LIGHT to "浅色", ThemeStore.DARK to "深色")
                                .forEach { (mode, label) ->
                                    val selected = themeMode == mode
                                    Button(
                                        onClick = {
                                            ThemeStore.save(this@MainActivity, mode)
                                            themeModeState.value = mode   // 触发 GarminTheme 重组，全 App 立即换肤
                                            log("✓ 主题已切换为「$label」")
                                        },
                                        enabled = !selected,
                                        modifier = Modifier.weight(1f).padding(end = if (mode == ThemeStore.DARK) 0.dp else 4.dp)
                                    ) {
                                        Text(label, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                        }
                        Text(
                            "切换后立即生效，下次启动也会记住你的选择。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---------------- 关于 ----------------
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("当前版本 v$version", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                            Text("检查更新")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "非官方个人项目，与蚂蚁集团 / 薄荷健康 / Garmin 无关联。" +
                                    "体脂为 BIA 估算值，仅供日常参考。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ====================================================================
    // 绑定秤页：扫描附近设备 → 按名字自动识别 A1/B1 → 点一下绑定（锁定通道）
    // ====================================================================
    @Composable
    private fun BindScaleScreen(onBack: () -> Unit) {
        var scanning by remember { mutableStateOf(false) }
        var devices by remember { mutableStateOf<List<ScaleReader.Device>>(emptyList()) }
        var boundTick by remember { mutableStateOf(0) }
        val bound = remember(boundTick) { scale.boundScale() }
        val scope = rememberCoroutineScope()
        // 小米秤手动绑定（广播不建连接，靠 MAC + 米家 BLE KEY）
        var xiaomiMac by remember { mutableStateOf("") }
        var xiaomiKey by remember { mutableStateOf("") }
        var xiaomiModel by remember { mutableStateOf("XIAOMI_S400") }
        var showXiaomiForm by remember { mutableStateOf(false) }
        var showXiaomiGuide by remember { mutableStateOf(false) }
        var showMiAuth by remember { mutableStateOf(false) }
        var miAuthStatus by remember { mutableStateOf("准备中…") }
        var miAuthSession by remember { mutableStateOf<XiaomiCloudAuth.Session?>(null) }
        var miDevices by remember { mutableStateOf<List<XiaomiCloudAuth.ScaleDevice>>(emptyList()) }
        var miQrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
        var qrPngBytes by remember { mutableStateOf<ByteArray?>(null) }
        var showMiQrFullscreen by remember { mutableStateOf(false) }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("绑定我的秤", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("当前绑定", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (bound != null) "● 已绑定：${bound.display}" else "○ 尚未绑定",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (bound != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                        if (bound != null) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scale.unbind()
                                    boundTick++
                                    devices = emptyList()
                                    log("已解除绑定，恢复按广播名自动识别")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("解绑（恢复自动识别）") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("扫描附近的秤", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                Text("先轻踩一下秤唤醒它，再点扫描。App 会按名字自动认出 A1 / B1。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            scanning = true; devices = emptyList()
                            val diag = scale.diagnose()
                            if (diag != null) {
                                log("✗ $diag")
                            } else {
                                log("绑定扫描 12 秒…")
                                devices = scale.scanAll(12000) { d -> log("  " + d.display) }
                                    .filter {
                                        ScaleCatalog.match(it.name, it.hasAfuService, it.hasA1Service) != null
                                    }
                                log("候选设备 ${devices.size} 个")
                            }
                            scanning = false
                        }
                    },
                    enabled = !scanning, modifier = Modifier.fillMaxWidth()
                ) { Text(if (scanning) "扫描中…（约 12 秒）" else "扫描附近的秤") }
                Spacer(Modifier.height(8.dp))

                devices.forEach { d ->
                    val modelInfo = ScaleCatalog.match(d.name, d.hasAfuService, d.hasA1Service)
                    val modelId = modelInfo?.id ?: scale.detectModel(d.name, d.hasA1Service)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name.ifBlank { "（无名设备）" },
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("${d.address}  ·  ${d.rssi}dBm  ·  ${modelInfo?.displayName ?: "未知型号"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                scale.bind(d, modelId)
                                boundTick++
                                log("✓ 已绑定 ${d.name.ifBlank { d.address.take(8) }}（${modelInfo?.displayName ?: modelId}）")
                            }) { Text("绑定") }
                        }
                    }
                }

                if (bound != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "提示：绑定后只连这台秤、只走该型号自己的协议。换秤请到这里重新绑定或解绑。" +
                                (if (ScaleCatalog.byId(bound.model)?.experimental == true)
                                    "\n该型号为实验性协议，体脂仅供参考，请回传日志帮忙校准。" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // ---------------- 小米秤手动绑定（广播秤，扫不到名字，靠 MAC + BLE KEY） ----------------
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showXiaomiForm = !showXiaomiForm },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(if (showXiaomiForm) "收起「小米秤绑定」" else "绑定小米体脂秤 S400 / S800…",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (showXiaomiForm) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            val ctx = LocalContext.current
                            Text("小米秤只发加密广播，扫名字认不出。需要这台秤的 MAC 和 32 位 BLE KEY，填在下面即可。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            // 米家扫码授权（推荐，无图形验证码，能拿到完整授权）
                            Button(onClick = {
                                showMiAuth = true
                                miQrBitmap = null
                                miAuthSession = null
                                miDevices = emptyList()
                                miAuthStatus = "正在获取登录二维码…"
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("用米家 App 扫码自动获取 KEY")
                            }
                            Text("点一下出二维码 → 用米家 App 扫码确认 → 自动读取账号下的体脂秤，一键填入 MAC 和 BLE KEY。无需密码、没有图形验证码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showXiaomiGuide = !showXiaomiGuide },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                                Text(if (showXiaomiGuide) "收起「怎么拿到 KEY」" else "怎么拿到 MAC 和 BLE KEY？（只需弄一次）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (showXiaomiGuide) {
                                Surface(color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        val guideSteps = listOf(
                                            "① 先在米家 App 里把这台秤配对好，并光脚完整称一次，让云端为你的账号生成 KEY。",
                                            "② 拿 KEY 有两个办法，二选一：",
                                            "    · 手机网页（免电脑）：点下方「手机网页提取」按钮，浏览器里登录米家账号（选 cn 区），在设备列表找这台秤，复制 MAC 和 BLE KEY。注意：这是第三方网站，要填米家账号密码；国内部分网络打不开，打不开就用下面电脑版。",
                                            "    · 电脑版（更稳）：电脑运行开源工具「Xiaomi-cloud-tokens-extractor」，Windows 下双击 token_extractor.exe，登录后同样在设备列表里复制 MAC 和 BLE KEY。",
                                            "③ 找设备时的坑：S400 在云端常不叫「S400」，而是 yunmai.scales.ms103 / ms104 / ms107 或 xiaomi.scales.ms110；S800 找 MS116。",
                                            "④ 复制它的 MAC（如 11:22:33:44:55:66）和 BLE KEY（32 位十六进制），填到下面。",
                                            "⑤ 每次称重前，务必把米家 App 彻底杀掉（后台也关），否则秤只连米家、不发广播，本 App 收不到数据。",
                                            "⑥ 别在米家里解绑重绑——每解绑一次 KEY 就重新生成，变了这里也要跟着改。KEY 提取一次后可一直用。",
                                        )
                                        guideSteps.forEach { step ->
                                            Text(step, style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.height(6.dp))
                                        }
                                        Text("BLE KEY 只存在你自己手机里，本 App 不上传、不联网。网页提取工具是第三方网站，要输入米家账号密码，介意的话请用电脑版开源工具。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(onClick = {
                                            try {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://xiaomi-token-web.asd.workers.dev/")))
                                            } catch (e: Exception) {
                                                log("✗ 打不开浏览器：${e.message}")
                                            }
                                        }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                                            Text("打开手机网页提取 KEY（第三方 · 可能打不开）",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = {
                                            try {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor")))
                                            } catch (e: Exception) {
                                                log("✗ 打不开浏览器：${e.message}")
                                            }
                                        }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                                            Text("打开电脑版工具说明（GitHub）",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            Row {
                                listOf("XIAOMI_S400" to "S400", "XIAOMI_S800" to "S800").forEach { (id, label) ->
                                    FilterChip(selected = xiaomiModel == id, onClick = { xiaomiModel = id },
                                        label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = xiaomiMac, onValueChange = { xiaomiMac = it },
                                label = { Text("MAC 地址，如 11:22:33:44:55:66") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = xiaomiKey, onValueChange = { xiaomiKey = it },
                                label = { Text("32 位 BLE KEY（米家云提取）") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val mac = xiaomiMac.trim()
                                val key = xiaomiKey.trim().replace(" ", "")
                                when {
                                    !mac.matches(Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")) ->
                                        log("✗ MAC 格式不对，应形如 11:22:33:44:55:66")
                                    key.length != 32 || !key.matches(Regex("(?i)^[0-9a-f]{32}$")) ->
                                        log("✗ BLE KEY 应为 32 位十六进制字符")
                                    else -> {
                                        val label = ScaleCatalog.byId(xiaomiModel)?.displayName ?: "小米体脂秤"
                                        scale.bind(ScaleReader.Device(mac.uppercase(), label), xiaomiModel)
                                        prefs.edit().putString(
                                            com.example.b1garmin.scale.xiaomi.XiaomiProtocol.PREF_BINDKEY_PREFIX + mac.uppercase(),
                                            key.lowercase()).apply()
                                        boundTick++
                                        log("✓ 已绑定 $label（$mac）。站上秤即可，需光脚踩满电极才有阻抗")
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("绑定小米秤") }
                            Text("S400 赤足能测双频阻抗；S800 广播只能读体重，体脂按 BMI 估算，均为实验性。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // ---------------- 米家授权对话框（实验性 · 扫码登录） ----------------
                if (showMiAuth) {
                    Dialog(onDismissRequest = { showMiAuth = false }) {
                        Surface(color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Column(
                                Modifier.padding(16.dp).verticalScroll(rememberScrollState())
                            ) {
                                val ctx = LocalContext.current
                                Text("米家扫码授权获取 KEY", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(miAuthStatus, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(10.dp))

                                // 二维码区域（含倒计时/重试）
                                miQrBitmap?.let { bmp ->
                                    Surface(color = androidx.compose.ui.graphics.Color.White,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth()) {
                                        Image(bmp, contentDescription = "米家登录二维码",
                                            modifier = Modifier.fillMaxWidth().height(230.dp).padding(8.dp),
                                            contentScale = ContentScale.Fit)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text("用米家 App 扫上面的二维码，并在手机上点「确认登录」。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.height(4.dp))
                                    // 重要提示：小米二维码防截屏，必须现场扫码
                                    Text("⚠️ 注意：小米二维码有防截屏保护，截图/录屏后扫码会过期！必须用米家 App 直接扫描屏幕上的二维码。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.height(4.dp))
                                    // 全屏显示二维码：方便用另一台设备扫描
                                    Button(onClick = { showMiQrFullscreen = true },
                                        enabled = qrPngBytes != null,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                        Text("📱 全屏显示二维码（方便他设备扫描，不可截图）")
                                    }
                                }

                                // 全屏二维码对话框
                                if (showMiQrFullscreen) {
                                    Dialog(onDismissRequest = { showMiQrFullscreen = false }) {
                                        Surface(
                                            color = androidx.compose.ui.graphics.Color.White,
                                            shape = MaterialTheme.shapes.large,
                                            modifier = Modifier.fillMaxSize().padding(16.dp)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                                
                                                modifier = Modifier.fillMaxSize()) {
                                                Text("全屏二维码", style = MaterialTheme.typography.titleLarge)
                                                Spacer(Modifier.height(16.dp))
                                                miQrBitmap?.let { bmp ->
                                                    Image(bmp, contentDescription = "米家登录二维码",
                                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                                        contentScale = ContentScale.Fit)
                                                }
                                                Spacer(Modifier.height(24.dp))
                                                Text("用米家 App 扫描上面的二维码",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.outline)
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = { showMiQrFullscreen = false },
                                                    modifier = Modifier.fillMaxWidth()) {
                                                    Text("关闭")
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Button(onClick = {
                                    miQrBitmap = null
                                    miAuthStatus = "正在获取登录二维码…"
                                    scope.launch {
                                        val ticket = XiaomiCloudAuth.requestQrLogin()
                                        if (ticket.isFailure) {
                                            miAuthStatus = "✗ 获取二维码失败：${ticket.exceptionOrNull()?.message ?: "网络错误"}"
                                            return@launch
                                        }
                                        val t = ticket.getOrThrow()
                                        val qrPng = XiaomiCloudAuth.downloadQr(t.qrImageUrl)
                                        if (qrPng.isSuccess) {
                                            val bytes = qrPng.getOrThrow()
                                            qrPngBytes = bytes
                                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            if (bmp != null) miQrBitmap = bmp.asImageBitmap()
                                        }
                                        miAuthStatus = "请用米家 App 扫描二维码并确认登录（倒计时中…）"
                                        val result = XiaomiCloudAuth.waitForScan(t,
                                            onTick = { waitedSec ->
                                                miAuthStatus = "等待扫码中… ${waitedSec}秒/${t.timeoutSec}秒"
                                            },
                                            shouldStop = { !showMiAuth })
                                        if (result.isFailure) {
                                            miAuthStatus = "✗ ${result.exceptionOrNull()?.message ?: "扫码失败"}"
                                            return@launch
                                        }
                                        val s = result.getOrThrow()
                                        miAuthSession = s
                                        miQrBitmap = null
                                        miAuthStatus = "登录成功，正在读取设备列表…"
                                        val devs = XiaomiCloudAuth.listDevices(s)
                                        if (devs.isFailure) {
                                            miAuthStatus = "✗ 读取设备失败：${devs.exceptionOrNull()?.message ?: "未知错误"}"
                                            return@launch
                                        }
                                        miDevices = devs.getOrThrow()
                                        miAuthStatus = if (miDevices.isEmpty())
                                            "账号下没找到体脂秤，请确认秤已在米家 App 配对。"
                                        else
                                            "找到 ${miDevices.size} 台设备，点「获取 KEY」自动填入。"
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (miQrBitmap == null) "获取 / 刷新二维码" else "重新获取二维码")
                                }

                                // 已找到设备时，显示设备列表 + 一键填入
                                if (miDevices.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text("你的体脂秤：", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline)
                                    miDevices.forEach { d ->
                                        Spacer(Modifier.height(4.dp))
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(d.name, style = MaterialTheme.typography.bodyMedium)
                                                    Text("${d.mac}  ·  ${d.model}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.outline)
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Button(onClick = {
                                                    xiaomiMac = d.mac
                                                    xiaomiModel = when {
                                                        d.model.contains("S800", ignoreCase = true) -> "XIAOMI_S800"
                                                        else -> "XIAOMI_S400"
                                                    }
                                                    miAuthStatus = "已填入 MAC，正在拉取 BLE KEY…"
                                                    scope.launch {
                                                        val session = miAuthSession
                                                        if (session == null) {
                                                            miAuthStatus = "✗ 登录会话丢失，请重新扫码"
                                                            return@launch
                                                        }
                                                        val keyResult = XiaomiCloudAuth.getBeaconKey(session, d.did)
                                                        if (keyResult.isSuccess) {
                                                            xiaomiKey = keyResult.getOrThrow()
                                                            miAuthStatus = "✓ KEY 已获取，点「绑定小米秤」完成绑定"
                                                            log("✓ 已获取 ${d.name} 的 BLE KEY: ${xiaomiKey.take(8)}…")
                                                        } else {
                                                            miAuthStatus = "✗ 获取 KEY 失败：${keyResult.exceptionOrNull()?.message}"
                                                        }
                                                    }
                                                }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                                    Text("获取 KEY")
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))
                                TextButton(onClick = { showMiAuth = false },
                                    modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                            }
                        }
                    }
                }

                // ---------------- 支持的秤清单（新增品牌在这里自动出现） ----------------
                Spacer(Modifier.height(16.dp))
                Text("支持的秤", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        ScaleCatalog.all.forEachIndexed { i, m ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${m.brand} · ${if (m.bodyComp == BodyCompStandard.HUAWEI) "华为口径" else if (m.bodyComp == BodyCompStandard.XIAOMI) "小米口径" else "阿福口径"}" +
                                        (if (m.experimental) " · 实验性" else "") +
                                        (if (m.singleShot) " · 一次称完即结束" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(m.note, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
    // ====================================================================
    @Composable
    private fun CalibScreen(
        loggedIn: Boolean,
        onBack: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var calibKg by remember { mutableStateOf("") }
        var calibBf by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        /** 校准完之后问用户：要不要把之前上传的记录也按新数值重传 */
        var askRepushAll by remember { mutableStateOf(false) }
        /** 「重新校准」二次确认：会把校准推倒重来，误触代价大，必须重新填数值确认 */
        var askRecalibrate by remember { mutableStateOf(false) }
        var recalKg by remember { mutableStateOf("") }
        var recalBf by remember { mutableStateOf("") }
        /** 校准/验证状态变了就自增，用来刷新那几行显示 */
        var calibTick by remember { mutableStateOf(0) }
        var offImp by remember(calibTick) {
            mutableStateOf(calibOf(currentScaleAddr()).imp?.toFloat())
        }
        var offBmi by remember(calibTick) {
            mutableStateOf(calibOf(currentScaleAddr()).bmi?.toFloat())
        }

        Surface(Modifier.fillMaxSize()) {
            Box {
                Column(
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("体脂率校准 / 验证", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("返回")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                // ---- 体脂率校准：通用 BIA 方程和厂家算法有系统偏差，用阿福的值对一次 ----
                // 状态先算出来，下面的「当前该做什么」和按钮文案都要用
                val anyCalibrated = offImp != null || offBmi != null
                val curCalib = remember(calibTick) { calibOf(currentScaleAddr()) }
                val streak = curCalib.streak
                val frozen = curCalib.frozen
                val pvKg = calibKg.toDoubleOrNull()
                    ?: prefs.getFloat("last_up_kg", 0f).toDouble().takeIf { it > 0.0 }
                val pvBf = calibBf.toDoubleOrNull()
                val pvAnchor = pvKg?.let { findAnchor(it, currentScaleAddr()) }

                Text("校准步骤", style = MaterialTheme.typography.titleMedium)
                Text(
                    "App 已内置阿福公式，装好即可用、默认和阿福对齐。" +
                            "这一页只在换新秤、或发现体脂率跟阿福差得多时才需要；" +
                            "平时称重不用管这里，回首页站着称即可，数据会自动同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(6.dp))

                // 「当前该做什么」：按状态直接给步骤，不用自己猜该按哪个
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            when {
                                frozen -> "✓ 已经跟阿福的标准对齐了"
                                !anyCalibrated -> "已内置阿福公式 · 对不上才按这几步校准"
                                else -> "以后每次这样验证"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                frozen ->
                                    "以后正常称重、正常同步就行，这一块不用再碰了。"
                                !anyCalibrated ->
                                    "装好直接用就行，体脂率默认和阿福对齐。只有实测对不上时才校准：\n" +
                                            "① 用阿福称一次，记下体重和体脂率\n" +
                                            "② 关掉阿福（后台也要划掉，它占着蓝牙）\n" +
                                            "③ 回到首页站着称一次（打开 App 就自动称重）\n" +
                                            "④ 再回到这页，填阿福的体重和体脂率，点下面的按钮"
                                else ->
                                    "① 正常称重（阿福称一次 + 首页自动称重一次）\n" +
                                            "② 在下面填阿福这次的数值\n" +
                                            "③ 点下面的按钮看差值；对得上就算通过"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        calibKg, { calibKg = it },
                        label = { Text("阿福体重 kg") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        calibBf, { calibBf = it },
                        label = { Text("阿福体脂率 %") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))

                // 预演：点之前就能看到会用哪条记录当锚点、最终偏移多少
                val allCalib = remember(calibTick) { calibMap().toList() }

                if (pvKg != null && pvBf != null && pvBf in 3.0..60.0) {
                    val (pvRaw, _) = BodyComp.rawBf(
                        pvKg, profileHeight(), profileAge(), profileSex(), pvAnchor?.res
                    )
                    if (pvAnchor != null) {
                        Text(
                            "锚点：${anchorTime(pvAnchor.ts)} 那条记录（%.2f kg · 阻抗 %.0fΩ）"
                                .format(pvAnchor.kg, pvAnchor.res),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            "没找到体重接近、且带阻抗的记录。\n" +
                                    "原因：秤这次没测出阻抗（袜子、脚太干、没踩满电极都会这样），" +
                                    "所以没有能当锚点的记录。\n" +
                                    "怎么办：在本 App 里光脚重新称一次（脚底微湿、四个电极都踩到、" +
                                    "站直别动），用这次的阻抗当锚点就行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "我们算 %.1f%% → 阿福 %.1f%%，偏移 %+.1f 个百分点"
                            .format(pvRaw, pvBf, pvBf - pvRaw),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "填上阿福的体重和体脂率后，这里会显示这次会校准成什么样。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // 最近读到的记录：校准锚点必须是「带阻抗」的那条，列出来才看得明白
                val recent = remember(calibTick) {
                    loadAnchors().sortedByDescending { it.ts }.take(4)
                }
                if (recent.isNotEmpty()) {
                    Text(
                        "最近读到的记录（校准要挑带阻抗的那条）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    recent.forEach { a ->
                        Text(
                            "   ${anchorTime(a.ts)}   %.2f kg   %s".format(
                                a.kg,
                                if (a.res > 0) "阻抗 ${a.res.toInt()}Ω  ✓ 能当锚点"
                                else "没有阻抗  ✗ 不能当锚点"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (a.res > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // 校准动作（首次校准 / 重新校准共用）
                val runCalibrate: (Double, Double) -> Unit = { kg, bf ->
                    scope.launch {
                        busy = true; showDebug = false
                        try {
                            val (lines, offs, anchor) = doCalibrate(kg, bf)
                            lines.forEach { log(it) }
                            offImp = offs.first
                            offBmi = offs.second
                            repushLastUpload().forEach { log(it) }
                            // 之前上传过的其它记录还是老数值，问一下要不要一起改
                            if (anchor != null && loadAnchors().size > 1) askRepushAll = true
                            calibTick++
                        } catch (e: Exception) {
                            log("✗ 校准出错：${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                }

                Button(
                    onClick = {
                        val bf = calibBf.toDoubleOrNull()
                        val kg = calibKg.toDoubleOrNull()
                            ?: prefs.getFloat("last_up_kg", 0f).toDouble().takeIf { it > 0.0 }
                        if (bf == null || bf !in 3.0..60.0) {
                            log("✗ 请填阿福显示的体脂率，例如 18.0")
                        } else if (kg == null || kg !in 20.0..300.0) {
                            log("✗ 请填阿福显示的体重，例如 67.75（或先同步一次，这里就能留空）")
                        } else if (!anyCalibrated) {
                            // 首次：校准 + 按新标准重新上传
                            runCalibrate(kg, bf)
                        } else {
                            // 已校准：只做验证，不动偏移
                            scope.launch {
                                busy = true; showDebug = false
                                try {
                                    verifyAgainstAfu(kg, bf, currentScaleAddr()).forEach { log(it) }
                                    calibTick++
                                } catch (e: Exception) {
                                    log("✗ 对比出错：${e.message}")
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy && calibBf.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (busy) "处理中…" else
                            if (!anyCalibrated) "校准并上传到 Garmin"
                            else "对比阿福数据（验证准不准）"
                    )
                }

                if (anyCalibrated) {
                    OutlinedButton(
                        onClick = {
                            // 打开确认框时清空，强制【重新输入】一遍阿福数值才算数
                            recalKg = ""
                            recalBf = ""
                            askRecalibrate = true
                        },
                        enabled = !busy,   // 不再依赖主输入框，随时可点；但要点开后重新填才生效
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重新校准（差值变大、体重变了才用）") }

                    // 防误触：弹窗里必须重新填一遍阿福的体重和体脂率，才会真的重新校准。
                    // 这会解除固化、清零验证次数、可能改偏移，所以门槛特意设高。
                    if (askRecalibrate) {
                        val rKg = recalKg.toDoubleOrNull()
                        val rBf = recalBf.toDoubleOrNull()
                        val inputsOk = rKg != null && rKg in 20.0..300.0 &&
                                rBf != null && rBf in 3.0..60.0
                        AlertDialog(
                            onDismissRequest = { askRecalibrate = false },
                            title = { Text("重新校准") },
                            text = {
                                Column {
                                    Text(
                                        "当前校准${if (frozen) "已固化" else "已生效"}。重新校准会清除验证次数" +
                                                (if (frozen) "、解除固化（以后又要重新验证 3 次）" else "") +
                                                "，请重新输入阿福这次的体重和体脂率确认。\n" +
                                                "若只是日常验证，点取消后用上面的「对比阿福数据」。"
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        recalKg, { recalKg = it },
                                        label = { Text("阿福体重 kg") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        recalBf, { recalBf = it },
                                        label = { Text("阿福体脂率 %") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = inputsOk,
                                    onClick = {
                                        askRecalibrate = false
                                        runCalibrate(rKg!!, rBf!!)
                                    }
                                ) { Text("确认重新校准") }
                            },
                            dismissButton = {
                                TextButton(onClick = { askRecalibrate = false }) { Text("取消") }
                            }
                        )
                    }
                }

                Text(
                    "当前秤：" + curScaleName() +
                            (if (currentScaleAddr().isNotBlank()) "（${currentScaleAddr()}）" else "（还没连过）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    buildString {
                        append("阻抗档 ")
                        append(offImp?.let { "已校准 %+.1f".format(it) }
                            ?: "内置 %+.1f".format(BodyComp.Offsets.AFU_FACTORY.imp ?: 0.0))
                        append("　|　BMI 估算档 ")
                        append(offBmi?.let { "已校准 %+.1f".format(it) }
                            ?: "内置 %+.1f".format(BodyComp.Offsets.AFU_FACTORY.bmi ?: 0.0))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    when {
                        frozen -> "✓ 已按阿福标准校准并固化：以后每次称重都按这个标准算，不用再校准了"
                        streak > 0 -> "验证：已连续 $streak 次与阿福数据对得上，再来 ${CALIB_FREEZE_STREAK - streak} 次就固化"
                        anyCalibrated -> "验证：还没对比过。称一次后填阿福的数值，点上面的按钮对比"
                        else -> "✓ 已内置阿福公式，开箱就按阿福标准算；只有实测和阿福对不上时才需要在这里校准"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (frozen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )

                if (allCalib.size > 1) {
                    Text(
                        "换过秤的话，各台秤的校准是分开记的：" +
                                allCalib.joinToString("、") { (addr, c) ->
                                    "${c.name.ifBlank { addr.take(8) }}：" +
                                            (c.imp?.let { "%+.1f".format(it) } ?: "未校准")
                                },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                OutlinedButton(
                    onClick = { askRepushAll = true },
                    enabled = !busy && loggedIn && loadAnchors().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("把之前上传的记录按新数值重传（可选，修正老数据）") }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true; showDebug = false
                            try {
                                scale.diagnose()?.let { log("✗ $it"); return@launch }
                                log("诊断扫描 15 秒（只列设备，不连接）…")
                                val devices = scale.scanAll(15000) { d -> log("  " + d.display) }
                                log("共 ${devices.size} 个设备。若看不到秤，说明它没在广播，")
                                log("踩一下秤唤醒后再扫一次。")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("只扫描看看（诊断用）") }

                
                }
            }
            // 重传确认框：会把 Garmin 上旧的那条删掉再传，属于不可逆操作，弹窗问清楚
            if (askRepushAll) {
                AlertDialog(
                    onDismissRequest = { askRepushAll = false },
                    title = { Text("把已上传的记录全部重传？") },
                    text = {
                        Text(
                            "本机记住的 ${loadAnchors().size} 条记录会逐条处理：" +
                                    "先删掉 Garmin 上对应的旧记录，再按校准后的数值重新传一遍。" +
                                    "（只动本 App 传上去的那些，不会碰你自己在 Garmin 里记的体重）"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            askRepushAll = false
                            scope.launch {
                                busy = true; showDebug = false
                                try {
                                    repushAllUploaded().forEach { log(it) }
                                } catch (e: Exception) {
                                    log("✗ 重传出错：${e.message}")
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("重传") }
                    },
                    dismissButton = {
                        TextButton(onClick = { askRepushAll = false }) { Text("先不用") }
                    }
                )
            }

        }
    }


    // ====================================================================
    // 登录页（账号/密码输入从首页拆出，功能不变）
    // ====================================================================
    @Composable
    private fun LoginScreen(
        loggedIn: Boolean,
        loggedInState: androidx.compose.runtime.MutableState<Boolean>,
        region: GarminAuth.Region,
        onRegionChange: (GarminAuth.Region) -> Unit,
        onWebLogin: () -> Unit,
        onBack: () -> Unit,
    ) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("登录 Garmin", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("返回")
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text("账号区域", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    GarminAuth.Region.entries.forEach { r ->
                        val selected = r == region
                        Button(
                            onClick = { if (!selected) onRegionChange(r) },
                            modifier = Modifier.weight(1f).padding(end = if (r == GarminAuth.Region.GLOBAL) 4.dp else 0.dp),
                            colors = if (selected) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors(),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(if (selected) "● ${r.label}" else r.label)
                        }
                    }
                }
                Text("国际版与国内版账号不互通，请选对区域；切换后各自独立登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))

                Text(
                    if (loggedIn) "● 当前已登录 Garmin（${region.label}）" else "○ 尚未登录，请登录 Garmin ${region.label}账号",
                    color = if (loggedIn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    email, { email = it }, label = { Text("邮箱") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; showDebug = false
                            log("开始密码登录…")
                            val r = auth.loginWithPassword(email.trim(), password)
                            if (r.isSuccess) {
                                loggedInState.value = true
                                log("✓ Garmin 登录成功")
                                loadGarminProfile(force = true)
                                onBack()   // 登录成功回首页
                            } else {
                                log("✗ ${r.exceptionOrNull()?.message}")
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "登录中…" else "登录") }

                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onWebLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("改用网页登录（开启了两步验证时用）")
                }
                Text("密码只在本机内存里用一次，不保存、不上传任何服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))
                Text(
                    "说明：当前登录的是 Garmin ${region.label}账号（connect.garmin.${region.domain.removePrefix("garmin.")}），" +
                            "登录成功后回到首页称重同步即可。数据只会发往你选的这个区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                // 退出登录放页面最底部，远离输入框和登录按钮，避免误触
                if (loggedIn) {
                    Spacer(Modifier.height(24.dp))
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            auth.clear()
                            loggedInState.value = false
                            garminAvatar = null
                            garminName = ""
                            summary = ""
                            email = ""; password = ""
                            log("已退出 Garmin 登录")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("退出登录")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

}
