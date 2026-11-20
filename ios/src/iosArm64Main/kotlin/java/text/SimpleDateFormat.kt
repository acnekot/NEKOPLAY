package java.text

import platform.Foundation.*

class SimpleDateFormat(pattern: String, locale: java.util.Locale? = null) {
    private val f = NSDateFormatter().apply {
        dateFormat = pattern
        setLocale(NSLocale(localeIdentifier = locale?.id ?: "en_US_POSIX"))
        timeZone = NSTimeZone.localTimeZone
    }
    fun format(d: java.util.Date): String = f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(d.millis / 1000.0))
    fun format(ms: Long): String = format(java.util.Date(ms))
    fun parse(s: String): java.util.Date? = f.dateFromString(s)?.let { java.util.Date((it.timeIntervalSince1970 * 1000).toLong()) }
}
