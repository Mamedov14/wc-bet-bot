package wcbet.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.generics.BotSession
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import ru.tinkoff.kora.application.graph.Lifecycle
import ru.tinkoff.kora.common.Component
import ru.tinkoff.kora.common.annotation.Root

@Root
@Component
class BotRegistrar(private val bot: BetBot) : Lifecycle {

    private val log = LoggerFactory.getLogger(BotRegistrar::class.java)
    private var session: BotSession? = null

    override fun init() {
        session = TelegramBotsApi(DefaultBotSession::class.java).registerBot(bot)
        bot.resolveUsername()
        registerCommands()
        log.info("Telegram bot '{}' registered, long polling started", bot.botUsername)
    }

    override fun release() {
        session?.stop()
        log.info("Telegram bot session stopped")
    }

    /** Меню команд в интерфейсе Telegram (кнопка «Меню» слева от поля ввода). */
    private fun registerCommands() {
        bot.execute(
            SetMyCommands.builder()
                .commands(
                    listOf(
                        BotCommand("matches", "⚽️ матчи для прогноза"),
                        BotCommand("my", "📋 мои ставки на сегодня"),
                        BotCommand("table", "📊 таблица игроков"),
                        BotCommand("groups", "🗂 таблицы групп"),
                        BotCommand("bracket", "🏆 сетка плей-офф"),
                        BotCommand("start", "🔔 подписаться на матчи"),
                        BotCommand("stop", "🔕 отписаться"),
                    )
                )
                .build()
        )
    }
}
