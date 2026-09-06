// Модуль v2 «programmatics» — слой L4.
// WBS с парами к узлам, оценка диапазоном с допущениями, созревание технологий, риски со сроком-точкой.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:architecture"))
    // Оценщик готовности объявляет ExtraChecks: условия сцен 10–12 знает
    // тот, кто ведёт программатику (зависимость вниз, L4 → L1).
    api(project(":core:v2:readiness"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core:v2:kernel")))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
