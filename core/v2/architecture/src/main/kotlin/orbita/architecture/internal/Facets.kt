// Грани компонента: сборка карточки из сущностей вокруг узла.
//
// Ни одна грань не «поле компонента»: функции, стыки, режимы, параметры,
// технологии живут отдельными сущностями и цепляются к узлу ссылкой или
// связью. Поэтому карточка — запрос, а не запись.
package orbita.architecture.internal

import orbita.architecture.api.ComponentView
import orbita.architecture.api.Facet
import orbita.architecture.api.FacetLine
import orbita.kernel.api.Area
import orbita.kernel.api.Entity
import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry

internal class Facets(
    private val store: EntityStore,
    private val links: LinkRegistry,
    private val лестница: Ladder,
) {

    /** Заголовки граней: карточка — вкладки, а не простыня полей. */
    private val заголовки = linkedMapOf(
        "identity" to "Идентичность",
        "functions" to "Функции",
        "deployment" to "Развёртывание",
        "interfaces" to "Порты и стыки",
        "exchange_items" to "Элементы обмена",
        "modes" to "Режимы и состояния",
        "parameters" to "Параметры",
        "models" to "Модели",
        "budgets" to "Бюджеты",
        "requirements" to "Требования",
        "verification" to "Верификация",
        "technologies" to "Технологии",
        "risks" to "Риски",
        "configuration" to "Конфигурация изделия",
        "standards" to "Нормативы",
        "supplier" to "Поставщик и ответственный",
        "documents" to "Документы",
    )

    /** Чего ждут в пустой грани — словами, а не пустотой. */
    val ожидания = mapOf(
        "functions" to "какие функции исполняет узел (распределение функций на узлы)",
        "deployment" to "на каком носителе развёрнут поведенческий компонент",
        "interfaces" to "стыки узла: с кем и по какой физике связан",
        "exchange_items" to "что именно идёт по обмену: команда, ТМ-параметр, сообщение — со структурой",
        "modes" to "режимы и переходы: что активно в каждом режиме",
        "parameters" to "величины анкеты узла с единицей, происхождением и классом зрелости",
        "models" to "какие модели читают параметры узла и какие их дают",
        "budgets" to "вклад узла в свёртки: масса · энергия · ресурсы вычисления · данные",
        "requirements" to "требования, носителем которых является узел",
        "verification" to "чем доказывается выполнение: испытание, анализ, инспекция, демонстрация",
        "technologies" to "критические технологии узла и их TRL",
        "risks" to "риски узла с владельцем и сроком",
        "configuration" to "единица конфигурации и версии изделия",
        "standards" to "стандарты разработки и квалификации узла",
        "supplier" to "кто разрабатывает узел и кто отвечает за него в проекте",
        "documents" to "спецификация узла, ICD его стыков, план верификации",
    )

    /** Собирает все грани узла разом: один проход по проектной области. */
    fun собрать(область: Area, узел: Entity, карточка: ComponentView): List<Facet> {
        val id = узел.id
        val стыки = store.list(область, "interface")
            .filter { it.doc.path("a").asText() == id || it.doc.path("b").asText() == id }
        val обмены = store.list(область, "exchange")
            .filter { обмен -> стыки.any { it.id == обмен.doc.path("interface").asText() } }
        val требования = store.list(область, "requirement")
            .filter { it.doc.path("carrier").asText() == id }
        val параметрыУзла = store.list(область, "parameter").filter { it.doc.path("target").asText() == id }
        val единицы = store.list(область, "configuration_item").filter { it.doc.path("component").asText() == id }

        val содержимое = mapOf(
            "identity" to listOf(
                FacetLine(
                    "${карточка.code} · ${карточка.name} · уровень ${карточка.level} · " +
                        "род ${карточка.nature.name.lowercase()} · вид ${карточка.kind}" +
                        (карточка.parent?.let { " · родитель $it" } ?: ""),
                    id,
                ),
            ),
            "functions" to store.list(область, "function")
                .filter { ф -> ф.doc.path("allocated_to").any { it.asText() == id } }
                .map { FacetLine("${it.code} ${it.doc.path("name").asText("")}", it.id) },
            "deployment" to (
                links.from(id, "deployed_on").mapNotNull { связь ->
                    store.byId(связь.to)?.let { FacetLine("развёрнут на ${it.code} (${it.doc.path("name").asText("")})", it.id) }
                } + links.to(id, "deployed_on").mapNotNull { связь ->
                    store.byId(связь.from)?.let { FacetLine("несёт ${it.code} (${it.doc.path("name").asText("")})", it.id) }
                }
                ),
            "interfaces" to стыки.map {
                FacetLine("${it.code} ${it.doc.path("name").asText("")} · ${it.doc.path("type").asText("")}", it.id)
            },
            "exchange_items" to обмены.flatMap { обмен ->
                val элементы = store.list(область, "exchange_item")
                    .filter { э -> э.doc.path("exchanges").any { it.asText() == обмен.id } }
                if (элементы.isEmpty()) {
                    listOf(FacetLine("${обмен.code}: ${обмен.doc.path("payload").asText("—")} (элемент обмена не описан)", обмен.id))
                } else {
                    элементы.map { э ->
                        val поля = э.doc.path("elements").joinToString(", ") { it.path("name").asText() }
                        FacetLine("${обмен.code} → ${э.code} {${поля}}", э.id)
                    }
                }
            },
            "modes" to store.list(область, "state_machine")
                .filter { it.doc.path("owner").asText() == id }
                .flatMap { машина ->
                    машина.doc.path("states").map { состояние ->
                        val активные = состояние.path("active_functions").joinToString(", ") {
                            store.byId(it.asText())?.code ?: it.asText()
                        }
                        FacetLine(
                            "${состояние.path("name").asText(состояние.path("code").asText())}" +
                                (if (активные.isBlank()) "" else " · активны: $активные"),
                            машина.id,
                        )
                    } + машина.doc.path("transitions").map { переход ->
                        FacetLine(
                            "переход ${переход.path("from").asText()} → ${переход.path("to").asText()}" +
                                " по ${переход.path("trigger").toString()}",
                            машина.id,
                        )
                    }
                },
            "parameters" to параметрыУзла.map {
                FacetLine(
                    "${it.doc.path("key").asText(it.code)} = ${it.doc.path("measure")} · " +
                        "${it.doc.path("maturity_class").asText("estimated")} ±${it.doc.path("uncertainty").asDouble(0.0)}%",
                    it.id,
                )
            },
            "models" to store.list(область, "system_model")
                .filter { модель ->
                    модель.doc.path("inputs").any { вход ->
                        параметрыУзла.any { it.id == вход.path("param_ref").asText() }
                    } || стыки.any { it.id == модель.doc.path("interface_ref").asText() }
                }
                .map { FacetLine("${it.code} ${it.doc.path("template_code").asText("")}", it.id) },
            "budgets" to store.list(область, "budget")
                .filter { it.doc.path("root").asText() == id }
                .map { FacetLine("бюджет ${it.doc.path("kind").asText("")}", it.id) },
            "requirements" to требования.map {
                FacetLine("${it.code} ${it.doc.path("title").asText("")}", it.id)
            },
            "verification" to store.list(область, "verification_event")
                .filter { событие -> требования.any { it.id == событие.doc.path("requirement").asText() } }
                .map { FacetLine("${it.code} ${it.doc.path("method").asText("")}", it.id) },
            "technologies" to store.list(область, "technology")
                .filter { it.doc.path("component").asText() == id }
                .map {
                    FacetLine(
                        "${it.doc.path("name").asText(it.code)}: TRL ${it.doc.path("trl_current").asInt()} → " +
                            "${it.doc.path("trl_required").asInt()} к ${it.doc.path("required_by").asText("—")}",
                        it.id,
                    )
                },
            "risks" to store.list(область, "risk")
                .filter { риск -> риск.doc.path("refs").any { it.asText() == id } }
                .map { FacetLine(it.doc.path("statement").asText(it.code), it.id) },
            "configuration" to (
                единицы.map { FacetLine("CI ${it.code} · ${it.doc.path("type").asText("")}", it.id) } +
                    store.list(область, "product_version")
                        .filter { версия -> единицы.any { it.id == версия.doc.path("ci").asText() } }
                        .map { FacetLine("версия изделия ${it.doc.path("version").asText("")}", it.id) }
                ),
            "standards" to узел.doc.path("standards").map { стандарт ->
                val документ = store.byId(стандарт.asText())
                FacetLine(документ?.code ?: стандарт.asText(), документ?.id)
            },
            "supplier" to links.to(id, "owns").mapNotNull { связь ->
                store.byId(связь.from)?.takeIf { it.kind == "stakeholder" }
                    ?.let { FacetLine("${it.doc.path("name").asText(it.code)} · ${it.doc.path("role").asText("поставщик")}", it.id) }
            },
            "documents" to links.to(id, "snapshot_of").mapNotNull { связь ->
                store.byId(связь.from)?.let { FacetLine("${it.code} ${it.doc.path("title").asText("")}", it.id) }
            },
        )

        val обязательные = лестница.обязательные(карточка.nature.name.lowercase(), "PDR")
        return заголовки.map { (ключ, заголовок) ->
            val строки = содержимое[ключ].orEmpty()
            Facet(
                key = ключ,
                title = заголовок,
                lines = строки,
                requiredTo = обязательные[ключ],
                expected = if (строки.isEmpty()) ожидания[ключ] else null,
            )
        }
    }

}
