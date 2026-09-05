// Модуль v2 «kernel» — слой ядро.
// Ядро сущности: id, code, kind, area, born_in, статус, версия, провенанс, история; связи реестром; схемы из YAML.
//
// Зависимости только вниз по слоям (ТЗ-BACKEND-V2 §3): наверх и вбок
// смотреть нельзя — это держит архитектурный тест, а не договорённость.
plugins {
    // Тестовая обвязка БД переиспользуется тестами модулей v2
    `java-test-fixtures`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("org.postgresql:postgresql:42.7.7")

    testFixturesApi("org.postgresql:postgresql:42.7.7")
    testImplementation(kotlin("test"))
}
