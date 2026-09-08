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
    // На SIGTERM соединение закрывается ПО-ЧЕЛОВЕЧЕСКИ. Пересоздание
    // контейнера рвало сокет JDBC на полуслове, и backend Postgres умирал с
    // exit code 2, уводя кластер в recovery (семь падений 08.09 — все по
    // секунде совпали с остановкой api). Закрытый сокет так не роняет.
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { conn.close() } })
    println("orbita core: database_ready schema_checked api_started port=$port schemas=${registry.names.size}")
}
