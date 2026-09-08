// Фабрика готовности: наружу отдаётся оценщик как порт процесса.
//
// Реализация живёт в internal и невидима вызывающим — это правило держит
// архитектурный тест, а не договорённость между модулями.
package orbita.readiness.api

import orbita.kernel.api.EntityStore
import orbita.kernel.api.LinkRegistry
import orbita.process.api.GateEvaluator
import orbita.readiness.internal.DomainGateEvaluator

object ReadinessFactory {
    fun gateEvaluator(
        store: EntityStore,
        links: LinkRegistry,
        scenesDone: (String) -> Set<String>,
        gatesPassed: (String) -> Set<String>,
        /** Условия, которые знают модули выше слоем; собирает их граница. */
        extra: ExtraChecks? = null,
        /** Русское имя вида для причин отказа: реестр схем живёт выше, readiness его не знает. */
        kindTitle: (String) -> String = { it },
    ): GateEvaluator = DomainGateEvaluator(store, links, scenesDone, gatesPassed, extra, kindTitle)
}
