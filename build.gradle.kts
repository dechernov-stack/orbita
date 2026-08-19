// Корневая сборка ядра (ADR-010). Расчётные зависимости на шаге 1 не подключаются (STEP-1 §1.1).
plugins {
    kotlin("jvm") version "2.0.21" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // БД для тестов хранилища — см. ci/local_db.sh и core/mod/README.md
        environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
        }
    }
}

// Регрессия производительности (TZ-COM-004, STEP-3 §0.2): реальный замер
// предрасчёта геометрии эталонной конфигурации (core/bal, PerfCheck).
// Превышение бюджета времени останавливает сборку.
tasks.register("perfCheck") {
    dependsOn(":core:bal:perfCheck")
}
