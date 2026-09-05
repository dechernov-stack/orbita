// Модуль v2 «models» — слой L4.
// Модели системы, прогоны, метрики, бюджеты с резервами, варианты построения, оценка засорения.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:architecture"))
    api(project(":core:v2:formulation"))
    testImplementation(kotlin("test"))
}
