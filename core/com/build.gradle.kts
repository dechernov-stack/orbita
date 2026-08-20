// core/com — общесистемное (TZ-COM): граница модуля, HTTP API, валидация входа.
// HTTP — jdk.httpserver из состава JDK: на шаге 1 внешний веб-фреймворк не нужен.
dependencies {
    implementation(project(":core:mod"))
    implementation(project(":core:req"))
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
