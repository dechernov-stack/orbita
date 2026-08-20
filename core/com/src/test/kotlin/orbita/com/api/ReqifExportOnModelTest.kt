// Полезная нагрузка экспорта ReqIF на заполненной базе (TZ-OUT-005, шаг 11.2).
//
// XML и сверку с XSD OMG делает служба на библиотеке `reqif` — это проверяет
// tools/check_reqif_roundtrip.py в CI. Здесь проверяется то, что отдаёт ЯДРО:
// нагрузка полна (нужды и сервисы выгружены вместе с требованиями), связи
// не висят, идентификаторы устойчивы между сборками.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry
import orbita.out.ExchangeLink
import orbita.out.ModelSnapshot
import orbita.out.ReqifExport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReqifExportOnModelTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val exportedAt = "2026-08-20T12:00:00.000+00:00"

    @BeforeAll
    fun seed() {
        TestDb.truncateAll()
        DemoProject.seed(boundary)
    }

    private fun payload() = ReqifExport.payload(
        ModelSnapshot.of(boundary.objects, mapper),
        (boundary.links.list("trace") + boundary.links.list("derive"))
            .map { ExchangeLink(it.fromId, it.toId, it.kind) },
        exportedAt,
        mapper,
    )

    @Test
    @DisplayName("§11.2: нужды и сервисы выгружаются вместе с требованиями")
    fun `нагрузка несёт все три вида объектов`() {
        val p = payload()
        val byType = p.path("objects").groupBy { it.path("type").asText() }
        assertEquals(9, byType.getValue("ST-REQUIREMENT").size)
        assertEquals(3, byType.getValue("ST-NEED").size)
        assertEquals(2, byType.getValue("ST-SERVICE").size)
    }

    /**
     * Связь, у которой конец не существует в файле, семантически ломает файл
     * для принимающего инструмента — это находит `reqif validate`, и именно
     * так дефект и нашёлся.
     */
    @Test
    @DisplayName("§11.2: у каждой связи оба конца в файле")
    fun `связи не висят`() {
        val p = payload()
        val exported = p.path("objects").map { it.path("identifier").asText() }.toSet()
        val relations = p.path("relations").toList()
        assertTrue(relations.isNotEmpty(), "связи не выгрузились вовсе")
        relations.forEach { r ->
            assertTrue(r.path("source").asText() in exported) { "висит source: $r" }
            assertTrue(r.path("target").asText() in exported) { "висит target: $r" }
        }
    }

    @Test
    @DisplayName("§11.2: повторная сборка нагрузки идентична — дата входит в вход")
    fun `нагрузка воспроизводима`() =
        assertEquals(payload().toString(), payload().toString())

    @Test
    @DisplayName("§11.2: условие требования разложено, сериализованных структур нет")
    fun `условие разложено на атрибуты`() {
        val rq0100 = payload().path("objects")
            .first { it.path("values").path("ReqIF.ForeignID").asText() == "RQ-0100" }
        val values = rq0100.path("values")
        assertEquals("le", values.path("MeasureOperator").asText())
        assertEquals(100.0, values.path("MeasureValue").asDouble())
        assertEquals("kg", values.path("MeasureUnit").asText())
        values.properties().forEach { (name, v) ->
            assertTrue(!v.isTextual || !v.asText().trimStart().startsWith("{")) {
                "$name: сериализованная структура в строке"
            }
        }
    }

    @Test
    @DisplayName("§11.2: перечисления объявлены с наборами значений")
    fun `перечисления с наборами значений`() {
        val dt = payload().path("datatypes")
        assertEquals("enum", dt.path("MeasureOperator").path("kind").asText())
        assertTrue(dt.path("MeasureOperator").path("values").map { it.asText() }.contains("le"))
        assertEquals("enum", dt.path("Status").path("kind").asText())
    }
}
