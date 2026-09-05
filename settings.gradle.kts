// Ядро ИС (ADR-010: Kotlin/JVM). Модуль = префикс ТЗ (core/README.md):
// core/mod ← TZ-MOD, core/com ← TZ-COM, core/req ← TZ-REQ, core/usr ← TZ-USR, core/out ← TZ-OUT.
rootProject.name = "orbita"

include(
    ":core:mod", ":core:com", ":core:req", ":core:usr", ":core:out",
    ":core:bal", ":core:ka", ":core:net", ":core:flw", ":core:ai",
)

// Каркас v2 (ТЗ-BACKEND-V2 §3): 14 модулей с явным api и internal.
// Старые модули живут рядом до замены — strangler, не переписывание разом.
include(
    ":core:v2:kernel", ":core:v2:access", ":core:v2:library", ":core:v2:readiness",
    ":core:v2:process", ":core:v2:knowledge", ":core:v2:formulation",
    ":core:v2:requirements", ":core:v2:architecture", ":core:v2:models",
    ":core:v2:programmatics", ":core:v2:documents", ":core:v2:ai", ":core:v2:exchange",
    ":core:v2:api",
)
