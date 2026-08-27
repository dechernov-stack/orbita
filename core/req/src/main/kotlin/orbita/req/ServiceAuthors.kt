// Единый список служебности на систему (круг 2 портфеля §1.1): им пользуются
// якорь помет и активность портфеля — двух списков не бывает. «system» —
// записи до починки канала (имена восстановимы картой авторов); вперёд
// служебное пишется честной учёткой ci-runner. Исторические миграции писали
// created_by описательной строкой «миграция V0NN: …» — тоже служебность.
package orbita.req

object ServiceAuthors {
    val all = setOf("system", "ci-runner")
    const val MIGRATION_PREFIX = "миграция "
    fun isService(author: String): Boolean =
        author in all || author.startsWith(MIGRATION_PREFIX)
}
