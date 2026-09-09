// Обмен (шип F): раскладка реестра в грамматику поле в поле, импорт .sdoc
// кандидатами через поле знаний с сохранённым UID (экспорт → импорт →
// экспорт совпадает), выгрузка знаний с отпечатком и фактами по
// диспозиции, пакет точки одним архивом, отказ без службы словами.
//
// Служба StrictDoc здесь подставная: она собирает документ из раскладки и
// разбирает его обратно — так проверяется всё, что делает ядро, а
// настоящий StrictDoc проверяется на стенде (tools/v2/check_exchange.py).
package orbita.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.internal.ExchangeRoutes
import orbita.exchange.api.ExchangeFactory
import orbita.exchange.api.SdocBundle
import orbita.exchange.api.StrictDocService
import orbita.exchange.api.KnowledgeFingerprint
import orbita.kernel.TestDbV2
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.KernelFactory
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.KnowledgeFactory
import orbita.library.api.LibraryFactory
import orbita.process.api.ProcessFactory
import orbita.readiness.api.ReadinessFactory
import java.util.zip.ZipInputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExchangeTest {
    private val mapper = ObjectMapper()
    private val store = KernelFactory.entityStore(TestDbV2.conn, mapper)
    private val links = KernelFactory.linkRegistry(TestDbV2.conn)
    private val проект = "PJ-9701"
    private val область = Area.Project(проект)
    private val провенанс = Provenance(Channel.MANUAL, "Иванов И.")
    private val intake = KnowledgeFactory.intake(store, links, mapper, LibraryFactory.shelves(store) { mapper.createObjectNode() })
    private val шаблон = mapper.readTree(TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-ФАЗЫ-PRE-A-NASA.json").toFile())

    /** Подставной StrictDoc: документ из раскладки, разбор — обратно по строкам. */
    private class ПодставнойStrictDoc : StrictDocService {
        override fun build(payload: JsonNode): SdocBundle {
            val текст = buildString {
                appendLine("[DOCUMENT]")
                appendLine("TITLE: ${payload.path("project").path("name").asText()}")
                appendLine()
                payload.path("requirements").forEach { р ->
                    appendLine("[REQUIREMENT]")
                    appendLine("UID: ${р.path("id").asText()}")
                    р.path("title").takeIf { it.isTextual }?.let { appendLine("TITLE: ${it.asText()}") }
                    appendLine("STATEMENT: ${р.path("statement").asText()}")
                    р.path("category").takeIf { it.isTextual }?.let { appendLine("CATEGORY: ${it.asText()}") }
                    р.path("level").takeIf { it.isTextual }?.let { appendLine("LEVEL: ${it.asText()}") }
                    р.path("verification_method").takeIf { it.isTextual }?.let { appendLine("VERIFICATION_METHOD: ${it.asText()}") }
                    р.path("mop").takeIf { it.isObject }?.let { м ->
                        appendLine("MOP_NAME: ${м.path("name").asText()}")
                        appendLine("MOP_OP: ${м.path("operator").asText()}")
                        appendLine("MOP_VALUE: ${м.path("value").path("value").asText()}")
                        appendLine("MOP_UNIT: ${м.path("value").path("unit").asText()}")
                    }
                    appendLine()
                }
            }
            return SdocBundle("[GRAMMAR]\nELEMENTS:\n- TAG: REQUIREMENT\n", текст)
        }

        override fun reqif(payload: JsonNode): String = "<REQ-IF>${payload.path("requirements").size()}</REQ-IF>"

        override fun parse(sdoc: String, sgra: String?): JsonNode {
            val mapper = ObjectMapper()
            val out = mapper.createObjectNode()
            val список = out.putArray("requirements")
            var текущее: com.fasterxml.jackson.databind.node.ObjectNode? = null
            val mop = mutableMapOf<String, String>()
            fun закрыть() {
                текущее?.let { т ->
                    if (mop.isNotEmpty()) {
                        val м = т.putObject("mop")
                        mop.forEach { (к, в) -> м.put(к, в) }
                    }
                    т.putObject("foreign_attributes")
                    т.putArray("relations")
                }
                mop.clear()
            }
            sdoc.lines().forEach { строка ->
                when {
                    строка == "[REQUIREMENT]" -> { закрыть(); текущее = список.addObject() }
                    строка.startsWith("UID: ") -> текущее?.put("id", строка.removePrefix("UID: "))
                    строка.startsWith("TITLE: ") -> текущее?.put("title", строка.removePrefix("TITLE: "))
                    строка.startsWith("STATEMENT: ") -> текущее?.put("statement", строка.removePrefix("STATEMENT: "))
                    строка.startsWith("CATEGORY: ") -> текущее?.put("category", строка.removePrefix("CATEGORY: "))
                    строка.startsWith("LEVEL: ") -> текущее?.put("level", строка.removePrefix("LEVEL: "))
                    строка.startsWith("VERIFICATION_METHOD: ") -> текущее?.put("verification_method", строка.removePrefix("VERIFICATION_METHOD: "))
                    строка.startsWith("MOP_NAME: ") -> mop["name"] = строка.removePrefix("MOP_NAME: ")
                    строка.startsWith("MOP_OP: ") -> mop["operator"] = строка.removePrefix("MOP_OP: ")
                    строка.startsWith("MOP_VALUE: ") -> mop["value"] = строка.removePrefix("MOP_VALUE: ")
                    строка.startsWith("MOP_UNIT: ") -> mop["unit"] = строка.removePrefix("MOP_UNIT: ")
                }
            }
            закрыть()
            return out
        }
    }

    private val служба = ПодставнойStrictDoc()
    private val обмен = ExchangeFactory.exchange(
        store, links, intake, mapper, strictDoc = служба,
        baselineRoot = java.nio.file.Files.createTempDirectory("orbita-sdoc").toFile().also { it.deleteOnExit() },
    )

    @BeforeTest
    fun чисто() {
        TestDbV2.очистить()
        store.create(проект, "project", область, "1", mapper.createObjectNode().put("name", "Обмен"), провенанс)
    }

    private fun реестр() {
        val сторона = store.create("SH-0001", "stakeholder", область, "3", mapper.readTree("""{"name":"Минтранс","role":"customer"}"""), провенанс)
        val нужда = store.create("ND-0001", "need", область, "3", mapper.readTree("""{"statement":"непрерывная телеметрия"}"""), провенанс)
        links.link("owns", сторона.id, нужда.id, провенанс)
        val сервис = store.create("SV-0001", "service", область, "6", mapper.readTree("""{"name":"короткие сообщения","qos_class":"B"}"""), провенанс)
        links.link("covers", сервис.id, нужда.id, провенанс)
        val цель = store.create("GL-0001", "goal", область, "4", mapper.readTree("""{"statement":"отслеживаемость","year":2033}"""), провенанс)
        store.create(
            "RQ-0002", "requirement", область, "8",
            mapper.readTree("""{"title":"Период","statement":"КА должен передавать сообщения не реже раза в сутки.","level":"project","category":"performance","verification_method":"test",
                "measure":{"key":"period","op":"le","value":24,"unit":"ч"},"source":[{"kind":"need","ref":"${нужда.id}"},{"kind":"goal","ref":"${цель.id}"}]}"""),
            провенанс,
        )
        store.create(
            "RQ-0001", "requirement", область, "8",
            mapper.readTree("""{"title":"Масса","statement":"Масса КА не более 100 кг.","level":"system","category":"constraint","source":[{"kind":"service","ref":"${сервис.id}"}]}"""),
            провенанс,
        )
    }

    @Test
    fun `раскладка — поле в поле, нити только на то, что в документе, порядок по кодам`() {
        реестр()
        val р = обмен.sdocPayload(проект)
        assertEquals(listOf("ND-0001"), р.path("needs").map { it.path("id").asText() })
        assertEquals("Минтранс", р.path("needs")[0].path("stakeholder").path("name").asText(), "сторона нужды — связью owns")
        assertEquals(listOf("ND-0001"), р.path("services")[0].path("traces_up").map { it.asText() }, "сервис ведёт к нужде связью covers")
        assertEquals(listOf("RQ-0001", "RQ-0002"), р.path("requirements").map { it.path("id").asText() }, "порядок по кодам")
        val второе = р.path("requirements")[1]
        assertEquals("le", второе.path("mop").path("operator").asText())
        assertEquals(24.0, второе.path("mop").path("value").path("value").asDouble())
        assertEquals("ч", второе.path("mop").path("value").path("unit").asText())
        assertEquals(listOf("ND-0001"), второе.path("traces_up").map { it.path("ref").asText() }, "цель в документе не живёт — нить на неё не идёт")
        assertEquals("draft", второе.path("lifecycle").path("status").asText())
        assertEquals(р.toString(), обмен.sdocPayload(проект).toString(), "повторная раскладка того же реестра совпадает")
    }

    @Test
    fun `экспорт → импорт → экспорт совпадает, требование приходит кандидатом с UID и показателем`() {
        реестр()
        val было = обмен.sdoc(проект)
        val импорт = обмен.importSdoc("PJ-9702", было.sdoc, было.sgra, "Петрова М.")
        assertEquals(2, импорт.candidates.size)
        assertEquals(mapOf("name" to "period", "operator" to "le", "value" to "24.0", "unit" to "ч"), импорт.candidates.first { it.uid == "RQ-0002" }.mop)
        val задание = assertNotNull(импорт.task, "кандидаты — планом задания, не записью в модель")
        assertTrue(store.list(Area.Project("PJ-9702"), "requirement").isEmpty(), "до приёма в модели ничего нет")
        val материал = assertNotNull(store.byCode(Area.Project("PJ-9702"), assertNotNull(импорт.material)))
        assertEquals("sdoc", материал.doc.path("kind").asText(), "файл .sdoc — входной документ")
        val план = intake.task("PJ-9702", задание)
        assertEquals(2, план.plan.count { it.targetKind == "requirement" })
        intake.accept("PJ-9702", задание, план.plan.indices.toList(), "Петрова М.")
        val принятые = store.list(Area.Project("PJ-9702"), "requirement").sortedBy { it.code }
        assertEquals(listOf("RQ-0001", "RQ-0002"), принятые.map { it.code }, "UID сохранён кодом")
        assertEquals(24.0, принятые[1].doc.path("measure").path("value").asDouble())
        assertEquals("s", принятые[1].provenance.anchor?.take(1), "требование помнит якорь блока .sdoc: ${принятые[1].provenance}")
        val снова = обмен.sdoc("PJ-9702")
        // Заголовок документа — имя проекта, оно у второго проекта своё;
        // требования обязаны совпасть побайтно.
        fun требования(т: String) = т.substringAfter("[REQUIREMENT]")
        assertEquals(требования(было.sdoc), требования(снова.sdoc), "экспорт после импорта совпадает с исходным")
    }

    @Test
    fun `выгрузка знаний — отпечаток без шапки, факты по диспозиции с якорями, сверка предупреждает`() {
        реестр()
        val материал = intake.putMaterial(проект, "Записка", "mission_memo", "Заказчик — Минтранс.\n\nСрок службы 5 лет.\n\nМасса 90 кг.", "Иванов И.")
        val я = intake.canon(проект, материал).map { it.anchor }
        val итог = intake.putFacts(проект, материал, """{"topics":[],"actions":[],"facts":[
            {"kind":"framing","subject":"заказчик","predicate":"кто заказчик","value":"Минтранс","source":{"anchor":"${я[0]}"},"source_mark":"И"},
            {"kind":"quantity","subject":"КА","predicate":"срок службы","value":"5","unit":"лет","source":{"anchor":"${я[1]}"},"source_mark":"И"},
            {"kind":"quantity","subject":"КА","predicate":"масса","value":"90","unit":"кг","source":{"anchor":"${я[2]}"},"source_mark":"П"}]}""", "Иванов И.")
        val коды = итог.accepted.map { it.id }
        intake.dispose(проект, коды[0], Disposition.ADOPTED, "", "Иванов И.")
        intake.dispose(
            проект, коды[2], Disposition.ASSUMED, "оценка поставщика", "Иванов И.",
            assumption = orbita.knowledge.api.Assumption("Иванов И.", "MCR", "весы", "перебор Р2"),
        )
        val пакет = обмен.knowledge(проект)
        assertEquals(обмен.knowledgeParts().size, пакет.files.size)
        assertEquals(пакет.fingerprint, обмен.knowledge(проект).fingerprint, "тот же реестр — тот же отпечаток")
        assertEquals(пакет.fingerprint, KnowledgeFingerprint.of(пакет.files), "шапка с отпечатком в хеш не входит")
        пакет.files.values.forEach { assertTrue(it.startsWith("<!-- отпечаток знаний: ${пакет.fingerprint}"), "шапка в каждом файле") }
        val факты = пакет.files.getValue("08-факты.md")
        assertTrue("`${коды[0]}` [И] заказчик — кто заказчик: Минтранс [$материал#${я[0]}]" in факты, факты)
        assertTrue("`${коды[2]}` [П] КА — масса: 90 кг [$материал#${я[2]}] _(допущение: Иванов И., до MCR" in факты, факты)
        assertFalse(коды[1] in факты, "свободный факт (без решения человека) в выгрузку не идёт")
        assertTrue("`ND-0001` непрерывная телеметрия" in пакет.files.getValue("03-цели-нужды-сервисы.md"))
        assertTrue("{#${я[0]}}" in пакет.files.getValue("07-материалы.md") || "<!-- ${я[0]} -->" in пакет.files.getValue("07-материалы.md"), "канон с якорями")

        assertTrue(обмен.verify(проект, пакет.fingerprint).ok)
        val устарел = обмен.verify(проект, "0000000000000000")
        assertFalse(устарел.ok); assertTrue("устарели" in устарел.warning!!)
        assertTrue("не назвал" in обмен.verify(проект, "").warning!!)
        intake.dispose(проект, коды[1], Disposition.NOTED, "", "Иванов И.")
        assertTrue(пакет.fingerprint != обмен.knowledge(проект).fingerprint, "решение по факту меняет знания — и отпечаток")
    }

    @Test
    fun `пакет точки — один архив, в нём точка, документы, требования, знания, манифест`() {
        реестр()
        val точка = mapper.createObjectNode().put("key", "mcr").put("title", "MCR")
        val пакет = обмен.pointPackage(проект, "mcr", "MCR — обзор концепции миссии", точка, mapOf("conops" to "%PDF-1.4 x".toByteArray()))
        val имена = mutableMapOf<String, String>()
        ZipInputStream(пакет.bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { имена[it.name] = zip.readAllBytes().decodeToString() }
        }
        assertEquals(пакет.entries.toSet() + "00-МАНИФЕСТ.md", имена.keys)
        assertTrue("точка-mcr.json" in имена && "документы/conops.pdf" in имена && "требования/PJ-9701.sdoc" in имена && "требования/orbita.sgra" in имена)
        assertTrue(имена.keys.count { it.startsWith("знания/") } == обмен.knowledgeParts().size)
        assertTrue("отпечаток знаний: `${пакет.fingerprint}`" in имена.getValue("00-МАНИФЕСТ.md"))
        assertEquals("пакет-mcr-PJ-9701.zip", пакет.fileName)
    }

    @Test
    fun `чужой файл обмена — каждый объект кандидат, атрибуты целиком в foreign, в модель ничего до приёма`() {
        // Разбор образца ReqPilot (насосная станция: 10 объектов шести типов, 9 связей,
        // свой профиль атрибутов UID · Title · Statement · Rationale · Status …), снятый
        // службой обмена; здесь — подставной разборщик. Правило владельца (ШИП-F-ЗАКРЫТ):
        // тесты обмена обязаны включать ЧУЖОЙ файл — образец остаётся фикстурой навсегда.
        val разобрано = mapper.readTree(javaClass.getResource("/чужой-reqif-насосная-станция.json")!!)
        val разборщик = orbita.exchange.api.ReqifParser { разобрано }
        val обменЧужой = ExchangeFactory.exchange(store, links, intake, mapper, strictDoc = служба, reqif = разборщик)
        val итог = обменЧужой.importForeign(проект, "<xml/>", "Петрова М.")
        assertEquals(10, итог.candidates.size, "каждый объект файла — кандидат, все шесть типов")
        assertEquals(6, итог.candidates.map { it.type }.toSet().size)
        assertTrue(итог.candidates.any { it.relations.isNotEmpty() }, "связи файла доехали до кандидатов ролью словами")
        val первый = итог.candidates.first()
        assertEquals("STK-001", первый.code, "код — из атрибута-идентификатора UID, не из identifier файла")
        assertEquals(mapOf("code" to "UID", "statement" to "Statement", "title" to "Title"), первый.used)
        assertTrue(первый.statement.startsWith("Оператор должен видеть текущее состояние"), первый.statement)
        assertEquals("Требование заинтересованной стороны", первый.type, "тип — словами файла")
        assertTrue("Owner" in первый.foreign && "Rationale" in первый.foreign && "Status" in первый.foreign, первый.foreign.keys.toString())
        assertFalse("Statement" in первый.foreign, "что легло в формулировку, в чужих не дублируется")
        assertEquals(12, первый.attributes.size, "ни один атрибут не потерян")
        assertTrue(store.list(область, "requirement").isEmpty(), "в модель до приёма ничего не записано")
        val задание = assertNotNull(итог.task)
        val план = intake.task(проект, задание)
        assertEquals(10, план.plan.count { it.targetKind == "requirement" })
        assertTrue(план.plan.first().effect.contains("код из «UID»"), план.plan.first().effect)
        intake.accept(проект, задание, план.plan.indices.toList(), "Петрова М.")
        val принято = store.byCode(область, "STK-001")
        assertNotNull(принято)
        assertEquals("И. Петров", принято!!.doc.path("foreign_attributes").path("Owner").asText(), "чужие атрибуты доехали до записи")
        assertEquals("Требование заинтересованной стороны", принято.doc.path("source_type").asText())
        val материал = store.byCode(область, assertNotNull(итог.material))
        assertEquals("reqif", материал!!.doc.path("kind").asText())
    }

    @Test
    fun `без службы StrictDoc экспорт отказывает словами, а знания и пакет точки живут`() {
        реестр()
        val безСлужбы = ExchangeFactory.exchange(store, links, intake, mapper, strictDoc = null)
        val записи = orbita.api.internal.GateRecords(store, mapper)
        val движок = ProcessFactory.engine(
            template = { шаблон },
            evaluator = ReadinessFactory.gateEvaluator(store, links, scenesDone = { emptySet() }, gatesPassed = { записи.passed(it) }),
            passedGates = { записи.passed(it) }, gatePlan = { emptyMap() },
            findings = { записи.findings(it) }, decisions = { записи.decisions(it) }, phaseOf = { записи.phaseOf(it) },
            onDecision = { p, т, кем, исход, помета, фаза -> записи.record(p, т, кем, исход, помета, фаза) },
        )
        val документы = orbita.documents.api.DocumentsFactory.documents(
            store, links,
            template = { код -> TestDbV2.repoRoot.resolve("docs/tz/v2/полки-порождённые/ШАБЛОН-" + код.uppercase() + ".json").toFile().takeIf { it.isFile }?.let { mapper.readTree(it) } },
            mapper = mapper,
            baselineRoot = java.nio.file.Files.createTempDirectory("orbita-baselines").toFile().also { it.deleteOnExit() },
        )
        val маршруты = ExchangeRoutes(store, безСлужбы, движок, документы, mapper)
        val п = mapOf("project" to проект)
        val отказ = assertNotNull(маршруты.handle("GET", "/v2/export/sdoc", п, null))
        assertEquals(503, отказ.code); assertTrue("ORBITA_STRICTDOC_URL" in отказ.body.path("error").asText())
        assertEquals(503, маршруты.handle("GET", "/v2/export/sdoc/reqif", п, null)!!.code)
        val безОбмена = ExchangeFactory.exchange(store, links, intake, mapper, strictDoc = null, reqif = null)
        val отказОбмена = assertNotNull(ExchangeRoutes(store, безОбмена, движок, документы, mapper).handle("POST", "/v2/import/reqif", п, """{"xml":"<x/>"}"""))
        assertEquals(503, отказОбмена.code); assertTrue("ORBITA_EXCHANGE_URL" in отказОбмена.body.path("error").asText())
        val знания = assertNotNull(маршруты.handle("GET", "/v2/export/knowledge", п, null))
        assertEquals(200, знания.code)
        assertEquals(обмен.knowledgeParts().size, знания.body.path("parts").size())
        assertNull(маршруты.handle("GET", "/v2/export/nothing", п, null))
        val архив = assertNotNull(маршруты.handle("GET", "/v2/points/MCR/package.zip", п, null))
        assertEquals("application/zip", архив.contentType)
        val имена = mutableListOf<String>()
        ZipInputStream(архив.binary!!.inputStream()).use { zip -> generateSequence { zip.nextEntry }.forEach { имена += it.name } }
        assertTrue("точка-MCR.json" in имена && "00-МАНИФЕСТ.md" in имена, имена.toString())
        assertFalse(имена.any { it.startsWith("требования/") }, "без службы .sdoc не приложен — и манифест говорит об этом")
    }
}
