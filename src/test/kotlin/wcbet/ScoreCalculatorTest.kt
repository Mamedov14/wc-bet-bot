package wcbet

import wcbet.service.ScoreCalculator
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreCalculatorTest {

    @Test
    fun `0 - не угадал победителя`() {
        assertEquals(0, ScoreCalculator.points(1, 0, 0, 1)) // ставил на хозяев, выиграли гости
        assertEquals(0, ScoreCalculator.points(0, 0, 2, 0)) // ставил ничью, победили хозяева
        assertEquals(0, ScoreCalculator.points(2, 1, 1, 1)) // ставил хозяев, ничья
    }

    @Test
    fun `1 - угадал победителя, но не счёт`() {
        assertEquals(1, ScoreCalculator.points(1, 0, 3, 1))
        assertEquals(1, ScoreCalculator.points(0, 2, 1, 4))
    }

    @Test
    fun `2 - угадал победителя и счёт`() {
        assertEquals(2, ScoreCalculator.points(2, 1, 2, 1))
        assertEquals(2, ScoreCalculator.points(0, 3, 0, 3))
    }

    @Test
    fun `2 - угадал ничью, но не счёт`() {
        assertEquals(2, ScoreCalculator.points(0, 0, 1, 1))
        assertEquals(2, ScoreCalculator.points(2, 2, 0, 0))
    }

    @Test
    fun `3 - ничья с точным счётом`() {
        assertEquals(3, ScoreCalculator.points(1, 1, 1, 1))
        assertEquals(3, ScoreCalculator.points(0, 0, 0, 0))
    }
}
