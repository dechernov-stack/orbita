// Ф-06: библиотека запрашивает данные. Меры владельца: в пустом проекте
// «Модель аппарата» показывает запросы формами; приложенный даташит
// предзаполняет чувствительность и частоты с якорями блоков; незаполненная
// масса — разрыв в готовности.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.ObjectStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataRequestsTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val requests by lazy { DataRequests(boundary) }

    @TempDir
    lateinit var files: Path

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2001","name":"Пустой проект","phase":"pre_phase_a","mission_class":"MC-9001",
                "milestones":[{"gate":"MCR"}],"lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2001",
        )
        boundary.ingest(
            CoreType.Glossary,
            """{"id":"GL-9001","name":"Глоссарий",
                "entries":[{"term":"Зона видимости","brief":"Геометрический footprint: угол места не ниже минимального."}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", ObjectStore.LIBRARY_PROJECT,
        )
        // анкеты — из того же сида, что уходит на стенд, а не из кода теста
        val seed = mapper.readTree(
            Files.readString(
                RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/09-анкеты-характеристик.json"),
            ),
        )
        seed.path("objects").forEach { form ->
            boundary.ingest(
                CoreType.PropertyForm, mapper.writeValueAsString(form), "test", ObjectStore.LIBRARY_PROJECT,
            )
        }
    }

    @Test
    fun `в пустом проекте библиотека спрашивает данные формами по ролям`() {
        val list = requests.of("PJ-2001")
        assertEquals(
            listOf("platform", "payload", "terminal", "ground_station"),
            list.map { it.role },
        ) { "анкеты приходят по ролям носителей" }

        val platform = list.first { it.role == "platform" }
        assertTrue(platform.fields.any { it.key == "dry_mass" && it.required && it.unit == "kg" })
        assertTrue(platform.fields.all { !it.filled }) { "в пустом проекте заполненных полей нет" }
        assertTrue(platform.fields.any { it.required })

        // поле несёт единицу справочника и подсказку — форма объясняет себя
        val life = platform.fields.first { it.key == "design_life" }
        assertEquals("a", life.unit)
        assertTrue(life.hint != null && life.hint!!.isNotBlank())
    }

    @Test
    fun `подсказка поля берётся из глоссария, если анкета назвала терм`() {
        val station = requests.of("PJ-2001").first { it.role == "ground_station" }
        val elevation = station.fields.first { it.key == "min_elevation" }
        assertEquals("Геометрический footprint: угол места не ниже минимального.", elevation.hint) {
            "подсказка обязана приходить из глоссария (Ф-03), а не из кода формы"
        }
    }

    @Test
    fun `заполненное в модели поле спрошенным больше не считается`() {
        // ADR-044: анкета платформы закрывается параметрами узла «Платформа»
        // дерева состава — модель аппарата раскладывается в узлы тем же
        // разложением, что миграция
        DemoProject.seedCarrierTree(
            boundary,
            """{"id":"SP-2001","preset":"cubesat_16u",
                "platform":{"dry_mass_kg":42.0,
                  "power":{"sa_area_m2":0.3,"sa_efficiency":0.28,"battery_wh":120},
                  "attitude":{"pointing_accuracy_deg":1.5},"design_life_years":5},
                "payload":{"architecture":"regenerative",
                  "links":[{"id":"RL-UP","role":"user_uplink","band_hz":868000000,"tx_power_w":0.1,
                            "g_over_t_db_k":-18,"required_margin_db":3,
                            "antenna":{"type":"patch","gain_dbi":6}}],
                  "onboard":{"buffer_mb":64,"priority_policy":["C_prime","B_prime","A_prime"]}},
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "PJ-2001", parent = null, usage = false,
        )
        val platform = requests.of("PJ-2001").first { it.role == "platform" }
        val dry = platform.fields.first { it.key == "dry_mass" }
        assertTrue(dry.filled) { "масса задана в модели — значит спрашивать её незачем" }
        assertEquals("model", dry.from)
        assertTrue(platform.missing.none { it.key == "dry_mass" })
        // остальные поля платформы зреют к своим точкам: к MCR они
        // приглашение, а не разрыв (Ф-06 п.5)
        assertTrue(platform.invited.any { it.key == "form_factor" })
    }

    @Test
    fun `даташит предзаполняет поля анкеты с координатой блока`() {
        boundary.ingest(
            CoreType.SourceDocument,
            """{"id":"SD-2001","name":"Даташит приёмника","kind":"datasheet",
                "org":"поставщик модуля","rights":"внутренний документ проекта",
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2001",
        )
        DocumentHarvest.store(
            files.toString(), "SD-2001", "fp-1",
            mapper.readTree(
                """
                {"kind":"document_semantic_parse","source_document":"SD-2001","items":[
                  {"class":"property","form_field":"sensitivity","name":"Чувствительность",
                   "measure":{"value":-148,"unit":"дБм"},
                   "canonical":{"value":-148,"unit":"dBm"},"block":["t1#3"]},
                  {"class":"property","form_field":"frequency_range","name":"Диапазон частот",
                   "range":{"min":150,"max":960,"unit":"МГц"},
                   "canonical":{"min":150,"max":960,"unit":"MHz"},"block":["b4"]}
                ]}
                """.trimIndent(),
            ),
        )

        val payload = withFilesDir { requests.of("PJ-2001").first { it.role == "payload" } }
        val sensitivity = payload.fields.first { it.key == "sensitivity" }
        assertTrue(sensitivity.filled) { "даташит предзаполнил чувствительность" }
        assertTrue(sensitivity.value!!.contains("-148")) { sensitivity.value!! }
        assertTrue(sensitivity.from!!.startsWith("harvest:SD-2001")) { sensitivity.from!! }
        assertTrue(sensitivity.from!!.contains("t1#3")) { "координата блока обязана остаться при значении" }

        val freq = payload.fields.first { it.key == "frequency_range" }
        assertTrue(freq.filled && freq.from!!.contains("b4")) { "${freq.from}" }
    }

    @Test
    fun `в Pre-A анкеты железа приглашают, а не горят разрывом`() {
        // ближайшая точка проекта — MCR: к ней требуется концептуальное
        // (диапазон частот, класс потребителя), а не масса платформы
        val platform = requests.of("PJ-2001").first { it.role == "platform" }
        assertTrue(platform.missing.isEmpty()) {
            "к MCR железо не требуется: ${platform.missing.map { it.name }}"
        }
        assertTrue(platform.invited.isNotEmpty()) { "поля обязаны быть видны приглашением" }

        val payload = requests.of("PJ-2001").first { it.role == "payload" }
        val freq = payload.fields.first { it.key == "frequency_range" }
        assertEquals("MCR", freq.requiredBy)
        assertTrue(freq.dueNow) { "диапазон частот нужен уже к MCR — без него нет концепции связи" }
        assertTrue(payload.missing.any { it.key == "frequency_range" })
        assertTrue(payload.missing.none { it.key == "mass" }) { "масса ПН зреет к SDR" }
    }

    @Test
    fun `разрыв готовности — только по полям ближайшей точки`() {
        val checks = boundary.gatePassing.readiness("MCR", "PJ-2001")
        val gap = checks.first { it.id == "data_requests" }
        assertEquals("open", gap.state)
        assertEquals("spacecraft", gap.place)
        assertTrue("Диапазон частот" in gap.note) { gap.note }
        assertTrue("Масса сухая" !in gap.note) { "поле SDR к MCR разрывом не считается: ${gap.note}" }
        assertTrue(!gap.blocking) { "запрос данных — предупреждение фазы, а не блокировка ворот" }
    }

    /** Хранилище урожая ищет файлы по ORBITA_FILES_DIR — подменяем на время теста. */
    private fun <T> withFilesDir(block: () -> T): T {
        val previous = System.getProperty("orbita.test.filesDir")
        return try {
            System.setProperty("orbita.test.filesDir", files.toString())
            block()
        } finally {
            if (previous == null) System.clearProperty("orbita.test.filesDir")
            else System.setProperty("orbita.test.filesDir", previous)
        }
    }

    /**
     * Ф-06 путь 3 (сверка владельца по репозиторию): механика чтения значений
     * из даташита была, а ВХОДА для неё не было — служба не знала ключей полей
     * анкеты и метить характеристику ей было нечем. Перечень полей обязан
     * попадать во вход разбора.
     */
    @Test
    fun `вход разбора несёт ключи полей анкет — иначе даташит нечем метить`() {
        val forms = boundary.objects.listCurrent(ObjectStore.LIBRARY_PROJECT)
            .filter { it.type == "property_form" }
        assertTrue(forms.isNotEmpty()) { "фикстура обязана иметь анкету" }
        val card = ObjectMapper().readTree("""{"name":"Даташит приёмника","kind":"datasheet"}""")
        val statement = DocumentHarvest.statementOf(card, "SD-2001", "# канон", null, forms)
        assertTrue("form_field" in statement) { "перечень полей обязан быть назван ключом: $statement" }
        assertTrue("sensitivity" in statement) { "ключ поля обязан дойти до службы: $statement" }
        // правило вида объясняет, что с этим перечнем делать
        val kind = orbita.ai.PackageKinds.default().of(DocumentHarvest.KIND)
        assertTrue(kind.rules.any { "form_field" in it }) { "правило про метку поля обязано быть в реестре" }
        assertTrue(kind.rulesVersion >= 3) { "редакция правил поднята: ${kind.rulesVersion}" }
    }
}

