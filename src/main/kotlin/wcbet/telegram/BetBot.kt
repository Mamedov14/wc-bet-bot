package wcbet.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
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

    /** Реальный @username бота (узнаём у Telegram через getMe; конфиг BOT_NAME может не совпадать). */
    @Volatile private var resolvedUsername = botConfig.name()

    /** Вызывается при старте: подтягивает настоящий username, чтобы корректно ловить /cmd@username в группах. */
    fun resolveUsername() {
        try {
            execute(GetMe()).userName?.let {
                resolvedUsername = it
                log.info("Resolved bot username: @{}", it)
            }
        } catch (e: Exception) {
            log.warn("Can not resolve bot username via getMe, fallback to BOT_NAME='{}': {}", botName, e.message)
        }
    }
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Лейблы кнопок меню -> команды. */
    private val menuLabels = mapOf(
        "⚽️ Матчи" to "/matches",
        "📋 Мои ставки" to "/my",
        "📊 Таблица" to "/table",
        "🗂 Группы" to "/groups",
        "🏆 Плей-офф" to "/bracket",
    )

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
        val raw = message.text.trim()
        val text = menuLabels[raw] ?: raw   // нажатие кнопки меню -> соответствующая команда
        if (!text.startsWith("/")) {
            return
        }
        // "/table@my_bot arg" -> команда "/table", адресат "my_bot"
        val token = text.split(" ", limit = 2)[0]
        val target = token.substringAfter("@", "")
        if (target.isNotEmpty() && !target.equals(resolvedUsername, ignoreCase = true)) {
            return // команда адресована другому боту
        }
        val command = token.substringBefore("@").lowercase()
        val from = message.from ?: return

        // в группах работает только таблица — ставки делаются в личке
        if (!message.chat.isUserChat) {
            when (command) {
                "/table", "/score" -> send(message.chatId, leaderboardText())
                "/groups", "/group" -> send(message.chatId, groupsText())
                "/bracket", "/playoff" -> send(message.chatId, bracketText())
                "/start", "/help", "/matches", "/my" ->
                    send(message.chatId, "Прогнозы делаются в личке — напиши мне: @$resolvedUsername 😉\nЗдесь работают /table, /groups, /bracket")
            }
            return
        }

        when (command) {
            "/start" -> {
                userRepository.upsert(BetUser(from.id, message.chatId, from.userName, from.firstName, STATUS_ACTIVE))
                reply(message.chatId, welcomeText(from.firstName))
                sendUpcomingMatches(from.id, message.chatId, silentIfEmpty = true)
            }

            "/matches", "/today" -> sendUpcomingMatches(from.id, message.chatId, silentIfEmpty = false)

            "/my", "/bets" -> reply(message.chatId, myBetsText(from.id))

            "/table", "/score" -> reply(message.chatId, leaderboardText())

            "/groups", "/group" -> reply(message.chatId, groupsText())

            "/bracket", "/playoff" -> reply(message.chatId, bracketText())

            "/stop" -> {
                userRepository.updateStatus(from.id, STATUS_STOPPED)
                send(message.chatId, "🔕 Окей, больше не беспокою.\nВернуться в игру — /start")
                return
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

            else -> reply(
                message.chatId,
                """
                Не знаю такой команды 🤷 Жми кнопки меню снизу или:

                ⚽️ /matches — матчи для прогноза
                📋 /my — мои ставки на сегодня
                📊 /table — таблица игроков
                🗂 /groups — таблицы групп
                🏆 /bracket — сетка плей-офф
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

        Жми кнопки меню снизу 👇 или команды:
        ⚽️ /matches — матчи для прогноза
        📋 /my — мои ставки на сегодня
        📊 /table — таблица игроков
        🗂 /groups — таблицы групп
        🏆 /bracket — сетка плей-офф
        🔕 /stop — отписаться

        Удачи! 🏆
    """.trimIndent()

    /** Ближайшие ещё не начавшиеся матчи (по возрастанию даты). */
    private fun upcomingMatches(): List<Match> {
        val now = OffsetDateTime.now()
        return matchRepository.findBetween(now, now.plusHours(appConfig.notifyHorizonHours()))
    }

    /** Возвращает ставку игрока на матч, создавая пустую (0:0), если её ещё нет. */
    private fun ensureBet(userId: Long, matchId: Int): Bet {
        betRepository.findByUserAndMatch(userId, matchId)?.let { return it }
        betRepository.insert(userId, matchId, 0)
        return betRepository.findByUserAndMatch(userId, matchId)!!
    }

    /**
     * Один матч на сообщение-карусель: листается ◀ ▶, счёт ставится тут же.
     * Вместо пачки карточек — одно сообщение на весь тур.
     */
    private fun sendUpcomingMatches(userId: Long, chatId: Long, silentIfEmpty: Boolean) {
        val user = userRepository.findById(userId) ?: run {
            send(chatId, "Сначала подпишись — /start")
            return
        }
        val matches = upcomingMatches()
        if (matches.isEmpty()) {
            if (!silentIfEmpty) {
                send(chatId, "😴 Ближайших матчей для прогноза нет.\nКак только появятся — пришлю сам!")
            }
            return
        }
        noticeText()?.let { send(chatId, it) }
        val match = matches[0]
        val bet = ensureBet(user.id, match.id)
        val message = SendMessage(chatId.toString(), carouselText(match, bet.homeScore, bet.awayScore, 0, matches.size)).apply {
            enableHtml(true)
            replyMarkup = carouselKeyboard(match, 0, matches.size)
        }
        try {
            val sent = execute(message)
            betRepository.updateMessageId(bet.id, sent.messageId)
        } catch (e: TelegramApiException) {
            handleSendError(user, e)
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

    private class TeamStat {
        var p = 0; var w = 0; var d = 0; var l = 0; var gf = 0; var ga = 0; var pts = 0
    }

    /** Таблицы всех групп A–L, посчитанные из сыгранных матчей группового этапа. */
    fun groupsText(): String {
        val played = matchRepository.findAll().filter {
            it.stage.equals("GROUP", ignoreCase = true) && it.homeScore != null && it.awayScore != null
        }
        val stats = HashMap<String, TeamStat>()
        for (m in played) {
            val h = m.homeScore!!
            val a = m.awayScore!!
            val sh = stats.getOrPut(m.homeSquadName) { TeamStat() }
            val sa = stats.getOrPut(m.awaySquadName) { TeamStat() }
            sh.p++; sa.p++
            sh.gf += h; sh.ga += a; sa.gf += a; sa.ga += h
            when {
                h > a -> { sh.w++; sh.pts += 3; sa.l++ }
                h < a -> { sa.w++; sa.pts += 3; sh.l++ }
                else -> { sh.d++; sa.d++; sh.pts++; sa.pts++ }
            }
        }
        val sb = StringBuilder("📊 <b>Группы</b>\n")
        for ((letter, teams) in GROUPS) {
            sb.append("\n<b>Группа $letter</b>\n")
            val rows = teams.map { it to (stats[it] ?: TeamStat()) }
                .sortedWith(
                    compareByDescending<Pair<String, TeamStat>> { it.second.pts }
                        .thenByDescending { it.second.gf - it.second.ga }
                        .thenByDescending { it.second.gf }
                )
            rows.forEachIndexed { i, (team, s) ->
                val mark = if (i < 2) "✅" else "▪️"
                val diffVal = s.gf - s.ga
                val diff = if (diffVal > 0) "+$diffVal" else "$diffVal"
                sb.append("$mark ${flag(team)} ${escapeHtml(team)} — <b>${s.pts}</b> <i>(${s.p} и, $diff)</i>\n")
            }
        }
        sb.append("\n✅ — топ-2 выходят в плей-офф")
        return sb.toString()
    }

    /** Сетка плей-офф по стадиям: 1/16 → финал. */
    fun bracketText(): String {
        val order = listOf("R32", "R16", "QF", "SF", "F")
        val ko = matchRepository.findAll().filter { m -> order.any { it.equals(m.stage, ignoreCase = true) } }
        if (ko.isEmpty()) {
            return "🏆 Плей-офф ещё не начался — идёт групповой этап.\nСетка появится, когда определятся пары. Группы — /groups"
        }
        val zone = ZoneId.of(appConfig.zone())
        val byStage = ko.groupBy { it.stage.uppercase() }
        val sb = StringBuilder("🏆 <b>Плей-офф</b>\n")
        for (st in order) {
            val list = byStage[st]?.sortedBy { it.date } ?: continue
            sb.append("\n<b>${stageName(st)}</b>\n")
            for (m in list) {
                val home = "${flag(m.homeSquadName)} ${escapeHtml(m.homeSquadName)}"
                val away = "${flag(m.awaySquadName)} ${escapeHtml(m.awaySquadName)}"
                if (m.homeScore != null && m.awayScore != null) {
                    sb.append("$home <b>${m.homeScore}:${m.awayScore}</b> $away\n")
                } else {
                    val t = m.date.atZoneSameInstant(zone).format(dateFormatter)
                    sb.append("$home — $away · 🕒 $t\n")
                }
            }
        }
        return sb.toString()
    }

    // --- callbacks ---
    //   b:{matchId}:{h+|h-|a+|a-}        — одиночная карточка (уведомление)
    //   b:{matchId}:{h+|h-|a+|a-}:{idx}  — карусель (/matches), idx = позиция в списке
    //   nav:{idx}                        — листание карусели
    //   nop                              — счётчик "i/n", без действия

    private fun handleCallback(callback: CallbackQuery) {
        val data = callback.data ?: return
        when {
            data == "nop" -> answer(callback, null)
            data.startsWith("nav:") -> handleNav(callback, data.removePrefix("nav:").toIntOrNull())
            data.startsWith("b:") -> handleBet(callback, data.split(":"))
            else -> {}
        }
    }

    /** Изменение счёта. parts = [b, matchId, op] (одиночная) или [b, matchId, op, idx] (карусель). */
    private fun handleBet(callback: CallbackQuery, parts: List<String>) {
        if (parts.size < 3) return
        val matchId = parts[1].toIntOrNull() ?: return
        val idx = parts.getOrNull(3)?.toIntOrNull()   // null => одиночная карточка
        val match = matchRepository.findById(matchId) ?: return answer(callback, "Матч не найден 🤔")
        val bet = ensureBet(callback.from.id, matchId)

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

        if (idx == null) {
            // одиночная карточка из уведомления
            editMessage(callback, matchCardText(match, home, away), keyboard(match))
        } else {
            // карусель: перерисовываем этот же матч, удерживая позицию (с поправкой на сдвиг списка)
            val matches = upcomingMatches()
            if (matches.isEmpty()) {
                editMessage(callback, "😴 Ближайших матчей нет — ставки закрыты.", null)
            } else {
                val pos = matches.indexOfFirst { it.id == matchId }.takeIf { it >= 0 }
                    ?: idx.coerceIn(0, matches.size - 1)
                val m = matches[pos]
                val b = ensureBet(callback.from.id, m.id)
                editMessage(callback, carouselText(m, b.homeScore, b.awayScore, pos, matches.size), carouselKeyboard(m, pos, matches.size))
            }
        }
        answer(callback, null)
    }

    /** Листание карусели на абсолютную позицию idx. */
    private fun handleNav(callback: CallbackQuery, idxRaw: Int?) {
        val matches = upcomingMatches()
        if (matches.isEmpty()) {
            editMessage(callback, "😴 Ближайших матчей нет.", null)
            return answer(callback, null)
        }
        val idx = (idxRaw ?: 0).coerceIn(0, matches.size - 1)
        val m = matches[idx]
        val b = ensureBet(callback.from.id, m.id)
        editMessage(callback, carouselText(m, b.homeScore, b.awayScore, idx, matches.size), carouselKeyboard(m, idx, matches.size))
        answer(callback, null)
    }

    /** Правит сообщение, где нажали кнопку (markup=null убирает клавиатуру). */
    private fun editMessage(callback: CallbackQuery, text: String, markup: InlineKeyboardMarkup?) {
        val edit = EditMessageText().apply {
            chatId = callback.message.chatId.toString()
            messageId = callback.message.messageId
            this.text = text
            parseMode = "HTML"
            replyMarkup = markup
        }
        execute(edit)
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

    /** Постоянная клавиатура-меню под полем ввода (кнопки = команды). */
    private fun mainKeyboard(): ReplyKeyboardMarkup {
        val row1 = KeyboardRow().apply { add("⚽️ Матчи"); add("📋 Мои ставки") }
        val row2 = KeyboardRow().apply { add("📊 Таблица"); add("🗂 Группы"); add("🏆 Плей-офф") }
        return ReplyKeyboardMarkup(listOf(row1, row2)).apply { resizeKeyboard = true }
    }

    /** Ответ в личке с прикреплённой клавиатурой-меню. */
    private fun reply(chatId: Long, text: String) {
        val message = SendMessage(chatId.toString(), text)
        message.enableHtml(true)
        message.replyMarkup = mainKeyboard()
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
            ⚽️ <b>${flag(match.homeSquadName)} ${escapeHtml(match.homeSquadName)} — ${flag(match.awaySquadName)} ${escapeHtml(match.awaySquadName)}</b>
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

    /** "· через 3 ч" / "· через 40 мин" до старта (или пусто). */
    private fun timeLeft(date: OffsetDateTime): String {
        val mins = java.time.Duration.between(OffsetDateTime.now(), date).toMinutes()
        return when {
            mins <= 0 -> ""
            mins < 60 -> " · через $mins мин"
            else -> " · через ${mins / 60} ч"
        }
    }

    private fun carouselText(match: Match, home: Int, away: Int, idx: Int, total: Int): String {
        val zone = ZoneId.of(appConfig.zone())
        val localTime = match.date.atZoneSameInstant(zone).format(dateFormatter)
        return """
            ⚽️ <b>${flag(match.homeSquadName)} ${escapeHtml(match.homeSquadName)} — ${flag(match.awaySquadName)} ${escapeHtml(match.awaySquadName)}</b>
            <i>${stageName(match.stage)}</i> · 🕒 $localTime${timeLeft(match.date)}

            Твой прогноз: <b>$home : $away</b>

            <i>Матч ${idx + 1}/$total · листай ◀ ▶</i>
        """.trimIndent()
    }

    private fun carouselKeyboard(match: Match, idx: Int, total: Int): InlineKeyboardMarkup {
        fun btn(text: String, data: String) = InlineKeyboardButton(text).apply { callbackData = data }
        val id = match.id
        val rows = mutableListOf(
            listOf(btn("➖ ${match.homeSquadName}", "b:$id:h-:$idx"), btn("➕ ${match.homeSquadName}", "b:$id:h+:$idx")),
            listOf(btn("➖ ${match.awaySquadName}", "b:$id:a-:$idx"), btn("➕ ${match.awaySquadName}", "b:$id:a+:$idx")),
        )
        if (total > 1) {
            val prev = (idx - 1 + total) % total
            val next = (idx + 1) % total
            rows.add(
                listOf(
                    btn("◀", "nav:$prev"),
                    btn("${idx + 1}/$total", "nop"),
                    btn("▶", "nav:$next"),
                )
            )
        }
        return InlineKeyboardMarkup(rows)
    }
}
