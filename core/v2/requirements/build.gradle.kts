// Модуль v2 «requirements» — слой L3.
// Требования уровнями, EARS, типовые с применимостью, связи с suspect, базовые наборы, события верификации.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:formulation"))
    api(project(":core:v2:knowledge"))
    testImplementation(kotlin("test"))
}
