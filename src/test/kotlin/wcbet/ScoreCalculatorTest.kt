package wcbet

import wcbet.service.ScoreCalculator
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreCalculatorTest {

    @Test
    fun `0 - не угадал исход`() {
        assertEquals(0, ScoreCalculator.points(1, 0, 0, 1)) // ставил на хозяев, выиграли гости
        assertEquals(0, ScoreCalculator.points(0, 0, 2, 0)) // ставил ничью, победили хозяева
        assertEquals(0, ScoreCalculator.points(2, 1, 1, 1)) // ставил хозяев, ничья
    }

    @Test
    fun `1 - угадал победителя, но не разницу`() {
        assertEquals(1, ScoreCalculator.points(1, 0, 3, 1)) // разница 1, а вышло 2
        assertEquals(1, ScoreCalculator.points(0, 2, 1, 4)) // разница -2, а вышло -3
        assertEquals(1, ScoreCalculator.points(3, 0, 1, 0)) // разница 3, а вышло 1
    }

    @Test
    fun `2 - угадал победителя и разницу`() {
        assertEquals(2, ScoreCalculator.points(2, 0, 3, 1)) // разница 2 = 2, счёт другой
        assertEquals(2, ScoreCalculator.points(1, 0, 2, 1)) // разница 1 = 1, счёт другой
        assertEquals(2, ScoreCalculator.points(0, 1, 1, 2)) // гости, разница -1 = -1
    }

    @Test
    fun `3 - точный счёт победы`() {
        assertEquals(3, ScoreCalculator.points(2, 1, 2, 1))
        assertEquals(3, ScoreCalculator.points(0, 3, 0, 3))
    }

    @Test
    fun `2 - угадал ничью, но не счёт`() {
        assertEquals(2, ScoreCalculator.points(0, 0, 1, 1))
        assertEquals(2, ScoreCalculator.points(2, 2, 0, 0))
    }

    @Test
    fun `3 - точный счёт ничьей`() {
        assertEquals(3, ScoreCalculator.points(1, 1, 1, 1))
        assertEquals(3, ScoreCalculator.points(0, 0, 0, 0))
    }
}
