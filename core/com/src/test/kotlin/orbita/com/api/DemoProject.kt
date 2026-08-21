// Демонстрационный проект «Орбита-IoT» (STEP-7-9 §7.2).
//
// ОДИН ИСТОЧНИК ДАННЫХ: и эталон spec/demo_project.py, и заполнение базы берут
// проект из одного места — сам эталон отдаёт его по `--dump`. Вторая копия
// демо-данных разошлась бы с эталоном на первом же изменении модели, и
// разошлась бы молча (STEP-7-9, ловушка 1).
//
// Проект намеренно неидеален: в нём есть требование без закрывающего события,
// небазированные объекты и риск к эскалации. Витрина, где всё зелёное,
// не показывает, как система ловит проблемы, — а это в ней главное.
package orbita.com.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import orbita.mod.DemoModel
import orbita.mod.RepoPaths
import orbita.mod.model.CoreType

/** Пометка демо-объектов: по ней они отличимы от рабочих (STEP-7-9 §7.2). */
const val DEMO_AUTHOR = "demo"

/** Сценарий сравнения вариантов демо-проекта. */
const val DEMO_SCENARIO = "SC-0001"

object DemoProject {

    private val mapper = ObjectMapper()

    /** Выгрузка проекта из эталона. Загрузчик один на весь проект — `DemoModel`. */
    fun load(): JsonNode = DemoModel.load()

    /**
     * Заполнение базы демо-проектом одной операцией. Порядок продиктован
     * зависимостями модели: компоненты и интерфейсы, нужды, сервисы,
     * требования (связи выводятся из документов), свидетельства, валидации,
     * риски. Виды декомпозиции проставляются после — связь derive к этому
     * моменту уже существует.
     */
    fun seed(boundary: Boundary, project: JsonNode = load()) {
        val components = project.path("components")
        // сначала элементы, затем интерфейсы: интерфейс ссылается на стороны
        components.properties().filter { it.value.path("kind").asText() != "interface" }
            .sortedBy { it.key }
            .forEach { (id, c) -> boundary.req.ingestComponent(componentJson(id, c), DEMO_AUTHOR) }
        components.properties().filter { it.value.path("kind").asText() == "interface" }
            .sortedBy { it.key }
            .forEach { (id, c) -> boundary.req.ingestInterface(interfaceJson(id, c), DEMO_AUTHOR) }

        project.path("needs").forEach { boundary.req.ingestNeed(withLifecycle(it), DEMO_AUTHOR) }
        project.path("services").forEach { boundary.req.ingestService(withLifecycle(it), DEMO_AUTHOR) }
        project.path("requirements").forEach { boundary.req.ingestRequirement(withLifecycle(it), DEMO_AUTHOR) }
        project.path("evidence").forEach { boundary.req.ingestEvidence(withLifecycle(it), DEMO_AUTHOR) }
        project.path("validations").forEach { boundary.req.ingestValidation(withLifecycle(it), DEMO_AUTHOR) }
        project.path("risks").forEach { boundary.req.ingestRisk(withLifecycle(it), DEMO_AUTHOR) }

        // Связи trace и allocation выводятся из документов при приёме требований;
        // декомпозиция задана эталоном отдельным списком и добавляется здесь.
        // Вид декомпозиции существен: allocated входит в свёртку бюджета,
        // derived — нет (ADR-019), поэтому он берётся из эталона, а не по умолчанию.
        project.path("links").filter { it.path("kind").asText() == "derive" }.forEach { l ->
            val kind = l.path("derivation_kind").asText("")
            require(kind.isNotBlank()) {
                "у связи derive ${l.path("from").asText()} → ${l.path("to").asText()} нет вида декомпозиции"
            }
            boundary.links.add(
                fromId = l.path("from").asText(),
                toId = l.path("to").asText(),
                kind = "derive",
                derivationKind = kind,
            )
        }

        // Входы моделирования — хранимые объекты (CR-005/ADR-021). Сценарий
        // ссылается на них, а не на компоненты: до CR-005 demand_map_ref вёл
        // на DM-0001, которого модель хранить не умела.
        seedModelingInputs(boundary)

        // Сценарий и результаты сравнения вариантов: экран 7 читает их из базы,
        // а не получает списком в коде — так он работает на данных модели.
        boundary.ingest(orbita.mod.model.CoreType.Scenario, scenarioJson(), DEMO_AUTHOR)
        project.path("options").forEachIndexed { i, option ->
            boundary.results.insert(
                scenarioId = DEMO_SCENARIO,
                kind = "kpi",
                payload = option,
                inputVersions = mapOf("demo_project" to "1"),
                moduleVersion = "0.1",
                rngSeed = 42L + i,
            )
        }
    }

    /** Идентификаторы входов моделирования демо-проекта. */
    const val DEMO_CONSTELLATION = "CN-0001"
    const val DEMO_SPACECRAFT = "SP-0001"
    const val DEMO_DEMAND_MAP = "DM-0001"
    const val DEMO_TERMINAL_PROFILE = "TP-0001"
    const val DEMO_GROUND_STATIONS = "GS-0001"
    const val DEMO_PROTOCOL_ADAPTER = "PA-0001"

    /**
     * Входы моделирования. Карта спроса и адаптер протокола НЕ пишутся здесь
     * руками: карта строится из внешнего датасета населения (шаг 10.2),
     * адаптер — сериализацией самого адаптера. Вторая копия этих данных
     * разошлась бы с первой молча (ловушка 1).
     */
    private fun seedModelingInputs(boundary: Boundary) {
        // Карта и станции строятся ДО модели аппарата: доли витка его
        // циклограммы генерируются из их масок (TZ-KA-009), а не пишутся руками
        val demandMap = demandMapJson()
        val stations = groundStationsJson()
        boundary.ingest(CoreType.Constellation, constellationJson(), DEMO_AUTHOR)
        boundary.ingest(CoreType.Spacecraft, spacecraftJson(maskFractions(demandMap, stations)), DEMO_AUTHOR)
        boundary.ingest(CoreType.DemandMap, demandMap, DEMO_AUTHOR)
        boundary.ingest(CoreType.TerminalProfile, terminalProfileJson(), DEMO_AUTHOR)
        boundary.ingest(CoreType.GroundStations, stations, DEMO_AUTHOR)
        boundary.ingest(CoreType.ProtocolAdapter, protocolAdapterJson(), DEMO_AUTHOR)
    }

    /**
     * Доли витка из географических масок (TZ-KA-009): ровно тот же путь, что
     * у `GET /views/spacecraft/mask-schedule`, — маски из карты и станций,
     * трасса Orekit за сутки, классификация точек. Второй копии чисел нет:
     * изменение карты спроса перегенерирует и маску, и циклограмму демо-модели.
     */
    private fun maskFractions(demandMapJson: String, stationsJson: String): Map<String, Double> {
        val walker = mapper.readTree(constellationJson()).path("walker")
        val config = orbita.bal.ConstellationConfig(
            incDeg = walker.path("inclination_deg").asDouble(),
            total = walker.path("total").asInt(),
            planes = walker.path("planes").asInt(),
            phasing = walker.path("phasing").asInt(),
            altKm = walker.path("altitude_km").asDouble(),
        )
        val masks = orbita.ka.buildMasks(
            mapper.readTree(demandMapJson), mapper.readTree(stationsJson), config.altKm,
        )
        val track = orbita.bal.VisibilityPrecompute(mapper)
            .groundTracks(config, "2026-03-20T00:00:00.000Z", 86400.0)
            .values.first().map { (_, lat, lon) -> orbita.ka.MaskPoint(lat, lon) }
        return orbita.ka.modeFractions(track, masks)
    }

    /**
     * Карта спроса из ВНЕШНЕГО ДАТАСЕТА (шаг 10.2). Программные популяции
     * выглядели как данные и вели себя как данные, но не содержали ни одной
     * реальной особенности: ни пустых океанов, ни сгущения к городам.
     *
     * Датасет проверяется по опорным точкам ДО сборки карты: перестановку
     * широты и долготы проверкой диапазонов не поймать — города Европы
     * укладываются в ±90 по обеим осям.
     */
    private fun demandMapJson(): String {
        val dataset = orbita.usr.PopulationDatasets.fromGeoJson(
            orbita.usr.PopulationDatasets.defaultPath(RepoPaths.repoRoot()),
        )
        val problems = orbita.usr.referenceCheck({ lat, lon -> dataset.lookup(lat, lon) })
        check(problems.isEmpty()) { "датасет населения не сошёлся с опорными точками: $problems" }

        // Слой 1 — население из датасета. Темп сообщений и класс берутся из
        // сценария «метеринг» библиотеки, а не из числа, выбранного здесь:
        // терминал, привязанный к населению, — это прибор учёта (шаг 10.3).
        val library = orbita.usr.ScenarioLibrary()
        val metering = library.byId(POPULATION_SCENARIO)
        val layer = orbita.usr.PopulationDatasets.populationLayer(
            dataset = dataset,
            terminalsPerCapita = TERMINALS_PER_CAPITA,
            msgsPerTerminalDay = metering.msgsPerTerminalDay,
            consumerClass = metering.consumerClass,
        )
        // Слой 3 — сценарии, чьи терминалы с населением не коррелируют. Опорные
        // ячейки самого «метеринга» не берутся: его вклад уже посчитан слоем 1,
        // и добавление привело бы к двойному счёту.
        val scenarioIds = library.scenarios.map { it.id }.filter { it != POPULATION_SCENARIO }
        val seeds = scenarioIds.flatMap { library.expandSeeds(it) }

        val cells = orbita.usr.DemandMapBuilder.build(layer, scenarios = seeds)
        val doc = orbita.usr.DemandMapBuilder.toContractJson(
            mapId = DEMO_DEMAND_MAP,
            cells = cells,
            terminalsPerCapita = TERMINALS_PER_CAPITA,
            dataset = "${dataset.id}@${dataset.version} — ${dataset.source}",
            scenarioLibraryIds = scenarioIds,
            // версии ОБОИХ входов входят в версию карты: подмена датасета или
            // правка библиотеки обязаны обесценивать результат (TZ-COM-006)
            libraryVersion = "${dataset.version}+${library.version}",
            mapper = mapper,
        )
        doc.putObject("lifecycle").put("status", "Draft").put("version", "1")
        return mapper.writeValueAsString(doc)
    }

    /** Терминалов на жителя: оценка первой очереди, одна на демо-проект. */
    private const val TERMINALS_PER_CAPITA = 0.02

    /** Сценарий библиотеки, задающий параметры слоя населения. */
    private const val POPULATION_SCENARIO = "metering"

    /** Конфигурация группировки: тот же Walker 40/5, что и в вариантах сравнения. */
    private fun constellationJson(): String =
        """{"id":"$DEMO_CONSTELLATION","name":"Walker 40/5 · 550 км","kind":"walker_delta",
            "walker":{"inclination_deg":53.0,"total":40,"planes":5,"phasing":1,"altitude_km":550.0},
            "lifecycle":{"status":"Draft","version":"1"}}"""

    /**
     * Модель аппарата. Ведомость масс задана позиционно (CR-006): без неё
     * расчёт массы обязан падать, а не возвращать ноль. Доли витка режимов
     * ГЕНЕРИРУЮТСЯ из географических масок карты спроса и станций
     * (TZ-KA-009, [maskFractions]) — явные значения в модели обязательны
     * по CR-007, но их источником служит география, а не ручной ввод.
     */
    private fun spacecraftJson(fractions: Map<String, Double>): String =
        """{"id":"$DEMO_SPACECRAFT","preset":"cubesat_16u",
            "platform":{
              "dry_mass_kg":30,
              "power":{"sa_area_m2":0.18,"sa_efficiency":0.29,"battery_wh":120},
              "attitude":{"pointing_accuracy_deg":1},
              "design_life_years":5,
              "mel":[
                {"name":"Корпус и механизмы","subsystem":"structure","mass_kg":8.0,"maturity":"existing"},
                {"name":"СЭП с батареей","subsystem":"power","mass_kg":6.0,"maturity":"modified"},
                {"name":"Маховики","subsystem":"adcs","mass_kg":1.2,"maturity":"existing","quantity":3},
                {"name":"Бортовой компьютер","subsystem":"obc","mass_kg":1.4,"maturity":"existing"},
                {"name":"Приёмопередатчик абонентской линии","subsystem":"comms","mass_kg":2.2,"maturity":"new"},
                {"name":"Полезная нагрузка","subsystem":"payload","mass_kg":6.5,"maturity":"new"}
              ]},
            "payload":{
              "architecture":"regenerative",
              "links":[
                {"id":"RL-UP","role":"user_uplink","band_hz":868000000,"tx_power_w":0.1,
                 "g_over_t_db_k":-18,"required_margin_db":3,"antenna":{"type":"patch","gain_dbi":6}},
                {"id":"RL-DN","role":"user_downlink","band_hz":868000000,"tx_power_w":2,
                 "g_over_t_db_k":-22,"required_margin_db":3,"antenna":{"type":"patch","gain_dbi":6}}
              ],
              "onboard":{"buffer_mb":64,"priority_policy":["C_prime","B_prime","A_prime"]},
              "ephemeris_beacon":{"enabled":true,"period_s":60,"format":"orbit_model"}},
            "modes":[
              {"name":"standby","power_w":6.0,"orbit_fraction":${fractions.getValue("standby")}},
              {"name":"rx","power_w":9.0,"orbit_fraction":${fractions.getValue("rx")}},
              {"name":"downlink","power_w":14.0,"orbit_fraction":${fractions.getValue("downlink")}}
            ],
            "lifecycle":{"status":"Draft","version":"1"}}"""

    /** Профиль терминала класса A′: односторонний, знает эфемериды (Р5/ADR-005). */
    private fun terminalProfileJson(): String =
        """{"id":"$DEMO_TERMINAL_PROFILE","consumer_class":"A_prime","regulatory_region":"RU864",
            "radio":{"eirp_dbm":14,"antenna_gain_dbi":2,"rx_sensitivity_dbm":-137,"duty_cycle_limit":0.01},
            "environment":"open_sky",
            "generation":{"model":"periodic","rate_per_day":4,"payload_bytes":24},
            "ephemeris":{"knows_ephemeris":true,"max_almanac_age_s":86400,
                         "beacon_rx_period_s":60,"degraded_rate_factor":0.3},
            "lifecycle":{"status":"Draft","version":"1"}}"""

    /** Набор станций приёма: размещены вручную (шаг 12 добавит рекомендательный режим). */
    private fun groundStationsJson(): String =
        """{"id":"$DEMO_GROUND_STATIONS","name":"Станции приёма «Орбита-IoT»",
            "stations":[
              {"id":"GST-MSK","name":"Москва","lat_deg":55.75,"lon_deg":37.62,
               "min_elevation_deg":10,"g_over_t_db_k":12,"placement":"manual"},
              {"id":"GST-NSK","name":"Новосибирск","lat_deg":55.03,"lon_deg":82.92,
               "min_elevation_deg":10,"g_over_t_db_k":12,"placement":"manual"},
              {"id":"GST-KHV","name":"Хабаровск","lat_deg":48.48,"lon_deg":135.07,
               "min_elevation_deg":10,"g_over_t_db_k":12,"placement":"manual"}
            ],
            "lifecycle":{"status":"Draft","version":"1"}}"""

    /** Адаптер протокола: сериализация самого адаптера, а не переписанные числа. */
    private fun protocolAdapterJson(): String {
        val doc = orbita.net.LoRaWanAdapter().toContractJson(DEMO_PROTOCOL_ADAPTER, mapper)
        doc.put("id", DEMO_PROTOCOL_ADAPTER)
        doc.putObject("lifecycle").put("status", "Draft").put("version", "1")
        return mapper.writeValueAsString(doc)
    }

    private fun scenarioJson(): String = mapper.writeValueAsString(
        mapper.createObjectNode().apply {
            put("id", DEMO_SCENARIO)
            put("name", "Сравнение вариантов построения «Орбита-IoT»")
            put("constellation_ref", DEMO_CONSTELLATION)
            put("spacecraft_ref", DEMO_SPACECRAFT)
            put("demand_map_ref", DEMO_DEMAND_MAP)
            put("ground_stations_ref", DEMO_GROUND_STATIONS)
            put("protocol_adapter_ref", DEMO_PROTOCOL_ADAPTER)
            put("delivery_mode", "store_and_forward")
            put("epoch", "2026-03-20T00:00:00Z")
            put("duration_s", 86400)
            put("rng_seed", 42)
            // Версии всех входов: без них результат невоспроизводим, и БД
            // такой сценарий не примет (V008, scenario_input_versions).
            putObject("input_versions").apply {
                put(DEMO_CONSTELLATION, "1")
                put(DEMO_SPACECRAFT, "1")
                put(DEMO_DEMAND_MAP, "1")
                put(DEMO_GROUND_STATIONS, "1")
                put(DEMO_PROTOCOL_ADAPTER, "1")
            }
            // схема сценария поля lifecycle не содержит: сценарий — расчётный
            // случай, а не управляемый объект со статусом
        },
    )

    /** Есть ли в базе объекты, созданные не заполнением демо-проекта. */
    fun hasNonDemoObjects(boundary: Boundary): Boolean =
        boundary.objects.listCurrent().any { it.createdBy != DEMO_AUTHOR }

    /**
     * Приведение записи эталона к нормативной схеме. `type` — служебное поле
     * эталона, в документе модели его нет. `status` у требования дублирует
     * lifecycle.status и схемой не предусмотрен; у риска это состояние самого
     * риска и остаётся. Свидетельства и валидации собственного жизненного
     * цикла не имеют — их схемы поля lifecycle не содержат.
     */
    private fun withLifecycle(node: JsonNode): String {
        val n: ObjectNode = node.deepCopy()
        n.remove("type")
        val id = n.path("id").asText("")
        when {
            id.startsWith("RSK-") -> if (!n.has("status")) n.put("status", "open")
            id.startsWith("EV-") || id.startsWith("VA-") -> n.remove("lifecycle")
            else -> {
                if (!n.has("lifecycle")) {
                    n.putObject("lifecycle")
                        .put("status", n.path("status").asText("Draft")).put("version", "1")
                }
                n.remove("status")
                // Эталон опускает ключ там, где событий верификации нет; схема
                // требует его наличия. Пустой массив выражает «событий нет»
                // ровно так же — и требование по-прежнему попадает в разрывы.
                if (id.startsWith("RQ-") && !n.has("verification_events")) {
                    n.putArray("verification_events")
                }
            }
        }
        return mapper.writeValueAsString(n)
    }

    private fun componentJson(id: String, c: JsonNode): String {
        val n = mapper.createObjectNode()
        n.put("id", id)
        n.put("name", c.path("name").asText())
        n.put("kind", c.path("kind").asText())
        c.path("parent").asText("").ifBlank { null }?.let { n.put("parent", it) }
        n.putObject("lifecycle").put("status", "Draft").put("version", "1")
        return mapper.writeValueAsString(n)
    }

    private fun interfaceJson(id: String, c: JsonNode): String {
        val n = mapper.createObjectNode()
        n.put("id", id)
        n.put("name", c.path("name").asText())
        n.put("kind", "interface")
        val owners: ArrayNode = n.putArray("owners")
        c.path("owners").forEach { owners.add(it.asText()) }
        n.putObject("lifecycle").put("status", "Draft").put("version", "1")
        return mapper.writeValueAsString(n)
    }
}
