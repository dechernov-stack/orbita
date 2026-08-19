// core/com — общесистемное (TZ-COM): граница модуля, HTTP API, валидация входа.
// HTTP — jdk.httpserver из состава JDK: на шаге 1 внешний веб-фреймворк не нужен.
dependencies {
    implementation(project(":core:mod"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(testFixtures(project(":core:mod")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
