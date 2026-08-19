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
// На шаге 1 расчётных модулей нет (STEP-1: баллистику, потоки, ИИ-контур не начинать),
// поэтому измерять нечего; задача сообщает об этом явно, ничего не имитируя.
// При появлении первого расчётного модуля здесь обязан появиться реальный замер
// эталонного сценария из tests/reference/ с бюджетом 5 минут.
tasks.register("perfCheck") {
    doLast {
        println("TZ-COM-004: расчётные модули на шаге 1 отсутствуют — замер не выполняется (не заглушка: измеряемого кода нет).")
    }
}
