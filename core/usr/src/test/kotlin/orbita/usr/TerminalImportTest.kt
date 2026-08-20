// Импорт профиля терминала: отображение и тот же фильтр (шаг 14, ADR-024).
// Перенос эталона spec/import_semantics.py: разделы «Отображение внешней
// записи» и «Тот же фильтр, что для рукописного».
package orbita.usr

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.RepoPaths
import orbita.mod.model.ImportPolicy
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TerminalImportTest {

    private val mapper = ObjectMapper()
    private val policy = ImportPolicy()
    private val prov = policy.provenanceFor(
        "lorawan-devices", "2026-08-01", "2026-08-20", itemRef = "vendor/x/dev-a",
    )

    private val device = mapper.readTree(
        """{"name":"Sensor A","description":"датчик","battery":"AA"}""",
    )
    private val profile = mapper.readTree(
        """{"macVersion":"1.0.3","maxEIRP":14,"region":"EU863-870","supportsClassC":false}""",
    )

    private fun with(json: String) = mapper.readTree(json)

    @Nested
    @DisplayName("Отображение внешней записи")
    inner class Mapping {

        private val mapped = TerminalImport.mapTerminal(device, profile, prov, mapper)

        @Test
        fun `класс выведен из свойств профиля`() =
            assertEquals("A_prime", mapped.path("consumer_class").asText())

        @Test
        fun `регион отображён в наш перечень`() =
            assertEquals("EU868", mapped.path("regulatory_region").asText())

        @Test
        fun `радиопараметр перенесён`() =
            assertEquals(14.0, mapped.path("radio").path("eirp_dbm").asDouble())

        @Test
        fun `незнакомые поля источника сохранены`() {
            assertEquals("AA", mapped.path("source_extras").path("battery").asText())
            assertTrue(mapped.path("source_extras").has("description"))
        }

        @Test
        fun `класс C выводится из поддержки`() {
            val c = TerminalImport.mapTerminal(
                device,
                with("""{"macVersion":"1.0.3","maxEIRP":14,"region":"EU863-870","supportsClassC":true}"""),
                prov, mapper,
            )
            assertEquals("C_prime", c.path("consumer_class").asText())
        }

        /** Угаданный регион дал бы правдоподобный профиль с чужим частотным планом. */
        @Test
        fun `неизвестный регион не подставляется наугад`() {
            val x = TerminalImport.mapTerminal(
                device,
                with("""{"macVersion":"1.0.3","maxEIRP":14,"region":"XX999","supportsClassC":false}"""),
                prov, mapper,
            )
            assertTrue(x.path("regulatory_region").isNull)
            assertNull(x.path("regulatory_region").asText(null))
        }
    }

    @Nested
    @DisplayName("Тот же фильтр, что для рукописного")
    inner class SameFilter {

        private val registry = SchemaRegistry(RepoPaths.schemasDir())
        private val rules = TerminalRules(registry)

        @Test
        fun `корректная запись проходит фильтр`() {
            val mapped = TerminalImport.mapTerminal(device, profile, prov, mapper)
            assertEquals(emptyList<String>(), TerminalImport.screen(mapped))
        }

        @Test
        fun `запись из каталога с непонятым регионом отбраковывается`() {
            val x = TerminalImport.mapTerminal(
                device,
                with("""{"macVersion":"1.0.3","maxEIRP":14,"region":"XX999","supportsClassC":false}"""),
                prov, mapper,
            )
            assertTrue(TerminalImport.screen(x).any { "регион" in it })
        }

        @Test
        fun `запись без мощности отбраковывается`() {
            val x = TerminalImport.mapTerminal(
                device,
                with("""{"macVersion":"1.0.3","region":"EU863-870","supportsClassC":false}"""),
                prov, mapper,
            )
            assertTrue(TerminalImport.screen(x).any { "мощность" in it })
        }

        @Test
        fun `запись с неполным происхождением отбраковывается`() {
            val weak = TerminalImport.mapTerminal(
                device, profile,
                with("""{"source":"imported","import":{"dataset":"x"}}"""), mapper,
            )
            assertTrue(TerminalImport.screen(weak).any { "не указано" in it })
        }

        /**
         * ПОСЛАБЛЕНИЙ ДЛЯ ИМПОРТА НЕТ: дополненный до полного профиль идёт
         * через ТЕ ЖЕ TerminalRules, что рукописный, — тот же объект правил,
         * а не копия с ослабленной проверкой. Обход кода из готовности шага:
         * профиль с изъяном, который не пропустили бы у рукописного,
         * не проходит и у импортированного.
         */
        @Test
        fun `дополненный профиль проходит те же правила, что рукописный`() {
            val completed = (
                TerminalImport.mapTerminal(device, profile, prov, mapper).deepCopy() as ObjectNode
                ).apply {
                put("id", "TP-0002")
                putObject("generation")
                    .put("model", "periodic").put("rate_per_day", 4).put("payload_bytes", 24)
                putObject("ephemeris")
                    .put("knows_ephemeris", true).put("max_almanac_age_s", 86400)
                    .put("degraded_rate_factor", 0.2)
                remove("source_extras")
                remove("name")
                putObject("radio").put("eirp_dbm", 14).put("rx_sensitivity_dbm", -137)
            }
            assertEquals(emptyList<Any>(), rules.validate(completed)) {
                rules.validate(completed).toString()
            }

            // тот же профиль с классом C′ без контура управления режет
            // ТА ЖЕ функция правил, что рукописный ввод
            val broken: ObjectNode = completed.deepCopy()
            broken.put("consumer_class", "C_prime")
            assertTrue(rules.validate(broken).any { "required_reaction_time_s" in it.message })
        }
    }
}
