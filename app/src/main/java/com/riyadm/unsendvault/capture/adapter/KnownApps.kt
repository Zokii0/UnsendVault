package com.riyadm.unsendvault.capture.adapter

/**
 * Display labels for well-known messaging packages. Used when PackageManager cannot resolve a label (API 30+ package
 * visibility hides packages that are not declared in `<queries>`), and to recognise titles that just name the app.
 */
object KnownApps {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val INSTAGRAM = "com.instagram.android"
    const val INSTAGRAM_LITE = "com.instagram.lite"
    const val THREADS = "com.instagram.barcelona"
    const val MESSENGER = "com.facebook.orca"
    const val MESSENGER_LITE = "com.facebook.mlite"
    const val FACEBOOK = "com.facebook.katana"
    const val FACEBOOK_LITE = "com.facebook.lite"
    const val TELEGRAM = "org.telegram.messenger"
    const val TELEGRAM_WEB = "org.telegram.messenger.web"
    const val TELEGRAM_X = "org.thunderdog.challegram"
    const val SIGNAL = "org.thoughtcrime.securesms"
    const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    const val SAMSUNG_MESSAGES = "com.samsung.android.messaging"
    const val DISCORD = "com.discord"
    const val VIBER = "com.viber.voip"
    const val LINE = "jp.naver.line.android"
    const val SNAPCHAT = "com.snapchat.android"
    const val SLACK = "com.Slack"
    const val TEAMS = "com.microsoft.teams"
    const val SKYPE = "com.skype.raider"
    const val SKYPE_LITE = "com.skype.m2"
    const val WECHAT = "com.tencent.mm"
    const val KAKAOTALK = "com.kakao.talk"
    const val THREEMA = "ch.threema.app"
    const val THREEMA_WORK = "ch.threema.app.work"
    const val THREEMA_LIBRE = "ch.threema.app.libre"
    const val SESSION = "network.loki.messenger"
    const val ELEMENT = "im.vector.app"
    const val ELEMENT_X = "io.element.android.x"
    const val WIRE = "com.wire"
    const val IMO = "com.imo.android.imoim"
    const val IMO_LITE = "com.imo.android.imoimlite"
    const val BOTIM = "im.thebot.messenger"
    const val TRUECALLER = "com.truecaller"

    private val LABELS: Map<String, String> = mapOf(
        WHATSAPP to "WhatsApp",
        WHATSAPP_BUSINESS to "WhatsApp Business",
        INSTAGRAM to "Instagram",
        INSTAGRAM_LITE to "Instagram Lite",
        THREADS to "Threads",
        MESSENGER to "Messenger",
        MESSENGER_LITE to "Messenger Lite",
        FACEBOOK to "Facebook",
        FACEBOOK_LITE to "Facebook Lite",
        TELEGRAM to "Telegram",
        TELEGRAM_WEB to "Telegram",
        TELEGRAM_X to "Telegram X",
        SIGNAL to "Signal",
        GOOGLE_MESSAGES to "Messages",
        SAMSUNG_MESSAGES to "Samsung Messages",
        DISCORD to "Discord",
        VIBER to "Viber",
        LINE to "LINE",
        SNAPCHAT to "Snapchat",
        SLACK to "Slack",
        TEAMS to "Microsoft Teams",
        SKYPE to "Skype",
        SKYPE_LITE to "Skype Lite",
        WECHAT to "WeChat",
        KAKAOTALK to "KakaoTalk",
        THREEMA to "Threema",
        THREEMA_WORK to "Threema Work",
        THREEMA_LIBRE to "Threema Libre",
        SESSION to "Session",
        ELEMENT to "Element",
        ELEMENT_X to "Element X",
        WIRE to "Wire",
        IMO to "imo",
        IMO_LITE to "imo Lite",
        BOTIM to "BOTIM",
        TRUECALLER to "Truecaller",
    )

    fun labelFor(packageName: String): String? = LABELS[packageName]

    val packages: Set<String> get() = LABELS.keys
}
