// Модуль v2 «ai» — слой сквозной.
// Виды пакетов, сборка промпта по источникам, канал пакетов, внешний контур, живые вызовы с журналом.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:library"))
    api(project(":core:v2:knowledge"))
    api(project(":core:v2:formulation"))
    api(project(":core:v2:requirements"))
    api(project(":core:v2:architecture"))
    testImplementation(kotlin("test"))
}
