// Условия ворот, которые знает контур архитектуры (сцена 7).
package orbita.architecture.internal

import orbita.architecture.api.Architecture
import orbita.architecture.api.Nature
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks

class ArchitectureChecks(
    private val store: EntityStore,
    private val architecture: Architecture,
) : ExtraChecks {

    override fun of(project: String, check: String): CheckResult? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let { it[0] to it.getOrNull(1) }

        return when (имя) {
            "composition_min" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val узлы = architecture.components(project)
                if (узлы.size >= нужно) CheckResult.ok
                else CheckResult.no("узлов состава ${узлы.size} из $нужно: состав берётся каркасом класса миссии")
            }

            // Phase A (шип G): у элемента состава назван хотя бы один стык.
            "node_interfaces_min" -> {
                val (код, число) = (аргумент ?: "").split(":", limit = 2).let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
                val узел = store.byCode(область, код) ?: return CheckResult.no("узла «$код» в составе нет")
                val стыки = store.list(область, "interface").filter { с ->
                    с.status != "cancelled" && listOf("a", "b").any { к ->
                        val конец = с.doc.path(к).asText("")
                        конец == узел.id || конец == узел.code
                    }
                }
                if (стыки.size >= число) CheckResult.ok
                else CheckResult.no("у узла ${узел.code} стыков ${стыки.size} из $число — заведите стык в архитектуре (сцена 7 / A4)")
            }
            "each_behaviour_deployed" -> {
                val без = architecture.components(project)
                    .filter { it.nature == Nature.BEHAVIOUR }
                    .filter { узел ->
                        architecture.card(project, узел.code).facets
                            .single { it.key == "deployment" }.lines.none { it.what.startsWith("развёрнут") }
                    }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "поведение без носителя: " + без.joinToString(", ") { it.code } +
                        " — behaviour обязан быть развёрнут хотя бы на одном node",
                )
            }

            "baseline_concept_chosen" -> {
                val концепция = store.list(область, "baseline_concept").lastOrNull()
                when {
                    концепция == null -> CheckResult.no(
                        "базовый вариант не назван: выбор без обоснования не решение",
                    )
                    концепция.doc.path("rationale").asText("").isBlank() -> CheckResult.no(
                        "у базового варианта нет обоснования: отклонённые остаются с причинами",
                    )
                    else -> CheckResult.ok
                }
            }

            "component_stage_closed" -> {
                val точка = аргумент ?: "MCR"
                val разрывы = architecture.gaps(project, точка)
                if (разрывы.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "ступень $точка не закрыта у ${разрывы.map { it.component }.distinct().size} узлов: " +
                        разрывы.take(2).joinToString("; ") { it.what },
                )
            }

            // --- сцена 9: режимы и операционные сценарии -------------------
            // Живут здесь, а не в отдельном модуле: и режимы, и цепочки —
            // прочтение СОСТАВА со стороны применения, и знает его архитектура.

            "modes_defined" -> {
                val машины = store.list(область, "state_machine")
                val сСостояниями = машины.filter { it.doc.path("states").size() >= 2 }
                when {
                    машины.isEmpty() -> CheckResult.no(
                        "машины режимов нет: ConOps §4 нечем наполнить — " +
                            "назовите режимы аппарата в сцене 9",
                    )
                    сСостояниями.isEmpty() -> CheckResult.no(
                        "у машины режимов меньше двух состояний: одно состояние — это не режим, " +
                            "а постоянное поведение",
                    )
                    else -> CheckResult.ok
                }
            }

            "scenarios_min" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val цепочки = store.list(область, "functional_chain")
                if (цепочки.size >= нужно) CheckResult.ok
                else CheckResult.no(
                    "операционных сценариев ${цепочки.size} из $нужно: сценарий — путь по составу, " +
                        "им наполняется ConOps §5",
                )
            }

            "each_scenario_has_steps" -> {
                val без = store.list(область, "functional_chain").filter { цепочка ->
                    val шаги = цепочка.doc.path("steps")
                    shagiPusty(шаги)
                }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "сценарии без шагов с участником: " + без.joinToString(", ") { it.code } +
                        " — шаг без участника не проверить: непонятно, кто это делает",
                )
            }

            // --- Phase A, условия владельца 10.09 (УСЛОВИЯ-СЦЕН-PHASE-A) ----------

            // A4: функции элемента распределены на узел экземпляра.
            "node_functions_min" -> {
                val (код, число) = узелИЧисло(аргумент)
                val узел = store.byCode(область, код) ?: return CheckResult.no("узла «$код» в составе нет")
                val свои = store.list(область, "function").filter { ф -> ссылается(ф.doc.path("allocated_to"), узел) }
                if (свои.size >= число) CheckResult.ok
                else CheckResult.no("на узел ${узел.code} распределено ${свои.size} функций из $число — функции SA распределяются на узлы 100 %")
            }
            // A4: ступень лестницы («архитектура» к SDR) закрыта у узла экземпляра.
            "node_stage_closed" -> {
                val (код, точка) = (аргумент ?: "").split(":", limit = 2).let { it[0] to (it.getOrNull(1) ?: "SDR") }
                val узел = store.byCode(область, код) ?: return CheckResult.no("узла «$код» в составе нет")
                val разрывы = architecture.gaps(project, точка).filter { it.component == узел.code }
                if (разрывы.isEmpty()) CheckResult.ok
                else CheckResult.no("ступень $точка не закрыта у ${узел.code}: " + разрывы.take(2).joinToString("; ") { it.what })
            }
            // A4: элементы обмена на стыках узла экземпляра (обмен → стык → узел).
            "node_exchange_items_min" -> {
                val (код, число) = узелИЧисло(аргумент)
                val узел = store.byCode(область, код) ?: return CheckResult.no("узла «$код» в составе нет")
                val стыкиУзла = store.list(область, "interface")
                    .filter { с -> listOf("a", "b").any { к -> ссылается(с.doc.path(к), узел) } }
                    .flatMap { listOf(it.id, it.code) }.toSet()
                val обмены = store.list(область, "exchange").filter { it.doc.path("interface").asText("") in стыкиУзла }
                    .flatMap { listOf(it.id, it.code) }.toSet()
                val элементы = store.list(область, "exchange_item").filter { э ->
                    э.doc.path("exchanges").any { it.asText() in обмены } ||
                        э.doc.path("interface").asText("") in стыкиУзла || ссылается(э.doc.path("node"), узел)
                }
                if (элементы.size >= число) CheckResult.ok
                else CheckResult.no("элементов обмена на стыках ${узел.code}: ${элементы.size} из $число — стык данных к SDR несёт элементы обмена")
            }
            // A5: функций без узла = 0.
            "functions_allocated" -> {
                val функции = store.list(область, "function").filter { it.status != "cancelled" && it.doc.path("layer").asText("SA") == "SA" }
                val без = функции.filter { it.doc.path("allocated_to").let { а -> а.isMissingNode || а.isNull || (а.isArray && а.isEmpty) || (а.isTextual && а.asText().isBlank()) } }
                when {
                    функции.isEmpty() -> CheckResult.no("функций слоя SA нет: распределять нечего — возьмите полку архитектуры или заведите функции")
                    без.isEmpty() -> CheckResult.ok
                    else -> CheckResult.no("функций без узла: ${без.size} — " + без.take(4).joinToString(", ") { it.code } + " (функция без носителя — не архитектура)")
                }
            }
            // A5: у каждой цепочки сценарное требование (realized_by ≥ 1).
            "chains_realized" -> {
                val цепочки = store.list(область, "functional_chain").filter { it.status != "cancelled" }
                val требования = store.list(область, "requirement")
                val без = цепочки.filter { ц -> требования.none { т -> т.doc.path("carrier").asText("").let { it == ц.id || it == ц.code } } }
                when {
                    цепочки.isEmpty() -> CheckResult.no("цепочек нет: сценарное требование не на что вешать")
                    без.isEmpty() -> CheckResult.ok
                    else -> CheckResult.no("цепочек без сценарного требования: " + без.take(4).joinToString(", ") { it.code } + " — цепочка реализует требование уровня scenario")
                }
            }
            // A5: бюджеты названных видов с политикой резервов.
            "budgets_min" -> {
                val виды = (аргумент ?: "mass,power,link").split(",").map { it.trim() }.filter { it.isNotBlank() }
                val бюджеты = store.list(область, "budget").filter { it.status != "cancelled" }
                val нет = виды.filter { в -> бюджеты.none { it.doc.path("kind").asText("") == в } }
                val безРезерва = бюджеты.filter { it.doc.path("kind").asText("") in виды && it.doc.path("reserve_policy").asText("").isBlank() }
                when {
                    нет.isNotEmpty() -> CheckResult.no("бюджетов нет: " + нет.joinToString(", ") + " — свёртка с двумя резервами против рамок обязательна к SDR")
                    безРезерва.isNotEmpty() -> CheckResult.no("бюджет без политики резервов: " + безРезерва.joinToString(", ") { it.code })
                    else -> CheckResult.ok
                }
            }
            // A5: логические компоненты развёрнуты (желательно — сцену не держит).
            "logical_components_deployed" -> {
                val лк = store.list(область, "logical_component").filter { it.status != "cancelled" }
                val без = лк.filter { it.doc.path("deployed_to").let { д -> !д.isArray || д.isEmpty } }
                when {
                    лк.isEmpty() -> CheckResult.no("логических компонентов нет: слой LA не развёрнут")
                    без.isEmpty() -> CheckResult.ok
                    else -> CheckResult.no("логических компонентов без узла: " + без.take(4).joinToString(", ") { it.code })
                }
            }
            // A6: варианты построения с метриками.
            "variants_min" -> {
                val нужно = (аргумент ?: "2").toIntOrNull() ?: 2
                val варианты = store.list(область, "constellation_variant").filter { it.status != "cancelled" }
                if (варианты.size >= нужно) CheckResult.ok
                else CheckResult.no("вариантов построения ${варианты.size} из $нужно: сравнивать один вариант не с чем")
            }
            // A6: базовый вариант — с отклонёнными и их причинами.
            "baseline_concept_with_rejected" -> {
                val концепция = store.list(область, "baseline_concept").lastOrNull()
                val отклонённые = концепция?.doc?.path("rejected")
                when {
                    концепция == null -> CheckResult.no("базовый вариант не назван: выбор без обоснования не решение")
                    концепция.doc.path("rationale").asText("").isBlank() -> CheckResult.no("у базового варианта нет обоснования")
                    отклонённые == null || !отклонённые.isArray || отклонённые.isEmpty ->
                        CheckResult.no("у базового варианта нет отклонённых: trade study без альтернатив — не сравнение")
                    отклонённые.any { it.path("reason").asText("").isBlank() } ->
                        CheckResult.no("отклонённый вариант без причины: протокол решения неполон")
                    else -> CheckResult.ok
                }
            }
            // A6: обязательные модели дали ответ и верифицированы.
            "model_runs_verified" -> {
                val коды = (аргумент ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (коды.isEmpty()) return CheckResult.no("условию нужен перечень моделей")
                val модели = store.list(область, "system_model")
                val прогоны = store.list(область, "model_run")
                val причины = коды.mapNotNull { код ->
                    val модель = модели.firstOrNull { it.code == код || it.doc.path("template_code").asText("") == код }
                        ?: return@mapNotNull "$код — модели в проекте нет"
                    val ответ = прогоны.filter { п -> п.doc.path("model").asText("").let { it == модель.id || it == модель.code } }
                        .any { п -> п.doc.path("outputs").let { it.isArray && !it.isEmpty } && !п.doc.path("proxy").asBoolean(false) }
                    val верифицирована = модель.doc.path("verification_status").asText("unverified") in setOf("verified", "validated")
                    when {
                        !ответ && !верифицирована -> "$код — нет ответа и не верифицирована"
                        !ответ -> "$код — ответа нет (прогон без прокси)"
                        !верифицирована -> "$код — не верифицирована"
                        else -> null
                    }
                }
                if (причины.isEmpty()) CheckResult.ok
                else CheckResult.no("модели: " + причины.joinToString("; "))
            }

            else -> null
        }
    }

    private fun узелИЧисло(аргумент: String?): Pair<String, Int> =
        (аргумент ?: "").split(":", limit = 2).let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }

    /** Поле ссылается на узел: значением-строкой либо массивом — по id или коду. */
    private fun ссылается(поле: com.fasterxml.jackson.databind.JsonNode, узел: orbita.kernel.api.Entity): Boolean = when {
        поле.isArray -> поле.any { it.asText("") == узел.id || it.asText("") == узел.code }
        поле.isTextual -> поле.asText() == узел.id || поле.asText() == узел.code
        else -> false
    }

    /** Пусто, если шагов нет или хотя бы один шаг не называет участника. */
    private fun shagiPusty(шаги: com.fasterxml.jackson.databind.JsonNode): Boolean =
        шаги.isEmpty || шаги.any { шаг ->
            шаг.path("actor").asText("").isBlank() && шаг.path("component").asText("").isBlank()
        }
}
