// Репозиторий базирований: коммит и аннотированный тег на каждую базовую
// линию документа.
//
// Зачем вообще git, если снимок и так лежит сущностью и неизменяем: снимок
// в базе проверяет ТОТ ЖЕ, кто его писал. Тег в отдельном репозитории —
// отметка, которую можно прочитать чем угодно и унести с собой; на точке
// спрашивают именно её («покажите, что было зафиксировано на внутреннем
// обзоре»), и ответ не должен зависеть от того, жива ли наша база.
//
// JGit, а не внешний git: в образе изделия нет ни git, ни apt — и это
// осознанное решение (ops/api.Dockerfile). Лицензия EDL 1.0 (BSD-3).
package orbita.documents.internal

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.nio.file.Files

/**
 * Книга базирований: один репозиторий на проект.
 *
 * Репозиторий проекта отделён от репозитория продукта намеренно: документы
 * проектов — содержание владельца, и в публичный репозиторий изделия они
 * не попадают ни при каких обстоятельствах.
 */
internal class BaselineBook(private val корень: File) {

    /**
     * Записать текст документа и поставить аннотированный тег.
     *
     * Ошибка репозитория НЕ роняет базирование: снимок в базе уже
     * состоялся и он главный. Но и молчать нельзя — вызывающий получает
     * причину словами и показывает её человеку.
     */
    fun отметить(
        project: String,
        document: String,
        name: String,
        text: String,
        author: String,
    ): Отметка = runCatching {
        val репозиторий = корень.resolve(безопасно(project))
        val git = открыть(репозиторий)
        git.use {
            val файл = репозиторий.resolve("${безопасно(document)}.md")
            Files.createDirectories(файл.parentFile.toPath())
            файл.writeText(text, Charsets.UTF_8)

            val тег = имяТега(name)
            if (it.tagList().call().any { ссылка -> ссылка.name == "refs/tags/$тег" }) {
                return Отметка("", "", "тег «$тег» уже стоит: базовая линия неизменяема")
            }

            it.add().addFilepattern(".").call()
            val кто = PersonIdent(author.ifBlank { "Орбита" }, "orbita@local")
            val коммит = it.commit()
                .setAuthor(кто).setCommitter(кто)
                .setMessage("базирование «$name»: $document")
                .call()
            it.tag()
                .setName(тег).setMessage("базовая линия «$name» документа $document")
                .setAnnotated(true).setTagger(кто)
                .call()
            Отметка(тег, коммит.name, "")
        }
    }.getOrElse { беда ->
        Отметка("", "", "репозиторий базирований недоступен: ${беда.message ?: беда::class.simpleName}")
    }

    /** Текст документа на теге; null — тега нет либо репозиторий недоступен. */
    fun текстНаТеге(project: String, document: String, name: String): String? = runCatching {
        val репозиторий = корень.resolve(безопасно(project))
        if (!репозиторий.resolve(".git").isDirectory) return null
        Git.open(репозиторий).use { git ->
            val тег = имяТега(name)
            val ссылка = git.repository.findRef("refs/tags/$тег") ?: return null
            val объект = git.repository.refDatabase.peel(ссылка).peeledObjectId ?: ссылка.objectId
            org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                val дерево = walk.parseCommit(объект).tree
                org.eclipse.jgit.treewalk.TreeWalk.forPath(
                    git.repository, "${безопасно(document)}.md", дерево,
                )?.use { обход ->
                    String(git.repository.open(обход.getObjectId(0)).bytes, Charsets.UTF_8)
                }
            }
        }
    }.getOrNull()

    private fun открыть(репозиторий: File): Git {
        Files.createDirectories(репозиторий.toPath())
        return if (репозиторий.resolve(".git").isDirectory) Git.open(репозиторий)
        else Git.init().setDirectory(репозиторий).call()
    }

    /**
     * Имя тега из человеческого имени линии. Пробелы и косые в ссылку git
     * не годятся, но узнаваемость сохраняется: «внутренний обзор» →
     * `базирование/внутренний-обзор`.
     */
    private fun имяТега(name: String): String =
        "базирование/" + name.trim().lowercase()
            .replace(Regex("[\\s/\\\\~^:?*\\[\\]]+"), "-")
            .trim('-')
            .ifBlank { "без-имени" }

    /** Имя каталога и файла: код проекта приходит снаружи, и путь не должен уводить из корня. */
    private fun безопасно(значение: String): String =
        значение.replace(Regex("[^A-Za-zА-Яа-я0-9_.-]"), "_").trim('.').ifBlank { "без-имени" }

    /** Итог отметки: тег и коммит либо причина, почему их нет. */
    data class Отметка(val tag: String, val commit: String, val note: String)
}
