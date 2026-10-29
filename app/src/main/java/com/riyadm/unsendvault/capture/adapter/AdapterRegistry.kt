package com.riyadm.unsendvault.capture.adapter

import com.riyadm.unsendvault.capture.NotificationSnapshot
import com.riyadm.unsendvault.capture.parser.CapturePlaceholders
import com.riyadm.unsendvault.capture.parser.ParseOutcome
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps packages to adapters and runs them defensively: an adapter that throws, returns null or returns an empty
 * [ParseOutcome.Parsed] falls back to the generic parser. A [ParseOutcome.Rejected] from an adapter is final.
 */
class AdapterRegistry(adapters: List<AppAdapter>, val generic: AppAdapter) {
    private val byPackage: Map<String, AppAdapter> = buildMap {
        for (adapter in adapters) for (pkg in adapter.packages) put(pkg, adapter)
    }

    val supportedPackages: Set<String> get() = byPackage.keys

    fun adapterFor(packageName: String): AppAdapter = byPackage[packageName] ?: generic

    data class Result(val adapterId: String, val outcome: ParseOutcome)

    fun parse(s: NotificationSnapshot): Result {
        val adapter = adapterFor(s.packageName)
        if (adapter !== generic) {
            when (val outcome = runSafely { adapter.parse(s) }) {
                is ParseOutcome.Rejected -> return Result(adapter.id, outcome)
                is ParseOutcome.Parsed -> if (!outcome.isEmpty) return Result(adapter.id, outcome)
                null -> Unit
            }
        }
        val outcome = runSafely { generic.parse(s) } ?: ParseOutcome.Parsed.EMPTY
        return Result(generic.id, outcome)
    }

    private inline fun runSafely(block: () -> ParseOutcome?): ParseOutcome? = try {
        block()
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    companion object {
        fun createDefault(placeholders: CapturePlaceholders = CapturePlaceholders()): AdapterRegistry {
            val p = placeholders
            fun noise(vararg patterns: String) = patterns.map { Regex("^(?:$it)$", RegexOption.IGNORE_CASE) }
            val adapters = listOf(
                WhatsAppAdapter(p),
                InstagramAdapter(p),
                MessengerAdapter(p),
                FacebookAdapter(p),
                TelegramAdapter(p),
                SignalAdapter(p),
                SmsAdapter(p, SmsAdapter.GOOGLE_MESSAGES_ID, setOf(KnownApps.GOOGLE_MESSAGES)),
                SmsAdapter(p, SmsAdapter.SAMSUNG_MESSAGES_ID, setOf(KnownApps.SAMSUNG_MESSAGES)),
                SimpleAdapter(p, "discord", setOf(KnownApps.DISCORD), dedicatedMessenger = false),
                SimpleAdapter(p, "viber", setOf(KnownApps.VIBER), dedicatedMessenger = true),
                SimpleAdapter(p, "line", setOf(KnownApps.LINE), dedicatedMessenger = true),
                SimpleAdapter(
                    p, "snapchat", setOf(KnownApps.SNAPCHAT), dedicatedMessenger = false,
                    // Snapchat announces chats without content; those carry nothing worth archiving.
                    extraNoise = noise("(?:.{1,60} )?sent you a chat", "(?:.{1,60} )?is typing", "new snap(?:s)?"),
                ),
                SimpleAdapter(p, "slack", setOf(KnownApps.SLACK), dedicatedMessenger = false),
                SimpleAdapter(p, "teams", setOf(KnownApps.TEAMS), dedicatedMessenger = false),
                SimpleAdapter(p, "skype", setOf(KnownApps.SKYPE, KnownApps.SKYPE_LITE), dedicatedMessenger = true),
                SimpleAdapter(
                    p, "wechat", setOf(KnownApps.WECHAT), dedicatedMessenger = true,
                    // WeChat prefixes an unread counter: "[2] Alex: hi", "[2条] Alex: hi".
                    bodyCleaner = { it.replace(WECHAT_COUNTER, "") },
                    splitSenderPrefix = true,
                ),
                SimpleAdapter(p, "kakaotalk", setOf(KnownApps.KAKAOTALK), dedicatedMessenger = true),
                SimpleAdapter(
                    p, "threema", setOf(KnownApps.THREEMA, KnownApps.THREEMA_WORK, KnownApps.THREEMA_LIBRE),
                    dedicatedMessenger = true,
                ),
                SimpleAdapter(p, "session", setOf(KnownApps.SESSION), dedicatedMessenger = true),
                SimpleAdapter(p, "element", setOf(KnownApps.ELEMENT, KnownApps.ELEMENT_X), dedicatedMessenger = true),
                SimpleAdapter(p, "wire", setOf(KnownApps.WIRE), dedicatedMessenger = true),
                SimpleAdapter(p, "imo", setOf(KnownApps.IMO, KnownApps.IMO_LITE), dedicatedMessenger = true),
                SimpleAdapter(p, "botim", setOf(KnownApps.BOTIM), dedicatedMessenger = true),
                // Truecaller is mostly caller ID; only its MessagingStyle / CATEGORY_MESSAGE notifications are messages.
                SimpleAdapter(p, "truecaller", setOf(KnownApps.TRUECALLER), dedicatedMessenger = false),
            )
            return AdapterRegistry(adapters, GenericAdapter(p))
        }

        private val WECHAT_COUNTER = Regex("^\\[\\d+\\s*\\S?\\]\\s*")
    }
}

/**
 * Resolves user-visible app labels: PackageManager first (via [lookup], never enumerating installed packages), then the
 * [KnownApps] table, then the package name. Results are cached for the process lifetime.
 */
class AppLabelResolver(private val lookup: (String) -> String?) {
    private val cache = ConcurrentHashMap<String, String>()

    fun labelFor(packageName: String): String = cache.getOrPut(packageName) {
        val resolved = try {
            lookup(packageName)?.trim()
        } catch (e: Exception) {
            null
        }
        resolved?.takeIf { it.isNotEmpty() && it != packageName } ?: KnownApps.labelFor(packageName) ?: packageName
    }
}
