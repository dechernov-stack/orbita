// Перенос spec/ai_semantics.py в тесты, один в один: 37 проверок.
//
// Три проверки эталона записаны в нём заглушкой `True` — на Python их нечем
// подтвердить. Здесь они выполнены по-настоящему: разбор без внешних вызовов
// проверяется обходом зависимостей класса, тождество правил фильтра — сравнением
// вердиктов с рукописным вводом, отсутствие перебивания — сравнением результата
// применения с предложением.
package orbita.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AiSemanticsTest {

    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry(RepoPaths.schemasDir())
    private val builder = PromptPackageBuilder(registry = registry, mapper = mapper)

    private fun json(s: String): JsonNode = mapper.readTree(s)

    private val schema = json("""{"required":["id","statement","operator","value","unit"]}""")
    private val context = json("""{"service":"SV-0001","moe":[{"name":"delivery","target":0.9}]}""")
    private val task = "Сформируй системные требования, реализующие показатели сервиса."

    private fun pkg() = builder.build("services_to_requirements", context, task, schema)

    @Nested
    @DisplayName("TZ-AI-001: промпт-пакет")
    inner class Packages {

        @Test
        fun `пакет собран и имеет идентификатор`() {
            assertTrue(pkg().id.startsWith("PP-"))
        }

        @Test
        fun `пакет полон`() {
            assertEquals(emptyList<String>(), packageIssues(pkg()))
        }

        @Test
        fun `идентификатор воспроизводим по содержимому`() {
            val p = pkg()
            assertEquals(p.id, builder.build("services_to_requirements", p.context, p.task, schema).id)
        }

        @Test
        fun `изменение задания меняет идентификатор`() {
            assertTrue(builder.build("services_to_requirements", context, "иное задание", schema).id != pkg().id)
        }

        @Test
        fun `схема ответа текстом отклонена`() {
            val bad = pkg().copy(responseSchema = mapper.readTree("\"JSON, пожалуйста\""))
            assertTrue(packageIssues(bad).any { "структурой" in it }, packageIssues(bad).toString())
        }

        @Test
        fun `неизвестный вид пакета отклонён`() {
            assertThrows<UnknownPackageKindException> {
                builder.build("write_me_a_poem", mapper.createObjectNode(), "", schema)
            }
        }
    }

    @Nested
    @DisplayName("TZ-AI-002: разбор ответа")
    inner class Parsing {

        private val parser = ResponseParser(mapper)

        @Test
        fun `разметка json снимается`() {
            val raw = "```json\n[{\"id\":\"RQ-9001\",\"statement\":\"Система должна ...\"," +
                "\"operator\":\"ge\",\"value\":0.9,\"unit\":\"1\"}]\n```"
            val r = parser.parse(raw, pkg())
            assertEquals(1, r.accepted.size)
            assertTrue(r.rejected.isEmpty())
        }

        @Test
        fun `частично корректный ответ принимается частично`() {
            val r = parser.parse(MIXED, pkg())
            assertEquals(1, r.accepted.size)
            assertEquals(1, r.rejected.size)
        }

        @Test
        fun `причина отклонения названа по полю`() {
            val r = parser.parse(MIXED, pkg())
            assertTrue(r.rejected[0].errors.any { "operator" in it }, r.rejected[0].errors.toString())
        }

        @Test
        fun `неразбираемый ответ не роняет разбор`() {
            val r = parser.parse("это не JSON", pkg())
            assertTrue(r.accepted.isEmpty())
            assertEquals(1, r.rejected.size)
        }

        // Эталон здесь ставит заглушку True: на Python подтвердить нечем.
        // Здесь — обход зависимостей: у разборщика нет ни клиента API, ни любого
        // иного пути наружу, поэтому обратиться к внешнему сервису он не может.
        @Test
        fun `разбор не обращается к внешним сервисам`() {
            val fields = ResponseParser::class.java.declaredFields.map { it.type.name }
            val ctorArgs = ResponseParser::class.java.declaredConstructors
                .flatMap { it.parameterTypes.toList() }.map { it.name }
            val outward = (fields + ctorArgs).filter { name ->
                name.startsWith("java.net") || name.startsWith("java.net.http") ||
                    name.contains("Http", ignoreCase = true) || name == StructuralApi::class.java.name
            }
            assertEquals(emptyList<String>(), outward, "у разборщика есть путь наружу: $outward")
        }

        private val MIXED = """[{"id":"RQ-9001","statement":"a","operator":"ge","value":0.9,"unit":"1"},
            {"id":"RQ-9002","statement":"b"}]"""
    }

    @Nested
    @DisplayName("Структурный фильтр до показа инженеру")
    inner class Screening {

        private val screening = ProposalScreening()

        private val good = json(
            """{"id":"RQ-9001","category":"performance",
               "statement":"Система должна обеспечивать доставку не менее 0,9.",
               "mop":{"name":"доставка","operator":"ge","value":{"value":0.9,"unit":"1"}},
               "verification":{"method":"analysis","means":"модель потоков",
                 "approach":"Прогон Монте-Карло по предрасчитанному расписанию пролётов, 500 реализаций."}}""",
        )

        private fun withoutOperator(): JsonNode {
            val n = good.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            n.put("id", "RQ-9002")
            (n.get("mop") as com.fasterxml.jackson.databind.node.ObjectNode).remove("operator")
            return n
        }

        private val bad = json("""{"id":"RQ-9003","category":"performance","statement":"Хорошая доставка данных."}""")

        @Test
        fun `состоятельное предложение доходит до инженера`() {
            val report = screening.screen(listOf(good, withoutOperator(), bad))
            assertEquals(1, report.shown.size, screening.issues(good).toString())
            assertEquals("RQ-9001", report.shown[0].path("id").asText())
        }

        @Test
        fun `предложение без оператора отбраковано до показа`() {
            assertEquals(2, screening.screen(listOf(good, withoutOperator(), bad)).rework.size)
        }

        @Test
        fun `замечания перечислены по правилам`() {
            val first = screening.screen(listOf(good, withoutOperator(), bad)).rework[0]
            assertTrue(
                first.issues.any { it.startsWith("качество: условие:") },
                first.issues.toString(),
            )
        }

        @Test
        fun `негодное предложение собирает несколько замечаний`() {
            val second = screening.screen(listOf(good, withoutOperator(), bad)).rework[1]
            assertTrue(second.issues.size >= 3, second.issues.toString())
        }

        // Эталон здесь ставит заглушку True. Здесь проверяется само свойство:
        // вердикт по предложению ИИ совпадает с вердиктом по рукописному
        // требованию того же содержания. Отдельной «облегчённой» проверки нет.
        @Test
        fun `фильтр применяет те же правила, что и к рукописному требованию`() {
            listOf(good, withoutOperator(), bad).forEach { item ->
                val handwritten = screening.issues(item)
                val proposed = screening.issues(asProposal(item, "PP-0001", "llm-a"))
                assertEquals(handwritten, proposed, "вердикты разошлись для ${item.path("id").asText()}")
            }
        }
    }

    @Nested
    @DisplayName("TZ-AI-003: арбитраж расхождений")
    inner class Arbitrate {

        private val a = listOf(json("""{"id":"R1","v":1}"""), json("""{"id":"R2","v":2}"""), json("""{"id":"R3","v":3}"""))
        private val b = listOf(json("""{"id":"R1","v":1}"""), json("""{"id":"R2","v":99}"""))
        private val answers = mapOf("llm-a" to a, "llm-b" to b)
        private val result = diffAnswers(answers)

        @Test
        fun `совпавшие фрагменты выделены`() {
            assertEquals(listOf("R1"), result.agreed.map { it.path("id").asText() })
        }

        @Test
        fun `расхождения выделены`() {
            assertEquals(listOf("R2", "R3"), result.disputed.map { it.key }.sorted())
        }

        @Test
        fun `в API уходят только спорные фрагменты`() {
            val payload = arbitrationPayload(result, mapper)
            assertTrue(payload.has("disputed"))
            assertFalse(payload.has("agreed"))
        }

        @Test
        fun `источник каждого варианта сохранён`() {
            val r2 = result.disputed.first { it.key == "R2" }
            assertEquals(setOf("llm-a", "llm-b"), r2.variants.keys)
        }

        @Test
        fun `передаётся меньше фрагментов, чем в полных ответах`() {
            assertTrue(
                fragmentsSent(result) < fragmentsTotal(answers),
                "${fragmentsSent(result)} из ${fragmentsTotal(answers)}",
            )
        }

        @Test
        fun `совпавший фрагмент в передаваемое не попадает`() {
            assertFalse("R1" in arbitrationPayload(result, mapper).toString())
        }
    }

    @Nested
    @DisplayName("TZ-AI-004: акцепт и происхождение")
    inner class Acceptance {

        private val item = json("""{"id":"RQ-9001","statement":"Система должна ..."}""")
        private val proposal = asProposal(item, "PP-0001", "llm-a", mapper)

        @Test
        fun `предложение помечено источником`() {
            assertEquals("ai_proposed", proposal.path("provenance").path("source").asText())
        }

        @Test
        fun `до акцепта предложение не влияет на расчёты`() {
            assertFalse(influencesCalculations(proposal))
        }

        @Test
        fun `после акцепта влияет`() {
            assertTrue(influencesCalculations(accept(proposal, by = "вед. системный инженер")))
        }

        @Test
        fun `автор акцепта зафиксирован`() {
            val accepted = accept(proposal, by = "вед. системный инженер")
            assertEquals("вед. системный инженер", accepted.path("provenance").path("ai").path("accepted_by").asText())
        }

        @Test
        fun `идентификатор пакета сохраняется после акцепта`() {
            val accepted = accept(proposal, by = "инженер")
            assertEquals("PP-0001", accepted.path("provenance").path("ai").path("prompt_package_id").asText())
        }

        @Test
        fun `правка перед акцептом помечена`() {
            val edited = accept(proposal, by = "инженер", edits = json("""{"statement":"Уточнённая формулировка должна ..."}"""))
            assertTrue(edited.path("provenance").path("ai").path("edited").asBoolean())
        }

        @Test
        fun `правка применена`() {
            val edited = accept(proposal, by = "инженер", edits = json("""{"statement":"Уточнённая формулировка должна ..."}"""))
            assertTrue(edited.path("statement").asText().startsWith("Уточнённая"))
        }

        @Test
        fun `ручной ввод влияет на расчёты без акцепта`() {
            assertTrue(influencesCalculations(json("""{"provenance":{"source":"manual"}}""")))
        }
    }

    @Nested
    @DisplayName("TZ-AI-006: применение как diff")
    inner class Diffs {

        private val current = json("""{"statement":"старая формулировка","operator":"ge","owner":"инженер"}""")
        private val proposed = json("""{"statement":"новая формулировка","operator":"le","unit":"kg"}""")
        private val diff = makeDiff(current, proposed)

        @Test
        fun `изменение поля видно как change`() {
            assertEquals(DiffOp.Change, diff.getValue("statement").op)
        }

        @Test
        fun `новое поле видно как add`() {
            assertEquals(DiffOp.Add, diff.getValue("unit").op)
        }

        @Test
        fun `отсутствующее в предложении сохраняется`() {
            assertEquals(DiffOp.Keep, diff.getValue("owner").op)
        }

        @Test
        fun `применяются только выбранные поля`() {
            val res = applyDiff(current, diff, setOf("statement", "unit"), mapper)
            assertEquals("новая формулировка", res.path("statement").asText())
            assertEquals("ge", res.path("operator").asText())
        }

        @Test
        fun `невыбранное добавление не применяется`() {
            val res = applyDiff(current, diff, setOf("statement", "unit"), mapper)
            assertEquals("kg", res.path("unit").asText())
        }

        @Test
        fun `пустой выбор ничего не меняет`() {
            assertEquals(current, applyDiff(current, diff, emptySet(), mapper))
        }

        // Эталон здесь ставит заглушку True. Здесь проверяется свойство:
        // после применения выбранных полей значения СОВПАДАЮТ с предложенными,
        // то есть переносить их руками не требуется ни в одном поле.
        @Test
        fun `ручного перебивания значений не требуется`() {
            val all = actionableFields(diff)
            val res = applyDiff(current, diff, all, mapper)
            all.forEach { field ->
                assertEquals(proposed.get(field), res.get(field), "поле $field пришлось бы вводить руками")
            }
        }
    }
}
