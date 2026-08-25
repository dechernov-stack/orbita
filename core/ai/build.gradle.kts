// core/ai — ИИ-контур (TZ-AI): промпт-пакеты, разбор ответа, структурный фильтр,
// арбитраж, акцепт, применение как diff.
//
// Зависимость от core/req — принципиальная: фильтр обязан вызывать ТЕ ЖЕ функции
// правил, что применяются к рукописному требованию. Собственных «облегчённых»
// проверок для предложений ИИ здесь нет и быть не должно (STEP-5, ловушка 1).
dependencies {
    implementation(project(":core:mod"))
    implementation(project(":core:req"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // инлайн-схема ответа дозаполнения (parseAgainstInline) — та же
    // библиотека, что в core/mod у нормативного реестра схем
    implementation("com.networknt:json-schema-validator:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
