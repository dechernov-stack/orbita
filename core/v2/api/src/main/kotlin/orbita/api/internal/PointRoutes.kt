// Маршруты точек (шип D: сцены 15–18): чек обзора, замечания с возвратом,
// решение точки, переход фазы.
//
// Домена здесь нет: возврат в сцену считает движок (замечание — событие),
// роль и блокирующие проверяет движок, записи ведёт GateRecords. Роутер
// только переводит HTTP в вызовы и обратно.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.api.api.Actor
import orbita.process.api.ProcessEngine
import orbita.process.api.RoleRefusedException

class PointRoutes(
    private val engine: ProcessEngine,
    private val records: GateRecords,
    private val mapper: ObjectMapper,
) {
    private val точка = Regex("/v2/points/([A-Za-z0-9_-]+)/(findings|decide)")
    private val ворота = Regex("/v2/gates/([A-Za-z0-9_-]+)/pass")
    private val замечаниеПуть = Regex("/v2/findings/([A-Za-z0-9_-]+)/close")

    /** Роли заводить замечание: те, кто ведёт обзор; инженер закрывает их работой. */
    private val обзор = setOf("lead", "lead_se", "da_review")

    fun handle(method: String, path: String, query: Map<String, String>, body: String?, actor: Actor?): V2Router.Ответ? {
        val проект = query["project"]
        return when {
            method == "GET" && path == "/v2/points" -> точки(требуется(проект))
            method == "GET" && path == "/v2/findings" -> замечания(требуется(проект), query["gate"], query["status"])
            method == "POST" && точка.matches(path) -> {
                val (ключ, действие) = точка.matchEntire(path)!!.destructured
                if (действие == "findings") завестиЗамечание(требуется(проект), ключ, разобрать(body), actor)
                else решение(требуется(проект), ключ, разобрать(body), actor)
            }
            method == "POST" && ворота.matches(path) ->
                решение(требуется(проект), ворота.matchEntire(path)!!.groupValues[1], разобрать(body), actor)
            method == "POST" && замечаниеПуть.matches(path) ->
                закрыть(требуется(проект), замечаниеПуть.matchEntire(path)!!.groupValues[1], разобрать(body), actor)
            else -> null
        }
    }

    private fun точки(проект: String): V2Router.Ответ {
        val фаза = engine.view(проект)
        val ответ = mapper.createObjectNode()
        ответ.put("project", проект)
        ответ.put("phase", фаза.phase)
        ответ.put("current_scene", фаза.currentScene)
        val массив = ответ.putArray("items")
        фаза.gates.forEach { массив.add(PhaseJson.точка(it, mapper)) }
        return V2Router.Ответ(200, ответ)
    }

    private fun замечания(проект: String, точка: String?, статус: String?): V2Router.Ответ {
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        records.findings(проект)
            .filter { точка == null || it.gate == точка }
            .filter { статус == null || it.status == статус }
            .forEach { массив.add(PhaseJson.замечание(it, mapper)) }
        return V2Router.Ответ(200, ответ)
    }

    private fun завестиЗамечание(проект: String, ключ: String, тело: JsonNode, actor: Actor?): V2Router.Ответ {
        val роли = роли(actor)
        if (роли.none { it in обзор }) {
            throw RoleRefusedException(
                "замечание обзора заводит руководитель проекта, ведущий системный инженер или DA; " +
                    "ваша роль — ${роли.joinToString(", ").ifBlank { "нет" }} (${автор(тело, actor)})",
            )
        }
        val фаза = engine.view(проект)
        val точка = фаза.gates.firstOrNull { it.key == ключ }
            ?: throw NoSuchElementException("точки «$ключ» в фазе проекта нет")
        val текст = тело.path("text").asText("").trim()
        require(текст.isNotBlank()) { "замечание без текста: скажите, что не так" }
        val сцена = тело.path("returns_to_scene").asText("").trim()
        require(фаза.scenes.any { it.key == сцена }) {
            "замечание возвращает в сцену «$сцена», которой в фазе нет — назовите сцену возврата (${фаза.scenes.joinToString(", ") { it.key }})"
        }
        val вид = тело.path("kind").asText("").ifBlank { if (ключ == "MCR") "rfa" else "finding" }
        require(вид in setOf("finding", "rfa", "rid")) { "вид замечания: finding · rfa · rid, получено «$вид»" }
        val запись = records.addFinding(
            проект, ключ, текст, сцена, автор(тело, actor), вид,
            тело.path("question").asText("").ifBlank { null },
            bornIn = when (ключ) { "MCR" -> "16"; "KDP-A" -> "18"; else -> "15" },
        )
        return V2Router.Ответ(201, PhaseJson.замечание(запись, mapper).also { it.put("point", точка.title) })
    }

    private fun закрыть(проект: String, код: String, тело: JsonNode, actor: Actor?): V2Router.Ответ {
        val роли = роли(actor)
        if (роли.none { it in обзор }) {
            throw RoleRefusedException(
                "закрывает замечание тот, кто ведёт обзор (руководитель проекта, ведущий СИ, DA); " +
                    "ваша роль — ${роли.joinToString(", ").ifBlank { "нет" }} (${автор(тело, actor)})",
            )
        }
        records.finding(проект, код) ?: throw NoSuchElementException("замечания «$код» в проекте нет")
        val запись = records.close(проект, код, автор(тело, actor), тело.path("note").asText("").ifBlank { null })
        return V2Router.Ответ(200, PhaseJson.замечание(запись, mapper))
    }

    private fun решение(проект: String, ключ: String, тело: JsonNode, actor: Actor?): V2Router.Ответ {
        val исход = тело.path("outcome").asText("").ifBlank { "approve" }
        val фаза = engine.decide(
            проект, ключ, автор(тело, actor), роли(actor), исход,
            тело.path("note").asText("").ifBlank { null },
        )
        val ответ = PhaseJson.вид(фаза, mapper)
        фаза.gates.firstOrNull { it.key == ключ }?.let { ответ.set<JsonNode>("point", PhaseJson.точка(it, mapper)) }
        return V2Router.Ответ(200, ответ)
    }

    /**
     * Роли решающего. Без учётки (режим без входа) ролей нет — и отказать
     * некому: тогда допускаются все роли шаблона, и это сказано в ответе
     * движка именем «стенд», а не выдуманной ролью.
     */
    private fun роли(actor: Actor?): Set<String> =
        actor?.roles ?: setOf("lead", "lead_se", "specialist", "da_review")

    private fun автор(тело: JsonNode, actor: Actor?): String =
        actor?.name ?: тело.path("author").asText("").ifBlank { "стенд" }

    private fun требуется(проект: String?): String =
        проект?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("укажите ?project=<код проекта>")

    private fun разобрать(body: String?): JsonNode =
        if (body.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(body)
}
