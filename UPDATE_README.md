# 应用内自动更新 — 使用说明

## 用户（手机上）怎么用

1. 打开 App，启动时会在后台静默检查有没有新版本（失败不打扰你，不影响正常称重）
2. 有新版本会弹出「发现新版本 vX.Y.Z」窗口，显示更新内容
3. 点「下载并更新」→ 自动下载 → 自动弹出系统安装界面 → 点「安装」即可
4. 也可以随时点右上角的「vX.Y.Z · 检查更新」手动检查

### 一次性设置（每台手机只需做一次）

Android 8+ 要求：首次更新时，系统会提示给本 App 开启「安装未知应用」权限，选「允许」即可。
（路径：设置 → 应用 → B1 → 安装未知应用 → 允许）

> 说明：最后一步「是否安装」的系统确认弹窗无法省略，这是 Android 的安全设计，所有非应用商店的 App 都一样。

## 开发者（我）怎么发新版本

一条命令搞定：

```bash
cd B1-Garmin-Android
./release_update.sh "这次更新了什么"
```

脚本会自动：编译 APK → 复制到 distribution/ → 生成 version.json → 提交 → 推送到 Gitee。

发版前只需两步准备：
1. 改 `app/build.gradle.kts` 里的 `versionCode`（+1）和 `versionName`（如 0.12.1）
2. 确认 `gitee_config.sh` 里的用户名和令牌有效

### 关键约束

- **APK 必须 < 10MB**：Gitee raw 直链对超过 10MB 的文件要求登录，手机匿名下载会失败。脚本会强制检查。
- **versionCode 单一数据源**：从 build.gradle.kts 读取，App 和 version.json 用同一个值，避免"永远提示有更新"。
- **Gitee 仓库需公开**：raw 直链只对公开仓库匿名开放。仓库内容只有 APK、版本清单和源码，无敏感信息。

## 托管结构

```
Gitee 仓库: https://gitee.com/<用户名>/b1-garmin-android
├── distribution/
│   ├── version.json                    ← App 启动时读这个（版本清单）
│   └── B1-Garmin-Sync-v0.12.0.apk      ← 新版本安装包
└── (源码)
```

`version.json` 格式：

```json
{
  "versionCode": 30,
  "versionName": "0.12.0",
  "url": "https://gitee.com/<用户名>/b1-garmin-android/raw/master/distribution/B1-Garmin-Sync-v0.12.0.apk",
  "notes": "更新说明",
  "size": 9792678
}
```

## 首次搭建（账号准备好后，我做）

1. 在 Gitee 新建**公开**空仓库 `b1-garmin-android`（不要勾选初始化 README）
2. 填好 `gitee_config.sh`（用户名 + 私人令牌）
3. 把 `Updater.kt` 里的 `{USER}` 换成真实用户名
4. 运行 `./release_update.sh "首次发布"` 完成首次推送
5. 手机装 v0.12.0，验证「检查更新」能读到版本清单
