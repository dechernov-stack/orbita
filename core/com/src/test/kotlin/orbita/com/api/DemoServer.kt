// Сервер с наполненной моделью для проверки экранов клиента на РЕАЛЬНЫХ данных
// API (STEP-6, определение готовности; STEP-7-9 §7.2). Живёт в тестовом наборе
// исходников: это средство проверки, а не часть изделия — наполнять прод
// фикстурами нельзя.
//
// Модель — демонстрационный проект «Орбита-IoT» из эталона spec/demo_project.py.
// Собственных демо-данных здесь нет: вторая копия разошлась бы с эталоном.
//
// Запуск: ./gradlew :core:com:demoServer
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.TestDb
import orbita.mod.schema.SchemaRegistry

fun main() {
    val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), TestDb.conn)
    TestDb.truncateAll()
    DemoProject.seed(boundary)

    val port = System.getenv("ORBITA_HTTP_PORT")?.toIntOrNull() ?: 8080
    HttpApi(boundary).start(port)
    println("orbita demo api: port=$port, проект «Орбита-IoT» загружен")
    Thread.currentThread().join()
}
