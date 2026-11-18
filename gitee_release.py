#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
在 Gitee 上创建 Release（用登录态网页表单，无需私人令牌）。

被 release_update.sh 调用，凭据从环境变量读，不打印：
  GITEE_LOGIN     登录名（手机号）
  GITEE_PASSWORD  密码

命令行参数：
  python3 gitee_release.py <owner> <repo> <tag> <title> <notes_file>

带幂等：若该 tag 的 Release 已存在则跳过。
"""
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import http.cookiejar

owner, repo, tag, title = sys.argv[1:5]
notes = open(sys.argv[5], encoding="utf-8").read()
login = os.environ["GITEE_LOGIN"]
password = os.environ["GITEE_PASSWORD"]

cj = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
op.addheaders = [
    ("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                   "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
]


def die(msg):
    print("✗ Gitee Release:" + msg)
    sys.exit(1)


# ---- 登录 ----
try:
    html = op.open("https://gitee.com/login", timeout=20).read().decode("utf-8", "ignore")
    csrf = re.search(r'name="authenticity_token" value="([^"]+)"', html).group(1)
    data = urllib.parse.urlencode({
        "authenticity_token": csrf,
        "user[login]": login,
        "user[password]": password,
        "commit": "登 录",
    }).encode()
    op.open(urllib.request.Request("https://gitee.com/login", data=data), timeout=20)
except Exception as e:
    die("登录失败：%s" % e)

base = "https://gitee.com/%s/%s" % (owner, repo)

# ---- 幂等：该 tag 的 Release 已存在则跳过 ----
try:
    lst = op.open(base + "/releases", timeout=20).read().decode("utf-8", "ignore")
    if ("/releases/tag/%s" % tag) in lst:
        print("Gitee Release %s 已存在，跳过创建" % tag)
        sys.exit(0)
except Exception as e:
    die("检查已有 Release 失败：%s" % e)

# ---- 取创建页 csrf ----
try:
    new = op.open(base + "/releases/new", timeout=20).read().decode("utf-8", "ignore")
    tok = re.search(r'<meta[^>]*name="csrf-token"[^>]*content="([^"]+)"', new)
    if not tok:
        die("创建页未找到 csrf-token")
    csrf2 = tok.group(1)
except Exception as e:
    die("打开创建页失败：%s" % e)

fields = {
    "utf8": "✓",
    "authenticity_token": csrf2,
    "release[title]": title,
    "release[tag_version]": tag,
    "release[ref]": "master",
    "release[release_type]": "release",
    "release[description]": notes,
}
try:
    r = op.open(
        urllib.request.Request(base + "/releases", data=urllib.parse.urlencode(fields).encode()),
        timeout=30,
    )
    print("Gitee Release 创建成功: %s" % r.url)
except urllib.error.HTTPError as e:
    die("创建请求 HTTP %d: %s" % (e.code, e.url))
except Exception as e:
    die("创建失败：%s" % e)
