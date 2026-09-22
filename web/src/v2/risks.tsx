// Реестр рисков — раздел «Риски» (шип 1, п. 1.2–1.4).
//
// Реестр живёт всю фазу, не только в сцене 11: пункт меню виден с сцены 11,
// до неё — в эксперт-режиме. Отбор «открытые · закрытые · все», сортировка по
// критичности и сроку-точке, отбор «держат точку», группировка по владельцу.
// Закрытие — с экрана, решением словами (кем и когда пишет сервер); возврат в
// открытые — причиной. Карточка вниз: условие · событие · последствие,
// вероятность и влияние кликом 1–5, стратегия, меры, владелец, срок — любая
// веха, связь с узлом или сценой. Точка называет риски, которые её держат, и
// ведёт сюда ссылкой: два клика от точки до закрытого риска.
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type ComponentRow, type KindSpec, type RiskRow } from './api'
import { useАвтор } from './research'

/**
 * Вехи проекта для срока риска.
 *
 * Истина: `risk.due_point: ref gate` — «срок — любая веха (gate.kind
 * phase|technology)». Перечень в коде («MCR · SRR · SDR · PDR») был вторым
 * списком мимо данных: у проекта свои точки и свои вехи технологий, и риск
 * держится ими. Пусто — значит вех в проекте ещё нет, и это сказано словами.
 */
export function useВехи(project: string): { код: string; подпись: string }[] {
  const [вехи, setВехи] = useState<{ код: string; подпись: string }[]>([])
  useEffect(() => {
    api.entities(project, 'gate')
      .then((р) => setВехи(р.items
        .filter((в) => в.status !== 'cancelled')
        .map((в) => ({
          код: в.code,
          подпись: `${в.code}${в.doc.name ? ` · ${String(в.doc.name)}` : ''}`
            + (в.doc.planned_date ? ` · ${String(в.doc.planned_date)}` : ''),
        }))))
      .catch(() => undefined)
  }, [project])
  return вехи
}

type Отбор = 'open' | 'closed' | 'all'
type Порядок = 'level' | 'due'

const ОТБОР: { код: Отбор; слово: string }[] = [
  { код: 'open', слово: 'открытые' }, { код: 'closed', слово: 'закрытые' }, { код: 'all', слово: 'все' },
]

/**
 * Слова статуса. Истина знает статусную модель риска (open|closed), но меток
 * под ключом risk.status в enum_labels не называет — вопрос владельцу; до
 * ответа слова здесь, чтобы на экране не было кода.
 */
const СТАТУС: Record<string, string> = { open: 'открыт', closed: 'закрыт' }

type Учётка = { login: string; display_name: string }

export function RiskRegistry({ project, wantRisk, сцены }: {
  project: string
  /** Карточка, которую просили открыть (ссылка с точки). */
  wantRisk?: string | null
  /** Сцены фазы для связи риска со сценой; нет — только узлы. */
  сцены?: { key: string; title: string }[]
}) {
  const [риски, setРиски] = useState<RiskRow[]>([])
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [учётки, setУчётки] = useState<Учётка[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [отбор, setОтбор] = useState<Отбор>('open')
  const [порядок, setПорядок] = useState<Порядок>('level')
  const [держатТочку, setДержатТочку] = useState('')
  const [поВладельцу, setПоВладельцу] = useState(false)
  const [открыт, setОткрыт] = useState<string | null>(wantRisk ?? null)
  const [поля, setПоля] = useState({
    statement: '', category: 'technical', probability: 3, impact: 3,
    strategy: 'mitigate', measures: '', owner: '', due_point: '',
  })
  const вехи = useВехи(project)
  const [всемВеха, setВсемВеха] = useState('')
  const [занятоВсем, setЗанятоВсем] = useState(false)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    api.risks(project).then((r) => setРиски(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  useEffect(перечитать, [перечитать])
  useEffect(() => {
    api.kind('risk').then(setВид).catch(() => setВид(null))
    api.components(project).then((r) => setУзлы(r.items)).catch(() => setУзлы([]))
    fetch('/api/auth/users').then((r) => (r.ok ? r.json() : { users: [] }))
      .then((d) => setУчётки(d.users ?? [])).catch(() => setУчётки([]))
  }, [project])
  useEffect(() => { if (wantRisk) { setОткрыт(wantRisk); setОтбор('all') } }, [wantRisk])

  /**
   * Правка риска на месте: вид «риск» правится полем, а
   * пересчёт уровня (вероятность × влияние) делает сервер — экран лишь
   * перечитывает реестр, чтобы «В×П» не разошлись с тем, что видят ворота.
   */
  const правитьРиск = (код: string, поля: Record<string, unknown>, зачем = 'оценка риска') => {
    api.patchEntity(project, код, поля, автор || 'инженер', зачем)
      .then(перечитать)
      .catch((ошибка) => setОтказ(String(ошибка.message ?? ошибка)))
  }

  /** Русские значения перечислений — только из истины (enum_labels вида). */
  const метки = (поле: string): Record<string, string> => вид?.enum_labels?.[поле] ?? {}
  const словом = (поле: string, значение: string) => метки(поле)[значение] ?? значение

  const точки = Array.from(new Set(риски.flatMap((р) => р.holds)))
  const видимые = риски
    .filter((р) => (отбор === 'all' ? true : р.status === отбор))
    .filter((р) => (держатТочку ? р.holds.includes(держатТочку) : true))
    .sort((а, б) => (порядок === 'level'
      ? б.level - а.level || а.code.localeCompare(б.code)
      : (а.due_date || '9999').localeCompare(б.due_date || '9999') || б.level - а.level))
  const группы: { владелец: string | null; строки: RiskRow[] }[] = поВладельцу
    ? Array.from(new Set(видимые.map((р) => р.owner || '—'))).sort()
      .map((в) => ({ владелец: в, строки: видимые.filter((р) => (р.owner || '—') === в) }))
    : [{ владелец: null, строки: видимые }]
  const открытых = риски.filter((р) => р.status === 'open').length
  const критичных = риски.filter((р) => р.status === 'open' && р.level >= 12).length

  return (
    <div className="v2-panel" data-why="работа">
      {отказ && <div className="v2-locked">{отказ}</div>}
      <h3>
        Реестр рисков
        <span className="v2-cnt">
          {риски.length} · открытых {открытых} · критичность ≥ 12: {критичных}
          {' · '}без срока {риски.filter((р) => р.status === 'open' && р.due_point === '—').length}
        </span>
      </h3>
      <div className="v2-form v2-form--row" data-why="работа">
        <span className="v2-inline" role="group" aria-label="отбор рисков">
          {ОТБОР.map((о) => (
            <button key={о.код} type="button" className={отбор === о.код ? 'v2-chip v2-chip--on' : 'v2-chip'}
              aria-pressed={отбор === о.код} onClick={() => setОтбор(о.код)}>
              {о.слово}
            </button>
          ))}
        </span>
        <label className="v2-inline">
          порядок
          <select aria-label="порядок рисков" value={порядок} onChange={(e) => setПорядок(e.target.value as Порядок)}>
            <option value="level">по критичности</option>
            <option value="due">по сроку-точке</option>
          </select>
        </label>
        <label className="v2-inline" title="открытые риски, чей срок не позже даты точки: они держат её решение">
          держат точку
          <select aria-label="держат точку" value={держатТочку} onChange={(e) => setДержатТочку(e.target.value)}>
            <option value="">— любую —</option>
            {точки.map((т) => <option key={т} value={т}>{т}</option>)}
          </select>
        </label>
        <label className="v2-inline">
          <input type="checkbox" checked={поВладельцу} onChange={(e) => setПоВладельцу(e.target.checked)} />
          по владельцу
        </label>
      </div>

      {риски.length === 0 ? (
        <div className="v2-empty">
          Рисков не заведено.
          <span className="v2-empty__why">
            Пустой реестр означает, что риски не искали: сцена 11 держится тремя записями.
          </span>
        </div>
      ) : видимые.length === 0 ? (
        <div className="v2-empty">
          По отбору рисков нет.
          <span className="v2-empty__why">Снимите отбор «{ОТБОР.find((о) => о.код === отбор)?.слово}»{держатТочку ? ` или точку ${держатТочку}` : ''}.</span>
        </div>
      ) : (
        <table className="v2-table">
          <thead>
            <tr><th>Код</th><th>Риск</th><th>В×П</th><th>Стратегия</th><th>Владелец</th><th>Срок</th><th>Состояние</th></tr>
          </thead>
          <tbody>
            {группы.map((г) => (
              <GroupRows key={г.владелец ?? '*'} группа={г} открыт={открыт} setОткрыт={setОткрыт}
                правитьРиск={правитьРиск} словом={словом} метки={метки} вехи={вехи}
                узлы={узлы} сцены={сцены ?? []} project={project} автор={автор}
                перечитать={перечитать} setОтказ={setОтказ} />
            ))}
          </tbody>
        </table>
      )}

      {/*
        Простановка разом: на проходе 20.09 без точки стояли ВСЕ двенадцать
        рисков — по одному это двенадцать кликов. Веху выбирает человек,
        проставляется она только тем, у кого точки нет: уже названный срок
        массовое действие не трогает.
      */}
      {риски.some((р) => р.due_point === '—') && (
        <div className="v2-form__actions" data-why="работа">
          <span className="v2-empty__why">
            без срока-точки: {риски.filter((р) => р.due_point === '—').length} из {риски.length}
          </span>
          <select name="всем.due_point" value={всемВеха} aria-label="веха для рисков без точки"
            onChange={(e) => setВсемВеха(e.target.value)}>
            <option value="">— веха проекта —</option>
            {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
          </select>
          <button type="button" disabled={!всемВеха || занятоВсем}
            title={всемВеха
              ? 'проставить выбранную веху всем рискам без точки; названные сроки не трогаются'
              : 'сначала выберите веху'}
            onClick={() => {
              setЗанятоВсем(true); setОтказ(null)
              const без = риски.filter((р) => р.due_point === '—')
              Promise.all(без.map((р) => api.patchEntity(project, р.code, { due_point: всемВеха }, автор || 'инженер',
                'срок-точка риска: проставлено разом')))
                .then(() => { setЗанятоВсем(false); перечитать() })
                .catch((ошибка) => { setЗанятоВсем(false); setОтказ(String(ошибка.message ?? ошибка)) })
            }}>
            {занятоВсем ? 'Ставлю…' : 'Проставить всем без точки'}
          </button>
        </div>
      )}

      <div className="v2-form">
        <label>Формулировка
          <input value={поля.statement} placeholder="SEU в памяти без ECC → зависание борта"
            onChange={(e) => setПоля({ ...поля, statement: e.target.value })} />
        </label>
        <label>Меры
          <input value={поля.measures} placeholder="ECC и watchdog"
            onChange={(e) => setПоля({ ...поля, measures: e.target.value })} />
        </label>
        <label>Вероятность 1–5
          <input type="number" min={1} max={5} value={поля.probability}
            onChange={(e) => setПоля({ ...поля, probability: Number(e.target.value) })} />
        </label>
        <label>Влияние 1–5
          <input type="number" min={1} max={5} value={поля.impact}
            onChange={(e) => setПоля({ ...поля, impact: Number(e.target.value) })} />
        </label>
        <label>Владелец
          <input list={`v2-владельцы-${project}`} value={поля.owner} placeholder="кто ведёт"
            onChange={(e) => setПоля({ ...поля, owner: e.target.value })} />
          <datalist id={`v2-владельцы-${project}`}>
            {учётки.map((у) => <option key={у.login} value={у.display_name}>{у.login}</option>)}
          </datalist>
        </label>
        <label>Срок — точка
          <select value={поля.due_point} onChange={(e) => setПоля({ ...поля, due_point: e.target.value })}>
            <option value="">— веха проекта —</option>
            {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
          </select>
        </label>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" disabled={!поля.statement.trim()}
            title="срок без точки не наступает — точка обязательна"
            onClick={() => api.addRisk(project, { ...поля, owner: поля.owner || автор || 'инженер', author: автор || 'инженер' })
              .then(() => { setПоля({ ...поля, statement: '', measures: '' }); перечитать() })
              .catch((e) => setОтказ(String(e.message ?? e)))}>
            Завести риск
          </button>
        </div>
      </div>
    </div>
  )
}

function GroupRows({ группа, открыт, setОткрыт, правитьРиск, словом, метки, вехи, узлы, сцены, project, автор, перечитать, setОтказ }: {
  группа: { владелец: string | null; строки: RiskRow[] }
  открыт: string | null
  setОткрыт: (код: string | null) => void
  правитьРиск: (код: string, поля: Record<string, unknown>, зачем?: string) => void
  словом: (поле: string, значение: string) => string
  метки: (поле: string) => Record<string, string>
  вехи: { код: string; подпись: string }[]
  узлы: ComponentRow[]
  сцены: { key: string; title: string }[]
  project: string
  автор: string
  перечитать: () => void
  setОтказ: (т: string | null) => void
}) {
  return (
    <>
      {группа.владелец !== null && (
        <tr><td colSpan={7} className="v2-dim">владелец: {группа.владелец} · {группа.строки.length}</td></tr>
      )}
      {группа.строки.map((р) => (
        <RiskRow_ key={р.code} р={р} раскрыт={открыт === р.code} onToggle={() => setОткрыт(открыт === р.code ? null : р.code)}
          правитьРиск={правитьРиск} словом={словом} метки={метки} вехи={вехи} узлы={узлы} сцены={сцены}
          project={project} автор={автор} перечитать={перечитать} setОтказ={setОтказ} />
      ))}
    </>
  )
}

function RiskRow_({ р, раскрыт, onToggle, правитьРиск, словом, метки, вехи, узлы, сцены, project, автор, перечитать, setОтказ }: {
  р: RiskRow
  раскрыт: boolean
  onToggle: () => void
  правитьРиск: (код: string, поля: Record<string, unknown>, зачем?: string) => void
  словом: (поле: string, значение: string) => string
  метки: (поле: string) => Record<string, string>
  вехи: { код: string; подпись: string }[]
  узлы: ComponentRow[]
  сцены: { key: string; title: string }[]
  project: string
  автор: string
  перечитать: () => void
  setОтказ: (т: string | null) => void
}) {
  const строка = useRef<HTMLTableRowElement | null>(null)
  useEffect(() => { if (раскрыт) строка.current?.scrollIntoView({ block: 'nearest' }) }, [раскрыт])
  const закрыт = р.status === 'closed'
  return (
    <>
      <tr ref={строка} className={раскрыт ? 'v2-row--open' : undefined}>
        <td className="v2-mono">
          <button type="button" className="v2-link" aria-expanded={раскрыт} title="карточка риска"
            onClick={onToggle}>
            {р.code}
          </button>
        </td>
        <td>
          {р.statement}
          {р.holds.length > 0 && <span className="v2-dim"> · держит {р.holds.join(', ')}</span>}
        </td>
        <td title="вероятность × влияние: шкала 1–5">
          <select name={`${р.code}.probability`} value={р.probability || ''}
            aria-label={`вероятность риска ${р.code}`} disabled={закрыт}
            onChange={(e) => правитьРиск(р.code, { probability: Number(e.target.value) })}>
            <option value="">—</option>
            {[1, 2, 3, 4, 5].map((з) => <option key={з} value={з}>{з}</option>)}
          </select>
          ×
          <select name={`${р.code}.impact`} value={р.impact || ''}
            aria-label={`влияние риска ${р.code}`} disabled={закрыт}
            onChange={(e) => правитьРиск(р.code, { impact: Number(e.target.value) })}>
            <option value="">—</option>
            {[1, 2, 3, 4, 5].map((з) => <option key={з} value={з}>{з}</option>)}
          </select>
          {р.level > 0 ? ` = ${р.level}` : ''}
        </td>
        <td>
          <select name={`${р.code}.strategy`} value={р.strategy === '—' ? '' : р.strategy}
            aria-label={`стратегия риска ${р.code}`} disabled={закрыт}
            onChange={(e) => правитьРиск(р.code, { strategy: e.target.value })}>
            <option value="">— стратегия —</option>
            {Object.entries(метки('strategy')).map(([код, слово]) => <option key={код} value={код}>{слово}</option>)}
          </select>
        </td>
        <td>
          <input name={`${р.code}.owner`} defaultValue={р.owner === '—' ? '' : р.owner} list={`v2-владельцы-${project}`}
            placeholder="кто ведёт" aria-label={`владелец риска ${р.code}`} disabled={закрыт}
            onBlur={(e) => {
              const имя = e.target.value.trim()
              if (имя && имя !== р.owner) правитьРиск(р.code, { owner: имя })
            }} />
        </td>
        {/*
          Срок правится ЗДЕСЬ: условие сцены 11 «у каждого риска срок-точка»
          держало проход, а поправить срок принятого риска было негде — только
          при заведении (проход владельца 20.09).
        */}
        <td className={р.due_point === '—' && !закрыт ? 'v2-warn' : undefined}>
          <select name={`${р.code}.due_point`} value={р.due_point === '—' ? '' : р.due_point}
            aria-label={`срок-точка риска ${р.code}`} disabled={закрыт}
            onChange={(e) => api.patchEntity(project, р.code, { due_point: e.target.value }, автор || 'инженер',
              'срок-точка риска')
              .then(перечитать)
              .catch((ошибка) => setОтказ(String(ошибка.message ?? ошибка)))}>
            <option value="">— нет точки —</option>
            {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
          </select>
        </td>
        <td className={закрыт ? 'v2-ok' : undefined}>{СТАТУС[р.status] ?? р.status}</td>
      </tr>
      {раскрыт && (
        <tr className="v2-card-row">
          <td colSpan={7}>
            <RiskCard р={р} правитьРиск={правитьРиск} словом={словом} метки={метки}
              узлы={узлы} сцены={сцены} project={project} автор={автор} перечитать={перечитать} setОтказ={setОтказ} />
          </td>
        </tr>
      )}
    </>
  )
}

/**
 * Карточка риска: всё, что у вида по истине, правится здесь; закрытие и
 * возврат — тут же. Правка идёт на сервер полем, пересчёт уровня — его.
 */
function RiskCard({ р, правитьРиск, словом, метки, узлы, сцены, project, автор, перечитать, setОтказ }: {
  р: RiskRow
  правитьРиск: (код: string, поля: Record<string, unknown>, зачем?: string) => void
  словом: (поле: string, значение: string) => string
  метки: (поле: string) => Record<string, string>
  узлы: ComponentRow[]
  сцены: { key: string; title: string }[]
  project: string
  автор: string
  перечитать: () => void
  setОтказ: (т: string | null) => void
}) {
  const [решение, setРешение] = useState('')
  const [причина, setПричина] = useState('')
  const [занято, setЗанято] = useState(false)
  const [cec, setCec] = useState({ condition: р.condition, event: р.event, consequence: р.consequence })
  const [меры, setМеры] = useState(р.measures)
  useEffect(() => {
    setCec({ condition: р.condition, event: р.event, consequence: р.consequence }); setМеры(р.measures)
  }, [р.code, р.version, р.condition, р.event, р.consequence, р.measures])
  const закрыт = р.status === 'closed'
  const сохранитьCec = () => {
    if (cec.condition === р.condition && cec.event === р.event && cec.consequence === р.consequence) return
    правитьРиск(р.code, { cec }, 'условие · событие · последствие')
  }
  const закрыть = () => {
    setЗанято(true); setОтказ(null)
    api.closeRisk(project, р.code, решение.trim(), автор || 'инженер')
      .then(() => { setРешение(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }
  const вернуть = () => {
    setЗанято(true); setОтказ(null)
    api.reopenRisk(project, р.code, причина.trim(), автор || 'инженер')
      .then(() => { setПричина(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }
  const ссылки = р.refs
  const добавитьСсылку = (код: string) => {
    if (!код || ссылки.includes(код)) return
    правитьРиск(р.code, { refs: [...ссылки, код] }, 'связь риска с узлом или сценой')
  }
  const снятьСсылку = (код: string) => правитьРиск(р.code, { refs: ссылки.filter((с) => с !== код) }, 'связь риска снята')

  return (
    <div className="v2-facets">
      <div className="v2-facet">
        <div className="v2-facet__title">Условие · событие · последствие</div>
        <div className="v2-facet__body v2-form">
          {(['condition', 'event', 'consequence'] as const).map((к) => (
            <label key={к}>{{ condition: 'условие', event: 'событие', consequence: 'последствие' }[к]}
              <input value={cec[к]} disabled={закрыт} aria-label={`${{ condition: 'условие', event: 'событие', consequence: 'последствие' }[к]} риска ${р.code}`}
                onChange={(e) => setCec({ ...cec, [к]: e.target.value })} onBlur={сохранитьCec} />
            </label>
          ))}
        </div>
      </div>
      <div className="v2-facet">
        <div className="v2-facet__title">Оценка</div>
        <div className="v2-facet__body">
          {/* Вероятность и влияние — кликом 1–5: одна кнопка на балл, не поле ввода. */}
          <div className="v2-dim">вероятность</div>
          <span role="group" aria-label={`вероятность риска ${р.code} кликом`}>
            {[1, 2, 3, 4, 5].map((з) => (
              <button key={з} type="button" className={р.probability === з ? 'v2-chip v2-chip--on' : 'v2-chip'}
                aria-pressed={р.probability === з} disabled={закрыт}
                title={закрыт ? 'риск закрыт: оценка не правится — верните в открытые с причиной' : `вероятность ${з} из 5`}
                onClick={() => р.probability !== з && правитьРиск(р.code, { probability: з })}>{з}</button>
            ))}
          </span>
          <div className="v2-dim">влияние</div>
          <span role="group" aria-label={`влияние риска ${р.code} кликом`}>
            {[1, 2, 3, 4, 5].map((з) => (
              <button key={з} type="button" className={р.impact === з ? 'v2-chip v2-chip--on' : 'v2-chip'}
                aria-pressed={р.impact === з} disabled={закрыт}
                title={закрыт ? 'риск закрыт: оценка не правится — верните в открытые с причиной' : `влияние ${з} из 5`}
                onClick={() => р.impact !== з && правитьРиск(р.code, { impact: з })}>{з}</button>
            ))}
          </span>
          <div>критичность: <b>{р.level || '—'}</b>{р.level >= 12 && <span className="v2-bad"> · ключевой (≥ 12)</span>}</div>
          <label className="v2-inline">категория
            <select value={р.category} disabled={закрыт} aria-label={`категория риска ${р.code}`}
              onChange={(e) => правитьРиск(р.code, { category: e.target.value })}>
              {Object.entries(метки('category')).map(([код, слово]) => <option key={код} value={код}>{слово}</option>)}
              {!метки('category')[р.category] && р.category && <option value={р.category}>{р.category}</option>}
            </select>
          </label>
          <div className="v2-dim">стратегия: {словом('strategy', р.strategy)}</div>
        </div>
      </div>
      <div className="v2-facet">
        <div className="v2-facet__title">Меры · владелец · срок · связи</div>
        <div className="v2-facet__body v2-form">
          <label>меры
            <textarea value={меры} disabled={закрыт} aria-label={`меры риска ${р.code}`} rows={3}
              onChange={(e) => setМеры(e.target.value)}
              onBlur={() => меры !== р.measures && правитьРиск(р.code, { measures: меры }, 'меры риска')} />
          </label>
          <div className="v2-dim">
            владелец: {р.owner || '—'} · срок: {р.due_point}{р.due_date ? ` (${р.due_date})` : ''}
            {р.holds.length > 0 ? ` · держит ${р.holds.join(', ')}` : ''}
          </div>
          <div>
            связи: {ссылки.length === 0 ? <span className="v2-dim">нет</span> : ссылки.map((с) => (
              <span key={с} className="v2-chip" title="узел состава или сцена">
                {с}
                {!закрыт && <button type="button" className="v2-link" aria-label={`снять связь ${с}`} onClick={() => снятьСсылку(с)}> ×</button>}
              </span>
            ))}
          </div>
          {!закрыт && (
            <div className="v2-form--row">
              <select aria-label={`связать риск ${р.code} с узлом`} value="" onChange={(e) => добавитьСсылку(e.target.value)}>
                <option value="">+ узел</option>
                {узлы.filter((у) => !ссылки.includes(у.code)).map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
              </select>
              {сцены.length > 0 && (
                <select aria-label={`связать риск ${р.code} со сценой`} value="" onChange={(e) => добавитьСсылку(e.target.value)}>
                  <option value="">+ сцена</option>
                  {сцены.filter((с) => !ссылки.includes(`scene:${с.key}`)).map((с) => <option key={с.key} value={`scene:${с.key}`}>сцена {с.key} · {с.title}</option>)}
                </select>
              )}
            </div>
          )}
        </div>
      </div>
      <div className="v2-facet">
        <div className="v2-facet__title">Состояние · v{р.version}</div>
        <div className="v2-facet__body v2-form">
          {закрыт ? (
            <>
              <div>
                <span className="v2-ok">закрыт</span> · {р.closed_by || '—'} · {р.closed_at ? р.closed_at.slice(0, 16).replace('T', ' ') : '—'}
                <div>решение: {р.resolution || '—'}</div>
              </div>
              <label>вернуть в открытые — причина
                <input value={причина} placeholder="почему решение о закрытии не держится"
                  aria-label={`причина возврата риска ${р.code}`} onChange={(e) => setПричина(e.target.value)} />
              </label>
              <div className="v2-form__actions">
                <button type="button" disabled={!причина.trim() || занято} onClick={вернуть}
                  title={причина.trim() ? 'вернуть риск в открытые с этой причиной' : 'возврат — с причиной словами'}>
                  Вернуть в открытые
                </button>
                {!причина.trim() && <span className="v2-empty__why">Нажать нельзя: причина не названа.</span>}
              </div>
            </>
          ) : (
            <>
              {р.reopen_reason && <div className="v2-dim">возвращён в открытые: {р.reopen_reason}</div>}
              <label>закрыть решением — чем снят или почему принят
                <input value={решение} placeholder="ECC и watchdog приняты в состав"
                  aria-label={`решение по риску ${р.code}`} onChange={(e) => setРешение(e.target.value)} />
              </label>
              <div className="v2-form__actions">
                <button type="button" className="v2-primary" disabled={!решение.trim() || занято} onClick={закрыть}
                  title={решение.trim() ? 'закрыть риск этим решением: кем и когда запишет сервер' : 'закрытие — решением словами'}>
                  Закрыть риск
                </button>
                {!решение.trim() && <span className="v2-empty__why">Нажать нельзя: решение не названо.</span>}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
