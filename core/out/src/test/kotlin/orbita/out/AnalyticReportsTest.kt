// Аналитические отчёты (TZ-OUT-002, STEP-6 §2.2).
//
// Главное свойство — пустой отчёт отличается от неисполненного. Без этого
// различия «нарушений не найдено» и «проверка не запускалась» выглядят
// одинаково, и второе читается как первое.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// Отчёта «TPM за пределами резервов» здесь больше нет (Шаг 16 §2.1): выход за
// резерв помечает TpmRegistry.breached (TZ-KA-010), второй вход удалён.
class AnalyticReportsTest {

    private val mapper = ObjectMapper()

    @Test
    @DisplayName("TZ-OUT-002: пустой отчёт отличается от неисполненного")
    fun `пустой отчёт отличается от неисполненного`() {
        val notRun = AnalyticReport.notExecuted<BottleneckEntry>("bottlenecks")
        val clean = AnalyticReport.of<BottleneckEntry>("bottlenecks", emptyList())

        assertFalse(notRun.executed)
        assertFalse(notRun.empty, "неисполненный отчёт не является пустым")
        assertTrue(clean.executed)
        assertTrue(clean.empty, "исполненный отчёт без находок пуст")
        // и это два разных состояния, а не одно
        assertTrue(notRun.entries == clean.entries && notRun.empty != clean.empty)
    }

    @Test
    @DisplayName("TZ-FLW-007 / TZ-OUT-002: узкие места читаются из результатов прогона")
    fun `узкие места берутся из прогона, а не пересчитываются`() {
        val results = mapper.readTree(
            """[{"scenario_ref":"SC-0001","bottlenecks":[
                  {"location":"user_uplink","utilization":0.42},
                  {"location":"onboard_buffer","utilization":1.31}]},
                {"scenario_ref":"SC-0002","bottlenecks":[
                  {"location":"feeder_downlink","utilization":0.77}]}]""",
        ).toList()
        val report = bottlenecks(results)
        assertEquals(3, report.entries.size)
        // самое загруженное — первым: отчёт отвечает на вопрос «что определяет систему»
        assertEquals("onboard_buffer", report.entries[0].location)
        assertEquals("SC-0001", report.entries[0].scenarioRef)
        assertTrue(report.entries[0].utilization > 1.0)
    }

    @Test
    @DisplayName("TZ-OUT-002: прогонов нет — отчёт исполнен и пуст")
    fun `без прогонов отчёт исполнен и пуст`() {
        val report = bottlenecks(emptyList())
        assertTrue(report.executed)
        assertTrue(report.empty)
    }
}
