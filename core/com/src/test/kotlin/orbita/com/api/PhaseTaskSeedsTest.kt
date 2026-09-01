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

    /**
     * Круг 6, ловушка 1: FS «потому что по умолчанию» — выдумка о регламенте.
     * Тип связи обязан стоять в полке у КАЖДОЙ зависимости; неразмеченная
     * строка прежнего формата на полке больше не живёт.
     */
    @Test
    fun `у каждой связи задач размечен тип`() {
        val голые = mutableListOf<String>()
        val чужие = mutableListOf<String>()
        val типы = setOf("FS", "SS", "FF", "INPUT")
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                задача.path("depends_on").forEach { d ->
                    val кто = "$имя · ${задача.path("id").asText()}"
                    if (d.isTextual) голые += "$кто → ${d.asText()}"
                    else if (d.path("type").asText("") !in типы) {
                        чужие += "$кто → ${d.path("task").asText()}: «${d.path("type").asText()}»"
                    }
                }
            }
        }
        assertTrue(голые.isEmpty()) { "связь без типа читается как INPUT и врёт о регламенте: $голые" }
        assertTrue(чужие.isEmpty()) { "тип связи вне набора FS · SS · FF · INPUT: $чужие" }
    }

    /**
     * Порядок шагов — тоже связями. Шаг без `after` считается начальным, и
     * это законно; незаконно — ссылаться на несуществующий соседний шаг или
     * на себя: такая связь тихо перестала бы держать порядок.
     */
    @Test
    fun `связи шагов ссылаются на соседей той же задачи`() {
        val битые = mutableListOf<String>()
        val начальные = mutableListOf<String>()
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                val шагов = задача.path("steps").size()
                var первых = 0
                задача.path("steps").forEachIndexed { i, шаг ->
                    if (шаг.path("after").isEmpty) первых += 1
                    шаг.path("after").forEach { a ->
                        val n = a.path("step").asInt(0)
                        val тип = a.path("type").asText("")
                        val кто = "$имя · ${задача.path("id").asText()} шаг ${i + 1}"
                        if (n < 1 || n > шагов || n - 1 == i) битые += "$кто → шаг $n"
                        if (тип !in setOf("FS", "SS")) битые += "$кто: тип «$тип»"
                    }
                }
                if (шагов > 1 && первых == шагов) {
                    начальные += "$имя · ${задача.path("id").asText()}"
                }
            }
        }
        assertTrue(битые.isEmpty()) { "связь шага никуда не ведёт: $битые" }
        assertTrue(начальные.isEmpty()) {
            "у задачи с несколькими шагами порядок не размечен вовсе — шаги встанут все параллельно: $начальные"
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
                    val кого = if (d.isTextual) d.asText() else d.path("task").asText("")
                    assertTrue(кого in ids) {
                        "$имя · ${задача.path("id").asText()} зависит от $кого, которого в фазе нет"
                    }
                }
            }
        }
    }

    /**
     * Живое наблюдение владельца: шаг «Орг-структура и план работ» вёл на
     * `startpath` — то есть в СОЗДАНИЕ ПРОЕКТА, хотя проект давно создан и
     * идёт фаза A. Адрес шага — такая же проверяемая вещь, как условие: шаг,
     * ведущий не туда, хуже мёртвой кнопки, потому что выглядит рабочим.
     *
     * Здесь: каждый адрес сида обязан быть настоящим экраном навигации.
     */
    @Test
    fun `каждый адрес шага — настоящий экран навигации`() {
        // перечень — из МАРШРУТИЗАТОРА, а не из меню: часть экранов
        // открывается переходом и пунктом рейки не значится
        val app = java.nio.file.Files.readString(
            orbita.mod.RepoPaths.repoRoot().resolve("web/src/App.tsx"),
        )
        val экраны = Regex("""case '([a-z0-9]+)'""").findAll(app)
            .map { it.groupValues[1] }.toSet()
        val чужие = mutableListOf<String>()
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                задача.path("steps").forEach { шаг ->
                    val адрес = шаг.path("screen").asText("")
                    if (адрес.isNotBlank() && адрес !in экраны) {
                        чужие += "$имя · ${задача.path("id").asText()} · «${шаг.path("title").asText()}» → $адрес"
                    }
                }
            }
        }
        assertTrue(чужие.isEmpty()) { "шаг ведёт в несуществующий экран: $чужие" }
    }

    /** Мастер-путь — место создания проекта; задача фазы туда не ведёт. */
    @Test
    fun `задачи фазы не ведут в мастер создания проекта`() {
        val промахи = mutableListOf<String>()
        пакеты.forEach { (имя, пакет) ->
            пакет.path("objects").forEach { задача ->
                val фаза = задача.path("phase").asText()
                задача.path("steps").forEach { шаг ->
                    if (фаза != "pre_phase_a" && шаг.path("screen").asText() == "startpath") {
                        промахи += "$имя · ${задача.path("id").asText()} · «${шаг.path("title").asText()}»"
                    }
                }
            }
        }
        assertTrue(промахи.isEmpty()) {
            "проект уже создан: шаг фазы не может вести в мастер начала — $промахи"
        }
    }
}

