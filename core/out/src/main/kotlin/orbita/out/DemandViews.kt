// Представление экрана 4 «Карта спроса» (STEP-7-9 §9.1).
//
// Карта собирается ЗДЕСЬ, тремя слоями TZ-USR-004: население, единичные
// объекты, сценарная библиотека. Клиент отдаёт слои и получает готовые ячейки
// с долей от максимума — шкала цвета считается на сервере, потому что вторая
// нормировка в интерфейсе разошлась бы с первой (STEP-7-9, ловушка 2).
//
// Классы потребителей нигде не усредняются (Р9): и в ячейке, и в сводке спрос
// разложен по классам.
package orbita.out

import orbita.usr.DemandCell
import orbita.usr.DemandMapBuilder
import orbita.usr.LatitudeBand
import orbita.usr.PopulationCell
import orbita.usr.ScenarioLibrary
import orbita.usr.SeedObject
import orbita.usr.cellAreaKm2
import orbita.usr.intensityAt
import orbita.usr.latitudeProfile
import orbita.usr.totalMsgsPerDay

/** Ячейка карты для отрисовки. `intensity` — доля от максимума карты, 0…1. */
data class DemandCellView(
    val id: String,
    val latDeg: Double,
    val lonDeg: Double,
    val areaKm2: Double,
    val msgsPerDay: Double,
    /** Спрос по классам: A′, B′, C′ раздельно (Р9). */
    val byClass: Map<String, Double>,
    val weight: Double,
    val intensity: Double,
)

/** Широтный пояс: суммарный вес ячеек пояса. */
data class LatitudeBandView(val bandDeg: Int, val weight: Double)

/** Вклад одной популяции в спрос — то, что показывает правая панель экрана. */
data class PopulationContribution(
    val id: String,
    val consumerClass: String,
    val terminals: Double,
    val msgsPerDay: Double,
    /** Доля в суммарном спросе карты, 0…1. */
    val share: Double,
    val cells: Int,
)

/** Референсный сценарий библиотеки как строка выбора слоя 3. */
data class ReferenceScenarioRow(
    val id: String,
    val name: String,
    val consumerClass: String,
    val geography: String,
    val terminals: Double,
    val msgsPerTerminalDay: Double,
    val mobilityModel: String,
)

/**
 * Пик спроса. Худшее сочетание «час × месяц» выбирается по профилям
 * активности (TZ-USR-005), а не берётся как среднее: средний час не
 * показывает, чем нагружена система в худшей точке.
 */
data class DemandPeak(val hour: Int, val month: Int, val msgsPerS: Double, val profiled: Boolean)

data class DemandMapView(
    val version: String,
    val cells: List<DemandCellView>,
    val totalMsgsPerDay: Double,
    val byClass: Map<String, Double>,
    val terminalsByClass: Map<String, Double>,
    val peak: DemandPeak,
    val latitudeProfile: List<LatitudeBandView>,
    val contributions: List<PopulationContribution>,
    /** Слои, участвовавшие в сборке: population, point_objects, scenario_library. */
    val layers: List<String>,
    val issues: List<String>,
)

/** Слои карты, как их задаёт экран. */
data class DemandLayers(
    val population: List<PopulationCell> = emptyList(),
    val pointObjects: List<SeedObject> = emptyList(),
    val scenarioIds: List<String> = emptyList(),
    /** 24 почасовых множителя; пусто — активность равномерная (TZ-USR-005). */
    val diurnal: List<Double>? = null,
    /** 12 сезонных множителей; пусто — сезонности нет. */
    val seasonal: List<Double>? = null,
)

class DemandViews(private val library: ScenarioLibrary = ScenarioLibrary()) {

    /** Библиотека референсных сценариев — слой 3 карты (TZ-USR-006). */
    fun referenceScenarios(): List<ReferenceScenarioRow> = library.scenarios.map { s ->
        ReferenceScenarioRow(
            id = s.id,
            name = s.name,
            consumerClass = s.consumerClass,
            geography = s.geography,
            terminals = s.seeds.sumOf { it.terminals },
            msgsPerTerminalDay = s.msgsPerTerminalDay,
            mobilityModel = s.mobilityModel,
        )
    }

    /**
     * Представление ХРАНИМОЙ карты (ADR-021): ячейки и веса берутся из
     * документа, а не пересчитываются. Пересчёт здесь означал бы, что
     * показанная карта может отличаться от сохранённой — а сценарий
     * ссылается именно на сохранённую.
     */
    fun fromDocument(doc: com.fasterxml.jackson.databind.JsonNode): DemandMapView {
        // Ячейки модели восстанавливаются из документа, чтобы широтный профиль
        // считала ТА ЖЕ функция разбиения на пояса, что и при сборке карты:
        // второе разбиение однажды дало бы другие пояса на том же спросе.
        val modelCells = doc.path("cells").associate { cell ->
            val id = cell.path("cell_id").asText("")
            id to DemandCell(
                id = id,
                lat = cell.path("lat_deg").asDouble(),
                lon = cell.path("lon_deg").asDouble(),
                areaKm2 = cell.path("area_km2").asDouble(),
                terminals = cell.path("demand").associate {
                    it.path("terminal_profile_ref").asText("") to it.path("count").asDouble()
                },
                msgsPerDay = cell.path("demand").associate {
                    it.path("terminal_profile_ref").asText("") to it.path("uplink_msgs_per_day").asDouble()
                },
                weight = cell.path("demand").firstOrNull()?.path("weight")?.asDouble() ?: 0.0,
            )
        }
        val cells = modelCells.values.map { c ->
            DemandCellView(
                id = c.id,
                latDeg = c.lat,
                lonDeg = c.lon,
                areaKm2 = sig(c.areaKm2),
                msgsPerDay = sig(c.totalMsgsPerDay()),
                byClass = sig(c.msgsPerDay.toSortedMap()),
                weight = sig(c.weight),
                intensity = 0.0,
            )
        }
        val maxCell = cells.maxOfOrNull { it.msgsPerDay } ?: 0.0
        val total = cells.sumOf { it.msgsPerDay }
        val classes = cells.flatMap { it.byClass.keys }.toSortedSet()
        return DemandMapView(
            version = doc.path("version").asText(""),
            cells = cells.map { it.copy(intensity = sig(if (maxCell > 0) it.msgsPerDay / maxCell else 0.0)) },
            totalMsgsPerDay = sig(total),
            byClass = sig(classes.associateWith { k -> cells.sumOf { it.byClass[k] ?: 0.0 } }),
            terminalsByClass = sig(
                classes.associateWith { k ->
                    doc.path("cells").sumOf { c ->
                        c.path("demand").filter { it.path("terminal_profile_ref").asText() == k }
                            .sumOf { it.path("count").asDouble() }
                    }
                },
            ),
            // Профили активности в документе карты — ячейковые; общего пика
            // по ним не строим, чтобы не выдать допущение за расчёт.
            peak = DemandPeak(0, 0, sig(total / 86400.0), profiled = false),
            latitudeProfile = latitudeProfile(modelCells, quality = { 1.0 }).map(::band),
            // Вклад слоёв в сохранённой карте не восстанавливается: документ
            // несёт результат сборки, а не то, из чего она собиралась.
            contributions = emptyList(),
            layers = doc.path("layers").fieldNames().asSequence().toList(),
            issues = if (cells.isEmpty()) listOf("сохранённая карта не содержит ячеек") else emptyList(),
        )
    }

    fun build(layers: DemandLayers): DemandMapView {
        val unknown = layers.scenarioIds.filterNot { id -> library.scenarios.any { it.id == id } }
        require(unknown.isEmpty()) { "неизвестные сценарии библиотеки: $unknown" }
        // Профиль неверной длины — некорректный запрос, а не замечание к карте:
        // молча дополнить его до 24 значений значило бы придумать активность.
        layers.diurnal?.let { require(it.size == 24) { "суточный профиль задаётся 24 значениями, получено ${it.size}" } }
        layers.seasonal?.let { require(it.size == 12) { "сезонный профиль задаётся 12 значениями, получено ${it.size}" } }
        val seeds = layers.scenarioIds.flatMap { library.expandSeeds(it) }
        val cells = DemandMapBuilder.build(layers.population, layers.pointObjects, seeds)

        val total = cells.values.sumOf { it.totalMsgsPerDay() }
        val maxCell = cells.values.maxOfOrNull { it.totalMsgsPerDay() } ?: 0.0
        val classes = cells.values.flatMap { it.msgsPerDay.keys }.toSortedSet()

        return DemandMapView(
            version = DemandMapBuilder.version(cells, library.version),
            cells = cells.keys.sorted().map { id ->
                val c = cells.getValue(id)
                DemandCellView(
                    id = c.id,
                    latDeg = c.lat,
                    lonDeg = c.lon,
                    areaKm2 = sig(c.areaKm2),
                    msgsPerDay = sig(c.totalMsgsPerDay()),
                    byClass = sig(c.msgsPerDay.toSortedMap()),
                    weight = sig(c.weight),
                    intensity = sig(if (maxCell > 0) c.totalMsgsPerDay() / maxCell else 0.0),
                )
            },
            totalMsgsPerDay = sig(total),
            byClass = sig(classes.associateWith { k -> cells.values.sumOf { it.msgsPerDay[k] ?: 0.0 } }),
            terminalsByClass = sig(classes.associateWith { k -> cells.values.sumOf { it.terminals[k] ?: 0.0 } }),
            peak = peak(cells.values, layers.diurnal, layers.seasonal),
            // Экран показывает вес спроса по поясам; качество здесь не при чём —
            // оно приходит из баллистики на экране сравнения вариантов.
            latitudeProfile = latitudeProfile(cells, quality = { 1.0 }).map(::band),
            contributions = contributions(layers, seeds, total),
            layers = buildList {
                if (layers.population.isNotEmpty()) add("population")
                if (layers.pointObjects.isNotEmpty()) add("point_objects")
                if (seeds.isNotEmpty()) add("scenario_library")
            },
            issues = issues(cells, layers),
        )
    }

    /**
     * Пик по всей карте: перебор 24 × 12 сочетаний. Профили здесь общие для
     * карты, поэтому пики ячеек совпадают по времени; если профили станут
     * ячейковыми, суммировать по общему часу будет уже нельзя.
     */
    private fun peak(
        cells: Collection<DemandCell>,
        diurnal: List<Double>?,
        seasonal: List<Double>?,
    ): DemandPeak {
        val profiled = diurnal != null || seasonal != null
        val (hour, month) = (0 until 24).flatMap { h -> (0 until 12).map { m -> h to m } }
            .maxByOrNull { (h, m) -> cells.sumOf { intensityAt(it, h, m, diurnal, seasonal) } }
            ?: (0 to 0)
        val perHour = cells.sumOf { intensityAt(it, hour, month, diurnal, seasonal) }
        return DemandPeak(hour, month, sig(perHour / 3600.0), profiled)
    }

    private fun band(b: LatitudeBand) = LatitudeBandView(b.band, sig(b.weight))

    /** Вклад слоёв: популяции и сценарные засевы считаются одинаково — по трафику. */
    private fun contributions(
        layers: DemandLayers,
        seeds: List<SeedObject>,
        total: Double,
    ): List<PopulationContribution> {
        val fromPopulation = layers.population.map { p ->
            val terminals = p.popDensityPerKm2 * cellAreaKm2(p.lat) * p.terminalsPerCapita
            PopulationContribution(
                id = p.id,
                consumerClass = p.klass,
                terminals = terminals,
                msgsPerDay = terminals * p.msgsPerTerminalDay,
                share = 0.0,
                cells = 1,
            )
        }
        val fromSeeds = (layers.pointObjects + seeds).groupBy { it.cellId to it.klass }
            .map { (key, group) ->
                PopulationContribution(
                    id = key.first,
                    consumerClass = key.second,
                    terminals = group.sumOf { it.terminals },
                    msgsPerDay = group.sumOf { it.terminals * it.msgsPerTerminalDay },
                    share = 0.0,
                    cells = 1,
                )
            }
        return (fromPopulation + fromSeeds)
            .sortedByDescending { it.msgsPerDay }
            .map {
                it.copy(
                    terminals = sig(it.terminals),
                    msgsPerDay = sig(it.msgsPerDay),
                    share = sig(if (total > 0) it.msgsPerDay / total else 0.0),
                )
            }
    }

    private fun issues(cells: Map<String, DemandCell>, layers: DemandLayers): List<String> = buildList {
        if (cells.isEmpty()) add("карта пуста: не задан ни один слой спроса")
        if (layers.population.isEmpty() && cells.isNotEmpty()) {
            add("слоя населения нет: карта держится на единичных объектах и сценариях")
        }
        cells.values.filter { it.totalMsgsPerDay() <= 0.0 }
            .forEach { add("${it.id}: ячейка без спроса — вес нулевой") }
    }
}
