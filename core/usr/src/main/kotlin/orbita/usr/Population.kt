// Популяции и подвижность (TZ-USR-003, Р7/ADR-007): только static и route,
// маршрут — трек с координатами и скоростью; роуминг регуляторных зон
// не реализуется, регион считается одним на трек.
package orbita.usr

import com.fasterxml.jackson.databind.JsonNode
import orbita.mod.store.ModelViolationException
import kotlin.math.pow

data class TrackPoint(val lat: Double, val lon: Double)

data class Route(val points: List<TrackPoint>, val speedMps: Double)

data class Population(
    val klass: String,
    val count: Double,
    val mobilityModel: String,
    val route: Route?,
    val growthPctPerYear: Double,
)

object Populations {

    private val ALLOWED_MOBILITY = setOf("static", "route")

    fun parse(doc: JsonNode): Population {
        val model = doc.path("mobility").path("model").asText("static")
        if (model !in ALLOWED_MOBILITY) {
            throw ModelViolationException(
                "TZ-USR-003 (Р7/ADR-007): mobility model '$model' is not supported; allowed: static, route"
            )
        }
        val route = if (model == "route") {
            val pts = doc.path("mobility").path("route").path("points")
            if (!pts.isArray || pts.isEmpty) {
                throw ModelViolationException("TZ-USR-003: route requires a track with coordinates")
            }
            Route(
                points = pts.map { TrackPoint(it.path("lat").asDouble(), it.path("lon").asDouble()) },
                speedMps = doc.path("mobility").path("route").path("speed_mps").asDouble(0.0),
            )
        } else null
        return Population(
            klass = doc.path("consumer_class").asText(),
            count = doc.path("count").asDouble(0.0),
            mobilityModel = model,
            route = route,
            growthPctPerYear = doc.path("growth_pct_per_year").asDouble(0.0),
        )
    }

    /** Прогноз роста численности к горизонту оценки ёмкости (TZ-USR-003). */
    fun grownCount(count: Double, growthPctPerYear: Double, years: Double): Double =
        count * (1 + growthPctPerYear / 100.0).pow(years)
}
