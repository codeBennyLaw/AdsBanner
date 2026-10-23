package com.adsbanner.clean.core

import java.net.URLEncoder

/** A mini app has its own statistics key; the installed host package is only used for its icon. */
class MiniAppSource private constructor(val hostPackage: String, val name: String?) {
    val key = "miniapp:$hostPackage:" + (name?.let { "name:" + URLEncoder.encode(it, "UTF-8") } ?: "unknown")
    val hostLabel: String get() = hosts.getValue(hostPackage)
    val displayName: String get() = name ?: "未识别的小程序"
    val notificationLabel: String get() = "$displayName · $hostLabel 小程序"

    companion object {
        private val hosts = mapOf("com.tencent.mm" to "微信", "com.tencent.mobileqq" to "QQ",
            "com.eg.android.AlipayGphone" to "支付宝")
        fun isHost(pkg: String) = pkg in hosts
        fun create(hostPackage: String, rawName: String?): MiniAppSource? {
            if (!isHost(hostPackage)) return null
            val name = rawName?.replace(WHITESPACE, " ")?.trim()?.takeIf {
                it.length in 1..60 && it !in GENERIC_NAMES && !it.contains(PRIVATE_OR_URL) && !TRANSIENT_LABEL.matches(it)
            }
            return MiniAppSource(hostPackage, name)
        }
        private val WHITESPACE = Regex("\\s+")
        private val PRIVATE_OR_URL = Regex("https?://|验证码|支付密码")
        private val TRANSIENT_LABEL = Regex("^(?:\\d{1,2}\\s*[sS秒]|跳过(?:\\s*\\d{1,2}\\s*[sS秒]?)?|(?:广告|廣告)\\s*[∨⌄▾▼﹀︾])$")
        private val GENERIC_NAMES = setOf("广告", "跳过", "关闭", "更多", "菜单", "返回", "搜索", "关闭小程序",
            "关闭当前小程序", "退出小程序", "小程序", "微信", "QQ", "支付宝", "加载中", "加载中…")
    }
}
