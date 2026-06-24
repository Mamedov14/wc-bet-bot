package wcbet.repo

import ru.tinkoff.kora.database.common.annotation.Query
import ru.tinkoff.kora.database.common.annotation.Repository
import ru.tinkoff.kora.database.jdbc.JdbcRepository
import wcbet.model.Bet
import wcbet.model.BetUser
import wcbet.model.LeaderboardRow
import wcbet.model.Match
import wcbet.model.PlayerStatsRow
import wcbet.model.PlayerWeightRow
import java.time.LocalDate
import java.time.OffsetDateTime

@Repository
interface UserRepository : JdbcRepository {

    @Query("select id, chat_id, username, first_name, status from users where id = :id")
    fun findById(id: Long): BetUser?

    @Query("select id, chat_id, username, first_name, status from users where status = 'ACTIVE'")
    fun findAllActive(): List<BetUser>

    @Query(
        """
        insert into users(id, chat_id, username, first_name, status)
        values (:user.id, :user.chatId, :user.username, :user.firstName, :user.status)
        on conflict (id) do update set
            chat_id = excluded.chat_id,
            username = excluded.username,
            first_name = excluded.first_name,
            status = excluded.status
        """
    )
    fun upsert(user: BetUser)

    @Query("update users set status = :status where id = :id")
    fun updateStatus(id: Long, status: String)
}

@Repository
interface MatchRepository : JdbcRepository {

    @Query("select id, stage, date, home_squad_name, away_squad_name, home_score, away_score, status from matches where id = :id")
    fun findById(id: Int): Match?

    @Query(
        """
        select id, stage, date, home_squad_name, away_squad_name, home_score, away_score, status
        from matches
        where date >= :from and date < :to
        order by date
        """
    )
    fun findBetween(from: OffsetDateTime, to: OffsetDateTime): List<Match>

    @Query(
        """
        select id, stage, date, home_squad_name, away_squad_name, home_score, away_score, status
        from matches
        where home_score is not null and away_score is not null
        """
    )
    fun findWithScore(): List<Match>

    @Query(
        """
        insert into matches(id, stage, date, home_squad_name, away_squad_name, home_score, away_score, status)
        values (:m.id, :m.stage, :m.date, :m.homeSquadName, :m.awaySquadName, :m.homeScore, :m.awayScore, :m.status)
        on conflict (id) do update set
            stage = excluded.stage,
            date = excluded.date,
            home_squad_name = excluded.home_squad_name,
            away_squad_name = excluded.away_squad_name,
            home_score = excluded.home_score,
            away_score = excluded.away_score,
            status = excluded.status
        """
    )
    fun upsert(m: Match)
}

@Repository
interface BetRepository : JdbcRepository {

    @Query("select id, user_id, match_id, message_id, home_score, away_score, points, result_notified, reminder_sent, created_at, updated_at from bets where user_id = :userId and match_id = :matchId")
    fun findByUserAndMatch(userId: Long, matchId: Int): Bet?

    @Query("select id, user_id, match_id, message_id, home_score, away_score, points, result_notified, reminder_sent, created_at, updated_at from bets where match_id = :matchId")
    fun findByMatch(matchId: Int): List<Bet>

    @Query(
        """
        insert into bets(user_id, match_id, message_id)
        values (:userId, :matchId, :messageId)
        on conflict (user_id, match_id) do nothing
        """
    )
    fun insert(userId: Long, matchId: Int, messageId: Int)

    @Query("update bets set home_score = :homeScore, away_score = :awayScore, updated_at = now() where id = :id")
    fun updateScore(id: Long, homeScore: Int, awayScore: Int)

    @Query(
        """
        insert into bet_score_changes(bet_id, user_id, match_id, home_score, away_score)
        values (:betId, :userId, :matchId, :homeScore, :awayScore)
        """
    )
    fun logScoreChange(betId: Long, userId: Long, matchId: Int, homeScore: Int, awayScore: Int)

    @Query("update bets set message_id = :messageId where id = :id")
    fun updateMessageId(id: Long, messageId: Int)

    @Query("update bets set points = :points where id = :id")
    fun updatePoints(id: Long, points: Int)

    @Query("update bets set result_notified = true where id = :id")
    fun markNotified(id: Long)

    @Query("update bets set reminder_sent = true where id = :id")
    fun markReminderSent(id: Long)

    @Query(
        """
        with live as (
            select coalesce('@' || u.username, u.first_name, cast(u.id as varchar)) as label,
                   coalesce(sum(b.points), 0) as pts,
                   count(b.points)            as m
            from users u
                     left join bets b on b.user_id = u.id
            group by u.id
        )
        select coalesce(live.label, leg.label)                  as name,
               coalesce(live.pts, 0) + coalesce(leg.points, 0)  as points,
               coalesce(live.m, 0)   + coalesce(leg.matches, 0) as matches
        from live
                 full outer join legacy_score leg on leg.label = live.label
        order by points desc, matches desc
        """
    )
    fun leaderboard(): List<LeaderboardRow>

    @Query(
        """
        select coalesce(u.first_name, u.username, cast(u.id as varchar))                          as name,
               count(b.id)                                                                        as settled,
               coalesce(sum(b.points), 0)                                                         as points,
               count(b.id) filter (where b.points >= 1)                                           as outcomes,
               count(b.id) filter (where b.home_score = m.home_score
                                     and b.away_score = m.away_score)                             as exacts,
               count(b.id) filter (where b.home_score = b.away_score)                             as draw_bets
        from bets b
                 join users u on u.id = b.user_id
                 join matches m on m.id = b.match_id
        where b.points is not null
        group by u.id
        order by points desc
        """
    )
    fun playerStats(): List<PlayerStatsRow>

    @Query(
        """
        select user_id, coalesce(sum(points), 0) as points, count(*) as settled
        from bets
        where points is not null
        group by user_id
        """
    )
    fun playerWeights(): List<PlayerWeightRow>
}

@Repository
interface DigestRepository : JdbcRepository {

    @Query("select count(*) from digest_log where day = :day")
    fun countForDay(day: LocalDate): Long

    @Query("insert into digest_log(day) values (:day) on conflict (day) do nothing")
    fun claim(day: LocalDate)
}
