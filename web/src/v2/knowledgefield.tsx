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
import { useCallback, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { ResearchPanel, отказСловами } from './research'
import {
  ServerRefusal, api,
  type Authority, type FactRow, type FactSourceView, type FieldDrift,
  type FormationOntology, type FormationProposal, type ReconcileAction,
  type ReconcileFinding, type ReconcileItem, type ReconcileRun, type ResearchTask,
  type SynthesisAccepted, type SynthesisDiff, type SynthesisDiffView, type SynthesisRun,
  type TorAssessment,
} from './api'

/** Диспозиции по-русски: служебное имя инженеру ничего не говорит. */
const РЕШЕНИЕ: Record<string, string> = {
  free: 'свободен',
  noted: 'рассмотрен',
  assumed: 'принят допущением',
  adopted: 'принят',
  rejected: 'отклонён',
  contested: 'оспорен',
  superseded: 'вытеснен',
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
 * Ранг доверия источника словами (истина схем `material.authority`). Ранг
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
}

/** Поля понятий словами: отличие называется полем, а не «похоже». */
const ПОЛЕ: Record<string, string> = {
  name: 'название',
  role: 'роль',
  interest: 'интерес',
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

/**
 * Действия сверки словами. Перечень действий — серверный (сервер шлёт код
 * действия в `offers`), слово к коду живёт одной таблицей здесь: вторых
 * формулировок для того же действия в продукте не заводим.
 */
const ДЕЙСТВИЕ: Record<string, string> = {
  accept_new: 'принять новым',
  merge_into: 'слить',
  refine: 'уточнение',
  generalize: 'обобщить',
  link_basis: 'привязать основание',
  mark_contested: 'оспорить',
  fix_input: 'исправить ввод',
  dismiss: 'снять находку',
}

/**
 * Куда ложится ручной ввод эксперта — поля ИСТИНЫ онтологии формирования, а
 * не выдуманные. `изПредмета` — понятие зовётся именем (сторона, сервис,
 * веха, норматив), и предмет ввода и есть это имя; `ссылка` — обязательная
 * связь понятия, которую предмет закрывает (нужда носит сторону).
 */
const ПОЛЯ_ПОНЯТИЯ: Record<string, {
  текст: string; изПредмета?: boolean; величина?: string; ссылка?: string
}> = {
  stakeholder: { текст: 'name', изПредмета: true },
  need: { текст: 'statement', ссылка: 'stakeholder' },
  goal: { текст: 'statement', величина: 'measure' },
  service: { текст: 'name', изПредмета: true, величина: 'target_measure' },
  constraint: { текст: 'statement', величина: 'bound' },
  assumption: { текст: 'statement' },
  milestone: { текст: 'name', изПредмета: true },
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
): Record<string, unknown> {
  const поля = ПОЛЯ_ПОНЯТИЯ[понятие] ?? { текст: 'statement' }
  const содержимое: Record<string, unknown> = {
    [поля.текст]: формулировкаКандидата(понятие, предмет, утверждение),
  }
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

/** Содержимое предложения словами: поля по-русски, значения — как пришли. */
function содержимоеСловами(payload: Record<string, string>): string {
  return Object.entries(payload)
    .filter(([, з]) => String(з).trim() !== '')
    .map(([к, з]) => `${ПОЛЕ[к] ?? к}: ${з}`)
    .join(' · ')
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
type Действие = { index: number; target_kind: string; scene: string; title: string; preview: string; facts: string[] }

export function KnowledgeField({ project }: { project: string | null }) {
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
  const [рукой, setРукой] = useState(false)
  const [план, setПлан] = useState<{ task: string; note: string; actions: Действие[]; assessment?: TorAssessment } | null>(null)
  const [выбраны, setВыбраны] = useState<number[]>([])
  const [занято, setЗанято] = useState(false)
  // Допущение ставится с владельцем, точкой и способом проверки — иначе к
  // точке его никто не подтвердит (истина схем: assumption при assumed).
  const [допущение, setДопущение] = useState<{ факт: string; owner: string; confirm_by: string; validation: string; impact: string } | null>(null)
  const [адресТемы, setАдресТемы] = useState('')
  // Поле знаний v2 — состояния объявлены ДО ранних возвратов (порядок хуков
  // стережёт CI): вкладка, дрейф поля, онтология, задачи исследования и окно
  // подтверждения. Признак самого поля знаний v2 даёт сервер, а не догадка.
  const [вкладка, setВкладка] = useState<'поле' | 'постановка'>('поле')
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
      if (фильтр === 'из исследований') return ф.authority === 'doubtful'
      return true
    })
  const ручных = все.filter((ф) => ф.manual).length
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

  const принятьПлан = () => {
    if (!план || выбраны.length === 0) return
    setЗанято(true)
    api.acceptPlan(project, план.task, выбраны, 'инженер')
      .then(() => { setПлан(null); setВыбраны([]); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

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
        <Постановка project={project} онтология={онтология} onChanged={перечитать} />
      )}

      {поле && вход && (
        <Source project={project}
          onParsed={(итог) => {
            перечитать()
            if (итог.task) {
              api.taskPlan(project, итог.task)
                .then((п) => { setПлан(п); setВыбраны(п.actions.map((д) => д.index)) })
                .catch(() => setПлан(null))
            }
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
            <button type="button" className="v2-primary" disabled={занято || выбраны.length === 0}
              title="выполнить выбранные действия: сущности получат нить к своим фактам"
              onClick={принятьПлан}>
              {занято ? 'Принимаю…' : `Принять ${выбраны.length} из ${план.actions.length}`}
            </button>
            <button type="button" className="v2-link" onClick={() => setПлан(null)}>позже</button>
          </div>
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
        <table className="v2-tab2">
          <thead>
            <tr>
              <th>Утверждение</th><th>Значение</th><th>Метка</th>
              {знанияV2 && <th>Ранг</th>}
              <th>Откуда</th><th>Решение</th><th />
            </tr>
          </thead>
          <tbody>
            {видно.map((ф) => {
              const было = ф.disposition ?? 'free'
              return (
                <tr key={ф.id}>
                  <td>{ф.subject ? `${ф.subject}: ` : ''}{ф.predicate}</td>
                  <td>{ф.value}{ф.unit ? ` ${ф.unit}` : ''}</td>
                  <td title={МЕТКА[ф.mark] ?? ф.mark}>{МЕТКА[ф.mark] ?? ф.mark}</td>
                  {знанияV2 && (
                    <td title={ф.authority
                      ? 'ранг доверия наследуется от источника; у руки эксперта — экспертный'
                      : 'ранга нет: факт заведён до перестройки поля — выдуманный ранг хуже отсутствующего'}>
                      {рангСловами(ф.authority)}
                    </td>
                  )}
                  <td className="v2-mono" title={ф.material}>
                    {ф.manual ? <span className="v2-kf__manual">{ф.material}</span> : (ф.anchor ?? '—')}
                    {ф.param_key && <span className="v2-dim"> · анкета: {ф.param_key}</span>}
                    {ф.conflicts && ф.conflicts.length > 0 && (
                      <span className="v2-bad" title="то же утверждение с иным значением: показаны оба, ИИ не выбирает"> · против {ф.conflicts.join(', ')}</span>
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
                              title="рассмотрен и не взят — это тоже решение, факт не исчезает"
                              onClick={() => решить(ф, 'noted')}>отложить</button>
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
                          onChange={(e) => setПричина(e.target.value)}
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
            })}
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
export function Source({ project, onParsed, onError }: {
  project: string
  onParsed: (итог: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => void
  onError: (e: string) => void
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
  const [знанияV2, setЗнанияV2] = useState(false)
  const [поставлен, setПоставлен] = useState<string | null>(null)

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
      ...(знанияV2 ? { authority: ранг || undefined } : { kind: вид }),
      ...(двоичный ? { filename: двоичный.name, file_base64: двоичный.base64 } : {}),
    })
      .then((м) => {
        // Показывается ранг, который ПОСТАВИЛО ядро, а не тот, что отправлен.
        if (м.authority) {
          setПоставлен(`ранг источника: ${РАНГ[м.authority] ?? м.authority}`
            + (м.authority_note ? ` · ${м.authority_note}` : ''))
        }
        return api.atomizeJob(project, м.code, задание, 'инженер')
      })
      .then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job} — стенд отвечает`); window.setTimeout(() => опрос(з.job), 3000) }
      })
      .catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
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
        <textarea rows={4} value={текст} onChange={(e) => setТекст(e.target.value)}
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
        {поставлен && <span className="v2-dim">{поставлен}</span>}
        {итог && <span className="v2-dim">{итог}</span>}
      </div>
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
        payload: содержимоеКандидата(понятие, предмет, утверждение, значение, единица),
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
              {` · ранг: ${рангСловами(п.authority)} · ${источникСловами(п.source)}`}
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
 */
function Постановка({ project, онтология, onChanged }: {
  project: string
  онтология: FormationOntology | null
  onChanged: () => void
}) {
  const [диф, setДиф] = useState<SynthesisDiffView | null>(null)
  const [занято, setЗанято] = useState(false)
  const [состояние, setСостояние] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [итог, setИтог] = useState<SynthesisAccepted | null>(null)
  const [ask, спросить, закрытьВопрос] = useConfirm()

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

  const принять = () => {
    if (!запуск) return
    спросить({
      question: `Принять отмеченные предложения: ${отмечены.length}. Заводится только названное `
        + 'новым — узнанное принятое останется решением человека в сверке.',
      ok: 'Принять',
      input: { label: 'почему берём', placeholder: 'основание решения' },
      onOk: (повод) => api.acceptSynthesis(project, запуск.id, отмечены, 'инженер', повод || undefined)
        .then((и) => { setИтог(и); setОтмечены([]); onChanged(); прочитать() })
        .catch((e) => setОтказ(отказПодробно(e))),
    })
  }

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={занято}
          title={занято
            ? 'синтез уже идёт: второй запуск того же среза даст тот же ответ'
            : 'пройти по всему полю и предложить постановку: четыре группы с основаниями и рангами; без вашего клика не заведётся ничего'}
          onClick={сформировать}>
          {занято ? 'Формирую…' : 'Сформировать постановку из поля'}
        </button>
        {состояние && <span className="v2-dim">{состояние}</span>}
      </div>

      {отказ && <div className="v2-locked">{отказ}</div>}

      {итог && (
        <div className="v2-empty__why">
          {итог.note}{` · заведено: ${итог.accepted}`}
          {итог.created.length > 0 && (
            <> · <span className="v2-mono">{итог.created.join(' · ')}</span></>
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
                        <tr key={п.proposal}>
                          <td>
                            <input type="checkbox" checked={отмечены.includes(п.proposal)}
                              aria-label={`отметить предложение ${п.proposal}`}
                              onChange={(e) => setОтмечены(e.target.checked
                                ? [...отмечены, п.proposal]
                                : отмечены.filter((к) => к !== п.proposal))} />
                          </td>
                          <td>
                            <span title={нота(п.concept)}>{ПОНЯТИЕ[п.concept] ?? п.concept}</span>
                            <div>{содержимоеСловами(п.payload)}</div>
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
                                {` · ранг: ${о.authority_word ?? рангСловами(о.authority)}`}
                              </div>
                            ))}
                          </td>
                          <td>
                            {п.missing.length === 0 ? '—' : п.missing.map((связь) => (
                              <div key={связь} className="v2-warn">
                                связь <span className="v2-mono">{связь}</span> не закрыта: сущности не будет
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
