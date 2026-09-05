// Модуль v2 «readiness» — слой L1.
// Каталог проверок, разрывы, лестница зрелости, TRL-шлюз; реализует оценщик ворот.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    testImplementation(kotlin("test"))
}
