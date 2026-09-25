// Документ → постановка-кандидат ОДНИМ вызовом (РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ, 15.09).
//
// Заменяет связку «атомизация по предикатам → формирование по фильтрам».
// Диагноз владельца: оба шага фильтровали лексически — вид факта и предикат
// из списка, — и очевидное («Минтранс России — заказчик, его интерес —
// требования к телематике») отбивалось, потому что факт оказался вида
// `assessment`, а правило требовало `relation`.
//
// Новый порядок: модель читает документ как инженер и отдаёт понятия сразу,
// каждое с ЦИТАТОЙ и якорем. Факт остаётся, но меняет роль — он след
// предложения (нормализованное утверждение + якорь), а не предварительный
// этап-фильтр; вид и предикат факта система выводит сама, производной
// классификацией для поиска, связей и противоречий.
//
// Ворота — только машинно проверяемые (истина, `gates.machine_checkable_only`):
// цитата дословно есть в каноне · якорь указывает на блок с цитатой · ссылки
// разрешаются · обязательные поля заполнены. Ворота НЕ судят о виде факта, не
// требуют предиката из списка и не отбивают содержательное предложение по
// форме.
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.ai.api.AiService
import orbita.ai.api.Answer
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.Verdict
import orbita.knowledge.api.SourceMark
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.CanonBlock
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.Intake
import orbita.knowledge.schema.GeneratedOntology

/**
 * Прочитанное из документа: сколько понятий какого рода получилось, какие
 * следы-факты за ними стоят и что отбито воротами — поимённо.
 */
data class Прочитанное(
    val material: String,
    val proposals: List<ЧитанноеПонятие>,
    val facts: List<String>,
    val refused: List<String>,
    val note: String,
)

/** Понятие, прочитанное из документа: поля, цитата, якорь и след-факт. */
data class ЧитанноеПонятие(
    val localId: String,
    val concept: String,
    val payload: Map<String, String>,
    val quote: String,
    val anchor: String,
    val confidence: Double?,
    val fact: String?,
)

class DocumentReader(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Прочитать материал и положить прочитанное запуском постановки: тот же
     * экран, тот же акцепт. Вердикт каждому предложению выносит СВЕРКА при
     * принятии (истина, `verdict_rule`), поэтому здесь все они «новые».
     */
    fun readInto(project: String, material: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        val итог = read(project, material, author)
        val предложения = итог.proposals.mapNotNull { понятие ->
            val след = понятие.fact ?: return@mapNotNull null
            val факт = store.byCode(Area.Project(project), след) ?: return@mapNotNull null
            FormationProposal(
                concept = понятие.concept,
                payload = понятие.payload,
                basis = listOf(
                    Basis(
                        factId = след,
                        material = material,
                        anchor = понятие.anchor,
                        rank = факт.doc.path("rank").asText("").ifBlank { null },
                        mark = SourceMark.entries.firstOrNull { it.name == факт.doc.path("mark").asText("") },
                    ),
                ),
                verdict = Verdict.NEW,
                sourceMark = SourceMark.entries.firstOrNull { it.name == факт.doc.path("mark").asText("") }
                    ?: SourceMark.И,
                confidence = понятие.confidence,
                // Незакрытая обязательная связь называется СРАЗУ: по ней экран
                // отделяет «требующее внимания» от того, что идёт пакетом.
                missing = GeneratedOntology.byCode[понятие.concept]
                    ?.let { FormationRules.нехватка(it, понятие.payload) }.orEmpty(),
            )
        }
        return synthesizer.record(
            project, author, intake.fingerprint(project, material), итог.note, предложения, итог.refused,
        )
    }

    /** Прочитать материал живым вызовом. Повтор той же версии — из журнала. */
    fun read(project: String, material: String, author: String): Прочитанное {
        val роль = роль(project, material)
        // Замысел — не массив, а объект; у устава он обязателен так же, как
        // стороны: без требования модель его опускала (216, второе чтение).
        val обязательные = массивыРоли(роль) +
            listOfNotNull(ЗАМЫСЕЛ.takeIf { роль in GeneratedOntology.of(ЗАМЫСЕЛ).allowedRoles })
        val промпт = prepare(project, material)
        val ответ = service.ask(
            project, KIND, промпт,
            maxTokens = БЮДЖЕТ,
            schema = AnswerSchemas.чтение(mapper, обязательные),
        )
        return apply(project, material, author, ответ, prompt = промпт)
    }

    /** Промпт чтения: призма устава · роль документа · карта разделов · канон. */
    fun prepare(project: String, material: String): String {
        val область = Area.Project(project)
        val карточка = store.byCode(область, material)
            ?: error("материала «$material» нет в проекте")
        val блоки = intake.canon(project, material)
        val роль = роль(карточка)
        return """
Ты — системный инженер. Прочитай документ и ВЫПИШИ ИЗ НЕГО ПОСТАНОВКУ проекта:
кто участвует, чего им не хватает, куда идёт проект, какие рамки и сроки.
Ты не изобретаешь ничего: всё, что пишешь, есть в тексте — и ты приводишь цитату.

${призма(область, роль)}

## Документ
Наименование: «${карточка.doc.path("name").asText(material)}»
Роль: ${рольСловами(роль)}
Ранг доверия источника: ${ранг(карточка)}

Разделы канона (якорь · тип · заголовок):
${карта(блоки)}

Текст по разделам:
${текст(блоки)}

## Что выписать
${чтоВыписать(роль)}

## Правила
1. ЦИТАТА ОБЯЗАТЕЛЬНА у каждого пункта: дословный фрагмент из текста выше
   (10–200 знаков) и якорь того блока, где он стоит. Нет цитаты — не выписывай.
   Пересказ цитатой не считается: система сверяет её с текстом дословно.
2. Ничего не додумывай. Поле не названо в тексте — оставь пустым.
3. Числа — с единицей, как в тексте; диапазон — как диапазон.
4. Одна мысль — один пункт. Абзац с тремя обязательствами даёт три рамки.
5. Нужды ссылки на сторону НЕ несут: они лежат внутри своей стороны полем
   `needs`, и носитель у них тем самым уже назван.
6. Не оценивай и не решай — ты предлагаешь, решает инженер.
${канонНаписания()}

## Перед ответом проверь
- у каждого пункта есть цитата и якорь;
- у КАЖДОЙ стороны `needs` не пуст;
- каждая общая нужда прошла по ВСЕМ сторонам, чей интерес говорит о том же
  предмете, и у каждой записана отдельно, теми же словами;
- интерес стороны разобран на столько записей, сколько в нём притязаний;
- нужд вышло заметно больше, чем сторон; вышло поровну — значит повторы слиты;
- стороны из ТАБЛИЦЫ выписаны все, построчно, без пропусков;
- издатель документа не попал в стороны;
${самопроверкаПоРоли(роль)}
Не прошедшее — убери, а не «исправь смыслом».
""".trimIndent()
    }

    /**
     * Ответ модели → следы-факты и понятия. Ворота стоят ЗДЕСЬ, на нашей
     * стороне: модель может ошибиться, система — нет.
     */
    fun apply(project: String, material: String, author: String, answer: Answer, prompt: String? = null): Прочитанное {
        val блоки = intake.canon(project, material).associateBy { it.anchor }
        val корень = runCatching { mapper.readTree(answer.text) }.getOrNull()?.let { развернуть(it) }
            ?: return Прочитанное(material, emptyList(), emptyList(), emptyList(), "ответ службы не разобран")

        val отбито = mutableListOf<String>()
        val пункты = mutableListOf<Пункт>()
        // Роль документа стережёт и ОТВЕТ, а не только промпт: обстановка целей
        // и сервисов не порождает (истина `document_roles`), и цель, которую
        // модель всё же выписала из «Приморья», в проект не идёт — она названа
        // отбитой поимённо, а не заведена молча.
        val роль = роль(project, material)
        val можно = понятияРоли(роль)
        ПОРЯДОК.forEach { (массив, понятие) ->
            if (понятие != ТОЛЬКО_ФАКТ && понятие !in можно) {
                val сколько = корень.path(массив).size()
                if (сколько > 0) отбито += "$массив: роль документа «$роль» этого понятия не порождает — пропущено $сколько (${словоПонятия(понятие)})"
                return@forEach
            }
            корень.path(массив).forEachIndexed { номер, узел ->
                val имя = узел.path("id").asText("").ifBlank { "$массив#${номер + 1}" }
                val беда = воротаПункта(узел, блоки)
                if (беда != null) {
                    отбито += "$имя ($понятие): $беда"
                    return@forEachIndexed
                }
                пункты += Пункт(имя, понятие, узел)
                // Нужды приходят ВНУТРИ стороны: у нужды один носитель, и
                // общая нужда документа принадлежит каждой стороне отдельной
                // записью. Разворачиваем их здесь, ссылкой на саму сторону, —
                // дальше они идут обычными пунктами со своей цитатой и якорем.
                if (понятие == "stakeholder" && "need" in можно) {
                    узел.path("needs").forEachIndexed { н, нужда ->
                        // Свой `id` у нужды ОБЯЗАТЕЛЕН (истина, `answer_shape`:
                        // «p1.n2 — на него ссылаются цели и сервисы; ответ без
                        // id у нужды недействителен»). Без него нужда отбивается
                        // поимённо — сервис иначе никогда не найдёт, что закрывает.
                        val свойId = нужда.path("id").asText("").trim()
                        val имяНужды = свойId.ifBlank { "$имя.n${н + 1}" }
                        if (свойId.isBlank()) {
                            отбито += "$имяНужды (need): у нужды нет своего id — ответ без него недействителен"
                            return@forEachIndexed
                        }
                        val бедаНужды = воротаПункта(нужда, блоки)
                        if (бедаНужды != null) {
                            отбито += "$имяНужды (need): $бедаНужды"
                            return@forEachIndexed
                        }
                        пункты += Пункт(имяНужды, "need", сНосителем(нужда, узел.path("name").asText(имя)))
                    }
                }
            }
        }
        // Замысел приходит объектом, а не списком: он один на проект (истина
        // `intent.note` — «один на проект; правится только решением»).
        корень.path(ЗАМЫСЕЛ).takeIf { it.isObject && !it.isEmpty }?.let { узел ->
            val беда = воротаПункта(узел, блоки)
            if (беда != null) отбито += "$ЗАМЫСЕЛ ($ЗАМЫСЕЛ): $беда" else пункты += Пункт("i1", ЗАМЫСЕЛ, узел)
        }

        // Ссылки внутри ответа разрешаются ДО фактов: нужда, назвавшая сторону
        // `ref`-ом, обязана получить её имя — иначе связь «owns→stakeholder»
        // не закроется ничем (прогон владельца 15.09).
        val имена = пункты.associate { it.localId to естественноеИмя(it.узел) }

        val следы = mapper.createObjectNode()
        val массивФактов = следы.putArray("facts")
        пункты.forEach { массивФактов.add(след(it)) }
        // Следы идут прогоном разбора с версией промпта чтения: переразбор
        // новой версией помечает прежние следы устаревшими (§3 шипа 4).
        val принято = intake.putFacts(
            project, material, mapper.writeValueAsString(следы), author,
            promptVersion = prompt?.let { п ->
                java.security.MessageDigest.getInstance("SHA-256").digest(п.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
            },
        )
        отбито += принято.refused

        // След ищется среди принятых ЭТИМ разбором, а если документ читается
        // повторно — среди уже лежащих в поле: повтор не заводит фактов заново,
        // и без этого второе чтение теряло основания у всех понятий разом.
        val свободные = принято.accepted.toMutableList()
        val вПоле = store.list(Area.Project(project), "fact")
            .filter { it.status !in СНЯТЫЕ }
            .toMutableList()
        val понятия = пункты.withIndex().filterNot { it.value.concept == ТОЛЬКО_ФАКТ }.map { (номер, пункт) ->
            // Повтор приём называет сам, по номеру факта во входе: сравнение
            // строк здесь лишь запасной путь, и на 216 он терял 16 из 117.
            val след = свободные.firstOrNull { совпало(it, пункт) }?.also { свободные.remove(it) }?.id
                ?: принято.repeated[номер]
                ?: вПоле.firstOrNull { совпалоСЗаписью(it, пункт) }?.also { вПоле.remove(it) }?.code
            ЧитанноеПонятие(
                localId = пункт.localId,
                concept = пункт.concept,
                payload = поля(пункт, имена),
                quote = пункт.узел.path("quote").asText(""),
                anchor = пункт.узел.path("anchor").asText(""),
                confidence = пункт.узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
                fact = след,
            )
        }
        val безСледа = понятия.count { it.fact == null }
        val чужиеЦели = пункты.count { it.concept == ТОЛЬКО_ФАКТ }
        val счёт = понятия.groupingBy { it.concept }.eachCount().entries
            .joinToString(" · ") { (понятие, число) -> "${словоПонятия(понятие)} $число" }
        return Прочитанное(
            material = material,
            proposals = понятия,
            facts = понятия.mapNotNull { it.fact },
            refused = отбито,
            note = buildString {
                append("прочитано: ").append(счёт.ifBlank { "ни одного понятия" })
                if (чужиеЦели > 0) append("; чужих целей фактами ").append(чужиеЦели)
                if (отбито.isNotEmpty()) append("; отбито воротами ").append(отбито.size)
                if (безСледа > 0) append("; без следа-факта ").append(безСледа)
            },
        )
    }

    /**
     * Снять обёртку вызова инструмента.
     *
     * Ответ приходит входом инструмента, и провайдер вправе завернуть его в
     * «{"parameters": {…}}» или «{"input": {…}}» — на живом чтении 16.09 это
     * случилось, и весь ответ на девяносто пять пунктов прочитался как «ни
     * одного понятия». Разворачиваем ОДИН слой и только тогда, когда снаружи
     * нет ни одного знакомого массива: чужого содержимого это не трогает.
     */
    private fun развернуть(узел: JsonNode): JsonNode {
        if (ПОРЯДОК.any { (массив, _) -> узел.path(массив).isArray }) return узел
        val один = узел.properties().singleOrNull()?.takeIf { it.value.isObject } ?: return узел
        return if (ПОРЯДОК.any { (массив, _) -> один.value.path(массив).isArray }) один.value else узел
    }

    // --- ворота -------------------------------------------------------------

    /**
     * Ворота пункта — ровно те, что назвала истина, и ни одним больше.
     * Возвращает причину отказа словами либо null.
     */
    private fun воротаПункта(узел: JsonNode, блоки: Map<String, CanonBlock>): String? {
        val цитата = узел.path("quote").asText("").trim()
        if (цитата.isBlank()) return "цитаты нет — пункт без цитаты не выписывается"
        val якорь = узел.path("anchor").asText("").trim()
        val блок = блоки[якорь] ?: return "якоря «$якорь» в каноне нет"
        if (!содержит(блок.text, цитата) && блоки.values.none { содержит(it.text, цитата) }) {
            return "цитаты «${цитата.take(60)}» нет в тексте документа дословно"
        }
        return null
    }

    /**
     * Дословность с поправкой на пробелы и регистр: перенос строки и двойной
     * пробел цитату не подделывают, а пересказ не пройдёт и так.
     */
    private fun содержит(текст: String, цитата: String): Boolean =
        сжать(текст).contains(сжать(цитата))

    private fun сжать(текст: String): String =
        текст.replace(Regex("\\s+"), " ").trim().lowercase()

    // --- след-факт ----------------------------------------------------------

    private data class Пункт(val localId: String, val concept: String, val узел: JsonNode)

    /**
     * Нужда со своей стороной: носитель у неё ровно один — тот, внутри кого
     * она лежала. Кладём ИМЯ, а не ссылку: по ссылке след-факт нужды получал
     * субъектом строку «сторона проекта» — все нужды оказывались про одну
     * несуществующую сторону, и происхождение терялось (сверка 16.09).
     */
    private fun сНосителем(нужда: JsonNode, имяСтороны: String): JsonNode {
        val узел = нужда.deepCopy<JsonNode>() as ObjectNode
        узел.put("stakeholder", имяСтороны)
        return узел
    }

    /**
     * След предложения: нормализованное утверждение с якорем.
     *
     * Вид и предикат факта — ПРОИЗВОДНАЯ классификация (истина, `reading_mode`):
     * они нужны поиску, связям и противоречиям, а воротами больше не служат.
     * Поэтому выводятся из понятия, а не ищутся в тексте.
     */
    private fun след(пункт: Пункт): ObjectNode {
        val узел = mapper.createObjectNode()
        val поля = пункт.узел
        узел.put("kind", ВИД_ФАКТА[пункт.concept] ?: "framing")
        узел.put("subject", субъект(пункт))
        узел.put("predicate", ПРЕДИКАТ[пункт.concept] ?: "назван документом")
        узел.put("value", утверждение(пункт))
        // Единица идёт ОТДЕЛЬНЫМ полем от модели: величина без единицы фактом
        // не становится (правило честности §6.1), а выковыривать единицу из
        // строки запрещено (истина схем, thresholds_rule).
        поля.path("unit").asText("").trim().ifBlank { null }?.let { узел.put("unit", it) }
        // Цитата ложится В ФАКТ полем: истина схем 15.09 сделала `quote`
        // обязательным («дословный фрагмент источника, сверяется с каноном»).
        // След предложения теперь несёт не только якорь, но и сам текст — по
        // нему человек видит основание, не открывая документ.
        узел.put("quote", поля.path("quote").asText(""))
        узел.put("anchor", поля.path("anchor").asText(""))
        узел.put("mark", если(пункт.concept == "assumption", "П", "И"))
        // Номер источника из списка документа (И1 · В10): истина схем завела
        // под него отдельное поле 17.09 — метка одной буквой номер теряла.
        поля.path("mark_no").asText("").trim().ifBlank { null }?.let {
            узел.put("mark_no", it)
        }
        поля.path("confidence").takeIf { it.isNumber }?.let { узел.put("confidence", it.asDouble()) }
        return узел
    }

    private fun если(условие: Boolean, да: String, нет: String): String = if (условие) да else нет

    private fun субъект(пункт: Пункт): String = when (пункт.concept) {
        "stakeholder" -> пункт.узел.path("name").asText("")
        "need" -> ссылкаТекстом(пункт.узел.path("stakeholder")).ifBlank { "сторона проекта" }
        "opportunity", "external_target" -> пункт.узел.path("owner").asText("")
        "service" -> "система"
        else -> "проект"
    }.ifBlank { "проект" }

    private fun утверждение(пункт: Пункт): String = ИМЕНА
        .firstNotNullOfOrNull { поле -> пункт.узел.path(поле).asText("").trim().ifBlank { null } }
        ?: пункт.узел.path("quote").asText("")

    /** То же сравнение для записи поля: повторное чтение находит прежний след. */
    private fun совпалоСЗаписью(запись: Entity, пункт: Пункт): Boolean =
        запись.doc.path("anchor").asText("") == пункт.узел.path("anchor").asText("") &&
            запись.doc.path("subject").asText("") == субъект(пункт) &&
            запись.doc.path("value").asText("") == утверждение(пункт)

    /** След ли это того пункта: якорь, субъект и утверждение совпали. */
    private fun совпало(факт: orbita.knowledge.api.Fact, пункт: Пункт): Boolean =
        факт.anchor == пункт.узел.path("anchor").asText("") &&
            факт.subject == субъект(пункт) &&
            факт.value == утверждение(пункт)

    // --- поля понятия -------------------------------------------------------

    /** Поля понятия из пункта: служебное (цитата, якорь, id) наружу не идёт. */
    private fun поля(пункт: Пункт, имена: Map<String, String>): Map<String, String> {
        val поля = linkedMapOf<String, String>()
        val величины = GeneratedKinds.byCode[видПонятия(пункт.concept)]?.measures.orEmpty()
        // Уверенность у применимости — поле ИСТИНЫ (`opportunity.confidence`,
        // обязательное), а не только служебная оценка предложения: остаётся в полях.
        val обязательные = GeneratedKinds.byCode[видПонятия(пункт.concept)]?.requiredFields.orEmpty()
        пункт.узел.properties().forEach { (имя, значение) ->
            if ((имя in СЛУЖЕБНЫЕ && !(имя == "confidence" && "confidence" in обязательные)) || имя in ВЕЛИЧИНА || имя in величины) return@forEach
            val текст = when {
                значение.isValueNode -> значение.asText("")
                ссылка(значение) -> ссылкаТекстом(значение, имена)
                // Объект, который не ссылка, — граница рамки, диапазон этапа:
                // едет как есть (истина, `normalization.measures` — «любая
                // величина объектом»). До 17.09 такие объекты терялись молча.
                else -> значение.toString()
            }
            if (текст.isNotBlank()) поля[имя] = текст
        }
        величины.forEach { поле -> величина(пункт, поле)?.let { поля[поле] = it } }
        return поля.mapValues { (_, значение) -> штрихКаноном(значение) }
    }

    /** Ссылка ли это: объект с `code`/`ref` либо перечень таких. */
    private fun ссылка(узел: JsonNode): Boolean = when {
        узел.isArray -> узел.all { it.isTextual || ссылка(it) }
        узел.isObject -> узел.has("code") || узел.has("ref")
        else -> false
    }

    /**
     * Штрих — канон ′ (U+2032); апостроф и акцент нормализуются (истина,
     * `normalization.prime`). Записка и эталон владельца пишут «A'», истина
     * схем — «A′», и без приведения класс обслуживания не совпадает ни с чем.
     */
    private fun штрихКаноном(текст: String): String =
        if (АПОСТРОФЫ.none { it in текст }) текст
        else АПОСТРОФЫ.fold(текст) { итог, знак -> итог.replace(знак, ШТРИХ) }

    /**
     * Величина понятия — ПАРОЙ «значение · единица» под именем, которое назвала
     * истина схем (`measure{…}`): у цели это `measure`, у сервиса
     * `target_measure`. Модель отдаёт их двумя плоскими полями, и пока пара не
     * складывалась, десять целей отбивались «величина без единицы — не факт»
     * (живое чтение записки 16.09). Имя поля берётся у истины, не у кода.
     */
    private fun величина(пункт: Пункт, поле: String): String? {
        // Величина приходит либо своим объектом под именем поля, либо плоскими
        // полями рядом — истина велит объект, но модель обе формы даёт.
        val источник = пункт.узел.path(поле).takeIf { it.isObject } ?: пункт.узел
        val единица = источник.path("unit").asText("").trim()
        if (единица.isBlank()) return null
        val пара = mapper.createObjectNode()
        val значение = источник.path("value").asText("").trim()
        val снизу = источник.path("min").asText("").trim()
        val сверху = источник.path("max").asText("").trim()
        when {
            значение.isNotBlank() -> пара.put("value", значение)
            снизу.isNotBlank() || сверху.isNotBlank() -> {
                if (снизу.isNotBlank()) пара.put("min", снизу)
                if (сверху.isNotBlank()) пара.put("max", сверху)
            }
            else -> return null
        }
        пара.put("unit", единица)
        return пара.toString()
    }

    /** Вид, которым понятие становится: его называет сама онтология. */
    private fun видПонятия(понятие: String): String =
        GeneratedOntology.byCode[понятие]?.let { it.targetKindCode ?: it.code } ?: понятие

    /**
     * Ссылка словами: `{"code":"SK-0001"}` — кодом принятого, `{"ref":"s1"}` —
     * именем пункта того же ответа. Перечень ссылок — через разделитель среза.
     */
    private fun ссылкаТекстом(узел: JsonNode, имена: Map<String, String> = emptyMap()): String {
        if (узел.isArray) {
            return узел.joinToString(" · ") { ссылкаТекстом(it, имена) }.trim(' ', '·').trim()
        }
        if (узел.isTextual) return узел.asText().trim()
        if (!узел.isObject) return ""
        узел.path("code").asText("").trim().ifBlank { null }?.let { return it }
        val ref = узел.path("ref").asText("").trim()
        return if (ref.isBlank()) "" else имена[ref] ?: ""
    }

    private fun естественноеИмя(узел: JsonNode): String = ИМЕНА
        .firstNotNullOfOrNull { поле -> узел.path(поле).asText("").trim().ifBlank { null } }
        .orEmpty()

    // --- промпт -------------------------------------------------------------

    /**
     * Призма устава: точка отсчёта для чтения ЛЮБОГО другого документа
     * (истина, `charter_prism`). Грани берутся у принятого в проекте — того,
     * что уже стало постановкой, а не у текста устава заново.
     */
    private fun призма(область: Area, роль: String): String {
        if (роль == УСТАВ) {
            return "## Проект\nЭто устав проекта — из него выписывается постановка целиком."
        }
        val цели = store.list(область, "goal").take(8).joinToString("\n") { цель ->
            "  · ${цель.doc.path("statement").asText("")} — " +
                "${цель.doc.path("measure").path("value").asText("")} ${цель.doc.path("measure").path("unit").asText("")}" +
                ", ${цель.doc.path("year").asText("")}"
        }
        val рамки = store.list(область, "constraint").take(12).joinToString("\n") { рамка ->
            "  · ${рамка.code}: ${рамка.doc.path("statement").asText("").ifBlank { рамка.doc.path("text").asText("") }}"
        }
        val замысел = store.list(область, "intent").firstOrNull()?.doc
        return """
## Проект и призма
Мы строим: ${замысел?.path("statement")?.asText("")?.ifBlank { null } ?: "(класс системы в проекте ещё не назван)"}
Цели проекта уже приняты:
${цели.ifBlank { "  (целей ещё нет)" }}
Рамки проекта — постановка обязана быть им не противна:
${рамки.ifBlank { "  (рамок ещё нет)" }}

Читая этот документ, спрашивай себя: что здесь ПРО НАШУ СИСТЕМУ — подтверждает,
уточняет, спорит, добавляет? Чужие цели и нужды выписывай как ЧУЖИЕ, с указанием,
чьи они.
""".trimIndent()
    }

    private fun карта(блоки: List<CanonBlock>): String {
        val разделы = linkedMapOf<String, MutableList<CanonBlock>>()
        блоки.forEach { разделы.getOrPut(it.anchor.substringBefore('#')) { mutableListOf() } += it }
        return разделы.entries.joinToString("\n") { (якорь, тело) ->
            val заголовок = тело.firstOrNull { it.kind == "heading" }?.text.orEmpty()
            val строки = тело.filter { it.kind != "heading" }.map { it.text }
            val таблица = строки.filter { it.startsWith("|") && it.endsWith("|") }
            val тип = when {
                таблица.size >= 2 -> "таблица"
                строки.count { it.startsWith("- ") || it.startsWith("* ") } * 2 > строки.size &&
                    строки.count { it.startsWith("- ") || it.startsWith("* ") } >= 2 -> "перечень"
                else -> "проза"
            }
            val колонки = if (тип == "таблица") {
                " · колонки: " + таблица.first().trim('|').split('|').joinToString(", ") { it.trim() }
            } else {
                ""
            }
            "  $якорь · $тип · «$заголовок»$колонки"
        }
    }

    private fun текст(блоки: List<CanonBlock>): String {
        val sb = StringBuilder()
        for (блок in блоки) {
            val строка = "[${блок.anchor}] ${блок.text}\n"
            if (sb.length + строка.length > ПОТОЛОК_ТЕКСТА) break
            sb.append(строка)
        }
        return sb.toString()
    }

    /**
     * Что выписывать — по роли документа (истина, `document_roles` и
     * `allowed_roles` понятий). Норматив сторон и целей не порождает, обстановка
     * целей и сервисов не порождает, устав — единственный источник целеполагания.
     */
    private fun чтоВыписать(роль: String): String {
        val можно = понятияРоли(роль)
        return buildString {
            ЧТО_ВЫПИСАТЬ.forEach { (код, текст) ->
                if (код !in можно) return@forEach
                append(текст)
                // Откуда понятие берётся и из чего НЕ образуется — словами
                // ИСТИНЫ, а не моей прозой: «нужды берутся из перечня общих
                // нужд · интереса каждой стороны · прямых формулировок
                // нехватки» — это `where_to_look` владельца, и меняется оно
                // правкой истины, а не правкой кода.
                val понятие = GeneratedOntology.byCode[код]
                понятие?.whereToLook?.let { append("\nГде искать: ").append(it).append(".") }
                // Подсказка раздела — тоже истина (`where_in_document`): «§2.1
                // общие нужды → привязка к сторонам по субъекту/смыслу». Без
                // неё модель не знает, ЧЕЙ раздел читать.
                понятие?.whereInDocument?.takeIf { it.isNotEmpty() }?.let {
                    append("\nГде в документе: ").append(it.joinToString("; ")).append(".")
                }
                понятие?.from?.let { append("\nОткуда: ").append(it).append(".") }
                понятие?.fromHint?.let { append("\nГде искать в тексте: ").append(it).append(".") }
                // Правило раздачи общей нужды — истина владельца, не догадка
                // кода: кому общая нужда достаётся, а кому нет.
                понятие?.distributionRule?.let { append("\nКак раздавать: ").append(it).append(".") }
                понятие?.answerShape?.let { append("\nФорма ответа: ").append(it).append(".") }
                понятие?.notFrom?.takeIf { it.isNotEmpty() }?.let {
                    append("\nНе ").append(словоПонятия(код).trimEnd('ы', 'и')).append(": ")
                    append(it.joinToString("; ")).append(".")
                }
                append("\n\n")
            }
            // Понятие, у которого своего блока в перечне нет (замысел,
            // требование, риск — пришли 17.09), описывается ИСТИНОЙ: её `from`
            // и `fields` говорят всё нужное, и второй прозы для них не пишем.
            можно.filterNot { код -> ЧТО_ВЫПИСАТЬ.any { (к, _) -> к == код } }
                .mapNotNull { GeneratedOntology.byCode[it] }
                .forEach { п ->
                    append("**").append(словоПонятия(п.code).replaceFirstChar { it.uppercase() })
                    append("** (`").append(МАССИВ[п.code] ?: п.code).append("`). ")
                    п.from?.let { append(it).append(". ") }
                    append("Поля: ").append(п.fields.entries.joinToString(" · ") { (имя, откуда) ->
                        if (откуда.isBlank()) имя else "$имя — $откуда"
                    })
                    п.note?.let { append(". ").append(it) }
                    append("\n\n")
                }
            if (роль != УСТАВ) {
                append(
                    "**Чужие цели.** Цель, найденная не в уставе, — ЧУЖАЯ: выписывай её в " +
                        "`external_targets` с указанием, чья она. В реестр целей проекта она не идёт.\n\n",
                )
            }
        }.trim()
    }

    /** Канон написания и форм — дословно из истины (`normalization`). */
    private fun канонНаписания(): String = GeneratedOntology.normalization.entries
        .mapIndexed { номер, (_, правило) -> "${номер + 7}. $правило." }
        .joinToString("\n")

    private fun самопроверкаПоРоли(роль: String): String = if (роль == УСТАВ) {
        "- ни одна цель не осталась без значения и года;"
    } else {
        "- ни одна цель проекта отсюда не выписана: цели рождает только устав;"
    }

    /**
     * Массивы ответа, разрешённые ролью документа: они же обязательные.
     * Пустой массив — законный ответ («сторон в нормативе нет»), а вот
     * ОТСУТСТВИЕ массива уводит модель с формата на первом же заполненном.
     */
    private fun массивыРоли(роль: String): List<String> = ПОРЯДОК
        .filter { (_, понятие) ->
            понятие == ТОЛЬКО_ФАКТ && роль != УСТАВ ||
                GeneratedOntology.byCode[понятие]?.let { п ->
                    п.allowedRoles.isEmpty() || роль in п.allowedRoles
                } == true
        }
        .map { (массив, _) -> массив }

    /** Понятия, о которых промпт говорит: нужда живёт внутри стороны, но правила её нужны. */
    private fun понятияРоли(роль: String): Set<String> = GeneratedOntology.concepts
        .filter { п -> п.allowedRoles.isEmpty() || роль in п.allowedRoles }
        .map { it.code }
        .toSet()

    private fun роль(project: String, material: String): String =
        store.byCode(Area.Project(project), material)?.let { роль(it) } ?: ПО_УМОЛЧАНИЮ

    private fun роль(карточка: Entity): String =
        карточка.doc.path("role").asText("").trim().ifBlank { ПО_УМОЛЧАНИЮ }

    private fun рольСловами(роль: String): String =
        GeneratedOntology.documentRoles[роль]?.let { "$роль — $it" } ?: роль

    private fun ранг(карточка: Entity): String =
        orbita.knowledge.api.Authority.word(карточка.doc.path("rank").asText(""))

    private fun словоПонятия(код: String): String = СЛОВА[код] ?: код

    internal companion object {
        const val KIND: String = "document_reading"

        /** Потолок ответа: постановка записки — это сотня пунктов с цитатами. */
        const val БЮДЖЕТ: Int = 64_000

        /** Потолок текста документа в промпте. */
        const val ПОТОЛОК_ТЕКСТА: Int = 60_000

        /** Роль документа, когда она не названа: читаем как обстановку — она беднее всех прав. */
        const val ПО_УМОЛЧАНИЮ: String = "context"

        const val УСТАВ: String = "charter"

        /** Массив ответа → понятие онтологии. Порядок ответа: стороны, потом нужды. */
        val ПОРЯДОК: List<Pair<String, String>> = listOf(
            "stakeholders" to "stakeholder",
            "goals" to "goal",
            // Чужая цель понятием НЕ становится (истина, goal_hierarchy:
            // «факт вида external_target — цель чужой программы; в реестр
            // целей проекта не попадает»). Остаётся следом-фактом: по нему
            // видно, чья это цель, и на него опирается применимость.
            "external_targets" to ТОЛЬКО_ФАКТ,
            "constraints" to "constraint",
            "milestones" to "milestone",
            "assumptions" to "assumption",
            "applicabilities" to "opportunity",
            "services" to "service",
            "requirements" to "requirement",
            "risks" to "risk",
            "normative_documents" to "normative_document",
        )

        /** Понятие → имя массива ответа: им промпт называет, куда класть. */
        val МАССИВ: Map<String, String> = ПОРЯДОК.associate { (массив, понятие) -> понятие to массив }

        /** Замысел приходит ОДНИМ объектом, а не списком: он один на проект. */
        const val ЗАМЫСЕЛ: String = "intent"

        /** Снятое с учёта в срез следов не идёт. */
        val СНЯТЫЕ: Set<String> = setOf("cancelled", "superseded")

        /** Понятием не становится — только следом-фактом. */
        const val ТОЛЬКО_ФАКТ: String = "external_target"

        /** Служебные поля пункта: в понятие они не идут. */
        val СЛУЖЕБНЫЕ: Set<String> = setOf("id", "quote", "anchor", "confidence", "mark_no")

        /** Канон штриха (истина, `normalization.prime`) и что в него приводится. */
        const val ШТРИХ: String = "\u2032"
        val АПОСТРОФЫ: List<String> = listOf("'", "\u00b4", "\u2019")

        /** Плоские половинки величины: наружу идут парой, а не порознь. */
        val ВЕЛИЧИНА: Set<String> = setOf("value", "unit")

        /** Поля, в которых живёт естественное имя понятия. */
        val ИМЕНА: List<String> = listOf("name", "statement", "external_item", "designation")

        /**
         * Вид следа-факта по понятию — производная классификация (`reading_mode`).
         * Значения — из перечня истины схем (`fact.kind`).
         */
        val ВИД_ФАКТА: Map<String, String> = mapOf(
            "stakeholder" to "relation",
            "need" to "framing",
            "goal" to "quantity",
            "external_target" to "external_target",
            "constraint" to "obligation",
            "milestone" to "event",
            "assumption" to "assumption",
            "opportunity" to "external_need",
            "service" to "capability",
            "intent" to "framing",
            "requirement" to "obligation",
            "risk" to "assessment",
            "normative_document" to "obligation",
        )

        /** Предикат следа — тоже производная классификация, не поиск слова в тексте. */
        val ПРЕДИКАТ: Map<String, String> = mapOf(
            "stakeholder" to "участвует в проекте",
            "need" to "нуждается в",
            "goal" to "достичь",
            "external_target" to "ставит чужую цель",
            "constraint" to "обязывает",
            "milestone" to "наступает",
            "assumption" to "принимается на веру",
            "opportunity" to "нуждается в",
            "service" to "даёт",
            "intent" to "замышляется как",
            "requirement" to "требуется",
            "risk" to "рискует",
            "normative_document" to "действует",
        )

        val СЛОВА: Map<String, String> = mapOf(
            "stakeholder" to "стороны",
            "need" to "нужды",
            "goal" to "цели",
            "external_target" to "чужие цели",
            "constraint" to "рамки",
            "milestone" to "вехи",
            "assumption" to "допущения",
            "opportunity" to "применимости",
            "service" to "сервисы",
            "intent" to "замысел",
            "requirement" to "требования",
            "risk" to "риски",
            "normative_document" to "нормативы",
        )

        val ЧТО_ВЫПИСАТЬ: List<Pair<String, String>> = listOf(
            "stakeholder" to """
                **Стороны** (`stakeholders`). Каждая организация, ведомство, группа, которая
                участвует: заказывает, регулирует, эксплуатирует, потребляет, поставляет,
                финансирует, создаётся под проект. Поля: имя · роль · интерес одной фразой ·
                масштаб с единицей, если назван. ЕСЛИ В ДОКУМЕНТЕ ТАБЛИЦА СТОРОН — ВЫПИШИ
                КАЖДУЮ СТРОКУ. Не сторона: тот, кто лишь подписал или издал документ; сама
                наша система; регион, порт, коридор — это объекты применения.
            """.trimIndent(),
            "need" to """
                **Нужды — ВНУТРИ каждой стороны, полем `needs`.** Отдельного списка нужд
                НЕТ: у нужды ровно один носитель, и она живёт у него.

                Порядок работы. Сначала найди в документе ОБЩИЕ нужды — раздел, где
                они перечислены для всех сразу. Затем пройди по сторонам и у каждой
                перечисли те общие нужды, которые её касаются. Общая нужда касается,
                как правило, нескольких сторон — значит и записана будет у каждой
                из них, своей записью с той же формулировкой.

                Заполни `needs` у КАЖДОЙ выписанной стороны. Туда идут:
                · каждая общая нужда документа, которая этой стороны касается —
                  своей записью, теми же словами, что и у других сторон;
                · её собственный интерес из таблицы сторон: в ячейке «роль/интерес» их
                  бывает два-три через запятую или точку с запятой. Правило «одна мысль —
                  один пункт» действует и внутри ячейки: сколько самостоятельных
                  притязаний — столько записей.
                У каждой записи своя цитата и свой якорь.

                Так: сторона s1 → needs: [ {statement: «единое оперативное управление
                транспортом», quote: …, anchor: …}, {statement: «контроль исполнения
                требований к телематике», quote: …, anchor: …} ]; сторона s2 → needs:
                [ {statement: «единое оперативное управление транспортом», …} ].
                Одна формулировка у двух сторон — ДВЕ записи, это верно и ожидаемо.

                Сторона с пустым `needs` — недоделанная работа. На четырнадцати
                сторонах нужд выходит около сорока.
                Не нужда: роль стороны; свойство системы; обязанность из норматива.
            """.trimIndent(),
            "goal" to """
                **Цели** (`goals`). Измеримый результат к сроку: формулировка · значение с
                единицей · год · какие нужды закрывает. `needs` у КАЖДОЙ цели не пуст:
                ссылки на id нужд этого же ответа (`{"ref": "s1.n2"}`) — цель без нужды в
                постановку не встаёт, она ждёт связи. Перечисляй ВСЕ нужды, которые цель
                закрывает, а не одну для примера: сцена целей закрывается, когда КАЖДАЯ
                выписанная нужда ведёт хотя бы к одной цели — пройди по нуждам всех сторон
                и отдай каждую какой-то цели.
            """.trimIndent(),
            "constraint" to """
                **Ограничения** (`constraints`). Запреты и границы: что нельзя, что только
                так, не более/не менее чего. Поля: формулировка · тип · числовая граница,
                если есть · обозначение норматива-основания, если назван.
            """.trimIndent(),
            "milestone" to """
                **Вехи и этапы** (`milestones`). Событие или этап с датой либо горизонтом:
                имя · род · дата или диапазон · числа (сколько КА, терминалов), если названы.
            """.trimIndent(),
            "assumption" to """
                **Допущения** (`assumptions`). То, что принято на веру («предполагается»,
                «ожидается», «при подтверждении»): формулировка. Владельца и срок НЕ
                выписывай — их поставит человек.
            """.trimIndent(),
            "opportunity" to """
                **Применимость** (`applicabilities`). Чужая нужда или программа, которую наша
                система могла бы закрыть. Поля: чья нужда · чья она · вердикт · чего не
                хватает. «Не наш профиль» — законный и ценный вердикт.
            """.trimIndent(),
            "service" to """
                **Сервисы** (`services`). Что система даёт и кому: имя · кому · класс
                обслуживания · целевой показатель. `needs` у КАЖДОГО сервиса не пуст:
                ссылки на id нужд этого же ответа (`{"ref": "s1.n2"}`).
            """.trimIndent(),
        )
    }
}
