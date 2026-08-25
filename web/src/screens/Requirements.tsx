// Экран 3 — требования: дерево, условие, свёртка, V&V.
// Экран 3б — карточка требования в правой панели.
//
// Все числа и строки берутся из /views/requirement-tree и /views/requirements/{id}
// как есть. Клиент не считает ни глубину отступа, ни свёртку, ни критерий успеха.
import { useCallback, useEffect, useState } from 'react'
import { RequirementMatrices, type MatrixKind } from './RequirementMatrices'
import { edit } from '../api/edit'
import { api } from '../api/client'
import { requestObject, screenOfObject, takeObject } from '../api/intent'
import type { RequirementCard, RequirementRow, RequirementTreeView } from '../api/types'
import { ObjectEditor } from '../ui/ObjectEditor'
import { BudgetGauge, Condition, StatusDot, Verification } from '../ui/parts'
import { useSession } from '../ui/session'

/** Разрезы реестра (список после MCR, п. 6): дерево — по декомпозиции,
 *  остальные группируют плоско — по уровню, категории, источнику,
 *  распределению на элемент/интерфейс. Группы считает клиент из полей строки:
 *  сами поля пришли с сервера. */
const GROUPINGS: Array<{ key: string; title: string }> = [
  { key: 'tree', title: 'Дерево' },
  { key: 'level', title: 'По уровню' },
  { key: 'category', title: 'По категории' },
  { key: 'source', title: 'По источнику' },
  { key: 'allocation', title: 'По распределению' },
]

export function Requirements({ onGo }: { onGo?: (screen: string) => void }) {
  const { label, author } = useSession()
  const [tree, setTree] = useState<RequirementTreeView | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [massReport, setMassReport] = useState<string | null>(null)
  const [massBusy, setMassBusy] = useState(false)
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState(false)
  const [card, setCard] = useState<RequirementCard | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [onlyViolated, setOnlyViolated] = useState(false)
  const [grouping, setGrouping] = useState('tree')
  /** Вкладка: дерево или одна из матриц (шаг 16 §2.4). */
  const [matrix, setMatrix] = useState<MatrixKind | null>(null)
  const [reqifIssues, setReqifIssues] = useState<string[]>([])
  const [importReport, setImportReport] = useState<string | null>(null)

  // Замечания отображения — рядом с кнопкой выгрузки (ADR-023): файл при них
  // валиден, терпит принимающий инструмент, поэтому предупреждаем до выгрузки
  useEffect(() => {
    api
      .reqifCheck()
      .then((c) => setReqifIssues([...c.mapping_issues, ...c.flattened.map((f) => `${f}: составное значение свёрнуто в строку`)]))
      .catch(() => setReqifIssues([]))
  }, [])

  // Импорт ReqIF (ADR-024, канал «требования»): файл разбирает служба обмена,
  // сюда возвращаются черновики — хранение идёт обычным каналом
  const importReqif = async (file: File) => {
    setImportReport(null)
    try {
      const parsed = await api.importReqif(await file.text())
      setImportReport(
        `разобрано черновиков: ${parsed.drafts.length}` +
          (parsed.source_title ? ` из «${parsed.source_title}»` : ''),
      )
    } catch (e) {
      setImportReport(String(e))
    }
  }

  const reload = useCallback(
    () =>
      api
        .requirementTree()
        .then((next) => {
          setTree(next)
          setError(null)
        })
        .catch((e) => setError(String(e))),
    [],
  )

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    if (!selected) return setCard(null)
    api.requirementCard(selected).then(setCard).catch((e) => setError(String(e)))
  }, [selected])

  // Переход «к объекту» с другого экрана (готовность, разрыв документа)
  useEffect(() => {
    if (!tree) return
    const wanted = takeObject()
    if (!wanted) return
    if (tree.rows.some((r) => r.id === wanted)) setSelected(wanted)
    else requestObject(wanted)
  }, [tree])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!tree) return <div className="empty">Загрузка…</div>

  // Порядок обхода задан деревом с сервера: roots и children — оттуда же.
  const ordered: RequirementRow[] = []
  const byId = new Map(tree.rows.map((r) => [r.id, r]))
  const walk = (id: string) => {
    const row = byId.get(id)
    if (!row) return
    ordered.push(row)
    ;(tree.children[id] ?? []).forEach(walk)
  }
  tree.roots.forEach(walk)
  const rows = onlyViolated ? ordered.filter((r) => r.budgetOverrun) : ordered

  // Группировка (п. 6): плоские разрезы поверх тех же строк. Требование с
  // несколькими источниками/распределениями попадает в каждую свою группу —
  // это разрез для чтения, а не разбиение множества.
  const groups: Array<{ title: string; rows: RequirementRow[] }> | null = (() => {
    if (grouping === 'tree') return null
    const flat = [...rows].sort((a, b) => a.id.localeCompare(b.id))
    const by = new Map<string, RequirementRow[]>()
    const put = (key: string, r: RequirementRow) => {
      const list = by.get(key) ?? []
      list.push(r)
      by.set(key, list)
    }
    flat.forEach((r) => {
      if (grouping === 'level') put(r.level === 'project' ? 'Уровень проекта' : 'Системные', r)
      else if (grouping === 'category') put(r.category ? label('req_category', r.category) : 'без категории', r)
      else if (grouping === 'source') {
        if (r.sources.length === 0) put('без источника', r)
        r.sources.forEach((s) => put(s, r))
      } else {
        if (r.allocatedTo.length === 0) put('не распределено', r)
        r.allocatedTo.forEach((a) => put(a, r))
      }
    })
    return Array.from(by.entries())
      .sort((a, b) => a[0].localeCompare(b[0], 'ru'))
      .map(([title, list]) => ({ title, rows: list }))
  })()

  const inspectorOpen = creating || editing || card != null
  return (
    <div className={inspectorOpen ? 'split' : 'pane'}
      style={inspectorOpen
        // Карточке — ширину (второй заход): при открытой карточке таблица
        // ужимается до ключевых колонок, остальное видно в самой карточке
        ? { gridTemplateColumns: 'minmax(0, 1fr) minmax(340px, 560px)' }
        : undefined}>
      <div className="pane">
        <div className="pane__tools">
          <button
            type="button"
            className="tab tab--primary"
            onClick={() => {
              setCreating(true)
              setEditing(true)
              setSelected(null)
            }}
          >
            + Добавить требование
          </button>
          {/* Массовое действие реестра (§3.2, блок E): базирование пачкой —
              каждый объект проходит ту же проверку, что одиночный promote;
              не прошедшие называются поимённо */}
          <button type="button" className="tab" disabled={!author || massBusy}
            title={author ? 'перевести все требования не в Baseline' : 'представьтесь в шапке'}
            onClick={() => {
              const ids = tree?.rows.filter((r) => r.status !== 'Baseline').map((r) => r.id) ?? []
              if (ids.length === 0) { setMassReport('всё уже базировано'); return }
              setMassBusy(true)
              api.promoteBatch(ids, 'Baseline', author)
                .then((r) => {
                  setMassReport(`базировано ${r.promoted.length}; отказов ${r.failed.length}` +
                    (r.failed.length ? ` — ${r.failed.slice(0, 3).map((f) => `${f.id}: ${f.reason}`).join('; ')}${r.failed.length > 3 ? '…' : ''}` : ''))
                  void reload()
                })
                .catch((e) => setMassReport(String(e)))
                .finally(() => setMassBusy(false))
            }}>
            {massBusy ? 'Базирование…' : 'Базировать все'}
          </button>
          {massReport && <span className="secondary">{massReport}</span>}
          <button
            type="button"
            className="tab"
            aria-selected={onlyViolated}
            onClick={() => setOnlyViolated((v) => !v)}
          >
            Бюджет нарушен
          </button>
          <span style={{ flex: 1 }} />
          {/* Матрицы живут здесь, где принимается решение по требованию, —
              отдельного экрана «Отчёты» нет намеренно (шаг 16 §2.4) */}
          {/* Разрезы реестра (п. 6): дерево и плоские группировки */}
          <select value={grouping} onChange={(e) => { setGrouping(e.target.value); setMatrix(null) }}
            title="как разложить реестр: деревом декомпозиции либо группами">
            {GROUPINGS.map((g) => <option key={g.key} value={g.key}>{g.title}</option>)}
          </select>
          <button type="button" className="tab" aria-selected={matrix === 'trace'} onClick={() => setMatrix('trace')}>
            Трассировка
          </button>
          <button
            type="button"
            className="tab"
            aria-selected={matrix === 'verification'}
            onClick={() => setMatrix('verification')}
          >
            Верификация
          </button>
          <button
            type="button"
            className="tab"
            aria-selected={matrix === 'validation'}
            onClick={() => setMatrix('validation')}
          >
            Валидация
          </button>
          {/* Выгрузка (TZ-OUT-005: «в ReqIF и CSV») и импорт (ADR-024) */}
          <a className="tab" href={api.exportUrls.reqif} download>
            ReqIF↓
          </a>
          <a className="tab" href={api.exportUrls.csv} download>
            CSV↓
          </a>
          <a className="tab" href={api.exportUrls.exchangeJson} download title="формат обмена reqif-lite, только наружу">
            JSON↓
          </a>
          <label className="tab" style={{ cursor: 'pointer' }}>
            ReqIF↑
            <input
              type="file"
              accept=".reqif,.xml"
              style={{ display: 'none' }}
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) void importReqif(f)
                e.target.value = ''
              }}
            />
          </label>
        </div>
        {reqifIssues.length > 0 && (
          <div className="warn" style={{ padding: '4px 8px' }}>
            Отображение ReqIF с замечаниями: {reqifIssues.join('; ')}
          </div>
        )}
        {importReport && <div className="empty" style={{ padding: '4px 8px' }}>{importReport}</div>}

        {matrix !== null && <RequirementMatrices kind={matrix} />}
        {matrix === null && (
        <>

        {ordered.length === 0 && (
          <div className="empty">
            Требований пока нет. Требование не бывает сиротой: укажите в traces_up нужду
            или сервис, из которых оно следует.
          </div>
        )}
        <table>
          <thead>
            {/* Формулировка идёт БЕЗ ширины и забирает остаток, вспомогательные
                колонки урезаны до необходимого. При table-layout: fixed остаток —
                это всё, что не заняли колонки с шириной, поэтому их сумма и есть
                бюджет читаемости главной колонки (шаг 15 §2, дефект 1). */}
            <tr>
              <th style={{ width: 96 }}>ID</th>
              <th>Требование</th>
              {!inspectorOpen && <th style={{ width: 108 }}>Источники</th>}
              {!inspectorOpen && <th style={{ width: 108 }}>Распределение</th>}
              {!inspectorOpen && <th style={{ width: 110 }}>Условие</th>}
              {!inspectorOpen && <th style={{ width: 110 }}>Свёртка</th>}
              {!inspectorOpen && <th style={{ width: 120 }}>Метод V&amp;V</th>}
              <th style={{ width: 100 }}>Статус</th>
            </tr>
          </thead>
          <tbody>
            {(groups ?? [{ title: '', rows }]).map((g) => (
              [
                g.title ? (
                  <tr key={`h:${g.title}`}>
                    <td colSpan={inspectorOpen ? 3 : 8} style={{ background: 'var(--surface-raised, #f3f4f6)', fontWeight: 600 }}>
                      {g.title} <span className="secondary">· {g.rows.length}</span>
                    </td>
                  </tr>
                ) : null,
                ...g.rows.map((row) => (
                  <tr
                    key={`${g.title}:${row.id}`}
                    aria-selected={row.id === selected}
                    onClick={() => {
                      setSelected(row.id)
                      setCreating(false)
                      setEditing(false)
                    }}
                  >
                    <td>
                      {/* отступ по уровню — depth пришёл с сервера; в плоских
                          разрезах иерархия не рисуется */}
                      <span style={{ paddingLeft: groups ? 0 : row.depth * 16 }}>
                        {!groups && <span className="twisty">{row.hasChildren ? '▸' : ''}</span>}
                        <span className="id">{row.id}</span>
                      </span>
                    </td>
                    <td title={row.statement}>{row.statement}</td>
                    {!inspectorOpen && <td>
                      {row.sources.map((s, i) => (
                        <span key={s}>
                          {i > 0 && ' '}
                          <button type="button" className="id" title="открыть источник"
                            style={{ cursor: 'pointer', border: 0, background: 'none', padding: 0 }}
                            onClick={(e) => {
                              e.stopPropagation()
                              const scr = screenOfObject(s)
                              if (scr) { requestObject(s); onGo?.(scr) }
                            }}>
                            {s}
                          </button>
                        </span>
                      ))}
                    </td>}
                    {!inspectorOpen && <td>
                      {row.allocatedTo.length === 0
                        ? <span className="secondary">—</span>
                        : row.allocatedTo.map((a, i) => (
                          <span key={a}>
                            {i > 0 && ' '}
                            <button type="button" className="id" title="открыть элемент/интерфейс"
                              style={{ cursor: 'pointer', border: 0, background: 'none', padding: 0 }}
                              onClick={(e) => {
                                e.stopPropagation()
                                const scr = screenOfObject(a)
                                if (scr) { requestObject(a); onGo?.(scr) }
                              }}>
                              {a}
                            </button>
                          </span>
                        ))}
                    </td>}
                    {!inspectorOpen && <td>
                      <Condition condition={row.condition} />
                    </td>}
                    {!inspectorOpen && <td>
                      <BudgetGauge bar={row.budget} />
                    </td>}
                    {!inspectorOpen && <td>
                      <Verification method={row.method} approach={row.approach} issues={row.planIssues} />
                    </td>}
                    <td>
                      <StatusDot status={row.status} />
                      <span className="secondary">{label('lifecycle', row.status)}</span>
                    </td>
                  </tr>
                )),
              ]
            ))}
          </tbody>
        </table>
        </>
        )}
      </div>

      {inspectorOpen && (
      <aside className="pane pane--side">
        {creating || editing ? (
          <ObjectEditor
            kind="requirement"
            schemaName="core/requirement"
            title="требование"
            id={creating ? null : selected}
            onSaved={(id) => {
              setCreating(false)
              setSelected(id)
              void reload()
            }}
            onCancelled={() => {
              setSelected(null)
              setEditing(false)
              void reload()
            }}
          />
        ) : card ? (
          <>
            <div className="pane__tools" style={{ padding: '0 0 8px' }}>
              <button type="button" className="tab" onClick={() => setEditing(true)}>
                Править требование
              </button>
            </div>
            <Card card={card} />
          </>
        ) : (
          <div className="secondary">Выберите требование</div>
        )}
      </aside>
      )}
    </div>
  )
}

/** Экран 3б: карточка требования — структурное условие и события верификации. */
function Card({ card }: { card: RequirementCard }) {
  /** Цепочка обхода трассировки: за один запрос, с глубиной (TZ-REQ-003). */
  const [chain, setChain] = useState<{ kind: string; hops: Array<{ id: string; depth: number }> } | null>(null)

  const walkChain = (kind: 'ancestors' | 'descendants') => {
    const call = kind === 'ancestors' ? edit.ancestors : edit.descendants
    call(card.row.id)
      .then((hops) => setChain({ kind: kind === 'ancestors' ? 'предки' : 'потомки', hops }))
      .catch(() => setChain({ kind: 'ошибка обхода', hops: [] }))
  }

  return (
    <div>
      <h2 style={{ fontSize: 15, margin: '0 0 4px' }}>
        <span className="id">{card.row.id}</span>
      </h2>
      <p style={{ marginTop: 0 }}>{card.row.statement}</p>

      <div className="card">
        <h3>Условие</h3>
        <div>
          <div className="field">
            <label>Показатель</label>
            <span>{card.row.condition?.name ?? '—'}</span>
          </div>
          <div className="field">
            <label>Значение</label>
            <Condition condition={card.row.condition} />
          </div>
          <div className="field">
            <label>Единица</label>
            {/* код СИ хранится в модели, подпись — только для отображения */}
            <span className="mono">
              {card.row.condition?.unit ?? '—'}
              {card.row.condition?.unitLabel && (
                <span className="secondary"> · {card.row.condition.unitLabel}</span>
              )}
            </span>
          </div>
        </div>
      </div>

      <div className="card">
        <h3>Верификация</h3>
        <div>
          <div className="field">
            <label>Критерий успеха</label>
            <span className="secondary mono">{card.successCriterion ?? '—'}</span>
          </div>
          {card.events.length === 0 && <div className="amber">△ события верификации не запланированы</div>}
          {card.events.map((event) => (
            <div key={event.id} className="field">
              <label>
                <span className="id">{event.id}</span> · {event.kind} · {event.phase}
                {event.closes && <span className="chip"> закрывающее</span>}
              </label>
              <div>
                <span className="chip">{event.method}</span>{' '}
                {event.approach ? (
                  <span className="secondary">{event.approach}</span>
                ) : (
                  <span className="amber">△ не описано, как выполняется проверка</span>
                )}
              </div>
              {event.means && <div className="secondary">Чем: {event.means}</div>}
              {event.evidenceRef && (
                <div>
                  <span className="chip">{event.evidenceRef}</span>{' '}
                  {event.evidenceStale && <span className="warn chip">устарело</span>}
                </div>
              )}
              {event.issues.map((issue) => (
                <div key={issue} className="amber">
                  △ {issue}
                </div>
              ))}
            </div>
          ))}
        </div>
      </div>

      <div className="card">
        <h3>Трассировка</h3>
        <div>
          <div className="field">
            <label>Источники</label>
            {card.sources.map((s) => (
              <span key={s} className="chip">
                {s}
              </span>
            ))}
          </div>
          <div className="field">
            <label>Распределено на</label>
            {card.allocatedTo.map((a) => (
              <span key={a} className="chip">
                {a}
              </span>
            ))}
          </div>
          <div className="field">
            <button type="button" className="tab" onClick={() => walkChain('ancestors')}>
              Предки
            </button>
            <button type="button" className="tab" onClick={() => walkChain('descendants')}>
              Потомки
            </button>
          </div>
          {chain && (
            <div className="field">
              <label>{chain.kind}</label>
              {chain.hops.length === 0 ? (
                <span className="secondary">цепочка пуста</span>
              ) : (
                chain.hops.map((h) => (
                  <span key={h.id} className="chip" title={`глубина ${h.depth}`}>
                    {h.id}
                  </span>
                ))
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
