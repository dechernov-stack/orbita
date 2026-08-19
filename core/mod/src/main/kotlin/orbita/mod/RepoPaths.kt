// Расположение нормативных каталогов репозитория (schemas/, db/migrations/).
package orbita.mod

import java.nio.file.Files
import java.nio.file.Path

object RepoPaths {

    /**
     * Корень репозитория: переменная ORBITA_REPO_ROOT (её задаёт сборка Gradle),
     * иначе поиск каталога schemas/ вверх от рабочего каталога.
     */
    fun repoRoot(): Path {
        System.getenv("ORBITA_REPO_ROOT")?.let { return Path.of(it) }
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("schemas"))) return dir
            dir = dir.parent
        }
        error("repository root not found: no schemas/ directory upwards from ${Path.of("").toAbsolutePath()}")
    }

    fun schemasDir(): Path = repoRoot().resolve("schemas")

    fun migrationsDir(): Path = repoRoot().resolve("db/migrations")
}
