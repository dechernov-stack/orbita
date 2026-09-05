// Модуль v2 «documents» — слой L5.
// Порт-контракт документов: документ, раздел, элемент, рендеринг, выпуск, результат.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:requirements"))
    api(project(":core:v2:architecture"))
    api(project(":core:v2:models"))
    api(project(":core:v2:formulation"))
    api(project(":core:v2:knowledge"))
    testImplementation(kotlin("test"))
}
