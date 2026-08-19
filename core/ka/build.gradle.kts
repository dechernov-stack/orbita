// core/ka — космический аппарат (TZ-KA): платформа, ПН, массовый и
// энергетический бюджеты, бюджеты линий, зоны обслуживания, маяк, буфер, TPM.
// Зависимость от core/net обязательна: требуемое Eb/N0 и оверхед берутся
// только из адаптера протокола (TZ-KA-007, ловушка 3).
dependencies {
    implementation(project(":core:mod"))
    implementation(project(":core:net"))
    implementation(project(":core:bal"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
