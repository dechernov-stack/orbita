// Отображение модели в ReqIF (TZ-OUT-005, шаг 11.2, ADR-023).
// Перенос эталона spec/reqif_semantics.py один в один: 26 проверок.
package orbita.out

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReqifMappingTest {

    private val mapper = ObjectMapper()

    private val req = mapper.readTree(
        """{"id":"RQ-0100","level":"system","category":"performance",
            "statement":"Сухая масса КА не должна превышать 100 кг.",
            "rationale":"Ограничение средства выведения",
            "mop":{"name":"Сухая масса","operator":"le",
                   "value":{"value":100,"unit":"kg"},"rollup":"sum"},
            "lifecycle":{"status":"Baseline","version":"3"},
            "owner":"вед. системный инженер","custom_field":"значение заказчика"}""",
    )

    @Nested
    @DisplayName("Отображение типов")
    inner class Types {

        @Test
        fun `отображение без замечаний`() =
            assertEquals(emptyList<String>(), mappingIssues())

        @Test
        fun `оператор условия — перечисление, не строка`() =
            assertEquals(
                REQIF_DATATYPES.getValue("enum"),
                datatypeDefinitions().getValue("MeasureOperator").type,
            )

        @Test
        fun `набор значений оператора задан`() =
            assertEquals(
                REQIF_ENUM_VALUES.getValue("MeasureOperator").toSet(),
                datatypeDefinitions().getValue("MeasureOperator").values!!.toSet(),
            )

        @Test
        fun `значение показателя — число, не строка`() =
            assertEquals(
                REQIF_DATATYPES.getValue("real"),
                datatypeDefinitions().getValue("MeasureValue").type,
            )

        @Test
        fun `формулировка — размеченный текст`() =
            assertEquals(
                REQIF_DATATYPES.getValue("xhtml"),
                datatypeDefinitions().getValue("ReqIF.Text").type,
            )

        @Test
        fun `перечислимое поле строкой выявлено`() {
            val bad = mappingIssues(listOf(ReqifField("mop.operator", "MeasureOperator", "string")))
            assertTrue(bad.any { "фильтрация" in it }) { bad.toString() }
        }

        @Test
        fun `у каждого вида объекта свой тип`() =
            assertEquals(SPEC_OBJECT_TYPES.size, SPEC_OBJECT_TYPES.values.toSet().size)
    }

    @Nested
    @DisplayName("Составное поле не сворачивается в строку")
    inner class Composite {

        private val so = toSpecObject(req)

        @Test
        fun `условие разложено на три атрибута`() =
            assertTrue(so.values.keys.containsAll(listOf("MeasureOperator", "MeasureValue", "MeasureUnit")))

        @Test
        fun `оператор доступен отдельным значением`() =
            assertEquals("le", so.values.getValue("MeasureOperator").asText())

        @Test
        fun `единица доступна отдельным значением`() =
            assertEquals("kg", so.values.getValue("MeasureUnit").asText())

        @Test
        fun `сериализованных структур в значениях нет`() =
            assertEquals(emptyList<String>(), flattenedAsString(so))

        @Test
        fun `сериализованная структура выявляется`() {
            val bad = SpecObject(
                "SO-x", "ST-REQUIREMENT",
                mapOf("Measure" to mapper.readTree("\"{\\\"operator\\\":\\\"le\\\",\\\"value\\\":100}\"")),
            )
            assertEquals(listOf("Measure"), flattenedAsString(bad))
        }
    }

    @Nested
    @DisplayName("Устойчивость идентификаторов")
    inner class Identifiers {

        private val so = toSpecObject(req)

        @Test
        fun `один объект — один идентификатор`() =
            assertEquals(so.identifier, toSpecObject(req).identifier)

        /** Правка статуса — не новый объект: история у принимающего сохраняется. */
        @Test
        fun `изменение содержимого идентификатор не меняет`() {
            val changed = (req.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode)
                .set<com.fasterxml.jackson.databind.node.ObjectNode>(
                    "lifecycle",
                    mapper.readTree("""{"status":"Draft","version":"4"}"""),
                )
            assertEquals(so.identifier, toSpecObject(changed).identifier)
        }

        @Test
        fun `другой объект — другой идентификатор`() {
            val other = (req.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode)
                .put("id", "RQ-0101")
            assertTrue(toSpecObject(other).identifier != so.identifier)
        }

        @Test
        fun `идентификатор связи устойчив`() {
            val link = listOf(ExchangeLink("A", "B", "trace"))
            assertEquals(
                toSpecRelations(link).first().identifier,
                toSpecRelations(link).first().identifier,
            )
        }
    }

    @Nested
    @DisplayName("Связи")
    inner class Relations {

        private val rels = toSpecRelations(
            listOf(
                ExchangeLink("RQ-0100", "RQ-0101", "derive"),
                ExchangeLink("ND-0001", "RQ-0100", "trace"),
            ),
        )

        @Test
        fun `виды связей различаются типами`() =
            assertEquals(setOf("RT-DERIVE", "RT-TRACE"), rels.map { it.type }.toSet())

        @Test
        fun `концы связи ссылаются на идентификаторы объектов`() =
            assertEquals(reqifIdentifier("SO", "RQ-0100"), rels.first().source)

        @Test
        fun `неотображённый вид связи отклонён`() {
            assertThrows<UnmappedLinkKindException> {
                toSpecRelations(listOf(ExchangeLink("A", "B", "что-то")))
            }
        }
    }

    @Nested
    @DisplayName("Круговой обмен")
    inner class RoundTrip {

        private val so = toSpecObject(req)
        private val back = fromSpecObject(so, mapper = mapper)

        @Test
        fun `идентификатор сохраняется`() =
            assertEquals("RQ-0100", back.path("id").asText())

        @Test
        fun `оператор условия сохраняется`() =
            assertEquals("le", back.path("mop").path("operator").asText())

        @Test
        fun `значение и единица сохраняются`() {
            assertEquals(100.0, back.path("mop").path("value").path("value").asDouble())
            assertEquals("kg", back.path("mop").path("value").path("unit").asText())
        }

        @Test
        fun `статус сохраняется`() =
            assertEquals("Baseline", back.path("lifecycle").path("status").asText())

        @Test
        fun `незнакомое поле не теряется`() =
            assertEquals("значение заказчика", back.path("custom_field").asText())

        @Test
        fun `формулировка сохраняется`() =
            assertEquals(req.path("statement").asText(), back.path("statement").asText())

        @Test
        fun `повторный проход ничего не меняет`() =
            assertEquals(back, fromSpecObject(toSpecObject(back), mapper = mapper))
    }
}
