// Модуль v2 «architecture» — слой L3.
// Компоненты с гранями, параметры, стыки, функции, цепочки, режимы, элементы обмена, конфигурационные единицы.
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
