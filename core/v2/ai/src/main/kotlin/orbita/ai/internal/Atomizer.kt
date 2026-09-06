// Д2б — атомизация: канон документа → факты с якорями (главный вызов).
//
// Правила честности §6 приходят В ПРОМПТ, а не остаются пожеланием: факт
// без якоря не существует; величина без единицы — не факт; внешнее — [В] с
// датой, допущение — [П]; конфликт показывается обоими фактами, модель не
// выбирает. Контекст проекта (ограничения Р, класс миссии) идёт туда же —
// чтобы «80–200 кг» столкнулось с Р2 на входе, а не на приёмке.
//
// Ворота ответа — на нашей стороне: `Intake.putFacts` не принимает факт без
// якоря из канона. Модель может ошибиться; система — нет.
package orbita.ai.internal

import com.fasterxml.jackson.databind.ObjectMapper
import orbita.ai.api.AiService
import orbita.kernel.api.Area
import orbita.kernel.api.EntityStore
import orbita.knowledge.api.FactIntake
import orbita.knowledge.api.Intake

class Atomizer(
    private val store: EntityStore,
    private val intake: Intake,
    private val service: AiService,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    /** Разбор материала живым вызовом. Повтор той же версии — из журнала. */
    fun atomize(project: String, material: String, intent: String, author: String): FactIntake {
        val блоки = intake.canon(project, material)
        require(блоки.isNotEmpty()) {
            "у материала «$material» нет текста: разбирать нечего. " +
                "Приложите текст документа — канон строится из него"
        }
        val промпт = промпт(project, material, intent, блоки.joinToString("\n") { "[${it.anchor}] ${it.text}" })
        val ответ = service.ask(project, KIND, промпт)
        val итог = intake.putFacts(project, material, ответ.text, author)
        return itogСПометой(итог, ответ.cached)
    }

    private fun itogСПометой(итог: FactIntake, изЖурнала: Boolean): FactIntake =
        if (!изЖурнала) итог
        else итог.copy(note = итог.note + " · ответ взят из журнала: та же версия документа уже разбиралась")

    /**
     * Промпт разбора. Контекст проекта — ограничения и класс — идёт в него
     * целиком: без них модель не столкнёт величину документа с рамкой
     * проекта и отдаст «80–200 кг» как факт без вопроса.
     */
    private fun промпт(project: String, material: String, intent: String, выжимка: String): String {
        val область = Area.Project(project)
        val паспорт = store.list(область, "project").firstOrNull()
        val ограничения = store.list(область, "constraint")
            .joinToString("\n") { "  · ${it.code}: ${it.doc.path("text").asText("")}" }
            .ifBlank { "  (ограничений в проекте пока нет)" }
        val карточка = store.byCode(область, material)
        val имя = карточка?.doc?.path("name")?.asText(material) ?: material
        val тип = карточка?.doc?.path("kind")?.asText("") ?: ""

        return """
Ты разбираешь входной документ инженерного проекта на АТОМАРНЫЕ ФАКТЫ.

## Проект
Название: ${паспорт?.doc?.path("name")?.asText(project) ?: project}
Ограничения проекта (рамки, с которыми факты обязаны быть сверены):
$ограничения

## Документ
Наименование: «$имя»${if (тип.isNotBlank()) ", тип: $тип" else ""}
Задание инженера: «$intent»

## Правила честности — нарушение делает ответ непригодным
1. Факт без ЯКОРЯ не существует. Якорь — это метка блока в квадратных
   скобках из текста ниже (например s3#2). Придумывать якоря нельзя:
   принимаются только те, что есть в тексте.
2. Величина без ЕДИНИЦЫ измерения — не факт. У вида `quantity` единица
   обязательна.
3. Метка достоверности: И — утверждение самого документа; В — внешний
   источник, на который документ ссылается; П — предположение или цель.
4. Конфликт величин между источниками — ДВА факта, оба с якорями. Не
   выбирай, какой верен.
5. Ничего не обобщай и не додумывай: чего в тексте нет, того нет.
6. Если величина документа противоречит ограничению проекта — факт всё
   равно записывается, а в `conflict` называется код ограничения.

## Виды фактов
quantity — измеримая величина · capability — что система умеет ·
obligation — обязанность из нормы или договора · framing — рамка, границы,
принцип · event — событие со сроком · relation — кто с кем связан ·
assessment — оценка, суждение · assumption — допущение.

## Формат ответа — ТОЛЬКО JSON, без пояснений вокруг
{
  "topics": [{"label": "короткое имя предмета фактов"}],
  "facts": [
    {
      "kind": "quantity|capability|obligation|framing|event|relation|assessment|assumption",
      "topic": "label темы из topics",
      "subject": "о чём факт",
      "predicate": "что утверждается",
      "value": "значение как в документе",
      "unit": "единица (обязательна для quantity)",
      "source": {"anchor": "якорь из текста"},
      "source_mark": "И|В|П",
      "confidence": 0.0,
      "conflict": "код ограничения, если противоречит (иначе поле опустить)"
    }
  ]
}

## Текст документа с якорями
$выжимка
""".trimIndent()
    }

    companion object {
        const val KIND: String = "intake_atomize"
    }
}
