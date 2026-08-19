// core/mod — модель данных (TZ-MOD): типы из схем, реестр схем, хранилище PostgreSQL.
plugins {
    // Тестовая обвязка БД (TestDb) переиспользуется тестами других модулей ядра
    `java-test-fixtures`
}

dependencies {
    // Валидация JSON Schema 2020-12 (TZ-MOD-001, TZ-MOD-002)
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // Хранилище (ADR-011)
    implementation("org.postgresql:postgresql:42.7.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
