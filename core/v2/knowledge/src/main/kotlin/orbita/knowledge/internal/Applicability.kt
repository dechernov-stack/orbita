// Применимость (шип 4 §5, знания v3 шип 5): чужая нужда или цель → наши
// сервисы → вердикт · чего не хватает · предложение эксперту. Матрица
// считается ПО ДАННЫМ: возможности заводит чтение документа (`opportunity`),
// их основания — следы-факты с якорем; «не наш профиль» опирается на
// границы устава (пункт 2 записки «чего мы не строим»), и ссылка на них
// приходит строкой матрицы. Ни одного вызова модели.
package orbita.knowledge.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.kernel.api.Area
import orbita.kernel.api.Channel
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.kernel.api.Provenance
import orbita.kernel.schema.GeneratedKinds
import orbita.knowledge.api.Applicability
import orbita.knowledge.api.ApplicabilityMatrix
import orbita.knowledge.api.ExternalFactView
import orbita.knowledge.api.ExternalTargetRow
import orbita.knowledge.api.OpportunityRow

internal class ApplicabilityMatrixBuilder(
    private val store: EntityStore,
    private val links: LinkRegistry?,
) : Applicability {

    override fun matrix(project: String): ApplicabilityMatrix {
        val область = Area.Project(project)
        val границы = CharterPoints.boundaries(store, область)
        val сервисы = store.list(область, "service").filter { it.status != "cancelled" }
        val строки = store.list(область, "opportunity").filter { it.status != "cancelled" }
            .map { строка(область, it, сервисы, границы) }
            .sortedWith(compareBy({ ПОРЯДОК_ВЕРДИКТОВ.indexOf(it.verdict).let { i -> if (i < 0) 9 else i } }, { it.code }))
        val чужиеЦели = store.list(область, "fact")
            .filter { it.status != "cancelled" && it.doc.path("kind").asText() == "external_target" && it.doc.path("disposition").asText() != "rejected" }
            .map { чужаяЦель(область, it) }
        val поВердиктам = строки.groupingBy { it.verdict }.eachCount().toSortedMap()
        return ApplicabilityMatrix(
            project = project,
            rows = строки,
            byVerdict = поВердиктам,
            externalTargets = чужиеЦели,
            charterBoundaries = границы,
            note = buildString {
                append("применимостей ${строки.size}")
                if (строки.isNotEmpty()) append(": ").append(поВердиктам.entries.joinToString(" · ") { (в, n) -> "${словоВердикта(в)} $n" })
                if (чужиеЦели.isNotEmpty()) append("; чужих целей фактами ${чужиеЦели.size} — в реестр целей проекта они не идут")
                if (границы.isEmpty()) append("; границ устава не найдено: «не наш профиль» не на что сослаться — назовите роль «устав» записке с разделом «чего мы не строим»")
            },
        )
    }

    override fun decide(project: String, code: String, status: String, author: String, reason: String): OpportunityRow {
        val область = Area.Project(project)
        val запись = store.byCode(область, code)?.takeIf { it.kind == "opportunity" }
            ?: throw NoSuchElementException("применимости «$code» в проекте нет")
        require(status in СОСТОЯНИЯ) { "состояние «$status» не из модели истины: ${СОСТОЯНИЯ.joinToString(" · ")}" }
        require(author.isNotBlank()) { "решение по применимости принимает названный человек: автор пуст" }
        require(status != "rejected" || reason.isNotBlank()) { "отклонение без причины не ставится: назовите, почему это не наш случай" }
        val документ = запись.doc.deepCopy() as ObjectNode
        if (reason.isNotBlank()) документ.put("rationale", (документ.path("rationale").asText("").ifBlank { null }?.let { "$it; " } ?: "") + "$author: $reason")
        val обновлена = store.update(запись.id, документ, Provenance(Channel.MANUAL, author), status = status)
        return строка(область, обновлена, store.list(область, "service").filter { it.status != "cancelled" }, CharterPoints.boundaries(store, область))
    }

    private fun строка(область: Area, з: Entity, сервисы: List<Entity>, границы: List<orbita.knowledge.internal.CharterBoundary>): OpportunityRow {
        val д = з.doc
        val вердикт = д.path("verdict").asText("")
        val предложение = д.path("proposal").asText("").ifBlank { null }
        val основание = основание(область, з)
        // `external_item` по истине — ссылка на факт: слова чужой нужды живут в
        // самом факте; текст, оставшийся от чтения, показывается как есть.
        val чужая = д.path("external_item").asText("").let { т ->
            if (Regex("^F-\\d+$").matches(т.trim())) (store.byCode(область, т.trim())?.doc?.path("value")?.asText("")?.ifBlank { null } ?: основание?.value ?: т) else т
        }
        val наши = д.path("our_services").let { у ->
            if (у.isArray) у.map { it.asText() } else у.asText("").split(Regex("\\s*[·;,]\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        }
        val имена = наши.map { имяСервиса(it, сервисы) }
        val текстДляГраницы = listOf(д.path("rationale").asText(""), д.path("missing").asText(""), чужая).joinToString(" ")
        return OpportunityRow(
            code = з.code,
            status = з.status,
            verdict = вердикт,
            verdictWord = словоВердикта(вердикт),
            proposal = предложение,
            proposalWord = предложение?.let { СЛОВО_ПРЕДЛОЖЕНИЯ[it] ?: it },
            missing = д.path("missing").asText("").ifBlank { null },
            rationale = д.path("rationale").asText("").ifBlank { null },
            owner = д.path("owner").asText("").ifBlank { null },
            externalItem = чужая,
            external = основание,
            ourServices = наши,
            ourServiceNames = имена,
            scale = масштаб(д.path("scale")),
            confidence = д.path("confidence").let { if (it.isNumber) it.asDouble() else it.asText("").replace(',', '.').toDoubleOrNull() },
            // «Не наш профиль» — со ссылкой на границу устава: ближайший по словам блок «чего мы не строим».
            charterBoundary = if (вердикт == "not_our_profile") ближайшаяГраница(границы, текстДляГраницы) else null,
        )
    }

    /** Основание применимости — след-факт чужой нужды с якорем и документом. */
    private fun основание(область: Area, з: Entity): ExternalFactView? {
        val реестр = links ?: return null
        val факт = реестр.from(з.id, "derived_from_fact").mapNotNull { store.byId(it.to) }.firstOrNull { it.kind == "fact" && it.status != "cancelled" }
            ?: return null
        return фактВид(область, факт)
    }

    private fun фактВид(область: Area, факт: Entity): ExternalFactView {
        val материал = факт.doc.path("material").asText("").ifBlank { null }
        val карточка = материал?.let { store.byCode(область, it) }
        return ExternalFactView(
            code = факт.code,
            subject = факт.doc.path("subject").asText(""),
            predicate = факт.doc.path("predicate").asText(""),
            value = факт.doc.path("value").let { if (it.isObject) it.path("value").asText("") else it.asText("") },
            quote = факт.doc.path("quote").asText("").ifBlank { null },
            anchor = факт.doc.path("anchor").asText("").ifBlank { null },
            material = материал,
            materialName = карточка?.doc?.path("name")?.asText("")?.ifBlank { null },
            role = карточка?.doc?.path("role")?.asText("")?.ifBlank { null },
        )
    }

    private fun чужаяЦель(область: Area, факт: Entity): ExternalTargetRow {
        val вид = фактВид(область, факт)
        // Цели проекта, стоящие на этом факте, — внешние обоснования цели.
        val обосновывает = links?.to(факт.id, "derived_from_fact")?.mapNotNull { store.byId(it.from) }
            ?.filter { it.kind == "goal" && it.status != "cancelled" }?.map { it.code }.orEmpty()
        return ExternalTargetRow(
            code = факт.code, owner = вид.subject, statement = вид.value, quote = вид.quote, anchor = вид.anchor,
            material = вид.material, materialName = вид.materialName, grounds = обосновывает,
            disposition = факт.doc.path("disposition").asText("free"),
        )
    }

    private fun ближайшаяГраница(границы: List<orbita.knowledge.internal.CharterBoundary>, текст: String): orbita.knowledge.internal.CharterBoundary? {
        if (границы.isEmpty()) return null
        val слова = слова(текст)
        return границы.maxByOrNull { г -> слова(г.text).count { it in слова } } ?: границы.first()
    }

    private fun слова(текст: String): Set<String> =
        Regex("[\\p{L}]{4,}").findAll(текст.lowercase()).map { it.value.take(6) }.toSet()

    private fun имяСервиса(ссылка: String, сервисы: List<Entity>): String {
        val с = ссылка.trim()
        val поКоду = сервисы.firstOrNull { it.code.equals(с, ignoreCase = true) }
        val поИмени = поКоду ?: сервисы.firstOrNull { сервис -> сервис.doc.path("name").asText("").let { имя -> имя.isNotBlank() && (имя.equals(с, ignoreCase = true) || имя.lowercase().contains(с.lowercase()) || с.lowercase().contains(имя.lowercase())) } }
        return поИмени?.let { "${it.code} · ${it.doc.path("name").asText(it.code)}" } ?: с
    }

    private fun масштаб(узел: JsonNode): String? = when {
        узел.isMissingNode || узел.isNull -> null
        узел.isObject -> listOf(узел.path("value").asText(""), узел.path("unit").asText("")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
        else -> узел.asText("").ifBlank { null }
    }

    private fun словоВердикта(в: String): String = GeneratedKinds.enumLabels["opportunity.verdict"]?.get(в) ?: в.ifBlank { "без вердикта" }

    private companion object {
        val СОСТОЯНИЯ: List<String> = listOf("proposed", "accepted", "rejected")
        val ПОРЯДОК_ВЕРДИКТОВ: List<String> = listOf("covered", "partial", "needs_work", "not_our_profile")
        val СЛОВО_ПРЕДЛОЖЕНИЯ: Map<String, String> = mapOf(
            "extend_scope" to "расширить зону", "add_service" to "добавить сервис", "descope" to "в отложенное", "reject" to "отклонить",
        )
    }
}
