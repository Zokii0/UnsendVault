package com.riyadm.unsendvault.data

import com.riyadm.unsendvault.capture.CapturedMessage
import com.riyadm.unsendvault.capture.ParsedNotification
import com.riyadm.unsendvault.core.model.ContentKind

object DeviceFx {
    const val PKG = "com.example.chat"
    const val T0 = 1_700_000_000_000L

    fun msg(text: String, sender: String? = "Alice", ts: Long = T0, key: String = "c1") =
        CapturedMessage(PKG, key, "Alice", false, sender, text, ContentKind.TEXT, ts, true)

    fun parsed(vararg messages: CapturedMessage, notificationKey: String = "0|$PKG|1|null|10001") =
        ParsedNotification(PKG, "Chat", notificationKey, T0, "test", messages.toList())
}
