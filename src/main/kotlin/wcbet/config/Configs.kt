package wcbet.config

import ru.tinkoff.kora.config.common.annotation.ConfigSource

@ConfigSource("bot")
interface BotConfig {
    fun token(): String
    fun name(): String

    /** Telegram id админа — открывает /stats и /consensus. Не задан — команды отключены. */
    fun adminId(): Long?
}

@ConfigSource("app")
interface AppConfig {
    /** Часовой пояс, в котором живут игроки (форматирование времени матчей). */
    fun zone(): String

    /** Статусы матча из API, которые считаем финальными (для уведомления о результате). */
    fun finishedStatuses(): List<String>

    /** За сколько часов вперёд рассылать матчи для прогноза. */
    fun notifyHorizonHours(): Long

    /** За сколько минут до матча напоминать тем, у кого прогноз остался 0:0. */
    fun reminderMinutes(): Long

    /** Время утреннего дайджеста (HH:mm, в поясе app.zone). */
    fun digestTime(): String

    /**
     * Плашка о временном режиме. Если непусто — добавляется к /start, /matches, /my, /table
     * и в дайджест. После слияния баз очистить (пустая строка/убрать env) — плашка исчезнет.
     */
    fun temporaryNotice(): String?
}
