// Вектор KPI варианта и фронт Парето (TZ-BAL-006).
//
// Показатели, зависящие от ядра Монте-Карло (вероятности доставки по классам
// и P(T ≤ T_треб), TZ-FLW), приходят снаружи: без результата моделирования
// поля остаются пустыми — пустое поле честнее правдоподобного (CLAUDE.md §5,
// ловушка 7). Свёртка в единый индекс требует явных весов и не заменяет фронт.
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Метрика класса потребителей (Р9: классы не усредняются между собой).
 * Допустимые значения [metric] — перечисление схемы contracts/kpi-vector.
 */
data class ClassMetric(val consumerClass: String, val metric: String, val value: Double)

data class QualityKpi(
    val demandWeightedScore: Double,
    val latitudeProfile: List<Triple<Double, Double, Double>> = emptyList(), // (пояс, качество, вес)
    val byClass: List<ClassMetric> = emptyList(),
    val meanGapS: Double? = null,
    val maxGapS: Double? = null,
    val revisitS: Double? = null,
    val multiplicityMean: Double? = null,
) {
    /** Метрики покрытия целиком из постобработки геометрии (TZ-BAL-005). */
    fun withCoverage(c: CoverageMetrics): QualityKpi = copy(
        meanGapS = c.meanGapS, maxGapS = c.maxGapS,
        revisitS = c.revisitS, multiplicityMean = c.multiplicityMean,
    )
}

data class EconomicsKpi(
    val launchCampaigns: Int,
    val deploymentTimeDays: Double,
    val totalMassKg: Double? = null,
)

data class EnergyKpi(
    val worstSeasonWhPerOrbit: Double,
    val bestSeasonWhPerOrbit: Double,
    val eclipseFractionWorst: Double,
    val allowedPayloadDutyCycle: Double?,
    val batteryDodWorst: Double? = null,
)

data class EnvironmentKpi(val lifetimeYears: Double?, val deorbitCompliant: Boolean?)

data class KpiVector(
    val scenarioRef: String,
    val constellation: ConstellationConfig,
    val quality: QualityKpi,
    val economics: EconomicsKpi,
    val energy: EnergyKpi,
    val degradationCurve: List<Pair<Int, Double>>,
    val environment: EnvironmentKpi? = null,
) {
    /** Сериализация в нормативный контракт contracts/kpi-vector. */
    fun toContractJson(mapper: ObjectMapper = ObjectMapper()): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("scenario_ref", scenarioRef)
        root.putObject("constellation_summary")
            .put("satellites", constellation.total)
            .put("planes", constellation.planes)
            .put("sats_per_plane", constellation.total / constellation.planes)
            .put("inclination_deg", constellation.effectiveIncDeg())
            .put("altitude_km", constellation.altKm)
            .put("sso", constellation.sso)
            .apply { constellation.ltanH?.let { put("ltan_h", it) } }

        val q = root.putObject("quality")
        q.put("demand_weighted_score", quality.demandWeightedScore)
        // by_class обязателен схемой; без результата моделирования пустой
        // массив честно показывает отсутствие данных (ловушка 7)
        val byClass = q.putArray("by_class")
        quality.byClass.forEach { m ->
            byClass.addObject()
                .put("consumer_class", m.consumerClass)
                .put("metric", m.metric)
                .put("value", m.value)
        }
        if (quality.latitudeProfile.isNotEmpty()) {
            val lp = q.putArray("latitude_profile")
            quality.latitudeProfile.forEach { (band, score, weight) ->
                lp.addObject().put("lat_band_deg", band).put("score", score).put("demand_weight", weight)
            }
        }
        // coverage заполняется только реально посчитанными полями
        val cov = mapper.createObjectNode()
        quality.meanGapS?.let { cov.put("mean_gap_s", it) }
        quality.maxGapS?.let { cov.put("max_gap_s", it) }
        quality.revisitS?.let { cov.put("revisit_s", it) }
        quality.multiplicityMean?.let { cov.put("multiplicity_mean", it) }
        if (!cov.isEmpty) q.set<ObjectNode>("coverage", cov)

        val e = root.putObject("economics")
        e.put("launch_campaigns", economics.launchCampaigns)
        e.put("deployment_time_days", economics.deploymentTimeDays)
        economics.totalMassKg?.let { e.put("total_mass_kg", it) }

        val rel = root.putObject("reliability")
        val curve = rel.putArray("degradation_curve")
        degradationCurve.forEach { (failed, score) ->
            curve.addObject().put("failed_satellites", failed).put("quality_score", score)
        }

        val en = root.putObject("energy")
        en.put("worst_season_wh_per_orbit", energy.worstSeasonWhPerOrbit)
        en.put("best_season_wh_per_orbit", energy.bestSeasonWhPerOrbit)
        en.put("eclipse_fraction_worst", energy.eclipseFractionWorst)
        energy.allowedPayloadDutyCycle?.let { en.put("allowed_payload_duty_cycle", it) }
        energy.batteryDodWorst?.let { en.put("battery_dod_worst", it) }

        environment?.let { env ->
            val n = root.putObject("environment")
            env.lifetimeYears?.takeIf { it.isFinite() }?.let { n.put("orbital_lifetime_years", it) }
            env.deorbitCompliant?.let { n.put("deorbit_compliant", it) }
        }
        return root
    }
}

/**
 * Недоминируемые варианты в пространстве «экономика × качество»:
 * вариант доминируется, если другой не хуже по обоим показателям и строго
 * лучше хотя бы по одному (качество — больше лучше, кампании — меньше лучше).
 */
fun paretoFront(vectors: List<KpiVector>): List<KpiVector> = vectors.filter { v ->
    vectors.none { o ->
        o !== v &&
            o.quality.demandWeightedScore >= v.quality.demandWeightedScore &&
            o.economics.launchCampaigns <= v.economics.launchCampaigns &&
            (o.quality.demandWeightedScore > v.quality.demandWeightedScore ||
                o.economics.launchCampaigns < v.economics.launchCampaigns)
    }
}

/**
 * Свёртка в единый индекс — только с ЯВНЫМИ весами и только как дополнение
 * к фронту (TZ-BAL-006): она не заменяет фронт и не используется для отбора.
 */
fun weightedIndex(v: KpiVector, qualityWeight: Double, economicsWeight: Double): Double {
    require(qualityWeight + economicsWeight > 0) { "weights must be explicit and positive" }
    val econScore = 1.0 / v.economics.launchCampaigns
    return (qualityWeight * v.quality.demandWeightedScore + economicsWeight * econScore) /
        (qualityWeight + economicsWeight)
}
