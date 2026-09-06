// Базирование и подозрительные связи (МОДЕЛЬ-ТРЕБОВАНИЯ-И-КОМПОНЕНТЫ §4, §6).
//
// Три вещи, которые здесь важнее кода:
//   1. Снимок — ОБЪЕКТ, а не флаг: имя, класс CM, точка, список (объект,
//      версия). Он неизменяем: перебазирование заводит НОВЫЙ снимок.
//   2. Незрелая технология не запрещает снимок, а делает элемент УСЛОВНЫМ:
//      TRL-шлюз пишется в сам снимок, а не в голову ведущему.
//   3. Помехи приходят СПИСКОМ и словами: «RQ-S-05: нет носителя», а не
//      «нельзя базировать».
package orbita.requirements.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.requirements.api.Applicability
import orbita.requirements.api.Baseline
import orbita.requirements.api.BaselineItem
import orbita.requirements.api.BaselineKind
import orbita.requirements.api.BaselineRefused
import orbita.requirements.api.Baselines
import orbita.requirements.api.Blocker
import orbita.requirements.api.Ears
import orbita.requirements.api.Firmness
import orbita.requirements.api.Level
import orbita.requirements.api.SuspectLink
import java.time.Instant
import java.time.OffsetDateTime

class EntityBaselines(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val mapper: ObjectMapper = ObjectMapper(),
) : Baselines {

    /** Лестница точек: до SRR метод верификации может быть TBD, после — нет. */
    private val лестница = listOf("MCR", "SRR", "SDR", "PDR", "CDR")

    override fun blockers(project: String, kind: BaselineKind, gate: String): List<Blocker> {
        val область = Area.Project(project)
        val помехи = mutableListOf<Blocker>()
        val строгоКМетоду = лестница.indexOf(gate.uppercase().substringBefore("-")) >= лестница.indexOf("SRR")

        store.list(область, "requirement")
            .filter { Level.of(it.doc.path("level").asText(null)) in kind.levels() }
            .forEach { требование ->
                val код = требование.code
                val док = требование.doc

                // Инвариант 1: без носителя, источника и метода — не базируется.
                val носитель = док.path("carrier").asText("").ifBlank { null }?.let { store.byId(it) }
                val уровень = Level.of(док.path("level").asText(null))
                if (носитель == null) {
                    помехи += Blocker(код, "И1", "нет носителя: без носителя — не требование")
                } else if (носитель.kind !in уровень.carrierKinds()) {
                    // Инвариант 2: природа носителя обязана совпадать с уровнем.
                    помехи += Blocker(
                        код, "И2",
                        "уровень «${уровень.name.lowercase()}» требует носителем ${уровень.carrierWords()}, " +
                            "а указан «${носитель.code}» вида «${носитель.kind}»",
                    )
                }
                if (док.path("source").isEmpty) {
                    помехи += Blocker(код, "И1", "нет источника: требование ниоткуда не выводится")
                }
                val метод = док.path("verification_method").asText("")
                if (метод.isBlank()) {
                    помехи += Blocker(код, "И1", "нет метода верификации: нечем будет доказать выполнение")
                } else if (метод.equals("TBD", ignoreCase = true) && строгоКМетоду) {
                    помехи += Blocker(код, "И1", "метод верификации TBD: к точке $gate он уже обязан быть выбран")
                }

                // Инвариант 2: характеристика без показателя не базируется.
                if (док.path("category").asText("") == "performance" &&
                    док.path("measure").let { it.isMissingNode || it.isNull }
                ) {
                    помехи += Blocker(код, "И2", "характеристика без показателя: проверить будет нечем")
                }

                if (док.path("acceptance_criteria").asText("").isBlank()) {
                    помехи += Blocker(код, "И1", "нет критерия приёмки: непонятно, что считать выполнением")
                }

                // Инвариант 4: экземпляр типового без применимости не существует.
                val шаблон = док.path("template_ref").asText("")
                if (шаблон.isNotBlank()) {
                    val применимость = док.path("applicability").asText("").ifBlank { null }
                        ?.let { runCatching { Applicability.valueOf(it.uppercase()) }.getOrNull() }
                    if (применимость == null) {
                        помехи += Blocker(код, "И4", "экземпляр типового без применимости")
                    } else if (применимость != Applicability.APPLIED &&
                        док.path("deviation_rationale").asText("").isBlank()
                    ) {
                        помехи += Blocker(код, "И4", "отклонение от типового без обоснования")
                    }
                }

                // Инвариант 7: формулировка вне шаблонов EARS — разрыв к точке.
                EarsLint.check(док.path("statement").asText(""), Ears.of(док.path("ears_pattern").asText(null)))
                    .forEach { помехи += Blocker(код, "И7", "${it.what} (${it.why})") }
            }

        // Инвариант 5: подозрительная связь — разрыв к следующей точке.
        suspects(project).forEach { помехи += Blocker(it.linkId, "И5", "связь подозрительна: ${it.why}") }

        return помехи
    }

    override fun baseline(
        project: String,
        name: String,
        kind: BaselineKind,
        gate: String,
        author: String,
    ): Baseline {
        val помехи = blockers(project, kind, gate)
        if (помехи.isNotEmpty()) throw BaselineRefused(помехи)

        val область = Area.Project(project)
        require(store.byCode(область, name) == null) {
            "снимок «$name» уже есть: базовая линия неизменяема, перебазирование заводит новое имя"
        }

        val незрелые = незрелыеТехнологии(область)
        val элементы = kind.kinds().flatMap { вид ->
            store.list(область, вид)
                .filter { вид != "requirement" || Level.of(it.doc.path("level").asText(null)) in kind.levels() }
                .map { сущность -> элемент(сущность, незрелые) }
        }.sortedBy { it.code }

        val документ = mapper.createObjectNode()
        документ.put("name", name)
        документ.put("kind", kind.name.lowercase())
        документ.put("gate", gate)
        документ.put("by", author)
        документ.put("at", OffsetDateTime.now().toString())
        val список = документ.putArray("items")
        элементы.forEach { э ->
            список.addObject().apply {
                put("ref", э.ref)
                put("code", э.code)
                put("entity_kind", э.kind)
                put("version", э.version)
                put("firmness", э.firmness.name.lowercase())
                э.conditionalOn?.let { put("conditional_on", it) }
                э.why?.let { put("why", it) }
            }
        }
        // Снимок неизменяем: заводится один раз со статусом, который прямо
        // говорит, что правка ему запрещена.
        store.create(
            name, "baseline", область, "8", документ,
            Provenance(Channel.MANUAL, author), status = "immutable",
        )
        return Baseline(name, kind, gate, элементы, author, документ.path("at").asText())
    }

    /**
     * Технологии, не дотянувшие TRL до требуемого: они и делают элементы
     * условными. Ключ — узел, на котором технология стоит.
     */
    private fun незрелыеТехнологии(область: Area): Map<String, Entity> =
        store.list(область, "technology")
            .filter { it.doc.path("trl_current").asInt(9) < it.doc.path("trl_required").asInt(0) }
            .associateBy { it.doc.path("component").asText("") }

    private fun элемент(сущность: Entity, незрелые: Map<String, Entity>): BaselineItem {
        // Условность наследуется от носителя: требование на узле, который
        // держится на незрелой технологии, тоже условно.
        val ключ = when (сущность.kind) {
            "requirement" -> сущность.doc.path("carrier").asText("")
            "parameter" -> сущность.doc.path("target").asText("")
            else -> сущность.id
        }
        val технология = незрелые[ключ] ?: незрелые[сущность.id]
        return if (технология == null) {
            BaselineItem(сущность.id, сущность.code, сущность.kind, сущность.version, Firmness.FIRM)
        } else {
            BaselineItem(
                сущность.id, сущность.code, сущность.kind, сущность.version, Firmness.CONDITIONAL,
                conditionalOn = технология.code,
                why = "технология «${технология.doc.path("name").asText(технология.code)}»: " +
                    "TRL ${технология.doc.path("trl_current").asInt()} " +
                    "из ${технология.doc.path("trl_required").asInt()} " +
                    "к точке ${технология.doc.path("required_by").asText("—")}",
            )
        }
    }

    override fun list(project: String): List<Baseline> =
        store.list(Area.Project(project), "baseline")
            .sortedBy { it.createdAt }
            .map { снимок ->
                Baseline(
                    name = снимок.code,
                    kind = runCatching {
                        BaselineKind.valueOf(снимок.doc.path("kind").asText("functional").uppercase())
                    }.getOrDefault(BaselineKind.FUNCTIONAL),
                    gate = снимок.doc.path("gate").asText(""),
                    items = снимок.doc.path("items").map {
                        BaselineItem(
                            it.path("ref").asText(), it.path("code").asText(),
                            it.path("entity_kind").asText(), it.path("version").asInt(),
                            if (it.path("firmness").asText() == "conditional") Firmness.CONDITIONAL else Firmness.FIRM,
                            it.path("conditional_on").asText("").ifBlank { null },
                            it.path("why").asText("").ifBlank { null },
                        )
                    },
                    by = снимок.doc.path("by").asText(""),
                    at = снимок.doc.path("at").asText(""),
                )
            }

    override fun suspects(project: String): List<SuspectLink> {
        val снимок = Snapshots.последний(store, project) ?: return emptyList()
        val зафиксировано = Snapshots.версии(снимок)
        if (зафиксировано.isEmpty()) return emptyList()

        val найденные = LinkedHashMap<String, SuspectLink>()
        зафиксировано.keys.forEach { id ->
            (links.from(id) + links.to(id)).forEach { связь ->
                if (связь.id in найденные) return@forEach
                val подтверждено = связь.confirmedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val причины = listOf(связь.from, связь.to).mapNotNull { конец ->
                    val сущность = store.byId(конец) ?: return@mapNotNull null
                    val была = зафиксировано[конец] ?: return@mapNotNull null
                    if (сущность.version <= была) return@mapNotNull null
                    // Подтверждение снимает подозрение ДО следующей правки:
                    // «я посмотрел» относится к тому, что было видно тогда.
                    if (подтверждено != null && !сущность.updatedAt.toInstant().isAfter(подтверждено)) {
                        return@mapNotNull null
                    }
                    "${сущность.code}: версия ${сущность.version} против ${была} в снимке «${снимок.code}»"
                }
                if (причины.isNotEmpty()) {
                    найденные[связь.id] = SuspectLink(
                        связь.id, связь.type, связь.from, связь.to,
                        причины.joinToString("; ") + " — связь ждёт подтверждения",
                    )
                }
            }
        }
        return найденные.values.toList()
    }

    override fun confirm(project: String, linkId: String, author: String) {
        val связь = links.byId(linkId) ?: error("связи «$linkId» нет: подтверждать нечего")
        val область = Area.Project(project).asText()
        val свои = listOf(связь.from, связь.to).mapNotNull { store.byId(it) }
        require(свои.any { it.area.asText() == область }) {
            "связь «$linkId» не из проекта $project"
        }
        links.confirm(linkId, author)
    }
}
