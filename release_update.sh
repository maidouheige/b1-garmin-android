#!/usr/bin/env bash
# ============================================================================
# 一键发布新版本到 Gitee（供 App 内自动更新使用）
#
# 用法：
#   ./release_update.sh "更新说明"
# 例：
#   ./release_update.sh "修复蓝牙断连；优化耗电"
#
# 版本号（versionCode / versionName）统一从 app/build.gradle.kts 读取，
# 只改那一处即可，避免 App 内的版本和清单对不上。
#
# 前置条件：
#   1. 本目录的 gitee_config.sh 里已填好 GITEE_USER 和 GITEE_TOKEN
#   2. app/build.gradle.kts 里版本号已升好
#   3. 编译出来的 APK < 10MB（Gitee raw 直链匿名下载上限）
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

if [ $# -lt 1 ]; then
    echo "用法: $0 \"更新说明\""
    echo "例:   $0 \"修复蓝牙断连；优化耗电\""
    exit 1
fi

NOTES="$1"

# ---- 读取 Gitee 配置 ----
if [ ! -f gitee_config.sh ]; then
    echo "✗ 缺少 gitee_config.sh，请先按说明填写 GITEE_USER / GITEE_TOKEN"
    exit 1
fi
source gitee_config.sh
if [ -z "${GITEE_USER:-}" ] || [ -z "${GITEE_TOKEN:-}" ]; then
    echo "✗ gitee_config.sh 里 GITEE_USER / GITEE_TOKEN 没填"
    exit 1
fi

# ---- 从 build.gradle.kts 读取版本号（唯一数据源）----
VER=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed -E 's/versionName = "([^"]+)"/\1/')
VC=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | awk '{print $3}')
if [ -z "$VER" ] || [ -z "$VC" ]; then
    echo "✗ 无法从 build.gradle.kts 读取版本号"
    exit 1
fi

REPO="b1-garmin-android"
APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
APK_NAME="B1-Garmin-Sync-v${VER}.apk"
APK_DST="distribution/${APK_NAME}"

echo "==> 发布版本 v${VER} (code ${VC})"

echo "==> [1/5] 编译 APK"
~/.gradle/wrapper/dists/gradle-8.5-bin/gradle-8.5/bin/gradle assembleDebug --project-dir . --offline >/dev/null

SIZE_BYTES=$(stat -f%z "$APK_SRC")
SIZE_MB=$(echo "scale=2; $SIZE_BYTES/1048576" | bc)
echo "    APK 大小: ${SIZE_MB} MB"
if [ "$SIZE_BYTES" -gt 10000000 ]; then
    echo "✗ APK 超过 10MB（$SIZE_MB MB），Gitee raw 直链将要求登录，手机匿名下载会失败。"
    echo "  需要瘦身后再发（比如开 minify 或移除无用资源）。"
    exit 1
fi

echo "==> [2/5] 复制 APK 到 distribution/"
cp "$APK_SRC" "$APK_DST"

RAW_URL="https://gitee.com/${GITEE_USER}/${REPO}/raw/master/distribution/${APK_NAME}"

echo "==> [3/5] 生成 version.json"
python3 - "$VC" "$VER" "$RAW_URL" "$NOTES" "$SIZE_BYTES" <<'PY'
import json, sys
vc, ver, url, notes, size = sys.argv[1:6]
data = {
    "versionCode": int(vc),
    "versionName": ver,
    "url": url,
    "notes": notes,
    "size": int(size),
}
with open("distribution/version.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
cat distribution/version.json

echo "==> [4/5] 提交到 git"
git add -A
git commit -m "release: v${VER} - ${NOTES}" || echo "    (没有变更需要提交)"

echo "==> [5/5] 推送到 Gitee"
GIT_URL="https://${GITEE_USER}:${GITEE_TOKEN}@gitee.com/${GITEE_USER}/${REPO}.git"
if git remote get-url gitee >/dev/null 2>&1; then
    git remote set-url gitee "$GIT_URL"
else
    git remote add gitee "$GIT_URL"
fi
# Gitee 新建仓库默认分支是 master；第一次推送需保证本地分支名与之匹配。
# 用 HEAD:master 显式推到远端 master，不管本地叫 main 还是 master。
git push -u gitee HEAD:master 2>&1 | sed -E "s/${GITEE_TOKEN}/****/g"

echo ""
echo "✓ 发布完成！"
echo "  版本清单: https://gitee.com/${GITEE_USER}/${REPO}/raw/master/distribution/version.json"
echo "  APK 直链: ${RAW_URL}"
echo ""
echo "现在在手机 App 里点「检查更新」就能看到 v${VER} 了。"
