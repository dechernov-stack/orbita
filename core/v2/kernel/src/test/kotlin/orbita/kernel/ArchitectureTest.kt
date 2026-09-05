// Архитектурный тест каркаса v2 (ТЗ-BACKEND-V2 §2, §3).
//
// Он проверяет то, о чём иначе договариваются словами и что разрушается за
// неделю: границу api/internal и направление зависимостей. Правила читаются
// из самих build.gradle.kts модулей — второй копии карты модулей нет, и
// разойтись ей не с чем.
package orbita.kernel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {

    /** Слои по возрастанию: зависеть можно только на свой уровень и ниже. */
    private val порядок = listOf("ядро", "L0", "L1", "L2", "L3", "L4", "L5", "сквозной")

    private val корень = File(System.getenv("ORBITA_REPO_ROOT") ?: ".").resolve("core/v2")

    private data class Модуль(val имя: String, val слой: String, val зависимости: List<String>)

    private fun модули(): List<Модуль> = корень.listFiles()!!
        // Каталог модуля опознаётся сборочным файлом, а не именем: рядом
        // лежит `build` — артефакт Gradle, и он не модуль.
        .filter { it.isDirectory && it.resolve("build.gradle.kts").isFile }
        .sortedBy { it.name }
        .map { каталог ->
            val сборка = каталог.resolve("build.gradle.kts").readText()
            val слой = Regex("""слой ([^.\n]+)\.""").find(сборка)?.groupValues?.get(1)?.trim()
                ?: error("модуль ${каталог.name} не объявил слой в build.gradle.kts")
            val зависимости = Regex("""project\("":core:v2:([a-z]+)""".replace("\"\"", "\"")).findAll(сборка)
                .map { it.groupValues[1] }.toList()
            Модуль(каталог.name, слой, зависимости)
        }

    @Test
    fun `каркас собран - четырнадцать модулей с объявленным слоем`() {
        val все = модули()
        assertTrue(все.size == 14, "модулей должно быть 14, найдено ${все.size}: ${все.map { it.имя }}")
        все.forEach { м ->
            assertTrue(м.слой in порядок, "модуль ${м.имя}: слой «${м.слой}» вне перечня $порядок")
        }
    }

    @Test
    fun `зависимости идут только вниз по слоям`() {
        val все = модули().associateBy { it.имя }
        val нарушения = mutableListOf<String>()
        все.values.forEach { м ->
            // сквозной модуль (ai) по построению смотрит в доменные — это его роль
            if (м.слой == "сквозной") return@forEach
            м.зависимости.forEach { имя ->
                val цель = все[имя] ?: error("модуль ${м.имя} зависит от несуществующего $имя")
                if (порядок.indexOf(цель.слой) > порядок.indexOf(м.слой)) {
                    нарушения += "${м.имя} (${м.слой}) → ${цель.имя} (${цель.слой})"
                }
            }
        }
        assertTrue(
            нарушения.isEmpty(),
            "зависимость вверх по слоям запрещена (ТЗ-BACKEND §3): $нарушения",
        )
    }

    @Test
    fun `публичный контракт лежит в api, реализация - в internal`() {
        val нарушения = mutableListOf<String>()
        модули().forEach { м ->
            val исходники = корень.resolve("${м.имя}/src/main/kotlin/orbita/${м.имя}")
            assertTrue(исходники.resolve("api").isDirectory, "у модуля ${м.имя} нет пакета api")
            assertTrue(исходники.resolve("internal").isDirectory, "у модуля ${м.имя} нет пакета internal")
            // чужой internal не импортируется: модуль виден только через api
            исходники.walkTopDown().filter { it.extension == "kt" }.forEach { файл ->
                Regex("""^import orbita\.([a-z]+)\.internal""", RegexOption.MULTILINE)
                    .findAll(файл.readText())
                    .filter { it.groupValues[1] != м.имя }
                    .forEach { нарушения += "${файл.name}: импорт чужого internal — ${it.value}" }
            }
        }
        assertTrue(нарушения.isEmpty(), "чужой internal невидим (ТЗ-BACKEND §2.1): $нарушения")
    }
}
