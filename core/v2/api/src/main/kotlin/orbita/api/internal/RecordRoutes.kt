// Общие маршруты записи по истине (27.09, «блокеры Phase A»): заведение записи
// и связь — одним вызовом на все виды.
//
// Карточка объекта одна на все виды (шип 5 §1.3). Правка на месте у неё
// общая давно (`PATCH /v2/entities/{код}`), а заведения и связи общих не
// было: «+ завести» функцию, элемент обмена или КЕ из строки долга карточки
// узла было нечем (у КЕ не было даже частного маршрута), а грань-связь
// («выведено из» у требования, поставщик узла) правкой поля не пишется —
// связь не поле. Отсюда «нечем» у A4 · A5 · A6 в таблице «до» сквозного прогона.
//
// Решает истина схем, а не этот файл: поле вне вида отбивает хранилище
// (`write_rules.schema_on_write`), перечень — значением истины (русское
// слово узнаётся и ложится кодом), обязательные поля вида — до записи,
// ссылка — на запись проекта. Вид с собственной логикой заведения (требование
// с нумерацией и деривацией, риск со сроком-точкой, стык со сторонами…) общий
// маршрут не заводит — отказ называет его форму.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.schema.Enums
import orbita.kernel.schema.GeneratedKinds

class RecordRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        method == "POST" && path == "/v2/entities" -> завести(требуется(query, "project"), разобрать(body))
        method == "GET" && path == "/v2/links" -> связиЗаписи(требуется(query, "project"), требуется(query, "code"), query["type"])
        method == "POST" && path == "/v2/links" -> связать(требуется(query, "project"), разобрать(body))
        method == "POST" && path == "/v2/links/remove" -> развязать(требуется(query, "project"), разобрать(body))
        else -> null
    }

    /**
     * Виды, которые заводятся общим маршрутом, и префикс их кода: у них нет
     * логики заведения сверх истины (запись — это и есть всё заведение).
     */
    private val заводимые = mapOf(
        "function" to "FN",
        "exchange" to "EX",
        "exchange_item" to "EI",
        "configuration_item" to "CI",
        "logical_component" to "LC",
    )

    private fun завести(проект: String, тело: JsonNode): V2Router.Ответ {
        val вид = тело.path("kind").asText("").trim()
        require(вид.isNotBlank()) { "нужен kind — вид записи истины схем" }
        val спец = runCatching { GeneratedKinds.of(вид) }.getOrNull()
            ?: throw IllegalArgumentException("вида «$вид» в истине схем нет")
        val префикс = заводимые[вид] ?: throw IllegalArgumentException(
            "«${спец.title}» общим маршрутом не заводится: у вида своя форма заведения (сцена рождения — ${спец.bornIn ?: "полка"}); " +
                "общий маршрут заводит " + заводимые.keys.joinToString(" · ") { GeneratedKinds.of(it).title },
        )
        val поля = тело.path("fields")
        require(поля.isObject) { "нужно fields: {поле: значение}" }
        val область = Area.Project(проект)
        val документ = mapper.createObjectNode()
        поля.fields().forEach { (имя, значение) ->
            require(имя !in setOf("code", "kind", "id")) { "поле «$имя» задаётся не значением: код — полем code тела, вид — kind" }
            require(имя in спец.fields) {
                "поля «$имя» у вида «${спец.title}» нет: истина знает " + спец.fields.joinToString(" · ") { спец.labels[it] ?: it }
            }
            val пусто = значение.isNull || (значение.isTextual && значение.asText().isBlank()) || (значение.isArray && значение.isEmpty)
            if (!пусто) документ.set<JsonNode>(имя, значение)
        }
        ссылки(область, спец, документ)
        val нет = спец.requiredFields.filter { it != "code" && документ.path(it).isMissingNode }
        require(нет.isEmpty()) {
            "у записи вида «${спец.title}» обязательны: " + нет.joinToString(" · ") { спец.labels[it] ?: it }
        }
        Enums.проверить(вид, документ, документ.fieldNames().asSequence().toSet()).let { отказы ->
            require(отказы.isEmpty()) { отказы.joinToString("; ") }
        }
        Enums.нормализовать(вид, документ)
        val код = тело.path("code").asText("").trim().ifBlank { следующийКод(область, вид, префикс) }
        require(store.byCode(область, код) == null) { "код «$код» в проекте занят" }
        // Сцена рождения: названная телом, иначе последняя по истине вида («7, A5» → A5).
        val сцена = тело.path("scene").asText("").trim().ifBlank { null }
            ?: спец.bornIn?.split(",")?.map { it.trim() }?.lastOrNull { it.isNotBlank() }
        val создано = store.create(код, вид, область, сцена, документ, Provenance(Channel.MANUAL, автор(тело)))
        return V2Router.Ответ(
            201,
            mapper.createObjectNode().put("code", создано.code).put("id", создано.id).put("kind", вид).put("version", создано.version),
        )
    }

    /**
     * Ссылки приходят кодами (пикер карточки показывает и шлёт код), а
     * хранятся записью: код ведёт к записи проекта нужного вида. Учётка
     * (`ref account`) — не запись проекта: логин ложится как есть.
     */
    private fun ссылки(область: Area, спец: orbita.kernel.schema.KindSpec, документ: ObjectNode) {
        спец.refKinds.forEach { (поле, виды) ->
            val значение = документ.get(поле) ?: return@forEach
            fun разрешить(код: String): String {
                val запись = store.byCode(область, код) ?: store.byId(код)
                if (запись == null) {
                    require("account" in виды) {
                        "«${спец.labels[поле] ?: поле}»: «$код» никуда не ведёт — такой записи в проекте нет"
                    }
                    return код
                }
                require(виды.isEmpty() || запись.kind in виды) {
                    "«${спец.labels[поле] ?: поле}»: «$код» — это ${GeneratedKinds.byCode[запись.kind]?.title ?: запись.kind}, " +
                        "а поле ждёт " + виды.joinToString(" либо ") { GeneratedKinds.byCode[it]?.title ?: it }
                }
                return запись.id
            }
            when {
                значение.isArray -> документ.putArray(поле).also { м -> значение.forEach { м.add(разрешить(it.asText())) } }
                значение.isTextual -> документ.put(поле, разрешить(значение.asText()))
            }
        }
    }

    // --- связи ----------------------------------------------------------

    /** Связи, у которых истина требует обоснования (`link.rationale`). */
    private val сОснованием = setOf("derives_from", "satisfied_by", "realized_by", "conflicts_with", "external_identity")

    private fun связать(проект: String, тело: JsonNode): V2Router.Ответ {
        val связь = GeneratedKinds.of("link")
        val тип = тело.path("type").asText("").trim()
        val типы = связь.enums["type"].orEmpty()
        require(тип in типы) { "вид связи «$тип» истине неизвестен" }
        val область = Area.Project(проект)
        val откуда = запись(область, тело.path("from").asText(""), "откуда")
        val куда = запись(область, тело.path("to").asText(""), "куда")
        require(откуда.id != куда.id) { "связь записи с самой собой не связь" }
        val основание = тело.path("rationale").asText("").trim().ifBlank { null }
        require(!(тип in сОснованием && основание == null)) {
            "у связи «${Enums.значения("link", "type")[тип] ?: тип}» обоснование обязательно: связь без причины неотличима от случайной"
        }
        val уточнение = тело.path("subtype").asText("").trim().ifBlank { null }
        if (уточнение != null) {
            require(тип == "derives_from") { "уточнение (decomposition · derivation · refinement) бывает только у «выведено из»" }
            require(уточнение in связь.enums["subtype"].orEmpty()) { "уточнение «$уточнение» истине неизвестно" }
        }
        links.from(откуда.id, тип).firstOrNull { it.to == куда.id }?.let { была ->
            return V2Router.Ответ(200, mapper.createObjectNode().put("id", была.id).put("type", тип).put("exists", true))
        }
        val новая = links.link(тип, откуда.id, куда.id, Provenance(Channel.MANUAL, автор(тело)), rationale = основание, subtype = уточнение)
        // Ответ — что заведено; сама связь читается GET /v2/links (один сериализатор связи).
        return V2Router.Ответ(201, mapper.createObjectNode().put("id", новая.id).put("type", тип).put("subtype", уточнение).put("exists", false))
    }

    /** Связи записи — от неё и к ней: грань-связь карточки показывает их чипами. */
    private fun связиЗаписи(проект: String, код: String, тип: String?): V2Router.Ответ {
        val область = Area.Project(проект)
        val сама = запись(область, код, "запись")
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        fun добавить(связь: orbita.kernel.api.Link, направление: String, другой: String) {
            val конец = store.byId(другой) ?: return
            массив.addObject().put("id", связь.id).put("type", связь.type).put("direction", направление)
                .put("other", конец.code).put("other_kind", конец.kind)
                .put("other_title", listOf("statement", "name", "title", "designation").firstNotNullOfOrNull { конец.doc.path(it).asText("").ifBlank { null } } ?: конец.code)
                .put("rationale", связь.rationale).put("subtype", связь.subtype)
        }
        links.from(сама.id, тип).forEach { добавить(it, "from", it.to) }
        links.to(сама.id, тип).forEach { добавить(it, "to", it.from) }
        return V2Router.Ответ(200, ответ)
    }

    private fun развязать(проект: String, тело: JsonNode): V2Router.Ответ {
        val id = тело.path("id").asText("").trim()
        val связь = links.byId(id) ?: throw NoSuchElementException("связи «$id» нет")
        val область = Area.Project(проект)
        require(store.byId(связь.from)?.area == область || store.byId(связь.to)?.area == область) { "связь «$id» не из этого проекта" }
        links.unlink(id, Provenance(Channel.MANUAL, автор(тело)))
        return V2Router.Ответ(200, mapper.createObjectNode().put("id", id).put("removed", true))
    }

    private fun запись(область: Area, код: String, конец: String) =
        (store.byCode(область, код.trim()) ?: store.byId(код.trim())?.takeIf { it.area == область })
            ?: throw IllegalArgumentException("конец связи «$конец»: «$код» — такой записи в проекте нет")

    // --- общее ------------------------------------------------------------

    private fun следующийКод(область: Area, вид: String, префикс: String): String {
        val занято = store.list(область, вид).mapNotNull {
            Regex("^$префикс-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "%s-%04d".format(префикс, (занято.maxOrNull() ?: 0) + 1)
    }

    private fun автор(тело: JsonNode): String = тело.path("author").asText("").ifBlank { "инженер" }

    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
