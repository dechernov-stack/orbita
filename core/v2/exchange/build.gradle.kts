// Модуль v2 «exchange» — слой L5.
// Обмен: StrictDoc и ReqIF, выгрузка знаний, пакет точки.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:requirements"))
    api(project(":core:v2:architecture"))
    api(project(":core:v2:knowledge"))
    testImplementation(kotlin("test"))
}
