package wcbet.job

import org.slf4j.LoggerFactory
import ru.tinkoff.kora.common.Component
import ru.tinkoff.kora.scheduling.jdk.annotation.ScheduleAtFixedRate
import wcbet.config.AppConfig
import wcbet.fifa.FifaApi
import wcbet.model.Match
import wcbet.repo.BetRepository
import wcbet.repo.MatchRepository
import wcbet.repo.UserRepository
import wcbet.service.ScoreCalculator
import wcbet.telegram.BetBot
import java.time.OffsetDateTime

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
                val messageId = bot.sendBetMessage(user, match) ?: continue
                betRepository.insert(user.id, match.id, messageId)
                log.info("Sent bet message for match {} to user {}", match.id, user.id)
            }
        }
    }
}
