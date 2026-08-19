// core/bal — баллистика (TZ-BAL): механика и геометрия в замкнутых формулах,
// пропагация и события видимости — Orekit (ADR-010: собственные интеграторы
// и модели видимости не создаются).
plugins {
    // Тестовые утилиты сходимости переиспользуются задачей perfCheck
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core:mod"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.orekit:orekit:12.2.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Регрессия производительности (TZ-COM-004, STEP-3 §0.2): замер предрасчёта
// геометрии эталонной конфигурации из spec/reference/perf_geometry.json.
// Превышение бюджета времени останавливает сборку.
tasks.register<JavaExec>("perfCheck") {
    group = "verification"
    description = "TZ-COM-004: замер предрасчёта геометрии против бюджета"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("orbita.bal.PerfCheckKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    // сообщения задачи — на русском; вывод раннера CI не обязан быть UTF-8 по умолчанию
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
