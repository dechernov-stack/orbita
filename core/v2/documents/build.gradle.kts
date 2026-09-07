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
    // Печать: PDFBox рисует лист сам — внешнего конвертера в контуре нет.
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    // JGit — чистая JVM: в образе изделия нет git и намеренно нет apt
    // (см. ops/api.Dockerfile), а базирование обязано ставить настоящий
    // тег. Лицензия EDL 1.0 (BSD-3) — не GPL и не EPL.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core:v2:kernel")))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
