// Выгрузка картины (шип 4 §5, КАРТИНА-МИРА-CAPELLA-И-LLM, часть 2): пакет
// для внешнего контура пятью блоками — срез картины с границами понятий и
// стандартными именами, принятые объекты среза с кодами и версиями, цепочка
// задачи и что на ней пусто, точечные вопросы, права и формат ответа — с
// отпечатком среза для обратной сверки. Пакет ответа — тот же, что у
// внутренней модели: предложения понятий с кодами, цитатами и вердиктами.
//
// «Выгрузка знаний» остаётся рядом (файлы фактов и канонов); картина шире —
// она говорит внешней модели, ЧТО есть что и чего от неё ждут.
package orbita.exchange.internal

import orbita.exchange.api.KnowledgeBundle
import orbita.exchange.api.KnowledgeFingerprint
import orbita.exchange.api.PictureTask
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.schema.GeneratedOntology

internal class PictureExport(private val store: EntityStore) {

    companion object {
        /** Задачи среза: какие области картины и какие цепочки идут внешней модели. */
        val TASKS: List<PictureTask> = listOf(
            PictureTask("reading", "чтение документа — стороны, нужды, чужие цели, применимость", listOf(1, 2), listOf(6)),
            PictureTask("applicability", "применимость — чужая нужда × наши сервисы → вердикт", listOf(1, 2), listOf(7)),
            PictureTask("requirements", "требования — чего не хватает по трассе цель → требование → носитель", listOf(2, 5), listOf(1, 2)),
            PictureTask("conops", "поведение — сценарии, цепочки, обмены, режимы", listOf(4), listOf(8)),
            PictureTask("all", "вся картина — девять областей и восемь цепочек", (1..9).toList(), (1..8).toList()),
        )

        val FILES: List<String> = listOf("01-срез-картины.md", "02-принятые-объекты.md", "03-цепочка-задачи.md", "04-вопросы.md", "05-права-и-формат.md")

        private const val HEADER_END = KnowledgeFingerprint.HEADER_END

        /** Области картины → виды истины схем (КАРТИНА-МИРА-ПРОЕКТА, девять областей). */
        val ОБЛАСТИ: List<Область> = listOf(
            Область(1, "Мир — внешнее по отношению к проекту", listOf("stakeholder", "normative_document")),
            Область(2, "Постановка", listOf("intent", "need", "goal", "service", "constraint", "qos_class", "opportunity")),
            Область(3, "Система — структура (PBS)", listOf("component", "component_usage", "parameter", "interface", "constellation_variant", "baseline_concept", "technology", "model_element")),
            Область(4, "Система — поведение (Arcadia)", listOf("capability", "function", "exchange", "exchange_item", "functional_chain", "logical_component", "state_machine", "scenario")),
            Область(5, "Требования", listOf("requirement", "link", "typical_requirement", "baseline")),
            Область(6, "Подтверждение", listOf("system_model", "model_run", "metric", "budget", "verification_event", "data_request", "debris_assessment")),
            Область(7, "Программа", listOf("wbs_package", "cost_estimate", "milestone", "risk", "plan", "assignment")),
            Область(8, "Процесс", listOf("phase", "scene", "gate", "criterion", "decision", "finding")),
            Область(9, "Документы и знание", listOf("material", "fact", "topic", "proposal", "document", "element", "rendering", "release")),
        )

        /** «Чем не является» — словами картины владельца; у понятий постановки границы берутся из онтологии. */
        val НЕ_ЯВЛЯЕТСЯ: Map<String, String> = mapOf(
            "stakeholder" to "не издатель акта, не место, не система",
            "normative_document" to "не сторона; не ограничение сама по себе",
            "intent" to "не цель",
            "need" to "не роль, не свойство системы, не норма",
            "goal" to "не показатель, не требование, не чужая цель",
            "service" to "не нужда, не функция",
            "constraint" to "не требование, не норма",
            "qos_class" to "справочник",
            "opportunity" to "не цель, не сервис",
            "component" to "не вхождение",
            "component_usage" to "не узел",
            "parameter" to "не свойство «в тексте» — сущность с версией",
            "interface" to "не обмен",
            "constellation_variant" to "не состав",
            "baseline_concept" to "не цель",
            "technology" to "не параметр",
            "model_element" to "не источник истины требований",
            "capability" to "не сервис (сервис — обещание, способность — умение)",
            "function" to "не узел",
            "exchange" to "не стык",
            "exchange_item" to "не обмен",
            "functional_chain" to "не требование",
            "logical_component" to "не узел PBS",
            "state_machine" to "не функция",
            "scenario" to "не требование",
            "requirement" to "не цель, не ограничение, не пожелание",
            "link" to "не поле",
            "typical_requirement" to "не проектное",
            "baseline" to "не флаг у записи",
            "system_model" to "не документ",
            "model_run" to "не запись модели",
            "metric" to "не показатель требования",
            "budget" to "не параметр",
            "verification_event" to "не план",
            "data_request" to "не параметр",
            "debris_assessment" to "не риск",
            "wbs_package" to "не узел",
            "cost_estimate" to "не число",
            "milestone" to "не срок действия нормы",
            "risk" to "не допущение",
            "plan" to "не Гант (Гант — вид)",
            "assignment" to "не мероприятие",
            "phase" to "—",
            "scene" to "не задача",
            "gate" to "не сцена",
            "criterion" to "не пожелание",
            "decision" to "не помета",
            "finding" to "не риск",
            "material" to "не документ проекта",
            "fact" to "не сущность",
            "topic" to "не сущность",
            "proposal" to "не сущность",
            "document" to "не текст",
            "element" to "не абзац",
            "rendering" to "не источник истины",
            "release" to "не документ",
        )

        /** Восемь сквозных цепочек картины. */
        val ЦЕПОЧКИ: Map<Int, Цепочка> = mapOf(
            1 to Цепочка("Трасса требования вниз", listOf("stakeholder", "need", "goal", "requirement", "component", "parameter", "system_model", "budget", "constraint")),
            2 to Цепочка("Трасса требования вправо", listOf("requirement", "verification_event", "gate", "baseline")),
            3 to Цепочка("Трасса денег", listOf("component", "wbs_package", "cost_estimate", "plan", "milestone")),
            4 to Цепочка("Трасса технологии", listOf("technology", "component", "wbs_package", "milestone", "requirement")),
            5 to Цепочка("Трасса процесса", listOf("phase", "scene", "document", "gate", "decision", "baseline")),
            6 to Цепочка("Трасса знания", listOf("material", "fact", "proposal", "stakeholder", "need", "document")),
            7 to Цепочка("Трасса применимости", listOf("fact", "opportunity", "service", "need", "stakeholder")),
            8 to Цепочка("Трасса поведения", listOf("scenario", "functional_chain", "function", "exchange", "interface", "exchange_item", "state_machine")),
        )

        /** Вопросы по задаче — точечные, с ожидаемым видом ответа (не «собери»). */
        val ВОПРОСЫ: Map<String, List<Pair<String, String>>> = mapOf(
            "reading" to listOf(
                "какие стороны здесь НЕ из наших и почему они стороны" to "stakeholder · new с цитатой",
                "какие чужие нужды и цели названы и чьи они" to "external_target / external_need — факты с владельцем, не цели проекта",
                "чем наша система (сервисы по кодам) им полезна — вердиктом" to "opportunity · verdict · missing · proposal",
                "какие нормы не названы в уставе" to "normative_document · new с обозначением",
                "что противоречит принятому и что его подтверждает" to "contradict / confirm с кодом принятой сущности",
            ),
            "applicability" to listOf(
                "чем наша система (сервисы по кодам) полезна каждой чужой нужде — вердиктом закрываем · частично · не наш профиль · нужна доработка" to "opportunity · verdict · our_services",
                "чего не хватает, чтобы закрыть" to "opportunity.missing — параметры, требования, сервисы",
                "что предложить эксперту" to "opportunity.proposal — расширить зону · добавить сервис · в отложенное · отклонить",
                "«не наш профиль» — на какую границу устава опирается" to "rationale со словами границы «чего мы не строим»",
            ),
            "requirements" to listOf(
                "каких требований не хватает по трассе цель → требование → носитель" to "requirement · new с источником (goal/need/constraint по коду)",
                "что противоречит уже принятым требованиям" to "contradict с кодом и полем",
                "какие показатели без единицы или с TBR без владельца" to "augment по коду требования",
            ),
            "conops" to listOf(
                "какие сценарии не покрыты цепочками функций" to "functional_chain · new с шагами-функциями по кодам",
                "какие обмены без элементов обмена" to "exchange_item · new к обмену по коду",
                "какие режимы и состояния не названы" to "state_machine · augment по коду компонента",
            ),
            "all" to listOf(
                "чего не хватает в картине по каждой цепочке" to "new по понятию с основанием",
                "что противоречит принятому" to "contradict с кодом и полем",
                "что подтверждает принятое" to "confirm с кодом",
                "какие поля принятых объектов пусты" to "augment по коду",
            ),
        )
    }

    data class Область(val n: Int, val title: String, val kinds: List<String>)
    data class Цепочка(val title: String, val kinds: List<String>)

    fun tasks(): List<PictureTask> = TASKS

    fun bundle(project: String, task: String): KnowledgeBundle {
        val задача = TASKS.firstOrNull { it.key == task.trim() }
            ?: throw IllegalArgumentException("среза «$task» нет: ${TASKS.joinToString(" · ") { it.key }}")
        val область = Area.Project(project)
        val тела = linkedMapOf(
            FILES[0] to срез(задача),
            FILES[1] to объекты(область, задача),
            FILES[2] to цепочка(область, задача),
            FILES[3] to вопросы(задача),
            FILES[4] to права(задача),
        )
        val отпечаток = KnowledgeFingerprint.of(тела)
        return KnowledgeBundle(отпечаток, тела.mapValues { (_, тело) -> header(отпечаток, project, задача.key) + тело })
    }

    private fun header(fingerprint: String, project: String, task: String): String = buildString {
        appendLine("<!-- отпечаток картины: $fingerprint · проект: $project · срез: $task -->")
        appendLine("<!-- Ответ ОБЯЗАН нести \"picture_fingerprint\": \"$fingerprint\" -->")
        append(HEADER_END)
        appendLine()
    }

    private fun kinds(задача: PictureTask): List<Pair<Область, List<String>>> =
        ОБЛАСТИ.filter { it.n in задача.areas }.map { о -> о to о.kinds.filter { GeneratedKinds.byCode.containsKey(it) } }

    private fun срез(задача: PictureTask): String = buildString {
        appendLine("# Срез картины: ${задача.title}")
        appendLine()
        appendLine("Картина — справочник с границами, не правила: у каждого понятия сказано, что это и чем оно НЕ является.")
        appendLine("Стандартные имена (NASA SE / ISO 15288 / Arcadia) даны там, где совпадают; своё знание кладите В ЭТУ структуру, а не рядом.")
        appendLine()
        val словарь = стандартныеИмена()
        kinds(задача).forEach { (область, виды) ->
            appendLine("## Область ${область.n} · ${область.title}")
            appendLine()
            appendLine("| Понятие | Код | Поля | Чем не является | Стандартное имя |")
            appendLine("|---|---|---|---|---|")
            виды.forEach { код ->
                val спец = GeneratedKinds.byCode.getValue(код)
                val поля = спец.fields.take(8).joinToString(" · ") { спец.labels[it] ?: it }
                val границы = GeneratedOntology.byCode[код]?.notFrom?.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: НЕ_ЯВЛЯЕТСЯ[код] ?: "—"
                appendLine("| ${спец.title} | `$код` | $поля | $границы | ${словарь[код] ?: "—"} |")
            }
            appendLine()
        }
        if (2 in задача.areas) {
            appendLine("## Роли документов и что они порождают")
            appendLine()
            GeneratedOntology.documentRoles.forEach { (роль, что) -> appendLine("- `$роль` — $что") }
            appendLine()
            appendLine("Цели, сервисы и горизонты проекта образуются только из устава; чужая цель — факт `external_target`, не цель.")
            appendLine()
        }
    }

    /** Стандартное имя понятия — из словаря библиотеки: термин с кодом объекта = вид. */
    private fun стандартныеИмена(): Map<String, String> {
        val имена = mutableMapOf<String, MutableList<String>>()
        store.list(Area.Library, "glossary_term").filter { it.status == "accepted" }.forEach { т ->
            val код = т.doc.path("object_code").asText("").ifBlank { return@forEach }
            val en = т.doc.path("term_en").asText("").ifBlank { return@forEach }
            val источник = т.doc.path("source").asText("").substringBefore(" (").take(24)
            имена.getOrPut(код) { mutableListOf() }.let { if (it.size < 2 && "$en ($источник)" !in it) it += "$en ($источник)" }
        }
        return имена.mapValues { it.value.joinToString("; ") }
    }

    private fun объекты(область: Area, задача: PictureTask): String = buildString {
        appendLine("# Принятые объекты среза")
        appendLine()
        appendLine("Ссылайтесь на них по коду (`code`); новое — только `new` с цитатой или обоснованием. Параллельная сущность вместо ссылки — брак ответа.")
        appendLine()
        kinds(задача).forEach { (обл, виды) ->
            виды.forEach { код ->
                val записи = живые(область, код)
                appendLine("## ${GeneratedKinds.byCode.getValue(код).title} (`$код`) — ${записи.size}")
                appendLine()
                if (записи.isEmpty()) appendLine("_пусто_") else {
                    записи.take(300).forEach { з ->
                        appendLine("- `${з.code}` · v${з.version} · ${з.status} — ${формулировка(з)}")
                    }
                    if (записи.size > 300) appendLine("- … ещё ${записи.size - 300}")
                }
                appendLine()
            }
        }
    }

    private fun цепочка(область: Area, задача: PictureTask): String = buildString {
        appendLine("# Цепочка задачи")
        appendLine()
        задача.traces.forEach { n ->
            val ц = ЦЕПОЧКИ.getValue(n)
            appendLine("## $n · ${ц.title}")
            appendLine()
            appendLine(ц.kinds.joinToString(" → ") { "`$it`" })
            appendLine()
            appendLine("| Звено | Есть в проекте | Состояние |")
            appendLine("|---|---|---|")
            ц.kinds.forEach { код ->
                val спец = GeneratedKinds.byCode[код]
                val n2 = if (спец == null) 0 else живые(область, код).size
                appendLine("| ${спец?.title ?: код} (`$код`) | $n2 | ${if (n2 == 0) "пусто — здесь нужны предложения" else "есть"} |")
            }
            appendLine()
        }
        appendLine("Любая сущность обязана лежать хотя бы на одной цепочке — иначе она вне картины.")
    }

    private fun вопросы(задача: PictureTask): String = buildString {
        appendLine("# Вопросы")
        appendLine()
        appendLine("Не «собери постановку», а точечные вопросы; ответ — дельта к картине с вердиктами new · augment · contradict · confirm.")
        appendLine()
        (ВОПРОСЫ[задача.key] ?: ВОПРОСЫ.getValue("all")).forEachIndexed { i, (вопрос, ответ) ->
            appendLine("${i + 1}. **$вопрос** — ожидаемый ответ: $ответ.")
        }
        appendLine()
    }

    private fun права(задача: PictureTask): String = buildString {
        appendLine("# Права и формат ответа")
        appendLine()
        appendLine("## Права")
        appendLine()
        appendLine("- предлагать — да: `new` с цитатой и якорем, `augment` · `confirm` · `contradict` по коду принятой сущности;")
        appendLine("- менять принятое, сливать, выбирать победителя, вводить новые виды, додумывать поля — нет;")
        appendLine("- параллельная сущность вместо ссылки на код — брак ответа; синонимы — через словарь;")
        appendLine("- у величин — единицы справочника, число и единица отдельными полями.")
        appendLine()
        appendLine("## Пакет ответа — единый для внутренней и внешней модели")
        appendLine()
        appendLine("Тот же пакет, что отдаёт внутренняя модель при чтении документа: предложения понятий с основаниями. Идёт сверкой (`POST /v2/reconcile`) либо результатом исследования (`POST /v2/research/{задача}/result`), затем массовым приёмом.")
        appendLine()
        appendLine("```json")
        appendLine("{")
        appendLine("  \"picture_fingerprint\": \"<отпечаток из шапки>\",")
        appendLine("  \"proposals\": [")
        appendLine("    {\"concept\": \"stakeholder\", \"verdict\": \"new\", \"payload\": {\"name\": \"…\", \"role\": \"customer\"},")
        appendLine("     \"basis\": [{\"quote\": \"дословный фрагмент источника\", \"anchor\": \"s3#2\", \"material\": \"SD-0002\"}], \"confidence\": 0.8},")
        appendLine("    {\"concept\": \"opportunity\", \"verdict\": \"new\", \"payload\": {\"external_item\": \"…\", \"owner\": \"…\", \"verdict\": \"not_our_profile\", \"rationale\": \"граница устава: …\"},")
        appendLine("     \"basis\": [{\"quote\": \"…\", \"anchor\": \"s4#1\"}]},")
        appendLine("    {\"concept\": \"goal\", \"verdict\": \"confirm\", \"target\": \"MG-0003\", \"basis\": [{\"quote\": \"…\", \"anchor\": \"s2#5\"}]}")
        appendLine("  ]")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("Понятие — код из среза; `target` — код принятой сущности (у `new` пусто); `basis` — цитата дословно и якорь блока канона.")
        appendLine("Срез: ${задача.key} · области ${задача.areas.joinToString(", ")} · цепочки ${задача.traces.joinToString(", ")}.")
        appendLine("Обратная сверка: `POST /v2/export/picture/verify {\"task\": \"${задача.key}\", \"fingerprint\": \"<из шапки>\"}` — устаревший срез предупреждает, а не отказывает.")
    }

    private fun живые(область: Area, вид: String): List<Entity> {
        val проектные = store.list(область, вид).filter { it.status != "cancelled" }
        // Полки и справочники живут в библиотеке: нормативы, классы обслуживания, типовые.
        return if (проектные.isNotEmpty() || GeneratedKinds.byCode[вид]?.layer?.name != "L0") проектные.sortedBy { it.code }
        else store.list(Area.Library, вид).filter { it.status != "cancelled" }.sortedBy { it.code }
    }

    private fun формулировка(з: Entity): String =
        listOf("name", "statement", "title", "designation", "label", "external_item", "what", "text")
            .map { з.doc.path(it).asText("") }.firstOrNull { it.isNotBlank() }?.replace("\n", " ")?.take(160) ?: "—"
}
