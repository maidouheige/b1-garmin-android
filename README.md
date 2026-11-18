# B1 → Garmin 同步助手（Android）

把**蚂蚁阿福 × 薄荷 B1 体脂秤**（AFU-BH-TZ-B1）的称重数据，通过手机蓝牙读取，生成 FIT 文件上传到 **Garmin Connect 国际版**的安卓 App。

站上秤 → 光脚站稳十几秒 → 体重、体脂、肌肉、水分等自动进 Garmin，无需电脑、无需 Root。

> 当前版本 **v0.17.0**。这是一个个人自用的小工具，源码开放供参考。

## 📥 下载

| 渠道 | 地址 |
|---|---|
| **APK 直链**（手机直接点，推荐） | [B1-Garmin-Sync-v0.17.0.apk](https://gitee.com/maidouheige/b1-garmin-android/raw/master/distribution/B1-Garmin-Sync-v0.17.0.apk) |
| Gitee Release（更新说明） | https://gitee.com/maidouheige/b1-garmin-android/releases |
| GitHub Release | https://github.com/maidouheige/b1-garmin-android/releases |

**装过老版本后，直接在 App 内点顶部「检查更新」即可升级**，不用再手动下载安装。详见 [UPDATE_README.md](UPDATE_README.md)。

---

## ⚠️ 免责声明（务必先读）

- 这是**非官方个人项目**，与蚂蚁集团、薄荷健康、Garmin（佳明）**没有任何关联**，也未获任何一家授权。请使用你自己合法的账号和自有设备。
- Garmin 登录走的是**模拟官方客户端**的非公开接口（DI OAuth2），属于灰色地带：**哪天 Garmin 改了认证方式，登录可能突然失效**，届时数据不会丢，需要更新 App 适配。App 不会导致你的 Garmin 账号被封——你只是把自己的体重写进自己的账号。
- **不要用于商业用途、不要包装成面向公众的服务**。官方个人 API 明确禁止多用户商业使用。
- 体脂率等身体成分是 **BIA 生物电阻抗估算**，不是医疗测量值，仅供日常参考，不作任何医疗诊断依据。

---

## 功能特性

- **实时称重同步**：站上秤即可读写，体重锁定后提示继续光脚站稳测阻抗，拿到体成分后自动上传
- **读取阻抗**：0x52 阻抗帧 + 0x54 体成分记录帧解析，拿不到阻抗时用 BMI 兜底
- **阿福体成分模型**：水分 = 0.6966×FFM、骨量 = 0.0522×FFM、肌肉 = FFM−骨量、BMR = 30×FFM、内脏脂肪 = BF/3
- **体脂校准**：按蓝牙地址（每台秤）分别保存校准偏移；支持阻抗档 / BMI 档两档；记录锚点库；连续 3 次与阿福一致后自动**固化**，之后免维护
- **防误触**：「重新校准」需在弹窗里重新填写阿福体重和体脂率才生效
- **资料设置**：身高（滚轮，0.5cm 步进）、出生日期（自动算年龄）、性别、参考体重
- **去重与幂等**：5 分钟内同一体重不重复上传；校验去重账本，避免刷屏和重复记录
- **应用内自动更新**：启动后台检查新版本（Gitee 托管），发现后弹窗 → 下载 → 调起系统安装；也可手动点版本号检查。详见 [UPDATE_README.md](UPDATE_README.md)
- **健壮性**：Garmin token 静默续期、蓝牙串行写队列 + 状态机、崩溃自动留证、日志折叠（成功只显结论，失败自动展开）

## 隐私

- 账号密码**不经过 App 存储**（网页登录时在内置浏览器完成）；token 只存在 App 内部 `SharedPreferences`
- 体重等数据只发往 **Garmin 官方服务器**，App 不含任何第三方统计 / 埋点 / 广告 SDK
- 工程内**无硬编码的账号、密码、token 或个人隐私信息**

## 安装

1. 手机浏览器打开上面的 **APK 直链**下载（约 9.35 MB）；已装旧版可在 App 内「检查更新」自动升级
2. 点击安装，按提示允许「未知来源」（debug 签名，无法上架应用商店，只能侧载）
   - 小米：设置 → 隐私保护 → 特殊权限 → 安装未知应用 → 给文件管理器授权
   - 华为 / OPPO / vivo：安装时按提示开启即可
3. 首次打开请求蓝牙和定位权限，**全部允许**（Android 12+ 扫 BLE 必须给「附近的设备」权限）

## 使用

### ① 登录 Garmin（国际版账号）

点「网页登录」，在打开的页面里正常登录你的 Garmin 国际区账号（含 2FA 验证码），App 自动换 token。日志出现「✓ Garmin 登录成功」即可。token 过期会自动续期，一般不用重复登录。

### ② 资料设置

进「资料设置」填好**身高、出生日期、性别**，这三项是体成分计算的硬性输入。

### ③ 称重同步

1. **彻底关掉阿福 App**（后台也划掉）——BLE 体脂秤同时只能连一个设备
2. 回到本 App 点「连接秤称重」，**光脚**站上去，四个脚掌都踩在电极上
3. 体重锁定后**不要马上下秤**，保持不动再等 8~12 秒，日志出现「阻抗 xxx Ω」「会话完成（已拿到体成分）」再下来
4. 等日志显示上传成功，去 Garmin Connect 网页核对

> 为什么体重锁定后还要站着？体脂靠生物电阻抗（BIA）测，需要光脚站稳十几秒才能测出阻抗。App 会等到阻抗或 16 秒上限才收工。

### ④ 体脂校准（让 App 和阿福读数对齐）

不同公式系数不同，第一次用建议校准一次：

1. 阿福 App 正常称一次，记下**体重和体脂率**
2. 本 App 也同步称一次
3. 在校准区两个输入框填阿福那次的体重和体脂率，点「**对比阿福数据**」
4. 差值在 0.5 个百分点以内算对上一次，**连续 3 次**即自动固化——之后称重直接传，不用再填阿福数据

日常只有「体重明显变化」或「差值突然变大」时才需要点「重新校准」（会弹窗要求重新填一遍数值，防误触）。

## 已知限制

- 仅支持 Garmin **国际区**账号（`connect.garmin.com`）；中国区账号不在此列
- BLE 体脂秤同一时刻只能连一个设备，阿福 App 占着连接时本 App 连不上，需先关阿福
- 校准/验证按秤记录；换秤或升级后可能需要重新校准
- 只读取体重、阻抗及由它们推算的体成分，不同步步数 / 楼层 / 睡眠等数据
- debug 签名，需侧载安装

## 项目结构

| 模块 | 文件 | 说明 |
|---|---|---|
| B1 协议 | `protocol/AfuProtocol.kt` | 20 字节定长帧、AF45 头、校验和、命令构造与解析 |
| 体成分 | `protocol/BodyComp.kt` | Segal FFM + 阿福四分量模型、校准偏移 |
| FIT 生成 | `protocol/FitWriter.kt` | 纯 Kotlin，weight_scale 消息，CRC 已验证 |
| Garmin 登录 | `garmin/GarminAuth.kt` | DI OAuth2、token 缓存与静默续期、体重查询/删除、FIT 上传 |
| 网页抓凭证 | `garmin/WebLogin.kt` | WebView 注入 JS 拦截 ticket |
| 蓝牙读秤 | `ble/ScaleReader.kt` / `ble/ScaleSession.kt` | 扫描、GATT 连接、状态机、阻抗回填 |
| 界面 | `MainActivity.kt` / `ui/*.kt` | Compose UI、设置页、校准与验证 |

技术栈：Kotlin + Jetpack Compose，Gradle 8.5 / AGP 8.2.2，`minSdk 26` / `targetSdk 34`。

## 从源码构建

```bash
# 用项目自带 wrapper（需 Android SDK）
./gradlew assembleDebug --offline
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

单元测试：`./gradlew testDebugUnitTest --offline`（协议帧、校验和、体成分、校准与验证逻辑）。

## 协议来源与致谢

本项目站在以下开源项目的肩膀上，特此致谢（均为 MIT 协议）：

- **B1 蓝牙协议**（帧结构、命令、GATT、状态机）移植自
  [OHealthDeviceBridge](https://github.com/BruceZhang1993/OHealthDeviceBridge) — © BruceZhang1993, MIT
- **BIA 体成分公式**（Segal FFM 等）参考
  [ant-afu-welland-scale](https://github.com/Mzdyl/ant-afu-welland-scale) — © Mzdyl, MIT
- Garmin 上传路径参考 [python-garminconnect](https://github.com/cyberjunky/python-garminconnect)

原始逆向资料与更多参考链接见配套的 `B1-Garmin-Bridge/docs/03-调研与来源.md`。

## License

本项目采用 [MIT License](LICENSE)。第三方协议来源的版权声明见上文「协议来源与致谢」及 `LICENSE` 末尾。
