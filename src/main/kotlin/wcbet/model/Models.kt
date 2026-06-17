package wcbet.model

import ru.tinkoff.kora.database.common.annotation.Column
import ru.tinkoff.kora.database.common.annotation.Id
import ru.tinkoff.kora.database.common.annotation.Table
import ru.tinkoff.kora.database.jdbc.EntityJdbc
import java.time.OffsetDateTime

const val STATUS_ACTIVE = "ACTIVE"
const val STATUS_STOPPED = "STOPPED"

@EntityJdbc
@Table("users")
data class BetUser(
    @Id val id: Long,
    @Column("chat_id") val chatId: Long,
    @Column("username") val username: String?,
    @Column("first_name") val firstName: String?,
    @Column("status") val status: String,
) {
    fun displayName(): String = firstName ?: username ?: id.toString()
}

@EntityJdbc
@Table("matches")
data class Match(
    @Id val id: Int,
    @Column("stage") val stage: String,
    @Column("date") val date: OffsetDateTime,
    @Column("home_squad_name") val homeSquadName: String,
    @Column("away_squad_name") val awaySquadName: String,
    @Column("home_score") val homeScore: Int?,
    @Column("away_score") val awayScore: Int?,
    @Column("status") val status: String,
) {
    fun started(now: OffsetDateTime = OffsetDateTime.now()): Boolean = !date.isAfter(now)
}

@EntityJdbc
@Table("bets")
data class Bet(
    @Id val id: Long,
    @Column("user_id") val userId: Long,
    @Column("match_id") val matchId: Int,
    @Column("message_id") val messageId: Int,
    @Column("home_score") val homeScore: Int,
    @Column("away_score") val awayScore: Int,
    @Column("points") val points: Int?,
    @Column("result_notified") val resultNotified: Boolean,
    @Column("reminder_sent") val reminderSent: Boolean,
    @Column("created_at") val createdAt: OffsetDateTime,
    @Column("updated_at") val updatedAt: OffsetDateTime,
)

@EntityJdbc
data class LeaderboardRow(
    @Column("name") val name: String,
    @Column("points") val points: Long,
    @Column("matches") val matches: Long,
)

@EntityJdbc
data class PlayerStatsRow(
    @Column("name") val name: String,
    @Column("settled") val settled: Long,
    @Column("points") val points: Long,
    @Column("outcomes") val outcomes: Long,
    @Column("exacts") val exacts: Long,
    @Column("draw_bets") val drawBets: Long,
)

@EntityJdbc
data class PlayerWeightRow(
    @Column("user_id") val userId: Long,
    @Column("points") val points: Long,
    @Column("settled") val settled: Long,
)
