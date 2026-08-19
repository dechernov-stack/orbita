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

// Регрессия производительности (TZ-COM-004) — сквозной критерий для расчётного кода.
// До появления расчётных модулей задача ПАДАЕТ (STEP-2 §0.2): молчаливо зелёная
// заглушка приучает к зелёному цвету и обесценивает проверку. Вызов в ci/checks.sh
// закомментирован до шага 4; там задача заменяется реальным замером эталонного
// сценария из tests/reference/ с бюджетом 5 минут.
tasks.register("perfCheck") {
    doLast {
        throw GradleException("TZ-COM-004: регрессия производительности включается на шаге 4 (баллистика)")
    }
}
