// Выгрузка знаний проекта (шип F): набор MD-файлов с отпечатком в шапке
// каждого. Знания идут во внешний контур один раз, ответы возвращаются
// пакетами, и по отпечатку видно, отвечала ли служба на актуальные знания.
//
// Правила и алгоритм отпечатка — те же, что у первой версии
// (core/com KnowledgeExport): sha256 по отсортированным файлам, имя + тело
// без шапки, первые 16 знаков. Новое против первой версии — часть
// «факты»: принятые · допущенные · замеченные, каждый с якорем блока.
package orbita.exchange.internal

import orbita.exchange.api.KnowledgeBundle
import orbita.exchange.api.KnowledgeFingerprint
import orbita.exchange.api.KnowledgePart
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.Disposition
import orbita.knowledge.api.Fact
import orbita.knowledge.api.Intake

internal class KnowledgeExportV2(
    private val store: EntityStore,
    private val intake: Intake,
) {

    companion object {
        val PARTS: List<KnowledgePart> = listOf(
            KnowledgePart("instruction", "00-инструкция.md", "Инструкция внешнего контура"),
            KnowledgePart("intent", "01-замысел.md", "Замысел миссии"),
            KnowledgePart("constraints", "02-ограничения.md", "Ограничения проекта (Р-коды)"),
            KnowledgePart("statement", "03-цели-нужды-сервисы.md", "Постановка: цели, нужды, сервисы"),
            KnowledgePart("stakeholders", "04-стейкхолдеры.md", "Стейкхолдеры"),
            KnowledgePart("normatives", "05-нормативы.md", "Нормативы полки: пункты"),
            KnowledgePart("glossary", "06-глоссарий.md", "Глоссарий полки"),
            KnowledgePart("materials", "07-материалы.md", "Каноны материалов проекта с якорями"),
            KnowledgePart("facts", "08-факты.md", "Факты: принятые · допущенные · замеченные"),
        )

        private const val HEADER_END = KnowledgeFingerprint.HEADER_END

        /** Общие правила внешнего контура — те же законы, что у внутреннего. */
        val CONTOUR_RULES: List<String> = listOf(
            "Отвечай только из знаний этого пакета и поставленной задачи; ничего не привноси. " +
                "Обобщение без опоры на блок-якорь запрещено.",
            "Каждое порождённое утверждение с фактическим основанием несёт `anchors` — " +
                "якоря блоков канона, из которых оно выведено.",
            "Реквизиты нормативных актов по памяти не восстанавливай: нет в канонах — " +
                "верни `\"need_ref\": true` вместо выдуманного реквизита.",
            "Числа — парой `{value, unit}`; «около» без диапазона запрещено.",
            "Действующие ограничения Р-* — жёсткие запреты: предложение, нарушающее Р-код, " +
                "не выдаётся вовсе.",
            "Метки источников: [И] — внутренний документ; [В] — внешний источник, проверенный " +
                "на дату; [П] — предлагаемая цель или допущение, требующее подтверждения. " +
                "[П]-допущение не выдавай за факт.",
            "Факт с диспозицией «допущен» — допущение с владельцем и точкой подтверждения: " +
                "опирайся на него, называя это допущением.",
            "В каждый ответ включай `knowledge_fingerprint` из шапки файлов этого пакета — " +
                "по нему стенд проверит, не устарели ли знания.",
        )

        fun fingerprintOf(files: Map<String, String>): String = KnowledgeFingerprint.of(files)
    }

    fun bundle(project: String, parts: Set<String>?): KnowledgeBundle {
        val выбранные = PARTS.filter { parts == null || it.key in parts }
        val тела = выбранные.associate { it.file to body(it.key, project) }
        val отпечаток = fingerprintOf(тела)
        return KnowledgeBundle(отпечаток, тела.mapValues { (_, тело) -> header(отпечаток, project) + тело })
    }

    private fun header(fingerprint: String, project: String): String = buildString {
        appendLine("<!-- отпечаток знаний: $fingerprint · проект: $project -->")
        appendLine("<!-- Ответ службы ОБЯЗАН нести \"knowledge_fingerprint\": \"$fingerprint\" -->")
        append(HEADER_END)
        appendLine()
    }

    private fun body(key: String, project: String): String {
        val область = Area.Project(project)
        return when (key) {
            "instruction" -> instruction(project)
            "intent" -> intent(область)
            "constraints" -> constraints(область)
            "statement" -> statement(область)
            "stakeholders" -> stakeholders(область)
            "normatives" -> normatives()
            "glossary" -> glossary()
            "materials" -> materials(project, область)
            "facts" -> facts(project)
            else -> ""
        }
    }

    private fun живые(область: Area, вид: String): List<Entity> =
        store.list(область, вид).filter { it.status != "cancelled" }.sortedBy { it.code }

    private fun instruction(project: String): String = buildString {
        val имя = store.byCode(Area.Project(project), project)?.doc?.path("name")?.asText(project) ?: project
        appendLine("# Инструкция внешнего контура «$имя»")
        appendLine()
        appendLine("Ты — служба генерации инженерной системы «Орбита» (космическая миссия")
        appendLine("ранних фаз, NPR 7120.5 / NPR 7123.1). Знания проекта — в файлах этого")
        appendLine("пакета: замысел, ограничения (Р-коды), постановка, стейкхолдеры, нормативы")
        appendLine("полки, глоссарий, каноны материалов с якорями `sN#M` и факты с диспозицией.")
        appendLine()
        appendLine("## Правила контура (нарушение любого — брак ответа)")
        appendLine()
        CONTOUR_RULES.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
        appendLine()
        appendLine("## Ответ")
        appendLine()
        appendLine("Ответ — только JSON по схеме запрошенного вида, без преамбулы. Не ложится в")
        appendLine("схему — верни `{\"error\": \"…\", \"needs\": [...]}`. Действия плана: `create_entity` ·")
        appendLine("`update_params` · `create_risk` · `request_data` · `flag_conflict` — с полем `facts`")
        appendLine("(коды фактов-оснований из части «факты»).")
    }

    private fun intent(область: Area): String = buildString {
        appendLine("# Замысел миссии")
        appendLine()
        val замысел = store.list(область, "intent").sortedWith(compareBy({ it.status != "accepted" }, { it.createdAt })).firstOrNull()
        if (замысел == null) {
            appendLine("_замысел не задан_")
        } else {
            listOf("for_whom" to "Для кого", "what" to "Что делает", "where" to "Где", "horizon" to "Горизонт").forEach { (поле, метка) ->
                val значение = замысел.doc.path(поле).asText("").trim()
                if (значение.isNotBlank()) appendLine("- **$метка:** $значение")
            }
            appendLine()
            appendLine("Состояние: `${замысел.status}`.")
        }
    }

    private fun constraints(область: Area): String = buildString {
        appendLine("# Ограничения проекта")
        appendLine()
        appendLine("Действующие ограничения — жёсткие запреты: предложение, нарушающее код, не выдаётся.")
        appendLine()
        val рамки = живые(область, "constraint")
        if (рамки.isEmpty()) appendLine("_ограничений нет_")
        рамки.forEach { р ->
            append("- **${р.code}**: ${р.doc.path("text").asText(р.doc.path("statement").asText(""))}")
            р.doc.path("category").asText("").takeIf { it.isNotBlank() }?.let { append(" _(${it})_") }
            р.doc.path("bound").takeIf { it.isObject }?.let { b ->
                append(" — рамка: ${b.path("key").asText("")} ${b.path("op").asText("")} ${b.path("value").asText("")} ${b.path("unit").asText("")}")
            }
            appendLine()
        }
    }

    private fun statement(область: Area): String = buildString {
        appendLine("# Постановка: цели, нужды, сервисы")
        appendLine()
        listOf("goal" to "Цели миссии", "need" to "Нужды стейкхолдеров", "service" to "Сервисы").forEach { (вид, заголовок) ->
            val строки = живые(область, вид)
            appendLine("## $заголовок (${строки.size})")
            appendLine()
            if (строки.isEmpty()) appendLine("_пусто_")
            строки.forEach { о ->
                val текст = listOf("statement", "name").firstNotNullOfOrNull { о.doc.path(it).asText("").ifBlank { null } } ?: ""
                val хвост = buildList {
                    о.doc.path("year").asText("").takeIf { it.isNotBlank() }?.let { add("год $it") }
                    о.doc.path("qos_class").asText("").takeIf { it.isNotBlank() }?.let { add("класс $it") }
                }
                appendLine("- `${о.code}` $текст" + (if (хвост.isEmpty()) "" else " _(${хвост.joinToString(", ")})_"))
            }
            appendLine()
        }
    }

    private fun stakeholders(область: Area): String = buildString {
        appendLine("# Стейкхолдеры")
        appendLine()
        val стороны = живые(область, "stakeholder")
        if (стороны.isEmpty()) appendLine("_пусто_")
        стороны.forEach { с ->
            appendLine("- `${с.code}` ${с.doc.path("name").asText("")}" +
                с.doc.path("role").asText("").takeIf { it.isNotBlank() }?.let { " — роль: $it" }.orEmpty())
        }
    }

    private fun normatives(): String = buildString {
        appendLine("# Нормативы полки")
        appendLine()
        appendLine("Реквизиты по памяти не восстанавливаются: чего здесь нет — того нет.")
        appendLine()
        val акты = живые(Area.Library, "normative_document")
        if (акты.isEmpty()) appendLine("_нормативов нет_")
        акты.forEach { нр ->
            appendLine("## `${нр.code}` ${нр.doc.path("designation").asText("")} ${нр.doc.path("title").asText("")}".trimEnd())
            appendLine()
            нр.doc.path("edition_date").asText("").takeIf { it.isNotBlank() }?.let { appendLine("- редакция: $it") }
            нр.doc.path("valid_until").asText("").takeIf { it.isNotBlank() }?.let { appendLine("- действует до: $it") }
            val пункты = нр.doc.path("clauses")
            if (!пункты.isArray || пункты.isEmpty) {
                appendLine("- _пункты не внесены: знание этого норматива системе недоступно_")
            } else {
                appendLine()
                пункты.forEach { п ->
                    append("- **${п.path("clause").asText("")}** — ${п.path("text").asText("")}")
                    п.path("limit").takeIf { it.isObject }?.let { l ->
                        append(" _(порог: ${l.path("key").asText("")} ${l.path("op").asText("")} ${l.path("value").asText("")} ${l.path("unit").asText("")})_")
                    }
                    appendLine()
                }
            }
            appendLine()
        }
    }

    private fun glossary(): String = buildString {
        appendLine("# Глоссарий")
        appendLine()
        val термины = живые(Area.Library, "glossary_term")
        if (термины.isEmpty()) appendLine("_терминов нет_")
        термины.forEach { т ->
            appendLine("- **${т.doc.path("term_ru").asText(т.code)}** — ${т.doc.path("definition").asText("")}")
        }
    }

    private fun materials(project: String, область: Area): String = buildString {
        appendLine("# Каноны материалов проекта")
        appendLine()
        appendLine("Якоря `sN` (заголовки) и `sN#M` (абзацы) — координаты оснований:")
        appendLine("ссылайся на них в `anchors`, иначе утверждение непроверяемо.")
        appendLine()
        val материалы = живые(область, "material")
        if (материалы.isEmpty()) appendLine("_материалов нет_")
        материалы.forEach { м ->
            appendLine("## `${м.code}` ${м.doc.path("name").asText("")} _(${м.doc.path("kind").asText("reference")})_")
            appendLine()
            intake.canon(project, м.code).forEach { б ->
                if (б.kind == "heading") appendLine("### ${б.text} {#${б.anchor}}") else appendLine("${б.text} <!-- ${б.anchor} -->")
            }
            appendLine()
        }
    }

    /** Факты по диспозиции: принятые · допущенные · замеченные — каждый с якорем; свободные и отклонённые не идут. */
    private fun facts(project: String): String = buildString {
        appendLine("# Факты проекта")
        appendLine()
        appendLine("Только с решением человека: принят · допущен · замечен. Якорь — блок канона")
        appendLine("материала (часть «материалы»); факт без якоря не существует.")
        appendLine()
        val факты = intake.facts(project)
        listOf(
            Disposition.ADOPTED to "Принятые (adopted)",
            Disposition.ASSUMED to "Допущенные (assumed) — допущение с владельцем и точкой подтверждения",
            Disposition.NOTED to "Замеченные (noted)",
        ).forEach { (д, заголовок) ->
            val свои = факты.filter { it.disposition == д }.sortedBy { it.id }
            appendLine("## $заголовок (${свои.size})")
            appendLine()
            if (свои.isEmpty()) appendLine("_нет_")
            свои.forEach { appendLine(строкаФакта(it)) }
            appendLine()
        }
    }

    private fun строкаФакта(ф: Fact): String = buildString {
        append("- `${ф.id}` [${ф.mark.name.first()}] ${ф.subject} — ${ф.predicate}: ${ф.value}")
        ф.unit?.takeIf { it.isNotBlank() }?.let { append(" $it") }
        append(" [${ф.material}#${ф.anchor ?: "?"}]")
        ф.assumption?.let { append(" _(допущение: ${it.owner}, до ${it.confirmBy}, проверка: ${it.validation})_") }
        if (ф.conflicts.isNotEmpty()) append(" ⚠ конфликт с ${ф.conflicts.joinToString(", ")}")
    }
}
