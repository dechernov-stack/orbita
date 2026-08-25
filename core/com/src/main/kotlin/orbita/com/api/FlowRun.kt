// Прогон потоков (Монте-Карло) от ХРАНИМЫХ объектов сценария (TZ-FLW-001).
//
// Ядро (core/flw) существовало и было валидировано с шага 5, но запускалось
// только перф-скриптом из spec-конфига: варианты сравнения в демо СЕЯЛИСЬ
// готовыми числами, и инженер запустить прогон из интерфейса не мог вовсе
// (находка живого прогона: «прогон потоков по сценарию не выполнялся» было
// вечным состоянием). Этот класс собирает входы движка из объектов модели —
// вторых копий чисел нет: изменилась карта спроса, изменится и прогон.
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.bal.ConstellationConfig
import orbita.bal.GridPoint
import orbita.flw.CellPass
import orbita.flw.ChannelParams
import orbita.flw.DeliveryMode
import orbita.flw.MonteCarloEngine
import orbita.flw.PopulationSlice
import orbita.mod.store.StoredObject

class FlowRun(private val boundary: Boundary, private val mapper: ObjectMapper = ObjectMapper()) {

    private fun ref(scenario: StoredObject, field: String, kind: String): StoredObject {
        val id = scenario.doc.path(field).asText("")
        require(id.isNotBlank()) { "сценарий ${scenario.id} не ссылается на $kind ($field)" }
        return boundary.objects.current(id)
            ?: throw NoSuchElementException("$kind '$id' по ссылке сценария не найден")
    }

    fun run(scenarioId: String, projectId: String): ObjectNode {
        val scenario = boundary.objects.current(scenarioId)
            ?.takeIf { it.type == "scenario" }
            ?: throw NoSuchElementException("сценарий '$scenarioId' в модели отсутствует")
        val constellation = ref(scenario, "constellation_ref", "группировка")
        val demandMap = ref(scenario, "demand_map_ref", "карта спроса")
        val stationsObj = ref(scenario, "ground_stations_ref", "наземные станции")
        val adapter = ref(scenario, "protocol_adapter_ref", "адаптер протокола")
        // профиль терминала: по ссылке сценария, иначе единственный в проекте
        val terminal = scenario.doc.path("terminal_profile_ref").asText("")
            .takeIf { it.isNotBlank() }?.let { boundary.objects.current(it) }
            ?: boundary.objects.listCurrent(projectId).firstOrNull { it.type == "terminal_profile" }
            ?: throw NoSuchElementException("профиль терминала в проекте отсутствует")

        val durationS = scenario.doc.path("duration_s").asDouble(0.0)
        require(durationS > 0) { "сценарий '$scenarioId': duration_s должен быть положительным" }
        val epoch = scenario.doc.path("epoch").asText("")
        require(epoch.isNotBlank()) { "сценарий '$scenarioId': не задана эпоха" }
        val runs = scenario.doc.path("monte_carlo_runs").takeIf { it.isInt }?.asInt() ?: DEFAULT_RUNS
        val rngSeed = scenario.doc.path("rng_seed").asLong(42L)
        val mode = DeliveryMode.entries.firstOrNull {
            it.wireId == scenario.doc.path("delivery_mode").asText("")
        } ?: DeliveryMode.StoreAndForward

        val w = constellation.doc.path("walker")
        val config = ConstellationConfig(
            incDeg = w.path("inclination_deg").asDouble(),
            total = w.path("total").asInt(),
            planes = w.path("planes").asInt(),
            phasing = w.path("phasing").asInt(),
            altKm = w.path("altitude_km").asDouble(),
        )

        // Геометрия ячеек спроса — те же углы, что у карты покрытия
        // (/views/coverage): второй пары констант быть не должно.
        val cellTargets = demandMap.doc.path("cells").map {
            GridPoint(it.path("cell_id").asText(), it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble())
        }
        require(cellTargets.isNotEmpty()) { "карта спроса пуста: прогону не по чему считать" }
        val userVis = boundary.visibility.schedule(
            config, epoch, durationS,
            minElevDeg = USER_MIN_ELEV_DEG, targets = cellTargets, scenarioRef = scenarioId,
            serviceElevDeg = SERVICE_ELEV_DEG,
        )
        val userPasses = userVis["passes"].map {
            CellPass(
                cellId = it["target_ref"].asText(),
                startS = it["start_s"].asDouble(),
                endS = it["end_s"].asDouble(),
                inServiceZone = it.path("in_service_zone").asBoolean(false),
            )
        }

        // Контакты с наземными станциями — ожидание нисходящего канала C'
        val stationTargets = stationsObj.doc.path("stations").map {
            GridPoint(it.path("id").asText(), it.path("lat_deg").asDouble(), it.path("lon_deg").asDouble())
        }
        val stationMinElev = stationsObj.doc.path("stations")
            .mapNotNull { it.path("min_elevation_deg").takeIf { e -> e.isNumber }?.asDouble() }
            .minOrNull() ?: USER_MIN_ELEV_DEG
        val relayContacts = if (stationTargets.isEmpty()) emptyList() else
            boundary.visibility.schedule(
                config, epoch, durationS,
                minElevDeg = stationMinElev, targets = stationTargets, scenarioRef = scenarioId,
            )["passes"].map {
                CellPass(
                    cellId = it["target_ref"].asText(),
                    startS = it["start_s"].asDouble(),
                    endS = it["end_s"].asDouble(),
                    inServiceZone = true,
                )
            }

        // Популяции — из слоёв спроса карты, класс за списком demand[]
        val populations = demandMap.doc.path("cells").flatMap { cell ->
            val cellId = cell.path("cell_id").asText()
            cell.path("demand").mapNotNull { d ->
                val terminals = d.path("count").asDouble(0.0)
                if (terminals <= 0) return@mapNotNull null
                val msgsDay = d.path("uplink_msgs_per_day").asDouble(0.0)
                PopulationSlice(
                    cellId = cellId,
                    consumerClass = d.path("terminal_profile_ref").asText("A_prime"),
                    terminals = terminals,
                    msgsPerTerminalDay = if (terminals > 0) msgsDay / terminals else 0.0,
                    weight = terminals,
                    // политика повторов Р6 (эфемеридный backoff): значения
                    // эталонного прогона TZ-COM-004; кандидат на вынос в
                    // профиль терминала
                    attemptsPerPass = 4, maxPasses = 2,
                )
            }
        }
        require(populations.isNotEmpty()) { "слои спроса пусты: популяций нет" }

        // Канал: время в эфире из адаптера и профиля терминала; потолок
        // сообщений на пролёт — длительность среднего сервисного пролёта,
        // делённая на время в эфире (данные, не выдуманные константы)
        val payloadBytes = terminal.doc.path("generation").path("payload_bytes").asDouble(24.0)
        val overheadBytes = adapter.doc.path("mac").path("overhead_bytes").asDouble(13.0)
        val msPerByte = adapter.doc.path("phy").path("modes").firstOrNull()
            ?.path("time_on_air_ms_per_byte")?.asDouble(1.5) ?: 1.5
        val timeOnAirS = (payloadBytes + overheadBytes) * msPerByte / 1000.0
        val servicePasses = userPasses.filter { it.inServiceZone }
        val meanPassS = servicePasses.takeIf { it.isNotEmpty() }
            ?.map { it.endS - it.startS }?.average() ?: 0.0
        require(meanPassS > 0) { "зона обслуживания пуста: линии не замыкаются, прогону не по чему считать" }
        val channel = ChannelParams(
            capacityMsgsPerPass = meanPassS / timeOnAirS,
            timeOnAirS = timeOnAirS,
            beaconPeriodS = terminal.doc.path("ephemeris").path("beacon_rx_period_s").asDouble(60.0),
            maxAlmanacAgeS = terminal.doc.path("ephemeris").path("max_almanac_age_s").asDouble(86400.0),
        )

        val result = MonteCarloEngine(mapper).run(
            scenarioRef = scenarioId,
            populations = populations,
            userPasses = userPasses,
            relayContacts = relayContacts,
            channel = channel,
            horizonS = durationS,
            runs = runs,
            rngSeed = rngSeed,
            mode = mode,
            parallelism = Runtime.getRuntime().availableProcessors(),
        )

        // прежние прогоны этого сценария устаревают: сравнение и узкие места
        // обязаны видеть свежий, а история остаётся строками stale
        boundary.connection.prepareStatement(
            "UPDATE results SET stale = true WHERE scenario_id = ? AND kind = 'flow' AND NOT stale",
        ).use { ps -> ps.setString(1, scenarioId); ps.executeUpdate() }

        val inputVersions = buildMap {
            listOf(constellation, demandMap, stationsObj, adapter, terminal).forEach { put(it.id, it.version) }
        }
        val stored = boundary.results.insert(
            scenarioId = scenarioId, kind = "flow",
            payload = result.toContractJson(mapper),
            inputVersions = inputVersions,
            moduleVersion = MODULE_VERSION, rngSeed = rngSeed,
        )

        val out = mapper.createObjectNode()
        out.put("result_pk", stored.pk)
        out.put("scenario", scenarioId)
        out.put("runs", result.runs)
        out.put("passes", userPasses.size)
        out.put("service_passes", servicePasses.size)
        out.put("populations", populations.size)
        out.set<ObjectNode>("kpi", result.toContractJson(mapper))
        return out
    }

    private companion object {
        /** Углы — те же, что у карты покрытия: второй пары констант нет. */
        const val USER_MIN_ELEV_DEG = 10.0
        const val SERVICE_ELEV_DEG = 25.0
        const val DEFAULT_RUNS = 100
        const val MODULE_VERSION = "0.1"
    }
}
