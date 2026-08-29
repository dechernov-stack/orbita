// Блокер прохода: пачка целей отклонялась на «единица 'КА' вне справочника» и
// «единица 'кг' вне справочника», хотя оба написания в справочнике ЕСТЬ —
// граница читала только ASCII-имена и написаний не видела.
//
// Мера: пачка с русскими написаниями проходит, значение ложится каноном, а в
// происхождении остаётся след «переведено из …». Неизвестное написание
// по-прежнему отклоняется с адресом полки — тихих строк мимо словаря нет.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

class UnitSpellingBoundaryTest {

    private val mapper = ObjectMapper()

    /** Справочник — тот же сид, что уходит на стенд. */
    private val index by lazy {
        val seed = mapper.readTree(
            Files.readString(
                RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/07-справочник-единиц.json"),
            ),
        )
        UnitRegistryIndex(seed.path("objects")[0])
    }

    @Test
    fun `русское написание массы принимается и приводится к канону`() {
        assertTrue(index.known("кг"))
        val (value, unit) = index.toCanon(100.0, "кг")!!
        assertEquals(100.0, value) { "написание канона значение не меняет" }
        assertEquals("kg", unit)
    }

    @Test
    fun `КА — счётная единица справочника, а не повод для отказа`() {
        assertTrue(index.known("КА")) { "«КА» обязана быть известна: она в справочнике данными" }
        val (value, unit) = index.toCanon(50.0, "КА")!!
        assertEquals(50.0, value)
        assertEquals("pcs", unit)
    }

    @Test
    fun `написание входной единицы пересчитывается её коэффициентом`() {
        val (value, unit) = index.toCanon(2.0, "тонн")!!
        assertEquals(2000.0, value)
        assertEquals("kg", unit)

        val (minutes, seconds) = index.toCanon(30.0, "мин")!!
        assertEquals(1800.0, minutes)
        assertEquals("s", seconds)
    }

    @Test
    fun `ASCII-имя канона остаётся без конверсии`() {
        assertNull(index.toCanon(5.0, "kg")) { "kg уже канон — трогать нечего" }
        assertNull(index.toCanon(3.0, "pcs"))
    }

    @Test
    fun `неизвестное написание по-прежнему отклоняется с адресом полки`() {
        val refusal = assertThrows<UnknownUnitException> { index.toCanon(1.0, "пудов") }
        assertTrue("пудов" in refusal.message!!)
        assertTrue("Справочник единиц" in refusal.message!!) { refusal.message!! }
    }

    @Test
    fun `нормализация пачки — значение каноном, написание в происхождении`() {
        val doc = mapper.readTree(
            """{"id":"MG-0008","kind":"goal","statement":"Аппараты 12U…100 кг",
                "moe":[{"id":"MOE-0001","name":"Масса аппарата",
                        "target":{"value":100,"unit":"кг"}}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
        )
        UnitBoundary.normalize(doc, index)
        val target = doc.path("moe")[0].path("target")
        assertEquals("kg", target.path("unit").asText())
        assertEquals(100.0, target.path("value").asDouble())
        assertTrue(
            target.path("provenance").path("converted_from").asText().contains("кг"),
        ) { "след конверсии обязан остаться: ${target.path("provenance")}" }
    }
}
