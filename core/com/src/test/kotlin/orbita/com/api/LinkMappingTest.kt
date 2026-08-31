// Г-01: пакет между проектами — сопоставление ссылок вместо отказа.
//
// Срез пакета из журнала несёт ссылки исходного проекта; изоляция режет их
// честно (ADR-022) и остаётся нетронутой. Задача — не ослабить границу, а
// снять с инженера ручную правку JSON: система показывает, чем заменить, и
// предлагает по совпадению формулировки.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinkMappingTest {

    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val mapper = ObjectMapper()

    private fun нужда(id: String, текст: String, проект: String) = boundary.ingest(
        CoreType.Need,
        """{"id":"$id","statement":"$текст",
            "stakeholder":{"name":"Оператор","role":"operator"},
            "lifecycle":{"status":"Draft","version":"1"}}""",
        "test", проект,
    )

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        listOf("PJ-1920" to "Источник", "PJ-1921" to "Приёмник").forEach { (id, имя) ->
            boundary.ingest(
                CoreType.Project,
                """{"id":"$id","name":"$имя","phase":"pre_phase_a","milestones":[{"gate":"MCR"}],
                    "lifecycle":{"status":"Draft","version":"1"}}""",
                "test", id,
            )
        }
        // исходный проект: нужда, на которую ссылается пакет
        нужда("ND-1920", "Перевозчик должен получать телеметрию груза в пути", "PJ-1920")
        нужда("ND-1921", "Водоканал должен получать телеметрию уровня в резервуарах", "PJ-1920")
        // целевой проект: та же по смыслу нужда — другими словами и другим id
        нужда("ND-1930", "Перевозчику нужна телеметрия груза в пути по всему маршруту", "PJ-1921")
        нужда("ND-1931", "Оператору нужен контроль качества сигнала на трассе", "PJ-1921")
    }

    private fun пакет(ссылка: String) = mapper.readTree(
        """[{"id":"SV-1920","name":"Телеметрия грузов","traces_up":["$ссылка"],
             "qos_profiles":[{"consumer_class":"A_prime","moe":[
               {"id":"MOE-1920","name":"service_availability",
                "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
             "lifecycle":{"status":"Draft","version":"1"}}]""",
    )

    @Test
    fun `чужая ссылка узнаётся и получает предложение по смыслу`() {
        val ссылки = LinkMapping.разобрать(boundary, пакет("ND-1920"), "PJ-1921")
        assertEquals(1, ссылки.size) { "ссылка чужого проекта обязана быть найдена" }
        val л = ссылки[0]
        assertEquals("PJ-1920", л.изПроекта) { "сказано, откуда ссылка" }
        assertTrue("телеметрию груза" in л.текст) { "показана формулировка исходного объекта" }
        assertEquals("ND-1930", л.предложение?.id) {
            "предложение — по совпадению формулировки, а не по порядку: ${л.кандидаты}"
        }
    }

    @Test
    fun `без смыслового совпадения предложения нет — молча не подставляем`() {
        val ссылки = LinkMapping.разобрать(boundary, пакет("ND-1921"), "PJ-1921")
        val л = ссылки.single()
        assertNull(л.предложение) {
            "нужда про водоканал не соответствует ни одной нужде приёмника: ${л.предложение}"
        }
        assertTrue(л.кандидаты.isNotEmpty()) { "кандидаты показываются — выбор за инженером" }
    }

    @Test
    fun `ссылка своего проекта разбором не трогается`() {
        val ссылки = LinkMapping.разобрать(boundary, пакет("ND-1930"), "PJ-1921")
        assertTrue(ссылки.isEmpty()) { "объект этого проекта — не чужая ссылка" }
    }

    @Test
    fun `подтверждённое сопоставление перебивает ссылку в пакете`() {
        val применено = LinkMapping.применить(пакет("ND-1920"), mapOf("ND-1920" to "ND-1930"))
        val трасса = применено[0].path("traces_up")[0].asText()
        assertEquals("ND-1930", трасса) { "ссылка заменена на объект целевого проекта" }
    }

    @Test
    fun `схожесть считает смысл, а не длину строки`() {
        val высокая = LinkMapping.схожесть(
            "Перевозчик должен получать телеметрию груза в пути",
            "Перевозчику нужна телеметрия груза в пути по маршруту",
        )
        val низкая = LinkMapping.схожесть(
            "Перевозчик должен получать телеметрию груза в пути",
            "Водоканал должен управлять задвижками насосных станций",
        )
        assertTrue(высокая > низкая) { "своё ближе чужого: $высокая против $низкая" }
        assertTrue(высокая >= 0.3) { "совпадение по существу обязано быть заметным: $высокая" }
    }
}
