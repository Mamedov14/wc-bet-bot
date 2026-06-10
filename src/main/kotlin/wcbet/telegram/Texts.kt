package wcbet.telegram

/** Экранирование пользовательских строк для Telegram HTML-разметки. */
fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Русские склонения: plural(5, "очко", "очка", "очков") -> "очков". */
fun plural(n: Long, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

fun stageName(stage: String): String = when (stage.uppercase()) {
    "GROUP" -> "Групповой этап"
    "R32" -> "1/16 финала"
    "R16" -> "1/8 финала"
    "QF" -> "Четвертьфинал"
    "SF" -> "Полуфинал"
    "F" -> "Финал"
    else -> stage
}
