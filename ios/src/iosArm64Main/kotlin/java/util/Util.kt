package java.util

import platform.Foundation.*
import kotlin.io.encoding.ExperimentalEncodingApi

// iOS 版的 Date / Calendar / Locale / Base64：只实现共用代码用到的部分

class Date(val millis: Long = (NSDate().timeIntervalSince1970 * 1000).toLong()) {
    val time: Long get() = millis
    fun getTime() = millis
    internal fun ns(): NSDate = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
}

class Locale private constructor(val id: String) {
    companion object {
        val CHINA = Locale("zh_CN"); val CHINESE = Locale("zh"); val SIMPLIFIED_CHINESE = Locale("zh_CN")
        val US = Locale("en_US"); val ENGLISH = Locale("en"); val ROOT = Locale("en_US_POSIX")
        fun getDefault() = Locale(NSLocale.currentLocale.localeIdentifier)
    }
}

class TimeZone private constructor(internal val tz: NSTimeZone) {
    companion object { fun getDefault() = TimeZone(NSTimeZone.localTimeZone) }
}

class Calendar private constructor() {
    var timeInMillis: Long = Date().millis
    var time: Date
        get() = Date(timeInMillis)
        set(v) { timeInMillis = v.millis }

    private val cal get() = NSCalendar.currentCalendar

    fun get(field: Int): Int {
        val d = Date(timeInMillis).ns()
        return when (field) {
            YEAR -> cal.component(NSCalendarUnitYear, d).toInt()
            MONTH -> cal.component(NSCalendarUnitMonth, d).toInt() - 1
            DAY_OF_MONTH -> cal.component(NSCalendarUnitDay, d).toInt()
            DAY_OF_YEAR -> cal.ordinalityOfUnit(NSCalendarUnitDay, NSCalendarUnitYear, d).toInt()
            DAY_OF_WEEK -> cal.component(NSCalendarUnitWeekday, d).toInt() // 1 = 周日，和 Java 一致
            HOUR_OF_DAY, HOUR -> cal.component(NSCalendarUnitHour, d).toInt().let { if (field == HOUR) it % 12 else it }
            MINUTE -> cal.component(NSCalendarUnitMinute, d).toInt()
            SECOND -> cal.component(NSCalendarUnitSecond, d).toInt()
            MILLISECOND -> (timeInMillis % 1000).toInt()
            WEEK_OF_YEAR -> cal.component(NSCalendarUnitWeekOfYear, d).toInt()
            else -> 0
        }
    }

    fun add(field: Int, amount: Int) {
        val unit = when (field) {
            YEAR -> NSCalendarUnitYear; MONTH -> NSCalendarUnitMonth
            DAY_OF_MONTH, DAY_OF_YEAR, DAY_OF_WEEK -> NSCalendarUnitDay
            WEEK_OF_YEAR -> NSCalendarUnitWeekOfYear
            HOUR_OF_DAY, HOUR -> NSCalendarUnitHour; MINUTE -> NSCalendarUnitMinute; SECOND -> NSCalendarUnitSecond
            else -> { timeInMillis += amount; return }
        }
        cal.dateByAddingUnit(unit, amount.toLong(), Date(timeInMillis).ns(), 0u)?.let { timeInMillis = (it.timeIntervalSince1970 * 1000).toLong() }
    }

    fun set(field: Int, value: Int) {
        val d = Date(timeInMillis).ns()
        val c = cal.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond, d)
        when (field) {
            YEAR -> c.year = value.toLong(); MONTH -> c.month = (value + 1).toLong(); DAY_OF_MONTH -> c.day = value.toLong()
            HOUR_OF_DAY -> c.hour = value.toLong(); MINUTE -> c.minute = value.toLong(); SECOND -> c.second = value.toLong()
            MILLISECOND -> { timeInMillis = timeInMillis - timeInMillis % 1000 + value; return }
            else -> return
        }
        val ms = timeInMillis % 1000
        cal.dateFromComponents(c)?.let { timeInMillis = (it.timeIntervalSince1970 * 1000).toLong() + if (field == SECOND) 0 else ms }
    }

    companion object {
        const val ERA = 0; const val YEAR = 1; const val MONTH = 2; const val WEEK_OF_YEAR = 3; const val DAY_OF_MONTH = 5; const val DATE = 5
        const val DAY_OF_YEAR = 6; const val DAY_OF_WEEK = 7; const val HOUR = 10; const val HOUR_OF_DAY = 11
        const val MINUTE = 12; const val SECOND = 13; const val MILLISECOND = 14
        const val SUNDAY = 1; const val MONDAY = 2; const val SATURDAY = 7
        fun getInstance() = Calendar()
        fun getInstance(@Suppress("UNUSED_PARAMETER") l: Locale) = Calendar()
    }
}

@OptIn(ExperimentalEncodingApi::class)
object Base64 {
    class Encoder internal constructor(private val url: Boolean) {
        fun encodeToString(b: ByteArray): String = (if (url) kotlin.io.encoding.Base64.UrlSafe else kotlin.io.encoding.Base64.Default).encode(b)
        fun encode(b: ByteArray): ByteArray = encodeToString(b).encodeToByteArray()
    }
    class Decoder internal constructor(private val url: Boolean) {
        fun decode(s: String): ByteArray = (if (url) kotlin.io.encoding.Base64.UrlSafe else kotlin.io.encoding.Base64.Default).decode(s)
        fun decode(b: ByteArray): ByteArray = decode(b.decodeToString())
    }
    fun getEncoder() = Encoder(false)
    fun getDecoder() = Decoder(false)
    fun getUrlEncoder() = Encoder(true)
    fun getUrlDecoder() = Decoder(true)
}
