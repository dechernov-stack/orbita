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
import orbita.knowledge.api.CanonBlock
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
                        authority = факт.doc.path("authority").asText("").ifBlank { null },
                        mark = SourceMark.entries.firstOrNull { it.name == факт.doc.path("mark").asText("") },
                    ),
                ),
                verdict = Verdict.NEW,
                sourceMark = SourceMark.entries.firstOrNull { it.name == факт.doc.path("mark").asText("") }
                    ?: SourceMark.И,
                confidence = понятие.confidence,
            )
        }
        return synthesizer.record(
            project, author, intake.fingerprint(project, material), итог.note, предложения, итог.refused,
        )
    }

    /** Прочитать материал живым вызовом. Повтор той же версии — из журнала. */
    fun read(project: String, material: String, author: String): Прочитанное {
        val ответ = service.ask(
            project, KIND, prepare(project, material),
            maxTokens = БЮДЖЕТ,
            schema = AnswerSchemas.чтение(mapper),
        )
        return apply(project, material, author, ответ)
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
5. Ссылки внутри ответа: нужда указывает сторону через `ref` на `id` пункта
   этого же ответа либо `code` уже принятой.
6. Не оценивай и не решай — ты предлагаешь, решает инженер.

## Перед ответом проверь
- у каждого пункта есть цитата и якорь;
- ни одна нужда не осталась без стороны;
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
    fun apply(project: String, material: String, author: String, answer: Answer): Прочитанное {
        val блоки = intake.canon(project, material).associateBy { it.anchor }
        val корень = runCatching { mapper.readTree(answer.text) }.getOrNull()
            ?: return Прочитанное(material, emptyList(), emptyList(), emptyList(), "ответ службы не разобран")

        val отбито = mutableListOf<String>()
        val пункты = mutableListOf<Пункт>()
        ПОРЯДОК.forEach { (массив, понятие) ->
            корень.path(массив).forEachIndexed { номер, узел ->
                val имя = узел.path("id").asText("").ifBlank { "$массив#${номер + 1}" }
                val беда = воротаПункта(узел, блоки)
                if (беда != null) {
                    отбито += "$имя ($понятие): $беда"
                    return@forEachIndexed
                }
                пункты += Пункт(имя, понятие, узел)
            }
        }
        // Ссылки внутри ответа разрешаются ДО фактов: нужда, назвавшая сторону
        // `ref`-ом, обязана получить её имя — иначе связь «owns→stakeholder»
        // не закроется ничем (прогон владельца 15.09).
        val имена = пункты.associate { it.localId to естественноеИмя(it.узел) }

        val следы = mapper.createObjectNode()
        val массивФактов = следы.putArray("facts")
        пункты.forEach { массивФактов.add(след(it)) }
        val принято = intake.putFacts(project, material, mapper.writeValueAsString(следы), author)
        отбито += принято.refused

        val свободные = принято.accepted.toMutableList()
        val понятия = пункты.map { пункт ->
            val след = свободные.firstOrNull { совпало(it, пункт) }?.also { свободные.remove(it) }
            ЧитанноеПонятие(
                localId = пункт.localId,
                concept = пункт.concept,
                payload = поля(пункт, имена),
                quote = пункт.узел.path("quote").asText(""),
                anchor = пункт.узел.path("anchor").asText(""),
                confidence = пункт.узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
                fact = след?.id,
            )
        }
        val безСледа = понятия.count { it.fact == null }
        val счёт = понятия.groupingBy { it.concept }.eachCount().entries
            .joinToString(" · ") { (понятие, число) -> "${словоПонятия(понятие)} $число" }
        return Прочитанное(
            material = material,
            proposals = понятия,
            facts = понятия.mapNotNull { it.fact },
            refused = отбито,
            note = buildString {
                append("прочитано: ").append(счёт.ifBlank { "ни одного понятия" })
                if (отбито.isNotEmpty()) append("; отбито воротами ").append(отбито.size)
                if (безСледа > 0) append("; без следа-факта ").append(безСледа)
            },
        )
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
        узел.put("anchor", поля.path("anchor").asText(""))
        узел.put("source_mark", если(пункт.concept == "assumption", "П", "И"))
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

    /** След ли это того пункта: якорь, субъект и утверждение совпали. */
    private fun совпало(факт: orbita.knowledge.api.Fact, пункт: Пункт): Boolean =
        факт.anchor == пункт.узел.path("anchor").asText("") &&
            факт.subject == субъект(пункт) &&
            факт.value == утверждение(пункт)

    // --- поля понятия -------------------------------------------------------

    /** Поля понятия из пункта: служебное (цитата, якорь, id) наружу не идёт. */
    private fun поля(пункт: Пункт, имена: Map<String, String>): Map<String, String> {
        val поля = linkedMapOf<String, String>()
        пункт.узел.properties().forEach { (имя, значение) ->
            if (имя in СЛУЖЕБНЫЕ) return@forEach
            val текст = when {
                значение.isValueNode -> значение.asText("")
                else -> ссылкаТекстом(значение, имена)
            }
            if (текст.isNotBlank()) поля[имя] = текст
        }
        return поля
    }

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
        val можно = GeneratedOntology.concepts
            .filter { понятие -> понятие.allowedRoles.isEmpty() || роль in понятие.allowedRoles }
            .map { it.code }
        return buildString {
            ЧТО_ВЫПИСАТЬ.forEach { (понятие, текст) ->
                if (понятие !in можно) return@forEach
                append(текст).append("\n\n")
            }
            if (роль != УСТАВ) {
                append(
                    "**Чужие цели.** Цель, найденная не в уставе, — ЧУЖАЯ: выписывай её в " +
                        "`external_targets` с указанием, чья она. В реестр целей проекта она не идёт.\n\n",
                )
            }
        }.trim()
    }

    private fun самопроверкаПоРоли(роль: String): String = if (роль == УСТАВ) {
        "- ни одна цель не осталась без значения и года;"
    } else {
        "- ни одна цель проекта отсюда не выписана: цели рождает только устав;"
    }

    private fun роль(карточка: Entity): String =
        карточка.doc.path("role").asText("").trim().ifBlank { ПО_УМОЛЧАНИЮ }

    private fun рольСловами(роль: String): String =
        GeneratedOntology.documentRoles[роль]?.let { "$роль — $it" } ?: роль

    private fun ранг(карточка: Entity): String =
        orbita.knowledge.api.Authority.word(карточка.doc.path("authority").asText(""))

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
            "needs" to "need",
            "goals" to "goal",
            "external_targets" to "external_target",
            "constraints" to "constraint",
            "milestones" to "milestone",
            "assumptions" to "assumption",
            "applicabilities" to "opportunity",
            "services" to "service",
        )

        /** Служебные поля пункта: в понятие они не идут. */
        val СЛУЖЕБНЫЕ: Set<String> = setOf("id", "quote", "anchor", "confidence")

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
                **Нужды** (`needs`). Чего стороне не хватает или что она требует. Поля:
                формулировка · чья (ссылка на сторону) · класс обслуживания, если назван.
                Нужда без стороны не выписывается: если сторона очевидна из текста, выпиши и
                её. Не нужда: роль стороны; свойство системы; обязанность из норматива.
            """.trimIndent(),
            "goal" to """
                **Цели** (`goals`). Измеримый результат к сроку: формулировка · значение с
                единицей · год · какие нужды закрывает.
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
                обслуживания · целевой показатель.
            """.trimIndent(),
        )
    }
}
