package wcbet.job

import org.slf4j.LoggerFactory
import ru.tinkoff.kora.common.Component
import ru.tinkoff.kora.scheduling.jdk.annotation.ScheduleAtFixedRate
import wcbet.config.AppConfig
import wcbet.fifa.FifaApi
import wcbet.model.Match
import wcbet.model.STATUS_ACTIVE
import wcbet.repo.BetRepository
import wcbet.repo.DigestRepository
import wcbet.repo.MatchRepository
import wcbet.repo.UserRepository
import wcbet.service.ScoreCalculator
import wcbet.telegram.BetBot
import wcbet.telegram.escapeHtml
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Тянет расписание/результаты из FIFA API, обновляет матчи,
 * пересчитывает очки (идемпотентно) и шлёт уведомления о результатах.
 */
@Component
class SyncJob(
    private val fifaApi: FifaApi,
    private val matchRepository: MatchRepository,
    private val betRepository: BetRepository,
    private val userRepository: UserRepository,
    private val bot: BetBot,
    private val appConfig: AppConfig,
) {

    private val log = LoggerFactory.getLogger(SyncJob::class.java)

    @ScheduleAtFixedRate(config = "jobs.sync")
    fun sync() {
        val stages = try {
            fifaApi.rounds()
        } catch (e: Exception) {
            log.warn("Can not fetch matches from FIFA API: {}", e.message)
            return
        }

        for (stage in stages) {
            for (dto in stage.tournaments) {
                // в плей-офф команды известны не сразу — пропускаем заглушки
                val home = dto.homeSquadName?.takeIf { it.isNotBlank() } ?: continue
                val away = dto.awaySquadName?.takeIf { it.isNotBlank() } ?: continue
                val date = try {
                    OffsetDateTime.parse(dto.date)
                } catch (e: Exception) {
                    log.warn("Bad date '{}' for match {}", dto.date, dto.id)
                    continue
                }
                matchRepository.upsert(
                    Match(
                        id = dto.id,
                        stage = stage.stage,
                        date = date,
                        homeSquadName = home,
                        awaySquadName = away,
                        homeScore = dto.homeScore,
                        awayScore = dto.awayScore,
                        status = (dto.status ?: "scheduled").lowercase(),
                    )
                )
            }
        }

        recalculatePoints()
    }

    private fun recalculatePoints() {
        val finishedStatuses = appConfig.finishedStatuses().map { it.lowercase() }.toSet()

        for (match in matchRepository.findWithScore()) {
            val resultHome = match.homeScore ?: continue
            val resultAway = match.awayScore ?: continue
            val finished = match.status in finishedStatuses

            for (bet in betRepository.findByMatch(match.id)) {
                val points = ScoreCalculator.points(bet.homeScore, bet.awayScore, resultHome, resultAway)
                if (bet.points != points) {
                    betRepository.updatePoints(bet.id, points)
                }
                if (finished && !bet.resultNotified) {
                    userRepository.findById(bet.userId)?.let { bot.sendResult(it, match, bet, points) }
                    betRepository.markNotified(bet.id)
                }
            }
        }
    }
}

/**
 * Рассылает активным игрокам матчи, начинающиеся в ближайшие notifyHorizonHours часов,
 * по которым у игрока ещё нет ставки.
 */
@Component
class NotificationJob(
    private val bot: BetBot,
    private val userRepository: UserRepository,
    private val matchRepository: MatchRepository,
    private val betRepository: BetRepository,
    private val appConfig: AppConfig,
) {

    private val log = LoggerFactory.getLogger(NotificationJob::class.java)

    @ScheduleAtFixedRate(config = "jobs.notify")
    fun notifyMatches() {
        val now = OffsetDateTime.now()
        val matches = matchRepository.findBetween(now, now.plusHours(appConfig.notifyHorizonHours()))
        if (matches.isEmpty()) {
            return
        }
        val users = userRepository.findAllActive()

        for (user in users) {
            for (match in matches) {
                if (betRepository.findByUserAndMatch(user.id, match.id) != null) {
                    continue
                }
                val messageId = bot.sendBetCard(user, match, 0, 0) ?: continue
                betRepository.insert(user.id, match.id, messageId)
                log.info("Sent bet message for match {} to user {}", match.id, user.id)
            }
        }
    }
}

/**
 * Напоминает за reminderMinutes до матча тем, у кого прогноз так и остался 0:0.
 */
@Component
class ReminderJob(
    private val bot: BetBot,
    private val userRepository: UserRepository,
    private val matchRepository: MatchRepository,
    private val betRepository: BetRepository,
    private val appConfig: AppConfig,
) {

    private val log = LoggerFactory.getLogger(ReminderJob::class.java)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    @ScheduleAtFixedRate(config = "jobs.reminder")
    fun remind() {
        val zone = ZoneId.of(appConfig.zone())
        val now = OffsetDateTime.now()
        val matches = matchRepository.findBetween(now, now.plusMinutes(appConfig.reminderMinutes()))

        for (match in matches) {
            for (bet in betRepository.findByMatch(match.id)) {
                if (bet.reminderSent || bet.homeScore != 0 || bet.awayScore != 0) {
                    continue
                }
                val user = userRepository.findById(bet.userId) ?: continue
                if (user.status != STATUS_ACTIVE) {
                    continue
                }
                val time = match.date.atZoneSameInstant(zone).format(timeFormatter)
                bot.sendTo(
                    user,
                    "⏰ <b>${escapeHtml(match.homeSquadName)} — ${escapeHtml(match.awaySquadName)}</b> " +
                        "начнётся в <b>$time</b>, а твой прогноз всё ещё <i>0 : 0</i>.\n" +
                        "Успей поменять — /matches"
                )
                betRepository.markReminderSent(bet.id)
                log.info("Sent 0:0 reminder for match {} to user {}", match.id, user.id)
            }
        }
    }
}

/**
 * Утренний дайджест: результаты вчерашних матчей + таблица одним сообщением.
 * Отправляется раз в день после app.digestTime (фиксируется в digest_log).
 */
@Component
class DigestJob(
    private val bot: BetBot,
    private val userRepository: UserRepository,
    private val matchRepository: MatchRepository,
    private val digestRepository: DigestRepository,
    private val appConfig: AppConfig,
) {

    private val log = LoggerFactory.getLogger(DigestJob::class.java)

    @ScheduleAtFixedRate(config = "jobs.digest")
    fun digest() {
        val zone = ZoneId.of(appConfig.zone())
        val today = LocalDate.now(zone)
        if (LocalTime.now(zone).isBefore(LocalTime.parse(appConfig.digestTime()))) {
            return
        }
        if (digestRepository.countForDay(today) > 0) {
            return
        }
        digestRepository.claim(today) // день занят, даже если вчера матчей не было

        val from = today.minusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val to = today.atStartOfDay(zone).toOffsetDateTime()
        val finished = matchRepository.findBetween(from, to)
            .filter { it.homeScore != null && it.awayScore != null }
        if (finished.isEmpty()) {
            return
        }

        val sb = StringBuilder("☀️ <b>Итоги вчерашних матчей</b>\n\n")
        for (m in finished) {
            sb.append("🏁 ${escapeHtml(m.homeSquadName)} <b>${m.homeScore}:${m.awayScore}</b> ${escapeHtml(m.awaySquadName)}\n")
        }
        sb.append("\n").append(bot.leaderboardText())
        sb.append("\n⚽️ Матчи сегодня — /matches")

        val text = sb.toString()
        val users = userRepository.findAllActive()
        users.forEach { bot.sendTo(it, text) }
        log.info("Sent morning digest to {} users ({} matches)", users.size, finished.size)
    }
}
