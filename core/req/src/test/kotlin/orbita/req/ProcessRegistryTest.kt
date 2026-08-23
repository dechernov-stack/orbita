// Реестры процесса (задача «реестры процесса», подготовка к П2): точки
// gates.json и операции operations.json согласованы между собой и с
// регламентами §5 — до появления экранов у реестров уже есть потребитель.
//
// Числа операций не зашиты константами: диапазон считается по самим кодам
// файла — пропуск или дубль внутри диапазона виден, а «нужное» число,
// переписанное в тест, разошлось бы с файлом молча.
package orbita.req

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProcessRegistryTest {

    private val mapper = ObjectMapper()
    private val gates = Gates()
    private val operations: List<JsonNode> =
        ProcessRegistryTest::class.java.getResourceAsStream("/orbita/req/operations.json")!!
            .use { mapper.readTree(it) }["operations"].toList()

    private fun ofPhase(phase: String) = operations.filter { it["phase"].asText() == phase }

    /** Числовая часть кода «О7» → 7. */
    private fun num(code: String): Int {
        assertTrue(code.startsWith("О")) { "код операции начинается с «О»: $code" }
        return code.removePrefix("О").toInt()
    }

    @Test
    fun `обе фазы покрыты без пропусков и дублей`() {
        for (phase in listOf("pre_phase_a", "phase_a")) {
            val nums = ofPhase(phase).map { num(it["code"].asText()) }
            assertTrue(nums.isNotEmpty()) { "фаза $phase пуста" }
            // диапазон — по файлу: от О1 до максимального кода, сплошняком
            assertEquals((1..nums.max()).toList(), nums.sorted()) { "фаза $phase: $nums" }
            assertEquals(nums.size, nums.toSet().size) { "дубли кодов в $phase" }
        }
    }

    @Test
    fun `каждая точка операций существует в реестре точек`() {
        val known = gates.gateNames
        for (op in operations) {
            val gate = op["gate"]
            if (gate.isNull) continue
            assertTrue(gate.asText() in known) {
                "${op["phase"].asText()}/${op["code"].asText()}: точка «${gate.asText()}» не описана в gates.json (${known})"
            }
        }
    }

    @Test
    fun `каждый Д-код — ровно у одной операции своей фазы`() {
        for (phase in listOf("pre_phase_a", "phase_a")) {
            val docs = ofPhase(phase).flatMap { op ->
                val d = op["output"]["doc"]
                when {
                    d.isNull -> emptyList()
                    d.isArray -> d.map { it.asText() }
                    else -> listOf(d.asText())
                }
            }
            val dupes = docs.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(dupes.isEmpty()) { "фаза $phase: Д-коды у нескольких операций: $dupes" }
        }
    }

    @Test
    fun `требуемые статусы — из статусной модели`() {
        for (op in operations) {
            val s = op["required_status"]
            if (s.isNull) continue
            assertTrue(s.asText() in Gates.ORDER) {
                "${op["phase"].asText()}/${op["code"].asText()}: статус «${s.asText()}» вне модели ${Gates.ORDER}"
            }
        }
    }

    @Test
    fun `входы-ссылки указывают на операции своей фазы`() {
        for (phase in listOf("pre_phase_a", "phase_a")) {
            val codes = ofPhase(phase).map { it["code"].asText() }.toSet()
            for (op in ofPhase(phase)) {
                for (ref in op["inputs"]) {
                    assertTrue(ref.asText() in codes) {
                        "$phase/${op["code"].asText()}: вход «${ref.asText()}» не существует"
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("реестр точек: шесть точек, статусы из модели, планка не смягчается")
    fun gatesRegistry() {
        val order = listOf("internal_review", "MCR", "KDP-A", "SRR", "SDR", "KDP-B")
        assertEquals(order.toSet(), gates.gateNames)
        // требования читаются из файла напрямую: через readiness планка Draft
        // неотличима от отсутствия требования
        val byGate = order.associateWith { g -> Gates.load()[g]!! }
        for ((g, req) in byGate) for ((kind, status) in req) {
            assertTrue(status in Gates.ORDER) { "$g/$kind: статус «$status» вне модели ${Gates.ORDER}" }
        }
        // по каждому виду планка от точки к точке не опускается
        val kinds = byGate.values.flatMap { it.keys }.toSet()
        for (kind in kinds) {
            var floor = 0
            for (g in order) {
                val required = byGate[g]!![kind] ?: continue
                val rank = Gates.ORDER.indexOf(required)
                assertTrue(rank >= floor) { "$g/$kind: планка «$required» ниже предыдущей точки" }
                floor = rank
            }
        }
    }
}
