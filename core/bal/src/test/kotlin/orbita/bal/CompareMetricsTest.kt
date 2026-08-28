// МВП-М2 (ЗАДАЧА-CODE-СРАВНЕНИЕ §4): меры приёмки путём данных.
// Ревизит — перцентилем (среднее на хвосте обмануло бы); 52°-Walker против
// смеси с ССО различимы ТАБЛИЦЕЙ (полярная ёмкость, латентность севера,
// энергорежим, партии) — без единого балла; деградация отлична между
// вариантами; коэффициенты — данными (правка файла без пересборки).
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.abs

class CompareMetricsTest {

    private val mapper = ObjectMapper()
    private val metrics = CompareMetrics(VisibilityPrecompute())

    @Test
    fun `ревизит перцентилем - среднее на хвосте обмануло бы`() {
        // десять коротких ревизитов и один хвост: типовое «в среднем норм»
        val revisits = List(10) { 60.0 } + listOf(3600.0)
        val mean = revisits.average()
        val p75 = percentile(revisits, 75.0)!!
        // порог «не реже 5 минут»: среднее (382с) порог валит, перцентиль —
        // честные 60с типового случая; хвост виден отдельной метрикой max gap
        assertTrue(mean > 300.0) { "среднее: $mean" }
        assertEquals(60.0, p75)
        // перцентиль монотонен и до хвоста дотягивается сотым
        assertEquals(3600.0, percentile(revisits, 100.0))
    }

    private fun walker(): com.fasterxml.jackson.databind.JsonNode = mapper.readTree(
        """{"id":"CN-9101","name":"Walker 52°","kind":"composite","subgroups":[
             {"name":"Наклонная","kind":"walker_delta","planes":4,"per_plane":2,
              "altitude_km":600,"inclination_deg":52,"phasing":1}]}""",
    )

    private fun mix(): com.fasterxml.jackson.databind.JsonNode = mapper.readTree(
        """{"id":"CN-9102","name":"Смесь 52°+ССО","kind":"composite","subgroups":[
             {"name":"Наклонная","kind":"walker_delta","planes":2,"per_plane":2,
              "altitude_km":600,"inclination_deg":52,"phasing":1},
             {"name":"Полярная","kind":"sso","planes":2,"per_plane":2,
              "altitude_km":600,"ltan_h":6.0}]}""",
    )

    private fun cells() = listOf(
        CompareMetrics.DemandCell(
            GridPoint("south", 45.0, 40.0), mapOf("A_prime" to (100.0 to 1000.0)),
        ),
        CompareMetrics.DemandCell(
            GridPoint("north", 72.0, 100.0), mapOf("C_prime" to (20.0 to 200.0)),
        ),
    )

    private fun stations() = listOf(
        GridPoint("MSK", 55.9, 37.6),
        GridPoint("MUR", 68.9, 33.1),
    )

    @Test
    fun `мера документа - Walker против смеси различимы таблицей, без балла`() {
        val day = 86400.0
        val epoch = "2026-03-20T00:00:00.000Z"
        val w = metrics.evaluate("CN-9101", "Walker 52°", walker(), cells(), stations(), epoch, day)
        val m = metrics.evaluate("CN-9102", "Смесь 52°+ССО", mix(), cells(), stations(), epoch, day)

        // полярная ёмкость и покрытие севера — за смесью
        val wNorth = w.path("service").path("C_prime")
        val mNorth = m.path("service").path("C_prime")
        assertTrue(
            mNorth.path("coverage_share").asDouble() > wNorth.path("coverage_share").asDouble(),
        ) { "север: walker=${wNorth.toPrettyString()} mix=${mNorth.toPrettyString()}" }
        assertTrue(
            mNorth.path("latency_s").asDouble() < wNorth.path("latency_s").asDouble(),
        ) { "латентность севера: walker=${wNorth.path("latency_s")} mix=${mNorth.path("latency_s")}" }

        // энергорежим: терминаторная ССО (LTAN 6:00) почти без тени,
        // наклонная — затмения к трети витка; видно колонкой Г
        fun shadows(v: com.fasterxml.jackson.databind.JsonNode) =
            v.path("orbit_proxy").path("power_regime")
                .associate { it.path("name").asText() to it.path("worst_shadow_share").asDouble() }
        val mixShadows = shadows(m)
        assertTrue(mixShadows.getValue("Полярная") < 0.10) { "ССО тень: $mixShadows" }
        assertTrue(mixShadows.getValue("Наклонная") > 0.25) { "наклонная тень: $mixShadows" }
        assertTrue(m.path("orbit_proxy").path("proxy").asBoolean()) { "пометка «прокси» обязана быть" }

        // пусковые партии: смесь проигрывает (двe несовместимые против одной)
        assertEquals(1, w.path("logistics").path("launch_batches").asInt())
        assertEquals(2, m.path("logistics").path("launch_batches").asInt())

        // деградация при минус-одном КА посчитана и отлична между вариантами
        val dw = w.path("resilience").path("degradation_dmax_gap_s").asDouble()
        val dm = m.path("resilience").path("degradation_dmax_gap_s").asDouble()
        assertTrue(abs(dw - dm) > 1.0) { "деградация неразличима: $dw vs $dm" }
    }

    @Test
    fun `коэффициенты - данными, правка файла без пересборки`() {
        val dir = Files.createTempDirectory("cmp-cfg")
        val prev = System.getenv("ORBITA_FILES_DIR")
        // конфиг читается из ORBITA_FILES_DIR; в тестах он задан окружением —
        // подменяем файлом во временном каталоге через отражение окружения
        // невозможно, поэтому мера: файл в НАСТОЯЩЕМ ORBITA_FILES_DIR
        val filesDir = prev?.let { java.nio.file.Path.of(it) } ?: dir
        Files.createDirectories(filesDir)
        val override = filesDir.resolve("compare-config.json")
        val base = metrics.config()
        try {
            val custom = (base.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode)
            (custom.path("cost") as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("platform_unit", 100.0)
            Files.writeString(override, mapper.writeValueAsString(custom))
            assertEquals(100.0, metrics.config().path("cost").path("platform_unit").asDouble())
        } finally {
            Files.deleteIfExists(override)
        }
        // после удаления файла — снова дефолт из ресурсов
        assertEquals(
            base.path("cost").path("platform_unit").asDouble(),
            metrics.config().path("cost").path("platform_unit").asDouble(),
        )
    }
}
