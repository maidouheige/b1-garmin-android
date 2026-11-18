package com.example.b1garmin.garmin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Garmin 网页登录 WebView。
 *
 * 用的是**移动端 SSO 登录页**（与密码登录同一套 service），这样抓到的
 * ticket 才能被 DI token 端点接受（service_url 必须与登录时一致）。
 *
 * 抓 ticket 的两条路径：
 *  1. hook window.fetch / XMLHttpRequest —— 直接读 /mobile/api/login 的响应 JSON
 *  2. 监听 URL 变化 —— 兜底，某些情况下 ticket 出现在跳转地址里
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class WebLogin(
    private val context: Context,
    private val onTicket: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "WebLogin"
        private const val IOS_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    }

    private var fired = false
    private fun fire(ticket: String, how: String) {
        if (fired) return
        fired = true
        Log.d(TAG, "ticket via $how, len=${ticket.length}")
        onTicket(ticket)
    }

    /** 注入 JS：hook fetch + XHR 抓登录响应，同时兜底监听 URL。 */
    private val hookJs = """
(function() {
  if (window.__b1_hooked) return;
  window.__b1_hooked = true;

  function handle(text, src) {
    try {
      var j = JSON.parse(text);
      var st = j.responseStatus && j.responseStatus.type;
      window.B1Bridge.onLog('登录响应(' + src + '): ' + st);
      if (st === 'SUCCESSFUL' && j.serviceTicketId) {
        window.B1Bridge.onTicket(j.serviceTicketId);
      } else if (st === 'MFA_REQUIRED') {
        window.B1Bridge.onLog('需要两步验证，请在页面上继续完成');
      } else if (st === 'INVALID_USERNAME_PASSWORD') {
        window.B1Bridge.onLog('账号或密码错误');
      }
    } catch (e) {}
  }

  // 1) hook fetch
  var of = window.fetch;
  if (of) {
    window.fetch = function() {
      var u = arguments[0];
      if (u && typeof u === 'object' && u.url) u = u.url;
      u = String(u || '');
      return of.apply(this, arguments).then(function(resp) {
        try {
          if (u.indexOf('/api/login') >= 0 || u.indexOf('/mfa') >= 0) {
            resp.clone().text().then(function(t) { handle(t, 'fetch'); });
          }
        } catch (e) {}
        return resp;
      });
    };
  }

  // 2) hook XMLHttpRequest
  var oOpen = XMLHttpRequest.prototype.open;
  var oSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u) { this.__b1url = String(u || ''); return oOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function() {
    var self = this;
    this.addEventListener('load', function() {
      try {
        var u = self.__b1url || '';
        if (u.indexOf('/api/login') >= 0 || u.indexOf('/mfa') >= 0) handle(self.responseText, 'xhr');
      } catch (e) {}
    });
    return oSend.apply(this, arguments);
  };

  // 3) 兜底：URL 里出现 ticket
  var last = location.href;
  setInterval(function() {
    if (location.href !== last) {
      last = location.href;
      window.B1Bridge.onLog('页面跳转: ' + location.href.substring(0, 90));
      var m = location.href.match(/[?&](?:ticket|serviceTicketId)=([^&]+)/);
      if (m) window.B1Bridge.onTicket(m[1]);
    }
  }, 600);

  window.B1Bridge.onLog('登录监控已注入');
})();
""".trimIndent()

    @Composable
    fun Screen(onClose: () -> Unit) {
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                // 必须用 iOS UA：登录页按 UA 渲染，用 Android UA 会被导向别的流程
                settings.userAgentString = IOS_UA
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(hookJs, null)
                        Log.d(TAG, "page finished: ${url.take(90)}")
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        val m = Regex("""[?&](?:ticket|serviceTicketId)=([^&]+)""").find(url)
                        if (m != null && url.contains("garmin.com")) {
                            fire(m.groupValues[1], "redirect")
                            return true
                        }
                        return false
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                        resultMsg: android.os.Message
                    ): Boolean = false
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onTicket(ticket: String) {
                        post { fire(ticket, "js") }
                    }
                    @JavascriptInterface
                    fun onLog(msg: String) {
                        post { onLog(msg) }
                    }
                }, "B1Bridge")

                setBackgroundColor(Color.WHITE)
                loadUrl(GarminAuth.ssoSigninUrl())
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
