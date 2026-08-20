// Внешний датасет населения как слой 1 карты спроса (шаг 10.2, TZ-USR-004).
//
// До этого шага слой населения строился программно. Программные популяции
// выглядят как данные и ведут себя как данные, но не содержат ни одной реальной
// особенности: ни пустых океанов, ни сгущения к городам (ловушка 1 шага 10).
//
// ЧТО ЭТО ЗА ДАТАСЕТ И ЧЕГО В НЁМ НЕТ. Natural Earth populated places —
// населённые пункты с координатами и оценкой населения, общественное достояние.
// Это НЕ растр плотности населения: сельское население в нём не представлено
// вовсе, а городское сосредоточено в точке. Для карты спроса на терминалы такое
// приближение осмысленно — терминалы ставят там, где есть инфраструктура, — но
// называть его плотностью населения нельзя, и в документе карты записано именно
// то, чем он является.
//
// Версия датасета — свёртка его содержимого. Она входит в версию карты спроса
// и через неё в input_versions сценария: подмена датасета обязана обесценивать
// результат (TZ-COM-006).
package orbita.usr

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Загруженный датасет: записи сетки, версия и происхождение. */
data class PopulationDataset(
    val id: String,
    val version: String,
    val source: String,
    val records: List<GridRecord>,
) {
    /** Плотность в точке — для проверки по опорным точкам. */
    fun lookup(lat: Double, lon: Double, dlat: Double = 1.0, dlon: Double = 1.0): Double {
        val key = CellKey(floorTo(lat, dlat), floorTo(lon, dlon))
        return records.filter { CellKey(floorTo(it.lat, dlat), floorTo(it.lon, dlon)) == key }
            .sumOf { it.densityPerKm2 ?: 0.0 }
    }

    private fun floorTo(x: Double, step: Double) = Math.floor(x / step) * step
}

object PopulationDatasets {

    private val mapper = ObjectMapper()

    /** Путь к датасету: переопределяется ORBITA_POPULATION_DATASET. */
    fun defaultPath(repoRoot: Path): Path =
        System.getenv("ORBITA_POPULATION_DATASET")?.let { Path.of(it) }
            ?: repoRoot.resolve("data/ne_50m_populated_places_simple.geojson")

    /**
     * Разбор GeoJSON населённых пунктов в записи сетки. Население пунктов,
     * попавших в одну ячейку, складывается и делится на ПЛОЩАДЬ ячейки —
     * не на число пунктов: иначе густонаселённая ячейка на широте 60°
     * получила бы тот же вес, что и экваториальная.
     */
    fun fromGeoJson(
        path: Path,
        id: String = "ne_50m_populated_places",
        dlat: Double = 1.0,
        dlon: Double = 1.0,
    ): PopulationDataset {
        val bytes = Files.readAllBytes(path)
        val root = mapper.readTree(bytes)
        val byCell = LinkedHashMap<CellKey, Double>()
        for (feature in root.path("features")) {
            val coords = feature.path("geometry").path("coordinates")
            if (coords.size() < 2) continue
            val lon = coords.path(0).asDouble()
            val lat = coords.path(1).asDouble()
            val population = feature.path("properties").path("pop_max").asDouble(0.0)
            if (population <= 0.0) continue
            val key = CellKey(Math.floor(lat / dlat) * dlat, Math.floor(lon / dlon) * dlon)
            byCell[key] = (byCell[key] ?: 0.0) + population
        }
        val records = byCell.map { (key, population) ->
            // центр ячейки: запись описывает ячейку, а не точку города
            val lat = key.lat + dlat / 2
            val lon = key.lon + dlon / 2
            GridRecord(lat, lon, population / cellAreaKm2(lat, dlat, dlon))
        }.sortedWith(compareBy({ it.lat }, { it.lon }))

        return PopulationDataset(
            id = id,
            version = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }.take(16),
            source = "Natural Earth 5.x populated places (общественное достояние); " +
                "население городских пунктов, агрегированное на сетку ${dlat}°×${dlon}°; " +
                "сельское население не представлено",
            records = records,
        )
    }

    /**
     * Слой населения карты спроса из датасета. Класс потребителя задаётся
     * вызывающим: датасет знает про людей, а не про то, какой сервис им нужен.
     */
    fun populationLayer(
        dataset: PopulationDataset,
        terminalsPerCapita: Double,
        msgsPerTerminalDay: Double,
        consumerClass: String,
    ): List<PopulationCell> = dataset.records.map { r ->
        PopulationCell(
            id = cellId(r.lat, r.lon),
            lat = r.lat,
            lon = r.lon,
            popDensityPerKm2 = r.densityPerKm2 ?: 0.0,
            terminalsPerCapita = terminalsPerCapita,
            msgsPerTerminalDay = msgsPerTerminalDay,
            klass = consumerClass,
        )
    }

    /** Идентификатор ячейки по её координатам: устойчив и читаем. */
    fun cellId(lat: Double, lon: Double): String =
        "c%+05.1f%+06.1f".format(java.util.Locale.ROOT, lat, lon)
}
