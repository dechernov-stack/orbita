// Сквозные экраны v2: полки, материалы и загрузка, поле знаний, матрица
// покрытия, «мои задания», перечень сущностей и фиксация точки.
//
// Вынесены из V2Router отдельным файлом по правилу ТЗ-BACKEND §3 (файл
// тонкий, домена внутри нет): экраны сквозные, к одной сцене не привязаны.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.formulation.api.Formulation
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.Intake
import orbita.library.api.Shelves
import orbita.process.api.ProcessEngine

class AcrossRoutes(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val engine: ProcessEngine,
    private val shelves: Shelves,
    private val intake: Intake,
    private val formulation: Formulation,
    private val mapper: ObjectMapper = ObjectMapper(),
    /**
     * Текст из двоичного файла (docx · pdf · xlsx · pptx) — извлекатель
     * подставляет граница; null — материал принимается только текстом и
     * ссылкой. Формат живёт вне ядра v2 (ADR-064: форматы — на границе).
     */
    private val extract: ((fileName: String, bytes: ByteArray) -> String?)? = null,
) {

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): V2Router.Ответ? = when {
        // Полки: загрузка поставки и просмотр.
        method == "POST" && path == "/v2/shelves" -> положитьНаПолку(разобрать(body))

        method == "GET" && path == "/v2/shelves" -> полка(требуется(query, "kind"))

        // Глоссарий: интерфейс говорит терминами NASA, соответствия — здесь.
        // Поиск идёт по ЛЮБОМУ из имён: инженер помнит своё.
        method == "GET" && path == "/v2/glossary" -> глоссарий(query["q"])

        // Волна 2: материалы, задание загрузки и план действий.
        method == "GET" && path == "/v2/materials" -> материалы(требуется(query, "project"))
        method == "POST" && path == "/v2/materials" -> материал(требуется(query, "project"), разобрать(body))

        method == "POST" && path == "/v2/intake" -> заданиеЗагрузки(требуется(query, "project"), разобрать(body))

        method == "GET" && path == "/v2/facts" -> фактология(требуется(query, "project"))

        // Матрица покрытия: не хранится, а считается по связям.
        method == "GET" && path == "/v2/coverage" -> покрытие(требуется(query, "project"))

        method == "GET" && path == "/v2/my-tasks" -> задания(требуется(query, "project"), query["role"])

        method == "GET" && path == "/v2/entities" ->
            перечень(требуется(query, "project"), требуется(query, "kind"))

        else -> null
    }

    private fun положитьНаПолку(тело: JsonNode): V2Router.Ответ {
        val вид = тело.path("kind").asText("")
        val код = тело.path("code").asText("")
        require(вид.isNotBlank() && код.isNotBlank()) { "полке нужны вид и код записи" }
        // Состояние выкладки и КОД, в который она легла, — часть ответа:
        // поставщик обязан видеть, что повтор ничего не переписал и что акт
        // ушёл в уже лежащую карточку, а не верить своему счётчику.
        val итог = shelves.put(вид, код, тело.path("doc"), автор(тело))
        return V2Router.Ответ(201, mapper.createObjectNode()
            .put("code", итог.item.code).put("kind", итог.item.kind)
            .put("state", итог.state.name.lowercase()))
    }

    private fun глоссарий(запрос: String?): V2Router.Ответ {
        val полка = shelves.of("glossary_cross_terms").firstOrNull()
        val ответ = mapper.createObjectNode()
        val массив = ответ.putArray("items")
        val игла = запрос?.trim()?.lowercase().orEmpty()
        полка?.doc?.path("items")?.forEach { термин ->
            val строки = listOf("term_nasa", "ru_equivalent", "en_full", "romanov")
                .map { термин.path(it).asText("") }
            if (игла.isEmpty() || строки.any { it.lowercase().contains(игла) }) {
                массив.add(термин)
            }
        }
        ответ.put(
            "note",
            if (полка == null) "глоссарий не загружен: python3 tools/v2/load_shelves.py"
            else "интерфейс — в терминах NASA; здесь соответствия РК-11КТ и схемы Романова",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun полка(вид: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        shelves.of(вид).forEach { запись ->
            массив.addObject().put("code", запись.code).put("kind", запись.kind)
                .set<JsonNode>("doc", запись.doc)
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }


    private val снимок = UrlSnapshot(mapper = mapper)

    /** Материалы проекта: тип, объём, прежняя версия — чтобы новую версию класть поверх старой. */
    private fun материалы(проект: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        store.list(Area.Project(проект), "material").sortedBy { it.code }.forEach { м ->
            массив.addObject()
                .put("code", м.code)
                .put("name", м.doc.path("name").asText(м.code))
                .put("kind", м.doc.path("kind").asText("reference"))
                .put("chars", м.doc.path("chars").asInt(0))
                .put("supersedes", м.doc.path("supersedes").asText("").ifBlank { null })
                .put("created_at", м.createdAt.toString())
                // Ранг доверия — отдельное поле рядом с типом: тип остаётся
                // режимом разбора, доверие называет человек. Профиль
                // содержимого (Д2а) ставит разбор — по нему экран показывает
                // доли блоков, а не гадает по расширению файла.
                .put("authority", м.doc.path("authority").asText("").ifBlank { null })
                .also { у ->
                    м.doc.path("profile").takeIf { it.isObject }?.let { у.set<JsonNode>("profile", it) }
                }
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }

    private fun материал(проект: String, тело: JsonNode): V2Router.Ответ {
        val ссылка = тело.path("url").asText("").trim()
        // Ссылка — второй вход в поле знаний (замечание прохода 08.09):
        // текст берётся по ней здесь и кладётся снимком, как и вставленный.
        // Снимок, а не живая ссылка: страница завтра другая, а факт с
        // якорем обязан оставаться проверяемым. Страница без рендера —
        // отказ словами (шип E п. 4), не пустой материал.
        // Двоичный файл (ПМИ-5, замечание 12.09 «только md»): docx · pdf · xlsx ·
        // pptx приходят base64, текст извлекает сервер — тем же извлекателем,
        // что у документов v1; формат не читается — отказ словами, не пустой материал.
        val имяФайла = тело.path("filename").asText("").trim()
        val файлBase64 = тело.path("file_base64").asText("")
        val извлечённый = if (файлBase64.isNotBlank()) {
            val извлекатель = extract ?: throw IllegalArgumentException("файлы на этом стенде не читаются: вставьте текст")
            val байты = try { java.util.Base64.getDecoder().decode(файлBase64.substringAfter(",")) } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("файл не разобрался как base64")
            }
            require(имяФайла.isNotBlank()) { "у файла нет имени — по расширению узнаётся формат" }
            извлекатель(имяФайла, байты)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "файл «$имяФайла» не прочитался: читаются docx · pdf · xlsx · pptx · txt · md; " +
                        "скан без текстового слоя тоже не читается — приложите текст",
                )
        } else null
        val снятый = if (тело.path("text").asText("").isBlank() && извлечённый == null && ссылка.isNotBlank()) снимок.fetch(ссылка) else null
        val текст = тело.path("text").asText("").ifBlank { извлечённый ?: снятый?.text ?: "" }
        require(текст.isNotBlank()) { "у материала нет текста: вставьте текст, приложите файл либо дайте ссылку" }
        val шапка = снятый?.let { "Источник: $ссылка · снимок ${it.date} · ${it.renderer}\n\n" }
            ?: извлечённый?.let { "Источник: файл $имяФайла · текст извлечён сервером\n\n" } ?: ""
        val код = intake.putMaterial(
            проект,
            тело.path("name").asText("").ifBlank { имяФайла.substringBeforeLast('.').ifBlank { ссылка.ifBlank { "материал" } } },
            тело.path("kind").asText("").ifBlank { тело.path("type").asText("reference") },
            шапка + текст,
            автор(тело),
            supersedes = тело.path("supersedes").asText("").ifBlank { null },
            // Ранг доверия приходит С ФОРМЫ и передаётся как есть: пустое —
            // решение ядра, а не умолчание маршрута. На проекте поля знаний
            // v2 ядро отвечает отказом «ранг доверия материала обязателен»,
            // на проекте прохода выводит ранг по прежнему типу входного.
            authority = тело.path("authority").asText("").trim().ifBlank { null },
        )
        val ответ = mapper.createObjectNode().put("code", код).put("from_url", ссылка.isNotBlank()).put("chars", текст.length)
        // Ранг возвращается тот, который ПОСТАВИЛО ядро: форма показывает не
        // то, что отправила, а то, с чем материал теперь живёт.
        store.byCode(Area.Project(проект), код)?.doc?.let { карточка ->
            ответ.put("authority", карточка.path("authority").asText(""))
            карточка.path("notes").asText("").takeIf { it.isNotBlank() }?.let { ответ.put("authority_note", it) }
        }
        извлечённый?.let { ответ.put("extracted_from", имяФайла) }
        снятый?.let { ответ.put("snapshot_renderer", it.renderer).put("snapshot_date", it.date) }
        тело.path("supersedes").asText("").takeIf { it.isNotBlank() }?.let { ответ.put("supersedes", it) }
        return V2Router.Ответ(201, ответ)
    }

    private fun заданиеЗагрузки(проект: String, тело: JsonNode): V2Router.Ответ {
        val задание = intake.plan(
            проект,
            тело.path("material").asText(""),
            тело.path("intent").asText("разбери по сущностям"),
            автор(тело),
        )
        val узел = mapper.createObjectNode()
        узел.put("task", задание.id)
        узел.put("material", задание.material)
        узел.put("intent", задание.intent)
        узел.put("note", задание.note)
        val факты = узел.putArray("facts")
        задание.facts.forEach { факты.add(KindJson.факт(mapper, it)) }
        val план = узел.putArray("plan")
        задание.plan.forEach { действие ->
            план.addObject()
                .put("kind", действие.kind)
                .put("title", действие.title)
                .put("effect", действие.effect)
        }
        return V2Router.Ответ(200, узел)
    }

    private fun фактология(проект: String): V2Router.Ответ {
        val массив = mapper.createArrayNode()
        // Тем же построителем, что и заведение: список и заведение писались
        // разным кодом, и `manual` был в одном, но не в другом (правило 2).
        intake.facts(проект).forEach { массив.add(KindJson.факт(mapper, it)) }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }

    private fun покрытие(проект: String): V2Router.Ответ {
        val матрица = formulation.coverage(проект)
        val ответ = mapper.createObjectNode()
        ответ.put("total", матрица.total)
        ответ.put("covered", матрица.covered)
        ответ.put("summary", матрица.summary)
        val строки = ответ.putArray("needs")
        матрица.needs.forEach { нужда ->
            val n = строки.addObject()
            n.put("code", нужда.code)
            n.put("statement", нужда.statement)
            n.put("owner", нужда.ownerName)
            n.put("covered", нужда.covered)
            n.put("gap", нужда.gap)
            val цели = n.putArray("goals")
            нужда.goals.forEach { цели.add(it) }
            val сервисы = n.putArray("services")
            нужда.services.forEach { сервисы.add(it) }
        }
        val края = ответ.putArray("stakeholders_without_needs")
        матрица.stakeholdersWithoutNeeds.forEach { края.add(it) }
        return V2Router.Ответ(200, ответ)
    }

    private fun задания(проект: String, роль: String?): V2Router.Ответ {
        val фаза = engine.view(проект)
        val массив = mapper.createArrayNode()
        фаза.scenes
            .filter { it.state != orbita.process.api.SceneState.DONE }
            .filter { роль == null || it.role == роль }
            .forEach { сцена ->
                сцена.blockers.forEach { причина ->
                    массив.addObject()
                        .put("scene", сцена.key)
                        .put("scene_title", сцена.title)
                        .put("role", сцена.role)
                        .put("what", причина)
                        // Сцена закрыта — работать нельзя; открыта — это моя работа
                        .put("waiting", сцена.state == orbita.process.api.SceneState.LOCKED)
                }
            }
        val ответ = mapper.createObjectNode()
        ответ.put("project", проект)
        ответ.set<JsonNode>("items", массив)
        ответ.put(
            "note",
            if (массив.isEmpty) "разрывов нет: все сцены фазы прожиты"
            else "разрывы берутся из условий сцен; закрывается разрыв работой в своей сцене",
        )
        return V2Router.Ответ(200, ответ)
    }

    private fun перечень(проект: String, вид: String): V2Router.Ответ {
        val область = Area.Project(проект)
        val массив = mapper.createArrayNode()
        store.list(область, вид).forEach { сущность ->
            val узел = массив.addObject()
            узел.put("id", сущность.id)
            узел.put("code", сущность.code)
            узел.put("status", сущность.status)
            узел.set<JsonNode>("doc", сущность.doc)
            val носители = links.to(сущность.id, "owns").map { it.from }
            if (носители.isNotEmpty()) {
                val массивНосителей = узел.putArray("owned_by")
                носители.forEach { массивНосителей.add(it) }
            }
            val покрытия = links.to(сущность.id, "covers").map { it.from }
            if (покрытия.isNotEmpty()) {
                val массивПокрытий = узел.putArray("covered_by")
                покрытия.forEach { массивПокрытий.add(it) }
            }
        }
        val ответ = mapper.createObjectNode()
        ответ.set<JsonNode>("items", массив)
        return V2Router.Ответ(200, ответ)
    }


    private fun разобрать(тело: String?): JsonNode =
        if (тело.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(тело)

    private fun автор(тело: JsonNode): String =
        тело.path("author").asText("").ifBlank { "стенд" }

    private fun требуется(query: Map<String, String>, имя: String): String =
        query[имя] ?: throw IllegalArgumentException("нужен параметр «$имя»")
}
