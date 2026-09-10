// Условия ворот сцен 10–12: технологии, риски, стоимость.
package orbita.programmatics.internal

import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.programmatics.api.Programmatics
import orbita.readiness.api.CheckResult
import orbita.readiness.api.ExtraChecks

class ProgrammaticsChecks(
    private val store: EntityStore,
    private val programmatics: Programmatics,
) : ExtraChecks {

    /**
     * Дата вехи по её ключу ИЛИ идентификатору.
     *
     * Срок риска хранится идентификатором (маршрут разрешает ключ в id при
     * заведении), а условия шаблона называют точку ключом. Искать только по
     * коду значило молча не находить ни одного срока — и точка объявляла бы
     * себя готовой, ничего не проверив.
     */
    private fun дата(область: Area, ключ: String): String? = ключ.takeIf { it.isNotBlank() }
        ?.let { store.byCode(область, it) ?: store.byId(it) }
        ?.doc?.path("planned_date")?.asText("")
        ?.takeIf { it.isNotBlank() }

    override fun of(project: String, check: String): CheckResult? {
        val область = Area.Project(project)
        val (имя, аргумент) = check.split(":", limit = 2).let { it[0] to it.getOrNull(1) }

        return when (имя) {
            "technologies_named" -> {
                val нужно = (аргумент ?: "1").toIntOrNull() ?: 1
                val есть = store.list(область, "technology").size
                if (есть >= нужно) CheckResult.ok
                else CheckResult.no(
                    "критических технологий названо $есть из $нужно: " +
                        "к MCR список критических технологий с TRL обязателен",
                )
            }

            "maturation_planned" -> {
                // Ворота НАБЛЮДАЮТ: читающее состояние, а не генератор.
                // Пока условие звало генератор, оно само закрывало разрыв,
                // который проверяло, и отказать не могло никогда.
                val открытые = programmatics.maturationState(project)
                    .filter { it.trlCurrent < it.trlRequired }
                val без = открытые.filter { it.packageCode == null || it.milestoneGate == null }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "без плана созревания: " + без.joinToString(", ") { it.technology } +
                        " — разрыв TRL требует пакета работ и вехи",
                )
            }

            "risks_min" -> {
                val нужно = (аргумент ?: "3").toIntOrNull() ?: 3
                val есть = store.list(область, "risk").size
                if (есть >= нужно) CheckResult.ok
                else CheckResult.no("рисков $есть из $нужно: пустой реестр рисков означает, что их не искали")
            }

            "each_risk_has_due_point" -> {
                val без = store.list(область, "risk")
                    .filter { it.doc.path("due_point").asText("").isBlank() }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "без срока-точки: " + без.take(3).joinToString(", ") { it.code } +
                        " — срок без точки не наступает",
                )
            }

            /**
             * Риски и TBR, чей срок наступил К ЭТОЙ ТОЧКЕ.
             *
             * Правило владельца 08.09: точка фазы считает сроки ПО ДАТЕ, а
             * не по виду вехи. Срок риска — любая веха: и точка фазы, и
             * веха технологии («к вехе TRL 6 датчикового терминала» точнее,
             * чем «к SRR»). Значит и сравнивать надо даты, иначе риск,
             * привязанный к вехе технологии, тихо переживёт точку, к
             * которой должен был закрыться.
             */
            "risks_due_closed" -> {
                val точка = аргумент ?: return CheckResult.no("условию нужен ключ точки")
                val датаТочки = дата(область, точка)
                    ?: return CheckResult.no("у точки «$точка» нет даты: сроки не с чем сравнивать")
                val просроченные = store.list(область, "risk")
                    .filter { it.status != "closed" }
                    .filter { риск ->
                        val срок = дата(область, риск.doc.path("due_point").asText(""))
                        срок != null && срок <= датаТочки
                    }
                val tbr = store.list(область, "parameter")
                    .filter { !it.doc.path("measure").path("tbr").isMissingNode }
                    .filter { п ->
                        val срок = дата(область, п.doc.path("measure").path("tbr").path("gate").asText(""))
                        срок != null && срок <= датаТочки
                    }
                when {
                    просроченные.isEmpty() && tbr.isEmpty() -> CheckResult.ok
                    else -> CheckResult.no(
                        buildString {
                            if (просроченные.isNotEmpty()) {
                                append("открытых рисков со сроком к $точка: ")
                                append(просроченные.take(3).joinToString(", ") { it.code })
                            }
                            if (tbr.isNotEmpty()) {
                                if (isNotEmpty()) append("; ")
                                append("неснятых TBR: ")
                                append(tbr.take(3).joinToString(", ") { it.code })
                            }
                            append(" — срок наступил, а решения нет")
                        },
                    )
                }
            }

            "oda_started" -> {
                val оценка = store.list(область, "debris_assessment").lastOrNull()
                if (оценка != null) CheckResult.ok
                else CheckResult.no("оценки засорения нет: ODA к MCR считается от базового варианта")
            }

            "wbs_paired" -> {
                val пакеты = programmatics.packages(project)
                val без = пакеты.filter { !it.crossCutting && it.pbsRefs.isEmpty() }
                when {
                    пакеты.isEmpty() -> CheckResult.no("WBS не взят: пакетов работ в проекте нет")
                    без.isNotEmpty() -> CheckResult.no(
                        "пакетов без пары к узлу: ${без.size} — " + без.take(3).joinToString(", ") { it.code },
                    )
                    else -> CheckResult.ok
                }
            }

            "estimate_ranged" -> {
                val оценки = store.list(область, "cost_estimate")
                val плохие = оценки.filter {
                    it.doc.path("range").path("max").asDouble() <= it.doc.path("range").path("min").asDouble() ||
                        it.doc.path("assumptions").asText("").isBlank()
                }
                when {
                    оценки.isEmpty() -> CheckResult.no("оценки нет: к KDP нужен ROM диапазоном с допущениями")
                    плохие.isNotEmpty() -> CheckResult.no(
                        "оценка без диапазона или допущений: " + плохие.take(3).joinToString(", ") { it.code },
                    )
                    else -> CheckResult.ok
                }
            }

            "estimate_includes_maturation" -> {
                // Тоже наблюдение: ворота ничего не заводят.
                val разрывы = programmatics.maturationState(project)
                    .filter { it.trlCurrent < it.trlRequired }
                if (разрывы.isEmpty()) return CheckResult.ok
                val сОценкой = programmatics.packages(project)
                    .filter { it.estimate != null }.map { it.code }.toSet()
                val без = разрывы.filter { it.packageCode !in сОценкой }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no(
                    "стоимость не включает созревание: " + без.joinToString(", ") { it.technology } +
                        " — оценка без пакетов созревания при открытых TRL считает не тот объём работ",
                )
            }

            // --- Phase A, условия владельца 10.09 (УСЛОВИЯ-СЦЕН-PHASE-A) ----------

            // A8: владелец у каждого риска.
            "each_risk_has_owner" -> {
                val без = store.list(область, "risk").filter { it.status != "closed" && it.doc.path("owner").asText("").isBlank() }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no("рисков без владельца: " + без.take(3).joinToString(", ") { it.code } + " — риск без владельца никто не ведёт")
            }
            // A8: мера у каждого критического (вероятность × влияние ≥ порога).
            "critical_risks_have_measures" -> {
                val порог = (аргумент ?: "12").toIntOrNull() ?: 12
                val критические = store.list(область, "risk").filter { р ->
                    р.status != "closed" && р.doc.path("probability").asInt(0) * р.doc.path("impact").asInt(0) >= порог
                }
                val без = критические.filter { р -> р.doc.path("measures").let { м -> м.isMissingNode || м.isNull || (м.isTextual && м.asText().isBlank()) || (м.isArray && м.isEmpty) } }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no("критических рисков без меры: " + без.take(3).joinToString(", ") { it.code } + " (критичность ≥ $порог требует меры)")
            }
            // A7: у технологии низкого TRL назван резерв.
            "technology_fallback_named" -> {
                val порог = (аргумент ?: "4").toIntOrNull() ?: 4
                val без = store.list(область, "technology").filter { т ->
                    т.doc.path("trl_current").asInt(9) <= порог &&
                        т.doc.path("fallback").asText("").isBlank() && т.doc.path("fallback_component").asText("").isBlank()
                }
                if (без.isEmpty()) CheckResult.ok
                else CheckResult.no("технологии TRL ≤ $порог без резерва: " + без.take(3).joinToString(", ") { it.doc.path("name").asText(it.code) } + " — резерв называется до SDR")
            }
            // A9: ОСЗ базового варианта — оба случая по нормативу.
            "oda_compliant" -> {
                val оценка = store.list(область, "debris_assessment").lastOrNull()
                    ?: return CheckResult.no("оценки засорения нет: ОСЗ считается от базового варианта")
                val активный = оценка.doc.path("compliant_active").asBoolean(false)
                val пассивный = оценка.doc.path("compliant_passive").asBoolean(false)
                when {
                    активный && пассивный -> CheckResult.ok
                    else -> CheckResult.no(
                        "ОСЗ ${оценка.code}: " + listOfNotNull(
                            if (!активный) "активный увод вне норматива" else null,
                            if (!пассивный) "пассивный сход вне норматива" else null,
                        ).joinToString(", "),
                    )
                }
            }
            // A9: норматив увода привязан к оценке.
            "oda_normative_bound" -> {
                val оценка = store.list(область, "debris_assessment").lastOrNull()
                    ?: return CheckResult.no("оценки засорения нет: привязывать норматив не к чему")
                val нет = listOf("normative_active", "normative_passive").filter { оценка.doc.path(it).asText("").isBlank() }
                if (нет.isEmpty()) CheckResult.ok
                else CheckResult.no("у ОСЗ ${оценка.code} не привязан норматив: " + нет.joinToString(", ") + " — норма увода берётся с полки нормативов")
            }
            // A10: метод оценки к KDP-B — параметрика или снизу вверх, не ROM.
            "estimate_method" -> {
                val методы = (аргумент ?: "parametric,bottom_up").split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                val оценки = store.list(область, "cost_estimate")
                when {
                    оценки.isEmpty() -> CheckResult.no("оценки нет")
                    оценки.any { it.doc.path("method").asText("") in методы } -> CheckResult.ok
                    else -> CheckResult.no("оценка только методом " + оценки.map { it.doc.path("method").asText("—") }.distinct().joinToString("/") + " — к KDP-B нужна " + методы.joinToString(" либо "))
                }
            }
            // A10: свёртка сроков WBS против дат точек — расхождение разрывом.
            "plan_consistent_with_gates" -> {
                val пакеты = programmatics.packages(project)
                val сПланом = пакеты.filter { it.planEnd != null }
                if (пакеты.isEmpty()) return CheckResult.no("WBS не взят: сверять сроки не с чем")
                if (сПланом.isEmpty()) return CheckResult.no("ни у одного пакета работ нет сроков: свёртка сроков пуста")
                val точки = store.list(область, "gate").filter { it.doc.path("kind").asText("phase") != "technology" }
                val последняя = точки.mapNotNull { it.doc.path("planned_date").asText("").ifBlank { null } }.maxOrNull()
                    ?: return CheckResult.no("у точек фазы нет дат: сроки пакетов не с чем сверять")
                val позже = сПланом.filter { it.planEnd!! > последняя }
                if (позже.isEmpty()) CheckResult.ok
                else CheckResult.no("пакеты заканчиваются позже последней точки ($последняя): " + позже.take(3).joinToString(", ") { "${it.code} → ${it.planEnd}" })
            }

            else -> null
        }
    }
}
