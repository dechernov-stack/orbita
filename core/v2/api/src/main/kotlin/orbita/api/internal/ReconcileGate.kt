// Ворота сохранения: на проекте поля знаний v2 введённое попадает в модель
// только через сверку (СВЕРКА-РУЧНОГО-ВВОДА, manual_input_rule).
//
// Почему отдельным файлом, а не двумя копиями в маршрутах: ворота одни и те
// же на факт (KnowledgeRoutes) и на сущности сцен 3–6 (SceneRoutes), а две
// копии одного правила расходятся молча — ровно так когда-то разошлись два
// построителя факта (ПРИЁМКА-KNOWLEDGE-REMARKS, правило 2). Здесь же второе
// правило: пока обязательная связь понятия не закрыта, сущности не будет
// (инвариант ОНТОЛОГИИ-ФОРМИРОВАНИЯ «понятие с must_link незакрытым —
// предложение с пометой, не сущность»).
//
// Домена ворота не изобретают: перечень обязательных связей берётся из
// онтологии, а маршрут называет лишь СВОИ поля запроса, которыми связь
// закрывается, — он же из них эту связь и строит.
//
// Флаг: на проекте без `knowledge_v2` ворот нет вовсе — ввод сохраняется
// прежним порядком, и ни один существующий тест сцен не правится.
package orbita.api.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.KnowledgeFlag
import orbita.knowledge.schema.GeneratedOntology

internal object ReconcileGate {

    /** Ссылка на сверку: запуск и местный номер строки ввода — «SR-12#c1». */
    private val ССЫЛКА = Regex("^SR-[0-9]+#\\S+$")

    /**
     * Пропустить ввод или отбить его.
     *
     * @param store хранилище — читается ровно за флагом проекта и за тем,
     *   существует ли названный запуск сверки; `null` — прежняя сборка
     *   (стенд ПМИ-5): развилки нет вовсе
     * @param concept понятие онтологии, которое заводится вводом; `null` —
     *   ввод понятием не становится (ручной факт), и нехватка не проверяется
     * @param закрывают связь понятия → поля ЭТОГО запроса, которыми она
     *   закрывается («owns» → «owner»): имя поля знает маршрут, а перечень
     *   обязательных связей — онтология
     * @return отказ, который маршрут обязан вернуть как есть; `null` — ввод
     *   сверен и обязательные связи закрыты, сохраняйте
     */
    fun ворота(
        store: EntityStore?,
        mapper: ObjectMapper,
        project: String,
        concept: String?,
        тело: JsonNode,
        закрывают: Map<String, List<String>> = emptyMap(),
    ): V2Router.Ответ? {
        val хранилище = store ?: return null
        if (!KnowledgeFlag.on(хранилище, project)) return null

        val ссылка = тело.path("reconcile").asText("").trim()
        val решение = тело.path("decision").asText("").trim()
        if (!ССЫЛКА.matches(ссылка) || решение.isBlank()) {
            return несверено(
                mapper,
                "введённое не попадает в модель напрямую: назовите сверку «reconcile» " +
                    "(например «SR-12#c1») и решение человека «decision»",
            )
        }
        // Ссылка обязана указывать на живой запуск: без этой проверки строка
        // «SR-1#c1» в теле была бы обходом ворот, а не следом сверки.
        val запуск = ссылка.substringBefore("#")
        if (хранилище.byCode(Area.Project(project), запуск)?.kind != "synthesis_run") {
            return несверено(mapper, "сверки «$запуск» в проекте нет: этот ввод через неё не проходил")
        }

        val понятие = concept?.let { GeneratedOntology.byCode[it] } ?: return null
        понятие.mustLink.forEach { токен ->
            val голова = токен.substringBefore(" if ").trim()
            val условие = токен.substringAfter(" if ", "").trim()
            // Условная связь обязательна не всегда («normative_basis if
            // obligation»): условие читается так же, как в сверке.
            if (условие.isNotBlank() && !приУсловии(тело, условие)) return@forEach
            val связь = голова.substringBefore("→").trim()
            val поля = закрывают[связь] ?: listOf(связь)
            if (поля.none { названо(тело, it) }) return нехватка(mapper, токен, поля)
        }
        return null
    }

    /**
     * Ввод мимо сверки. Код 409, а не 400: запрос сам по себе правильный —
     * не выполнен порядок работы, и человеку сказано, каким он бывает.
     */
    private fun несверено(mapper: ObjectMapper, что: String): V2Router.Ответ = V2Router.Ответ(
        409,
        mapper.createObjectNode()
            .put("error", "ввод не сверен")
            .put("what_to_do", "POST /v2/reconcile")
            .put("note", что),
    )

    /**
     * Обязательная связь не закрыта. Код 422: содержимое понятно, но принять
     * его нечем — сущности без этой связи не бывает. Имя связи идёт наружу
     * отдельным полем, иначе человеку нечего выбирать.
     */
    private fun нехватка(mapper: ObjectMapper, токен: String, поля: List<String>): V2Router.Ответ = V2Router.Ответ(
        422,
        mapper.createObjectNode()
            .put("error", "обязательная связь понятия не закрыта: «$токен» — сущности без неё не будет")
            .put("missing", токен)
            .put("what_to_do", "назовите " + поля.joinToString(" либо ") { "«$it»" } + " и повторите"),
    )

    /**
     * Условие обязательности связи. Читается тем же правилом, что и в сверке
     * (`Reconciler.подходит`): вид факта совпал с условием либо ограничение
     * пришло регуляторным — «obligation→regulatory».
     */
    private fun приУсловии(тело: JsonNode, условие: String): Boolean {
        val вид = тело.path("kind").asText("").trim()
        val тип = тело.path("type").asText("").trim()
        return вид == условие || (условие == "obligation" && тип == "regulatory")
    }

    /** Названо ли поле: пустая строка и пустой список — это «не названо». */
    private fun названо(тело: JsonNode, поле: String): Boolean {
        val узел = тело.path(поле)
        return when {
            узел.isMissingNode || узел.isNull -> false
            узел.isArray -> узел.any { it.asText("").isNotBlank() }
            else -> узел.asText("").isNotBlank()
        }
    }
}
