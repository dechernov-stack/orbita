// Режимы разбора по типу входного (шип E, ДОКУМЕНТЫ-1-ВХОДНЫЕ §1, §3):
// ТЗ — полный разбор + оценка против нужд; даташит — параметрический разбор
// в поля анкеты узла; норматив — пункты обязательствами. И сквозные правила
// честности: конфликт фактов — показать оба; новая версия источника —
// пометы «источник обновлён» по изменённым блокам.
//
// Здесь нет вызовов модели: модель отвечает на промпт разбора, а всё, что
// можно посчитать по данным, считается по данным.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.knowledge.api.TorAssessment
import orbita.knowledge.api.TorLine
import orbita.knowledge.api.TorNeed

internal class IntakeModes(
    private val store: EntityStore,
    private val links: LinkRegistry?,
    private val mapper: ObjectMapper,
) {

    /**
     * Конфликты: два факта проекта с тем же субъектом и утверждением, но
     * разным значением. ИИ не выбирает — оба получают ссылку друг на друга,
     * и разрыв точки говорит «решите диспозицией».
     */
    fun markConflicts(область: Area, author: String) {
        val факты = store.list(область, "fact").filter { it.status != "cancelled" }
        val группы = факты.groupBy { ключ(it.doc) }.values.filter { it.size > 1 }
        группы.forEach { группа ->
            val значения = группа.map { it.doc.path("value").asText("").trim().lowercase() + "|" + it.doc.path("unit").asText("") }.toSet()
            if (значения.size < 2) return@forEach
            группа.forEach { ф ->
                val другие = группа.filter { it.code != ф.code && значениеИное(it, ф) }.map { it.code }.sorted()
                val было = ф.doc.path("conflicts").map { it.asText() }.sorted()
                if (другие != было) {
                    val документ = (ф.doc.deepCopy() as ObjectNode)
                    val массив = документ.putArray("conflicts")
                    другие.forEach { массив.add(it) }
                    store.update(ф.id, документ, Provenance(Channel.SERVICE, author))
                }
            }
        }
    }

    private fun ключ(д: JsonNode): String =
        д.path("subject").asText("").trim().lowercase() + "|" + д.path("predicate").asText("").trim().lowercase()

    private fun значениеИное(а: Entity, б: Entity): Boolean =
        а.doc.path("value").asText("").trim().lowercase() != б.doc.path("value").asText("").trim().lowercase() ||
            а.doc.path("unit").asText("") != б.doc.path("unit").asText("")

    /**
     * Новая версия входного: диф канонов БЛОКАМИ. Факт прежней версии, чей
     * блок изменился или исчез, получает `superseded` и помету; сущности,
     * выведенные из него (`derived_from_fact`), — помету «источник
     * обновлён». Диспозиции не трогаются (правило поля знаний).
     *
     * @return сколько фактов помечено
     */
    fun supersede(область: Area, старый: Entity, новый: Entity, author: String): Int {
        val былоБлоки = Canon.of(старый.doc.path("text").asText("")).blocks.associate { it.anchor to it.text.trim() }
        val сталоБлоки = Canon.of(новый.doc.path("text").asText("")).blocks.associate { it.anchor to it.text.trim() }
        val изменились = былоБлоки.filter { (якорь, текст) -> сталоБлоки[якорь] != текст }.keys
        var помечено = 0
        store.list(область, "fact")
            .filter { it.doc.path("material").asText() == старый.code }
            .forEach { факт ->
                val якорь = факт.doc.path("anchor").asText("")
                if (якорь.isBlank() || якорь !in изменились) return@forEach
                val документ = (факт.doc.deepCopy() as ObjectNode)
                    .put("superseded", true)
                    .put("source_updated", новый.code)
                    .put("source_note", "источник обновлён: ${новый.code}, блок $якорь изменён")
                store.update(факт.id, документ, Provenance(Channel.SERVICE, author, source = новый.code))
                помечено += 1
                // Сущности, выведенные из факта, узнают об этом пометой — как
                // «текст устарел» у документа, только в обратную сторону.
                links?.from(факт.id, "derived_from_fact")?.forEach { связь ->
                    val сущность = store.byId(связь.from) ?: return@forEach
                    store.update(
                        сущность.id,
                        (сущность.doc.deepCopy() as ObjectNode).put("source_updated", "${новый.code}: блок $якорь"),
                        Provenance(Channel.SERVICE, author, source = новый.code),
                    )
                }
                links?.to(факт.id, "derived_from_fact")?.forEach { связь ->
                    val сущность = store.byId(связь.from) ?: return@forEach
                    store.update(
                        сущность.id,
                        (сущность.doc.deepCopy() as ObjectNode).put("source_updated", "${новый.code}: блок $якорь"),
                        Provenance(Channel.SERVICE, author, source = новый.code),
                    )
                }
            }
        return помечено
    }

    /**
     * Оценка ТЗ из ответа модели: номера фактов → коды; нужды — кодами
     * проекта. Строки — от требования; блок `needs` — ОТ НУЖДЫ, с дырой
     * словами. Что можно посчитать по данным, считается по данным: нужда
     * без единой строки — непокрыта, что бы ни сказала модель; нужда, у
     * которой строки есть, берёт вердикт и дыру у модели, а без них — по
     * лучшей строке.
     */
    fun assessment(корень: JsonNode, поНомеру: Map<Int, String>, область: Area): ObjectNode? {
        val узел = корень.path("assessment")
        if (!узел.isObject) return null
        val нужды = store.list(область, "need").map { it.code }.toSet()
        val итог = mapper.createObjectNode()
        val строки = итог.putArray("lines")
        узел.path("lines").forEach { л ->
            val код = поНомеру[л.path("fact").asInt(-1)] ?: return@forEach
            val с = строки.addObject()
            с.put("fact", код)
            с.put("requirement", л.path("requirement").asText(""))
            с.put("verdict", л.path("verdict").asText("none"))
            с.put("note", л.path("note").asText(""))
            // Строка несёт слова факта: «частично» про «состав ОГ — не менее 180 МКА»
            // читается как дыра с числом, а не как код.
            с.put("text", имяФакта(область, код).trim())
            val адреса = с.putArray("needs")
            л.path("needs").forEach { н -> if (н.asText() in нужды) адреса.add(н.asText()) }
        }
        val сироты = итог.putArray("orphan_requirements")
        строки.filter { it.path("needs").isEmpty }.forEach { сироты.add(it.path("fact").asText()) }

        // От нужды: вердикт и дыра модели — подсказка; наличие строк — истина.
        val сказаноМоделью = узел.path("needs").filter { it.path("need").asText() in нужды }
            .associateBy { it.path("need").asText() }
        val поНужде = итог.putArray("needs")
        val непокрытые = итог.putArray("uncovered_needs")
        val дыры = итог.putArray("gaps")
        нужды.sorted().forEach { код ->
            val свои = строки.filter { л -> л.path("needs").any { it.asText() == код } }
            val лучшая = when {
                свои.any { it.path("verdict").asText() == "covers" } -> "covered"
                свои.isNotEmpty() -> "partial"
                else -> "uncovered"
            }
            val модель = сказаноМоделью[код]
            val вердикт = when {
                свои.isEmpty() -> "uncovered"
                модель == null -> лучшая
                модель.path("verdict").asText() in setOf("covered", "partial") -> модель.path("verdict").asText()
                else -> лучшая
            }
            val дыра = модель?.path("gap")?.asText("")?.trim().orEmpty().ifBlank {
                when (вердикт) {
                    "uncovered" -> "ни одно требование ТЗ не ведёт к этой нужде"
                    "partial" -> свои.mapNotNull { it.path("note").asText("").ifBlank { null } }.distinct().joinToString("; ")
                        .ifBlank { "требования ТЗ касаются нужды частично — спецификации нет" }
                    else -> ""
                }
            }
            val н = поНужде.addObject().put("need", код).put("verdict", вердикт).put("gap", дыра)
            н.putArray("requirements").also { а -> свои.forEach { а.add(it.path("fact").asText()) } }
            if (вердикт == "uncovered") непокрытые.add(код)
            if (вердикт != "covered") дыры.add("$код — $дыра")
        }
        // Сирота называется САМА — предметом и утверждением факта: «F-0061 —
        // без нужды» не скажет, что это LEO-PNT, а дыра обязана быть названа
        // словами (ШИП-G-ПРИНЯТ: 3 из 4 — дефект атомизации, не оценки).
        строки.filter { it.path("needs").isEmpty }.forEach { л ->
            val почему = л.path("note").asText("").ifBlank { "требование ТЗ без нужды проекта" }
            дыры.add("${л.path("fact").asText()} ${имяФакта(область, л.path("fact").asText())}— $почему")
        }
        return итог
    }

    /** «предмет: утверждение» факта в кавычках, либо пусто, если факта нет. */
    private fun имяФакта(область: Area, код: String): String {
        val факт = store.byCode(область, код) ?: return ""
        val класс = факт.doc.path("entity_class").asText("").ifBlank { null }
        val текст = (факт.doc.path("subject").asText("") + ": " + факт.doc.path("predicate").asText("")).trim(':', ' ').take(90)
        return "«$текст»" + (класс?.let { " [$it]" } ?: "") + " "
    }

    fun assessmentView(узел: JsonNode?): TorAssessment? {
        if (узел == null || !узел.isObject) return null
        return TorAssessment(
            lines = узел.path("lines").map {
                TorLine(
                    it.path("fact").asText(), it.path("requirement").asText(""),
                    it.path("needs").map { н -> н.asText() }, it.path("verdict").asText("none"),
                    it.path("note").asText(""), it.path("text").asText(""),
                )
            },
            uncoveredNeeds = узел.path("uncovered_needs").map { it.asText() },
            orphanRequirements = узел.path("orphan_requirements").map { it.asText() },
            needs = узел.path("needs").map {
                TorNeed(
                    it.path("need").asText(), it.path("verdict").asText("uncovered"), it.path("gap").asText(""),
                    it.path("requirements").map { р -> р.asText() },
                )
            },
            gaps = узел.path("gaps").map { it.asText() },
        )
    }

    fun typeActions(
        область: Area,
        карточка: Entity,
        intent: String,
        факты: List<Pair<Int, Entity>>,
        оценка: JsonNode?,
        действия: ArrayNode,
    ) {
        when (карточка.doc.path("kind").asText("")) {
            "tor" -> torActions(область, оценка, действия)
            "datasheet" -> datasheetActions(область, карточка, intent, факты, действия)
        }
    }

    private fun torActions(область: Area, оценка: JsonNode?, действия: ArrayNode) {
        if (оценка == null) return
        val строки = оценка.path("lines").associateBy { it.path("fact").asText() }
        // От нужды: непокрытая и частично покрытая — обе RFA заказчику, с
        // дырой словами; разница — в тексте, не в наличии запроса.
        оценка.path("needs").filter { it.path("verdict").asText() != "covered" }.forEach { н ->
            val код = н.path("need").asText()
            val нужда = store.byCode(область, код) ?: return@forEach
            val формулировка = нужда.doc.path("statement").asText(код)
            val дыра = н.path("gap").asText("")
            val частично = н.path("verdict").asText() == "partial"
            val д = действия.addObject()
            д.put("kind", "request_data").put("target_kind", "finding").put("scene", "3")
            д.put("title", if (частично) "RFA заказчику: нужда $код покрыта ТЗ частично" else "RFA заказчику: нужда $код не покрыта ТЗ")
            д.put("preview", "появится запрос действия (RFA) заказчику с возвратом в сцену 3: «$формулировка» — $дыра")
            д.putObject("payload")
                .put(
                    "text",
                    if (частично) "RFA заказчику: нужда $код «$формулировка» покрыта ТЗ частично — $дыра"
                    else "RFA заказчику: нужда $код «$формулировка» не покрыта ни одним требованием ТЗ — $дыра",
                )
                .put("returns_to_scene", "3").put("gate", "internal_review").put("kind", "rfa").put("addressee", "заказчик")
            д.putArray("facts").also { а -> н.path("requirements").forEach { а.add(it.asText()) } }
        }
        оценка.path("orphan_requirements").forEach { код ->
            val почему = строки[код.asText()]?.path("note")?.asText("").orEmpty()
            val имя = имяФакта(область, код.asText()).trim()
            val класс = store.byCode(область, код.asText())?.doc?.path("entity_class")?.asText("").orEmpty()
            val что = when (класс) { "service" -> "услуга"; "function" -> "функция"; else -> "требование" }
            val д = действия.addObject()
            д.put("kind", "flag_conflict").put("target_kind", "finding").put("scene", "8")
            д.put("title", "$что ТЗ ${код.asText()} без нужды" + (if (имя.isBlank()) "" else ": $имя"))
            д.put("preview", "появится расхождение (RID) с возвратом в сцену 8: ${что} ТЗ нет нужды — завести нужду или спросить заказчика")
            д.putObject("payload")
                .put("text", "$что ТЗ ${код.asText()} $имя не ведёт ни к одной нужде проекта".replace("  ", " ") + (if (почему.isBlank()) "" else " — $почему"))
                .put("returns_to_scene", "8").put("gate", "internal_review").put("kind", "rid").put("addressee", "заказчик")
            д.putArray("facts").add(код.asText())
        }
    }

    private fun datasheetActions(
        область: Area,
        карточка: Entity,
        intent: String,
        факты: List<Pair<Int, Entity>>,
        действия: ArrayNode,
    ) {
        val узел = узелЗадания(область, intent)
        val кандидат = узел == null && intent.contains("рассмотрим как базовую")
        val кодУзла = узел?.code ?: if (кандидат) "CAND-" + карточка.code else null
        if (кандидат) {
            val д = действия.addObject()
            д.put("kind", "create_entity").put("target_kind", "component").put("scene", "7")
            д.put("title", "завести узел кандидатом базового решения")
            д.put("preview", "появится узел «${карточка.doc.path("name").asText()}» кандидатом (решение «кандидат», не выбор)")
            д.putObject("payload")
                .put("code", кодУзла).put("name", карточка.doc.path("name").asText(карточка.code))
                .put("kind", "subsystem").put("level", 2).put("nature", "node").put("candidate", true)
                .put("decision", "кандидат базового решения по материалу ${карточка.code}")
            д.putArray("facts").also { а -> факты.forEach { (_, ф) -> а.add(ф.code) } }
        }
        val сПараметром = факты.filter { (_, ф) -> ф.doc.path("param_key").asText("").isNotBlank() }
        if (кодУзла != null) {
            сПараметром.forEach { (_, ф) ->
                val ключ = ф.doc.path("param_key").asText()
                val д = действия.addObject()
                д.put("kind", "update_params").put("target_kind", "parameter").put("scene", "7")
                д.put("title", "параметр «$ключ» узла $кодУзла из даташита")
                д.put("preview", "появится параметр $кодУзла.$ключ = ${ф.doc.path("value").asText()} ${ф.doc.path("unit").asText("")} с якорем ${ф.doc.path("anchor").asText()}")
                д.putObject("payload")
                    .put("target", кодУзла).put("key", ключ)
                    .put("value", ф.doc.path("value").asText()).put("unit", ф.doc.path("unit").asText(""))
                    .put("origin", "datasheet").put("anchor", ф.doc.path("anchor").asText(""))
                    .put("material", карточка.code).put("maturity_class", "off_the_shelf")
                д.putArray("facts").add(ф.code)
            }
        }
        // Сверка с рамками проекта: величина даташита против ограничений с полем bound.
        val рамки = store.list(область, "constraint").filter { it.doc.path("bound").isObject }
        сПараметром.forEach { (_, ф) ->
            val ключ = ф.doc.path("param_key").asText()
            val значение = ф.doc.path("value").asText("").replace(",", ".").toDoubleOrNull() ?: return@forEach
            рамки.forEach { р ->
                val граница = р.doc.path("bound")
                if (!родственны(граница.path("key").asText(), ключ)) return@forEach
                if (граница.path("unit").asText("") != ф.doc.path("unit").asText("")) return@forEach
                val предел = граница.path("value").asDouble()
                val нарушено = when (граница.path("op").asText("le")) {
                    "le" -> значение > предел
                    "lt" -> значение >= предел
                    "ge" -> значение < предел
                    "gt" -> значение <= предел
                    else -> false
                }
                if (!нарушено) return@forEach
                val д = действия.addObject()
                д.put("kind", "create_risk").put("target_kind", "risk").put("scene", "11")
                д.put("title", "конфликт с ${р.code}: ${ключ} = $значение ${граница.path("unit").asText()}")
                д.put("preview", "появится риск: ${ф.doc.path("subject").asText()} — $ключ $значение ${граница.path("unit").asText()} против ${р.code} (${граница.path("op").asText()} $предел); предложение — сузить конфигурацию или пересмотреть ${р.code} с основанием")
                д.putObject("payload")
                    .put("statement", "${ф.doc.path("subject").asText()}: $ключ = $значение ${граница.path("unit").asText()} выходит за ${р.code} «${р.doc.path("text").asText()}»")
                    .put("category", "техническое").put("probability", 4).put("impact", 4)
                    .put("strategy", "mitigate").put("owner", "руководитель проекта").put("due_point", "MCR")
                    .put("measures", "сузить конфигурацию до рамки либо пересмотреть ${р.code} с основанием")
                    .put("constraint", р.code)
                д.putArray("facts").add(ф.code)
            }
        }
        // Недостающие поля анкеты — запрос данных поставщику.
        if (кодУзла != null) {
            val анкета = анкетаУзла(узел)
            val есть = сПараметром.map { (_, ф) -> ф.doc.path("param_key").asText() }.toSet()
            val нет = анкета.filter { it !in есть }
            if (нет.isNotEmpty()) {
                val д = действия.addObject()
                д.put("kind", "request_data").put("target_kind", "data_request").put("scene", "7")
                д.put("title", "запросить у поставщика ${нет.size} параметров анкеты")
                д.put("preview", "появится запрос данных по анкете узла $кодУзла: " + нет.joinToString(", "))
                val содержимое = д.putObject("payload")
                содержимое.put("questionnaire", узел?.doc?.path("template_ref")?.asText("")?.ifBlank { null } ?: "platform")
                содержимое.put("target", кодУзла)
                val поля = содержимое.putArray("fields")
                нет.forEach { поля.addObject().put("key", it).put("filled", false) }
                д.putArray("facts").also { а -> сПараметром.forEach { (_, ф) -> а.add(ф.code) } }
            }
        }
    }

    /** Узел, названный в задании кодом или именем: «обнови параметры SC-PLT». */
    fun узелЗадания(область: Area, intent: String): Entity? {
        val узлы = store.list(область, "component")
        return узлы.firstOrNull { intent.contains(it.code, ignoreCase = true) }
            ?: узлы.firstOrNull { у ->
                val имя = у.doc.path("name").asText("")
                имя.length >= 4 && intent.contains(имя, ignoreCase = true)
            }
    }

    /**
     * Анкета узла: ключи параметров лестницы типового компонента (полка
     * `component_template_shelf`) по `template_ref` узла; без ссылки —
     * ключи типовой платформы. Ключи, а не поля: даташит заполняет их.
     */
    fun анкетаУзла(узел: Entity?): List<String> {
        val полка = store.list(Area.Library, "component_template_shelf").maxByOrNull { it.version } ?: return emptyList()
        val шаблоны = полка.doc.path("templates")
        val ссылка = узел?.doc?.path("template_ref")?.asText("")?.ifBlank { null }
        val шаблон = шаблоны.firstOrNull { it.path("code").asText() == ссылка }
            ?: шаблоны.firstOrNull { it.path("code").asText() == "TC-SC" }
            ?: return emptyList()
        return шаблон.path("ladder").properties().flatMap { (_, ступень) ->
            ступень.path("params").map { it.path("key").asText() }
        }.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Ключ рамки против ключа анкеты — ЯВНЫМ списком родственников: рамка
     * «средняя мощность ≤ 60 Вт» не про пик, и сравнивать её с power_peak
     * значило бы завести ложный риск (поймано живым прогоном на E1).
     */
    private fun родственны(рамка: String, ключ: String): Boolean =
        рамка.isNotBlank() && ключ in (РОДСТВЕННИКИ[рамка] ?: setOf(рамка))

    private companion object {
        val РОДСТВЕННИКИ: Map<String, Set<String>> = mapOf(
            "mass" to setOf("mass", "mass_dry", "mass_wet"),
            "power" to setOf("power", "power_avg"),
            "power_peak" to setOf("power_peak"),
            "altitude" to setOf("altitude", "orbit_altitude"),
            "lifetime" to setOf("lifetime"),
        )
    }

    /** Ключи анкеты узла для промпта: «ключ · единица». */
    fun анкетаДляПромпта(область: Area, intent: String): List<Pair<String, String>> {
        val узел = узелЗадания(область, intent)
        val полка = store.list(Area.Library, "component_template_shelf").maxByOrNull { it.version } ?: return emptyList()
        val шаблоны = полка.doc.path("templates")
        val ссылка = узел?.doc?.path("template_ref")?.asText("")?.ifBlank { null }
        val шаблон = шаблоны.firstOrNull { it.path("code").asText() == ссылка }
            ?: шаблоны.firstOrNull { it.path("code").asText() == "TC-SC" } ?: return emptyList()
        return шаблон.path("ladder").properties().flatMap { (_, ступень) ->
            ступень.path("params").map { it.path("key").asText() to it.path("unit").asText("") }
        }.distinctBy { it.first }
    }
}
