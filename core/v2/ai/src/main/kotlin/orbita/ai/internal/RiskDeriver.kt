// Риски из разрывов фазы (§2.5 шипа 6) — помощник Phase A на общем каркасе
// (CODE-28-09-ОДИН-ПУТЬ): МАШИНА сама находит разрывы фазы (§2.5: «машина
// находит все разрывы сама, без модели; модель только пишет формулировку;
// ИИ не на критическом пути»), модель лишь ПЕРЕФОРМУЛИРУЕТ готовое условие ·
// событие · последствие. Разрывов четыре источника: TRL ниже порога точки
// (порт программатики), пустые обязательные грани лестницы к ближайшей точке
// (порт архитектуры), запас бюджета ниже политики (порт бюджетов — резерв
// съеден принятыми долями), цели без покрывающего требования (реестр связей).
// Предложения ложатся РУЧНЫМ прогоном постановки и принимаются той же сверкой.
// Своего приёма нет.
//
// Основание предложения — РАЗРЫВ (Basis.Gap: вид, узел, грань, точка): при
// приёме в source не ложится (SynthesisRoutes.основанияПолем) — ссылка риска
// на свой разрыв это срок-точка и ссылки на узла, которые машина кладёт в
// payload сама. Кандидат-факт эксперта не нужен: у риска истина формулировку
// называет, и ворота формулировки общей сверкой закрываются.
//
// Машинные ворота (тест на отказ у каждого, ЗАДАНИЕ-ШИП-6 §2.5):
//  · без разрыва риск не предлагается: основание-разрыв обязательно, его
//    четыре части стережёт конструктор Basis.Gap — разрыв назван не полностью,
//    предложения не будет вовсе;
//  · разрыв модели вне среза — отказ поимённо: модель переформулирует ТОЛЬКО
//    разрывы из списка, свой разрыв не выдумывает;
//  · дубль открытого риска — не new: вердикт выносит сверка по ключу
//    идентичности понятия (statement_core), годное здесь «новое»;
//  · модель недоступна — НЕ отказ: формулировка остаётся шаблонной
//    («Если TRL узла X к SDR останется N, то…»), ИИ не на критическом пути.
package orbita.ai.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiService
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.ProviderUnavailable
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.architecture.api.Architecture
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.knowledge.api.Budgets
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.SourceMark
import orbita.programmatics.api.Programmatics
import orbita.knowledge.schema.GeneratedOntology
import java.security.MessageDigest

class RiskDeriver(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val architecture: Architecture,
    private val programmatics: Programmatics,
    private val budgets: Budgets,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Разрыв фазы, который машина нашла сама: четыре части основания + слова. */
    private data class Разрыв(
        val ключ: String = "",
        /** Вид разрыва — kind основания-разрыва: trl · лестница · бюджет · требования. */
        val вид: String,
        /** Узел (код): компонент, технология без узла, потолок, цель. */
        val узел: String,
        /** Грань — что именно пусто или ниже порога. */
        val грань: String,
        /** Точка (код вехи) — к чему разрыв привязан. */
        val точка: String,
        /** Категория риска кодом перечня истины: техническое → technical и т.д. */
        val категория: String,
        /** Шаблонная формулировка — условие · событие · последствие. */
        val формулировка: Формулировка,
    )

    /** Формулировка риска: одно предложение и его три части (cec вида risk). */
    private class Формулировка(
        val statement: String,
        val condition: String,
        val event: String,
        val consequence: String,
    )

    /**
     * Вывести риски из разрывов фазы предложениями в РУЧНОЙ прогон постановки.
     * Кнопка «риски из разрывов фазы» реестра рисков (A8) ведёт в «Предложения»
     * по этому прогону; приём — та же сверка (вердикт каждому выносит она,
     * оттого годные здесь «новые»).
     */
    fun deriveInto(project: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        require(author.isNotBlank()) { "у деривации нет автора: решение принимает человек" }

        val срез = разрывы(project)
        val промпт = промпт(project, срез)
        val отпечаток = отпечаток(project, промпт)

        // Разрывов нет — прогон пустой, модель не зовём: формулировать нечего.
        if (срез.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "риски из разрывов: разрывов фазы не нашлось — TRL на месте, " +
                    "грани лестницы закрыты, запасы бюджетов целы, цели покрыты",
                emptyList(), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }

        // Модель — только перо: переформулирует шаблонное «если… то…».
        // Недоступна — остаётся шаблон (§2.5: ИИ не на критическом пути);
        // ответ не разобран — тоже: формулировка у машины есть всегда.
        var ответМодели: com.fasterxml.jackson.databind.JsonNode? = null
        var модельЖива = true
        // Причина недоступности — в заметку прогона: без неё живой день не
        // отличить «провайдер перегружен» от «поток оборван», а починка у
        // них разная (поймано днём живой модели шипа 6: причина терялась).
        var причинаНедоступности: String? = null
        try {
            val ответ = service.ask(project, KIND, промпт, maxTokens = бюджет(срез.size), schema = AnswerSchemas.риски(mapper))
            ответМодели = runCatching {
                mapper.readTree(
                    ответ.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
                )
            }.getOrNull().takeIf { it != null && it.isObject }
        } catch (e: ProviderUnavailable) {
            модельЖива = false
            причинаНедоступности = e.message
        }

        val предложения = mutableListOf<FormationProposal>()
        val отказы = mutableListOf<String>()
        срез.forEach { разрыв ->
            val узелОтвета = ответМодели?.path("risks")?.firstOrNull { it.path("id").asText("") == разрыв.ключ }
            if (узелОтвета != null) {
                // Ворота разрыва: модель переформулирует ТОЛЬКО свой разрыв из
                // среза; формулировка пустая — отказ поимённо, шаблон не берём:
                // пустая формулировка — беспокойство, а не риск.
                val формулировка = узелОтвета.path("statement").asText("").trim()
                if (формулировка.isBlank()) {
                    отказы += "${разрыв.ключ}: пустая формулировка риска — отклонено"
                } else {
                    предложения += предложение(
                        project, разрыв,
                        Формулировка(
                            statement = формулировка,
                            condition = узелОтвета.path("condition").asText("").trim().ifBlank { разрыв.формулировка.condition },
                            event = узелОтвета.path("event").asText("").trim().ifBlank { разрыв.формулировка.event },
                            consequence = узелОтвета.path("consequence").asText("").trim().ifBlank { разрыв.формулировка.consequence },
                        ),
                    )
                }
            } else {
                // Модель не ответила по этому разрыву — шаблонная формулировка.
                предложения += предложение(project, разрыв, разрыв.формулировка)
            }
        }

        val счёт = срез.groupingBy { it.вид }.eachCount()
        val словом = счёт.entries.joinToString(" · ") { (вид, n) -> "$вид $n" }
        val заметка = buildString {
            append("риски из разрывов: $словом, ${предложения.size} предл.")
            if (!модельЖива) append(" — модель недоступна (${причинаНедоступности ?: "причина не названа"}), формулировки шаблонные")
            if (ответМодели == null && модельЖива) append(" — ответ модели не разобран, формулировки шаблонные")
        }
        return synthesizer.record(project, author, отпечаток, заметка, предложения, отказы, trigger = РУЧНОЙ, пакет = ПАКЕТ)
    }

    /**
     * Разрывы фазы — четыре источника, каждый свой порт, машина считает сама:
     * TRL ниже порога точки · пустые обязательные грани к ближайшей точке ·
     * запас бюджета ниже политики · цели без покрывающего требования. У
     * источников без своей точки (бюджет, цели) точка — ближайшая непройденная
     * веха фазы; вехи нет — разрыва к сроку нет, и риск из него не выводится.
     */
    private fun разрывы(project: String): List<Разрыв> {
        val область = Area.Project(project)
        val точка = ближайшаяТочка(project)
        val срез = mutableListOf<Разрыв>()

        // TRL ниже порога точки: созревание читается СОСТОЯНИЕМ (матurationState
        // ничего не заводит — критерий наблюдает, а не действует).
        срез += programmatics.maturationState(project)
            .filter { it.trlCurrent < it.trlRequired }
            .filter { it.requiredBy.isNotBlank() && it.requiredBy != "—" }
            .map { m ->
                val узел = m.component ?: m.technology
                Разрыв(
                    вид = "trl", узел = узел, грань = "TRL ${m.trlCurrent}→${m.trlRequired}", точка = m.requiredBy,
                    категория = "technical",
                    формулировка = Формулировка(
                        statement = "Если TRL $узел к точке ${m.requiredBy} останется ${m.trlCurrent} при требуемом " +
                            "${m.trlRequired}, то узел не пройдёт ${m.requiredBy}: технология не созрела к сроку",
                        condition = "TRL $узел — ${m.trlCurrent} при требуемом ${m.trlRequired}",
                        event = "созревание к точке ${m.requiredBy} не завершено",
                        consequence = "$узел не проходит ${m.requiredBy}: пакет созревания не закрыт",
                    ),
                )
            }

        // Грани лестницы, пустые к ближайшей точке: весь подсчёт — у порта
        // архитектуры (ворота точек его же считают; здесь только переупаковка).
        if (точка != null) {
            срез += architecture.gaps(project, точка).map { g ->
                Разрыв(
                    вид = "лестница", узел = g.component, грань = g.facet, точка = точка, категория = "technical",
                    формулировка = Формулировка(
                        statement = "Если к точке ${g.gate} грань «${g.facet}» узла ${g.component} останется пустой, " +
                            "то узел не пройдёт ${g.gate}: ${g.what}",
                        condition = "грань «${g.facet}» узла ${g.component} пуста",
                        event = "точка ${g.gate} наступает без закрытой грани «${g.facet}»",
                        consequence = g.what,
                    ),
                )
            }
        }

        // Запас бюджета ниже политики: резерв задан числом (reserve_pct — и есть
        // политика, словами она только для документа) и съеден принятыми долями.
        if (точка != null) {
            срез += store.list(область, "budget").mapNotNull { бюджет ->
                val потолокId = бюджет.doc.path("requirement").asText("").ifBlank { null } ?: return@mapNotNull null
                val потолок = store.byId(потолокId)
                val состояние = потолок?.let { budgets.forCeiling(project, it.code) }
                if (потолок == null || состояние == null) return@mapNotNull null
                if (состояние.reservePct == null || состояние.accepted <= состояние.cap) return@mapNotNull null
                val узел = бюджет.doc.path("root").asText("").ifBlank { null }
                    ?.let { store.byId(it)?.code } ?: потолок.code
                Разрыв(
                    вид = "бюджет", узел = узел, грань = "запас бюджета «${потолок.code}»", точка = точка,
                    категория = "cost",
                    формулировка = Формулировка(
                        statement = "Если принятые доли «${потолок.code}» достигнут ${число(состояние.accepted)} " +
                            "${состояние.unit} при потолке с резервом ${число(состояние.cap)} ${состояние.unit}, " +
                            "то запас бюджета исчерпан к $точка",
                        condition = "принято ${число(состояние.accepted)} ${состояние.unit} при потолке с резервом " +
                            "${число(состояние.cap)} ${состояние.unit} у «${потолок.code}»",
                        event = "доли съедают резерв, положенный политикой",
                        consequence = "запас «${потолок.code}» исчерпан к $точка: новые доли нечем закрыть",
                    ),
                )
            }
        }

        // Цели без покрывающего требования: покрытие — связь derives_from
        // (требование покрывает цель); у цели без неё условие сцены 8 не выйдет.
        if (точка != null) {
            срез += store.list(область, "goal")
                .filter { it.status != "cancelled" }
                .filter { links.to(it.id, "derives_from").isEmpty() }
                .map { цель ->
                    Разрыв(
                        вид = "требования", узел = цель.code, грань = "покрытие требованием", точка = точка,
                        категория = "programmatic",
                        формулировка = Формулировка(
                            statement = "Если цель «${цель.code}» к точке $точка останется без покрывающего " +
                                "требования, то цель не закроется на приёмке точки",
                            condition = "у цели «${цель.code}» нет покрывающего требования",
                            event = "приёмка точки $точка без покрытия цели",
                            consequence = "цель «${цель.code}» не закрывается требованием: условие сцены 8 не выходит",
                        ),
                    )
                }
        }

        return срез.mapIndexed { i, разрыв -> разрыв.copy(ключ = "g${i + 1}") }
    }

    /**
     * Одно предложение риска из разрыва: основание-разрыв обязательно (без
     * разрыва риска нет — сторож в конструкторе Basis.Gap), срок-точка и
     * ссылки на узла — ссылка риска на свой разрыв (в source при приёме
     * разрыв не ложится, SynthesisRoutes.основанияПолем). Вероятность,
     * последствия, стратегия, меры и владелец — поля человека (истина
     * онтологии риска: их называет сцена 11), машина их не выдумывает.
     */
    private fun предложение(project: String, разрыв: Разрыв, формулировка: Формулировка): FormationProposal {
        val область = Area.Project(project)
        val payload = linkedMapOf<String, String>()
        payload["statement"] = формулировка.statement
        payload["cec"] = mapper.createObjectNode()
            .put("condition", формулировка.condition)
            .put("event", формулировка.event)
            .put("consequence", формулировка.consequence)
            .toString()
        payload["category"] = разрыв.категория
        // Срок-точка — id вехи, как у ручной формы риска (ModelRoutes.завестиРиск).
        payload["due_point"] = store.byCode(область, разрыв.точка)?.id ?: разрыв.точка
        // Ссылки вида risk держат component|scene: узел-компонент кладём,
        // узел-цель/технология без узла — нет (разрыв всё равно в основании).
        if (store.byCode(область, разрыв.узел)?.kind == "component") {
            payload["refs"] = mapper.createArrayNode().add(разрыв.узел).toString()
        }

        val понятие = GeneratedOntology.of("risk")
        return FormationProposal(
            concept = "risk",
            payload = payload,
            basis = listOf(
                Basis.Gap(
                    kind = разрыв.вид,
                    node = разрыв.узел,
                    facet = разрыв.грань,
                    point = разрыв.точка,
                ),
            ),
            verdict = Verdict.NEW,
            // Достоверность — И: разрыв из собственной модели проекта, не из
            // внешнего материала; ранга у разрыва нет (это не свидетельство).
            sourceMark = SourceMark.И,
            missing = FormationRules.нехватка(понятие, payload),
        )
    }

    /** Ближайшая непройденная веха фазы: с датой — по дате, без — по коду. */
    private fun ближайшаяТочка(project: String): String? {
        val область = Area.Project(project)
        val точки = store.list(область, "gate")
            .filter { it.status != "cancelled" && it.status != "passed" }
            .filter { it.doc.path("kind").asText("phase") == "phase" }
        return точки.filter { it.doc.path("planned_date").asText("").isNotBlank() }
            .sortedBy { it.doc.path("planned_date").asText("") }
            .firstOrNull()?.code
            ?: точки.sortedBy { it.code }.firstOrNull()?.code
    }

    /** Отпечаток среза — по промпту: тот же срез → тот же отпечаток → кэш вызова. */
    private fun отпечаток(project: String, промпт: String): String =
        MessageDigest.getInstance("SHA-256").digest("$project/$промпт".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)

    /** Число словами без хвоста «.0» — срезы читают люди. */
    private fun число(значение: Double): String =
        if (значение % 1.0 == 0.0) значение.toLong().toString() else значение.toString()

    private fun промпт(project: String, срез: List<Разрыв>): String {
        val шапка = buildString {
            appendLine("Ты — системный инженер проекта. Переформулируй РИСКИ из разрывов фазы.")
            appendLine("Разрыв нашла машина — он есть, спорить с ним нельзя; твоя работа — только формулировка.")
            appendLine("Риск — одно предложение: условие · событие · последствие. По-русски, без канцелярита.")
            appendLine()
            appendLine("Разрывы проекта $project:")
        }
        val разрывы = срез.joinToString("\n") { r ->
            "- ${r.ключ}: разрыв ${r.вид} — узел ${r.узел} · ${r.грань} · точка ${r.точка}; " +
                "заготовка: ${r.формулировка.statement}"
        }
        val хвост = buildString {
            appendLine()
            appendLine("Для каждого разрыва назови риск: id (ключ разрыва из списка), statement (одно предложение),")
            appendLine("condition (условие), event (событие), consequence (последствие).")
            appendLine("Разрыв вне списка не выдумывай, пустую формулировку не давай. Ответ — по схеме.")
        }
        return шапка + разрывы + хвост
    }

    private companion object {
        /** Пакет помощника: им, не кодом понятия, отличают риски-из-разрывов среди прогонов. */
        const val ПАКЕТ = "risk_from_gap"

        /** Вид вызова в журнале — свой у помощника. */
        const val KIND = "risk_from_gap"

        /** Причина запуска: ручной прогон помощника (истина synthesis_run.trigger). */
        const val РУЧНОЙ = "manual"
    }

    /**
     * Бюджет ответа — от числа разрывов, а не константой: константа 8000 на
     * срезе в 671 разрыв дала пустой видимый ответ — модель съела весь бюджет
     * скрытым мышлением над входом в 71 тыс. токенов и оборвалась по
     * max_tokens, ни разу не коснувшись JSON (день живой модели шипа 6,
     * PJ-ПМИ7: «провайдер вернул пустой ответ»). Счёт: формулировка — строка
     * JSON в ~60–80 токенов; 120 на разрыв даёт запас на мышление. Малый срез
     * держит прежний минимум 8000.
     */
    private fun бюджет(разрывов: Int): Int = maxOf(8_000, разрывов * 120)
}
