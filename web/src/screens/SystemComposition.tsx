// Экран «Дерево состава» — одно дерево носителей (ADR-044). Слева — вхождения
// с уровнем и кратностью: система → сегмент → элемент → подсистема →
// компонент; узел КА открывается как «Модель аппарата» — вид того же узла,
// а не отдельная сущность. Построения — вхождения КА ×N: подгруппа даёт число
// аппаратов, свёртку массы группировки считает сервер. Справа — спецификация
// выбранного определения и карточка требования, распределённого на него.
//
// Клиент ничего не складывает и не умножает: кратности, массы и претензии
// сборки приходят готовыми (правило обхода кода клиента).
import { useEffect, useState } from 'react'
import { useSession } from '../ui/session'
import { api } from '../api/client'
import type { CompositionTree, RequirementCard } from '../api/types'
import { ComponentSpec } from './ComponentSpec'

const KIND_LABEL: Record<string, string> = {
  system: 'система',
  segment: 'сегмент',
  element: 'элемент',
  subsystem: 'подсистема',
  component: 'компонент',
  assembly: 'сборка',
}

const ROLE_LABEL: Record<string, string> = {
  spacecraft: 'КА',
  platform: 'платформа',
  payload: 'полезная нагрузка',
  subsystem: 'подсистема',
  ground_station: 'станция',
  terminal: 'терминал',
}

export function SystemComposition({ onGo }: { onGo?: (screen: string, kind?: string, target?: string) => void }) {
  const [tree, setTree] = useState<CompositionTree | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [reqId, setReqId] = useState<string | null>(null)
  const [card, setCard] = useState<RequirementCard | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** Замечание Б3-01: подтверждение опциональных узлов и добор зависимого. */
  const [busyCode, setBusyCode] = useState<string | null>(null)
  const [confirmNote, setConfirmNote] = useState<string | null>(null)
  const [needTopUp, setNeedTopUp] = useState(false)
  const { author } = useSession()

  const loadTree = () =>
    api
      .compositionTree()
      .then((t) => {
        setTree(t)
        const first = t.rows[0]?.definition ?? t.definitions_without_usage[0]?.id
        if (first) setSelected((cur) => cur ?? first)
      })
      .catch((e) => setError(String(e)))

  useEffect(() => { void loadTree() }, [])

  const confirmNode = (fragment: string, code: string) => {
    if (busyCode) return
    setBusyCode(code)
    setConfirmNote(null)
    api.libraryConfirm(fragment, author, [code])
      .then((r) => {
        setConfirmNote(`${code}: узел заведён (создано ${r.created.length}) — зависимые полки можно добрать`)
        setNeedTopUp(true)
        return loadTree()
      })
      .catch((e) => setConfirmNote(String(e)))
      .finally(() => setBusyCode(null))
  }

  const topUp = () => {
    if (busyCode) return
    setBusyCode('topup')
    api.libraryTopUp(author)
      .then((r) => {
        const строки = r.fragments.filter((f) => f.created > 0).map((f) => `${f.name}: +${f.created}`)
        setConfirmNote(r.created > 0
          ? `добор: создано ${r.created} — ${строки.join('; ')}`
          : 'добор: новых записей нет — всё разрешимое уже взято')
        setNeedTopUp(false)
      })
      .catch((e) => setConfirmNote(String(e)))
      .finally(() => setBusyCode(null))
  }

  useEffect(() => {
    if (!reqId) {
      setCard(null)
      return
    }
    api.requirementCard(reqId).then(setCard).catch((e) => setError(String(e)))
  }, [reqId])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!tree) return <div className="empty">Загрузка дерева состава…</div>

  const pick = (definition: string) => {
    setSelected(definition)
    setReqId(null)
  }

  const empty = tree.rows.length === 0 && tree.definitions_without_usage.length === 0

  return (
    <div
      style={{
        gridArea: 'main',
        display: 'grid',
        gridTemplateColumns: '460px minmax(0, 1fr)',
        minHeight: 0,
        minWidth: 0,
      }}
    >
      <div className="pane" style={{ borderRight: '1px solid var(--border)' }}>
        <h3 className="pbs-head">
          Состав системы <span className="secondary">· вхождений: {tree.rows.length}</span>
          {(tree.pending_optional?.length ?? 0) > 0 && (
            <div className="warn" style={{ marginTop: 6, padding: 6, fontSize: 12 }}>
              Не подтверждены необязательные узлы каркаса: {tree.pending_optional!.length}
              <span className="secondary"> — зависимые стыки, функции и пакеты работ ждут их</span>
              <div style={{ marginTop: 4 }}>
                {tree.pending_optional!.map((n) => (
                  <div key={n.code}>
                    <span className="mono">{n.code}</span> {n.name}
                    <button type="button" className="rr-assign" style={{ marginLeft: 6 }}
                      disabled={busyCode !== null}
                      title="заводит узел из уже взятого каркаса; родитель — по коду"
                      onClick={() => confirmNode(n.fragment, n.code)}>
                      {busyCode === n.code ? 'завожу…' : 'подтвердить'}
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
          {(needTopUp || confirmNote) && (
            <div className="secondary" style={{ marginTop: 4, fontSize: 12 }}>
              {confirmNote}
              {needTopUp && (
                <button type="button" className="rr-assign" style={{ marginLeft: 6 }} disabled={busyCode !== null}
                  title="повторное взятие применённых полок создаёт только теперь разрешимые записи"
                  onClick={topUp}>
                  {busyCode === 'topup' ? 'добираю…' : 'добрать зависимое из взятых полок →'}
                </button>
              )}
            </div>
          )}
        </h3>
        {empty ? (
          <div className="empty">
            Вхождений в дереве нет: заведите определения на Ш4 «Элементы и интерфейсы» и
            вхождения к ним — состав строится по вхождениям, а не по списку определений.
          </div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Узел</th>
                <th style={{ width: 92 }}>Уровень</th>
                <th style={{ width: 44, textAlign: 'right' }}>×N</th>
                <th style={{ width: 76, textAlign: 'right' }}>Масса, кг</th>
                <th style={{ width: 62 }}>Вид</th>
              </tr>
            </thead>
            <tbody>
              {tree.rows.map((r) => (
                <tr
                  key={r.usage || `def:${r.definition}`}
                  className="pbs-row"
                  tabIndex={0}
                  onClick={() => pick(r.definition)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      pick(r.definition)
                    }
                  }}
                  style={{ background: r.definition === selected ? 'rgba(11,95,255,0.08)' : undefined }}
                >
                  <td style={{ paddingLeft: 8 + r.level * 14, whiteSpace: 'normal' }}>
                    <span className="mono secondary" style={{ marginRight: 6 }}>{r.definition}</span>
                    {r.name}
                    {r.role && (
                      <span className="chip" style={{ marginLeft: 6 }}>{ROLE_LABEL[r.role] ?? r.role}</span>
                    )}
                    {r.by_definition && (
                      <span className="secondary" style={{ marginLeft: 6 }} title="узел заведён определением, вхождение ему ещё не заведено">
                        по определению
                      </span>
                    )}
                  </td>
                  <td className="secondary">{KIND_LABEL[r.kind] ?? r.kind}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>{r.multiplier}</td>
                  <td className="mono" style={{ textAlign: 'right' }}>
                    {r.mass_total_kg !== undefined ? r.mass_total_kg.toFixed(2) : '—'}
                  </td>
                  <td>
                    {r.role === 'spacecraft' && onGo && (
                      <button
                        type="button"
                        className="tab"
                        title="Открыть модель аппарата — вид этого узла"
                        onClick={(e) => {
                          e.stopPropagation()
                          onGo('spacecraft', 'component', r.definition)
                        }}
                      >
                        аппарат
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <h3 className="pbs-head">
          Построения <span className="secondary">· вхождений КА: {tree.constellations.reduce((n, c) => n + c.subgroups.length, 0)}</span>
        </h3>
        {tree.constellations.length === 0 ? (
          <div className="empty">Построений с вхождениями КА нет: подгруппа построения — вхождение узла КА ×N.</div>
        ) : (
          tree.constellations.map((c) => (
            <div key={c.id} className="card" style={{ margin: '4px 8px' }}>
              <div>
                <span className="mono secondary">{c.id}</span> {c.name}
                <span className="secondary"> · аппаратов: {c.satellites}</span>
                {c.mass_total_kg !== undefined ? (
                  <span className="secondary"> · масса группировки: {c.mass_total_kg.toFixed(1)} кг</span>
                ) : (
                  <span className="secondary"> · {c.mass_note}</span>
                )}
              </div>
              <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
                {c.subgroups.map((s) => (
                  <li key={s.usage}>
                    {s.subgroup}: <span className="mono">{s.usage}</span> → {s.name} ×{s.quantity}
                    {s.mass_total_kg !== undefined && <span className="secondary"> · {s.mass_total_kg.toFixed(1)} кг</span>}
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}

        {tree.carriers.some((c) => c.problems.length > 0) && (
          <>
            <h3 className="pbs-head">
              Сборка аппарата <span className="secondary">· претензий: {tree.carriers.reduce((n, c) => n + c.problems.length, 0)}</span>
            </h3>
            {tree.carriers.map((c) => (
              c.problems.length > 0 && (
                <div key={c.id} className="card" style={{ margin: '4px 8px' }}>
                  <div><span className="mono secondary">{c.id}</span> {c.name}</div>
                  <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
                    {c.problems.map((p) => <li key={p}>{p}</li>)}
                  </ul>
                </div>
              )
            ))}
          </>
        )}

        {tree.definitions_without_usage.length > 0 && (
          <>
            <h3 className="pbs-head">
              Определения без вхождений <span className="secondary">· {tree.definitions_without_usage.length}</span>
            </h3>
            <table>
              <tbody>
                {tree.definitions_without_usage.map((d) => (
                  <tr
                    key={d.id}
                    className="pbs-row"
                    tabIndex={0}
                    onClick={() => pick(d.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        pick(d.id)
                      }
                    }}
                    style={{ background: d.id === selected ? 'rgba(11,95,255,0.08)' : undefined }}
                  >
                    <td><span className="mono secondary" style={{ marginRight: 6 }}>{d.id}</span>{d.name}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>

      <div style={{ minWidth: 0, minHeight: 0, display: 'grid' }}>
        {reqId && card ? (
          <div className="pane" style={{ padding: 16 }}>
            <button type="button" className="tab" onClick={() => setReqId(null)}>
              ← к узлу {selected}
            </button>
            <h2 style={{ fontSize: 15 }}>
              <span className="id">{card.row.id}</span> {card.row.statement}
            </h2>
            <div className="field">
              <label>Статус</label>
              {card.row.status}
            </div>
            {card.successCriterion && (
              <div className="field">
                <label>Критерий успеха</label>
                <span className="mono">{card.successCriterion}</span>
              </div>
            )}
            <div className="field">
              <label>Источники</label>
              {card.sources.map((s) => (
                <span key={s} className="chip">{s}</span>
              ))}
            </div>
            <div className="field">
              <label>Распределено на</label>
              {card.allocatedTo.map((a) => (
                <button key={a} type="button" className="tab" onClick={() => pick(a)}>
                  {a}
                </button>
              ))}
            </div>
          </div>
        ) : (
          selected && <ComponentSpec componentId={selected} onSelectRequirement={setReqId} />
        )}
      </div>
    </div>
  )
}
