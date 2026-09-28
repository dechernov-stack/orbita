// Деривация требований вниз (§2.1 шипа 6) — ОБРАЗЕЦ помощника Phase A.
//
// Путь один и общий пяти помощникам (диф владельца 28.09,
// CODE-28-09-ОДИН-ПУТЬ): машина находит срез и ставит ворота, модель
// ФОРМУЛИРУЕТ, предложения ложатся прогоном постановки и принимаются той же
// сверкой. Своего приёма у помощника нет.
//
// Здесь: узел состава + требования, несомые узлом или его предком, →
// дочерние требования узла. Основание предложения — РОДИТЕЛЬСКОЕ ТРЕБОВАНИЕ
// (Basis.Entity), не факт: при приёме оно ложится в requirement.source
// (model_basis_rule). Ворота — машинные: родитель существует и уровнем ВЫШЕ;
// без родителя деривации нет. Единицу из справочника и «сумму долей бюджета ≤
// родителя» ставит §2.2-помощник мер — здесь их ещё нет (долг NEXT.md).
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiService
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.Intake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology
import java.security.MessageDigest

class RequirementDeriver(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /**
     * Вывести требования узла предложениями в РУЧНОЙ прогон постановки.
     *
     * Кнопка карточки узла ведёт в «Предложения» с отбором по этому прогону
     * (SR-N). Приёма своего нет: вердикт каждому выносит сверка, оттого здесь
     * все «новые».
     */
    fun deriveInto(project: String, node: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        require(author.isNotBlank()) { "у деривации нет автора: решение принимает человек" }
        val область = Area.Project(project)
        val узел = store.byCode(область, node) ?: error("узла «$node» нет в проекте")
        require(узел.kind == "component") { "«$node» — это ${узел.kind}, а не узел состава" }

        // Срез: требования, несомые узлом или его предком по составу. Носитель
        // хранится идентификатором (requirement.carrier = id), предки — по
        // ссылке component.parent вверх до корня.
        val предки = предки(область, узел)
        val родители = store.list(область, "requirement")
            .filter { it.status !in СНЯТЫЕ }
            .filter { val носитель = it.doc.path("carrier").asText(""); носитель.isNotBlank() && носитель in предки }
            .sortedBy { it.code }

        val промпт = промпт(узел, родители)
        val отпечаток = отпечаток(project, node, промпт)

        // Нет родителя — деривации нет (ворота): выводить вниз не из чего.
        // Прогон всё равно заводится, пустой и с причиной, — чтобы кнопка вела
        // в предложения, а не в тишину.
        if (родители.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "деривация «$node»: у узла и его предков нет требований — выводить вниз не из чего",
                emptyList(), listOf("нет родительского требования"),
                trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }

        val ответ = service.ask(
            project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = AnswerSchemas.деривация(mapper),
        )
        val корень = mapper.readTree(
            ответ.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        ).takeIf { it.isObject } ?: return synthesizer.record(
            project, author, отпечаток, "деривация «$node»: ответ модели не разобран",
            emptyList(), listOf("ответ модели не разобран"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )

        val поКоду = родители.associateBy { it.code }
        val предложения = mutableListOf<FormationProposal>()
        val отказы = mutableListOf<String>()
        корень.path("requirements").forEachIndexed { номер, узелОтвета ->
            runCatching { предложение(узелОтвета, поКоду, носитель = узел.id) }
                .onSuccess { предложения += it }
                .onFailure { отказы += "требование ${имя(узелОтвета, номер)}: ${it.message}" }
        }

        return synthesizer.record(
            project, author, отпечаток,
            "деривация «$node»: ${родители.size} родит., ${предложения.size} предл.",
            предложения, отказы, trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )
    }

    /**
     * Одно дочернее требование из ответа модели — с воротами. Негодное
     * отбивается поимённо (см. вызов), годное становится предложением с
     * основанием-родителем (Basis.Entity): при приёме родитель ложится в
     * requirement.source, а не в ссылку на факт.
     */
    private fun предложение(узел: JsonNode, поКоду: Map<String, Entity>, носитель: String): FormationProposal {
        val кодРодителя = узел.path("derives_from").asText("").trim()
        require(кодРодителя.isNotBlank()) { "деривация без родителя: не назван derives_from" }
        // Ворота: родитель существует среди требований узла или предков.
        val родитель = поКоду[кодРодителя]
            ?: throw IllegalArgumentException("родителя «$кодРодителя» нет среди требований узла или предков")
        val уровеньРодителя = родитель.doc.path("level").asText("")
        val уровень = узел.path("level").asText("")
        require(уровень.isNotBlank()) { "уровень дочернего требования не назван" }
        // Ворота: родитель уровнем ВЫШЕ. Деривация идёт вниз; равный или ниже —
        // не деривация (порядок уровней — из истины схем, не из копии в коде).
        require(выше(уровеньРодителя, уровень)) {
            "уровень родителя «$уровеньРодителя» не выше уровня «$уровень» — это не деривация вниз"
        }

        val payload = payload(узел, носитель)
        val понятие = GeneratedOntology.of("requirement")
        return FormationProposal(
            concept = "requirement",
            payload = payload,
            basis = listOf(Basis.Entity(kind = "requirement", code = родитель.code)),
            verdict = Verdict.NEW,
            // Метка достоверности выводного требования — [П]: это предложение
            // модели по принятому требованию, не свидетельство из документа.
            sourceMark = SourceMark.П,
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = FormationRules.нехватка(понятие, payload),
        )
    }

    /** Поля дочернего требования: носитель — этот узел (id), уровень и текст — от модели. */
    private fun payload(узел: JsonNode, носитель: String): Map<String, String> {
        val поля = linkedMapOf<String, String>()
        поля["level"] = узел.path("level").asText("")
        val формулировка = узел.path("statement").asText("").trim()
        поля["statement"] = формулировка
        поля["title"] = узел.path("title").asText("").ifBlank { формулировка.take(60) }
        узел.path("category").asText("").ifBlank { null }?.let { поля["category"] = it }
        узел.path("ears_pattern").asText("").ifBlank { null }?.let { поля["ears_pattern"] = it }
        // Носитель — этот узел. Хранится идентификатором: carrier читается через
        // store.byId (EntityRequirements), кодом не находится.
        поля["carrier"] = носитель
        return поля
    }

    /** Отпечаток среза — по промпту: тот же срез → тот же отпечаток → кэш вызова. */
    private fun отпечаток(project: String, node: String, промпт: String): String =
        MessageDigest.getInstance("SHA-256").digest("$project/$node/$промпт".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)

    /** Предки узла по составу (id): сам узел и вся цепочка component.parent вверх. */
    private fun предки(область: Area, узел: Entity): Set<String> {
        val ids = linkedSetOf(узел.id)
        var текущий = узел
        while (true) {
            val ссылка = текущий.doc.path("parent").asText("").ifBlank { break }
            val родитель = store.byId(ссылка) ?: store.byCode(область, ссылка) ?: break
            if (!ids.add(родитель.id)) break // страховка от цикла в составе
            текущий = родитель
        }
        return ids
    }

    /** Уровень «а» выше уровня «б» по порядку истины схем (requirement.level). */
    private fun выше(а: String, б: String): Boolean {
        val порядок = GeneratedKinds.byCode["requirement"]?.enums?.get("level").orEmpty()
        val ia = порядок.indexOf(а)
        val ib = порядок.indexOf(б)
        return ia in 0 until ib
    }

    private fun имя(узел: JsonNode, номер: Int): String =
        узел.path("id").asText("").ifBlank { "#$номер" }

    private fun промпт(узел: Entity, родители: List<Entity>): String {
        val шапка = buildString {
            appendLine("Ты — системный инженер. Выведи требования УЗЛА состава вниз из требований выше.")
            appendLine()
            appendLine("Узел: ${узел.code} «${узел.doc.path("name").asText(узел.code)}».")
            appendLine()
            appendLine("Требования, из которых выводишь (родительские, узла или его предка):")
        }
        val список = родители.joinToString("\n") { р ->
            val уровень = р.doc.path("level").asText("")
            val текст = р.doc.path("statement").asText("").ifBlank { р.doc.path("title").asText("") }
            val мера = р.doc.path("measure").takeIf { it.isObject }
                ?.let { " [${it.path("value").asText("")}${it.path("unit").asText("")}]" } ?: ""
            "- ${р.code} (уровень $уровень)$текст$мера"
        }
        val хвост = buildString {
            appendLine()
            appendLine("Для каждого выводимого требования назови derives_from (код родителя из списка),")
            appendLine("level НИЖЕ родителя, формулировку узла шаблоном EARS и, если требование мерное,")
            appendLine("measure (доля бюджета родителя) с единицей из справочника. Код не придумывай —")
            appendLine("derives_from только из списка. Ответ — по схеме.")
        }
        return шапка + список + хвост
    }

    private companion object {
        /** Пакет помощника: им, а не кодом понятия, отличают деривацию среди прогонов. */
        const val ПАКЕТ = "requirement_derivation"

        /** Вид вызова в журнале — свой у помощника, отдельно от синтеза поля. */
        const val KIND = "requirement_derivation"

        /** Причина запуска: ручной прогон помощника (истина synthesis_run.trigger). */
        const val РУЧНОЙ = "manual"

        const val БЮДЖЕТ = 16_000

        /** Снятые с учёта статусы — из списка не берём (те же, что у отбора синтеза). */
        val СНЯТЫЕ = setOf("cancelled", "rejected")
    }
}
