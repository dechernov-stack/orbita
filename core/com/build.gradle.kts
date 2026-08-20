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
