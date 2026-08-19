// core/req — контур требований (TZ-REQ): нужды, сервисы, трассировка,
// качество формулировок, flow down, базирование, верификация, зрелость.
dependencies {
    implementation(project(":core:mod"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(testFixtures(project(":core:mod")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
