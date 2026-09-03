// ПМИ-4, реестр пакетов (правило 5 поставки): каждый пакет вставлен на
// одноразовом стенде — схема проходит, счётчики совпадают, отказов по форме
// нет; отсев по содержанию допустим и записан. Одноразовый стенд здесь —
// тестовая база: тот же канал пакетов, те же схемы и правила, что у живого
// вызова. В модель стенда при подготовке не пишется ничего.
//
// Пакеты РАСКЛАДЫВАЮТСЯ из поставки внешнего контура скриптом
// tools/build_pmi4_packets.py: истина — в поставке, а не в разложенном файле,
// и последний тест это стережёт.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Pmi4PacketsTest {

    private val mapper = ObjectMapper()
    private val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    private val dir = RepoPaths.repoRoot().resolve("docs/tz/manual-run-4/пакеты")

    /** Профиль службы стенда: виды пакетов ПМИ-4, основание обязательно. */
    private val PROFILE = """
        {"id":"AP-0001","name":"Пакеты ПМИ-4","purpose":"проверка пакетов на тестовой базе",
         "kinds":["mission_to_needs","mission_to_goals","needs_to_services","services_to_requirements",
                  "requirement_decomposition","risk_register","section_prose"],
         "transport":"any","model_hint":"пакет",
         "statement_rules":["формулировка требования содержит модальное «должна»"],
         "prohibitions":["bent-pipe не предлагать (Р1)"],
         "require_source":true,
         "lifecycle":{"status":"Draft","version":"1"}}"""

    /** Полки, чьи коды называют пакеты Р08 и Р10: каркас, стыки, архитектура. */
    private fun взятьПолки(project: String) {
        val канал = LibraryChannel(boundary)
        listOf("18-каркас-pbs.json", "19-интерфейсы.json", "20-архитектура-arcadia.json").forEach { файл ->
            val фрагмент = mapper.readTree(
                RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/$файл").toFile(),
            ).path("objects")[0] as com.fasterxml.jackson.databind.node.ObjectNode
            фрагмент.remove("id")
            val id = boundary.editing.create(
                CoreType.LibraryFragment, фрагмент, "test", orbita.mod.store.ObjectStore.LIBRARY_PROJECT,
            ).id
            канал.apply(id, project, "инженер", LibraryChannel.TakeOptions(withOptional = true))
        }
    }

    @BeforeEach
    fun clean() {
        TestDb.truncateAll()
        TestDb.conn.createStatement().use { it.execute("DELETE FROM ai_calls") }
        boundary.ingest(
            CoreType.Project,
            """{"id":"PJ-2501","name":"ПМИ-4 проверка пакетов","phase":"pre_phase_a",
                "mission_intent":{"for_whom":"грузоперевозчики","what":"резервный канал координат",
                                  "where":"вне наземного покрытия","horizon":"2030"},
                "milestones":[{"gate":"MCR","due":"2026-12-01"}],
                "lifecycle":{"status":"Draft","version":"1"}}""",
            "test", "PJ-2501",
        )
        boundary.ingest(CoreType.AiProfile, PROFILE, "test", "PJ-2501")
    }

    private fun raw(name: String) = dir.resolve(name).toFile().readText()

    private fun report(name: String) = boundary.ai.packet(raw(name), "PJ-2501", "Чернов").report

    private fun сломанные(отчёт: com.fasterxml.jackson.databind.JsonNode): List<String> =
        отчёт.path("malformed").map { it.path("item").path("id").asText() }

    /** Причина отказа по форме — словами схемы, чтобы правку было куда вносить. */
    private fun почему(отчёт: com.fasterxml.jackson.databind.JsonNode): String =
        отчёт.path("malformed").joinToString("\n") {
            it.path("item").path("id").asText() + ": " + it.path("errors").joinToString("; ") { e -> e.asText() }
        }

    @Test
    fun `Р01 замысел — четыре поля с якорями по схеме черновика`() {
        // замысел вставляется на шаге Ш3 мастер-пути, не общим каналом пакетов:
        // проверяется схемой черновика — той же, что применяет принятие
        val node = mapper.readTree(raw("Р01-замысел.json"))
        assertEquals("mission_intent_from_docs", node.path("kind").asText())
        val errors = boundary.schemas.validate("core/mission-intent-draft", node)
        assertTrue(errors.isEmpty()) { "черновик замысла обязан проходить схему: $errors" }
        listOf("for_whom", "what", "where", "horizon").forEach { f ->
            assertTrue(node.path("intent").path(f).path("text").asText("").isNotBlank()) { "поле $f с якорями" }
            assertTrue(node.path("intent").path(f).path("anchors").size() > 0) { "поле $f без якорей происхождения" }
        }
    }

    @Test
    fun `Р02 цели и нужды — девять и шесть, отказов по форме нет`() {
        val цели = report("Р02-цели.json")
        assertEquals(emptyList<String>(), сломанные(цели)) {
            цели.path("malformed").joinToString("\n") { it.path("issues").toString() }
        }
        assertEquals(9, цели.path("proposed").asInt())
        val нужды = report("Р02-нужды.json")
        assertEquals(emptyList<String>(), сломанные(нужды)) { нужды.path("malformed").toPrettyString() }
        assertEquals(6, нужды.path("proposed").asInt())
    }

    @Test
    fun `Р03 сервисы — восемь, четыре ссылки чужого проекта без пары`() {
        val r = report("Р03-сервисы.json")
        assertEquals(emptyList<String>(), сломанные(r)) { r.path("malformed").toPrettyString() }
        assertEquals(8, r.path("proposed").asInt())
        val items = mapper.readTree(raw("Р03-сервисы.json")).path("items")
        val чужие = items.filter { s -> s.path("traces_up").any { it.asText().startsWith("ND-01") } }
        assertEquals(4, чужие.size) { "четыре сервиса нарочно ссылаются на нужды среза PJ-0001" }
        val свои = items.filter { s -> s.path("traces_up").any { it.asText().startsWith("ND-9") } }
        assertEquals(4, свои.size) { "остальные сопоставлены с нуждами Р02 по формулировке" }
    }

    @Test
    fun `пакет с неизвестным кодом полки отказывает целиком и называет код`() {
        val e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            boundary.ai.packet(
                """{"kind":"services_to_requirements","items":[{"id":"RQ-9999","level":"system",
                    "statement":"Система должна выполнять проверку кода полки надёжно.","category":"functional",
                    "traces_up":[{"ref":"MG-9001"}],"verification_events":[],"owner":"вед. СИ",
                    "allocated_to":[{"component":"@НЕТ-ТАКОГО-УЗЛА"}],
                    "lifecycle":{"status":"Draft","version":"1"}}]}""",
                "PJ-2501", "Чернов",
            )
        }
        assertTrue(e.message!!.contains("НЕТ-ТАКОГО-УЗЛА")) { e.message!! }
    }

    @Test
    fun `Р04 требования проекта — четырнадцать, ловушки поставки срабатывают`() {
        val r = report("Р04-требования.json")
        assertEquals(14, r.path("proposed").asInt()) { "предложено: ${r.path("proposed")}" }
        // RQ-P-13 «число без источника» — величина без происхождения: отсев по
        // форме; RQ-P-14 «при необходимости» — по форме цело, но свод качества
        // шлёт его в доработку с названной причиной
        val доработка = r.path("rework").path("rework").associate {
            it.path("item").path("tags").joinToString(" ") { t -> t.asText() } to it.path("issues").toString()
        }
        val ловушка14 = доработка.entries.firstOrNull { it.key.contains("RQ-P-14") }
        assertTrue(ловушка14 != null) { "«при необходимости» обязано дойти до доработки: ${доработка.keys}" }
        assertTrue("неизмеримое" in ловушка14!!.value || "нечёт" in ловушка14.value) { ловушка14.value }
        val показано = r.path("shown").map { it.path("item").path("title").asText() }
        assertTrue(показано.isNotEmpty()) {
            "чистые требования обязаны проходить. Доработка: " +
                r.path("rework").path("rework").joinToString("\n") { стр ->
                    стр.path("item").path("id").asText() + " → " + стр.path("issues").joinToString("; ") { i -> i.asText() }
                }
        }
    }

    @Test
    fun `Р05 урожай записки — сто один кандидат двенадцати классов по схеме разбора`() {
        val node = mapper.readTree(raw("Р05-урожай-записки.json"))
        assertEquals("document_semantic_parse", node.path("kind").asText())
        assertEquals(101, node.path("items").size())
        val errors = boundary.schemas.validate("core/document-harvest", node)
        assertTrue(errors.isEmpty()) { "урожай обязан проходить схему разбора: $errors" }
        val классы = node.path("items").map { it.path("class").asText() }.toSet()
        assertEquals(12, классы.size) { "классов: $классы" }
        // классы вне известного перечня объясняют себя полем schema_note
        listOf("risk", "open_question", "finding").forEach { расширение ->
            val строки = node.path("items").filter { it.path("class").asText() == расширение }
            assertTrue(строки.isNotEmpty()) { "класс $расширение обязан быть в урожае" }
            assertTrue(строки.all { it.path("schema_note").asText("").isNotBlank() }) {
                "$расширение: класс вне перечня обязан объяснить себя"
            }
        }
        // метка достоверности — одной буквой схемы, полный код поставки в note
        val метки = node.path("items").mapNotNull { it.path("source_mark").asText("").ifBlank { null } }.toSet()
        assertTrue(метки.all { it in setOf("И", "В", "П") }) { "метки: $метки" }
    }

    @Test
    fun `Р06 и Р07 проза — по разделу, текст с происхождением службы`() {
        listOf("Р06-проза-SEMP-3.json" to "semp", "Р07-проза-ConOps-1.json" to "conops").forEach { (файл, шаблон) ->
            val node = mapper.readTree(raw(файл))
            assertEquals("section_prose", node.path("kind").asText())
            val item = node.path("items")[0]
            assertEquals(шаблон, item.path("template_code").asText()) { файл }
            assertTrue(item.path("text").asText("").length > 400) { "$файл: текст раздела пуст или обрывочен" }
            val errors = boundary.schemas.validate("core/section-text", item)
            assertTrue(errors.isEmpty()) { "$файл: $errors" }
        }
    }

    @Test
    fun `Р08 системные — семнадцать, распределение кодами полок и связь с проектным требованием`() {
        взятьПолки("PJ-2501")
        val r = report("Р08-системные.json")
        assertEquals(emptyList<String>(), сломанные(r)) { почему(r) }
        assertEquals(17, r.path("proposed").asInt())
        val items = mapper.readTree(raw("Р08-системные.json")).path("items")
        // канон TBR (L-C5): пять значений ждут расчёта — помета с владельцем,
        // точкой SRR и действием, а не число из воздуха
        val tbr = items.filter { it.path("mop").path("tbr").asBoolean(false) }
        assertEquals(5, tbr.size) { "помет TBR: ${tbr.size}" }
        assertTrue(tbr.all { it.path("mop").path("tbd_due").asText() == "SRR" && it.path("mop").path("tbd_owner").asText().isNotBlank() })
        // RQ-S-13 разделено по П14 «одна мысль — одно требование»: 13 и 13a
        val метки = items.flatMap { it.path("tags").map { t -> t.asText() } }
        assertTrue("код поставки: RQ-S-13a" in метки) { "разделённое требование обязано дойти: $метки" }
        val сУзлами = items.filter { it.path("allocated_to").size() > 0 }
        assertTrue(сУзлами.size >= 12) { "распределение на носители: ${сУзлами.size} из ${items.size()}" }
        // ссылка на узел полки — кодом «@»: её разрешает канал ДО проверки формы
        val коды = items.flatMap { r2 ->
            r2.path("allocated_to").map { a ->
                a.path("component").asText("").ifBlank { a.path("interface").asText("") }
            }
        }
        assertTrue(коды.all { it.startsWith("@") }) { "в пакете ссылки остаются кодами: $коды" }
        val принятые = r.path("shown").flatMap { стр ->
            стр.path("item").path("allocated_to").map { a ->
                a.path("component").asText("").ifBlank { a.path("interface").asText("") }
            }
        }
        assertTrue(принятые.isNotEmpty() && принятые.none { it.startsWith("@") }) {
            "канал обязан разрешить коды полок в идентификаторы проекта: $принятые"
        }
        // связь с родительским требованием Р04 — кодом поставки в метках
        val выведенные = items.filter { r2 -> r2.path("tags").any { it.asText().startsWith("выведено из: ") } }
        assertTrue(выведенные.size >= 10) { "связей с проектными требованиями: ${выведенные.size}" }
    }

    @Test
    fun `Р09 риски — четырнадцать со шкалами NPR 8000_4, владелец ролью`() {
        val r = report("Р09-риски.json")
        assertEquals(emptyList<String>(), сломанные(r)) { r.path("malformed").toPrettyString() }
        assertEquals(14, r.path("proposed").asInt())
        val items = mapper.readTree(raw("Р09-риски.json")).path("items")
        assertTrue(items.all { it.path("probability").asInt() in 1..5 && it.path("impact").asInt() in 1..5 }) {
            "вероятность и последствия — по шкале 1–5"
        }
        val высокие = items.filter { it.path("probability").asInt() * it.path("impact").asInt() >= 12 }
        assertTrue(высокие.all { it.path("strategy").asText("").isNotBlank() }) {
            "риск высокой критичности обязан нести стратегию"
        }
    }

    @Test
    fun `Р10 сценарные — шесть на цепочки, покрытие цепочкой кодом полки`() {
        взятьПолки("PJ-2501")
        val r = report("Р10-сценарные-цепочки.json")
        assertEquals(emptyList<String>(), сломанные(r)) { r.path("malformed").toPrettyString() }
        assertEquals(6, r.path("proposed").asInt())
        val items = mapper.readTree(raw("Р10-сценарные-цепочки.json")).path("items")
        val цепочки = items.flatMap { it.path("realized_by").map { c -> c.asText() } }
        assertEquals(6, цепочки.size) { "каждое сценарное требование ложится на цепочку: $цепочки" }
        assertTrue(цепочки.all { it.startsWith("@FC-") }) { "цепочка названа кодом полки: $цепочки" }
        assertTrue(items.all { it.path("category").asText() == "operational" }) {
            "сценарное требование покрывается цепочкой — категория operational"
        }
    }

    @Test
    fun `сводка проверки по каждому пакету — предложено, показано, доработка, отсев`() {
        взятьПолки("PJ-2501")
        val строки = listOf(
            "Р02-цели.json", "Р02-нужды.json", "Р03-сервисы.json", "Р04-требования.json",
            "Р08-системные.json", "Р09-риски.json", "Р10-сценарные-цепочки.json",
        ).map { файл ->
            val r = report(файл)
            listOf(
                файл, r.path("proposed").asInt().toString(),
                r.path("shown").size().toString(),
                r.path("rework").path("rejected").asInt().toString(),
                r.path("malformed").size().toString(),
            ).joinToString(" · ")
        }
        // сводка ЖИВАЯ: числа снимаются прогоном и сверяются с реестром пакетов
        val причины = listOf("Р09-риски.json", "Р08-системные.json").joinToString("\n") { файл ->
            val r = report(файл)
            файл + ":\n" + r.path("rework").path("rework").take(3).joinToString("\n") { стр ->
                "  " + стр.path("item").path("id").asText() + " → " + стр.path("issues").joinToString("; ") { i -> i.asText() }
            }
        }
        val реестр = RepoPaths.repoRoot().resolve("docs/tz/manual-run-4/пакеты/реестр.md").toFile().readText() + причины
        строки.forEach { строка ->
            val части = строка.split(" · ")
            val ожидание = "${части[0]} | ${части[1]} | ${части[2]} | ${части[3]} | ${части[4]}"
            assertTrue(реестр.contains(ожидание)) {
                "итог проверки в реестре разошёлся с прогоном.\nОжидалось строкой: $ожидание\nВсе строки прогона:\n" +
                    строки.joinToString("\n") + "\n\n" + причины
            }
        }
    }

    @Test
    fun `пакеты разложены из поставки, а не правлены руками`() {
        val процесс = ProcessBuilder("python3", "tools/build_pmi4_packets.py", "--check")
            .directory(RepoPaths.repoRoot().toFile())
            .redirectErrorStream(true)
            .start()
        val вывод = процесс.inputStream.bufferedReader().readText()
        assertEquals(0, процесс.waitFor(), "раскладка разошлась с поставкой:\n$вывод")
    }
}
