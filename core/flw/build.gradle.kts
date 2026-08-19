// core/flw — информационные потоки (TZ-FLW): гибридное ядро Монте-Карло,
// коллизии через адаптер протокола, бюджет времени реакции, узкие места.
dependencies {
    implementation(project(":core:mod"))
    implementation(project(":core:net"))    // модель коллизий (TZ-NET-003)
    implementation(project(":core:ka"))     // политика приоритетов буфера (TZ-KA-008)
    implementation(project(":core:bal"))    // предрасчёт геометрии и вектор KPI
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Регрессия производительности эталонного прогона (TZ-FLW-001 MOP, TZ-COM-004):
// предрасчёт геометрии плюс ядро Монте-Карло. Превышение бюджета времени
// останавливает сборку.
tasks.register<JavaExec>("perfCheck") {
    group = "verification"
    description = "TZ-COM-004: замер эталонного прогона против бюджета"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("orbita.flw.FlowPerfCheckKt")
    environment("ORBITA_REPO_ROOT", rootDir.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
