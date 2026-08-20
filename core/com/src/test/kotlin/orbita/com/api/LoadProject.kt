// Нагрузочный проект для сквозного прохода на полном масштабе (шаг 13.2).
//
// НЕ содержательные данные, а НАГРУЗКА: экраны обязаны не деградировать на
// реальных объёмах — дерево на сотнях требований, карта спроса на тысячах
// ячеек, роза на десятке вариантов. Объекты помечены автором `load` и
// пронумерованы блоком 9xxx: перепутать их с содержательными нельзя.
//
// Живёт в тестовом наборе исходников по той же причине, что и демо-проект:
// нагрузочные фикстуры в образ изделия не попадают.
//
// Запуск: ./gradlew :core:com:loadServer  (порт 8080)
package orbita.com.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.model.CoreType
import orbita.mod.schema.SchemaRegistry

const val LOAD_AUTHOR = "load"

object LoadProject {

    private val mapper = ObjectMapper()

    /**
     * Поверх демо-проекта добавляются: 200 требований элементов (по 25 на
     * каждый из 8 компонентов), у каждого события верификации и распределение;
     * 10 вариантов сравнения; карта спроса остаётся из датасета — она уже
     * несёт тысячи ячеек.
     */
    fun seed(boundary: Boundary) {
        DemoProject.seed(boundary)

        val components = boundary.objects.listCurrent()
            .filter { it.type == "component" }.map { it.id }.sorted()

        var n = 0
        for (component in components) {
            repeat(25) { i ->
                n += 1
                val id = "RQ-9%03d".format(n)
                boundary.req.ingestRequirement(
                    """{"id":"$id",
                        "statement":"Нагрузочное требование $n к элементу $component: параметр №$i удерживается в допуске.",
                        "category":"performance","level":"element",
                        "owner":"нагрузочный прогон",
                        "traces_up":[{"ref":"RQ-0100"}],
                        "allocated_to":[{"component":"$component"}],
                        "verification_events":[
                          {"id":"VE-9%03d","method":"analysis","kind":"qualification","level":"component",
                           "closes":true,"status":"planned","phase":"PhaseA",
                           "approach":"Анализ параметра №$i по модели элемента"}],
                        "lifecycle":{"status":"Draft","version":"1"}}""".format(n),
                    LOAD_AUTHOR,
                )
            }
        }

        // Десять вариантов сравнения: роза и Парето на реальном числе осей
        repeat(10) { i ->
            boundary.results.insert(
                scenarioId = DEMO_SCENARIO,
                kind = "kpi",
                payload = mapper.createObjectNode()
                    .put("name", "Вариант Н-%02d".format(i + 1))
                    .put("quality", 0.5 + 0.04 * i)
                    .put("cost", 60.0 + 5.0 * i)
                    .put("reliability", 0.65 + 0.03 * i)
                    .put("energy", 60.0 + 2.5 * i)
                    .put("deployment_days", 200.0 - 10.0 * i)
                    .put("launch_campaigns", (1 + i % 3).toDouble()),
                inputVersions = mapOf("load_project" to "1"),
                moduleVersion = "0.1",
                rngSeed = 4200L + i,
            )
        }
    }
}

/** Сервер с нагрузочным проектом — для прохода экранов на полном масштабе. */
fun main() {
    val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    TestDb.truncateAll()
    LoadProject.seed(boundary)
    val requirements = boundary.objects.listCurrent().count { it.type == "requirement" }
    val port = System.getenv("ORBITA_HTTP_PORT")?.toIntOrNull() ?: 8080
    HttpApi(boundary).start(port)
    println("orbita load api: port=$port, требований $requirements, вариантов 13")
    Thread.currentThread().join()
}
