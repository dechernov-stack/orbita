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
import orbita.mod.RepoPaths
import java.util.concurrent.TimeUnit

/** Пометка демо-объектов: по ней они отличимы от рабочих (STEP-7-9 §7.2). */
const val DEMO_AUTHOR = "demo"

/** Сценарий сравнения вариантов демо-проекта. */
const val DEMO_SCENARIO = "SC-0001"

object DemoProject {

    private val mapper = ObjectMapper()

    /** Выгрузка проекта из эталона. Второй копии данных в проекте нет. */
    fun load(): JsonNode {
        val script = RepoPaths.repoRoot().resolve("spec/demo_project.py")
        val process = ProcessBuilder("python3", script.toString(), "--dump")
            .directory(RepoPaths.repoRoot().toFile())
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.readAllBytes().decodeToString()
        val err = process.errorStream.readAllBytes().decodeToString()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            error("не удалось выгрузить демо-проект из эталона: $err")
        }
        return mapper.readTree(out)
    }

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

    private fun scenarioJson(): String = mapper.writeValueAsString(
        mapper.createObjectNode().apply {
            put("id", DEMO_SCENARIO)
            put("name", "Сравнение вариантов построения «Орбита-IoT»")
            put("constellation_ref", "CM-0010")
            put("spacecraft_ref", "CM-0011")
            put("demand_map_ref", "DM-0001")
            put("delivery_mode", "store_and_forward")
            put("epoch", "2026-03-20T00:00:00Z")
            put("duration_s", 86400)
            put("rng_seed", 42)
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
