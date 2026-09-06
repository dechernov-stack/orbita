// Модуль v2 «architecture» — слой L3.
// Компоненты с гранями, параметры, стыки, функции, цепочки, режимы, элементы обмена, конфигурационные единицы.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    // Оценщик готовности объявляет точку расширения ExtraChecks: правило
    // носителя живёт здесь, а спрашивают его ворота (зависимость вниз, L3 → L1).
    api(project(":core:v2:readiness"))
    api(project(":core:v2:formulation"))
    api(project(":core:v2:knowledge"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core:v2:kernel")))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
