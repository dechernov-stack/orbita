// Раскладка реестра v2 в грамматику Орбиты (ops/strictdoc: NEED · SERVICE ·
// REQUIREMENT с показателем парой). Поле в поле, без переименований по
// дороге: имя поля документа реестра — путь в раскладке, который знает
// служба. Порядок — по кодам: повторная выгрузка неизменённого проекта
// совпадает побайтно (детерминизм проверяет tools/check_sdoc_roundtrip.py).
//
// Нити — из реестра связей (`covers`, `derives_from`) и из `source`
// требования: часть нитей документ требования не называет, их знает только
// реестр связей. Нить на объект, которого в документе нет (цель, узел), в
// файл не идёт — принимающий инструмент счёл бы её битой.
package orbita.exchange.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry

internal class SdocPayload(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val mapper: ObjectMapper,
) {

    fun of(project: String): ObjectNode {
        val область = Area.Project(project)
        val проект = store.byCode(область, project) ?: store.list(область, "project").firstOrNull()
        val out = mapper.createObjectNode()
        out.putObject("project")
            .put("id", project)
            .put("name", проект?.doc?.path("name")?.asText(project) ?: project)

        val нужды = живые(область, "need")
        val сервисы = живые(область, "service")
        val требования = живые(область, "requirement")
        val вДокументе = (нужды + сервисы + требования).map { it.code }.toSet()
        val кодПоId = (нужды + сервисы + требования).associate { it.id to it.code }

        val n = out.putArray("needs")
        нужды.forEach { нужда ->
            val узел = n.addObject().put("id", нужда.code).put("statement", нужда.doc.path("statement").asText(""))
            // Сторона нужды — связью `owns` (сцена 3), не полем документа.
            links.to(нужда.id, "owns").firstNotNullOfOrNull { store.byId(it.from) }
                ?.let { узел.putObject("stakeholder").put("name", it.doc.path("name").asText(it.code)) }
        }
        val s = out.putArray("services")
        сервисы.forEach { сервис ->
            val узел = s.addObject().put("id", сервис.code).put("name", сервис.doc.path("name").asText(""))
            val вверх = узел.putArray("traces_up")
            links.from(сервис.id, "covers").mapNotNull { кодПоId[it.to] }.filter { it in вДокументе }.sorted().forEach { вверх.add(it) }
        }
        val r = out.putArray("requirements")
        требования.forEach { т -> r.add(требование(т, кодПоId, вДокументе)) }
        out.putArray("links")
        return out
    }

    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != "cancelled" }.sortedBy { it.code }

    private fun требование(т: Entity, кодПоId: Map<String, String>, вДокументе: Set<String>): ObjectNode {
        val д = т.doc
        val узел = mapper.createObjectNode()
        узел.put("id", т.code)
        текст(узел, "title", д.path("title"))
        узел.put("statement", д.path("statement").asText(""))
        текст(узел, "category", д.path("category"))
        текст(узел, "level", д.path("level"))
        текст(узел, "priority", д.path("priority"))
        текст(узел, "rationale", д.path("rationale"))
        текст(узел, "acceptance_criteria", д.path("acceptance_criteria"))
        текст(узел, "verification_method", д.path("verification_method"))
        текст(узел, "owner", д.path("owner"))
        // Источник: документ и якорь — первый источник-материал; нити вверх —
        // источники-сущности (нужда, сервис), если они в документе.
        val источники = д.path("source").takeIf { it.isArray }?.toList().orEmpty()
        источники.firstOrNull { it.path("kind").asText() in setOf("material", "document", "tor") }?.let { и ->
            val ссылка = и.path("ref").asText("")
            val код = store.byId(ссылка)?.code ?: ссылка
            узел.putObject("source").put("doc", код).put("anchor", и.path("anchor").asText(""))
        }
        д.path("normative_basis").takeIf { it.isObject }?.let { н ->
            узел.putObject("normative_basis").put("ref", н.path("ref").asText("")).put("clause", н.path("clause").asText(""))
        }
        // Показатель парой: имя · оператор · величина · единица.
        д.path("measure").takeIf { it.isObject }?.let { м ->
            val mop = узел.putObject("mop")
            текст(mop, "name", м.path("key").takeIf { !it.isMissingNode } ?: м.path("name"))
            текст(mop, "operator", м.path("op").takeIf { !it.isMissingNode } ?: м.path("operator"))
            val величина = mop.putObject("value")
            м.path("value").let { в -> if (в.isNumber) величина.put("value", в.asDouble()) else величина.put("value", в.asText("")) }
            величина.put("unit", м.path("unit").asText(""))
        }
        узел.putObject("lifecycle").put("status", т.status).put("version", т.version)
        val вверх = узел.putArray("traces_up")
        val нити = linkedSetOf<String>()
        источники.filter { it.path("kind").asText() in setOf("need", "service", "requirement") }
            .mapNotNull { и -> кодПоId[и.path("ref").asText()] ?: и.path("ref").asText("").takeIf { к -> к in вДокументе } }
            .forEach { нити += it }
        links.from(т.id, "covers").mapNotNull { кодПоId[it.to] }.forEach { нити += it }
        нити.filter { it in вДокументе }.sorted().forEach { вверх.addObject().put("ref", it) }
        val вывод = узел.putArray("derives_from")
        links.from(т.id, "derives_from").mapNotNull { кодПоId[it.to] }.filter { it in вДокументе }.sorted().forEach { вывод.add(it) }
        return узел
    }

    private fun текст(узел: ObjectNode, поле: String, значение: JsonNode) {
        val т = if (значение.isMissingNode || значение.isNull) "" else значение.asText("")
        if (т.isNotBlank()) узел.put(поле, т)
    }
}
