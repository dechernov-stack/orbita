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
    // Пул с проверкой и переподключением (ПРИЁМКА-KNOWLEDGE-REMARKS): одно
    // JDBC-соединение без переподключения превращало любой чих базы в
    // мёртвый стенд до docker restart. Лицензия Apache-2.0.
    implementation("com.zaxxer:HikariCP:6.2.1")
    // Журнал пула в stderr контейнера: «Failed to validate connection» и
    // переподключения видны в docker logs, а не глотаются NOP-логгером.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    // Обвязка тестов: выгрузка демо-проекта из эталона (DemoModel) отдаёт JsonNode,
    // и тип виден потребителям — потому api, а не implementation
    testFixturesApi("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
