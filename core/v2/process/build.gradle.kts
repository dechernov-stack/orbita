// Модуль v2 «process» — слой L1.
// Движок процесса на Flowable: фазы, сцены, шаги, ворота, решения, задания; видимость видов по сцене.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
dependencies {
    api(project(":core:v2:kernel"))
    api(project(":core:v2:access"))
    api(project(":core:v2:readiness"))
    // Движок процесса (РЕШЕНИЕ-ДВИЖОК-ПРОЦЕССА): Flowable 7, Apache-2.0.
    // CMMN — точное попадание в регламент: сцена = stage, ворота = sentry,
    // точка = milestone. Домена в движке нет, там только состояние процесса.
    api("org.flowable:flowable-cmmn-engine:7.0.1")
    api("org.flowable:flowable-engine:7.0.1")
    runtimeOnly("com.h2database:h2:2.2.224")
    testImplementation(kotlin("test"))
}
