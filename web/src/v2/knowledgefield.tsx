// Экран «Поле знаний» (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ §3; замечания прохода 08.09).
//
// Поле знаний — не список фактов, а рабочая поверхность: ТЕМЫ (о чём
// накопились утверждения), ДИСПОЗИЦИИ (что с ними решено) и ИСТОЧНИКИ
// (откуда). И оно же — дом для ВХОДА: источник загружается здесь, разбор
// кладёт факты и темы на глазах, план действий ложится на акцепт тут же.
//
// Три правила прохода 08.09:
//   1. Обоснование — только там, где оно несёт смысл: отклонение, спор,
//      смена уже принятого. Согласие и «рассмотрен» — одним кликом.
//      Обоснование не должно быть налогом на согласие.
//   2. Загрузка во вкладке: текст, файл или ссылка + задание → разбор.
//   3. Ручные темы и факты — полноправные; ручное видно отдельно.
//
// Поле знаний v2 (ЗНАНИЯ-V2, мера ПМИ-6 пп. 1.2–1.9) живёт ЗДЕСЬ ЖЕ и только
// там, где сервер его признал: вкладка «Постановка из поля», счётчик дрейфа
// «поле изменилось: N», колонка ранга доверия, отбор «из исследований» и
// ранг вместо типа в форме источника показываются, когда адреса синтеза
// отвечают делом; на проекте прохода ПМИ-5 те же адреса отвечают отказом 409,
// и экран остаётся прежним целиком.
//
// Ни одна цифра здесь не считается: счётчики групп дифа, дрейф поля и доля
// знаний приходят с сервера готовыми. Ни одно слияние, принятие и уточнение
// не происходит без нажатия человека — служба только предлагает.
import { Fragment as Фрагмент, useCallback, useEffect, useState, type KeyboardEvent } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { ResearchPanel, отказСловами } from './research'
import {
  ServerRefusal, api, РЕЖИМЫ_ПРИЁМА, РОЛИ_ДОКУМЕНТА,
  type Authority, type DocumentRole, type FactRow, type FactSourceView, type FieldDrift,
  type MaterialRow, type GapBrief,
  type FormationOntology, type FormationProposal, type ReconcileAction,
  type ReconcileFinding, type ReconcileItem, type ReconcileRun, type ResearchTask,
  type SynthesisAccepted, type SynthesisDiff, type SynthesisDiffView, type SynthesisRun,
  type TaskPlan,
  type DistributionLink,
  type DistributionRun,
} from './api'
import { ДЕЙСТВИЕ_СВЕРКИ } from './words'
import { ВзятьИзПроекта, Досье, КартаПробелов, Партия } from './dossier'

/**
 * Диспозиции факта — словами истины схем (`fact.disposition`, ОНТОЛОГИЯ-АУДИТ
 * часть 2): у факта — учтён · принят · отклонён · оспорен · устарел; у
 * предложения — принять · отклонить · отложить; у сущности — базировать ·
 * отменить. Одно слово на уровень: «отложен» факту не принадлежит.
 */
const РЕШЕНИЕ: Record<string, string> = {
  free: 'не рассмотрен',
  noted: 'учтён',
  assumed: 'допущение',
  adopted: 'принят',
  rejected: 'отклонён',
  contested: 'оспорен',
  superseded: 'устарел',
}

const МЕТКА: Record<string, string> = {
  И: 'наш документ',
  В: 'внешний источник',
  П: 'допущение',
}

const ВИДЫ_ФАКТА: [string, string][] = [
  ['framing', 'рамка'],
  ['quantity', 'величина'],
  ['capability', 'способность'],
  ['obligation', 'обязательство'],
  ['event', 'событие'],
  ['assessment', 'оценка'],
  ['relation', 'связь'],
  ['assumption', 'допущение'],
]

/**
 * Ранг доверия источника словами (истина схем `material.rank`). Ранг
 * латиницей на экран не выходит: «обязательный · экспертный · справочный ·
 * сомнительный» — это и есть его имя для человека.
 */
const РАНГ: Record<string, string> = {
  mandatory: 'обязательный',
  expert: 'экспертный',
  reference: 'справочный',
  doubtful: 'сомнительный',
}

/** Ранги, которые называет человек у ИСТОЧНИКА: экспертный — у руки, не у файла. */
const РАНГИ_ИСТОЧНИКА: Authority[] = ['mandatory', 'reference', 'doubtful']

/** Понятия онтологии формирования по-русски: их восемь, девятого не бывает. */
const ПОНЯТИЕ: Record<string, string> = {
  stakeholder: 'сторона',
  need: 'нужда',
  goal: 'цель',
  service: 'сервис',
  constraint: 'ограничение',
  assumption: 'допущение',
  milestone: 'веха',
  normative_document: 'нормативный документ',
  opportunity: 'применимость',
}

/** Те же понятия во множественном: ими написан предпросмотр пакета. */
const ПОНЯТИЕ_МН: Record<string, string> = {
  stakeholder: 'сторон',
  need: 'нужд',
  goal: 'целей',
  service: 'сервисов',
  constraint: 'ограничений',
  assumption: 'допущений',
  milestone: 'вех',
  normative_document: 'нормативных документов',
  opportunity: 'применимостей',
}

/** Поля понятий словами: отличие называется полем, а не «похоже». */
const ПОЛЕ: Record<string, string> = {
  name: 'название',
  role: 'роль',
  interest: 'интересы',
  scale: 'масштаб',
  influence: 'влияние',
  statement: 'постановка',
  stakeholder: 'сторона',
  qos_class: 'класс обслуживания',
  measure: 'показатель',
  year: 'год',
  needs: 'нужды',
  target_measure: 'целевой показатель',
  code: 'код',
  type: 'род',
  bound: 'граница',
  owner: 'владелец',
  confirm_by: 'точка подтверждения',
  date: 'дата',
  fleet: 'состав группировки',
  designation: 'обозначение',
  edition: 'редакция',
  clauses: 'пункты',
}

/** Четыре группы дифа: вердикт словом и чем группа отличается от соседней. */
const ГРУППЫ: [keyof SynthesisDiff, string, string][] = [
  ['new', 'новое', 'в поле есть основание, а в постановке такого ещё нет'],
  ['augment', 'дополнить', 'принятое остаётся как есть — поле добавляет к нему поле-другое'],
  ['contradict', 'противоречит',
    'значения разошлись: показаны оба с рангами, победителя служба не выбирает'],
  ['confirm', 'подтверждено', 'второй независимый источник говорит то же самое'],
]

/** Действия сверки словами — одной таблицей на весь клиент (words.ts). */
const ДЕЙСТВИЕ: Record<string, string> = ДЕЙСТВИЕ_СВЕРКИ

/**
 * Куда ложится ручной ввод эксперта — поля ИСТИНЫ онтологии формирования, а
 * не выдуманные. `изПредмета` — понятие зовётся именем (сторона, сервис,
 * веха, норматив), и предмет ввода и есть это имя; `ссылка` — обязательная
 * связь понятия, которую предмет закрывает (нужда носит сторону).
 */
const ПОЛЯ_ПОНЯТИЯ: Record<string, {
  текст: string; изПредмета?: boolean; величина?: string; ссылка?: string
  /** Обязательное перечисление вида: род вехи — поле `kind` схемы вида 286. */
  род?: { поле: string; подпись: string; значения: [string, string][] }
}> = {
  stakeholder: { текст: 'name', изПредмета: true },
  need: { текст: 'statement', ссылка: 'stakeholder' },
  goal: { текст: 'statement', величина: 'measure' },
  service: { текст: 'name', изПредмета: true, величина: 'target_measure' },
  constraint: { текст: 'statement', величина: 'bound' },
  assumption: { текст: 'statement' },
  milestone: {
    текст: 'name', изПредмета: true,
    // Перечисление — из истины схем (вид 286, поле kind). Веха технологии
    // сюда не идёт: она остаётся точкой.
    род: {
      поле: 'kind', подпись: 'род вехи',
      значения: [
        ['program_stage', 'программный этап'],
        ['external_event', 'внешнее событие'],
        ['contract', 'контракт'],
      ],
    },
  },
  normative_document: { текст: 'designation', изПредмета: true },
}

/**
 * Формулировка кандидата из ручного ввода. У понятия-имени (сторона, сервис,
 * веха, норматив) это предмет; у нужды предмет — СТОРОНА, и в постановку он
 * не вклеивается; иначе предмет и утверждение складываются в одну фразу.
 */
function формулировкаКандидата(понятие: string, предмет: string, утверждение: string): string {
  const поля = ПОЛЯ_ПОНЯТИЯ[понятие]
  if (поля?.изПредмета) return предмет.trim() || утверждение.trim()
  if (поля?.ссылка) return утверждение.trim()
  return [предмет.trim(), утверждение.trim()].filter((ч) => ч !== '').join(': ')
}

/** Содержимое кандидата: только поля онтологии — выдуманных полей не бывает. */
function содержимоеКандидата(
  понятие: string, предмет: string, утверждение: string, значение: string, единица: string,
  род = '',
): Record<string, unknown> {
  const поля = ПОЛЯ_ПОНЯТИЯ[понятие] ?? { текст: 'statement' }
  const содержимое: Record<string, unknown> = {
    [поля.текст]: формулировкаКандидата(понятие, предмет, утверждение),
  }
  if (поля.род && род.trim() !== '') содержимое[поля.род.поле] = род.trim()
  if (поля.ссылка && предмет.trim() !== '') содержимое[поля.ссылка] = предмет.trim()
  // Величина без единицы — не факт: пара идёт целиком либо не идёт вовсе.
  if (поля.величина && значение.trim() !== '' && единица.trim() !== '') {
    содержимое[поля.величина] = { op: '=', value: значение.trim(), unit: единица.trim() }
  }
  return содержимое
}

/** Ранг словами либо честный прочерк: выдуманный ранг хуже отсутствующего. */
function рангСловами(ранг?: Authority | null): string {
  return ранг ? РАНГ[ранг] ?? ранг : '—'
}

/** Отказ словами сервера плюс имя незакрытой связи — человеку есть что выбрать. */
function отказПодробно(e: unknown): string {
  const нет = e instanceof ServerRefusal && e.body.missing ? ` · не закрыто: ${e.body.missing}` : ''
  return `${отказСловами(e)}${нет}`
}

/** Источник кандидата: документ с якорем ЛИБО эксперт с учёткой, ролью и датой. */
function источникСловами(и: FactSourceView): string {
  return 'material' in и ? `${и.material} · ${и.anchor}` : `эксперт: ${и.account}, ${и.role}, ${и.at}`
}

type Фильтр = 'все' | 'свободные' | 'допущения' | 'спорные' | 'ручные' | 'из исследований'

/**
 * Нужен ли повод к решению. Правило то же, что на сервере: сервер
 * откажет и без нас, но спрашивать текст там, где он не нужен, — налог.
 */
function нуженПовод(было: string, стало: string): boolean {
  if (стало === 'rejected') return true
  if (было === 'contested') return true
  return было !== 'free' && было !== стало
}

type Тема = { id: string; label: string; facts: number; resolved_to?: string | null }
type Покрытие = { total: number; from_facts: number; from_manual_facts: number; manual: number; share_percent: number }

export function KnowledgeField({ project, expert = false, ручной = false, onGoGlossary }: {
  /** Переход в словарь: кандидаты из приёма плана принимаются там. */
  onGoGlossary?: () => void
  project: string | null
  expert?: boolean
  /**
   * Открыть сразу ручной ввод: сюда приходят из §11 отчёта («Темы без
   * разрешения»), и дорога обязана привести К ФОРМЕ, а не к экрану, где
   * форму ещё надо найти (владелец 21.09).
   */
  ручной?: boolean
}) {
  const [факты, setФакты] = useState<FactRow[] | null>(null)
  const [темы, setТемы] = useState<Тема[]>([])
  const [покрытие, setПокрытие] = useState<Покрытие | null>(null)
  const [тема, setТема] = useState<string | null>(null)
  const [фильтр, setФильтр] = useState<Фильтр>('все')
  const [отказ, setОтказ] = useState<string | null>(null)
  // Хуки — до любых возвратов: порядок хуков стережёт CI.
  const [решаем, setРешаем] = useState<{ факт: string; решение: string } | null>(null)
  const [причина, setПричина] = useState('')
  const [вход, setВход] = useState(false)
  const [рукой, setРукой] = useState(ручной)
  const [план, setПлан] = useState<TaskPlan | null>(null)
  const [выбраны, setВыбраны] = useState<number[]>([])
  // Что ворота приёма НЕ пропустили: сервер называет каждое непринятое
  // действие причиной, и экран обязан показать это, а не молча создать
  // меньше, чем человек отметил (остановка ПМИ-6).
  const [заметкиПриёма, setЗаметкиПриёма] = useState<string[]>([])
  /**
   * Факты сложены по предмету (КТ2: «огромные простыни — складывать по сути и
   * раскрывать»): группа — строка с числом фактов и нерешённых, раскрывается
   * кликом; по умолчанию свёрнуто, кроме одной-единственной группы.
   */
  const [раскрытые, setРаскрытые] = useState<Set<string>>(new Set())
  const [всеРаскрыты, setВсеРаскрыты] = useState(false)
  /** Факт, к которому перешли по ссылке «противоречит F-…»: строка подсвечена. */
  const [подсвечен, setПодсвечен] = useState<string | null>(null)
  const кФакту = (код: string) => {
    const ф = (факты ?? []).find((x) => x.code === код)
    if (ф) setРаскрытые((р) => new Set(р).add(предметФакта(ф)))
    setПодсвечен(код)
    window.setTimeout(() => document.getElementById(`v2-fact-${код}`)?.scrollIntoView({ block: 'center' }), 50)
  }
  const [занято, setЗанято] = useState(false)
  // Пакетный приём фактов: идёт ли он сейчас, сколько фактов уже прошло и чем
  // кончился. Итог показывается ЧИСЛАМИ и первой причиной отказа: пакет, что
  // молча принял меньше обещанного, — та же дыра, что остановка ПМИ-6.
  const [пакетИдёт, setПакетИдёт] = useState(false)
  const [пакетШаг, setПакетШаг] = useState(0)
  const [пакетФактов, setПакетФактов] = useState<
    { принято: number; отказано: number; пропущено: number; причина: string | null } | null>(null)
  // Допущение ставится с владельцем, точкой и способом проверки — иначе к
  // точке его никто не подтвердит (истина схем: assumption при assumed).
  const [допущение, setДопущение] = useState<{ факт: string; owner: string; confirm_by: string; validation: string; impact: string } | null>(null)
  const [адресТемы, setАдресТемы] = useState('')
  // Поле знаний v2 — состояния объявлены ДО ранних возвратов (порядок хуков
  // стережёт CI): вкладка, дрейф поля, онтология, задачи исследования и окно
  // подтверждения. Признак самого поля знаний v2 даёт сервер, а не догадка.
  const [вкладка, setВкладка] = useState<'поле' | 'постановка'>('поле')
  /** Итог чтения документа: экран говорит, что прочитано, на вкладке постановки. */
  const [прочитано, setПрочитано] = useState('')
  const [знанияV2, setЗнанияV2] = useState(false)
  const [дрейф, setДрейф] = useState<FieldDrift | null>(null)
  const [онтология, setОнтология] = useState<FormationOntology | null>(null)
  const [исследования, setИсследования] = useState<ResearchTask[]>([])
  const [слитьВ, setСлитьВ] = useState('')
  const [ask, спросить, закрытьВопрос] = useConfirm()

  const перечитать = useCallback(() => {
    if (!project) return
    api.facts(project).then((r) => setФакты(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.topics(project).then((r) => setТемы(r.items)).catch(() => setТемы([]))
    api.knowledgeCoverage(project).then(setПокрытие).catch(() => setПокрытие(null))
    // «Поле изменилось: N» считает сервер. Он же и отвечает, включено ли
    // поле знаний v2: на проекте прохода тот же адрес даёт отказ 409 — и
    // экран остаётся прежним, без вкладки, ранга и отбора исследований.
    api.synthesisPending(project)
      .then((д) => {
        setДрейф(д)
        setЗнанияV2(true)
        api.research(project).then((r) => setИсследования(r.items)).catch(() => setИсследования([]))
        api.formationOntology(project).then(setОнтология).catch(() => setОнтология(null))
      })
      .catch(() => {
        setДрейф(null)
        setЗнанияV2(false)
        setВкладка('поле')
        setИсследования([])
      })
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  }
  if (отказ) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <div className="v2-locked">{отказ}</div>
        <button type="button" className="v2-link" onClick={() => { setОтказ(null); перечитать() }}>ещё раз</button>
      </div>
    )
  }

  const все = факты ?? []
  const видно = все
    .filter((ф) => (тема ? ф.topic === тема : true))
    .filter((ф) => {
      if (фильтр === 'свободные') return (ф.disposition ?? 'free') === 'free'
      if (фильтр === 'допущения') return ф.mark === 'П' || ф.disposition === 'assumed'
      if (фильтр === 'спорные') return ф.disposition === 'contested'
      if (фильтр === 'ручные') return ф.manual === true
      // Результат внешнего контура живёт сомнительным, пока человек не
      // подтвердил источники: отбор показывает ровно его.
      if (фильтр === 'из исследований') return ф.rank === 'doubtful'
      return true
    })
  const ручных = все.filter((ф) => ф.manual).length
  const группы = группыФактов(видно)
  const раскрыта = (предмет: string) => всеРаскрыты || раскрытые.has(предмет) || группы.length === 1
  const переключить = (предмет: string) => setРаскрытые((р) => {
    const н = new Set(р); if (н.has(предмет)) н.delete(предмет); else н.add(предмет); return н
  })
  // Вкладка поля — она же весь экран там, где поля знаний v2 нет.
  const поле = вкладка === 'поле' || !знанияV2
  const имяТемы = (код: string) => темы.find((т) => т.id === код)?.label ?? код

  const решить = (ф: FactRow, решение: string) => {
    const было = ф.disposition ?? 'free'
    if (нуженПовод(было, решение)) {
      // Повод спрашивается СТРОКОЙ В ТАБЛИЦЕ, нормальным многострочным
      // полем — и только здесь. Нативных диалогов в продукте нет.
      setРешаем({ факт: ф.id, решение }); setПричина('')
      return
    }
    api.disposeFact(project, ф.id, решение, '', 'инженер')
      .then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))
  }

  // Пакет идёт ровно по тому, что человек ВИДИТ: тема и отбор уже сузили
  // список, за его края пакет не выходит. Решённые не трогаем — пакет
  // закрывает нерешённое, а не переписывает чужие решения.
  const свободные = видно.filter((ф) => (ф.disposition ?? 'free') === 'free')
  // Сомнительный ранг пакетом не принимается — тем же правилом, каким сервер
  // запирает пакетный приём плана разбора (`batch_refusal`): источник не
  // подтверждён. Расхождение экрана и сервера хуже молчания, поэтому правило
  // стоит и здесь; по одному факту решение в строке остаётся за человеком.
  const кПакету = свободные.filter((ф) => ф.rank !== 'doubtful')
  const сомнительных = свободные.length - кПакету.length

  /**
   * Принять все показанные факты — ПО ОДНОМУ вызову на факт, тем же маршрутом,
   * что и «принять» в строке: второй дороги приёма в продукте нет. Идём
   * подряд, а не разом: отказ по одному факту не отменяет уже принятого, и
   * человек видит, сколько прошло, а сколько отказало и почему.
   */
  const принятьПодряд = async (список: FactRow[]) => {
    setПакетИдёт(true)
    setПакетФактов(null)
    setПакетШаг(0)
    let принято = 0
    let отказано = 0
    let причина: string | null = null
    for (const ф of список) {
      try {
        await api.disposeFact(project, ф.id, 'adopted', '', 'инженер')
        принято += 1
        setПакетШаг(принято + отказано)
      } catch (e) {
        отказано += 1
        setПакетШаг(принято + отказано)
        if (причина === null) причина = `${ф.id} — ${отказПодробно(e)}`
      }
    }
    setПакетФактов({ принято, отказано, пропущено: сомнительных, причина })
    setПакетИдёт(false)
    перечитать()
  }

  const принятьВсеФакты = () => {
    if (пакетИдёт || кПакету.length === 0) return
    const список = кПакету
    спросить({
      question: `Принять все показанные факты: ${список.length}. Каждый станет основанием`
        + ' сущностей; уже решённые факты пакет не трогает'
        + (сомнительных > 0
          ? `; сомнительных пропустим: ${сомнительных} — их источник не подтверждён.`
          : '.'),
      ok: 'Принять все',
      onOk: () => { void принятьПодряд(список) },
    })
  }

  const записать = () => {
    if (!решаем || !причина.trim()) return
    api.disposeFact(project, решаем.факт, решаем.решение, причина.trim(), 'инженер')
      .then(() => { setРешаем(null); setПричина(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const записатьДопущение = () => {
    if (!допущение || !допущение.owner.trim() || !допущение.validation.trim()) return
    api.disposeFact(project, допущение.факт, 'assumed', 'принято допущением до подтверждения', 'инженер', {
      owner: допущение.owner.trim(), confirm_by: допущение.confirm_by,
      validation: допущение.validation.trim(), impact_if_wrong: допущение.impact.trim(),
    })
      .then(() => { setДопущение(null); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const разрешитьТему = () => {
    if (!тема || !адресТемы.trim()) return
    api.resolveTopic(project, тема, адресТемы.trim(), 'инженер')
      .then(() => { setАдресТемы(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  // Пакетный приём заперт там же, где его запирает сервер: план материала,
  // источник которого не подтверждён, не принимается ни целиком, ни частью.
  const пакетЗаперт = план?.batch_accept === false
  const принятьПлан = () => {
    if (!план || выбраны.length === 0 || пакетЗаперт) return
    setЗанято(true)
    setЗаметкиПриёма([])
    api.acceptPlan(project, план.task, выбраны, 'инженер')
      .then((итог) => { setПлан(null); setВыбраны([]); setЗаметкиПриёма(итог.notes ?? []); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  /**
   * Подсказка кнопки приёма: серая кнопка обязана назвать причину И путь
   * оживления (правило Ф-11). Причина запрета — серверная, слово в слово.
   */
  const подсказкаПриёма = план?.batch_refusal
    ? `${план.batch_refusal}. Источники подтверждаются в карточке исследования ниже`
    : выбраны.length === 0
      ? 'отметьте действия, которые принимаете: снятое остаётся рассмотренным'
      : 'выполнить выбранные действия: сущности получат нить к своим фактам'

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Поле знаний
        <span className="v2-cnt">
          {факты === null ? 'читаю…' : `${все.length} фактов · ${темы.length} тем`}
          {ручных > 0 && ` · руками ${ручных}`}
          {покрытие && ` · из источников ${покрытие.share_percent}% сущностей`}
          {покрытие && покрытие.from_manual_facts > 0 && ` · из ручных ${покрытие.from_manual_facts}`}
          {знанияV2 && дрейф !== null && дрейф.changed > 0 && (
            <span className="v2-warn"
              title="столько в поле поменялось после последнего синтеза — число считает сервер">
              {` · поле изменилось: ${дрейф.changed}`}
            </span>
          )}
        </span>
        <span className="v2-head__spacer" />
        <button type="button" className={вход ? 'v2-chip v2-chip--on' : 'v2-chip'}
          title="текст, файл или ссылка + задание → разбор → факты и темы ложатся сюда"
          onClick={() => { setВход(!вход); setРукой(false); setВкладка('поле') }}>
          Загрузить источник
        </button>
        <button type="button" className={рукой ? 'v2-chip v2-chip--on' : 'v2-chip'}
          title="завести тему или факт руками: метка [И], источник — инженер и дата"
          onClick={() => { setРукой(!рукой); setВход(false); setВкладка('поле') }}>
          Руками
        </button>
        <a className="v2-chip" href={api.knowledgeZipUrl(project ?? '')} target="_blank" rel="noreferrer"
          title="пакет знаний внешнему контуру: MD-файлы с отпечатком в шапке — факты принятые · допущенные · замеченные с якорями">
          Выгрузка знаний
        </a>
      </h3>

      {знанияV2 && (
        <div className="v2-tabs" role="tablist" aria-label="поле знаний и постановка из него">
          <button type="button" role="tab" className="v2-tab" aria-selected={поле}
            title="накопленное поле: факты с рангом доверия, темы и решения человека"
            onClick={() => setВкладка('поле')}>
            Факты и темы
            <span className="v2-card__count">{факты === null ? '…' : все.length}</span>
          </button>
          <button type="button" role="tab" className="v2-tab" aria-selected={!поле}
            title="что поле предлагает постановке: четыре группы с основаниями и рангами; заводит только клик человека"
            onClick={() => { setВкладка('постановка'); setВход(false); setРукой(false) }}>
            Постановка из поля
            {дрейф !== null && дрейф.changed > 0 && (
              <span className="v2-card__count" title="поле изменилось с последнего синтеза — число сервера">
                {дрейф.changed}
              </span>
            )}
          </button>
        </div>
      )}

      {!поле && (
        <>
          {прочитано && <div className="v2-note-line" data-why="следующий-клик">{прочитано}</div>}
          <Постановка project={project} онтология={онтология} onChanged={перечитать} expert={expert} />
        </>
      )}

      {поле && вход && (
        <Source project={project}
          onParsed={(итог) => {
            перечитать()
            if (итог.task) {
              api.taskPlan(project, итог.task)
                // Заметки прошлого приёма гаснут вместе со своим планом: они
                // говорят о ТОМ задании, и рядом с новым были бы неправдой.
                .then((п) => { setПлан(п); setВыбраны(п.actions.map((д) => д.index)); setЗаметкиПриёма([]) })
                .catch(() => setПлан(null))
            }
          }}
          onRead={(run, note) => {
            // Прочитанное ложится запуском постановки: экран переходит на
            // вкладку предложений — человеку не надо догадываться, куда идти.
            перечитать()
            setВкладка('постановка')
            setВход(false)
            setОтказ(null)
            setПрочитано(`документ прочитан: ${note} · запуск ${run}`)
          }}
          onError={setОтказ} />
      )}

      {поле && (
        <Документы project={project}
          onRead={(run, note) => {
            перечитать()
            setВкладка('постановка')
            setВход(false)
            setОтказ(null)
            setПрочитано(`документ прочитан: ${note} · запуск ${run}`)
          }}
          onError={setОтказ} />
      )}

      {поле && рукой && (
        <Manual project={project} темы={темы} знанияV2={знанияV2}
          onDone={перечитать} onError={setОтказ} />
      )}

      {поле && план && (
        <div className="v2-kf__src" data-why="следующий-клик">
          <div className="v2-empty__why">
            План из разбора: {план.actions.length} действий. {план.note}
            {' '}Снятое действие остаётся рассмотренным — факт не исчезает.
          </div>
          {/*
            Ранг материала — НАД планом и словами: до 14.09 экран показывал
            галочки и «Принять N из M», не сказав, откуда план собран.
            Сомнительный ранг гасит пакетный приём — теми же словами, какими
            отказывают ворота приёма на сервере.
          */}
          {пакетЗаперт ? (
            <div className="v2-locked" data-why="почему-нельзя">
              Ранг доверия материала: {план.rank_word}
              <span className="v2-empty__why">{план.batch_refusal}</span>
              <span className="v2-empty__why">
                Источники подтверждаются в карточке исследования ниже: отметьте факты,
                источники которых проверили, — материал поднимется до справочного, и план
                станет приниматься. Факты плана из поля не исчезают: они ждут подтверждения.
              </span>
            </div>
          ) : план.rank_word ? (
            <div className="v2-note-line">Ранг доверия материала: {план.rank_word}</div>
          ) : null}
          {план.assessment && (
            <div className="v2-scroll">
              {(план.assessment.gaps?.length ?? 0) > 0 && (
                <div className="v2-empty__why" data-why="почему-нельзя">
                  Дыры ТЗ против нужд — {план.assessment.gaps!.length}:
                  <ul className="v2-list">
                    {план.assessment.gaps!.map((д) => <li key={д}>{д}</li>)}
                  </ul>
                </div>
              )}
              {(план.assessment.needs?.length ?? 0) > 0 && (
                <table className="v2-tab2">
                  <thead><tr><th>Нужда</th><th>Вердикт</th><th>Чего в ТЗ нет</th><th>Требования ТЗ</th></tr></thead>
                  <tbody>
                    {план.assessment.needs!.map((н) => (
                      <tr key={н.need}>
                        <td className="v2-mono">{н.need}</td>
                        <td className={н.verdict === 'uncovered' ? 'v2-bad' : н.verdict === 'partial' ? 'v2-warn' : 'v2-ok'}>
                          {н.verdict === 'covered' ? 'покрыта' : н.verdict === 'partial' ? 'частично' : 'не покрыта'}
                        </td>
                        <td>{н.gap || '—'}</td>
                        <td className="v2-mono">{н.requirements.join(', ') || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <table className="v2-tab2">
                <thead><tr><th>Требование ТЗ</th><th>Покрывает нужды</th><th>Вердикт</th><th>Почему</th></tr></thead>
                <tbody>
                  {план.assessment.lines.map((л) => (
                    <tr key={л.fact}>
                      <td><span className="v2-mono">{л.fact}</span> {л.requirement}</td>
                      <td className="v2-mono">{л.needs.join(', ') || '—'}</td>
                      <td className={л.verdict === 'none' ? 'v2-bad' : л.verdict === 'partial' ? 'v2-warn' : 'v2-ok'}>
                        {л.verdict === 'covers' ? 'покрывает' : л.verdict === 'partial' ? 'частично' : 'без нужды'}
                      </td>
                      <td>{л.note || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="v2-note-line">
                непокрытых нужд: {план.assessment.uncovered_needs.length}
                {план.assessment.uncovered_needs.length > 0 && ` (${план.assessment.uncovered_needs.join(', ')}) — RFA заказчику в плане`}
                {' · '}требований без нужды: {план.assessment.orphan_requirements.length}
              </div>
            </div>
          )}
          <table className="v2-tab2">
            <thead><tr><th /><th>Действие</th><th>Что появится</th><th>Сцена</th></tr></thead>
            <tbody>
              {план.actions.map((д) => (
                <tr key={д.index}>
                  <td>
                    <input type="checkbox" checked={выбраны.includes(д.index)}
                      onChange={(e) => setВыбраны(e.target.checked
                        ? [...выбраны, д.index] : выбраны.filter((i) => i !== д.index))} />
                  </td>
                  <td>{д.title}</td>
                  <td>{д.preview}</td>
                  <td className="v2-mono">{д.scene}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              disabled={занято || выбраны.length === 0 || пакетЗаперт}
              title={подсказкаПриёма}
              onClick={принятьПлан}>
              {занято ? 'Принимаю…' : `Принять ${выбраны.length} из ${план.actions.length}`}
            </button>
            <button type="button" className="v2-link" onClick={() => setПлан(null)}>позже</button>
          </div>
        </div>
      )}

      {поле && заметкиПриёма.length > 0 && (
        <div className="v2-kf__src" data-why="почему-нельзя">
          <div className="v2-card__head">
            <span className="v2-card__title">Принято не всё</span>
            <span className="v2-card__count">{заметкиПриёма.length}</span>
          </div>
          <div className="v2-empty__why">
            Ворота приёма назвали каждое непринятое действие причиной. Факты остались
            в поле: решайте их по одному либо примите план заново, поправив основания.
          </div>
          <ul className="v2-list">
            {заметкиПриёма.map((з) => (
              <li key={з}>
                {з}
                {/словар/i.test(з) && onGoGlossary && (
                  <>{' '}<button type="button" className="v2-link" onClick={onGoGlossary}
                    title="открыть словарь: кандидат с цитатой ждёт решения — принять, отклонить или слить">к месту: Словарь</button></>
                )}
              </li>
            ))}
          </ul>
          <button type="button" className="v2-link" onClick={() => setЗаметкиПриёма([])}>скрыть</button>
        </div>
      )}

      {поле && (
        <div className="v2-kf__bar">
          <span className="v2-dim">темы:</span>
          <button type="button" className={тема === null ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title="все темы поля знаний" onClick={() => setТема(null)}>все</button>
          {темы.filter((т) => т.facts > 0).map((т) => (
            <button key={т.id} className={тема === т.id ? 'v2-chip v2-chip--on' : 'v2-chip'}
              type="button" title={`факты темы: ${т.facts}`} onClick={() => setТема(т.id)}>
              {т.label} <b>{т.facts}</b>
            </button>
          ))}
        </div>
      )}

      {поле && тема && (() => {
        const т = темы.find((x) => x.id === тема)
        if (!т) return null
        return (
          <div className="v2-kf__bar" data-why="работа">
            <span className="v2-dim">тема «{т.label}»:</span>
            {т.resolved_to
              ? <span>разрешена в <span className="v2-mono">{т.resolved_to}</span></span>
              : (
                <>
                  <input value={адресТемы} placeholder="код сущности проекта (узел, сторона, требование…)"
                    onChange={(e) => setАдресТемы(e.target.value)} />
                  <button type="button" className="v2-link" disabled={!адресТемы.trim()}
                    title="к точке принятые факты темы обязаны найти адрес — сущность проекта"
                    onClick={разрешитьТему}>разрешить в сущность</button>
                </>
              )}
            {знанияV2 && темы.filter((д) => д.id !== т.id).length > 0 && (
              <>
                <select value={слитьВ} onChange={(e) => setСлитьВ(e.target.value)}
                  title="один предмет — одна тема, как бы его ни звали документы: тождество предлагает разбор, сливает человек">
                  <option value="">— слить эту тему в другую —</option>
                  {темы.filter((д) => д.id !== т.id).map((д) => (
                    <option key={д.id} value={д.id}>{д.label}</option>
                  ))}
                </select>
                <button type="button" className="v2-link" disabled={!слитьВ}
                  title={слитьВ
                    ? 'слить темы: факты обеих читаются по голове цепочки, ни один факт не пропадает'
                    : 'выберите тему, в которую сливаем: слияние без цели не бывает'}
                  onClick={() => спросить({
                    question: `Слить тему «${т.label}» в «${имяТемы(слитьВ)}»? `
                      + 'Факты обеих будут читаться по одной голове; отменить слияние нельзя.',
                    ok: 'Слить',
                    input: { label: 'почему это одна тема', required: true },
                    onOk: (повод) => api.mergeTopic(project, т.id, слитьВ, 'инженер', повод)
                      .then((голова) => { setСлитьВ(''); setТема(голова.id); перечитать() })
                      .catch((e) => setОтказ(отказПодробно(e))),
                  })}>слить темы</button>
              </>
            )}
          </div>
        )
      })()}

      {поле && (
        <div className="v2-kf__bar">
          <span className="v2-dim">показать:</span>
          {([
            ['все', 'все факты'],
            ['свободные', 'не рассмотрены'],
            ['допущения', 'допущения к точке'],
            ['спорные', 'противоречия'],
            ['ручные', 'заведены руками'],
            ...(знанияV2 ? [['из исследований', 'из исследований'] as [Фильтр, string]] : []),
          ] as [Фильтр, string][]).map(([ключ, имя]) => (
            <button key={ключ} type="button"
              className={фильтр === ключ ? 'v2-chip v2-chip--on' : 'v2-chip'}
              title={ключ === 'допущения'
                ? 'допущения [П] и принятые допущением — их подтверждают к ближайшей точке'
                : ключ === 'свободные' ? 'факты, по которым решения ещё нет'
                  : ключ === 'ручные' ? 'факты без источника-документа: инженер и дата'
                    : ключ === 'из исследований'
                      ? 'принесённое внешним контуром: ранг сомнительный, пока человек не подтвердил источники'
                      : имя}
              onClick={() => setФильтр(ключ)}>
              {имя}
            </button>
          ))}
        </div>
      )}

      {поле && факты !== null && видно.length === 0 && (
        <div className="v2-empty">
          Фактов по этому отбору нет.
          <span className="v2-empty__why">
            Поле наполняется разбором источника — «Загрузить источник» — либо руками.
          </span>
        </div>
      )}

      {поле && видно.length > 0 && (
        <div className="v2-kf__bar" data-why="следующий-клик">
          <button type="button" className="v2-primary"
            disabled={пакетИдёт || кПакету.length === 0}
            title={пакетИдёт
              ? 'приём идёт: факты принимаются по одному, чтобы отказ по одному не отменил принятого'
              : свободные.length === 0
                ? 'принимать нечего: в этом списке все факты уже решены'
                : кПакету.length === 0
                  ? `все нерешённые здесь сомнительного ранга (${сомнительных}): источник не подтверждён`
                    + ' — подтвердите источники в карточке исследования, либо решайте их в строке'
                  : 'принять все показанные нерешённые факты: каждый станет основанием сущностей'}
            onClick={принятьВсеФакты}>
            {пакетИдёт
              ? `Принимаю… ${пакетШаг} из ${кПакету.length}`
              : `Принять все факты: ${кПакету.length}`}
          </button>
          {сомнительных > 0 && (
            <span className="v2-warn"
              title="результат внешнего контура живёт сомнительным, пока человек не подтвердил источники: пакетом такие факты не принимаются">
              {`сомнительных пропустим: ${сомнительных}`}
            </span>
          )}
          <span className="v2-dim">
            {`в списке: ${видно.length} · не решено: ${свободные.length}`}
          </span>
        </div>
      )}

      {поле && пакетФактов && (
        // Итог пакета — это отчёт о сделанном, а не запертое состояние: даже
        // когда часть отказала, человек читает, что прошло и почему остальное
        // не прошло. Ответ на тест действия один и тот же — «работа».
        <div className="v2-kf__src" data-why="работа">
          <div className="v2-card__head">
            <span className="v2-card__title">Принято пакетом</span>
            <span className="v2-card__count">{пакетФактов.принято}</span>
          </div>
          <div className="v2-empty__why">
            {`Принято: ${пакетФактов.принято}`}
            {пакетФактов.отказано > 0 ? ` · отказал сервер: ${пакетФактов.отказано}` : ' · отказов не было'}
            {пакетФактов.пропущено > 0 && ` · пропущено сомнительных: ${пакетФактов.пропущено}`}
          </div>
          {пакетФактов.причина && (
            <div className="v2-locked">{`первый отказ — ${пакетФактов.причина}`}</div>
          )}
          {пакетФактов.пропущено > 0 && (
            <div className="v2-empty__why">
              Сомнительные факты пакетом не принимаются: их источник не подтверждён.
              Подтвердите источники в карточке исследования — либо решайте такой факт
              в строке по одному, как и прежде.
            </div>
          )}
          <button type="button" className="v2-link" onClick={() => setПакетФактов(null)}>скрыть</button>
        </div>
      )}

      {поле && видно.length > 0 && (
        <table className="v2-tab2">
          <thead>
            <tr>
              <th>
                Утверждение
                {группы.length > 1 && (
                  <button type="button" className="v2-link" onClick={() => setВсеРаскрыты((в) => !в)}
                    title="факты сложены по предмету: раскрыть все группы разом или свернуть">
                    {' '}{всеРаскрыты ? 'свернуть все' : `раскрыть все (${группы.length})`}
                  </button>
                )}
              </th><th>Значение</th><th>Метка</th>
              {знанияV2 && <th>Ранг</th>}
              <th>Откуда</th><th>Решение</th><th />
            </tr>
          </thead>
          <tbody>
            {группы.flatMap((г) => [
              группы.length > 1 && (
                <tr key={`g:${г.предмет}`} className="v2-kf__group">
                  <td colSpan={знанияV2 ? 7 : 6}>
                    <button type="button" className="v2-link" onClick={() => переключить(г.предмет)}
                      title={раскрыта(г.предмет) ? 'свернуть группу' : 'раскрыть факты этого предмета'}>
                      {раскрыта(г.предмет) ? '▾' : '▸'} <b>{г.предмет}</b>
                    </button>
                    <span className="v2-dim">{` · фактов ${г.факты.length} · не решено ${г.нерешено}`}</span>
                  </td>
                </tr>
              ),
              ...(раскрыта(г.предмет) ? г.факты : []).map((ф) => {
              const было = ф.disposition ?? 'free'
              return (
                <tr key={ф.id} id={ф.code ? `v2-fact-${ф.code}` : undefined} className={подсвечен && ф.code === подсвечен ? 'v2-row--cur' : undefined}>
                  <td>{ф.subject ? `${ф.subject}: ` : ''}{ф.predicate}</td>
                  <td>{ф.value}{ф.unit ? ` ${ф.unit}` : ''}</td>
                  <td title={МЕТКА[ф.mark] ?? ф.mark}>{МЕТКА[ф.mark] ?? ф.mark}</td>
                  {знанияV2 && (
                    <td title={ф.rank
                      ? 'ранг доверия наследуется от источника; у руки эксперта — экспертный'
                      : 'ранга нет: факт заведён до перестройки поля — выдуманный ранг хуже отсутствующего'}>
                      {рангСловами(ф.rank)}
                    </td>
                  )}
                  <td className="v2-mono" title={ф.material}>
                    {ф.manual ? <span className="v2-kf__manual">{ф.material}</span> : (ф.anchor ?? '—')}
                    {ф.param_key && <span className="v2-dim"> · анкета: {ф.param_key}</span>}
                    {ф.conflicts && ф.conflicts.length > 0 && (
                      <span className="v2-bad" title="противоречие: то же утверждение с иным значением в другом факте; показаны оба, победителя ИИ не выбирает — решает человек">
                        {' '}· противоречит {ф.conflicts.map((к, i) => (
                          <span key={к}>{i > 0 ? ', ' : ''}
                            <button type="button" className="v2-link" onClick={() => кФакту(к)}
                              title={`перейти к факту ${к}: его строка раскроется и подсветится`}>{к}</button>
                          </span>
                        ))}
                      </span>
                    )}
                    {ф.source_updated && <span className="v2-warn" title={ф.source_updated}> · источник обновлён</span>}
                    {ф.assumption && (
                      <span className="v2-dim" title={`проверка: ${ф.assumption.validation}; если неверно: ${ф.assumption.impact_if_wrong}`}>
                        {' '}· допущение · {ф.assumption.owner} · к {ф.assumption.confirm_by}
                      </span>
                    )}
                  </td>
                  <td className={было === 'adopted' ? 'v2-ok' : было === 'rejected' ? 'v2-warn' : ''}>
                    {РЕШЕНИЕ[было] ?? было}
                  </td>
                  <td>
                    {решаем?.факт !== ф.id && (
                      <>
                        {было !== 'adopted' && (
                          <button type="button" className="v2-link"
                            title="принять факт: он станет основанием сущности — одним кликом"
                            onClick={() => решить(ф, 'adopted')}>принять</button>
                        )}
                        {было !== 'noted' && (
                          <>
                            {было !== 'adopted' && ' · '}
                            <button type="button" className="v2-link"
                              title="учтён: рассмотрен и не взят — это тоже решение, факт не исчезает"
                              onClick={() => решить(ф, 'noted')}>учесть</button>
                          </>
                        )}
                        {было !== 'rejected' && (
                          <>
                            {' · '}
                            <button type="button" className="v2-link"
                              title="отклонить — с причиной: «нет» без объяснения через год читается как забывчивость"
                              onClick={() => решить(ф, 'rejected')}>отклонить</button>
                          </>
                        )}
                        {было !== 'assumed' && было !== 'adopted' && (
                          <>
                            {' · '}
                            <button type="button" className="v2-link"
                              title="принять допущением: владелец, точка подтверждения и способ проверки обязательны — к точке допущение держит её"
                              onClick={() => setДопущение({ факт: ф.id, owner: '', confirm_by: 'MCR', validation: '', impact: '' })}>допущение</button>
                          </>
                        )}
                      </>
                    )}
                    {допущение?.факт === ф.id && (
                      <div className="v2-kf__why" data-why="работа">
                        <input value={допущение.owner} placeholder="владелец допущения"
                          onChange={(e) => setДопущение({ ...допущение, owner: e.target.value })} />
                        <select value={допущение.confirm_by} onChange={(e) => setДопущение({ ...допущение, confirm_by: e.target.value })}>
                          <option value="internal_review">к внутреннему обзору</option>
                          <option value="MCR">к MCR</option>
                          <option value="KDP-A">к KDP-A</option>
                        </select>
                        <input value={допущение.validation} placeholder="чем подтвердить (замер, расчёт, запрос)"
                          onChange={(e) => setДопущение({ ...допущение, validation: e.target.value })} />
                        <input value={допущение.impact} placeholder="что будет, если неверно"
                          onChange={(e) => setДопущение({ ...допущение, impact: e.target.value })} />
                        <div className="v2-form__actions">
                          <button type="button" className="v2-primary" disabled={!допущение.owner.trim() || !допущение.validation.trim()}
                            title="поставить допущение: до подтверждения точка держится им" onClick={записатьДопущение}>Допустить</button>
                          <button type="button" className="v2-link" onClick={() => setДопущение(null)}>отмена</button>
                        </div>
                      </div>
                    )}
                    {решаем?.факт === ф.id && (
                      <span className="v2-kf__why">
                        <textarea value={причина} autoFocus rows={2}
                          autoComplete="off" onChange={(e) => setПричина(e.target.value)}
                          placeholder={
                            решаем.решение === 'rejected' ? 'почему не берём'
                              : было === 'contested' ? 'какой факт победил и почему'
                                : 'что изменилось с прошлого решения'
                          } />
                        <button type="button" disabled={!причина.trim()}
                          title={причина.trim() ? 'записать решение' : 'здесь повод обязателен'}
                          onClick={записать}>Записать</button>
                        <button type="button" className="v2-link" title="не менять решение"
                          onClick={() => { setРешаем(null); setПричина('') }}>отмена</button>
                      </span>
                    )}
                  </td>
                </tr>
              )
            }),
            ])}
          </tbody>
        </table>
      )}

      {поле && знанияV2 && исследования.length > 0 && (
        <div className="v2-kf__src" data-why="следующий-клик">
          <div className="v2-card__head">
            <span className="v2-card__title">Исследования</span>
            <span className="v2-card__count">{исследования.length}</span>
          </div>
          <div className="v2-empty__why">
            Принесённое внешним контуром живёт материалом сомнительного ранга: его факты не
            идут в предложения сцен, пока человек не подтвердил источники — отбор
            «из исследований» показывает их все.
          </div>
          {местаИсследований(исследования).map((з) => (
            <ResearchPanel key={з.id} project={project} trigger={з.trigger} onChanged={перечитать} />
          ))}
        </div>
      )}

      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/**
 * По одной панели на МЕСТО входа: у места бывает несколько циклов, а панель
 * ведёт последний — две панели одного места были бы одной и той же дважды.
 */
function местаИсследований(задачи: ResearchTask[]): ResearchTask[] {
  const место = (з: ResearchTask) => з.trigger.scene ?? з.trigger.gate ?? з.place
  return задачи.filter((з, i) => задачи.findIndex((д) => место(д) === место(з)) === i)
}

/** Вход в поле: текст, файл или ссылка + задание → разбор. Экспорт — сцена 2 зовёт его на пустом проекте (З-02). */
export function Source({ project, onParsed, onError, onRead }: {
  project: string
  onParsed: (итог: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => void
  onError: (e: string) => void
  /** Документ прочитан в постановку: экран переходит к предложениям. */
  onRead?: (run: string, note: string) => void
}) {
  const [имя, setИмя] = useState('')
  const [текст, setТекст] = useState('')
  const [ссылка, setСсылка] = useState('')
  const [вид, setВид] = useState('mission_memo')
  const [задание, setЗадание] = useState('разбери по сущностям')
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)
  const [прежние, setПрежние] = useState<{ code: string; name: string }[]>([])
  const [прежний, setПрежний] = useState('')
  // Ранг доверия называет ЧЕЛОВЕК, режим разбора выводит разбор по профилю
  // содержимого: тип документа полем ввода на проекте поля знаний v2 не бывает.
  const [ранг, setРанг] = useState<Authority | ''>('')
  const [рольДок, setРольДок] = useState<DocumentRole | ''>('')
  const [читаю, setЧитаю] = useState(0)
  const [знанияV2, setЗнанияV2] = useState(false)
  const [поставлен, setПоставлен] = useState<string | null>(null)
  // Устав проверяется по двенадцати пунктам записки при загрузке: короткая
  // карта приходит ответом, полная — по кнопке (ТРЕБОВАНИЯ-К-ЗАПИСКЕ-МИССИИ).
  const [пробелы, setПробелы] = useState<{ code: string; карта: GapBrief } | null>(null)
  const [картаОткрыта, setКартаОткрыта] = useState(false)

  useEffect(() => {
    api.materials(project).then((r) => setПрежние(r.items)).catch(() => setПрежние([]))
    // Признак поля знаний v2 даёт сервер: там, где синтеза нет, форма
    // остаётся прежней — с типом входного и без ранга.
    api.synthesisPending(project).then(() => setЗнанияV2(true)).catch(() => setЗнанияV2(false))
  }, [project])

  // Задание — по типу входного (каталог заданий, ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА §3);
  // строка остаётся редактируемой: узел или намерение инженер уточняет сам.
  const заданиеПоТипу: Record<string, string> = {
    mission_memo: 'разбери по сущностям',
    tor: 'это ТЗ — оцени против нужд',
    datasheet: 'обнови параметры ‹код узла›',
    normative: 'это норматив — заведи',
    analysis: 'разбери по сущностям',
    reference: 'просто в контекст',
  }
  const сменитьТип = (т: string) => { setВид(т); setЗадание(заданиеПоТипу[т] ?? 'разбери по сущностям') }

  // Текстовый файл читается В БРАУЗЕРЕ и уходит текстом; двоичный (docx ·
  // pdf · xlsx · pptx) уходит base64, текст извлекает сервер тем же
  // извлекателем, что у документов v1 (замечание ПМИ-5, 12.09).
  const [двоичный, setДвоичный] = useState<{ name: string; base64: string } | null>(null)
  const файл = (f: File | null) => {
    if (!f) { setДвоичный(null); return }
    if (!имя.trim()) setИмя(f.name.replace(/\.[^.]+$/, ''))
    const текстовый = /\.(txt|md|csv|markdown)$/i.test(f.name) || f.type.startsWith('text/')
    if (текстовый) {
      setДвоичный(null)
      f.text().then(setТекст).catch(() => onError('файл не прочитался: приложите текстовый'))
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const url = String(reader.result ?? '')
      setДвоичный({ name: f.name, base64: url.substring(url.indexOf(',') + 1) })
      setТекст('')
    }
    reader.onerror = () => onError('файл не прочитался')
    reader.readAsDataURL(f)
  }

  // Разбор — фоновой задачей (ADR-069): стенд отвечает, пока модель думает;
  // статус опрашивается раз в три секунды, готовый ответ применяется при опросе.
  const разобрать = () => {
    setЗанято(true); setИтог(null)
    const готово = (р: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => {
      setИтог(`${р.note}${р.refusals.length ? ` · отклонено: ${р.refusals.slice(0, 3).join('; ')}` : ''}`)
      onParsed(р)
      setЗанято(false)
    }
    const опрос = (job: string) => {
      api.atomizeJobStatus(project, job).then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job}: ${з.elapsed_seconds} с — стенд отвечает, страницу можно не держать`); window.setTimeout(() => опрос(job), 3000) }
      }).catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
    }
    api.putMaterial(project, {
      name: имя, text: текст, url: ссылка, author: 'инженер', supersedes: прежний || undefined,
      // Ранг — с формы; тип входного остаётся только там, где поля знаний v2
      // нет: иначе режим разбора снова читался бы с типа файла.
      ...(знанияV2 ? { rank: ранг || undefined, role: рольДок || undefined } : { kind: вид }),
      ...(двоичный ? { filename: двоичный.name, file_base64: двоичный.base64 } : {}),
    })
      .then((м) => {
        // Показывается ранг, который ПОСТАВИЛО ядро, а не тот, что отправлен.
        if (м.rank) {
          setПоставлен(`ранг источника: ${РАНГ[м.rank] ?? м.rank}`
            + (м.rank_note ? ` · ${м.rank_note}` : ''))
        }
        setПробелы(м.gaps ? { code: м.code, карта: м.gaps } : null)
        return api.atomizeJob(project, м.code, задание, 'инженер')
      })
      .then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job} — стенд отвечает`); window.setTimeout(() => опрос(з.job), 3000) }
      })
      .catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
  }

  /**
   * Прочитать документ В ПОСТАНОВКУ — один вызов вместо связки «атомизация по
   * предикатам → формирование по фильтрам» (РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ, 15.09).
   * Модель читает смыслом и отдаёт понятия сразу, каждое с цитатой и якорем.
   *
   * Вызов идёт минуту и больше: экран считает секунды вслух, иначе ожидание
   * читается как «ничего не происходит».
   */
  const прочитать = () => {
    setЗанято(true); setИтог(''); setЧитаю(1)
    const часы = window.setInterval(() => setЧитаю((с) => с + 1), 1000)
    const кончить = () => { window.clearInterval(часы); setЧитаю(0); setЗанято(false) }
    api.putMaterial(project, {
      name: имя, text: текст, url: ссылка, author: 'инженер', supersedes: прежний || undefined,
      rank: ранг || undefined, role: рольДок || undefined,
      ...(двоичный ? { filename: двоичный.name, file_base64: двоичный.base64 } : {}),
    })
      .then((м) => {
        if (м.rank) setПоставлен(`ранг источника: ${РАНГ[м.rank] ?? м.rank}`)
        setПробелы(м.gaps ? { code: м.code, карта: м.gaps } : null)
        return api.readDocument(project, м.code, 'инженер')
      })
      .then((р) => {
        кончить()
        setИтог(`${р.note} · запуск ${р.run}`)
        onRead?.(р.run, р.note)
      })
      .catch((e) => { кончить(); onError(String(e.message ?? e)) })
  }

  const есть = текст.trim().length > 0 || ссылка.trim().length > 0 || двоичный !== null

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <label>название
          <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Записка миссии" />
        </label>
        {знанияV2 ? (
          <label>ранг доверия
            <select value={ранг} onChange={(e) => setРанг(e.target.value as Authority | '')}
              title="чего этот источник стоит: обязательный — заказчик, регулятор, директива; справочный — аналитика и даташит; сомнительный — непроверенное. Режим разбора выводится из содержимого, а не из типа файла">
              <option value="">— назовите ранг —</option>
              {РАНГИ_ИСТОЧНИКА.map((р) => <option key={р} value={р}>{РАНГ[р]}</option>)}
            </select>
          </label>
        ) : (
          <label>тип
            <select value={вид} onChange={(e) => сменитьТип(e.target.value)}>
              <option value="mission_memo">записка миссии</option>
              <option value="tor">техническое задание</option>
              <option value="normative">норматив</option>
              <option value="datasheet">даташит</option>
              <option value="analysis">анализ</option>
              <option value="reference">справочный</option>
            </select>
          </label>
        )}
      </div>
      <label>текст
        <textarea rows={4} value={текст} autoComplete="off" onChange={(e) => setТекст(e.target.value)}
          placeholder="вставьте текст — либо приложите файл или дайте ссылку ниже" />
      </label>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr' }}>
        <label>файл
          <input type="file" accept=".txt,.md,.csv,.docx,.pdf,.xlsx,.pptx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.presentationml.presentation"
            title="txt · md · csv читаются в браузере; docx · pdf · xlsx · pptx — текст извлекает сервер"
            onChange={(e) => файл(e.target.files?.[0] ?? null)} />
          {двоичный && <span className="v2-muted"> {двоичный.name}: текст извлечёт сервер</span>}
        </label>
        <label>ссылка
          <input value={ссылка} onChange={(e) => setСсылка(e.target.value)} placeholder="https://…" />
        </label>
      </div>
      {знанияV2 && (
        <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr' }}>
          <label>роль документа
            <select value={рольДок} onChange={(e) => setРольДок(e.target.value as DocumentRole | '')}
              title="ролью решается, ЧТО из документа может образоваться: цели рождает только устав, издатель норматива стороной не бывает">
              <option value="">— назовите роль —</option>
              {РОЛИ_ДОКУМЕНТА.map((р) => <option key={р.code} value={р.code}>{р.word}</option>)}
            </select>
          </label>
          <div className="v2-note-line">
            {рольДок
              ? РОЛИ_ДОКУМЕНТА.find((р) => р.code === рольДок)?.hint
              : 'без роли документ читается как обстановка — она беднее всех правами: ни целей, ни сервисов'}
          </div>
        </div>
      )}
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <label>задание
          <input value={задание} onChange={(e) => setЗадание(e.target.value)}
            placeholder="разбери по сущностям · это норматив — заведи · сравни с нашим" />
        </label>
        <label>новая версия материала
          <select value={прежний} onChange={(e) => setПрежний(e.target.value)}
            title="прежняя версия того же входного: изменённые блоки пометят свои факты и сущности «источник обновлён»">
            <option value="">— нет, новый источник —</option>
            {прежние.map((м) => <option key={м.code} value={м.code}>{м.code} · {м.name}</option>)}
          </select>
        </label>
      </div>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary"
          disabled={занято || !есть || !имя.trim() || (знанияV2 && !ранг)}
          title={!имя.trim() ? 'дайте источнику название'
            : !есть ? 'нужен текст, файл или ссылка'
              : знанияV2 && !ранг ? 'назовите ранг доверия: чего этот источник стоит, решает человек'
                : 'положить материал и разобрать: факты и темы лягут в поле'}
          onClick={разобрать}>
          {занято ? 'Разбираю…' : 'Разобрать'}
        </button>
        {знанияV2 && (
          <button type="button" className="v2-primary"
            disabled={занято || !есть || !имя.trim() || !ранг}
            title={!имя.trim() ? 'дайте источнику название'
              : !есть ? 'нужен текст, файл или ссылка'
                : !ранг ? 'назовите ранг доверия'
                  : !рольДок ? 'роль не названа: документ прочтётся как обстановка — ни целей, ни сервисов'
                    : 'один вызов: документ читается смыслом и даёт постановку сразу — стороны, нужды, цели, рамки, вехи; у каждого пункта цитата и якорь'}
            onClick={прочитать}>
            {читаю > 0 ? `Читаю… ${читаю} с` : 'Прочитать документ'}
          </button>
        )}
        {поставлен && <span className="v2-dim">{поставлен}</span>}
        {итог && <span className="v2-dim">{итог}</span>}
      </div>
      {пробелы && (
        <div className="v2-note-line" data-why="почему-нельзя" aria-label="пробелы устава">
          карта пробелов устава: найдено {пробелы.карта.present} из 12
          {пробелы.карта.missing.length > 0 && <> · не найдено: {пробелы.карта.missing.join(', ')}</>}
          {пробелы.карта.blocked_scenes.length > 0 && <> · заперты сцены {пробелы.карта.blocked_scenes.join(', ')}</>}
          {' '}
          <button type="button" className="v2-link" onClick={() => setКартаОткрыта(!картаОткрыта)}
            title="двенадцать пунктов записки: что есть, чего нет, какую сцену пункт закрывает">
            {картаОткрыта ? 'скрыть карту' : 'показать карту'}
          </button>
        </div>
      )}
      {пробелы && картаОткрыта && <КартаПробелов project={project} code={пробелы.code} onError={onError} />}
      {знанияV2 && (
        <Партия project={project} ранг={ранг} роль={рольДок}
          onDone={() => onParsed({ task: '', note: 'партия каталога разобрана', accepted: 0, refused: 0, refusals: [] })}
          onError={onError} />
      )}
    </div>
  )
}

/**
 * Документы проекта: роль у каждого и «Прочитать документ» для УЖЕ лежащих.
 *
 * До 16.09 чтение начиналось только с формы загрузки — у документов, уже
 * разобранных прежним порядком, кнопки не было вовсе, и владелец жал
 * «Сформировать постановку из поля»: срез в 50 элементов из трёхсот давал
 * двадцать шесть предложений, из них восемнадцать «узнанного принятого».
 * Читать надо ДОКУМЕНТ, а не срез поля, — и теперь это можно сделать с
 * любого лежащего.
 */
export function Документы({ project, onRead, onError }: {
  project: string
  onRead: (run: string, note: string) => void
  onError: (e: string) => void
}) {
  const [список, setСписок] = useState<MaterialRow[]>([])
  const [роли, setРоли] = useState<Record<string, DocumentRole | ''>>({})
  const [читаю, setЧитаю] = useState<{ code: string; sec: number } | null>(null)
  // Досье документа (шип 4 §3) раскрывается под строкой; карта пробелов — у устава.
  const [досьеОткрыто, setДосьеОткрыто] = useState<string | null>(null)
  const [картаОткрыта, setКартаОткрыта] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    api.materials(project).then((r) => {
      setСписок(r.items)
      setРоли(Object.fromEntries(r.items.map((м) => [м.code, (м.role ?? '') as DocumentRole | ''])))
    }).catch(() => setСписок([]))
  }, [project])
  useEffect(перечитать, [перечитать])

  const прочитать = (м: MaterialRow) => {
    const роль = роли[м.code]
    setЧитаю({ code: м.code, sec: 1 })
    const часы = window.setInterval(
      () => setЧитаю((т) => (т ? { ...т, sec: т.sec + 1 } : null)), 1000,
    )
    const кончить = () => { window.clearInterval(часы); setЧитаю(null) }
    const роль_ = роль ? api.setMaterialRole(project, м.code, роль) : Promise.resolve(null)
    роль_
      .then(() => api.readDocument(project, м.code, 'инженер'))
      .then((р) => { кончить(); перечитать(); onRead(р.run, р.note) })
      .catch((e) => { кончить(); onError(String(e.message ?? e)) })
  }

  // Библиотека другого проекта показывается и без своих документов: новому
  // проекту норматив или обстановку берут готовыми, не разбирая заново.
  if (список.length === 0) {
    return (
      <div className="v2-kf__src" data-why="работа">
        <ВзятьИзПроекта project={project} onDone={перечитать} onError={onError} />
      </div>
    )
  }
  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-note-line">
        Документы проекта. Чтение идёт по ДОКУМЕНТУ и даёт постановку сразу —
        стороны, нужды, цели, рамки, вехи, у каждого пункта цитата и якорь.
        «Сформировать постановку из поля» — другое: оно берёт срез поля под потолок.
        Досье показывает, что документ принёс и что на нём стоит; вклад откатывается одним действием.
      </div>
      <table className="v2-table">
        <thead><tr><th>Код</th><th>Документ</th><th>Роль</th><th>Ранг</th><th /></tr></thead>
        <tbody>
          {список.map((м) => (
            <Фрагмент key={м.code}>
            <tr>
              <td className="v2-mono">{м.code}</td>
              <td>{м.name}<span className="v2-muted"> · {м.chars} знаков</span>
                {м.accept_mode && <span className="v2-chip" title="режим приёма документа"> {РЕЖИМЫ_ПРИЁМА.find((р) => р.code === м.accept_mode)?.word ?? м.accept_mode}</span>}
                {м.summary && <div className="v2-muted" title="резюме разбора">{м.summary}</div>}
              </td>
              <td>
                <select value={роли[м.code] ?? ''}
                  onChange={(e) => setРоли({ ...роли, [м.code]: e.target.value as DocumentRole | '' })}
                  title="ролью решается, ЧТО из документа может образоваться: цели рождает только устав">
                  <option value="">— роль не названа —</option>
                  {РОЛИ_ДОКУМЕНТА.map((р) => <option key={р.code} value={р.code}>{р.word}</option>)}
                </select>
              </td>
              <td>{м.rank ? (РАНГ[м.rank] ?? м.rank) : <span className="v2-warn">не назван</span>}</td>
              <td>
                <button type="button" className="v2-primary"
                  disabled={читаю !== null}
                  title={роли[м.code]
                    ? 'один вызов: документ читается смыслом и даёт постановку сразу'
                    : 'роль не названа — документ прочтётся как обстановка: ни целей, ни сервисов'}
                  onClick={() => прочитать(м)}>
                  {читаю?.code === м.code ? `Читаю… ${читаю.sec} с` : 'Прочитать документ'}
                </button>
                {' '}
                <button type="button" className="v2-link" onClick={() => setДосьеОткрыто(досьеОткрыто === м.code ? null : м.code)}
                  title="досье: резюме, прогоны разбора, вклад, сущности на документе, откат вклада">
                  {досьеОткрыто === м.code ? 'скрыть досье' : 'Досье'}
                </button>
                {м.role === 'charter' && (
                  <>
                    {' '}
                    <button type="button" className="v2-link" onClick={() => setКартаОткрыта(картаОткрыта === м.code ? null : м.code)}
                      title="карта пробелов устава: двенадцать пунктов записки миссии">
                      {картаОткрыта === м.code ? 'скрыть карту' : 'Карта пробелов'}
                    </button>
                  </>
                )}
              </td>
            </tr>
            {(досьеОткрыто === м.code || картаОткрыта === м.code) && (
              <tr className="v2-card-row"><td colSpan={5}>
                {досьеОткрыто === м.code && <Досье project={project} code={м.code} onChanged={перечитать} onError={onError} />}
                {картаОткрыта === м.code && <КартаПробелов project={project} code={м.code} onError={onError} />}
              </td></tr>
            )}
            </Фрагмент>
          ))}
        </tbody>
      </table>
      <ВзятьИзПроекта project={project} onDone={перечитать} onError={onError} />
    </div>
  )
}

/**
 * Тема или факт руками: полноправно, но видно как ручное.
 *
 * На проекте поля знаний v2 ввод эксперта не попадает в модель напрямую: он
 * идёт СВЕРКОЙ. Она заводит кандидат-факт (учётка · роль · дата вместо
 * якоря) и показывает находки по четырём вопросам; слить, уточнить, оспорить
 * или завести новым — решение человека, одно на карточку.
 */
function Manual({ project, темы, знанияV2, onDone, onError }: {
  project: string
  темы: Тема[]
  знанияV2: boolean
  onDone: () => void
  onError: (e: string) => void
}) {
  const [метка, setМетка] = useState('')
  const [предмет, setПредмет] = useState('')
  const [утверждение, setУтверждение] = useState('')
  const [значение, setЗначение] = useState('')
  const [единица, setЕдиница] = useState('')
  const [вид, setВид] = useState('framing')
  const [темаФакта, setТемаФакта] = useState('')
  const [понятие, setПонятие] = useState('need')
  const [род, setРод] = useState('')
  const [роль, setРоль] = useState('')
  const [сверка, setСверка] = useState<ReconcileRun | null>(null)
  const [смысл, setСмысл] = useState(false)
  const [занято, setЗанято] = useState(false)

  const завестиТему = () => {
    api.addTopic(project, метка.trim(), 'инженер')
      .then(() => { setМетка(''); onDone() }).catch((e) => onError(String(e.message ?? e)))
  }
  const завестиФакт = () => {
    api.addFact(project, {
      subject: предмет, predicate: утверждение, value: значение, unit: единица,
      kind: вид, topic: темаФакта, author: 'инженер',
    })
      .then(() => { setУтверждение(''); setЗначение(''); onDone() })
      .catch((e) => onError(String(e.message ?? e)))
  }
  const величина = вид === 'quantity'

  // Сверка НИЧЕГО не меняет в модели: она заводит кандидат-факт и запись
  // запуска, а дальше ждёт человека. Батч здесь один — строка ввода одна.
  const сверить = () => {
    setЗанято(true)
    api.reconcile(
      project,
      [{
        local_id: 'c1',
        concept: понятие,
        payload: содержимоеКандидата(понятие, предмет, утверждение, значение, единица, род),
        origin: 'manual',
      }],
      'инженер',
      роль.trim(),
      смысл,
    )
      .then((з) => { setСверка(з); onDone() })
      .catch((e) => onError(отказПодробно(e)))
      .finally(() => setЗанято(false))
  }

  /** Решение человека применено — перечитываем тот же запуск, без нового вызова. */
  const перечитатьСверку = () => {
    onDone()
    if (!сверка) return
    api.reconcileRun(project, сверка.run).then(setСверка).catch((e) => onError(отказПодробно(e)))
  }

  const формулировка = формулировкаКандидата(понятие, предмет, утверждение)

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
        <label>новая тема
          <input value={метка} onChange={(e) => setМетка(e.target.value)} placeholder="Опыт ЛИ Гонец-Д1М" />
        </label>
        <button type="button" disabled={!метка.trim()} onClick={завестиТему}
          title="тема руками: предмет, о котором знания копятся">Завести тему</button>
      </div>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr 1fr 80px 1fr' }}>
        <label>предмет
          <input value={предмет} onChange={(e) => setПредмет(e.target.value)} placeholder="Гонец-Д1М" />
        </label>
        <label>утверждение
          <input value={утверждение} onChange={(e) => setУтверждение(e.target.value)}
            placeholder="срок активного существования по ЛИ" />
        </label>
        <label>значение
          <input value={значение} onChange={(e) => setЗначение(e.target.value)} placeholder="7" />
        </label>
        <label>единица
          <input value={единица} onChange={(e) => setЕдиница(e.target.value)} placeholder={величина ? 'лет' : '—'}
            title={величина ? 'величина без единицы — не факт' : 'у текста единицы нет'} />
        </label>
        {знанияV2 ? (
          <label>о чём ввод
            <select value={понятие} onChange={(e) => setПонятие(e.target.value)}
              title="понятие онтологии формирования: по нему сверка знает, что считать дублем, что противоречием и какой связи не хватает">
              {Object.entries(ПОНЯТИЕ).map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
        ) : (
          <label>вид
            <select value={вид} onChange={(e) => setВид(e.target.value)}>
              {ВИДЫ_ФАКТА.map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
        )}
      </div>
      {знанияV2 && ПОЛЯ_ПОНЯТИЯ[понятие]?.род && (
        <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
          <label>{ПОЛЯ_ПОНЯТИЯ[понятие]!.род!.подпись}
            <select value={род} onChange={(e) => setРод(e.target.value)}
              title="обязательное поле вида: без него запись не отвечала бы собственной схеме, и сервер её не примет">
              <option value="">— выбрать —</option>
              {ПОЛЯ_ПОНЯТИЯ[понятие]!.род!.значения.map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
          <span className="v2-empty__why">Вехи технологий сюда не идут: они остаются точкой.</span>
        </div>
      )}
      {знанияV2 && (
        <div className="v2-empty__why">
          {ПОЛЯ_ПОНЯТИЯ[понятие]?.ссылка
            ? 'Предмет здесь — сторона, которая нуждается: связь обязательна, без неё сущности не будет.'
            : ПОЛЯ_ПОНЯТИЯ[понятие]?.изПредмета
              ? 'Предмет здесь — само имя: им понятие и узнаётся.'
              : 'Предмет и утверждение складываются в одну постановку.'}
          {ПОЛЯ_ПОНЯТИЯ[понятие]?.величина
            ? ' Значение с единицей ложится показателем: величина без единицы — не факт.'
            : ''}
        </div>
      )}
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
        {знанияV2 ? (
          <label>роль автора
            <input value={роль} onChange={(e) => setРоль(e.target.value)} placeholder="ведущий СИ"
              title="у руки эксперта якоря нет: источником становятся учётка, роль и дата — без роли сервер ввод не примет" />
          </label>
        ) : (
          <label>тема факта
            <select value={темаФакта} onChange={(e) => setТемаФакта(e.target.value)}>
              <option value="">— без темы —</option>
              {темы.map((т) => <option key={т.id} value={т.label}>{т.label}</option>)}
            </select>
          </label>
        )}
        {знанияV2 ? (
          <span className="v2-kf__why">
            <label title="ступень по ключу идёт всегда и токенов не стоит: дубль по ключу находится без вызова службы. Смысловую ступень зовёт человек — за неё платят токенами">
              <input type="checkbox" checked={смысл} onChange={(e) => setСмысл(e.target.checked)} />
              {' '}спросить смысл
            </label>
            <button type="button" className="v2-primary"
              disabled={занято || формулировка === '' || !роль.trim()}
              title={!роль.trim()
                ? 'назовите роль автора: у руки эксперта якоря нет — есть учётка, роль и дата'
                : формулировка === ''
                  ? 'сверять нечего: назовите предмет либо утверждение'
                  : 'показать находки по четырём вопросам — дубль · противоречие · соединение · нехватка; модель не изменится ни на строку'}
              onClick={сверить}>{занято ? 'Сверяю…' : 'Сверить ввод'}</button>
          </span>
        ) : (
          <button type="button" className="v2-primary"
            disabled={!утверждение.trim() || !значение.trim() || (величина && !единица.trim())}
            title="факт руками: метка [И], источник — инженер и дата; полноправный, но виден как ручной"
            onClick={завестиФакт}>Завести факт</button>
        )}
      </div>
      <div className="v2-empty__why">
        {знанияV2
          ? 'Ввод эксперта — источник, равный документу по механике и отличный рангом: экспертный. '
            + 'Сверка заводит кандидат-факт и показывает находки; завести новым, слить, уточнить '
            + 'или оспорить — решение человека, одно на карточку.'
          : 'Ручной факт полноправен — диспозиции, связи, промпт. Доля знаний из источников '
            + 'считается без него: ручное видно отдельно.'}
      </div>

      {знанияV2 && сверка && (
        <Находки project={project} сверка={сверка} onDone={перечитатьСверку} onError={onError} />
      )}
    </div>
  )
}

/**
 * Находки сверки: что служба УВИДЕЛА и что предлагает сделать с вводом.
 *
 * Одно действие на карточку и только по нажатию человека: `apply` —
 * единственный путь изменения модели, и без причины сервер решение не ставит.
 * Служба не сливает и не переписывает принятое сама; «похоже» без названного
 * отличия сюда не доезжает — такую находку сервер отбрасывает раньше.
 */
function Находки({ project, сверка, onDone, onError }: {
  project: string
  сверка: ReconcileRun
  onDone: () => void
  onError: (e: string) => void
}) {
  const [ask, спросить, закрытьВопрос] = useConfirm()
  const [итог, setИтог] = useState<string | null>(null)

  const решить = (
    п: ReconcileItem, номер: number, н: ReconcileFinding, действие: ReconcileAction,
  ) => спросить({
    question: `${ДЕЙСТВИЕ[действие] ?? действие}: ${н.question_word}`
      + `${н.target ? ` · ${н.target}` : ''}. Модель меняется только этим решением.`,
    ok: ДЕЙСТВИЕ[действие] ?? действие,
    input: { label: 'почему так решили', required: true },
    onOk: (повод) => api.reconcileApply(project, сверка.run, {
      local_id: п.local_id,
      finding: номер,
      action: действие,
      target: н.target ?? undefined,
      reason: повод,
      author: 'инженер',
    })
      .then((и) => { setИтог(`${и.note} · доля знаний из источников: ${и.coverage}%`); onDone() })
      .catch((e) => onError(отказПодробно(e))),
  })

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-note-line">
        Сверка <span className="v2-mono">{сверка.run}</span>
        {сверка.note ? ` · ${сверка.note}` : ''}
        {сверка.ai_called ? ' · со смысловой ступенью' : ' · по ключу, без живого вызова'}
      </div>
      {итог && <div className="v2-empty__why">{итог}</div>}
      {сверка.items.map((п) => (
        <div key={п.local_id} className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">{ПОНЯТИЕ[п.concept] ?? п.concept}</span>
            <span className="v2-card__count" title="вердикт сверки словами сервера">{п.verdict_word}</span>
            <span className="v2-dim">
              кандидат-факт <span className="v2-mono">{п.candidate_fact}</span>
              {` · ранг: ${рангСловами(п.rank)} · ${источникСловами(п.source)}`}
            </span>
          </div>
          {п.note && <div className="v2-empty__why">{п.note}</div>}
          {п.blocking.length > 0 && (
            <div className="v2-locked">
              Пока не закрыто, сущности не будет:{' '}
              <span className="v2-mono">{п.blocking.join(' · ')}</span>
            </div>
          )}
          {п.decided ? (
            <div className="v2-empty__why">
              решение человека: {ДЕЙСТВИЕ[п.decided] ?? п.decided} — одно на карточку
            </div>
          ) : п.findings.map((н, номер) => (
            <div key={`${п.local_id}-${номер}`} className="v2-check">
              <span>·</span>
              <span className="v2-check__t">
                <b>{н.question_word}</b>
                {н.target && <> · <span className="v2-mono">{н.target}</span></>}
                {` · нашли ${н.match}`}
                {н.difference && (
                  <div>
                    {`отличие по полю «${ПОЛЕ[н.difference.field] ?? н.difference.field}»: `}
                    {н.difference.comparison}
                    {` — у ввода «${н.difference.mine}», у принятого «${н.difference.theirs}»`}
                    {н.difference.reason ? ` · ${н.difference.reason}` : ''}
                  </div>
                )}
                {н.compared_fields.length > 0 && (
                  <div className="v2-dim">
                    {`сравнивали по полям: ${н.compared_fields.map((к) => ПОЛЕ[к] ?? к).join(' · ')}`}
                  </div>
                )}
                {н.basis.length > 0 && (
                  <div className="v2-dim">
                    основания: <span className="v2-mono">{н.basis.join(' · ')}</span>
                  </div>
                )}
                {н.missing && (
                  <div className="v2-warn">
                    не закрыта обязательная связь: <span className="v2-mono">{н.missing}</span>
                  </div>
                )}
                <span className="v2-form__actions">
                  {н.offers.map((действие) => (
                    <button key={действие} type="button" className="v2-link"
                      title={`${ДЕЙСТВИЕ[действие] ?? действие}: решение запишется с причиной — без неё сервер его не ставит`}
                      onClick={() => решить(п, номер, н, действие)}>
                      {ДЕЙСТВИЕ[действие] ?? действие}
                    </button>
                  ))}
                </span>
              </span>
            </div>
          ))}
        </div>
      ))}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/**
 * Постановка из поля: диф четырьмя группами — новое · дополнить ·
 * противоречит · подтверждено.
 *
 * Синтез идёт по ВСЕМУ полю, а не по последнему источнику, и ничего не
 * заводит: он предлагает. Отмеченное принимает клик человека, и то ЧЕРЕЗ
 * сверку — теми же четырьмя вопросами, что и ручной ввод. Счётчики групп,
 * размер среза и вердикты приходят с сервера готовыми; заранее не отмечено
 * ничего — в том числе в группе «противоречит», где значение выбирает
 * человек, а не служба.
 *
 * Отметка «все» (просьба владельца на прогоне 14.09: щёлкать каждую строку
 * поодиночке — работа, а не решение) ставит галочки РАЗОМ, но только на
 * предложения с пустым «не закрыто». Помеченное — незакрытая обязательная
 * связь или правило образования — пакетом не идёт: сервер отказывает всему
 * пакету целиком (422) ещё до первого заведения, и одна такая строка увела
 * бы в отказ и все здоровые. Сколько их и почему они мимо пакета, сказано
 * рядом словами; решаются они по одной, в своей строке.
 */
/**
 * Раздача нужд по целям и сервисам — один вызов (решение владельца 17.09).
 * Чтение документа даёт цели и сервисы со ссылками не на все нужды, и сцены
 * 4 и 6 не закрываются содержанием. Здесь модель получает ПОЛНЫЕ перечни и
 * отдаёт карту связей; экран показывает её строками с причиной, а заводит
 * связи только клик «Принять связи» — и он обратим.
 */
function РаздачаНужд({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [раздача, setРаздача] = useState<DistributionRun | null>(null)
  const [идёт, setИдёт] = useState(false)
  const [секунды, setСекунды] = useState(0)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [ask, спросить, закрытьВопрос] = useConfirm()

  const перечитать = useCallback(() => {
    api.distribution(project)
      .then((р) => {
        if ('links' in р) {
          setРаздача(р)
          setОтмечены(р.links.filter((с) => кПриёмуЛи(с)).map((с) => с.id))
        } else {
          setРаздача(null)
        }
      })
      .catch((e) => setОтказ(отказПодробно(e)))
  }, [project])
  useEffect(перечитать, [перечитать])

  const раздать = () => {
    setИдёт(true); setОтказ(null); setИтог(null); setСекунды(1)
    const часы = window.setInterval(() => setСекунды((с) => с + 1), 1000)
    const кончить = () => { window.clearInterval(часы); setСекунды(0); setИдёт(false) }
    api.distributeNeeds(project)
      .then((р) => {
        кончить()
        setРаздача(р)
        setОтмечены(р.links.filter((с) => кПриёмуЛи(с)).map((с) => с.id))
        setИтог(р.note)
      })
      .catch((e) => { кончить(); setОтказ(отказПодробно(e)) })
  }

  const кПриёму = раздача ? раздача.links.filter(кПриёмуЛи) : []
  const принятые = раздача ? раздача.links.filter((с) => с.accepted) : []
  const выбрано = отмечены.filter((id) => кПриёму.some((с) => с.id === id))

  const принять = () => {
    if (!раздача || выбрано.length === 0) return
    const целей = выбрано.filter((id) => раздача.links.find((с) => с.id === id)?.kind === 'goal').length
    const сервисов = выбрано.filter((id) => раздача.links.find((с) => с.id === id)?.kind === 'service').length
    спросить({
      question: `Принять связей: ${выбрано.length} (к целям ${целей}, к сервисам ${сервисов}). `
        + 'Нужда без класса обслуживания получит класс покрывшего сервиса. '
        + 'Приём обратим: «Отменить раздачу» снимет связи и вернёт классы.',
      ok: 'Принять связи',
      input: { label: 'почему берём', placeholder: 'основание решения' },
      onOk: (повод) => api.acceptDistribution(project, раздача.run, выбрано, 'инженер', повод || undefined)
        .then((и) => { setИтог(и.note); onChanged(); перечитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  const отменить = () => {
    if (!раздача) return
    спросить({
      question: `Отменить раздачу ${раздача.run}: принятые связи (${принятые.length}) снимутся, классы вернутся как были.`,
      ok: 'Отменить раздачу',
      onOk: () => api.undoDistribution(project, раздача.run, 'инженер')
        .then((о) => { setИтог(о.note); onChanged(); перечитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  const строка = (с: DistributionLink) => (
    <tr key={с.id} className={!кПриёмуЛи(с) ? 'v2-dim' : undefined}>
      <td>
        {!кПриёмуЛи(с) ? (
          <span title={с.accepted ? 'связь принята этой раздачей' : 'связь уже есть в модели — второй раз не заводится'}
            data-why="почему-нельзя">{с.accepted ? 'принята' : 'уже есть'}</span>
        ) : (
          <input type="checkbox" checked={отмечены.includes(с.id)}
            aria-label={`отметить связь ${с.id}`}
            onChange={(e) => setОтмечены(e.target.checked
              ? [...отмечены, с.id]
              : отмечены.filter((к) => к !== с.id))} />
        )}
      </td>
      <td><span className="v2-mono">{с.need}</span><div>{с.need_text}</div></td>
      <td>
        <span className="v2-mono">{с.target}</span>
        <div>{с.kind === 'goal' ? 'цель' : 'сервис'}: {с.target_text}</div>
      </td>
      <td>
        {с.kind === 'service' ? (с.qos_class ?? '—') : ''}
        {с.exists && с.class_pending && !с.accepted && (
          <div className="v2-dim" title="связь уже есть, а класса у нужды нет: приём даст ей класс сервиса">
            связь есть · класс ждёт
          </div>
        )}
      </td>
      <td>{с.reason}</td>
    </tr>
  )

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Раздача нужд по целям и сервисам</span>
        <span className="v2-card__count" title="связей в последней раздаче">{раздача ? раздача.links.length : '—'}</span>
      </div>
      <div className="v2-empty__why">
        Сцена 4 закрывается, когда каждая нужда ведёт к цели; сцена 6 — когда каждая
        покрыта сервисом и несёт класс. Один вызов по полным перечням проекта; связи
        заводит только приём, и он обратим.
      </div>
      <div className="v2-form__actions">
        <button type="button" disabled={идёт}
          title={идёт ? 'раздача уже идёт: тот же перечень даст тот же ответ' : 'один вызов модели по всем нуждам, целям и сервисам проекта'}
          onClick={раздать}>
          {идёт ? `Раздаю… ${секунды} с` : 'Раздать нужды по целям и сервисам'}
        </button>
        {кПриёму.length > 0 && (
          <button type="button" className="v2-primary" disabled={выбрано.length === 0}
            title={выбрано.length === 0 ? 'отметьте связи — раздача сама ничего не заводит' : `принять ${выбрано.length} связей`}
            onClick={принять}>
            Принять связи ({выбрано.length})
          </button>
        )}
        {принятые.length > 0 && раздача && (
          <button type="button" className="v2-link" onClick={отменить}
            title="снять связи этой раздачи и вернуть классы как были">
            Отменить раздачу
          </button>
        )}
      </div>
      {отказ && <div className="v2-error">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      {раздача && (
        <>
          <div className="v2-note-line">
            <span className="v2-mono">{раздача.run}</span>
            {раздачаСловами(раздача)}
          </div>
          {раздача.unassigned.length > 0 && (
            <div className="v2-error">
              не раздано: {раздача.unassigned.join(', ')} — модели не к чему было отнести; свяжите на сценах 4 и 6 сами
            </div>
          )}
          {раздача.refused.length > 0 && (
            <ul className="v2-dim">{раздача.refused.map((р) => <li key={р}>{р}</li>)}</ul>
          )}
          {раздача.links.length > 0 && (
            <table className="v2-tab2">
              <thead>
                <tr><th /><th>Нужда</th><th>Ведёт к · покрыта</th><th>Класс</th><th>Почему</th></tr>
              </thead>
              <tbody>{раздача.links.map(строка)}</tbody>
            </table>
          )}
        </>
      )}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/** К приёму — новая связь либо лежащая связь сервиса у нужды без класса. */
function кПриёмуЛи(с: DistributionLink): boolean {
  return !с.accepted && (!с.exists || с.class_pending)
}

function раздачаСловами(р: DistributionRun): string {
  const части = [р.note]
  if (р.cached) части.push('ответ из журнала — живого вызова не было')
  return ' · ' + части.filter(Boolean).join(' · ')
}

function Постановка({ project, онтология, onChanged, expert = false }: {
  project: string
  онтология: FormationOntology | null
  onChanged: () => void
  /** Эксперт-режим: старый синтез из среза поля показывается только в нём. */
  expert?: boolean
}) {
  const [диф, setДиф] = useState<SynthesisDiffView | null>(null)
  const [занято, setЗанято] = useState(false)
  const [состояние, setСостояние] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [итог, setИтог] = useState<SynthesisAccepted | null>(null)
  const [ask, спросить, закрытьВопрос] = useConfirm()
  /**
   * Порог уверенности для «кроме требующих внимания». В истине его нет —
   * значит, он не прячется в коде: число стоит на экране, человек его видит и
   * меняет. Половина — отправная точка, а не правило.
   */
  const [порог, setПорог] = useState(0.5)
  /**
   * Правки предложений до приёма: {предложение: {поле: значение}}. Владелец
   * 18.09: «во вкладке Постановка нет редактируемых полей» — предложение не
   * приговор, поля правятся здесь и уезжают вместе с приёмом. Что можно
   * править и какие значения перечислены, говорит истина схем (`kind`).
   */
  const [правки, setПравки] = useState<Record<string, Record<string, string>>>({})


  const прочитать = useCallback(() => {
    api.synthesisDiff(project).then(setДиф).catch((e) => setОтказ(отказПодробно(e)))
  }, [project])

  useEffect(прочитать, [прочитать])

  // Синтез — фоновой задачей (ADR-069): опрос раз в три секунды, готовый
  // ответ применяется при опросе. Повтор по неизменённому полю приходит
  // сразу готовым и с пометой «живого вызова не было».
  const применить = (з: SynthesisRun): void => {
    if (з.status === 'done') {
      setДиф(з)
      setОтмечены([])
      setЗанято(false)
      setСостояние(з.cached
        ? `ответ взят по тому же срезу поля — живого вызова не было${з.note ? ` · ${з.note}` : ''}`
        : з.note)
      onChanged()
      return
    }
    if (з.status === 'error') {
      setОтказ(з.error || 'синтез не удался')
      setЗанято(false)
      return
    }
    setСостояние(`синтез идёт задачей ${з.id} — стенд отвечает, страницу можно не держать`)
    window.setTimeout(() => {
      api.synthesisState(project, з.id).then(применить)
        .catch((e) => { setОтказ(отказПодробно(e)); setЗанято(false) })
    }, 3000)
  }

  const сформировать = () => {
    setЗанято(true)
    setОтказ(null)
    setИтог(null)
    setСостояние(null)
    api.synthesize(project, 'manual', 'инженер').then(применить)
      .catch((e) => { setОтказ(отказПодробно(e)); setЗанято(false) })
  }

  const запуск = диф !== null && 'id' in диф ? диф : null
  const пусто = диф !== null && !('id' in диф) ? диф : null
  const нота = (понятие: string) => онтология?.concepts.find((к) => к.code === понятие)?.note ?? ''

  // Предложения всех четырёх групп одним списком: отметка «все» смотрит на
  // весь диф, а не на одну карточку — человек отмечает разом то, что и
  // показано ему разом.
  const группы = запуск?.diff
  const предложения: FormationProposal[] = группы ? ГРУППЫ.flatMap(([ключ]) => группы[ключ]) : []
  // Пакетом идёт ТОЛЬКО предложение с пустым «не закрыто». Непустое — это
  // незакрытая обязательная связь либо помета правила образования (прогон
  // 14.09), и сервер отказывает ВСЕМУ пакету целиком ещё до первого
  // заведения: одна помеченная строка увела бы в отказ и все здоровые. Такие
  // решаются по одной, в своей строке, — поэтому отметка «все» их не берёт.
  const пакетные = предложения.filter((п) => п.missing.length === 0).map((п) => п.proposal)
  const сПометой = предложения.filter((п) => п.missing.length > 0).length

  /**
   * Требует внимания — то, что человек обязан решить сам, а не отдать пакету.
   *
   * Два признака — из истины: незакрытая обязательная связь (`must_link`) и
   * противоречие (сверка показывает ОБА значения, победителя выбирает
   * человек). Третий — уверенность ниже порога; порога уверенности в истине
   * нет, поэтому он ЗДЕСЬ, на экране, и его видно и правит человек: прятать
   * выдуманное число в коде нельзя.
   */
  const требуетВнимания = (п: FormationProposal): boolean =>
    п.missing.length > 0 || п.verdict === 'contradict'
    || (typeof п.confidence === 'number' && п.confidence < порог)
  const спокойные = предложения.filter((п) => !требуетВнимания(п))
  const внимание = предложения.filter(требуетВнимания)
  /** Предпросмотр одной строкой: что появится, если нажать сейчас. */
  const появится = (коды: string[]): string => {
    const счёт = new Map<string, number>()
    предложения.filter((п) => коды.includes(п.proposal))
      .forEach((п) => счёт.set(п.concept, (счёт.get(п.concept) ?? 0) + 1))
    const части = [...счёт.entries()].map(([к, n]) => `${n} ${ПОНЯТИЕ_МН[к] ?? к}`)
    return части.length === 0 ? 'ничего' : части.join(', ')
  }
  const всеОтмечены = пакетные.length > 0 && пакетные.every((код) => отмечены.includes(код))
  /** Все строки дифа по порядку групп — по ним ходят стрелки и работает Shift-диапазон. */
  const подряд: FormationProposal[] = [...внимание, ...спокойные]
  const решена = (п: FormationProposal) => Boolean(п.decision)
  const отложенные = предложения.filter((п) => п.decision === 'deferred').length
  const отклонённые = предложения.filter((п) => п.decision === 'rejected').length

  /** Умный набор: отмечает разом то, что названо словами, и ничего не заводит. */
  const наборы: { имя: string; коды: string[]; зачем: string }[] = [
    {
      имя: 'всё, кроме требующих внимания',
      коды: спокойные.filter((п) => !решена(п)).map((п) => п.proposal),
      зачем: 'противоречия, незакрытые связи и низкая уверенность остаются вам',
    },
    {
      имя: 'только новое',
      коды: спокойные.filter((п) => п.verdict === 'new' && !решена(п)).map((п) => п.proposal),
      зачем: 'в постановке такого ещё нет — заведётся как новое',
    },
    {
      имя: 'только дополнения',
      коды: спокойные.filter((п) => п.verdict === 'augment' && !решена(п)).map((п) => п.proposal),
      зачем: 'принятое остаётся как есть, поле добавляет к нему поле-другое',
    },
  ]
  const наборыПоВиду = [...new Set(спокойные.filter((п) => !решена(п)).map((п) => п.concept))]
    .map((вид) => ({
      имя: ПОНЯТИЕ_МН[вид] ?? вид,
      коды: спокойные.filter((п) => п.concept === вид && !решена(п)).map((п) => п.proposal),
    }))
    .filter((н) => н.коды.length > 0)

  /** Отметить диапазон: Shift-клик от последней отметки до нажатой строки. */
  const [последняя, setПоследняя] = useState<string | null>(null)
  const отметить = (код: string, да: boolean, диапазоном = false) => {
    if (диапазоном && последняя) {
      const от = подряд.findIndex((п) => п.proposal === последняя)
      const до = подряд.findIndex((п) => п.proposal === код)
      if (от >= 0 && до >= 0) {
        // Границы диапазона — номера строк на экране, не величины модели.
        const начало = от < до ? от : до
        const конец = от < до ? до : от
        const кусок = подряд.slice(начало, конец + 1).map((п) => п.proposal)
        setОтмечены((было) => [...new Set(да ? [...было, ...кусок] : было.filter((к) => !кусок.includes(к)))])
        setПоследняя(код)
        return
      }
    }
    setОтмечены((было) => (да ? [...new Set([...было, код])] : было.filter((к) => к !== код)))
    setПоследняя(код)
  }

  /** Решение по отмеченным: отклонить, отложить, вернуть в работу. */
  const решить = (что: 'rejected' | 'deferred' | 'pending') => {
    if (!запуск || отмечены.length === 0) return
    api.declineSynthesis(project, запуск.id, отмечены, что, 'инженер')
      .then((о) => { setСостояние(о.note); setОтмечены([]); прочитать() })
      .catch((e) => setОтказ(отказПодробно(e)))
  }

  /** Строка под фокусом клавиатуры: ↑↓ ходят по ней, Space отмечает. */
  const [фокус, setФокус] = useState(0)
  const клавиша = (е: KeyboardEvent<HTMLDivElement>) => {
    if (предложения.length === 0) return
    const последний = подряд.length - 1
    const строка = подряд[фокус > последний ? последний : фокус]
    const шаг = (куда: number) => {
      // Индекс строки — не величина модели: клиент двигает курсор, а не считает.
      const сырой = фокус + куда
      const новый = сырой < 0 ? 0 : сырой > последний ? последний : сырой
      setФокус(новый)
      document.getElementById(`предложение-${подряд[новый]?.proposal}`)?.scrollIntoView({ block: 'nearest' })
    }
    switch (е.key) {
      case 'ArrowDown': е.preventDefault(); шаг(1); break
      case 'ArrowUp': е.preventDefault(); шаг(-1); break
      case ' ': if (строка) { е.preventDefault(); отметить(строка.proposal, !отмечены.includes(строка.proposal), е.shiftKey) } break
      case 'Enter': е.preventDefault(); if (отмечены.length > 0) принять(); break
      case 'a': case 'A': case 'ф': case 'Ф':
        е.preventDefault(); setОтмечены(наборы[0].коды); break
      case 'r': case 'R': case 'к': case 'К':
        е.preventDefault(); if (отмечены.length > 0) решить('rejected'); break
      case 'l': case 'L': case 'д': case 'Д':
        е.preventDefault(); if (отмечены.length > 0) решить('deferred'); break
      default: break
    }
  }
  // Ф-11: неактивная отметка обязана назвать причину И путь оживления —
  // серая галочка без объяснения оставляет человека гадать.
  const подсказкаВсе = пакетные.length === 0
    ? предложения.length === 0
      ? 'отмечать нечего: в дифе нет ни одного предложения — сформируйте постановку из поля'
      : 'пакетом не идёт ни одно предложение: у каждого стоит строка «не закрыто» — '
        + 'закройте обязательную связь или поправьте основание в строке, и отметка оживёт'
    : всеОтмечены
      ? 'снять все отметки'
      : `отметить разом предложения с пустым «не закрыто»: ${пакетные.length}. `
        + 'Помеченные отметка не берёт и уже отмеченное с пометой снимает: сервер откажет пакету целиком'

  /**
   * Принять всё, кроме требующих внимания. 91 предложение по одному не
   * принимается; а противоречия и незакрытые связи в пакет не идут и остаются
   * человеку — отдельной группой сверху.
   */
  const принятьСпокойные = () => {
    if (!запуск || спокойные.length === 0) return
    const коды = спокойные.map((п) => п.proposal)
    спросить({
      question: `Выбрано ${коды.length} → появится ${появится(коды)}.`
        + (внимание.length > 0 ? ` Требуют внимания: ${внимание.length} — они остаются вам.` : '')
        + ' Приём обратим целиком: «Отменить пакет» вернёт всё.',
      ok: 'Принять пакет',
      input: { label: 'почему берём', placeholder: 'основание решения' },
      onOk: (повод) => api.acceptSynthesis(project, запуск.id, коды, 'инженер', повод || undefined, undefined, правкиДля(коды))
        .then((и) => { setИтог(и); setОтмечены([]); setПравки({}); onChanged(); прочитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  /** Коды заведённых записей, отмеченные к снятию принятия (выборкой). */
  const [снятие, setСнятие] = useState<string[]>([])
  const снятьВыборку = () => {
    if (!запуск || снятие.length === 0) return
    спросить({
      question: `Снять принятие с ${снятие.length}: эти записи уйдут с учёта, остальное в пакете останется.`,
      ok: 'Снять принятие',
      onOk: () => api.undoSynthesis(project, запуск.id, 'инженер', снятие)
        .then((о) => { setСостояние(о.note); setСнятие([]); onChanged(); прочитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  /** Отменить пакет: заведённое снимается с учёта, факты остаются. */
  const отменитьПакет = () => {
    if (!запуск) return
    спросить({
      question: `Отменить принятый пакет запуска ${запуск.id}: заведённое снимется с учёта.`
        + ' Факты-основания останутся — они след документа, а не решение человека.',
      ok: 'Отменить пакет',
      onOk: () => api.undoSynthesis(project, запуск.id, 'инженер')
        .then((о) => { setСостояние(о.note); setИтог(null); onChanged(); прочитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  /** Истина схем о виде понятия: поля, обязательные и перечни значений. */
  const видПонятия = (понятие: string) => онтология?.concepts.find((к) => к.code === понятие)?.kind

  /** Правки только выбранных строк: чужих в приём не отправляем. */
  const правкиДля = (коды: string[]): Record<string, Record<string, string>> | undefined => {
    const свои = Object.fromEntries(
      Object.entries(правки).filter(([код, поля]) => коды.includes(код) && Object.keys(поля).length > 0),
    )
    return Object.keys(свои).length === 0 ? undefined : свои
  }

  const правитьПоле = (предложение: string, поле: string, значение: string) => {
    setПравки((было) => ({ ...было, [предложение]: { ...(было[предложение] ?? {}), [поле]: значение } }))
    setОтмечены((было) => (было.includes(предложение) ? было : [...было, предложение]))
  }

  /**
   * Поля строки правки: что предложение уже несёт плюс обязательные поля
   * вида, которых у него нет (их и спрашивает своя сцена). Величины и
   * поля-ссылки правятся не здесь — у них своя форма на сцене.
   */
  /**
   * Что спрашивается НА ПРИЁМЕ. Истина схем 18.09 (`required_at`) называет
   * стадию и того, кто заполняет: `accept` — человек здесь, `accept:system ·
   * from_basis · auto · default(x)` — подставит система, `baseline` и точки —
   * своя сцена. Форма приёма из всей схемы (журнал ПМИ-7, З-09: «все поля
   * обязательны… работать нельзя») кончилась: спрашиваем ровно своё.
   */
  const поляПравки = (п: FormationProposal): string[] => {
    const понятие = онтология?.concepts.find((к) => к.code === п.concept)
    const вид = понятие?.kind
    const системные = понятие?.system_fields ?? []
    const стадия = вид?.required_at ?? {}
    const наПриёме = (поле: string) => {
      const правило = стадия[поле]
      if (!правило) return true
      const [когда, чем] = [правило.split(':')[0].trim(), (правило.split(':')[1] ?? '').trim()]
      return когда === 'accept' && чем === ''
    }
    const свои = Object.keys(п.payload).filter((поле) => !системные.includes(поле) && наПриёме(поле))
    const надо = (вид?.required ?? []).filter((поле) => !свои.includes(поле)
      && !системные.includes(поле) && наПриёме(поле)
      && !(вид?.measures ?? []).includes(поле) && !(вид?.fact_refs ?? []).includes(поле))
    return [...свои, ...надо]
  }

  /** Что доделается позже и где — строкой под полями приёма, именами истины. */
  const позже = (п: FormationProposal): string => {
    const понятие = онтология?.concepts.find((к) => к.code === п.concept)
    const вид = понятие?.kind
    if (!вид) return ''
    const поСтадиям = new Map<string, string[]>()
    Object.entries(вид.required_at ?? {}).forEach(([поле, правило]) => {
      const когда = правило.split(':')[0].trim()
      const чем = (правило.split(':')[1] ?? '').trim()
      if (когда === 'accept' && чем === '') return
      const где = когда === 'accept'
        ? 'подставит система'
        : когда === 'baseline' ? 'к базированию' : `к точке ${когда}`
      поСтадиям.set(где, [...(поСтадиям.get(где) ?? []), вид.labels?.[поле] ?? поле])
    })
    return [...поСтадиям.entries()].map(([где, поля]) => `${где}: ${поля.join(' · ')}`).join(' · ')
  }

  const принять = () => {
    if (!запуск) return
    спросить({
      question: `Принять отмеченные предложения: ${отмечены.length}.`
        + (сПометой > 0 ? ` Мимо пакета: ${сПометой} — со строкой «не закрыто».` : '')
        + ' Заводится только названное '
        + 'новым — узнанное принятое останется решением человека в сверке.',
      ok: 'Принять',
      input: { label: 'почему берём', placeholder: 'основание решения' },
      onOk: (повод) => api.acceptSynthesis(project, запуск.id, отмечены, 'инженер', повод || undefined, undefined, правкиДля(отмечены))
        .then((и) => { setИтог(и); setОтмечены([]); setПравки({}); onChanged(); прочитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  return (
    // Клавиатура проходит экран без мыши (экран 12): фокус на контейнере,
    // строки — по ↑↓, отметка — Space, приём — Enter.
    <div className="v2-kf__src" data-why="работа" tabIndex={0} onKeyDown={клавиша}
      aria-label="предложения постановки: клавиши ↑ ↓ Space Enter A R L">
      <РаздачаНужд project={project} onChanged={onChanged} />
      <div className="v2-form__actions">
        {/*
          Старый синтез из СРЕЗА поля — только в эксперт-режиме (ПМИ-7,
          предусловия): владелец жал его вместо чтения документа и получал
          26 предложений из 50 элементов среза. Путь по умолчанию —
          «Прочитать документ» в поле знаний.
        */}
        {expert && (
          <button type="button" disabled={занято}
            title={занято
              ? 'синтез уже идёт: второй запуск того же среза даст тот же ответ'
              : 'эксперт-режим: пройти по срезу поля (потолок 50 элементов) и предложить постановку. Обычный путь — «Прочитать документ» на вкладке поля'}
            onClick={сформировать}>
            {занято ? 'Формирую…' : 'Сформировать постановку из среза поля (эксперт)'}
          </button>
        )}
        {предложения.length > 0 && (
          <>
            <button type="button" className="v2-primary" disabled={занято || спокойные.length === 0}
              title={спокойные.length === 0
                ? 'спокойных предложений нет: каждое требует вашего решения — противоречие, незакрытая связь или низкая уверенность'
                : `принять пакетом ${спокойные.length}; ${внимание.length} останутся вам`}
              onClick={принятьСпокойные}>
              Принять все, кроме требующих внимания
            </button>
            <label className="v2-dim" title="порога уверенности в истине нет — он здесь, на экране, и его правит человек">
              порог уверенности
              <input type="number" min={0} max={1} step={0.05} value={порог}
                onChange={(e) => setПорог(Number(e.target.value))}
                style={{ width: '4.5rem', marginLeft: '0.4rem' }} />
            </label>
            <button type="button" disabled={занято}
              title="приём обратим целиком: заведённое снимется с учёта, факты-основания останутся"
              onClick={отменитьПакет}>
              Отменить пакет
            </button>
            {итог && итог.created.length > 0 && (
              <button type="button" disabled={занято || снятие.length === 0}
                title={снятие.length === 0
                  ? 'снимать нечего: отметьте заведённые коды в списке ниже'
                  : `снять принятие с ${снятие.length}: остальное в пакете останется`}
                onClick={снятьВыборку}>
                Снять принятие ({снятие.length})
              </button>
            )}
          </>
        )}
        {состояние && <span className="v2-dim">{состояние}</span>}
      </div>

      {предложения.length > 0 && (
        <>
          <div className="v2-form v2-form--row" data-why="следующий-клик">
            <span className="v2-dim">умные наборы:</span>
            {наборы.filter((н) => н.коды.length > 0).map((н) => (
              <button key={н.имя} type="button" className="v2-chip"
                title={`${н.зачем}; отметит ${н.коды.length} — ничего не заводя`}
                onClick={() => setОтмечены(н.коды)}>
                {н.имя} ({н.коды.length})
              </button>
            ))}
            {наборыПоВиду.length > 1 && (
              <label className="v2-inline" title="отметить разом все спокойные строки одного вида">
                по виду
                <select value="" aria-label="умный набор по виду"
                  onChange={(e) => {
                    const выбран = наборыПоВиду.find((н) => н.имя === e.target.value)
                    if (выбран) setОтмечены(выбран.коды)
                  }}>
                  <option value="">— выберите —</option>
                  {наборыПоВиду.map((н) => <option key={н.имя} value={н.имя}>{н.имя} ({н.коды.length})</option>)}
                </select>
              </label>
            )}
            <button type="button" className="v2-link" disabled={отмечены.length === 0}
              title={отмечены.length === 0 ? 'сначала отметьте строки — снимать нечего' : 'снять все отметки'}
              onClick={() => setОтмечены([])}>
              снять отметки
            </button>
            <button type="button" disabled={отмечены.length === 0}
              title={отмечены.length === 0
                ? 'сначала отметьте строки: отклоняются отмеченные'
                : `отклонить ${отмечены.length}: больше не предлагаются, решение обратимо`}
              onClick={() => решить('rejected')}>
              Отклонить отмеченные
            </button>
            <button type="button" disabled={отмечены.length === 0}
              title={отмечены.length === 0
                ? 'сначала отметьте строки: откладываются отмеченные'
                : `отложить ${отмечены.length}: воротам не мешает, видно счётчиком`}
              onClick={() => решить('deferred')}>
              Отложить отмеченные
            </button>
          </div>
          <div className="v2-note-line" data-why="следующий-клик">
            {`выбрано ${отмечены.length} из ${предложения.length} → появится ${появится(отмечены)}`}
            {внимание.length > 0 && `; требуют внимания ${внимание.length} — в пакет не идут`}
            {` · спокойных ${спокойные.length}`}
            {отложенные > 0 && ` · отложено ${отложенные}`}
            {отклонённые > 0 && ` · отклонено ${отклонённые}`}
          </div>
          <div className="v2-dim">
            клавиши: ↑ ↓ — строка, Space — отметить (с Shift — диапазон), Enter — принять отмеченные,
            A — набор «всё, кроме требующих внимания», R — отклонить, L — отложить
          </div>
        </>
      )}

      {отказ && <div className="v2-locked">{отказ}</div>}

      {итог && (
        <div className="v2-empty__why">
          {итог.note}{` · заведено: ${итог.accepted}`}
          {итог.created.length > 0 && (
            <ul className="v2-list">
              {итог.created.map((код) => (
                <li key={код}>
                  <label className="v2-check" title="отметить, чтобы снять принятие с этой записи">
                    <input type="checkbox" checked={снятие.includes(код)}
                      aria-label={`снять принятие с ${код}`}
                      onChange={(e) => setСнятие(e.target.checked
                        ? [...снятие, код]
                        : снятие.filter((к) => к !== код))} />
                    <span className="v2-mono">{код}</span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          {итог.pending.length > 0 && (
            <ul className="v2-list">
              {итог.pending.map((ж) => (
                <li key={ж.proposal}>
                  <span className="v2-mono">{ж.proposal}</span>
                  {` — ${ж.verdict}: узнанное принятое решает человек в сверке`}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {пусто && (
        <div className="v2-empty">
          {пусто.note}
          <span className="v2-empty__why">
            Постановка собирается по всему полю, а не по последнему источнику.
          </span>
        </div>
      )}

      {запуск && (
        <>
          <div className="v2-note-line">
            <span className="v2-mono">{запуск.id}</span>
            {` · в срезе элементов: ${запуск.slice_size}`}
            {запуск.cached && ' · ответ по тому же срезу: живого вызова не было'}
            {запуск.note ? ` · ${запуск.note}` : ''}
          </div>

          {внимание.length > 0 && (
            <div className="v2-card" data-why="почему-нельзя">
              <div className="v2-card__head">
                <span className="v2-card__title">Требует внимания</span>
                <span className="v2-card__count">{внимание.length}</span>
              </div>
              <div className="v2-empty__why">
                Эти строки в пакет не идут никогда: противоречие, незакрытая обязательная связь или
                уверенность ниже порога. Каждая решается своей строкой — ниже, в своей группе.
              </div>
              <ul className="v2-why">
                {внимание.map((п) => (
                  <li key={п.proposal}>
                    <span className="v2-mono">{п.proposal}</span> {ПОНЯТИЕ[п.concept] ?? п.concept}
                    {' — '}
                    {п.missing.length > 0
                      ? `не закрыто: ${п.missing.join(', ')}`
                      : п.verdict === 'contradict'
                        ? `противоречие${п.diff_field ? ` по полю «${п.diff_field}»` : ''}: победителя выбирает человек`
                        : `уверенность ниже порога ${порог}`}
                    {п.decision === 'deferred' && <span className="v2-dim"> · отложено</span>}
                    {п.decision === 'rejected' && <span className="v2-dim"> · отклонено</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {ГРУППЫ.map(([ключ, слово, зачем]) => {
            const строки = запуск.diff ? запуск.diff[ключ] : []
            return (
              <div key={ключ} className="v2-card">
                <div className="v2-card__head">
                  <span className="v2-card__title">{слово}</span>
                  <span className="v2-card__count" title="счёт группы считает сервер">
                    {запуск.counts ? запуск.counts[ключ] : '—'}
                  </span>
                </div>
                <div className="v2-empty__why">{зачем}</div>
                {строки.length === 0 ? (
                  <div className="v2-empty">в этой группе пусто</div>
                ) : (
                  <table className="v2-tab2">
                    <thead>
                      <tr>
                        <th />
                        <th>Что предлагается</th>
                        <th>Чем отличается</th>
                        <th>Основания</th>
                        <th>Не закрыто</th>
                      </tr>
                    </thead>
                    <tbody>
                      {строки.map((п: FormationProposal) => (
                        <tr key={п.proposal} id={`предложение-${п.proposal}`}
                          className={подряд[фокус]?.proposal === п.proposal ? 'v2-row--open' : undefined}>
                          <td>
                            <input type="checkbox" checked={отмечены.includes(п.proposal)}
                              aria-label={`отметить предложение ${п.proposal}`}
                              title={п.decision === 'rejected'
                                ? 'строка отклонена: отметьте и верните в работу, если решение изменилось'
                                : п.decision === 'deferred'
                                  ? 'строка отложена: воротам не мешает, вернуть в работу можно отметкой'
                                  : п.missing.length === 0
                                    ? 'отметить это предложение: заводит его клик по «Принять отмеченные»; Shift — диапазон'
                                    : 'у предложения стоит «не закрыто»: отметка «все» его не берёт, '
                                      + 'а в пакете оно уведёт в отказ и здоровые строки — закройте связь '
                                      + 'или поправьте основание'}
                              onChange={(e) => отметить(п.proposal, e.target.checked)}
                              onClick={(e) => { if (e.shiftKey) отметить(п.proposal, !отмечены.includes(п.proposal), true) }} />
                            {п.decision === 'deferred' && <div className="v2-dim">отложено</div>}
                            {п.decision === 'rejected' && <div className="v2-dim">отклонено</div>}
                          </td>
                          <td>
                            <span title={нота(п.concept)}>{ПОНЯТИЕ[п.concept] ?? п.concept}</span>
                            {/*
                              Поля предложения — правимые до приёма (владелец
                              18.09). Перечень значений и обязательность берутся
                              из истины схем, а не из догадки экрана; правка
                              уезжает вместе с приёмом и проверяется сервером.
                            */}
                            <div className="v2-form">
                              {поляПравки(п).map((поле) => {
                                const вид = видПонятия(п.concept)
                                const перечень = вид?.enums?.[поле] ?? []
                                const текущее = правки[п.proposal]?.[поле] ?? п.payload[поле] ?? ''
                                const обязательно = (вид?.required ?? []).includes(поле)
                                return (
                                  <label key={поле} className="v2-dim">
                                    {вид?.labels?.[поле] ?? ПОЛЕ[поле] ?? поле}{обязательно && !текущее ? ' · обязательно' : ''}
                                    {перечень.length > 0 ? (
                                      /*
                                        Значения перечисления — по-русски из истины 19.09
                                        (`enum_labels`): «enum → селект русскими значениями».
                                        Своего перевода у экрана нет; неназванное значение
                                        показывается кодом — это видно и правится в истине.
                                      */
                                      <select value={текущее} name={`${п.proposal}.${поле}`}
                                        onChange={(e) => правитьПоле(п.proposal, поле, e.target.value)}>
                                        <option value="">— не задано —</option>
                                        {перечень.map((з) => (
                                          <option key={з} value={з}>{вид?.enum_labels?.[поле]?.[з] ?? з}</option>
                                        ))}
                                      </select>
                                    ) : текущее.length > 90 ? (
                                      /*
                                        Длинное значение — в поле на несколько строк: замысел
                                        несёт четыре абзаца, и одной строкой они слипались в
                                        стену (владелец 18.09). autoComplete выключен у всех:
                                        браузер подставлял в безымянные поля телефон из своей
                                        памяти — «система подставила номер куда только смогла».
                                      */
                                      <textarea rows={3} value={текущее} name={`${п.proposal}.${поле}`}
                                        autoComplete="off" spellCheck={false}
                                        onChange={(e) => правитьПоле(п.proposal, поле, e.target.value)} />
                                    ) : (
                                      <input value={текущее} name={`${п.proposal}.${поле}`}
                                        autoComplete="off" spellCheck={false}
                                        onChange={(e) => правитьПоле(п.proposal, поле, e.target.value)} />
                                    )}
                                  </label>
                                )
                              })}
                              {позже(п) && <div className="v2-dim">{позже(п)}</div>}
                            </div>
                            {п.target_ref && (
                              <div className="v2-dim">
                                о принятом <span className="v2-mono">{п.target_ref}</span>
                              </div>
                            )}
                          </td>
                          <td>
                            {п.diff_field ? ПОЛЕ[п.diff_field] ?? п.diff_field : '—'}
                            {п.rank_hint && <div className="v2-dim">{п.rank_hint}</div>}
                          </td>
                          <td>
                            {п.basis.length === 0 ? (
                              <span className="v2-warn">без документального основания</span>
                            ) : п.basis.map((о, номер) => (
                              <div key={`${п.proposal}-${номер}`} className="v2-dim">
                                <span className="v2-mono">{о.fact}</span>
                                {о.anchor && <span className="v2-mono">{` · ${о.anchor}`}</span>}
                                {о.account && ` · эксперт: ${о.account}${о.role ? `, ${о.role}` : ''}`
                                  + `${о.at ? `, ${о.at}` : ''}`}
                                {` · ранг: ${о.rank_word ?? рангСловами(о.rank)}`}
                              </div>
                            ))}
                          </td>
                          <td>
                            {п.missing.length === 0 ? '—' : п.missing.map((строка) => (
                              <div key={строка} className="v2-warn">
                                {/* В «не закрыто» лежат ДВЕ разные вещи: имя незакрытой
                                    связи понятия (токен без пробелов) и целая фраза
                                    правила образования. Одевать фразу в слова про связь
                                    значило бы соврать о причине. */}
                                {строка.includes(' ')
                                  ? строка
                                  : <>связь <span className="v2-mono">{строка}</span> не закрыта: сущности не будет</>}
                              </div>
                            ))}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )
          })}

          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занято || отмечены.length === 0}
              title={отмечены.length === 0
                ? 'отметьте предложения: синтез сам не заводит ничего'
                : 'принять отмеченное через сверку — теми же четырьмя вопросами, что и ручной ввод'}
              onClick={принять}>
              {`Принять отмеченные: ${отмечены.length}`}
            </button>
            <label className="v2-check" title={подсказкаВсе}>
              <input type="checkbox" checked={всеОтмечены} disabled={пакетные.length === 0}
                aria-label="отметить разом предложения с пустым «не закрыто»"
                onChange={() => setОтмечены(всеОтмечены ? [] : пакетные)} />
              {' '}все
            </label>
            {сПометой > 0 && (
              <span className="v2-dim"
                title={'строка «не закрыто» — это либо незакрытая обязательная связь понятия, '
                  + 'либо помета правила образования: факт не даёт того, чем понятие узнаётся. '
                  + 'Закройте связь или поправьте основание в самой строке — и предложение пойдёт со всеми'}>
                {`мимо пакета: ${сПометой} — со строкой «не закрыто»: такие принимаются по одному`}
              </span>
            )}
            {отмечены.length > 0 && (
              <button type="button" className="v2-link" title="снять все отметки"
                onClick={() => setОтмечены([])}>снять отметки</button>
            )}
          </div>
        </>
      )}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/** Предмет факта для свёртки: субъект утверждения; без субъекта — материал. */
export function предметФакта(ф: { subject?: string; material?: string; manual?: boolean }): string {
  const с = (ф.subject ?? '').trim()
  if (с) return с
  return ф.manual ? 'ручной ввод' : (ф.material ?? '—')
}

/** Группы фактов по предмету — в порядке появления, с числом нерешённых. */
export function группыФактов<T extends { subject?: string; material?: string; manual?: boolean; disposition?: string }>(
  факты: T[],
): { предмет: string; факты: T[]; нерешено: number }[] {
  const по = new Map<string, T[]>()
  факты.forEach((ф) => {
    const п = предметФакта(ф)
    const с = по.get(п)
    if (с) с.push(ф); else по.set(п, [ф])
  })
  return [...по.entries()].map(([предмет, список]) => ({
    предмет, факты: список, нерешено: список.filter((ф) => (ф.disposition ?? 'free') === 'free').length,
  }))
}
