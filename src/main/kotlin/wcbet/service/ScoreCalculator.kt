package wcbet.service

/**
 * Правила начисления очков:
 *  0 — не угадал победителя
 *  1 — угадал победителя
 *  2 — угадал победителя и счёт
 *  2 — угадал, что будет ничья, но не угадал счёт
 *  3 — матч завершился ничьей и угадан точный счёт
 *
 * Считается по счёту основного времени (как в старом bet-app, пенальти не учитываются).
 */
object ScoreCalculator {

    fun points(betHome: Int, betAway: Int, resultHome: Int, resultAway: Int): Int {
        val betSign = Integer.signum(betHome - betAway)
        val resultSign = Integer.signum(resultHome - resultAway)
        if (betSign != resultSign) {
            return 0
        }
        val exactScore = betHome == resultHome && betAway == resultAway
        return when {
            resultSign == 0 && exactScore -> 3
            resultSign == 0 -> 2
            exactScore -> 2
            else -> 1
        }
    }
}
