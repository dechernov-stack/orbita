// Сервер с наполненной моделью для проверки экранов клиента на РЕАЛЬНЫХ данных
// API (STEP-6, определение готовности). Живёт в тестовом наборе исходников:
// это средство проверки, а не часть изделия — наполнять прод фикстурами нельзя.
//
// Запуск: ./gradlew :core:com:demoServer
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry

fun main() {
    val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    TestDb.truncateAll()
    seedDemoModel(boundary)

    val port = System.getenv("ORBITA_HTTP_PORT")?.toIntOrNull() ?: 8080
    HttpApi(boundary).start(port)
    println("orbita demo api: port=$port")
    Thread.currentThread().join()
}

/** Небольшая, но полная модель: дерево требований, свёртка, превышение, V&V. */
fun seedDemoModel(boundary: Boundary) {
    boundary.req.ingestNeed(
        """{"id":"ND-0001","statement":"Сбор данных с датчиков в удалённых районах.",
            "stakeholder":{"name":"Оператор системы","role":"operator","priority":2},
            "lifecycle":{"status":"Draft","version":"1"}}""",
    )
    boundary.req.ingestService(
        """{"id":"SV-0001","name":"Сбор телеметрии датчиков","traces_up":["ND-0001"],
            "qos_profiles":[{"consumer_class":"A_prime","moe":[{"id":"MOE-0001",
              "name":"delivery_probability_daily",
              "target":{"value":0.9,"unit":"1","provenance":{"source":"manual"}}}]}],
            "lifecycle":{"status":"Draft","version":"1"}}""",
    )
    listOf(
        """{"id":"CM-0001","name":"Космический аппарат","kind":"system",
            "lifecycle":{"status":"Draft","version":"1"}}""",
        """{"id":"CM-0002","name":"Платформа","kind":"subsystem","parent":"CM-0001",
            "lifecycle":{"status":"Draft","version":"1"}}""",
        """{"id":"CM-0003","name":"Полезная нагрузка","kind":"subsystem","parent":"CM-0001",
            "lifecycle":{"status":"Draft","version":"1"}}""",
    ).forEach { boundary.req.ingestComponent(it) }

    // Масса: свёртка укладывается в бюджет
    boundary.req.ingestRequirement(mass("RQ-0100", "CM-0001", 100.0, "Масса КА", "VE-0100"))
    boundary.req.ingestRequirement(mass("RQ-0101", "CM-0002", 60.0, "Масса платформы", "VE-0101", "RQ-0100"))
    boundary.req.ingestRequirement(mass("RQ-0102", "CM-0003", 30.0, "Масса ПН", "VE-0102", "RQ-0100"))
    boundary.req.deriveAs("RQ-0100", "RQ-0101", "allocated")
    boundary.req.deriveAs("RQ-0100", "RQ-0102", "allocated")

    // Мощность: свёртка ПРЕВЫШАЕТ бюджет — полоса должна покраснеть
    boundary.req.ingestRequirement(power("RQ-0200", "CM-0001", 95.0, "Мощность КА", "VE-0200"))
    boundary.req.ingestRequirement(power("RQ-0201", "CM-0002", 60.0, "Мощность платформы", "VE-0201", "RQ-0200"))
    boundary.req.ingestRequirement(power("RQ-0202", "CM-0003", 52.0, "Мощность ПН", "VE-0202", "RQ-0200"))
    boundary.req.deriveAs("RQ-0200", "RQ-0201", "allocated")
    boundary.req.deriveAs("RQ-0200", "RQ-0202", "allocated")
}

private fun mass(
    id: String,
    component: String,
    limit: Double,
    name: String,
    eventId: String,
    parent: String? = null,
) = requirementJson(id, component, limit, "kg", name, eventId, parent, "Сухая масса")

private fun power(
    id: String,
    component: String,
    limit: Double,
    name: String,
    eventId: String,
    parent: String? = null,
) = requirementJson(id, component, limit, "W", name, eventId, parent, "Потребляемая мощность")

private fun requirementJson(
    id: String,
    component: String,
    limit: Double,
    unit: String,
    name: String,
    eventId: String,
    parent: String?,
    what: String,
) = """
    {"id":"$id","level":"system","category":"performance",
     "statement":"$what не должна превышать $limit.",
     "traces_up":[{"ref":"SV-0001","consumer_class":"A_prime"}],
     "derives_from":${parent?.let { "[\"$it\"]" } ?: "[]"},
     "allocated_to":[{"component":"$component","kind":"full"}],
     "mop":{"name":"$name","operator":"le","rollup":"sum",
       "value":{"value":$limit,"unit":"$unit","provenance":{"source":"manual"}}},
     "verification_events":[{"id":"$eventId","method":"test","phase":"PhaseD","level":"system",
       "kind":"qualification","approach":"Измерение на собранном изделии по методике испытаний.",
       "means":"Испытательный стенд","status":"planned","closes":true,"design_version":"v1"}],
     "lifecycle":{"status":"Draft","version":"1"},"owner":"вед. системный инженер"}
"""
