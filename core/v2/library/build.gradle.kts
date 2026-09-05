// Модуль v2 «library» — слой L0.
// Полки и шаблоны: PBS, стыки, Arcadia, WBS, шаблоны компонентов, шаблон фазы, каталог процессов; окно взятия.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    testImplementation(kotlin("test"))
}
