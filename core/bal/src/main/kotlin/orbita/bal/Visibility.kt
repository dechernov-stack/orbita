// Предрасчёт геометрии видимости (TZ-BAL-002, ADR-013).
//
// Двухуровневая схема: пропагация Orekit даёт подспутниковые трассы; на грубой
// сетке пролёты собираются сравнением сферической дуги с центральным углом зоны
// (та же нормативная геометрия, что и в эталоне), а точечное УТОЧНЕНИЕ границ
// пролёта — событиями Orekit (ElevationDetector), без собственных интеграторов.
// Применимость сетки проверяется (Grid.applicableCoarseCellKm), результат
// кэшируется по ключу «конфигурация + эпоха + версии входов и Orekit»:
// пересчёт геометрии внутри цикла Монте-Карло недопустим (TZ-COM-004).
package orbita.bal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.hipparchus.util.FastMath
import org.orekit.bodies.GeodeticPoint
import org.orekit.bodies.OneAxisEllipsoid
import org.orekit.frames.FramesFactory
import org.orekit.frames.TopocentricFrame
import org.orekit.orbits.KeplerianOrbit
import org.orekit.orbits.PositionAngleType
import org.orekit.propagation.analytical.EcksteinHechlerPropagator
import org.orekit.propagation.events.ElevationDetector
import org.orekit.propagation.events.EventsLogger
import org.orekit.propagation.events.handlers.ContinueOnEvent
import org.orekit.time.AbsoluteDate
import org.orekit.time.TimeScalesFactory
import org.orekit.utils.Constants
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Пролёт КА над целью, секунды от эпохи. */
data class Pass(
    val satId: String,
    val targetId: String,
    val startS: Double,
    val endS: Double,
    val maxElevDeg: Double,
)

/** Сферическая дуга между двумя точками (широта/долгота в градусах). */
fun centralAngleBetweenDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val f1 = Math.toRadians(lat1)
    val f2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin((f2 - f1) / 2).let { it * it } +
        cos(f1) * cos(f2) * sin(dl / 2).let { it * it }
    return Math.toDegrees(2 * asin(sqrt(a.coerceIn(0.0, 1.0))))
}

/** Угол места цели при центральном угле psi до подспутниковой точки. */
fun elevationDegFromCentralAngle(altKm: Double, psiDeg: Double): Double {
    if (psiDeg <= 1e-9) return 90.0
    val psi = Math.toRadians(psiDeg)
    val ratio = RE_KM / (RE_KM + altKm)
    return Math.toDegrees(atan2(cos(psi) - ratio, sin(psi)))
}

class VisibilityPrecompute(private val mapper: ObjectMapper = ObjectMapper()) {

    private val cache = ConcurrentHashMap<String, ObjectNode>()

    /** Число фактических расчётов — кэш обязан не расти в цикле (TZ-COM-004). */
    @Volatile
    var computeCount: Int = 0
        private set

    /**
     * Расписание видимости группировки над целями; результат — документ
     * contracts/visibility. Повторный вызов с тем же ключом отдаёт кэш.
     */
    fun schedule(
        config: ConstellationConfig,
        epochIso: String,
        durationS: Double,
        minElevDeg: Double,
        targets: List<GridPoint>,
        scenarioRef: String = "SC-0000",
        serviceElevDeg: Double? = null,
        stepS: Double = 30.0,
    ): ObjectNode {
        val key = cacheKey(config, epochIso, durationS, minElevDeg, targets, serviceElevDeg, stepS)
        return cache.getOrPut(key) {
            computeCount++
            compute(config, epochIso, durationS, minElevDeg, targets, scenarioRef, serviceElevDeg, stepS)
        }
    }

    /** Первый уровень ADR-013: цели — грубая сетка с проверенной применимостью. */
    fun scheduleOnCoarseGrid(
        config: ConstellationConfig,
        epochIso: String,
        durationS: Double,
        minElevDeg: Double,
        requestedCellKm: Double = 800.0,
        scenarioRef: String = "SC-0000",
    ): ObjectNode {
        val cellKm = applicableCoarseCellKm(requestedCellKm, config.altKm, minElevDeg)
        return schedule(config, epochIso, durationS, minElevDeg, coarseGrid(cellKm), scenarioRef)
    }

    private fun compute(
        config: ConstellationConfig,
        epochIso: String,
        durationS: Double,
        minElevDeg: Double,
        targets: List<GridPoint>,
        scenarioRef: String,
        serviceElevDeg: Double?,
        stepS: Double,
    ): ObjectNode {
        OrekitSetup.ensureInitialized()
        val epoch = AbsoluteDate(epochIso, TimeScalesFactory.getUTC())
        val earthFrame = FramesFactory.getGTOD(false)
        val earth = OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, earthFrame,
        )
        val lambdaDeg = centralAngleDeg(config.altKm, minElevDeg)
        val steps = (durationS / stepS).toInt()

        // подспутниковые трассы: пропагация Orekit, шаг stepS
        data class Track(val satId: String, val latDeg: DoubleArray, val lonDeg: DoubleArray)

        val tracks = config.expand().map { slot ->
            val prop = propagatorFor(slot, epoch)
            val lat = DoubleArray(steps + 1)
            val lon = DoubleArray(steps + 1)
            for (k in 0..steps) {
                val date = epoch.shiftedBy(k * stepS)
                val p = prop.getPVCoordinates(date, earthFrame).position
                val gp = earth.transform(p, earthFrame, date)
                lat[k] = FastMath.toDegrees(gp.latitude)
                lon[k] = FastMath.toDegrees(gp.longitude)
            }
            Track(slot.satId, lat, lon)
        }

        val passes = mutableListOf<Pass>()
        for (track in tracks) {
            for (t in targets) {
                var start = -1
                var bestPsi = Double.MAX_VALUE
                for (k in 0..steps) {
                    val psi = centralAngleBetweenDeg(track.latDeg[k], track.lonDeg[k], t.latDeg, t.lonDeg)
                    val inView = psi <= lambdaDeg
                    if (inView) {
                        if (start < 0) { start = k; bestPsi = psi } else if (psi < bestPsi) bestPsi = psi
                    }
                    if ((!inView || k == steps) && start >= 0) {
                        val endK = if (inView) k else k - 1
                        passes += Pass(
                            track.satId, t.id,
                            startS = start * stepS, endS = endK * stepS,
                            maxElevDeg = elevationDegFromCentralAngle(config.altKm, bestPsi),
                        )
                        start = -1
                        bestPsi = Double.MAX_VALUE
                    }
                }
            }
        }
        return toDocument(scenarioRef, epochIso, durationS, passes, serviceElevDeg)
    }

    /**
     * Второй уровень ADR-013: точное уточнение границ пролёта событиями Orekit
     * (ElevationDetector) в окне вокруг грубого пролёта. Используется для ячеек
     * спроса по границе зоны обслуживания и для проверки сходимости.
     */
    fun refinePass(
        slot: OrbitSlot,
        epochIso: String,
        target: GridPoint,
        coarse: Pass,
        elevDeg: Double,
        windowS: Double = 120.0,
    ): Pass? {
        OrekitSetup.ensureInitialized()
        val epoch = AbsoluteDate(epochIso, TimeScalesFactory.getUTC())
        val earthFrame = FramesFactory.getGTOD(false)
        val earth = OneAxisEllipsoid(
            Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, earthFrame,
        )
        val topo = TopocentricFrame(
            earth, GeodeticPoint(Math.toRadians(target.latDeg), Math.toRadians(target.lonDeg), 0.0), target.id,
        )
        val prop = propagatorFor(slot, epoch)
        val logger = EventsLogger()
        prop.addEventDetector(
            logger.monitorDetector(
                ElevationDetector(30.0, 1.0e-3, topo)
                    .withConstantElevation(Math.toRadians(elevDeg))
                    .withHandler(ContinueOnEvent()),
            ),
        )
        val from = epoch.shiftedBy((coarse.startS - windowS).coerceAtLeast(0.0))
        val to = epoch.shiftedBy(coarse.endS + windowS)
        prop.propagate(from, to)

        var startDate: AbsoluteDate? = null
        var endDate: AbsoluteDate? = null
        for (e in logger.loggedEvents) {
            if (e.isIncreasing && startDate == null) startDate = e.state.date
            if (!e.isIncreasing) endDate = e.state.date
        }
        val s = startDate ?: from
        val en = endDate ?: to
        // максимум угла места — выборкой внутри уточнённого интервала
        var maxElev = -90.0
        var d = s
        while (d.compareTo(en) <= 0) {
            val elev = FastMath.toDegrees(
                topo.getElevation(prop.getPVCoordinates(d, earthFrame).position, earthFrame, d),
            )
            if (elev > maxElev) maxElev = elev
            d = d.shiftedBy(5.0)
        }
        if (maxElev < elevDeg) return null
        return Pass(slot.satId, target.id, s.durationFrom(epoch), en.durationFrom(epoch), maxElev)
    }

    /**
     * Аналитический пропагатор Эйнштейна–Хехлера с гравитационной моделью
     * EIGEN5C (зональные гармоники J2…J6 из констант Orekit, без внешних
     * файлов). J2 обязателен: прецессия ВДУ — та самая физика, ради которой
     * считается наклонение ССО; кеплеровская пропагация её не воспроизводит.
     */
    private fun propagatorFor(slot: OrbitSlot, epoch: AbsoluteDate): EcksteinHechlerPropagator =
        EcksteinHechlerPropagator(
            orbitOf(slot, epoch),
            Constants.EIGEN5C_EARTH_EQUATORIAL_RADIUS, Constants.EIGEN5C_EARTH_MU,
            Constants.EIGEN5C_EARTH_C20, Constants.EIGEN5C_EARTH_C30,
            Constants.EIGEN5C_EARTH_C40, Constants.EIGEN5C_EARTH_C50,
            Constants.EIGEN5C_EARTH_C60,
        )

    private fun orbitOf(slot: OrbitSlot, epoch: AbsoluteDate): KeplerianOrbit = KeplerianOrbit(
        (RE_KM + slot.altKm) * 1000.0, 0.0,
        Math.toRadians(slot.incDeg), 0.0,
        Math.toRadians(slot.raanDeg), Math.toRadians(slot.maDeg),
        PositionAngleType.MEAN, FramesFactory.getEME2000(), epoch, Constants.EIGEN5C_EARTH_MU,
    )

    private fun toDocument(
        scenarioRef: String,
        epochIso: String,
        durationS: Double,
        passes: List<Pass>,
        serviceElevDeg: Double?,
    ): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("scenario_ref", scenarioRef)
        root.put("epoch", epochIso)
        root.put("duration_s", durationS)
        val arr = root.putArray("passes")
        passes.sortedWith(compareBy({ it.satId }, { it.targetId }, { it.startS })).forEach { p ->
            val n = arr.addObject()
            n.put("spacecraft_ref", p.satId)
            n.put("target_type", "cell")
            n.put("target_ref", p.targetId)
            n.put("start_s", p.startS)
            n.put("end_s", p.endS)
            n.put("max_elevation_deg", p.maxElevDeg)
            serviceElevDeg?.let { n.put("in_service_zone", p.maxElevDeg >= it) }
        }
        return root
    }

    private fun cacheKey(
        config: ConstellationConfig,
        epochIso: String,
        durationS: Double,
        minElevDeg: Double,
        targets: List<GridPoint>,
        serviceElevDeg: Double?,
        stepS: Double,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(config.toString().toByteArray())
        md.update("$epochIso|$durationS|$minElevDeg|$serviceElevDeg|$stepS".toByteArray())
        targets.forEach { md.update("${it.id}|${it.latDeg}|${it.lonDeg}".toByteArray()) }
        OrekitSetup.inputVersions().entries.sortedBy { it.key }
            .forEach { (k, v) -> md.update("$k=$v".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
