// Конфигуратор группировок (TZ-BAL-003): Walker Delta i:T/P/F разворачивается
// в перечень орбит; произвольный перечень тоже принимается; ССО без LTAN
// отклоняется. Эталон spec/ballistics_semantics.py, один в один.
package orbita.bal

import orbita.mod.store.ModelViolationException

/** Версия расчётного модуля — входит в input_versions результатов (TZ-COM-006). */
const val BAL_MODULE_VERSION = "0.2"

data class OrbitSlot(
    val plane: Int,
    val raanDeg: Double,
    val maDeg: Double,
    val incDeg: Double,
    val altKm: Double,
    val satId: String = "SAT-p$plane-${"%.1f".format(maDeg)}",
)

/** Walker Delta i:T/P/F → перечень орбит (RAAN, средняя аномалия). */
fun walkerDelta(incDeg: Double, t: Int, p: Int, f: Int, altKm: Double): List<OrbitSlot> {
    if (t % p != 0) throw ModelViolationException("TZ-BAL-003: T=$t не делится на P=$p")
    val s = t / p
    return buildList {
        for (plane in 0 until p) {
            val raanDeg = 360.0 * plane / p
            for (slot in 0 until s) {
                val maDeg = (360.0 * slot / s + 360.0 * f * plane / t).mod(360.0)
                add(OrbitSlot(plane, raanDeg, maDeg, incDeg, altKm))
            }
        }
    }
}

/**
 * Walker Star: плоскости веером в полукруге (RAAN шаг 180°/P) — классическая
 * форма приполярных построений (Walker 1984; Iridium — её частный случай).
 * Фазировка внутри плоскости — той же формулой Delta.
 */
fun walkerStar(incDeg: Double, t: Int, p: Int, f: Int, altKm: Double): List<OrbitSlot> {
    if (t % p != 0) throw ModelViolationException("TZ-BAL-003: T=$t не делится на P=$p")
    val s = t / p
    return buildList {
        for (plane in 0 until p) {
            val raanDeg = 180.0 * plane / p
            for (slot in 0 until s) {
                val maDeg = (360.0 * slot / s + 360.0 * f * plane / t).mod(360.0)
                add(OrbitSlot(plane, raanDeg, maDeg, incDeg, altKm))
            }
        }
    }
}

/** Прокси-экономика: число уникальных пар «наклонение × высота» = число кампаний. */
fun launchCampaigns(sats: List<OrbitSlot>): Int =
    sats.map { "%.3f".format(it.incDeg) to "%.3f".format(it.altKm) }.toSet().size

/**
 * Прокси срока развёртывания, сут: месяц на пусковую кампанию плюс разведение
 * плоскостей прецессией (дёшево, но медленно) — полмесяца на каждую плоскость
 * сверх первой. Прокси задокументирован; уточняется на шаге экономики.
 */
fun deploymentTimeDaysProxy(campaigns: Int, planes: Int): Double =
    30.0 * campaigns + 15.0 * (planes - 1)

/** Конфигурация группировки; ССО без LTAN отклоняется (TZ-BAL-003). */
data class ConstellationConfig(
    val incDeg: Double,
    val total: Int,
    val planes: Int,
    val phasing: Int,
    val altKm: Double,
    val sso: Boolean = false,
    val ltanH: Double? = null,
) {
    init {
        if (sso && ltanH == null) {
            throw ModelViolationException(
                "TZ-BAL-003: SSO configuration requires LTAN — eclipse pattern depends on it"
            )
        }
    }

    /** Для ССО наклонение определяется высотой, а не задаётся вручную. */
    fun effectiveIncDeg(): Double = if (sso) ssoInclinationDeg(altKm) else incDeg

    fun expand(): List<OrbitSlot> = walkerDelta(effectiveIncDeg(), total, planes, phasing, altKm)
}

/**
 * Подгруппа составного построения (МВП-М1, ЗАДАЧА-CODE-ПОСТРОЕНИЕ §1):
 * walker_delta / walker_star — наклонение вводится; sso — наклонение
 * ВЫЧИСЛЯЕТСЯ из высоты, вводится LTAN; плоскости ССО разнесены по RAAN
 * (шаг 360°/P = сдвиг LTAN на 24/P ч — прежняя семантика схемы).
 */
data class SubgroupConfig(
    val name: String,
    val kind: String,
    val planes: Int,
    val perPlane: Int,
    val altKm: Double,
    val incDeg: Double? = null,
    val phasing: Int = 0,
    val ltanH: Double? = null,
) {
    init {
        when (kind) {
            "walker_delta", "walker_star" ->
                if (incDeg == null) throw ModelViolationException(
                    "TZ-BAL-003: подгруппа «$name» ($kind) без наклонения",
                )
            "sso" ->
                if (ltanH == null) throw ModelViolationException(
                    "TZ-BAL-003: подгруппа «$name» (sso) без LTAN — картина затенения зависит от него",
                )
            else -> throw ModelViolationException("TZ-BAL-003: неизвестный вид подгруппы «$kind»")
        }
    }

    val total: Int get() = planes * perPlane

    fun effectiveIncDeg(): Double = if (kind == "sso") ssoInclinationDeg(altKm) else incDeg!!

    /** Орбиты подгруппы; satId несёт префикс — id уникальны между подгруппами. */
    fun expand(prefix: String): List<OrbitSlot> {
        val slots = when (kind) {
            "walker_star" -> walkerStar(effectiveIncDeg(), total, planes, phasing, altKm)
            // sso: тот же круг RAAN 360°/P, что и delta, — сдвиг LTAN на 24/P ч
            else -> walkerDelta(effectiveIncDeg(), total, planes, phasing, altKm)
        }
        return slots.map { it.copy(satId = "$prefix-${it.satId}") }
    }
}

/** Составное построение: набор подгрупп; итог — сумма (вычисляется). */
data class CompositeConfig(val subgroups: List<SubgroupConfig>) {
    init {
        if (subgroups.isEmpty()) throw ModelViolationException("TZ-BAL-003: построение без подгрупп")
    }

    val totalSats: Int get() = subgroups.sumOf { it.total }

    /** Горизонт «виток» для смеси высот — по самой низкой (короткий виток: худшее окно). */
    val minAltKm: Double get() = subgroups.minOf { it.altKm }

    fun expandAll(): List<OrbitSlot> =
        subgroups.mapIndexed { i, g -> g.expand("G${i + 1}") }.flatten()

    /** Кампании и срок развёртывания — прежними прокси по полному перечню орбит. */
    fun campaigns(): Int = launchCampaigns(expandAll())

    companion object {
        /** Одиночная конфигурация — составное из одной подгруппы (миграция ×1). */
        fun of(single: ConstellationConfig): CompositeConfig = CompositeConfig(
            listOf(
                SubgroupConfig(
                    name = "построение",
                    kind = if (single.sso) "sso" else "walker_delta",
                    planes = single.planes,
                    perPlane = single.total / single.planes,
                    altKm = single.altKm,
                    incDeg = single.incDeg,
                    phasing = single.phasing,
                    ltanH = single.ltanH,
                ),
            ),
        )
    }
}

/** Разобранное построение: перечень орбит + подгруппы для сводок и трасс. */
data class ParsedConstellation(
    val slots: List<OrbitSlot>,
    val subgroups: List<SubgroupConfig>,
    /** satId → высота: зона и высота точки на карте — по своему КА. */
    val altBySat: Map<String, Double>,
) {
    val totalSats: Int get() = slots.size
    val minAltKm: Double get() = slots.minOf { it.altKm }

    /** Слоты по подгруппам — трассы «каждая подгруппа своим цветом» (§6). */
    fun slotsBySubgroup(): List<Pair<SubgroupConfig, List<OrbitSlot>>> =
        subgroups.mapIndexed { i, g -> g to slots.filter { it.satId.startsWith("G${i + 1}-") } }
}

/**
 * Документ constellation → перечень орбит. Три формы живут рядом:
 * composite (подгруппы, МВП-М1), одиночный walker (миграция ×1 на чтении —
 * прежние проекты живут без правок), explicit (перечень орбит как есть).
 */
fun parseConstellationDoc(doc: com.fasterxml.jackson.databind.JsonNode): ParsedConstellation {
    val kind = doc.path("kind").asText("")
    val composite = when {
        kind == "composite" || doc.path("subgroups").isArray && doc.path("subgroups").size() > 0 ->
            CompositeConfig(
                doc.path("subgroups").map { g ->
                    SubgroupConfig(
                        name = g.path("name").asText(""),
                        kind = g.path("kind").asText(""),
                        planes = g.path("planes").asInt(),
                        perPlane = g.path("per_plane").asInt(),
                        altKm = g.path("altitude_km").asDouble(),
                        incDeg = g.path("inclination_deg").takeIf { it.isNumber }?.asDouble(),
                        phasing = g.path("phasing").asInt(0),
                        ltanH = g.path("ltan_h").takeIf { it.isNumber }?.asDouble(),
                    )
                },
            )
        kind == "explicit" && doc.path("orbits").isArray -> {
            val slots = doc.path("orbits").mapIndexed { i, o ->
                OrbitSlot(
                    plane = i,
                    raanDeg = o.path("raan_deg").asDouble(),
                    maDeg = o.path("mean_anomaly_deg").asDouble(),
                    incDeg = o.path("inclination_deg").asDouble(),
                    altKm = o.path("altitude_km").asDouble(),
                    satId = "ORB-$i",
                )
            }
            return ParsedConstellation(
                slots,
                subgroups = emptyList(),
                altBySat = slots.associate { it.satId to it.altKm },
            )
        }
        else -> {
            val w = doc.path("walker")
            CompositeConfig.of(
                ConstellationConfig(
                    incDeg = w.path("inclination_deg").asDouble(),
                    total = w.path("total").asInt(),
                    planes = w.path("planes").asInt(),
                    phasing = w.path("phasing").asInt(),
                    altKm = w.path("altitude_km").asDouble(),
                    sso = w.path("sso").asBoolean(false),
                    ltanH = w.path("ltan_h").takeIf { it.isNumber }?.asDouble(),
                ),
            )
        }
    }
    val slots = composite.expandAll()
    return ParsedConstellation(
        slots, composite.subgroups, slots.associate { it.satId to it.altKm },
    )
}
