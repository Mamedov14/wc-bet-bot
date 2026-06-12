package wcbet.service

import ru.tinkoff.kora.common.Component
import wcbet.config.AppConfig
import wcbet.repo.BetRepository
import wcbet.repo.MatchRepository
import wcbet.telegram.escapeHtml
import wcbet.telegram.plural
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Админская аналитика: статистика игроков и взвешенный консенсус-прогноз.
 * Вес игрока = (очки + 1) / (завершённых ставок + 1) — сглаживание Лапласа,
 * чтобы новичок с одной удачной ставкой не перевешивал стабильного лидера.
 */
@Component
class AnalyticsService(
    private val betRepository: BetRepository,
    private val matchRepository: MatchRepository,
    private val appConfig: AppConfig,
) {

    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    fun statsText(): String {
        val rows = betRepository.playerStats()
        if (rows.isEmpty()) {
            return "📈 Завершённых ставок ещё нет — статистика появится после первых матчей."
        }
        val sb = StringBuilder("📈 <b>Статистика игроков</b> <i>(по завершённым ставкам)</i>\n\n")
        rows.forEachIndexed { i, r ->
            val avg = String.format(Locale.US, "%.2f", r.points.toDouble() / r.settled)
            val outcomePct = r.outcomes * 100 / r.settled
            val drawPct = r.drawBets * 100 / r.settled
            sb.append(
                "${i + 1}. <b>${escapeHtml(r.name)}</b> — ${r.points} ${plural(r.points, "очко", "очка", "очков")} " +
                    "за ${r.settled} ${plural(r.settled, "ставку", "ставки", "ставок")} · ср. <b>$avg</b>\n"
            )
            sb.append("    угадан исход: $outcomePct% · точный счёт: ${r.exacts} · ставит ничьи: $drawPct%\n")
        }
        return sb.toString()
    }

    fun consensusText(): String {
        val now = OffsetDateTime.now()
        val matches = matchRepository.findBetween(now, now.plusHours(appConfig.notifyHorizonHours()))
        if (matches.isEmpty()) {
            return "🔮 Ближайших матчей нет — нечего прогнозировать."
        }
        val weights = betRepository.playerWeights()
            .associate { it.userId to (it.points + 1.0) / (it.settled + 1.0) }
        val zone = ZoneId.of(appConfig.zone())

        val sb = StringBuilder("🔮 <b>Консенсус толпы</b> <i>(вес игрока = ср. очки за ставку)</i>\n\n")
        for (match in matches) {
            val time = match.date.atZoneSameInstant(zone).format(dateFormatter)
            sb.append("⚽️ <b>${escapeHtml(match.homeSquadName)} — ${escapeHtml(match.awaySquadName)}</b> · $time\n")

            val bets = betRepository.findByMatch(match.id)
            if (bets.isEmpty()) {
                sb.append("    ставок пока нет\n\n")
                continue
            }

            var homeWin = 0.0
            var draw = 0.0
            var awayWin = 0.0
            val scoreVotes = HashMap<Pair<Int, Int>, Double>()
            for (bet in bets) {
                val w = weights[bet.userId] ?: 1.0
                when {
                    bet.homeScore > bet.awayScore -> homeWin += w
                    bet.homeScore < bet.awayScore -> awayWin += w
                    else -> draw += w
                }
                scoreVotes.merge(bet.homeScore to bet.awayScore, w, Double::plus)
            }
            val total = homeWin + draw + awayWin
            fun pct(v: Double) = Math.round(v * 100 / total)

            val topScores = scoreVotes.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { "${it.key.first}:${it.key.second}" }

            sb.append("    П1 <b>${pct(homeWin)}%</b> · X <b>${pct(draw)}%</b> · П2 <b>${pct(awayWin)}%</b>\n")
            sb.append("    счёт толпы: <b>$topScores</b> · ${bets.size} ${plural(bets.size.toLong(), "ставка", "ставки", "ставок")}\n\n")
        }
        return sb.toString()
    }
}
