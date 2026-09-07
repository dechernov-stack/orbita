// Экран требований (ТЗ §4.3, сцена 8): ШЕСТЬ КОЛОНОК и карточка вниз.
//
// Правило экрана: в таблице живёт только то, по чему ищут глазами — код,
// заголовок, формулировка, показатель, носитель, статус. Всё остальное
// (источники, метод, применимость, пометы линта) открывается карточкой под
// строкой, а не отдельным окном: контекст строки не теряется.
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ReactFlow, Background, Controls, MarkerType, type Node, type Edge,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import {
  api, type BaselineRow, type Blocker, type ImpactGraph, type LintNote,
  type RequirementRow, type SuspectRow,
} from './api'

const ШАБЛОНЫ: { key: string; title: string; форма: string }[] = [
  { key: 'always', title: 'Всегда', форма: '‹Носитель› должен ‹действие› ‹объект› [‹показатель›].' },
  { key: 'event', title: 'По событию', форма: 'Когда ‹событие›, ‹носитель› должен ‹действие› [в течение ‹T›].' },
  { key: 'state', title: 'В состоянии', форма: 'Пока ‹состояние›, ‹носитель› должен ‹действие›.' },
  { key: 'unwanted', title: 'Нежелательное', форма: 'Если ‹условие›, то ‹носитель› должен ‹парирование›.' },
  { key: 'optional', title: 'Опциональное', форма: 'Где предусмотрен ‹элемент›, ‹носитель› должен ….' },
]

const УРОВНИ = [
  { key: 'project', title: 'проектное', носитель: 'узел состава' },
  { key: 'scenario', title: 'сценарное', носитель: 'цепочка или сценарий' },
  { key: 'system', title: 'системное', носитель: 'узел состава' },
  { key: 'subsystem', title: 'подсистемное', носитель: 'узел состава' },
  { key: 'interface', title: 'интерфейсное', носитель: 'стык' },
]

export function Requirements({ project }: { project: string | null }) {
  const [строки, setСтроки] = useState<RequirementRow[]>([])
  const [открыта, setОткрыта] = useState<string | null>(null)
  const [дерево, setДерево] = useState<'carrier' | 'source'>('carrier')
  const [подозрения, setПодозрения] = useState<SuspectRow[]>([])
  const [снимки, setСнимки] = useState<BaselineRow[]>([])
  const [помехи, setПомехи] = useState<Blocker[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    if (!project) return
    api.requirements(project).then((r) => setСтроки(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.suspects(project).then((r) => setПодозрения(r.items)).catch(() => undefined)
    api.baselines(project).then((r) => setСнимки(r.items)).catch(() => undefined)
    api.baselineBlockers(project, 'functional', 'SRR').then((r) => setПомехи(r.items)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">Сначала откройте проект — требования живут в проекте.</div>
      </div>
    )
  }

  return (
    <>
      {отказ && <div className="v2-card"><div className="v2-locked">{отказ}</div></div>}

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Требования</span>
          <span className="v2-card__count">{строки.length}</span>
          <span className="v2-head__spacer" />
          <button type="button" className="v2-chip" aria-pressed={дерево === 'carrier'}
            title="дерево по носителю: кто несёт требование"
            onClick={() => setДерево('carrier')}>по носителю</button>
          <button type="button" className="v2-chip" aria-pressed={дерево === 'source'}
            title="дерево по источнику: откуда требование выведено"
            onClick={() => setДерево('source')}>по источнику</button>
        </div>

        {строки.length === 0 ? (
          <div className="v2-empty">
            Требований пока нет.
            <span className="v2-empty__why">
              Требование выводится из целей, нужд и ограничений — источник обязателен,
              а носитель определяет, кто за него отвечает.
            </span>
          </div>
        ) : (
          <table className="v2-table v2-table--req">
            <thead>
              <tr>
                <th>Код</th><th>Заголовок</th><th>Формулировка</th>
                <th>Показатель</th><th>Носитель</th><th>Статус</th>
              </tr>
            </thead>
            <tbody>
              {строки.map((т) => (
                <TableRow key={т.code} т={т}
                  открыта={открыта === т.code}
                  onToggle={() => setОткрыта(открыта === т.code ? null : т.code)} />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {открыта && <Impact код={открыта} project={project} />}

      <Деревья строки={строки} вид={дерево} />

      <Подозрения project={project} строки={подозрения} onConfirmed={перечитать} />

      <Базирование project={project} снимки={снимки} помехи={помехи} onDone={перечитать} />

      <Форма project={project} onAdded={перечитать} />
    </>
  )
}

/** Строка таблицы плюс карточка ВНИЗ: контекст строки не теряется. */
function TableRow({ т, открыта, onToggle }: {
  т: RequirementRow; открыта: boolean; onToggle: () => void
}) {
  const показатель = т.measure ? кратко(т.measure) : '—'
  return (
    <>
      <tr className={открыта ? 'v2-row v2-row--open' : 'v2-row'} onClick={onToggle}>
        <td>
          {т.code}
          {т.after_baseline_changed && (
            <span className="v2-flag" title="версия выше зафиксированной снимком — изменено после утверждения">
              изменено
            </span>
          )}
        </td>
        <td>{т.title}</td>
        <td className="v2-cell--statement">
          {т.statement}
          {т.notes.length > 0 && (
            <span className="v2-flag v2-flag--warn" title={т.notes.map((n) => `${n.what}: ${n.why}`).join('\n')}>
              линт: {т.notes.length}
            </span>
          )}
        </td>
        <td>{показатель}</td>
        <td>
          {т.carrier ?? <span className="v2-flag v2-flag--warn" title="без носителя — не требование">нет</span>}
          {т.carrier_kind && <span className="v2-dim"> · {т.carrier_kind}</span>}
        </td>
        <td>{т.status}</td>
      </tr>
      {открыта && (
        <tr className="v2-card-row">
          <td colSpan={6}>
            <div className="v2-facets">
              <Группа title="Происхождение">
                <div>уровень: {УРОВНИ.find((у) => у.key === т.level)?.title ?? т.level}</div>
                <div>категория: {т.category}</div>
                <div>источники: {т.sources.length > 0 ? т.sources.join(', ') : 'нет — требование ниоткуда не выводится'}</div>
                {т.template_ref && <div>типовое: {т.template_ref} · применимость {т.applicability ?? '—'}</div>}
              </Группа>
              <Группа title="Проверка">
                <div>шаблон EARS: {ШАБЛОНЫ.find((ш) => ш.key === т.ears)?.title ?? т.ears}</div>
                <div>метод верификации: {т.verification_method ?? 'не выбран'}</div>
                <div>версия: {т.version}</div>
              </Группа>
              <Группа title="Пометы линта">
                {т.notes.length === 0
                  ? <div className="v2-dim">замечаний нет</div>
                  : т.notes.map((n) => <Помета key={n.rule + n.what} note={n} />)}
              </Группа>
              <Группа title="Что заденет правка">
                <div className="v2-dim">
                  Граф связей — под таблицей: в колонке карточки он был бы нечитаем.
                </div>
              </Группа>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

/**
 * «Что заденет правка» — граф влияния СВЯЗЯМИ, а не догадкой по таблице.
 *
 * Прежде соседство считалось здесь же: общий носитель и упоминание кода в
 * источниках. Это давало ответ, похожий на правду, но видело только
 * требования и только те, что уже загружены на экран. Настоящие нити
 * лежат в реестре связей и ведут дальше — к узлам, функциям, моделям, — и
 * ходить по ним умеет сервер. Глубина спрашивается: «на два шага» и «на
 * четыре» — разные ответы, и человек выбирает, насколько далеко смотреть.
 *
 * Рисует БИБЛИОТЕКА (ADR-046): раскладку считает dagre при каждом показе,
 * координаты не хранятся ни на сервере, ни в браузере — иначе картинка
 * однажды начнёт врать про вчерашние связи.
 */
function Impact({ код, project }: { код: string; project: string }) {
  const [граф, setГраф] = useState<ImpactGraph | null>(null)
  const [глубина, setГлубина] = useState(2)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    let живо = true
    setГраф(null); setОтказ(null)
    api.impact(project, код, глубина)
      .then((г) => { if (живо) setГраф(г) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [project, код, глубина])

  const разложено = useMemo(() => (граф ? раскладка(граф) : { nodes: [], edges: [] }), [граф])

  return (
    <div className="v2-card" data-why="почему-нельзя">
      <div className="v2-card__head">
        <span className="v2-card__title">Что заденет правка {код}</span>
        <span className="v2-card__count">{граф ? граф.nodes.length - 1 : '…'}</span>
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {!отказ && !граф && <div className="v2-empty">читаю связи…</div>}
      {!отказ && граф && <ImpactCanvas граф={граф} код={код} глубина={глубина}
        setГлубина={setГлубина} разложено={разложено} />}
    </div>
  )
}

function ImpactCanvas({ граф, код, глубина, setГлубина, разложено }: {
  граф: ImpactGraph
  код: string
  глубина: number
  setГлубина: (г: number) => void
  разложено: { nodes: Node[]; edges: Edge[] }
}) {
  return (
    <>
      <div className="v2-kf__bar">
        <span className="v2-dim">на шагов:</span>
        {[1, 2, 4].map((г) => (
          <button key={г} type="button"
            className={глубина === г ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title={`пройти по связям на ${г} ${г === 1 ? 'шаг' : 'шага'} от требования`}
            onClick={() => setГлубина(г)}>{г}</button>
        ))}
      </div>
      {граф.nodes.length <= 1 ? (
        <div className="v2-dim">
          ничего: у требования нет связей — ни носителя, ни выведенных из него.
        </div>
      ) : (
        <>
          <div className="v2-imp__canvas">
            <ReactFlow
              key={`${код}:${глубина}:${граф.nodes.length}`}
              nodes={разложено.nodes}
              edges={разложено.edges}
              nodeOrigin={[0.5, 0.5]}
              fitView
              // Подгонка после готовности: раскладка dagre даёт координаты в
              // своих единицах, и без неё граф открывается не в кадре.
              onInit={(инстанс) => { setTimeout(() => инстанс.fitView(), 0) }}
              nodesDraggable={false}
              nodesConnectable={false}
              proOptions={{ hideAttribution: true }}
              minZoom={0.2}
              // Без потолка подгонка раздувает три узла во весь экран.
              maxZoom={1.2}
            >
              <Background />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
          <div className="v2-dim">
            {граф.summary || 'после правки эти связи станут подозрительными — их придётся подтвердить.'}
          </div>
        </>
      )}
    </>
  )
}

const ШИР = 168
const ВЫС = 54

/** Раскладка dagre при каждом показе: координаты нигде не живут. */
function раскладка(граф: ImpactGraph): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: 22, ranksep: 90 })
  граф.nodes.forEach((у) => g.setNode(у.id, { width: ШИР, height: ВЫС }))
  граф.edges.forEach((р) => g.setEdge(р.from, р.to))
  dagre.layout(g)
  return {
    nodes: граф.nodes.map((у) => {
      const т = g.node(у.id)
      const корень = у.depth === 0
      return {
        id: у.id,
        position: { x: т.x, y: т.y },
        // Размер задан явно, а не оставлен на обмер: пока библиотека не
        // обмерила узел, она держит его скрытым, а обмер идёт наблюдателем
        // размеров — во вкладке, которую браузер не рисует (фон, свёрнутое
        // окно), наблюдатель молчит, и граф остаётся пустым полотном.
        width: ШИР,
        height: ВЫС,
        data: {
          label: (
            <div className="v2-imp__node" title={`${у.kind}: ${у.title}`}>
              <div className="v2-imp__kind">{ВИД_УЗЛА[у.kind] ?? у.kind}</div>
              <div className="v2-mono">{у.code}</div>
            </div>
          ),
        },
        style: {
          width: ШИР,
          fontSize: 12,
          borderRadius: 6,
          border: корень ? '2px solid var(--accent)' : '1px solid var(--line)',
          background: 'var(--ground)',
        },
      } as Node
    }),
    // Подписью идёт ТИП связи, а не её обоснование: обоснование бывает
    // целым предложением («поведение исполняется на бортовой вычислительной
    // машине») и ложится поверх соседних узлов. Причина — в подсказке.
    edges: граф.edges.map((р, i) => ({
      id: `${р.from}->${р.to}:${i}`,
      source: р.from,
      target: р.to,
      label: СВЯЗЬ[р.type] ?? р.type,
      labelStyle: { fontSize: 12 },
      labelShowBg: true,
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { stroke: 'var(--line)' },
      data: { why: р.why },
    } as Edge)),
  }
}

/** Тип связи по-русски: подпись ребра короткая, причина — в подсказке. */
const СВЯЗЬ: Record<string, string> = {
  carrier: 'носитель',
  target: 'носитель анкеты',
  deployed_on: 'развёрнуто на',
  derived_from_fact: 'из факта',
  derived_from: 'выведено из',
  owns: 'владеет',
  covers: 'покрывает',
  snapshot_of: 'напечатано в',
  verifies: 'проверяет',
  allocation: 'размещено на',
  trace: 'трассировка',
  conflicts_with: 'противоречит',
}

/** Вид узла по-русски: латинское имя вида инженеру ничего не говорит. */
const ВИД_УЗЛА: Record<string, string> = {
  requirement: 'требование',
  component: 'узел',
  need: 'нужда',
  goal: 'цель',
  service: 'сервис',
  interface: 'стык',
  function: 'функция',
  model: 'модель',
  parameter: 'анкета',
  constraint: 'ограничение',
  stakeholder: 'сторона',
  technology: 'технология',
  risk: 'риск',
  document: 'документ',
  event: 'событие',
}

function Группа({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="v2-facet">
      <div className="v2-facet__title">{title}</div>
      <div className="v2-facet__body">{children}</div>
    </div>
  )
}

function Помета({ note }: { note: LintNote }) {
  return (
    <div className="v2-note">
      <span className="v2-note__rule">{note.rule}</span>
      <span>{note.what}</span>
      <span className="v2-empty__why">{note.why}</span>
    </div>
  )
}

/** Два дерева: по носителю (кто несёт) и по источнику (откуда выведено). */
function Деревья({ строки, вид }: { строки: RequirementRow[]; вид: 'carrier' | 'source' }) {
  const группы = useMemo(() => {
    const карта = new Map<string, RequirementRow[]>()
    строки.forEach((т) => {
      const ключи = вид === 'carrier'
        ? [т.carrier ?? 'без носителя']
        : (т.sources.length > 0 ? т.sources : ['без источника'])
      ключи.forEach((к) => карта.set(к, [...(карта.get(к) ?? []), т]))
    })
    return [...карта.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [строки, вид])

  if (группы.length === 0) return null

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">
          Дерево {вид === 'carrier' ? 'по носителю' : 'по источнику'}
        </span>
        <span className="v2-card__count">{группы.length}</span>
      </div>
      <ul className="v2-tree">
        {группы.map(([ключ, дети]) => (
          <li key={ключ}>
            <span className="v2-tree__node">{ключ}</span>
            <span className="v2-card__count">{дети.length}</span>
            <ul>
              {дети.map((т) => (
                <li key={т.code}><span className="v2-dim">{т.code}</span> {т.title}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Подозрения({ project, строки, onConfirmed }: {
  project: string; строки: SuspectRow[]; onConfirmed: () => void
}) {
  if (строки.length === 0) return null
  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Подозрительные связи</span>
        <span className="v2-card__count">{строки.length}</span>
      </div>
      <div className="v2-empty__why">
        Конец связи изменился после снимка. Подозрение снимает человек — это разрыв к следующей точке.
      </div>
      {строки.map((с) => (
        <div key={с.link} className="v2-note">
          <span className="v2-note__rule">{с.type}</span>
          <span>{с.from} → {с.to}: {с.why}</span>
          <button type="button" className="v2-chip"
            title="подтвердить, что связь по-прежнему верна"
            onClick={() => api.confirmSuspect(project, с.link, 'стенд').then(onConfirmed).catch(() => undefined)}>
            подтвердить
          </button>
        </div>
      ))}
    </div>
  )
}

function Базирование({ project, снимки, помехи, onDone }: {
  project: string; снимки: BaselineRow[]; помехи: Blocker[]; onDone: () => void
}) {
  const [имя, setИмя] = useState('SRR')
  const [класс, setКласс] = useState('functional')
  const [отказ, setОтказ] = useState<Blocker[] | null>(null)
  const [занято, setЗанято] = useState(false)

  const базировать = () => {
    setЗанято(true); setОтказ(null)
    api.baseline(project, { name: имя, kind: класс, gate: имя, author: 'стенд' })
      .then(() => onDone())
      .catch((e) => setОтказ([{ code: '—', rule: '—', what: String(e.message ?? e) }]))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Базирование</span>
        <span className="v2-card__count">{снимки.length}</span>
      </div>

      {снимки.map((с) => (
        <div key={с.name} className="v2-note">
          <span className="v2-note__rule">{с.name}</span>
          <span>
            {с.kind} · точка {с.gate} · {с.items.length} объектов
            {с.items.length - с.firm > 0 && ` · условных ${с.items.length - с.firm}`}
          </span>
          {с.items.filter((э) => э.firmness === 'conditional').map((э) => (
            <span key={э.ref} className="v2-empty__why">{э.code}: {э.why}</span>
          ))}
        </div>
      ))}

      {помехи.length > 0 && (
        <div className="v2-locked">
          Базировать пока нельзя — {помехи.length} помех:
          <ul>
            {помехи.slice(0, 8).map((п, i) => (
              <li key={`${п.code}-${i}`}><b>{п.code}</b> · {п.rule} — {п.what}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="v2-form__actions">
        <label>Имя снимка
          <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="SRR" />
        </label>
        <label>Класс
          <select value={класс} onChange={(e) => setКласс(e.target.value)}>
            <option value="functional">функциональная</option>
            <option value="allocated">распределённая</option>
            <option value="product">изделия</option>
          </select>
        </label>
        <button type="button" className="v2-primary" disabled={занято || помехи.length > 0}
          title={помехи.length > 0
            ? `сначала закройте помехи: ${помехи[0].what}`
            : 'снять снимок набора: версии зафиксируются, незрелые технологии войдут условными'}
          onClick={базировать}>
          {занято ? 'Снимаю…' : 'Базировать'}
        </button>
      </div>

      {отказ && <div className="v2-locked">{отказ.map((п) => п.what).join('; ')}</div>}
    </div>
  )
}

/** Форма требования: шаблон EARS в форме, линт — пометами по ходу. */
function Форма({ project, onAdded }: { project: string; onAdded: () => void }) {
  const [поля, setПоля] = useState({
    code: '', level: 'system', title: '', statement: '', category: 'functional',
    carrier: '', verification_method: 'test', acceptance_criteria: '',
  })
  const [шаблон, setШаблон] = useState('always')
  const [пометы, setПометы] = useState<LintNote[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  /** Источник обязателен: требование ниоткуда не выводится. */
  const [источники, setИсточники] = useState<{ kind: string; id: string; подпись: string }[]>([])
  const [источник, setИсточник] = useState('')

  useEffect(() => {
    const собрать = async () => {
      const пары = await Promise.all(
        (['goal', 'need', 'constraint'] as const).map(async (вид) => {
          const r = await api.entities(project, вид)
          return r.items.map((с) => ({
            kind: вид,
            id: с.id,
            подпись: `${с.code} · ${String(с.doc.statement ?? с.doc.text ?? с.doc.name ?? '')}`.slice(0, 70),
          }))
        }),
      )
      setИсточники(пары.flat())
    }
    собрать().catch(() => undefined)
  }, [project])

  useEffect(() => {
    if (!поля.statement.trim()) { setПометы([]); return }
    const таймер = setTimeout(() => {
      api.lint(поля.statement, шаблон).then((r) => setПометы(r.notes)).catch(() => undefined)
    }, 400)
    return () => clearTimeout(таймер)
  }, [поля.statement, шаблон])

  const форма = ШАБЛОНЫ.find((ш) => ш.key === шаблон)!
  const уровень = УРОВНИ.find((у) => у.key === поля.level)!

  const завести = () => {
    setЗанято(true); setОтказ(null)
    const выбран = источники.find((и) => и.id === источник)
    api.addRequirement(project, {
      ...поля,
      ears_pattern: шаблон,
      source: выбран ? [{ kind: выбран.kind, ref: выбран.id }] : [],
    })
      .then(() => { setПоля({ ...поля, code: '', title: '', statement: '' }); onAdded() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card">
      <div className="v2-card__head"><span className="v2-card__title">Новое требование</span></div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form">
        <label>Уровень
          <select value={поля.level} onChange={(e) => setПоля({ ...поля, level: e.target.value })}>
            {УРОВНИ.map((у) => <option key={у.key} value={у.key}>{у.title}</option>)}
          </select>
        </label>
        <label>Носитель ({уровень.носитель})
          <input value={поля.carrier} onChange={(e) => setПоля({ ...поля, carrier: e.target.value })}
            placeholder={поля.level === 'interface' ? 'IF-DATA-BUS' : 'OBC-CPU'} />
        </label>
        <label>Заголовок
          <input value={поля.title} onChange={(e) => setПоля({ ...поля, title: e.target.value })} />
        </label>
        <label>Шаблон формулировки
          <select value={шаблон} onChange={(e) => setШаблон(e.target.value)}>
            {ШАБЛОНЫ.map((ш) => <option key={ш.key} value={ш.key}>{ш.title}</option>)}
          </select>
        </label>
        <label>Формулировка
          <textarea rows={2} value={поля.statement} placeholder={форма.форма}
            onChange={(e) => setПоля({ ...поля, statement: e.target.value })} />
        </label>
        <span className="v2-empty__why">Форма: {форма.форма}</span>
        {пометы.map((n) => <Помета key={n.rule + n.what} note={n} />)}
        <label>Источник (откуда выведено)
          <select value={источник} onChange={(e) => setИсточник(e.target.value)}>
            <option value="">— выберите цель, нужду или ограничение —</option>
            {источники.map((и) => <option key={и.id} value={и.id}>{и.подпись}</option>)}
          </select>
        </label>
        <label>Критерий приёмки
          <input value={поля.acceptance_criteria}
            onChange={(e) => setПоля({ ...поля, acceptance_criteria: e.target.value })}
            placeholder="наблюдаемый результат: что считать выполнением" />
        </label>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary"
            disabled={занято || !поля.statement.trim() || !поля.carrier.trim() || !источник}
            title={!поля.carrier.trim()
              ? `укажите носителя: для уровня «${уровень.title}» это ${уровень.носитель}`
              : !источник
                ? 'выберите источник: требование ниоткуда не выводится'
                : 'завести требование; пометы линта не мешают черновику, но держат базирование'}
            onClick={завести}>
            {занято ? 'Завожу…' : 'Завести требование'}
          </button>
        </div>
      </div>
    </div>
  )
}

function кратко(measure: string): string {
  try {
    const м = JSON.parse(measure) as Record<string, unknown>
    const значение = м.value ?? `${м.min ?? ''}…${м.max ?? ''}`
    return `${м.op ?? ''} ${значение} ${м.unit ?? ''}`.trim()
  } catch {
    return measure
  }
}
