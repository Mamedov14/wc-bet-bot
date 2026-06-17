package wcbet.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import ru.tinkoff.kora.common.Component
import wcbet.config.AppConfig
import wcbet.config.BotConfig
import wcbet.model.Bet
import wcbet.model.BetUser
import wcbet.model.Match
import wcbet.model.STATUS_ACTIVE
import wcbet.model.STATUS_STOPPED
import wcbet.repo.BetRepository
import wcbet.repo.MatchRepository
import wcbet.repo.UserRepository
import wcbet.service.AnalyticsService
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class BetBot(
    botConfig: BotConfig,
    private val appConfig: AppConfig,
    private val userRepository: UserRepository,
    private val matchRepository: MatchRepository,
    private val betRepository: BetRepository,
    private val analyticsService: AnalyticsService,
) : TelegramLongPollingBot(botConfig.token()) {

    private val log = LoggerFactory.getLogger(BetBot::class.java)
    private val botName = botConfig.name()
    private val adminId = botConfig.adminId()
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun getBotUsername(): String = botName

    override fun onUpdateReceived(update: Update) {
        try {
            when {
                update.hasMessage() && update.message.hasText() -> handleCommand(update.message)
                update.hasCallbackQuery() -> handleCallback(update.callbackQuery)
            }
        } catch (e: Exception) {
            log.error("Failed to handle update {}", update.updateId, e)
        }
    }

    // --- commands ---

    private fun handleCommand(message: Message) {
        val text = message.text.trim()
        if (!text.startsWith("/")) {
            return
        }
        // "/table@my_bot arg" -> команда "/table", адресат "my_bot"
        val token = text.split(" ", limit = 2)[0]
        val target = token.substringAfter("@", "")
        if (target.isNotEmpty() && !target.equals(botName, ignoreCase = true)) {
            return // команда адресована другому боту
        }
        val command = token.substringBefore("@").lowercase()
        val from = message.from ?: return

        // в группах работает только таблица — ставки делаются в личке
        if (!message.chat.isUserChat) {
            when (command) {
                "/table", "/score" -> send(message.chatId, leaderboardText())
                "/start", "/help", "/matches", "/my" ->
                    send(message.chatId, "Прогнозы делаются в личке — напиши мне: @$botName 😉\nЗдесь работает /table")
            }
            return
        }

        when (command) {
            "/start" -> {
                userRepository.upsert(BetUser(from.id, message.chatId, from.userName, from.firstName, STATUS_ACTIVE))
                send(message.chatId, welcomeText(from.firstName))
                sendUpcomingMatches(from.id, message.chatId, silentIfEmpty = true)
            }

            "/matches", "/today" -> sendUpcomingMatches(from.id, message.chatId, silentIfEmpty = false)

            "/my", "/bets" -> send(message.chatId, myBetsText(from.id))

            "/table", "/score" -> send(message.chatId, leaderboardText())

            "/stop" -> {
                userRepository.updateStatus(from.id, STATUS_STOPPED)
                send(message.chatId, "🔕 Окей, больше не беспокою.\nВернуться в игру — /start")
            }

            // --- админские команды (не светятся в меню) ---
            "/stats", "/consensus", "/crowd", "/broadcast" -> {
                if (adminId == null || from.id != adminId) {
                    send(message.chatId, "Эта команда только для админа 😎")
                    return
                }
                when (command) {
                    "/stats" -> send(message.chatId, analyticsService.statsText())
                    "/consensus", "/crowd" -> send(message.chatId, analyticsService.consensusText())
                    "/broadcast" -> broadcast(message, token)
                }
            }

            else -> send(
                message.chatId,
                """
                Не знаю такой команды 🤷

                ⚽️ /matches — матчи для прогноза
                📋 /my — мои ставки на сегодня
                📊 /table — таблица игроков
                🔕 /stop — отписаться
                """.trimIndent()
            )
        }
    }

    private fun welcomeText(firstName: String?): String = """
        Привет, ${escapeHtml(firstName ?: "болельщик")}! ⚽️

        Это конкурс прогнозов на <b>ЧМ-2026</b>. Каждый день я присылаю матчи — ставь счёт кнопками под сообщением. До стартового свистка прогноз можно менять сколько угодно, со свистком ставка фиксируется.

        🎯 <b>Очки</b>
        0 — не угадал исход
        1 — угадал победителя
        2 — угадал победителя и разницу мячей
        3 — угадал точный счёт
        2 — угадал ничью · 3 — точный счёт ничьей

        ⚽️ /matches — матчи для прогноза
        📋 /my — мои ставки на сегодня
        📊 /table — таблица игроков
        🔕 /stop — отписаться

        Удачи! 🏆
    """.trimIndent()

    /**
     * Шлёт карточки всех ещё не начавшихся матчей в ближайшие notifyHorizonHours часов.
     * Если ставки нет — создаёт; если есть — присылает свежую карточку с текущим прогнозом.
     */
    private fun sendUpcomingMatches(userId: Long, chatId: Long, silentIfEmpty: Boolean) {
        val user = userRepository.findById(userId) ?: run {
            send(chatId, "Сначала подпишись — /start")
            return
        }
        val now = OffsetDateTime.now()
        val matches = matchRepository.findBetween(now, now.plusHours(appConfig.notifyHorizonHours()))
        if (matches.isEmpty()) {
            if (!silentIfEmpty) {
                send(chatId, "😴 Ближайших матчей для прогноза нет.\nКак только появятся — пришлю сам!")
            }
            return
        }
        for (match in matches) {
            val bet = betRepository.findByUserAndMatch(user.id, match.id)
            val messageId = sendBetCard(user, match, bet?.homeScore ?: 0, bet?.awayScore ?: 0) ?: continue
            if (bet == null) {
                betRepository.insert(user.id, match.id, messageId)
            } else {
                betRepository.updateMessageId(bet.id, messageId)
            }
        }
    }

    /** Сводка ставок игрока на сегодня одним сообщением. */
    private fun myBetsText(userId: Long): String {
        val zone = ZoneId.of(appConfig.zone())
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime()
        val matches = matchRepository.findBetween(startOfDay, startOfDay.plusDays(1))
        if (matches.isEmpty()) {
            return "Сегодня матчей нет 😴\nБлижайшие пришлю сам, или смотри /matches"
        }
        val now = OffsetDateTime.now()
        val sb = StringBuilder("📋 <b>Твои ставки на сегодня</b>\n\n")
        for (match in matches) {
            val bet = betRepository.findByUserAndMatch(userId, match.id)
            val betStr = bet?.let { "${it.homeScore}:${it.awayScore}" } ?: "—"
            val time = match.date.atZoneSameInstant(zone).format(timeFormatter)
            val home = escapeHtml(match.homeSquadName)
            val away = escapeHtml(match.awaySquadName)
            val line = when {
                match.homeScore != null && match.awayScore != null -> {
                    val pts = bet?.points?.let { " → <b>+$it</b>" } ?: ""
                    "🏁 $home <b>${match.homeScore}:${match.awayScore}</b> $away · прогноз $betStr$pts"
                }

                match.started(now) -> "🔒 $time $home — $away · прогноз <b>$betStr</b>"

                else -> "🕒 $time $home — $away · прогноз <b>$betStr</b>"
            }
            sb.append(line).append("\n")
        }
        sb.append("\nПоменять прогноз — /matches")
        return sb.toString()
    }

    fun leaderboardText(): String {
        val rows = betRepository.leaderboard()
        if (rows.isEmpty()) {
            return "Пока никто не играет. Будь первым — /start"
        }
        val sb = StringBuilder("🏆 <b>Таблица игроков</b>\n\n")
        rows.forEachIndexed { i, row ->
            val place = when (i) {
                0 -> "🥇"
                1 -> "🥈"
                2 -> "🥉"
                else -> "  ${i + 1}."
            }
            val points = "${row.points} ${plural(row.points, "очко", "очка", "очков")}"
            val matches = "${row.matches} ${plural(row.matches, "матч", "матча", "матчей")}"
            sb.append("$place ${escapeHtml(row.name)} — <b>$points</b> · $matches\n")
        }
        return sb.toString()
    }

    // --- callbacks: b:{matchId}:{h+|h-|a+|a-} ---

    private fun handleCallback(callback: CallbackQuery) {
        val parts = callback.data?.split(":") ?: return
        if (parts.size != 3 || parts[0] != "b") {
            return
        }
        val matchId = parts[1].toIntOrNull() ?: return
        val match = matchRepository.findById(matchId) ?: return answer(callback, "Матч не найден 🤔")
        val bet = betRepository.findByUserAndMatch(callback.from.id, matchId)
            ?: return answer(callback, "Ставка не найдена — обнови карточки: /matches")

        if (match.started(OffsetDateTime.now())) {
            return answer(callback, "⛔ Матч начался — ставка зафиксирована: ${bet.homeScore}:${bet.awayScore}")
        }

        var home = bet.homeScore
        var away = bet.awayScore
        when (parts[2]) {
            "h+" -> home++
            "h-" -> home--
            "a+" -> away++
            "a-" -> away--
            else -> return
        }
        if (home < 0 || away < 0 || home > 20 || away > 20) {
            return answer(callback, "Такой счёт не поставить 🙂")
        }

        betRepository.updateScore(bet.id, home, away)
        betRepository.logScoreChange(bet.id, callback.from.id, matchId, home, away)
        log.info("Score change: user {} match {} -> {}:{}", callback.from.id, matchId, home, away)
        val edit = EditMessageText().apply {
            chatId = callback.message.chatId.toString()
            messageId = callback.message.messageId   // правим именно ту карточку, где нажали
            text = matchCardText(match, home, away)
            parseMode = "HTML"
            replyMarkup = keyboard(match)
        }
        execute(edit)
        answer(callback, null)
    }

    private fun answer(callback: CallbackQuery, text: String?) {
        val answer = AnswerCallbackQuery(callback.id)
        if (text != null) {
            answer.text = text
            answer.showAlert = false
        }
        execute(answer)
    }

    // --- outgoing messages ---

    /** @return messageId отправленного сообщения или null, если отправить не удалось. */
    fun sendBetCard(user: BetUser, match: Match, home: Int, away: Int): Int? {
        return try {
            val message = SendMessage(user.chatId.toString(), matchCardText(match, home, away)).apply {
                enableHtml(true)
                replyMarkup = keyboard(match)
            }
            execute(message).messageId
        } catch (e: TelegramApiException) {
            handleSendError(user, e)
            null
        }
    }

    /** Рассылка произвольного сообщения всем активным игрокам: /broadcast текст (HTML поддерживается). */
    private fun broadcast(message: Message, commandToken: String) {
        val payload = message.text.trim().removePrefix(commandToken).trim()
        if (payload.isEmpty()) {
            send(
                message.chatId,
                "Использование: <code>/broadcast текст</code>\n" +
                    "Можно несколько строк и HTML-разметку: &lt;b&gt;жирный&lt;/b&gt;, &lt;i&gt;курсив&lt;/i&gt;.\n" +
                    "Уйдёт всем активным игрокам сразу — без подтверждения, так что перечитай перед отправкой 😉"
            )
            return
        }
        val users = userRepository.findAllActive()
        val delivered = users.count { sendTo(it, payload) }
        send(message.chatId, "📨 Доставлено: <b>$delivered</b> из ${users.size}")
    }

    /** Безопасная отправка произвольного текста пользователю (для джобов). @return true, если доставлено. */
    fun sendTo(user: BetUser, text: String): Boolean {
        return try {
            send(user.chatId, text)
            true
        } catch (e: TelegramApiException) {
            handleSendError(user, e)
            false
        }
    }

    fun sendResult(user: BetUser, match: Match, bet: Bet, points: Int) {
        sendTo(
            user,
            """
            🏁 <b>${escapeHtml(match.homeSquadName)} ${match.homeScore}:${match.awayScore} ${escapeHtml(match.awaySquadName)}</b>

            Твой прогноз: ${bet.homeScore}:${bet.awayScore}
            Начислено: <b>+$points ${plural(points.toLong(), "очко", "очка", "очков")}</b>

            📊 Таблица — /table
            """.trimIndent()
        )
    }

    private fun send(chatId: Long, text: String) {
        val message = SendMessage(chatId.toString(), text)
        message.enableHtml(true)
        execute(message)
    }

    private fun handleSendError(user: BetUser, e: TelegramApiException) {
        if (e.message?.contains("blocked", ignoreCase = true) == true) {
            log.warn("User {} blocked the bot, stopping notifications", user.id)
            userRepository.updateStatus(user.id, STATUS_STOPPED)
        } else {
            log.error("Can not send message to user {}", user.id, e)
        }
    }

    private fun matchCardText(match: Match, home: Int, away: Int): String {
        val zone = ZoneId.of(appConfig.zone())
        val localTime = match.date.atZoneSameInstant(zone).format(dateFormatter)
        return """
            ⚽️ <b>${escapeHtml(match.homeSquadName)} — ${escapeHtml(match.awaySquadName)}</b>
            <i>${stageName(match.stage)}</i> · 🕒 $localTime

            Твой прогноз: <b>$home : $away</b>
        """.trimIndent()
    }

    private fun keyboard(match: Match): InlineKeyboardMarkup {
        fun btn(text: String, data: String) = InlineKeyboardButton(text).apply { callbackData = data }
        val id = match.id
        return InlineKeyboardMarkup(
            listOf(
                listOf(btn("➖ ${match.homeSquadName}", "b:$id:h-"), btn("➕ ${match.homeSquadName}", "b:$id:h+")),
                listOf(btn("➖ ${match.awaySquadName}", "b:$id:a-"), btn("➕ ${match.awaySquadName}", "b:$id:a+")),
            )
        )
    }
}
