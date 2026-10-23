package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class MiniAppSourceTest {
    @Test fun differentNamesAndHostsNeverShareTheNativeAppKey() {
        val wx = MiniAppSource.create("com.tencent.mm", "铁路出行")!!
        val qq = MiniAppSource.create("com.tencent.mobileqq", "铁路出行")!!
        val other = MiniAppSource.create("com.tencent.mm", "天气查询")!!
        assertNotEquals(wx.key, qq.key)
        assertNotEquals(wx.key, other.key)
        assertNotEquals(wx.hostPackage, wx.key)
        assertEquals("微信", wx.hostLabel)
    }
    @Test fun unknownNamesHaveAnExplicitBucketForEachHost() {
        val wx = MiniAppSource.create("com.tencent.mm", null)!!
        val alipay = MiniAppSource.create("com.eg.android.AlipayGphone", "广告")!!
        assertEquals("未识别的小程序", wx.displayName)
        assertNotEquals(wx.key, alipay.key)
        assertNull(MiniAppSource.create("example.app", "铁路出行"))
    }
    @Test fun normalizedNamesRemainStableWithoutStorageKeyDelimiterCollisions() {
        assertEquals(MiniAppSource.create("com.tencent.mm", " 铁路  出行 ")!!.key,
            MiniAppSource.create("com.tencent.mm", "铁路  出行")!!.key)
        assertNotEquals(MiniAppSource.create("com.tencent.mm", "a:b")!!.key,
            MiniAppSource.create("com.tencent.mm", "a%3Ab")!!.key)
    }
}
