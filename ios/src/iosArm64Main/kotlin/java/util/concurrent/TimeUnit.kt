package java.util.concurrent

enum class TimeUnit(private val ms: Long) {
    MILLISECONDS(1), SECONDS(1000), MINUTES(60_000), HOURS(3_600_000), DAYS(86_400_000);
    fun toMillis(d: Long) = d * ms
    fun toSeconds(d: Long) = d * ms / 1000
}
