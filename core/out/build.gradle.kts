// core/out — отчёты (TZ-OUT-003/004): матрицы трассировки и верификации,
// отчёт зрелости пакета к контрольной точке.
dependencies {
    implementation(project(":core:mod"))
    implementation(project(":core:req"))
    implementation(project(":core:bal"))   // нормировка розы KPI и горизонты тепловой карты
    implementation(project(":core:usr"))   // карта спроса экрана 4
    implementation(project(":core:ka"))    // бюджеты аппарата экрана 5
    implementation(project(":core:net"))   // скорость и Eb/N0 радиолиний — из адаптера
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(testFixtures(project(":core:mod")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
