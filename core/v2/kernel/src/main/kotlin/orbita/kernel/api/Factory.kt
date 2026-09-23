// Фабрика ядра: порты собираются здесь, реализация наружу не показывается.
package orbita.kernel.api

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.kernel.internal.GeneratedSchemaRegistry
import orbita.kernel.internal.PgEntityStore
import orbita.kernel.internal.PgLinkRegistry
import orbita.kernel.internal.PgTextIndex
import java.nio.file.Path
import java.sql.Connection

object KernelFactory {
    fun entityStore(conn: Connection, mapper: ObjectMapper = ObjectMapper()): EntityStore =
        PgEntityStore(conn, mapper)

    fun linkRegistry(conn: Connection): LinkRegistry = PgLinkRegistry(conn)

    /**
     * Текстовый индекс базы знаний (шип 4 §2): таблица ядра, вектор — колонкой
     * pgvector, если расширение доступно; размерность — по провайдеру эмбеддингов.
     */
    fun textIndex(conn: Connection, mapper: ObjectMapper = ObjectMapper(), vectorDim: Int = 1024): TextIndex =
        PgTextIndex(conn, mapper, vectorDim)

    fun schemaRegistry(schemasV2Dir: Path): SchemaRegistry = GeneratedSchemaRegistry(schemasV2Dir)
}
