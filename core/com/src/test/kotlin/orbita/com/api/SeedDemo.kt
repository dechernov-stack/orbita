// Заполнение базы демонстрационным проектом ОДНОЙ операцией (STEP-7-9 §7.2).
//
// Запуск: ./gradlew :core:com:seedDemo
//
// Демо-проект не смешивается с рабочими: объекты помечаются автором `demo`,
// и заполнение отказывается работать по базе, где есть чужие объекты.
// Иначе демонстрационные данные однажды попали бы в рабочий проект — и это
// заметили бы на обзоре, а не при заполнении.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.DbConfig
import orbita.mod.store.Migrator
import kotlin.system.exitProcess

fun main() {
    val conn = DbConfig.fromEnv().open()
    Migrator(RepoPaths.migrationsDir()).migrate(conn)
    val boundary = Boundary(SchemaRegistry(RepoPaths.schemasDir()), conn)

    val existing = boundary.objects.listCurrent()
    val foreign = existing.filter { it.createdBy != DEMO_AUTHOR }
    if (foreign.isNotEmpty()) {
        println(
            "заполнение отменено: в базе есть объекты рабочего проекта " +
                "(${foreign.size}, например ${foreign.first().id} от ${foreign.first().createdBy})",
        )
        exitProcess(1)
    }
    if (existing.isNotEmpty()) {
        println("заполнение отменено: демо-проект уже загружен (${existing.size} объектов)")
        exitProcess(1)
    }

    DemoProject.seed(boundary)
    val byType = boundary.objects.listCurrent().groupingBy { it.type }.eachCount().toSortedMap()
    println("демо-проект «Орбита-IoT» загружен: " + byType.entries.joinToString(", ") { "${it.key} ${it.value}" })
}
