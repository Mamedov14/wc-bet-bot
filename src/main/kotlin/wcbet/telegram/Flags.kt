package wcbet.telegram

/**
 * Статичные данные турнира: флаги сборных и состав групп.
 * Ключи — ровно те названия команд, что приходят из FIFA-фида.
 * Если команда не найдена — флаг пустой, группа null (рендерится без флага).
 */

private val FLAGS: Map<String, String> = mapOf(
    "Mexico" to "🇲🇽", "Korea Republic" to "🇰🇷", "Czechia" to "🇨🇿", "South Africa" to "🇿🇦",
    "Canada" to "🇨🇦", "Qatar" to "🇶🇦", "Switzerland" to "🇨🇭", "Bosnia-Herzegovina" to "🇧🇦",
    "Haiti" to "🇭🇹", "Scotland" to "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "Brazil" to "🇧🇷", "Morocco" to "🇲🇦",
    "Australia" to "🇦🇺", "Türkiye" to "🇹🇷", "USA" to "🇺🇸", "Paraguay" to "🇵🇾",
    "Germany" to "🇩🇪", "Curaçao" to "🇨🇼", "Côte d'Ivoire" to "🇨🇮", "Ecuador" to "🇪🇨",
    "Netherlands" to "🇳🇱", "Japan" to "🇯🇵", "Sweden" to "🇸🇪", "Tunisia" to "🇹🇳",
    "Belgium" to "🇧🇪", "Egypt" to "🇪🇬", "IR Iran" to "🇮🇷", "New Zealand" to "🇳🇿",
    "Spain" to "🇪🇸", "Cabo Verde" to "🇨🇻", "Saudi Arabia" to "🇸🇦", "Uruguay" to "🇺🇾",
    "France" to "🇫🇷", "Senegal" to "🇸🇳", "Norway" to "🇳🇴", "Iraq" to "🇮🇶",
    "Argentina" to "🇦🇷", "Algeria" to "🇩🇿", "Austria" to "🇦🇹", "Jordan" to "🇯🇴",
    "Portugal" to "🇵🇹", "Congo DR" to "🇨🇩", "Uzbekistan" to "🇺🇿", "Colombia" to "🇨🇴",
    "England" to "🏴󠁧󠁢󠁥󠁮󠁧󠁿", "Croatia" to "🇭🇷", "Ghana" to "🇬🇭", "Panama" to "🇵🇦",
)

/** Состав групп A–L. */
val GROUPS: Map<Char, List<String>> = linkedMapOf(
    'A' to listOf("Mexico", "Korea Republic", "Czechia", "South Africa"),
    'B' to listOf("Canada", "Qatar", "Switzerland", "Bosnia-Herzegovina"),
    'C' to listOf("Haiti", "Scotland", "Brazil", "Morocco"),
    'D' to listOf("Australia", "Türkiye", "USA", "Paraguay"),
    'E' to listOf("Germany", "Curaçao", "Côte d'Ivoire", "Ecuador"),
    'F' to listOf("Netherlands", "Japan", "Sweden", "Tunisia"),
    'G' to listOf("Belgium", "Egypt", "IR Iran", "New Zealand"),
    'H' to listOf("Spain", "Cabo Verde", "Saudi Arabia", "Uruguay"),
    'I' to listOf("France", "Senegal", "Norway", "Iraq"),
    'J' to listOf("Argentina", "Algeria", "Austria", "Jordan"),
    'K' to listOf("Portugal", "Congo DR", "Uzbekistan", "Colombia"),
    'L' to listOf("England", "Croatia", "Ghana", "Panama"),
)

/** Команда -> буква группы (для быстрого поиска). */
private val TEAM_GROUP: Map<String, Char> =
    GROUPS.entries.flatMap { (letter, teams) -> teams.map { it to letter } }.toMap()

/** Флаг-эмодзи сборной или пустая строка. */
fun flag(team: String): String = FLAGS[team] ?: ""

/** "🇩🇪 Germany" (имя НЕ экранируется — экранируй у вызывающего, если нужно). */
fun withFlag(team: String): String = flag(team).let { if (it.isEmpty()) team else "$it $team" }

fun groupOf(team: String): Char? = TEAM_GROUP[team]
