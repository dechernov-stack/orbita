// Полка «Задачи фазы»: контент — данные, но данные с адресами. Шаг задачи
// гаснет по УСЛОВИЮ, и если условие ссылается на несуществующую проверку
// готовности, шаг не погаснет никогда — молча, без единой ошибки.
//
// Так и вышло: в сиде Pre-A три идентификатора («documents_issued»,
// «needs_covered», «requirement_mop») были выдуманы при наполнении полки, а
// заметилось это только при наполнении Phase A. Здесь сторож: каждый адрес
// сида обязан существовать — вида объекта, кода документа, проверки точки.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.model.CoreType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhaseTaskSeedsTest {

    private val mapper = ObjectMapper()

    private val пакеты = listOf(
        "11-задачи-фазы-pre-a.json",
        "12-задачи-фазы-phase-a.json",
    ).map { имя ->
        имя to mapper.readTree(
            RepoPaths.repoRoot().resolve("docs/tz/manual-run/packets/$имя").toFile(),
        )
    }

    /** Идентификаторы проверок готовности — из самого кода готовности. */
    private val проверки = setOf(
        "tbd", "trace", "reviews", "docs",
        "needs", "verification", "carriers", "geo_masks", "data_requests", "need_stakeholder",
    )

    private fun условия(задача: JsonNode): List<JsonNode> =
        задача.path("steps").map { it.path("done_when") } +
            задача.path("input").toList() +
            listOfNotNull(задача.path("output").path("done_when").takeIf { it.isObject })

    @Test
    fun `каждая проверка точки в сиде существует в готовности`() {
        val чужие = mutableListOf<String>()
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                условия(задача).filter { it.path("check").asText() == "gate_check" }.forEach { c ->
                    val id = c.path("gate_check_id").asText("")
                    if (id !in проверки) чужие += "$имя · ${задача.path("id").asText()} → «$id»"
                }
            }
        }
        assertTrue(чужие.isEmpty()) {
            "шаг с несуществующей проверкой не гаснет никогда: $чужие"
        }
    }

    @Test
    fun `каждый вид объекта в сиде — настоящий вид модели`() {
        val виды = CoreType.entries.map { it.dbType }.toSet()
        val чужие = mutableListOf<String>()
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                условия(задача)
                    .filter { it.path("check").asText() in setOf("objects", "taken_from_library") }
                    .forEach { c ->
                        val тип = c.path("type").asText("")
                        if (тип !in виды) чужие += "$имя · ${задача.path("id").asText()} → «$тип»"
                    }
            }
        }
        assertTrue(чужие.isEmpty()) { "условие ссылается на несуществующий вид: $чужие" }
    }

    @Test
    fun `каждый код документа в сиде — настоящий шаблон`() {
        val шаблоны = orbita.out.SeedTemplates.all.map { it.code }.toSet()
        val чужие = mutableListOf<String>()
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                условия(задача).filter { it.path("check").asText() == "document_issued" }.forEach { c ->
                    val код = c.path("code").asText("")
                    // Д-коды регламента (d2…d5) — псевдонимы комплекта, они законны
                    if (код !in шаблоны && !код.matches(Regex("^d[0-9]+$"))) {
                        чужие += "$имя · ${задача.path("id").asText()} → «$код»"
                    }
                }
                val выходКод = задача.path("output").path("document_code").asText("")
                if (выходКод.isNotBlank() && выходКод !in шаблоны && !выходКод.matches(Regex("^d[0-9]+$"))) {
                    чужие += "$имя · ${задача.path("id").asText()} · выход → «$выходКод»"
                }
            }
        }
        assertTrue(чужие.isEmpty()) { "выход задачи ссылается на несуществующий шаблон: $чужие" }
    }

    @Test
    fun `зависимости задач разрешаются внутри своей фазы`() {
        пакеты.forEach { (имя, пакет) ->
            val ids = пакет.path("objects").map { it.path("id").asText() }.toSet()
            пакет.path("objects").forEach { задача ->
                задача.path("depends_on").forEach { d ->
                    assertTrue(d.asText() in ids) {
                        "$имя · ${задача.path("id").asText()} зависит от ${d.asText()}, которого в фазе нет"
                    }
                }
            }
        }
    }
}
