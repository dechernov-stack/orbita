// core/flw — информационные потоки (TZ-FLW): гибридное ядро Монте-Карло,
// коллизии через адаптер протокола, бюджет времени реакции, узкие места.
dependencies {
    implementation(project(":core:mod"))
    implementation(project(":core:net"))
    implementation(project(":core:usr"))
    implementation(project(":core:ka"))
    implementation(project(":core:bal"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
