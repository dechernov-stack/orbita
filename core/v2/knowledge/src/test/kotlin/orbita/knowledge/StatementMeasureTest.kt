// Мера постановки из записки — эталон владельца, без единого вызова модели.
//
// `docs/tz/v2/фикстуры/постановка-из-записки.json` — то, что система обязана
// дать ИЗ ОДНОГО ДОКУМЕНТА: 14 сторон · 43 нужды · 9 целей · 8 сервисов ·
// 12 требований · 13 ограничений · 4 этапа · 12 рисков.
//
// Здесь проверяется не модель, а УСТРОЙСТВО меры: по ней строится промпт, и
// пока устройство не названо тестом, «43 нужды» остаются числом из воздуха.
// Главное в нём — нужда несёт ОДНОГО носителя, поэтому общая формулировка из
// §2.1 даёт по пункту на каждую сторону, которой она принадлежит.
package orbita.knowledge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.knowledge.schema.GeneratedOntology
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatementMeasureTest {

    private val mapper = ObjectMapper()
    private val корень = File(System.getenv("ORBITA_REPO_ROOT") ?: ".")
    private val эталонФайл: File = корень.resolve("docs/tz/v2/фикстуры/постановка-из-записки.json")
    private val эталон: JsonNode by lazy {
        assertTrue(эталонФайл.isFile, "эталона нет по пути ${эталонФайл.path}")
        mapper.readTree(эталонФайл)
    }

    @Test
    fun `мера владельца названа числами и совпадает с содержимым эталона`() {
        val мера = эталон.path("summary")
        listOf(
            "stakeholders" to 14, "needs" to 43, "goals" to 9, "services" to 8,
            "requirements" to 12, "constraints" to 13, "stages" to 4, "risks" to 12,
        ).forEach { (раздел, число) ->
            assertEquals(число, мера.path(раздел).asInt(), "мера «$раздел»")
            assertEquals(
                число, эталон.path(раздел).size(),
                "в эталоне «$раздел» столько же записей, сколько обещает мера",
            )
        }
    }

    @Test
    fun `сорок три нужды — это общие по сторонам плюс своя у каждой`() {
        val нужды = эталон.path("needs")
        val общие = нужды.count { it.path("shared").asBoolean(false) }
        val свои = нужды.count { !it.path("shared").asBoolean(false) }

        assertEquals(20, общие, "общих нужд — повторённых по сторонам")
        assertEquals(23, свои, "своих нужд — выведенных из интереса стороны")
        assertEquals(43, общие + свои)

        // Ключевое устройство: одна и та же формулировка встречается у РАЗНЫХ
        // сторон отдельными пунктами. Иначе 43 не набрать никаким промптом.
        val поФормулировке = нужды.groupBy { it.path("statement").asText() }
        val повторённые = поФормулировке.filterValues { it.size > 1 }
        assertTrue(
            повторённые.isNotEmpty(),
            "общая нужда повторяется по сторонам — иначе меры не будет",
        )
        повторённые.forEach { (формулировка, пункты) ->
            val стороны = пункты.map { it.path("stakeholder").asText() }
            assertEquals(
                стороны.size, стороны.distinct().size,
                "повтор формулировки «${формулировка.take(40)}» — по РАЗНЫМ сторонам, а не дубль у одной",
            )
        }
    }

    @Test
    fun `у каждой стороны эталона есть свои нужды`() {
        val стороны = эталон.path("stakeholders").map { it.path("name").asText() }
        val сНуждами = эталон.path("needs").map { it.path("stakeholder").asText() }.toSet()

        assertEquals(14, стороны.size)
        val безНужд = стороны.filterNot { it in сНуждами }
        assertTrue(безНужд.isEmpty(), "сторона без нужд — сцена 3 не закроется: $безНужд")
        // Разброс эталона — от одной нужды (ГКРЧ: только частотные присвоения)
        // до четырёх. Минимум здесь ОДИН, а не два: число взято из самого
        // эталона, а не из догадки о том, сколько нужд «положено» стороне.
        val поСторонам = стороны.associateWith { имя ->
            эталон.path("needs").count { it.path("stakeholder").asText() == имя }
        }
        assertEquals(1, поСторонам.values.min(), "самая бедная сторона эталона несёт одну нужду")
        assertEquals(4, поСторонам.values.max(), "самая богатая — четыре")
        assertEquals(43, поСторонам.values.sum(), "нужды разошлись по сторонам без остатка")
    }

    @Test
    fun `три раздела эталона онтологией не описаны — это вопрос владельцу, не работа`() {
        val понятия = GeneratedOntology.concepts.map { it.code }.toSet()

        // Виды под них в истине СХЕМ есть, а понятий в онтологии формирования
        // нет: заводить понятие в коде запрещено, и чтение их не производит.
        listOf("intent", "requirement", "risk").forEach { вид ->
            assertTrue(
                вид !in понятия,
                "понятие «$вид» появилось в онтологии — тогда чтение обязано его давать, и этот тест пора менять",
            )
            assertTrue(
                orbita.kernel.schema.GeneratedKinds.byCode.containsKey(вид),
                "вид «$вид» в истине схем есть — значит не хватает только понятия",
            )
        }
        assertTrue(эталон.has("intent"), "эталон замысел содержит")
        assertTrue(эталон.path("requirements").size() > 0, "эталон требования содержит")
        assertTrue(эталон.path("risks").size() > 0, "эталон риски содержит")
    }
}
