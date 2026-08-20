// Импорт внешних данных: правовой режим, происхождение, повторный импорт
// (шаг 14, ADR-024). Перенос эталона spec/import_semantics.py: разделы
// «Правовые условия источника», «Происхождение», «Повторный импорт».
package orbita.mod.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ImportPolicyTest {

    private val mapper = ObjectMapper()
    private val policy = ImportPolicy()

    @Nested
    @DisplayName("Правовые условия источника")
    inner class Legal {

        @Test
        fun `массовая выгрузка защищённого каталога запрещена`() {
            val v = policy.importAllowed("lorawan-devices", "bulk")
            assertFalse(v.allowed)
        }

        @Test
        fun `причина названа условиями источника`() =
            assertTrue("sui generis" in policy.importAllowed("lorawan-devices", "bulk").reason!!)

        @Test
        fun `извлечение отдельной записи разрешено`() =
            assertTrue(policy.importAllowed("lorawan-devices", "item").allowed)

        @Test
        fun `открытый источник допускает массовую выгрузку`() =
            assertTrue(policy.importAllowed("natural-earth", "bulk").allowed)

        @Test
        fun `неописанный источник отклонён`() =
            assertFalse(policy.importAllowed("unknown-catalog", "item").allowed)

        @Test
        fun `отсутствие описания трактуется как запрет, а не разрешение`() =
            assertTrue("неизвестен" in policy.importAllowed("unknown-catalog", "item").reason!!)
    }

    @Nested
    @DisplayName("Происхождение импортированного")
    inner class Provenance {

        private val prov = policy.provenanceFor(
            "lorawan-devices", "2026-08-01", "2026-08-20", itemRef = "vendor/x/dev-a",
        )

        @Test
        fun `происхождение полно`() =
            assertEquals(emptyList<String>(), provenanceIssues(prov))

        @Test
        fun `условия использования зафиксированы`() =
            assertTrue("sui generis" in prov.path("import").path("terms").asText())

        @Test
        fun `признак массовой выгрузки перенесён`() =
            assertFalse(prov.path("import").path("bulk_allowed").asBoolean(true))

        @Test
        fun `версия отображения зафиксирована`() =
            assertEquals("1", prov.path("import").path("mapping_version").asText())

        @Test
        fun `неполное происхождение выявлено`() {
            val bare = mapper.readTree("""{"source":"imported","import":{"dataset":"x"}}""")
            assertEquals(3, provenanceIssues(bare).size)
        }

        @Test
        fun `ручной ввод происхождения импорта не требует`() =
            assertEquals(emptyList<String>(), provenanceIssues(mapper.readTree("""{"source":"manual"}""")))

        @Test
        fun `источник вне перечня отклонён`() {
            assertThrows<IllegalArgumentException> {
                policy.provenanceFor("unknown", "1", "2026-08-20")
            }
        }
    }

    @Nested
    @DisplayName("Повторный импорт")
    inner class Merge {

        private fun record(itemRef: String, eirp: Double): ObjectNode {
            val n = mapper.createObjectNode()
            n.put("name", "Sensor A")
            n.putObject("radio").put("eirp_dbm", eirp)
            n.set<ObjectNode>(
                "provenance",
                policy.provenanceFor("lorawan-devices", "2026-08-01", "2026-08-20", itemRef),
            )
            return n
        }

        @Test
        fun `первый импорт добавляет запись`() {
            val (store, action) = mergeImported(emptyList(), record("vendor/x/dev-a", 14.0))
            assertEquals(MergeAction.Added, action)
            assertEquals(1, store.size)
        }

        @Test
        fun `повторный импорт обновляет, а не дублирует`() {
            val (store, _) = mergeImported(emptyList(), record("vendor/x/dev-a", 14.0))
            val (updated, action) = mergeImported(store, record("vendor/x/dev-a", 16.0))
            assertEquals(MergeAction.Updated, action)
            assertEquals(1, updated.size)
        }

        @Test
        fun `обновлённое значение применилось`() {
            val (store, _) = mergeImported(emptyList(), record("vendor/x/dev-a", 14.0))
            val (updated, _) = mergeImported(store, record("vendor/x/dev-a", 16.0))
            assertEquals(16.0, updated[0].path("radio").path("eirp_dbm").asDouble())
        }

        /** Инженер правил осознанно: обновление источника не затирает его правку. */
        @Test
        fun `ручная правка поверх импорта не затирается`() {
            val edited = record("vendor/x/dev-a", 10.0)
            edited.putObject("_edited").put("radio", true)
            val (updated, action) = mergeImported(listOf(edited), record("vendor/x/dev-a", 16.0))
            assertEquals(MergeAction.Updated, action)
            assertEquals(10.0, updated[0].path("radio").path("eirp_dbm").asDouble())
        }

        @Test
        fun `другая запись источника добавляется отдельно`() {
            val (store, _) = mergeImported(emptyList(), record("vendor/x/dev-a", 14.0))
            val (two, action) = mergeImported(store, record("vendor/x/dev-b", 14.0))
            assertEquals(MergeAction.Added, action)
            assertEquals(2, two.size)
        }
    }
}
