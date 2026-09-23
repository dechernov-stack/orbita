// Инструменты модели (шип 4 §2, РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ §4): знание не
// подставляется в промпт, а берётся по запросу — term · node · interface ·
// facts · clauses · scene · similar. Каждый инструмент отвечает объектом с
// тем, откуда взято (код записи), чтобы модель цитировала источник; чего нет
// — сказано словом `missing`, а не выдумано.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.Tool
import orbita.ai.api.ToolHandler
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.Glossary
import orbita.knowledge.api.KnowledgeIndex
import orbita.library.api.Shelves
import orbita.process.api.ProcessEngine

class ModelToolbox(
    private val store: EntityStore,
    private val glossary: Glossary,
    private val index: KnowledgeIndex,
    private val engine: ProcessEngine,
    private val shelves: Shelves,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun tools(): List<Tool> = listOf(
        инструмент("term", "Термин словаря по написанию или синониму: каноническое имя, класс, определение, синонимы, код объекта.", "name" to "написание термина"),
        инструмент("node", "Узел состава по коду или имени: код, имя, вид, родитель, анкета параметров (ключ · единица).", "code" to "код или имя узла"),
        инструмент("interface", "Стык по коду или имени: код, имя, тип, стороны, параметры.", "code" to "код или имя стыка"),
        инструмент("facts", "Факты проекта по словам (с цитатами и рангами): до десяти лучших.", "query" to "о чём факты"),
        инструмент("clauses", "Пункты норматива по обозначению и теме: текст пункта и кто обязан.", "norm" to "обозначение акта, например «ПП 2216»", "topic" to "тема пунктов"),
        инструмент("scene", "Сцена фазы по ключу: вопрос сцены, условия выхода, ожидаемые выходы, мероприятия; для сцены 3 — нужды и стороны проекта.", "key" to "ключ сцены, например 3 или A4"),
        инструмент("similar", "Ближайшие по смыслу блоки базы знаний: термины, факты, пункты, блоки документов.", "text" to "текст для поиска", "class" to "term · fact · clause · canon_block либо пусто"),
    )

    fun handler(project: String): ToolHandler = ToolHandler { имя, вход ->
        runCatching {
            when (имя) {
                "term" -> термин(project, вход.path("name").asText(""))
                "node" -> узел(project, вход.path("code").asText(""))
                "interface" -> стык(project, вход.path("code").asText(""))
                "facts" -> факты(project, вход.path("query").asText(""))
                "clauses" -> пункты(вход.path("norm").asText(""), вход.path("topic").asText(""))
                "scene" -> сцена(project, вход.path("key").asText(""))
                "similar" -> похожее(project, вход.path("text").asText(""), вход.path("class").asText(""))
                else -> mapper.createObjectNode().put("error", "инструмента «$имя» нет")
            }
        }.getOrElse { mapper.createObjectNode().put("error", "инструмент «$имя» отказал: ${it.message}") }
    }

    // --- инструменты -----------------------------------------------------------

    private fun термин(project: String, имя: String): ObjectNode {
        val т = glossary.resolve(Area.Project(project), имя) ?: return нет("термина «$имя» в словаре нет — используй написание документа, система предложит кандидата")
        val запись = store.byId(т.id)
        return mapper.createObjectNode().put("code", т.code).put("term", т.termRu).put("class", т.cls)
            .put("definition", запись?.doc?.path("definition")?.asText("") ?: "").put("status", т.status)
            .also { у -> у.putArray("synonyms").also { м -> т.synonyms.forEach { м.add(it) } }; т.objectCode?.let { у.put("object_code", it) } }
    }

    private fun узел(project: String, код: String): ObjectNode {
        val игла = glossary.flat(код)
        val свой = store.list(Area.Project(project), "component").firstOrNull { glossary.flat(it.code) == игла || glossary.flat(it.doc.path("name").asText("")) == игла }
        if (свой != null) {
            return mapper.createObjectNode().put("code", свой.code).put("name", свой.doc.path("name").asText(""))
                .put("kind", свой.doc.path("kind").asText("")).put("level", свой.doc.path("level").asText(""))
                .put("parent", свой.doc.path("parent").asText("")).put("source", "проект")
                .also { у -> у.set<JsonNode>("params", свой.doc.path("params").takeIf { it.isArray } ?: mapper.createArrayNode()) }
        }
        val узлы = shelves.of("pbs_template").flatMap { п -> п.doc.path("nodes").toList() }
        val полочный = узлы.firstOrNull { glossary.flat(it.path("code").asText("")) == игла || glossary.flat(it.path("name").asText("")) == игла }
            ?: return нет("узла «$код» нет ни в проекте, ни на полке состава")
        return mapper.createObjectNode().put("code", полочный.path("code").asText()).put("name", полочный.path("name").asText(""))
            .put("kind", полочный.path("kind").asText("")).put("level", полочный.path("level").asText(""))
            .put("parent", полочный.path("parent").asText("")).put("desc", полочный.path("desc").asText("")).put("source", "полка состава")
            .also { у -> у.putArray("params").also { м -> полочный.path("params").forEach { п -> м.addObject().put("key", п.path("key").asText()).put("name", п.path("name").asText("")).put("unit", п.path("unit").asText("")) } } }
    }

    private fun стык(project: String, код: String): ObjectNode {
        val игла = glossary.flat(код)
        val свой = store.list(Area.Project(project), "interface").firstOrNull { glossary.flat(it.code) == игла || glossary.flat(it.doc.path("name").asText("")) == игла }
        if (свой != null) return свой.doc.deepCopy<ObjectNode>().put("code", свой.code).put("source", "проект")
        val полочный = shelves.of("interface_template").flatMap { п -> п.doc.path("interfaces").toList() }
            .firstOrNull { glossary.flat(it.path("code").asText("")) == игла || glossary.flat(it.path("name").asText("")) == игла }
            ?: return нет("стыка «$код» нет ни в проекте, ни на полке стыков")
        return полочный.deepCopy<ObjectNode>().put("source", "полка стыков")
    }

    private fun факты(project: String, слова: String): ObjectNode {
        val область = Area.Project(project)
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("facts")
        index.search(project, слова, kinds = listOf("fact"), limit = 10).forEach { н ->
            val ф = store.byCode(область, н.ref) ?: return@forEach
            массив.addObject().put("code", ф.code).put("subject", ф.doc.path("subject").asText(""))
                .put("predicate", ф.doc.path("predicate").asText("")).put("value", ф.doc.path("value").asText(""))
                .put("unit", ф.doc.path("unit").asText("")).put("quote", ф.doc.path("quote").asText(""))
                .put("rank", ф.doc.path("rank").asText("")).put("mark", ф.doc.path("mark").asText(""))
                .put("disposition", ф.doc.path("disposition").asText("")).put("material", ф.doc.path("material").asText(""))
        }
        if (массив.isEmpty) ответ.put("missing", "фактов по «$слова» в проекте нет")
        return ответ
    }

    private fun пункты(норма: String, тема: String): ObjectNode {
        val игла = glossary.flat(норма).replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.isNotBlank() }
        val акт = store.list(Area.Library, "normative_document").firstOrNull { н ->
            val обозначение = glossary.flat(н.doc.path("designation").asText("") + " " + н.code)
            игла.isNotEmpty() && игла.all { it in обозначение }
        } ?: return нет("норматива «$норма» на полке нет")
        val слова = glossary.flat(тема).split(Regex("\\s+")).filter { it.length > 3 }
        val ответ = mapper.createObjectNode().put("code", акт.code).put("designation", акт.doc.path("designation").asText(""))
            .put("title", акт.doc.path("title").asText(""))
        val массив = ответ.putArray("clauses")
        акт.doc.path("clauses").forEachIndexed { i, п ->
            val текст = glossary.flat(п.path("text").asText("") + " " + п.path("clause").asText(""))
            if (слова.isEmpty() || слова.any { it.take(5) in текст }) {
                массив.addObject().put("n", i + 1).put("clause", п.path("clause").asText("")).put("text", п.path("text").asText(""))
                    .put("who", п.path("who").asText("")).also { у -> if (п.path("limit").isObject) у.set<JsonNode>("limit", п.path("limit")) }
            }
        }
        return ответ
    }

    private fun сцена(project: String, ключ: String): ObjectNode {
        val фаза = engine.view(project)
        val сц = фаза.scenes.firstOrNull { it.key == ключ.trim() } ?: return нет("сцены «$ключ» в фазе нет: ${фаза.scenes.joinToString(" · ") { it.key }}")
        val ответ = mapper.createObjectNode().put("key", сц.key).put("title", сц.title).put("question", сц.question)
            .put("state", сц.state.name.lowercase())
        ответ.putArray("exit").also { м -> сц.exit.forEach { у -> м.addObject().put("title", у.title).put("passed", у.passed) } }
        ответ.putArray("activities").also { м -> сц.activities.forEach { а ->
            м.addObject().put("code", а.code).put("name", а.name).put("goal", а.goal)
                .also { у -> у.putArray("outputs").also { в -> а.outputs.forEach { о -> в.addObject().put("kind", о.kind).put("what", о.what).put("min", о.min).put("count", о.count) } } }
        } }
        val область = Area.Project(project)
        if (сц.key == "3") {
            ответ.putArray("stakeholders").also { м -> store.list(область, "stakeholder").filter { it.status != "cancelled" }.forEach { с -> м.addObject().put("code", с.code).put("name", с.doc.path("name").asText("")).put("role", с.doc.path("role").asText("")) } }
            ответ.putArray("needs").also { м -> store.list(область, "need").filter { it.status != "cancelled" }.forEach { н -> м.addObject().put("code", н.code).put("statement", н.doc.path("statement").asText("")) } }
        }
        if (сц.key == "5") {
            ответ.putArray("constraints").also { м -> store.list(область, "constraint").filter { it.status != "cancelled" }.forEach { р -> м.addObject().put("code", р.code).put("text", р.doc.path("text").asText(р.doc.path("statement").asText(""))) } }
        }
        return ответ
    }

    private fun похожее(project: String, текст: String, класс: String): ObjectNode {
        val виды = класс.trim().takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        index.search(project, текст, kinds = виды, limit = 10).forEach { н -> массив.add(IndexHitJson.of(mapper, н)) }
        if (массив.isEmpty) ответ.put("missing", "похожего на «$текст» в базе знаний нет")
        ответ.put("vector", index.vectorReady)
        return ответ
    }

    private fun нет(слова: String): ObjectNode = mapper.createObjectNode().put("missing", слова)

    private fun инструмент(имя: String, описание: String, vararg поля: Pair<String, String>): Tool {
        val схема = mapper.createObjectNode().put("type", "object")
        val свойства = схема.putObject("properties")
        поля.forEach { (п, о) -> свойства.putObject(п).put("type", "string").put("description", о) }
        схема.putArray("required").also { м -> поля.take(1).forEach { м.add(it.first) } }
        return Tool(имя, описание, схема)
    }
}
