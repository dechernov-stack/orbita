// Модуль v2 «readiness» — слой L1.
// Каталог проверок, разрывы, лестница зрелости, TRL-шлюз; реализует оценщик ворот.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    // Порт GateEvaluator объявлен в process: readiness его РЕАЛИЗУЕТ.
    // Зависимость идёт на api соседа того же слоя — это разрешено
    // (ТЗ-BACKEND §3: readiness → доменные, только через api).
    api(project(":core:v2:process"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core:v2:kernel")))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
