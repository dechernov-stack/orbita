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
        val промпт = prepare(project, material, intent)
        // Урожай в сотню фактов не помещается в бюджет короткого ответа:
        // разбор просит свой потолок, а не полагается на общий.
        val ответ = service.ask(project, KIND, промпт, maxTokens = БЮДЖЕТ_РАЗБОРА)
        return apply(project, material, intent, author, ответ, journaled = true)
    }

    /**
     * Промпт разбора — читается из базы на потоке запросов; дальше он
     * самодостаточен, и сетевой вызов можно делать где угодно (ADR-069).
     */
    fun prepare(project: String, material: String, intent: String): String {
        val блоки = intake.canon(project, material)
        require(блоки.isNotEmpty()) {
            "у материала «$material» нет текста: разбирать нечего. " +
                "Приложите текст документа — канон строится из него"
        }
        return промпт(project, material, intent, блоки.joinToString("\n") { "[${it.anchor}] ${it.text}" })
    }

    /** Применить ответ модели: журнал (если ещё не записан) и приём фактов — на потоке запросов. */
    fun apply(project: String, material: String, intent: String, author: String, answer: orbita.ai.api.Answer, journaled: Boolean = false, prompt: String? = null): FactIntake {
        if (!journaled && prompt != null && !answer.cached) service.record(project, KIND, prompt, answer)
        val итог = intake.putFacts(project, material, answer.text, author, intent)
        return itogСПометой(итог, answer.cached)
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
        val режим = режимПоТипу(project, тип, intent)

        return """
Ты разбираешь входной документ инженерного проекта на АТОМАРНЫЕ ФАКТЫ.

## Проект
Название: ${паспорт?.doc?.path("name")?.asText(project) ?: project}
Ограничения проекта (рамки, с которыми факты обязаны быть сверены):
$ограничения

## Документ
Наименование: «$имя»${if (тип.isNotBlank()) ", тип: $тип" else ""}
Задание инженера: «$intent»

$режим

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

## План действий (Д2в)
После фактов предложи ПЛАН: что завести в проекте из этих фактов. Каждое
действие несёт содержимое будущей записи — человек увидит его ДО нажатия
и снимет ненужное. Виды целей и их поля:
  · intent (сцена 2) — замысел: for_whom · what · where · horizon (ОДНО действие)
  · stakeholder (сцена 3) — сторона миссии: name · role · influence
    (роль из: customer · operator · regulator · supplier · partner · consumer ·
    established; influence по формулировке источника: «якорный заказчик»,
    регулятор → decides; оператор, партнёр, «потенциальный оператор» →
    influences; потребитель → informed; сила power 1–5 — предложение)
  · need (сцена 3) — потребность: statement · owner (имя стороны)
    ПРАВИЛО НУЖД (ПМИ-5, поставка 12.09): интерес стороны из колонки
    «роль/интерес» или из описания стороны — ЭТО НУЖДА с меткой [П], owner —
    эта сторона; общая нужда раздела «нужды» (например §2.1, метка [И])
    привязывается к сторонам ПО СМЫСЛУ — по одному действию need на каждую
    сторону, которой она принадлежит (одна формулировка, разные owner);
    у каждой заведённой стороны должна быть хотя бы одна нужда — сторона без
    нужды означает, что её интерес не превращён в нужду. Пример: сторона
    «ГКРЧ, Роскомнадзор» с интересом «частотные присвоения до запуска» →
    need «Частотные присвоения и координация до запуска; изменение частот по
    решению регулятора» [П], owner ГКРЧ; общая нужда «Суверенитет и
    безопасность данных» [И] → need у субъектов КИИ, у ТЭК и у Минцифры/ФСБ.
  · goal (сцена 4) — цель: statement · metric · year
  · constraint (сцена 5) — ограничение: text · category
    (normative_basis — обозначение НПА, если ограничение нормативное)
  · service (сцена 6) — сервис: name · classes (A′/B′/C′)
  · normative_document (полка) — норматив: designation · title · edition ·
    edition_date · valid_until
Ссылка на факты — НОМЕРАМИ в массиве facts (с нуля).

## Формат ответа — ТОЛЬКО JSON, без пояснений вокруг
{
  "topics": [{"label": "короткое имя предмета фактов"}],
  "actions": [
    {
      "kind": "create_entity",
      "target_kind": "stakeholder",
      "scene": "3",
      "title": "завести сторону миссии «Минтранс России»",
      "preview": "появится сторона «Минтранс России», роль «заказчик»",
      "payload": {"name": "Минтранс России", "role": "customer"},
      "facts": [0, 4]
    }
  ],
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
      "conflict": "код ограничения, если противоречит (иначе поле опустить)",
      "param_key": "ключ анкеты узла — только для даташита (иначе опустить)",
      "entity_class": "requirement|function|service|composition_node|stakeholder|constraint|milestone|normative_ref — только для ТЗ (иначе опустить)",
      "limit": {"key": "…", "op": "le|ge|lt|gt|eq", "value": 0, "unit": "…"}
    }
  ],
  "assessment": {
    "lines": [{"fact": 0, "requirement": "п. ТЗ", "needs": ["ND-0001"], "verdict": "covers|partial|none", "note": "почему так: чего не хватает до нужды либо почему нужды нет"}],
    "needs": [{"need": "ND-0001", "verdict": "covered|partial|uncovered", "gap": "чего в ТЗ нет для этой нужды (пусто у покрытой)"}]
  }
}
(поля `limit` и `assessment` — только в режимах норматива и ТЗ, иначе опустить)

## Текст документа с якорями
$выжимка
""".trimIndent()
    }

    /**
     * Режим по типу входного (ДОКУМЕНТЫ-1-ВХОДНЫЕ §3): ТЗ — полный разбор и
     * ОЦЕНКА против нужд проекта; даташит — параметры в поля АНКЕТЫ узла;
     * норматив — пункты обязательствами с порогом ПОЛЕМ. Записка и прочее —
     * общий режим.
     */
    private fun режимПоТипу(project: String, тип: String, intent: String): String = when (тип) {
        "tor" -> {
            val нужды = store.list(Area.Project(project), "need")
                .joinToString("\n") { "  · ${it.code}: ${it.doc.path("statement").asText("")}" }
                .ifBlank { "  (нужд в проекте пока нет — оценка невозможна, верни assessment с пустыми lines)" }
            """
## Режим: техническое задание заказчика (полный разбор + оценка против нужд)
ПРАВИЛО АТОМИЗАЦИИ (ШИП-G-ПРИНЯТ): каждое требование, каждая функция и
каждый сервис (услуга) ТЗ — ОТДЕЛЬНЫЙ факт; ничего не объединяй: «услуги
связи, телеметрии и навигации (PNT)» — три факта, не один. Так же отдельными
фактами — каждая сторона, каждый узел состава, каждое ограничение, каждая веха
и каждый нормативный акт, на который ТЗ ссылается. У каждого факта ТЗ —
поле `entity_class` из: requirement · function · service · composition_node ·
stakeholder · constraint · milestone · normative_ref.
Требование ТЗ — факт вида obligation: subject — «ТЗ п. N», predicate —
формулировка требования, value — «требование». Функция — capability, сервис —
capability с value «услуга», узел состава — framing с value «узел», веха —
event с датой, норматив — framing с обозначением акта.
Действия плана — по классу факта: requirement и function → create_entity
requirement (сцена 8): statement · level = "project" · category
(functional|performance|interface|operational|constraint) · rationale = пункт
ТЗ; service → create_entity service (сцена 6): name · classes; composition_node
→ create_entity component (сцена 7): name · kind (segment|element|subsystem) ·
level (1 сегмент, 2 элемент, 3 подсистема) · parent (имя родителя); stakeholder →
create_entity stakeholder (сцена 3): name · role; constraint → create_entity
constraint (сцена 5): text · category; normative_ref → create_entity
normative_document (полка). Вехи (milestone) — факты без действия: даты точек
задаёт план фазы.
ОЦЕНКА — в две стороны. От требования: для КАЖДОГО факта класса requirement,
function и service назови, какие нужды проекта он покрывает (коды из списка), вердикт
covers|partial|none и `note` — почему: partial — чего не хватает до нужды
(нет спецификации: числа, условия, способа проверки; число названо без
обоснования нуждой); none — требование ни к одной нужде не ведёт (новая
услуга или функция, которой заказчик не просил, либо величина облика —
число КА, орбита, диапазон — заданная без нужды: назови её вместе с числом
как в ТЗ). От нужды:
для КАЖДОЙ нужды проекта строка в `needs` — вердикт covered|partial|
uncovered и `gap` словами: чего в ТЗ нет, чтобы нужду считать закрытой
(нет требования на регион/условия, нет спецификации гарантии, нет
показателя). Дыра называется предметно — «нет требования на широты выше
70°», а не «покрыто частично». Нужды проекта:
$нужды
Верни блок "assessment" с lines по всем требованиям и needs по всем нуждам.""".trimIndent()
        }
        "datasheet" -> {
            val анкета = intake.questionnaireKeys(project, intent)
                .joinToString("\n") { (ключ, ед) -> "  · $ключ (${ед.ifBlank { "—" }})" }
                .ifBlank { "  (анкета узла не найдена — параметры без param_key)" }
            """
## Режим: даташит изделия (параметрический разбор в анкету узла)
Задание: «$intent». Каждый параметр изделия — факт вида quantity с единицей;
если параметр — поле анкеты узла ниже, поставь ему `param_key` (ключ из
списка, единица — как в анкете, пересчитай при нужде и скажи об этом в
predicate). Параметры вне анкеты — факты без param_key. Условия среды,
стыки, комплект поставки, ограничения поставщика (санкции, сроки) — факты
своих видов. Анкета узла (ключ · единица):
$анкета
Действия плана по параметрам НЕ предлагай — их соберёт система по анкете и
рамкам проекта; предложи только стейкхолдера-поставщика и риски, если они
видны из документа.""".trimIndent()
        }
        "normative" -> """
## Режим: нормативный акт (пункты — обязательствами)
Каждый пункт-обязательство — факт вида obligation: subject — кто обязан,
predicate — что обязан, value — «обязанность» либо величина порога с
единицей. Порог нормы — ПОЛЕМ `limit {key, op, value, unit}` у факта, не
числом в тексте (например «увод не более 5 лет» → limit{active_lifetime,
le, 5, год}). Предложи ОДНО действие create_entity с target_kind
normative_document (полка): designation · title · edition · edition_date ·
valid_until · clauses[{clause, text, who, limit?}] — пункты из фактов.""".trimIndent()
        else -> ""
    }

    companion object {
        const val KIND: String = "intake_atomize"

        /** Потолок ответа разбора: сто фактов с якорями — это десятки тысяч токенов. */
        const val БЮДЖЕТ_РАЗБОРА: Int = 64_000
    }
}
