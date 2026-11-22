#!/usr/bin/env bash
# ============================================================================
# 一键发布新版本到 Gitee + GitHub（双平台）
#
# 用法：
#   ./release_update.sh "更新说明"
# 例：
#   ./release_update.sh "修复蓝牙断连；优化耗电"
#
# 版本号（versionCode / versionName）统一从 app/build.gradle.kts 读取，只改那一处。
# 本脚本自动完成：
#   1. 编译 APK
#   2. 复制 APK 到 distribution/ + 生成 version.json（Gitee raw 直链）
#   3. 自动更新 README 里的版本号和下载链接
#   4. git commit
#   5. 推送代码到 Gitee(master) 和 GitHub(main)
#   6. 打 tag v${VER} 并推送两边
#   7. 创建 Gitee Release + GitHub Release（GitHub 带 APK 附件）
#
# 前置条件：
#   1. gitee_config.sh 已填好 GITEE_USER / GITEE_TOKEN / GITEE_LOGIN / GITEE_PASSWORD
#   2. GitHub 已用 `gh auth login` 登录（本脚本复用其凭据）
#   3. app/build.gradle.kts 里版本号已升好
#   4. APK < 10MB（Gitee raw 直链匿名下载上限）
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

if [ $# -lt 1 ]; then
    echo "用法: $0 \"更新说明\""
    echo "例:   $0 \"修复蓝牙断连；优化耗电\""
    exit 1
fi

NOTES="$1"

# ---- 读取配置 ----
if [ ! -f gitee_config.sh ]; then
    echo "✗ 缺少 gitee_config.sh"
    exit 1
fi
source gitee_config.sh
for v in GITEE_USER GITEE_TOKEN GITEE_LOGIN GITEE_PASSWORD; do
    if [ -z "${!v:-}" ]; then echo "✗ gitee_config.sh 里 $v 没填"; exit 1; fi
done
if ! command -v gh >/dev/null 2>&1; then
    echo "✗ 未找到 gh CLI，GitHub Release 无法创建。请先安装并 gh auth login"
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    echo "✗ gh 未登录 GitHub，请先运行 gh auth login"
    exit 1
fi
GH_USER=$(gh api user --jq .login 2>/dev/null || echo "maidouheige")

# ---- 从 build.gradle.kts 读取版本号 ----
VER=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed -E 's/versionName = "([^"]+)"/\1/')
VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | awk '{print $3}')
if [ -z "$VER" ] || [ -z "$VC" ]; then
    echo "✗ 无法从 build.gradle.kts 读取版本号"
    exit 1
fi

REPO="b1-garmin-android"
TAG="v${VER}"
APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
APK_NAME="B1-Garmin-Sync-${TAG}.apk"
APK_DST="distribution/${APK_NAME}"
RAW_URL="https://gitee.com/${GITEE_USER}/${REPO}/raw/master/distribution/${APK_NAME}"

echo "==> 发布 ${TAG} (code ${VC}) → Gitee + GitHub"

echo "==> [1/8] 编译 APK"
~/.gradle/wrapper/dists/gradle-8.5-bin/gradle-8.5/bin/gradle assembleDebug --project-dir . --offline >/dev/null

SIZE_BYTES=$(stat -f%z "$APK_SRC")
SIZE_MB=$(echo "scale=2; $SIZE_BYTES/1048576" | bc)
echo "    APK 大小: ${SIZE_MB} MB"
if [ "$SIZE_BYTES" -gt 11000000 ]; then
    echo "✗ APK 超过 10MB，Gitee raw 直链将要求登录，手机匿名下载会失败。需先瘦身。"
    exit 1
fi

echo "==> [2/8] 复制 APK 到 distribution/"
cp "$APK_SRC" "$APK_DST"

echo "==> [3/8] 生成 version.json"
python3 - "$VC" "$VER" "$RAW_URL" "$NOTES" "$SIZE_BYTES" <<'PY'
import json, sys
vc, ver, url, notes, size = sys.argv[1:6]
data = {"versionCode": int(vc), "versionName": ver, "url": url,
        "notes": notes, "size": int(size)}
with open("distribution/version.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
cat distribution/version.json

echo "==> [4/8] 自动更新 README 版本号 + 下载链接"
python3 - "$VER" "$GITEE_USER" "$REPO" <<'PY'
import re, sys
ver, user, repo = sys.argv[1:4]
tag = "v" + ver
p = "README.md"
s = open(p, encoding="utf-8").read()
# 版本号行
s = re.sub(r'(> 当前版本 \*\*)v[\d.]+(\*\*)', r'\g<1>' + tag + r'\g<2>', s)
# APK 文件名与直链（形如 B1-Garmin-Sync-vX.Y.Z.apk）
s = re.sub(r'B1-Garmin-Sync-v[\d.]+\.apk', 'B1-Garmin-Sync-' + tag + '.apk', s)
open(p, "w", encoding="utf-8").write(s)
print("    README 已更新为", tag)
PY

# Release 说明（两个平台共用）
NOTES_FILE=$(mktemp)
python3 - "$VER" "$NOTES" "$GITEE_USER" "$REPO" "$APK_NAME" > "$NOTES_FILE" <<'PY'
import sys
ver, notes, user, repo, apk = sys.argv[1:6]
tag = "v" + ver
raw = "https://gitee.com/%s/%s/raw/master/distribution/%s" % (user, repo, apk)
import datetime
print("## %s（%s）\n" % (tag, datetime.date.today().isoformat()))
print("### 本次更新\n- %s\n" % notes.replace("；", "\n- "))
print("### 下载安装")
print("- **APK 直链（手机直接点，推荐）**：%s" % raw)
print("- GitHub Release 附件亦提供同名 APK")
print("- 大小约 9 MB，debug 签名需侧载（允许「未知来源」安装）")
print("- 装过旧版可在 App 内点顶部「检查更新」自动升级\n")
print("### 使用前必读")
print("- 非官方个人项目，与蚂蚁集团 / 薄荷健康 / Garmin 无关联")
print("- Garmin 登录走模拟客户端接口，账号密码仅在内置网页登录后换 token，App 不存储密码")
print("- 体脂为 BIA 估算值，仅供日常参考，非医疗数据")
print("- 详见仓库 README 的免责声明与使用说明")
PY

echo "==> [5/8] git commit"
git add -A
git commit -m "release: ${TAG} - ${NOTES}" || echo "    (没有变更需要提交)"

echo "==> [6/8] 推送代码到 Gitee + GitHub"
GIT_URL="https://${GITEE_USER}:${GITEE_TOKEN}@gitee.com/${GITEE_USER}/${REPO}.git"
if git remote get-url gitee >/dev/null 2>&1; then
    git remote set-url gitee "$GIT_URL"
else
    git remote add gitee "$GIT_URL"
fi
git push -u gitee HEAD:master 2>&1 | sed -E "s/${GITEE_TOKEN}/****/g"
git push origin HEAD:main 2>&1

echo "==> [7/8] 打 tag ${TAG} 并推送两边"
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "    tag ${TAG} 本地已存在，跳过打 tag"
else
    git tag -a "$TAG" -m "${TAG} ${NOTES}"
fi
git push gitee "$TAG" 2>&1 | sed -E "s/${GITEE_TOKEN}/****/g" || echo "    Gitee tag 可能已存在"
git push origin "$TAG" 2>&1 || echo "    GitHub tag 可能已存在"

echo "==> [8/8] 创建 Release"
# Gitee（登录态网页方式，幂等）
GITEE_LOGIN="$GITEE_LOGIN" GITEE_PASSWORD="$GITEE_PASSWORD" \
    python3 gitee_release.py "$GITEE_USER" "$REPO" "$TAG" "${TAG} 更新" "$NOTES_FILE"
# GitHub（gh CLI，带 APK 附件，幂等）
if gh release view "$TAG" --repo "${GH_USER}/${REPO}" >/dev/null 2>&1; then
    echo "    GitHub Release ${TAG} 已存在，跳过创建"
else
    gh release create "$TAG" "$APK_DST" \
        --repo "${GH_USER}/${REPO}" \
        --title "${TAG} 更新" \
        --notes-file "$NOTES_FILE" \
        --target main 2>&1
    echo "    GitHub Release 创建成功（含 APK 附件）"
fi
rm -f "$NOTES_FILE"

echo ""
echo "✓ 双平台发布完成！"
echo "  Gitee  版本清单: https://gitee.com/${GITEE_USER}/${REPO}/raw/master/distribution/version.json"
echo "  Gitee  APK 直链: ${RAW_URL}"
echo "  Gitee  Release:  https://gitee.com/${GITEE_USER}/${REPO}/releases"
echo "  GitHub Release:  https://github.com/${GH_USER}/${REPO}/releases/tag/${TAG}"
echo ""
echo "手机 App 内点「检查更新」即可看到 ${TAG}。"
