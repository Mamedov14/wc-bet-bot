package wcbet.service

/**
 * Правила начисления очков (v2):
 *  0 — не угадал исход
 *  1 — угадал победителя, но не разницу мячей
 *  2 — угадал победителя и разницу мячей
 *  3 — угадал победителя и точный счёт
 *  2 — угадал, что будет ничья (разница совпала автоматически)
 *  3 — угадал точный счёт ничьей
 *
 * Считается по счёту основного времени (пенальти не учитываются).
 */
object ScoreCalculator {

    fun points(betHome: Int, betAway: Int, resultHome: Int, resultAway: Int): Int {
        val betDiff = betHome - betAway
        val resultDiff = resultHome - resultAway
        if (Integer.signum(betDiff) != Integer.signum(resultDiff)) {
            return 0
        }
        return when {
            betHome == resultHome && betAway == resultAway -> 3
            betDiff == resultDiff -> 2
            else -> 1
        }
    }
}
