// Фабрика ядра: порты собираются здесь, реализация наружу не показывается.
package orbita.kernel.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.internal.GeneratedSchemaRegistry
import orbita.kernel.internal.PgEntityStore
import orbita.kernel.internal.PgLinkRegistry
import java.nio.file.Path
import java.sql.Connection

object KernelFactory {
    fun entityStore(conn: Connection, mapper: ObjectMapper = ObjectMapper()): EntityStore =
        PgEntityStore(conn, mapper)

    fun linkRegistry(conn: Connection): LinkRegistry = PgLinkRegistry(conn)

    fun schemaRegistry(schemasV2Dir: Path): SchemaRegistry = GeneratedSchemaRegistry(schemasV2Dir)
}
