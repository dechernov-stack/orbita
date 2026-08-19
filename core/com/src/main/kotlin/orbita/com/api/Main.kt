// Точка входа ядра: миграция хранилища, загрузка схем, HTTP API.
package orbita.com.api

import orbita.mod.RepoPaths
import orbita.mod.schema.SchemaRegistry
import orbita.mod.store.DbConfig
import orbita.mod.store.Migrator

fun main() {
    val conn = DbConfig.fromEnv().open()
    // Схема сверяется при старте; расхождение с моделями роняет запуск (Migrator/SchemaCheck)
    Migrator(RepoPaths.migrationsDir()).migrate(conn)
    val registry = SchemaRegistry(RepoPaths.schemasDir())
    val port = System.getenv("ORBITA_HTTP_PORT")?.toIntOrNull() ?: 8090
    HttpApi(Boundary(registry, conn)).start(port)
    println("orbita core: database_ready schema_checked api_started port=$port schemas=${registry.names.size}")
}
