// Ядро ИС (ADR-010: Kotlin/JVM). Модуль = префикс ТЗ (core/README.md):
// core/mod ← TZ-MOD, core/com ← TZ-COM, core/req ← TZ-REQ, core/usr ← TZ-USR, core/out ← TZ-OUT.
rootProject.name = "orbita"

include(":core:mod", ":core:com", ":core:req", ":core:usr", ":core:out", ":core:bal", ":core:ka", ":core:net")
