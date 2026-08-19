// core/usr — контур потребителей (TZ-USR): профили терминалов, популяции,
// карта спроса, профили активности, библиотека сценариев, агрегация метрик.
dependencies {
    implementation(project(":core:mod"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
