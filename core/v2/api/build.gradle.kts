// Модуль v2 «api» — слой фасад.
// Тонкие маршруты по модулям: файл ≤ 300 строк, домена внутри нет.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:library"))
    api(project(":core:v2:process"))
    api(project(":core:v2:readiness"))
    api(project(":core:v2:knowledge"))
    api(project(":core:v2:formulation"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core:v2:kernel")))
}
