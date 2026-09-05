// Модуль v2 «knowledge» — слой L2.
// Материалы, канон и карта разбора, задания загрузки, факты, темы, допущения.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:library"))
    testImplementation(kotlin("test"))
}
