// core/com — общесистемное (TZ-COM): граница модуля, HTTP API, валидация входа.
// HTTP — jdk.httpserver из состава JDK: на шаге 1 внешний веб-фреймворк не нужен.
dependencies {
    // Фасад совместимости v2 (ТЗ-BACKEND §3): нынешний сервер отдаёт пути
    // /api/v2/** роутеру второй версии. Единственная нить между старым и
    // новым — она же исчезнет в волне 6 вместе с HttpApi.
    implementation(project(":core:v2:api"))
    implementation(project(":core:mod"))
    implementation(project(":core:req"))
    implementation(project(":core:flw"))
    implementation(project(":core:usr"))
    implementation(project(":core:out"))
    implementation(project(":core:bal"))
    implementation(project(":core:ka"))
    implementation(project(":core:net"))
    implementation(project(":core:ai"))   // ИИ-контур: пакеты, разбор, фильтр, diff
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(testFixtures(project(":core:mod")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Сервер с наполненной моделью для проверки экранов клиента на реальных данных
// API (STEP-6). Не часть изделия: точка входа и фикстуры живут в тестовом наборе.
tasks.register<JavaExec>("demoServer") {
    group = "verification"
    description = "STEP-6: API с демонстрационной моделью для проверки экранов клиента"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("orbita.com.api.DemoServerKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// ПУСТОЙ проект для проверки рабочего слоя (шаг 15, критерий приёмки):
// Ш1–Ш7 проходятся через интерфейс, а не по чужим данным seedDemo.
tasks.register<JavaExec>("emptyServer") {
    group = "verification"
    description = "STEP-15: API с ПУСТЫМ проектом — наполнение только через интерфейс"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("orbita.com.api.EmptyServerKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// Заполнение базы демонстрационным проектом одной операцией (STEP-7-9 §7.2).
// Данные берутся из эталона spec/demo_project.py — второй копии в проекте нет.
tasks.register<JavaExec>("seedDemo") {
    group = "application"
    description = "STEP-7: загрузить демонстрационный проект «Орбита-IoT» в базу"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("orbita.com.api.SeedDemoKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// ---------------------------------------------------------------------------
// Сборка для контейнеров. ДВА образа, а не один: фикстуры демо-проекта живут
// в тестовом наборе исходников и в образ изделия не попадают (STEP-7-9 §7.2).
// Один общий образ был бы удобнее ровно до того дня, когда демонстрационные
// данные оказались бы в рабочем проекте.
// ---------------------------------------------------------------------------

/** Подписи чужих JAR внутри общего архива недействительны — их надо убрать. */
fun Jar.excludeSignatures() {
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

/** Образ изделия: только main. Точка входа — orbita.com.api.MainKt. */
tasks.register<Jar>("apiJar") {
    group = "distribution"
    description = "Единый архив API ядра для контейнера (без фикстур)"
    archiveFileName.set("orbita-api.jar")
    manifest { attributes["Main-Class"] = "orbita.com.api.MainKt" }
    from(sourceSets["main"].output)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    from({ configurations.runtimeClasspath.get().filter { it.isDirectory } })
    excludeSignatures()
}

/** Образ заполнения демо-проекта: main + test. Точка входа — SeedDemoKt. */
tasks.register<Jar>("seedJar") {
    group = "distribution"
    description = "Единый архив заполнения демо-проекта для контейнера"
    archiveFileName.set("orbita-seed.jar")
    manifest { attributes["Main-Class"] = "orbita.com.api.SeedDemoKt" }
    from(sourceSets["main"].output, sourceSets["test"].output)
    from({ configurations.testRuntimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    from({ configurations.testRuntimeClasspath.get().filter { it.isDirectory } })
    excludeSignatures()
}

// Разовый просмотр документов на заполненной базе (шаг 11.1).
tasks.register<JavaExec>("dumpDocsOnDb") {
    group = "verification"
    description = "Печать разделов и разрывов трёх документов на демо-проекте в базе"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("orbita.com.api.DocsOnDbKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// Сервер с нагрузочным проектом (шаг 13.2): экраны на полном масштабе.
tasks.register<JavaExec>("loadServer") {
    group = "verification"
    description = "Шаг 13.2: API с нагрузочным проектом для прохода экранов"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("orbita.com.api.LoadProjectKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
