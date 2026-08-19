// Зоны обслуживания (TZ-KA-005): для КАЖДОЙ линии и КАЖДОГО профиля терминала.
// «Зона спутника» без указания профиля бессмысленна (ловушка 2): одна и та же
// линия даёт разные зоны для A' и C'. Результат — документ contracts/service-zone
// с обязательным ограничивающим фактором.
package orbita.ka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.bal.RE_KM
import orbita.bal.footprintRadiusKm

/** Зона обслуживания по паре «линия × профиль терминала». */
data class ServiceZone(
    val spacecraftRef: String,
    val linkRef: String,
    val terminalProfileRef: String,
    val altKm: Double,
    val minElevDeg: Double,
    val serviceElevDeg: Double,
    val limitingFactor: String,
    val radiusKm: Double,
    val areaKm2: Double,
    val budget: LinkBudgetBreakdown,
    val doppler: Doppler?,
)

object ServiceZones {

    /**
     * Зона по линии и профилю; null — линия не замыкается нигде, зоны нет.
     * [leg] несёт requiredEbn0Db из адаптера протокола (TZ-KA-007).
     */
    fun compute(
        spacecraftRef: String,
        leg: LinkLeg,
        terminalProfileRef: String,
        requiredMarginDb: Double,
        minElevDeg: Double = 5.0,
        dopplerToleranceHz: Double? = null,
    ): ServiceZone? {
        val serviceElev = serviceElevationDeg(leg, requiredMarginDb, minElevDeg) ?: return null
        val radius = footprintRadiusKm(leg.altKm, serviceElev)
        // площадь сферического сегмента радиуса дуги r по поверхности Земли
        val psi = radius / RE_KM
        val area = 2 * Math.PI * RE_KM * RE_KM * (1 - Math.cos(psi))
        return ServiceZone(
            spacecraftRef = spacecraftRef,
            linkRef = leg.id,
            terminalProfileRef = terminalProfileRef,
            altKm = leg.altKm,
            minElevDeg = minElevDeg,
            serviceElevDeg = serviceElev,
            limitingFactor = limitingFactor(serviceElev, minElevDeg),
            radiusKm = radius,
            areaKm2 = area,
            budget = linkBudget(leg, serviceElev),
            doppler = dopplerToleranceHz?.let { doppler(leg.altKm, leg.freqHz, minElevDeg, it) },
        )
    }

    /** Сериализация в нормативный контракт contracts/service-zone. */
    fun toContractJson(zone: ServiceZone, mapper: ObjectMapper = ObjectMapper()): ObjectNode {
        val n = mapper.createObjectNode()
        n.put("spacecraft_ref", zone.spacecraftRef)
        n.put("link_ref", zone.linkRef)
        n.put("terminal_profile_ref", zone.terminalProfileRef)
        n.put("altitude_km", zone.altKm)
        n.put("min_elevation_deg", zone.minElevDeg)
        n.putObject("boundary")
            .put("service_elevation_deg", zone.serviceElevDeg)
            .put("limiting_factor", zone.limitingFactor)
            .put("radius_km", zone.radiusKm)
            .put("area_km2", zone.areaKm2)
        n.putObject("link_budget")
            .put("eirp_dbw", zone.budget.eirpDbw)
            .put("fspl_db", zone.budget.fsplDb)
            .put("c_over_n0_dbhz", zone.budget.cOverN0DbHz)
            .put("required_ebn0_db", zone.budget.requiredEbn0Db)
            .put("margin_db", zone.budget.marginDb)
        zone.doppler?.let { d ->
            n.putObject("doppler")
                .put("max_shift_hz", d.maxShiftHz)
                .put("max_rate_hz_s", d.maxRateHzS)
                .put("within_receiver_capture", d.withinCapture)
        }
        return n
    }
}
