// Представления экранов мастера Ш1–Ш7 (STEP-7-9 §9.1).
//
// Всё, что инженер видит на экране, собирается здесь: счётчики связей, покрытие
// классов потребителей, разрывы готовности. В клиенте расчётов нет — там только
// отрисовка.
package orbita.out

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.model.Lifecycle
import orbita.req.GateGap
import orbita.req.ReqService
import orbita.req.uncoveredConsumerClasses

/** Строка экрана 1: нужда стейкхолдера и её трассировка вниз. */
data class NeedRow(
    val id: String,
    val statement: String,
    val stakeholder: String,
    val role: String,
    val priority: Int,
    /** Сервисы, порождённые нуждой; пустой список — разрыв трассировки. */
    val services: List<String>,
    val status: String,
)

/** Показатель эффективности профиля QoS. */
data class MoeRow(val id: String, val name: String, val target: Double?, val unit: String?)

/** Профиль качества по классу потребителя (Р9: классы не усредняются). */
data class QosProfileRow(val consumerClass: String, val moe: List<MoeRow>)

/** Строка экрана 2: сервис и его профили. */
data class ServiceRow(
    val id: String,
    val name: String,
    val needs: List<String>,
    val profiles: List<QosProfileRow>,
    /** Классы, присутствующие в карте спроса, но без профиля. */
    val uncoveredClasses: List<String>,
    val requirements: List<String>,
    val status: String,
)

/** Шаг мастера: что он закрывает и чего в нём не хватает. */
data class WizardStep(
    val number: Int,
    val title: String,
    val objects: Int,
    val issues: List<String>,
    val complete: Boolean,
)

/** Экран 9: готовность к контрольной точке. */
data class ReadinessView(
    val gate: String,
    val gaps: List<GateGap>,
    val ready: Boolean,
    /** Доля объектов, дошедших до требуемого статуса. */
    val readyObjects: Int,
    val totalObjects: Int,
)

class WizardViews(private val req: ReqService) {

    fun needs(): List<NeedRow> = req.objects.listCurrent()
        .filter { it.type == "need" && it.status != Lifecycle.Cancelled }
        .map { n ->
            val doc = n.doc
            NeedRow(
                id = n.id,
                statement = doc.path("statement").asText(""),
                stakeholder = doc.path("stakeholder").path("name").asText(""),
                role = doc.path("stakeholder").path("role").asText(""),
                priority = doc.path("stakeholder").path("priority").asInt(0),
                services = req.links.linksFrom(n.id, "trace").map { it.toId }
                    .filter { it.startsWith("SV-") }.sorted(),
                status = n.status.name,
            )
        }.sortedBy { it.id }

    fun services(demandClasses: Set<String> = DEFAULT_CLASSES): List<ServiceRow> =
        req.objects.listCurrent()
            .filter { it.type == "service" && it.status != Lifecycle.Cancelled }
            .map { s ->
                val doc = s.doc
                ServiceRow(
                    id = s.id,
                    name = doc.path("name").asText(""),
                    needs = doc.path("traces_up").map { it.asText() },
                    profiles = doc.path("qos_profiles").map { p ->
                        QosProfileRow(
                            consumerClass = p.path("consumer_class").asText(""),
                            moe = p.path("moe").map { m ->
                                MoeRow(
                                    id = m.path("id").asText(""),
                                    name = m.path("name").asText(""),
                                    target = m.path("target").path("value").takeIf { it.isNumber }?.asDouble(),
                                    unit = m.path("target").path("unit").asText("").ifBlank { null },
                                )
                            },
                        )
                    },
                    uncoveredClasses = uncoveredConsumerClasses(doc, demandClasses),
                    requirements = req.links.linksFrom(s.id, "trace").map { it.toId }
                        .filter { it.startsWith("RQ-") }.sorted(),
                    status = s.status.name,
                )
            }.sortedBy { it.id }

    fun readiness(gate: String): ReadinessView {
        val gaps = req.readiness(gate)
        val total = req.objects.listCurrent().count { it.status != Lifecycle.Cancelled }
        return ReadinessView(
            gate = gate,
            gaps = gaps,
            ready = gaps.isEmpty(),
            readyObjects = total - gaps.size,
            totalObjects = total,
        )
    }

    /**
     * Состояние семи шагов мастера. Шаг считается закрытым, когда в нём есть
     * объекты и нет замечаний: «пусто» и «всё хорошо» — разные состояния,
     * и путать их нельзя.
     */
    fun wizard(screens: ScreenViews): List<WizardStep> {
        val needs = needs()
        val services = services()
        val tree = screens.requirementTree()
        val overview = screens.systemOverview()
        val components = req.objects.listCurrent()
            .count { (it.type == "component" || it.type == "interface") && it.status != Lifecycle.Cancelled }
        val risks = req.risks()

        return listOf(
            step(1, "Нужды", needs.size, needs.filter { it.services.isEmpty() }
                .map { "${it.id}: нужда не порождает сервисов" }),
            step(2, "Сервисы", services.size, services.flatMap { s ->
                s.uncoveredClasses.map { "${s.id}: класс $it есть в спросе, профиля нет" }
            }),
            step(3, "Требования", tree.rows.size, tree.rows.flatMap { r ->
                r.planIssues.map { "${r.id}: $it" }
            }),
            step(4, "Конфигурация", components, req.elementsWithoutRequirements()
                .map { "$it: на элемент не распределено ни одного требования" }),
            // Счётчик шага — действующие результаты расчётов, а не «есть ли
            // устаревшие»: число в подписи шага обязано что-то означать.
            step(5, "Моделирование", activeResults().size,
                req.results.staleReport().map { "результат ${it.pk} устарел" }),
            step(6, "Верификация", tree.rows.count { it.verificationState != NOT_PLANNED },
                tree.rows.filter { it.verificationState == NOT_PLANNED }
                    .map { "${it.id}: верификация не запланирована" }),
            step(7, "Пакет", risks.size, overview.problems),
        )
    }

    private fun step(number: Int, title: String, objects: Int, issues: List<String>) =
        WizardStep(number, title, objects, issues, complete = objects > 0 && issues.isEmpty())

    /** Действующие результаты по всем сценариям модели. */
    private fun activeResults() = req.objects.listCurrent()
        .filter { it.type == "scenario" && it.status != Lifecycle.Cancelled }
        .flatMap { req.results.activeForScenario(it.id) }

    companion object {
        /** Классы потребителей карты спроса (Р9). */
        val DEFAULT_CLASSES = setOf("A_prime", "B_prime", "C_prime")
        const val NOT_PLANNED = "не запланирована"
    }
}
