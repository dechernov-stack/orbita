// SpineTest — хребет процесса (ТЗ §7, волна 0).
//
// Проверяет ровно то, ради чего берётся движок: он встроен, поднимается на
// своей БД и ДЕРЖИТ ВОРОТА. Пока условие сцены не выполнено, следующая
// задача не появляется; выполнилось — появляется сама, без нашего кода.
//
// Домена в движке нет: условие приходит переменной, которую в волне 1
// будет считать внешний оценщик готовности (порт GateEvaluator).
package orbita.process

import org.flowable.cmmn.engine.CmmnEngine
import org.flowable.cmmn.engine.CmmnEngineConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpineTest {

    private val движок: CmmnEngine = run {
        // setJdbcUrl отдаёт базовый тип конфигурации, поэтому цепочку не строим:
        // настраиваем объект, потом собираем движок.
        val конфигурация = CmmnEngineConfiguration.createStandaloneInMemCmmnEngineConfiguration()
        конфигурация.jdbcUrl = "jdbc:h2:mem:spine;DB_CLOSE_DELAY=-1"
        конфигурация.buildCmmnEngine()
    }

    @AfterTest
    fun закрыть() = движок.close()

    @Test
    fun `движок встроен и разворачивает модель фазы`() {
        val развёртывание = движок.cmmnRepositoryService
            .createDeployment()
            .addClasspathResource("spine.cmmn.xml")
            .deploy()
        assertTrue(развёртывание.id != null, "модель фазы не развернулась")

        val определение = движок.cmmnRepositoryService
            .createCaseDefinitionQuery()
            .caseDefinitionKey("spine")
            .singleResult()
        assertEquals("Хребет процесса", определение.name)
    }

    @Test
    fun `ворота держат - следующая задача не появляется, пока условие не выполнено`() {
        движок.cmmnRepositoryService.createDeployment()
            .addClasspathResource("spine.cmmn.xml").deploy()

        val дело = движок.cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("spine")
            .variable("sceneDone", false)
            .start()

        val доВорот = движок.cmmnTaskService.createTaskQuery()
            .caseInstanceId(дело.id).list().map { it.name }
        assertTrue(
            доВорот == listOf("Сцена: замысел"),
            "до выполнения условия видна только сцена, а не точка: $доВорот",
        )

        // условие выполнилось — движок сам открывает следующую задачу
        val сцена = движок.cmmnTaskService.createTaskQuery()
            .caseInstanceId(дело.id).singleResult()
        движок.cmmnRuntimeService.setVariable(дело.id, "sceneDone", true)
        движок.cmmnTaskService.complete(сцена.id)

        val послеВорот = движок.cmmnTaskService.createTaskQuery()
            .caseInstanceId(дело.id).list().map { it.name }
        assertTrue(
            "Точка: MCR" in послеВорот,
            "после выполнения условия точка обязана открыться сама: $послеВорот",
        )
    }
}
