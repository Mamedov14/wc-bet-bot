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
) : TelegramLongPollingBot(botConfig.token()) {

    private val log = LoggerFactory.getLogger(BetBot::class.java)
    private val botName = botConfig.name()
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

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
        val from = message.from ?: return
        when {
            text.startsWith("/start") -> {
                userRepository.upsert(BetUser(from.id, message.chatId, from.userName, from.firstName, STATUS_ACTIVE))
                send(
                    message.chatId,
                    "Привет, ${from.firstName ?: "болельщик"}! ⚽\n\n" +
                        "Я буду присылать матчи ЧМ-2026 — ставь прогноз кнопками под сообщением.\n" +
                        "Ставка фиксируется со стартовым свистком.\n\n" +
                        "Очки: победитель — 1, победитель и точный счёт — 2, " +
                        "ничья (не угадал счёт) — 2, ничья с точным счётом — 3.\n\n" +
                        "/table — таблица игроков\n/stop — отписаться"
                )
            }

            text.startsWith("/stop") -> {
                userRepository.updateStatus(from.id, STATUS_STOPPED)
                send(message.chatId, "Окей, больше не беспокою. Вернуться — /start")
            }

            text.startsWith("/table") || text.startsWith("/score") -> send(message.chatId, leaderboardText())
        }
    }

    private fun leaderboardText(): String {
        val rows = betRepository.leaderboard()
        if (rows.isEmpty()) {
            return "Пока никто не играет. Будь первым — /start"
        }
        val sb = StringBuilder("🏆 Таблица игроков\n\n")
        rows.forEachIndexed { i, row ->
            val place = when (i) {
                0 -> "🥇"
                1 -> "🥈"
                2 -> "🥉"
                else -> "${i + 1}."
            }
            sb.append("$place ${row.name} — ${row.points} очк. (матчей: ${row.matches})\n")
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
        val match = matchRepository.findById(matchId) ?: return answer(callback, "Матч не найден")
        val bet = betRepository.findByUserAndMatch(callback.from.id, matchId)
            ?: return answer(callback, "Ставка не найдена, дождись новой рассылки")

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
        val edit = EditMessageText().apply {
            chatId = callback.message.chatId.toString()
            messageId = bet.messageId
            text = matchText(match, home, away)
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
    fun sendBetMessage(user: BetUser, match: Match): Int? {
        return try {
            val message = SendMessage(user.chatId.toString(), matchText(match, 0, 0)).apply {
                replyMarkup = keyboard(match)
            }
            execute(message).messageId
        } catch (e: TelegramApiException) {
            handleSendError(user, e)
            null
        }
    }

    fun sendResult(user: BetUser, match: Match, bet: Bet, points: Int) {
        try {
            send(
                user.chatId,
                "🏁 ${match.homeSquadName} ${match.homeScore}:${match.awayScore} ${match.awaySquadName}\n" +
                    "Твой прогноз: ${bet.homeScore}:${bet.awayScore}\n" +
                    "Начислено: +$points очк.\n\nТаблица — /table"
            )
        } catch (e: TelegramApiException) {
            handleSendError(user, e)
        }
    }

    private fun send(chatId: Long, text: String) {
        execute(SendMessage(chatId.toString(), text))
    }

    private fun handleSendError(user: BetUser, e: TelegramApiException) {
        if (e.message?.contains("blocked", ignoreCase = true) == true) {
            log.warn("User {} blocked the bot, stopping notifications", user.id)
            userRepository.updateStatus(user.id, STATUS_STOPPED)
        } else {
            log.error("Can not send message to user {}", user.id, e)
        }
    }

    private fun matchText(match: Match, home: Int, away: Int): String {
        val zone = ZoneId.of(appConfig.zone())
        val localTime = match.date.atZoneSameInstant(zone).format(dateFormatter)
        return "🏆 ${stageName(match.stage)}\n" +
            "${match.homeSquadName} — ${match.awaySquadName}\n" +
            "🕒 $localTime\n\n" +
            "Твой прогноз: $home : $away"
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

    private fun stageName(stage: String): String = when (stage.uppercase()) {
        "GROUP" -> "Групповой этап"
        "R32" -> "1/16 финала"
        "R16" -> "1/8 финала"
        "QF" -> "Четвертьфинал"
        "SF" -> "Полуфинал"
        "F" -> "Финал"
        else -> stage
    }
}
