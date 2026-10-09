// Анкета узла из паспорта изделия (§2.4 шипа 6) — помощник Phase A на общем
// каркасе (CODE-28-09-ОДИН-ПУТЬ): машина находит срез (поставщик узла полем,
// диф владельца 27.09-b; его документы роли supplier — происхождение полем
// истины «ref stakeholder | str»; ключи анкеты из полки шаблонов; единицы
// справочника), модель ФОРМУЛИРУЕТ величины с цитатой и якорем, предложения
// ложатся РУЧНЫМ прогоном постановки и принимаются той же сверкой. Своего
// приёма нет.
//
// Основание предложения — СЛЕД-ФАКТ паспорта (величина с цитатой и якорем,
// формой как у разбора даташита): общая сверка принимает величину своим
// фактом документа, без кандидата-факта эксперта.
//
// Машинные ворота (тест на отказ у каждого, ЗАДАНИЕ-ШИП-6 §2.4):
//  · цитата — в каноне документа, якорь — из якорей канона (как у приёма
//    фактов разбора: факт без якоря не существует, EntityIntake.putFacts);
//  · единица — из справочника (вид unit хранилища);
//  · ключ — из анкеты узла (полка шаблонов, template_ref узла);
//  · число без цитаты — отказ (сторож чисел, истина онтологии понятия
//    parameter: «число без цитаты — отказ»).
package orbita.ai.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiService
import orbita.ai.api.Basis
import orbita.ai.api.FormationProposal
import orbita.ai.api.SynthesisRun
import orbita.ai.api.Verdict
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.Provenance
import orbita.knowledge.api.Authority
import orbita.knowledge.api.CanonBlock
import orbita.knowledge.api.FormationRules
import orbita.knowledge.api.Intake
import orbita.knowledge.api.SourceMark
import orbita.knowledge.schema.GeneratedOntology
import java.security.MessageDigest

class QuestionnaireDeriver(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Документ поставщика с его каноном: цитаты и якоря сверяются по нему. */
    private class Документ(val материал: Entity, val блоки: List<CanonBlock>) {
        val код: String get() = материал.code
        val канон: String = блоки.joinToString("\n") { it.text }
        val якоря: Set<String> = блоки.map { it.anchor }.toSet()
    }

    /** Срез картины: что машина нашла до вызова модели. */
    private class Срез(
        val поставщик: Entity?,
        val документы: List<Документ>,
        /** Ключи анкеты узла: ключ к единице умолчания из полки шаблонов. */
        val ключи: Map<String, String>,
        /** Единицы справочника (вид unit, область Library). */
        val единицы: Set<String>,
    )

    /**
     * Вывести анкету узла из паспорта изделия предложениями в РУЧНОЙ прогон
     * постановки. Строка долга «параметры» лестницы (A4/A5) ведёт в
     * «Предложения» по этому прогону; приём — та же сверка (вердикт каждому
     * выносит она, оттого годные здесь «новые»).
     */
    fun deriveInto(project: String, node: String, author: String, synthesizer: Synthesizer): SynthesisRun {
        require(author.isNotBlank()) { "у деривации нет автора: решение принимает человек" }
        val область = Area.Project(project)
        val узел = store.byCode(область, node) ?: error("узла «$node» нет в проекте")
        require(узел.kind == "component") { "«$node» — это ${узел.kind}, а не узел состава" }

        val срез = срез(project, область, узел)
        val промпт = промпт(узел, срез)
        val отпечаток = отпечаток(project, node, промпт)

        // Ворота среза — по порядку, с причиной, модель не зовём.
        val ссылкаПоставщика = узел.doc.path("supplier").asText("").trim()
        if (ссылкаПоставщика.isBlank()) {
            return synthesizer.record(
                project, author, отпечаток,
                "анкета «$node»: поставщик узла не назван — назначьте сторону-поставщика (грань «Поставщик» карточки узла)",
                emptyList(), listOf("поставщик узла не назван"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }
        if (срез.поставщик == null) {
            return synthesizer.record(
                project, author, отпечаток,
                "анкета «$node»: поставщик «$ссылкаПоставщика» не заведён стороной проекта",
                emptyList(), listOf("поставщик узла не заведён стороной"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }
        if (срез.документы.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "анкета «$node»: у поставщика «${срез.поставщик.code}» нет документа в проекте — " +
                    "загрузите паспорт изделия с ролью «поставщик» и происхождением-поставщиком",
                emptyList(), listOf("у поставщика нет документа в проекте"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }
        if (срез.ключи.isEmpty()) {
            return synthesizer.record(
                project, author, отпечаток,
                "анкета «$node»: ключей анкеты нет — полки шаблонов компонентов нет либо шаблон без параметров",
                emptyList(), listOf("анкета узла не собрана"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
            )
        }

        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ, schema = AnswerSchemas.анкета(mapper))
        val корень = mapper.readTree(
            ответ.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
        ).takeIf { it.isObject } ?: return synthesizer.record(
            project, author, отпечаток, "анкета «$node»: ответ модели не разобран",
            emptyList(), listOf("ответ модели не разобран"), trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )

        val поКодуДок = срез.документы.associateBy { it.код }
        val предложения = mutableListOf<FormationProposal>()
        val отказы = mutableListOf<String>()
        корень.path("parameters").forEachIndexed { номер, узелОтвета ->
            runCatching { предложение(узелОтвета, узел, срез, поКодуДок, область, author) }
                .onSuccess { предложения += it }
                .onFailure { отказы += "величина ${имя(узелОтвета, номер)}: ${it.message}" }
        }

        return synthesizer.record(
            project, author, отпечаток,
            "анкета «$node»: ${срез.ключи.size} ключ., ${срез.документы.size} док., ${предложения.size} предл.",
            предложения, отказы, trigger = РУЧНОЙ, пакет = ПАКЕТ,
        )
    }

    /**
     * Срез: поставщик узла полем (диф 27.09-b) → сторона; его документы роли
     * supplier, чьё происхождение — эта сторона (id · код · имя, истина:
     * «ref stakeholder | str»); ключи анкеты из полки шаблонов
     * (questionnaireKeys — тот же срез, что у промпта даташита чтения);
     * единицы справочника из вида unit.
     */
    private fun срез(project: String, область: Area, узел: Entity): Срез {
        val ссылка = узел.doc.path("supplier").asText("").trim()
        val поставщик = ссылка.takeIf { it.isNotBlank() }
            ?.let { store.byId(it) ?: store.byCode(область, it) }
            ?.takeIf { it.kind == "stakeholder" }

        val документы = store.list(область, "material")
            .filter { it.status !in СНЯТЫЕ && it.doc.path("role").asText("") == "supplier" }
            .filter { отПоставщика(область, it, ссылка, поставщик) }
            .sortedBy { it.code }
            .map { Документ(it, intake.canon(project, it.code)) }

        val ключи = intake.questionnaireKeys(project, узел.code).toMap()
        val единицы = store.list(Area.Library, "unit")
            .map { it.doc.path("symbol").asText("") }.filter { it.isNotBlank() }.toSet()
        return Срез(поставщик, документы, ключи, единицы)
    }

    /** Документ от поставщика, если его происхождение — эта сторона: id, код либо имя (истина: ref | str). */
    private fun отПоставщика(область: Area, документ: Entity, ссылка: String, поставщик: Entity?): Boolean {
        val происхождение = документ.doc.path("origin").asText("").trim()
        if (происхождение.isBlank()) return false
        if (происхождение == ссылка) return true
        if (поставщик != null) {
            if (происхождение == поставщик.id || происхождение == поставщик.code) return true
            val запись = store.byId(происхождение) ?: store.byCode(область, происхождение)
            if (запись?.id == поставщик.id) return true
            val имя = поставщик.doc.path("name").asText("").trim()
            if (имя.isNotBlank() && происхождение.equals(имя, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Одна величина из ответа модели — с воротами. Негодное отбивается
     * поимённо (см. вызов), годное — след-фактом паспорта и предложением
     * parameter с основанием-фактом. Сторона target — идентификатором, как
     * ручная форма параметра (ArchRoutes.завестиПараметр).
     */
    private fun предложение(
        узел: JsonNode,
        узелСостава: Entity,
        срез: Срез,
        поКодуДок: Map<String, Документ>,
        область: Area,
        author: String,
    ): FormationProposal {
        // Ворота ключа: ключ анкеты — из полки шаблонов, других не бывает.
        val ключ = узел.path("key").asText("").trim()
        require(ключ.isNotBlank()) { "величина без ключа анкеты" }
        require(ключ in срез.ключи) {
            "ключа анкеты «$ключ» нет: анкета узла — ${срез.ключи.keys.joinToString(" · ")}"
        }
        // Ворота документа: цитируется тот, что в срезе.
        val кодДок = узел.path("doc").asText("").trim()
        val документ = поКодуДок[кодДок]
            ?: throw IllegalArgumentException("документа «$кодДок» нет в срезе: паспорт изделия поставщика — ${поКодуДок.keys.joinToString(" · ")}")
        // Сторож чисел: величина без цитаты не существует (истина онтологии
        // понятия parameter). Число обязательно, единица — из справочника.
        val значениеТекст = узел.path("value").asText("").trim()
        require(значениеТекст.isNotBlank()) { "величина без числа: ключ «$ключ»" }
        val число = значениеТекст.replace(",", ".").toDoubleOrNull()
            ?: throw IllegalArgumentException("величина «$значениеТекст» не числом: ключ «$ключ»")
        val цитата = узел.path("quote").asText("").trim()
        require(цитата.isNotBlank()) {
            "число $значениеТекст без цитаты отклонено (сторож чисел): ключ «$ключ» — назовите документ словами"
        }
        val канонНорм = норм(документ.канон)
        require(норм(цитата) in канонНорм) {
            "цитаты «${цитата.take(80)}» нет в каноне документа «${документ.код}» — придуманную величину не принимаем"
        }
        val якорь = узел.path("anchor").asText("").trim()
        require(якорь.isNotBlank()) { "цитата без якоря: ключ «$ключ» — не найти место в документе" }
        require(якорь in документ.якоря) {
            "якоря «$якорь» нет в каноне документа «${документ.код}»: ${документ.якоря.joinToString(" · ")}"
        }
        val единица = узел.path("unit").asText("").trim()
        require(единица.isNotBlank()) { "величина без единицы: ключ «$ключ» (истина: величина без единицы — не факт)" }
        require(единица in срез.единицы) {
            "единицы «$единица» нет в справочнике: ${срез.единицы.joinToString(" · ")}"
        }

        val payload = linkedMapOf<String, String>()
        payload["target"] = узелСостава.id
        payload["key"] = ключ
        payload["measure"] = mapper.createObjectNode()
            .put("value", число)
            .put("unit", единица)
            .toString()
        payload["origin"] = "datasheet"
        payload["source"] = mapper.createObjectNode()
            .put("material", документ.код)
            .put("anchor", якорь)
            .toString()

        // След-факт паспорта — величина с цитатой и якорем, формой как у разбора
        // даташита (шип E): kind quantity · мера объектом · quote · anchor ·
        // param_key. Предложение ссылается на него основанием-ФАКТОМ: общая
        // сверка принимает величину СВОИМ фактом документа (кандидат с
        // основанием сверяется своим фактом), а кандидат-факт эксперта не
        // нужен — он требовал бы поля формулировки, которого у вида parameter
        // истина не названа: её роль у величины играет цитата в следе.
        val документФакта = mapper.createObjectNode()
        документФакта.put("kind", "quantity")
        документФакта.put("subject", узелСостава.code)
        документФакта.put("predicate", ключ)
        документФакта.set<JsonNode>(
            "value",
            mapper.createObjectNode().put("value", число).put("unit", единица),
        )
        документФакта.put("quote", цитата)
        документФакта.put("anchor", якорь)
        документФакта.put("material", документ.код)
        // Паспорт поставщика — внешний материал: помета «В», ранг — ранг
        // документа, у документа без ранга — экспертный (рука эксперта).
        документФакта.put("mark", SourceMark.В.name)
        документФакта.put(
            "rank",
            документ.материал.doc.path("rank").asText("").trim().ifBlank { null }
                ?: Authority.EXPERT,
        )
        документФакта.put("param_key", ключ)
        документФакта.put("disposition", "free")
        документФакта.set<JsonNode>(
            "source",
            mapper.createObjectNode().put("material", документ.код).put("anchor", якорь),
        )
        val кодФакта = следующийФакт(область)
        store.create(
            кодФакта, "fact", область, null, документФакта,
            Provenance(Channel.SERVICE, author, source = документ.код, anchor = якорь),
        )

        val понятие = GeneratedOntology.of("parameter")
        return FormationProposal(
            concept = "parameter",
            payload = payload,
            basis = listOf(
                Basis.Fact(
                    factId = кодФакта,
                    material = документ.код,
                    anchor = якорь,
                    mark = SourceMark.В,
                    source = orbita.knowledge.api.FactSource.FromMaterial(документ.код, якорь),
                ),
            ),
            verdict = Verdict.NEW,
            // Метка достоверности — самая слабая из меток оснований: паспорт
            // поставщика внешний (В).
            sourceMark = SourceMark.В,
            confidence = узел.path("confidence").takeIf { it.isNumber }?.asDouble(),
            missing = FormationRules.нехватка(понятие, payload),
        )
    }

    /** Код след-факта: следующий «F-N» области, как у приёма разбора. */
    private fun следующийФакт(область: Area): String {
        val занято = store.list(область, "fact").mapNotNull {
            Regex("^F-(\\d+)$").find(it.code)?.groupValues?.get(1)?.toIntOrNull()
        }
        return "F-%04d".format((занято.maxOrNull() ?: 0) + 1)
    }

    /** Отпечаток среза — по промпту: тот же срез → тот же отпечаток → кэш вызова. */
    private fun отпечаток(project: String, node: String, промпт: String): String =
        MessageDigest.getInstance("SHA-256").digest("$project/$node/$промпт".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)

    private fun имя(узел: JsonNode, номер: Int): String =
        узел.path("id").asText("").ifBlank { "#$номер" }

    /** Нормализация цитаты и канона: регистр и пробелы не должны ломать сверку. */
    private fun норм(текст: String): String = Regex("\\s+").replace(текст.lowercase().replace('ё', 'е'), " ").trim()

    private fun промпт(узел: Entity, срез: Срез): String {
        val шапка = buildString {
            appendLine("Ты — системный инженер. Выведи АНКЕТУ узла состава из паспорта изделия поставщика.")
            appendLine("Величина — ключ анкеты, число, единица из справочника, цитата ДОСЛОВНО из документа и якорь её блока.")
            appendLine()
            appendLine("Узел: ${узел.code} «${узел.doc.path("name").asText(узел.code)}».")
            срез.поставщик?.let {
                appendLine("Поставщик: ${it.code} «${it.doc.path("name").asText(it.code)}».")
            }
            appendLine("Ключи анкеты (ключ · единица умолчания):")
        }
        val ключи = срез.ключи.entries.joinToString("\n") { (ключ, единица) ->
            "- $ключ · ${единица.ifBlank { "—" }}"
        }
        val документы = buildString {
            срез.документы.forEach { документ ->
                appendLine()
                appendLine("Документ ${документ.код} «${документ.материал.doc.path("name").asText(документ.код)}»:")
                документ.блоки.forEach { блок ->
                    appendLine("[${блок.anchor}] ${блок.text}")
                }
            }
        }
        val хвост = buildString {
            appendLine()
            appendLine("Для каждого ключа, значение которого нашлось в документах, назови parameter:")
            appendLine("id (p1, p2…), key (из списка), value (число как в документе), unit (из справочника),")
            appendLine("quote (дословный фрагмент текста документа), anchor (якорь блока в квадратных скобках),")
            appendLine("doc (код документа). Ключ вне списка не выдумывай, цитату не перефразируй:")
            appendLine("величина без дословной цитаты отклоняется. Ответ — по схеме.")
        }
        return шапка + ключи + документы + хвост
    }

    private companion object {
        /** Пакет помощника: им, не кодом понятия, отличают анкету-из-паспорта среди прогонов. */
        const val ПАКЕТ = "parameter_from_datasheet"

        /** Вид вызова в журнале — свой у помощника. */
        const val KIND = "parameter_from_datasheet"

        /** Причина запуска: ручной прогон помощника (истина synthesis_run.trigger). */
        const val РУЧНОЙ = "manual"

        const val БЮДЖЕТ = 16_000

        /** Снятые с учёта статусы — из списка не берём. */
        val СНЯТЫЕ = setOf("cancelled", "rejected")
    }
}
