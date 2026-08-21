// ПУСТОЙ проект для проверки рабочего слоя (шаг 15, критерий приёмки).
//
// Единственный критерий шага: инженер проходит Ш1–Ш7 через интерфейс от пустого
// проекта до пакета передачи. Проверять это на demoServer нельзя — там модель
// уже наполнена чужими данными, и ввод подменяется просмотром.
//
// Живёт в тестовом наборе исходников по той же причине, что demoServer: это
// средство проверки, а не часть изделия.
//
// Запуск: ./gradlew :core:com:emptyServer
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry

fun main() {
    val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    TestDb.truncateAll()

    val port = System.getenv("ORBITA_HTTP_PORT")?.toIntOrNull() ?: 8080
    HttpApi(boundary).start(port)
    println("orbita empty api: port=$port, проект ПУСТ — наполнение только через интерфейс")
    Thread.currentThread().join()
}
