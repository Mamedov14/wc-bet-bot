package wcbet.fifa

import ru.tinkoff.kora.http.client.common.annotation.HttpClient
import ru.tinkoff.kora.http.common.HttpMethod
import ru.tinkoff.kora.http.common.annotation.HttpRoute
import ru.tinkoff.kora.json.common.annotation.Json

/**
 * Тот же эндпоинт, что использовался в старом bet-app — уже отдаёт расписание ЧМ-2026.
 */
@HttpClient(configPath = "httpClient.fifa")
interface FifaApi {

    @Json
    @HttpRoute(method = HttpMethod.GET, path = "/json/bracket_predictor/rounds.json")
    fun rounds(): List<StageDto>
}

@Json
data class StageDto(
    val id: Int,
    val stage: String,
    val status: String?,
    val startDate: String?,
    val endDate: String?,
    val tournaments: List<MatchDto>,
)

@Json
data class MatchDto(
    val id: Int,
    val venueName: String?,
    val date: String,
    val homeSquadName: String?,
    val awaySquadName: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val homePenaltyScore: Int?,
    val awayPenaltyScore: Int?,
    val status: String?,
)
