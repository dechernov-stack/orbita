// Жизненный цикл проекта (блок D, дизайн: экран lifecycle): лента шести
// контрольных точек и карта операций текущей фазы в три колонки. Состояние
// операций считает сервер (ADR-029 п. 6) — клиент только показывает и ведёт
// на рабочее место операции.
import { useEffect, useState } from 'react'
import { api, type OperationsView } from '../api/client'
import { edit } from '../api/edit'

interface GateRow {
  gate: string
  due: string | null
  held: boolean
  /** Дата выведена цепочкой длительностей, а не задана якорем. */
  computed?: boolean
  duration_days?: number
  /** Точка в горизонте ИС (ворота ведут) или плановая веха Phase B–F. */
  in_scope?: boolean
  phase?: string
  /** Дней от предыдущей датированной точки — считает сервер (STEP-6 §3.2). */
  days_from_prev?: number
  /** Просрочена (дата в прошлом, точка не пройдена) — вердикт сервера. */
  overdue?: boolean
}

const STATE_LABEL: Record<string, string> = {
  Done: 'выполнена',
  InProgress: 'в работе',
  NotStarted: 'не начата',
  NotMeasurable: 'нечем измерить',
}

/**
 * Связка между соседними точками (второй заход прогона, замечание к п. 1):
 * длительность стоит МЕЖДУ карточками, а не на отдельной шкале. Число дней
 * приходит С СЕРВЕРА (days_from_prev): расчётов в клиенте нет (STEP-6 §3.2),
 * обход кода клиента это стережёт — и поймал первую версию, считавшую дни сама.
 */
function GateLink({ from, to }: { from: GateRow; to: GateRow }) {
  const days = to.days_from_prev ?? null
  return (
    <div className="gatelink" title={days == null
      ? 'длительность появится, когда у обеих точек будут плановые даты (паспорт проекта)'
      : `${from.gate} → ${to.gate}: ${days} дн.`}>
      <span className={days == null ? 'secondary' : ''} style={{ fontSize: 10.5, whiteSpace: 'nowrap' }}>
        {days == null ? 'даты нет' : `${days} дн.`}
      </span>
      <span className="gatelink__arrow">→</span>
    </div>
  )
}

export function Lifecycle({ project, onGo }: { project: string; onGo: (screen: string) => void }) {
  const [gates, setGates] = useState<GateRow[] | null>(null)
  const [ops, setOps] = useState<OperationsView | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [suggested, setSuggested] = useState<Array<{ gate: string; phase: string }>>([])
  /** Правки плана (длительность/якорь по вехам) до сохранения одной кнопкой. */
  const [planEdits, setPlanEdits] = useState<Record<string, { duration_days?: number | null; due?: string | null }>>({})
  const [planBusy, setPlanBusy] = useState(false)
  const [outlookBusy, setOutlookBusy] = useState(false)
  const [outlookNote, setOutlookNote] = useState<string | null>(null)

  const loadGates = () => {
    api.gates()
      .then((g) => {
        const v = g as unknown as { gates: GateRow[]; suggested_outlook?: Array<{ gate: string; phase: string }> }
        setGates(v.gates)
        setSuggested(v.suggested_outlook ?? [])
      })
      .catch((e) => setError(String(e)))
  }

  useEffect(() => {
    loadGates()
    api.operations()
      .then(setOps)
      .catch((e) => setError(String(e)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!gates || !ops) return <div className="empty">Загрузка…</div>

  const columns: OperationsView['operations'][number][][] = [[], [], []]
  ops.operations.forEach((o, i) => columns[i % 3].push(o))

  return (
    <>
      <div className="toolbar">
        <h2>Жизненный цикл</h2>
        <span className="secondary">
          фаза {ops.phase === 'phase_a' ? 'Phase A' : 'Pre-Phase A'}
          {ops.next_gate && <> · ближайшая точка <b className="mono">{ops.next_gate}</b></>}
        </span>
        <div className="grow" />
        {/* Линейность без мастера (список после MCR, п. 3): спина процесса
            сама называет следующий незакрытый шаг и ведёт на его рабочее
            место — свобода ходить по экранам при этом не отнимается.
            Неизмеримые операции пропускаются: кнопка, вечно зовущая к шагу,
            который никогда не станет «выполнен», — не подсказка, а капкан
            (второй заход прогона). */}
        {(() => {
          const next = ops.operations.find(
            (o) => o.state !== 'Done' && o.state !== 'NotMeasurable' && o.screen,
          )
          if (!next) return null
          return (
            <button className="btn" onClick={() => onGo(next.screen!)}
              title={`${next.name} — ${next.executor}`}>
              следующий шаг: {next.code} {next.name.length > 34 ? `${next.name.slice(0, 34)}…` : next.name} →
            </button>
          )
        })()}
        {/* Проект идёт через контрольные точки — и перспектива есть ТЕ ЖЕ
            точки (находка прогона: текстовые поля сбоку — не план). Кнопка
            добавляет стандартные вехи Phase B–F (NPR 7120.5) в паспорт;
            даты инженер правит там же. ИС эти точки показывает, не проводит. */}
        {suggested.length > 0 && (
          <button className="btn" disabled={outlookBusy}
            title={`добавить в контрольные точки: ${suggested.map((s) => s.gate).join(', ')} — даты правятся в паспорте`}
            onClick={() => {
              setOutlookBusy(true)
              setOutlookNote(null)
              edit.object(project)
                .then((p) => {
                  const doc = p.doc as Record<string, unknown>
                  const cur = Array.isArray(doc.milestones) ? (doc.milestones as unknown[]) : []
                  const next = [...cur, ...suggested.map((s) => ({ gate: s.gate, phase: s.phase }))]
                  // паспорт может быть базирован: правка идёт процедурой с
                  // основанием; статус честно вернётся в черновик
                  return edit.changeWithRef(project, { ...doc, milestones: next },
                    'план по фазам: добавлены стандартные вехи Phase B–F (NPR 7120.5)')
                })
                .then(() => {
                  setOutlookNote('Вехи добавлены. Паспорт вернулся в черновик — задайте даты и ре-базируйте его в «Паспорте проекта».')
                  loadGates()
                })
                .catch((e) => setOutlookNote(String(e)))
                .finally(() => setOutlookBusy(false))
            }}>
            {outlookBusy ? 'Добавление…' : '+ вехи Phase B–F'}
          </button>
        )}
        {Object.keys(planEdits).length > 0 && (
          <button className="btn btn--primary" disabled={planBusy}
            title="одной правкой паспорта, с основанием"
            onClick={() => {
              setPlanBusy(true)
              setOutlookNote(null)
              edit.object(project)
                .then((p) => {
                  const doc = p.doc as Record<string, unknown>
                  const ms = (Array.isArray(doc.milestones) ? doc.milestones : []) as Array<Record<string, unknown>>
                  const next = ms.map((m) => {
                    const e = planEdits[String(m.gate)]
                    if (!e) return m
                    const out = { ...m }
                    if (e.duration_days !== undefined) {
                      if (e.duration_days == null) delete out.duration_days
                      else out.duration_days = e.duration_days
                    }
                    if (e.due !== undefined) {
                      if (!e.due) delete out.due
                      else out.due = e.due
                    }
                    return out
                  })
                  return edit.changeWithRef(project, { ...doc, milestones: next },
                    'план по фазам: длительности этапов и якорные даты')
                })
                .then(() => {
                  setPlanEdits({})
                  setOutlookNote('План сохранён. Паспорт вернулся в черновик — ре-базируйте его в «Паспорте проекта».')
                  loadGates()
                })
                .catch((e) => setOutlookNote(String(e)))
                .finally(() => setPlanBusy(false))
            }}>
            {planBusy ? 'Сохранение…' : `Сохранить план (${Object.keys(planEdits).length})`}
          </button>
        )}
        <button className="btn btn--primary" onClick={() => onGo('readiness')}>Готовность к точке</button>
      </div>
      <div className="workarea">
        {outlookNote && <div className="notice" style={{ margin: '8px 14px 0' }}>{outlookNote}</div>}
        <div className="gatestrip">
          {gates.map((g, i) => {
            const overdue = g.overdue === true
            return [
              i > 0 ? <GateLink key={`l${i}`} from={gates[i - 1]} to={g} /> : null,
              <div key={g.gate}
                className={`gatecard ${g.held ? 'gatecard--held' : g.gate === ops.next_gate ? 'gatecard--next' : ''}`}
                style={g.in_scope === false ? { opacity: 0.62 } : undefined}
                title={g.in_scope === false ? 'плановая веха за горизонтом Формулирования: ИС её показывает, но не проводит' : undefined}>
                <div className="mono" style={{ fontWeight: 600 }}>{g.gate}</div>
                <div className="secondary" style={{ fontSize: 11.5 }}>
                  {g.held ? 'пройдена'
                    : g.gate === ops.next_gate ? 'ближайшая'
                    : g.in_scope === false ? `${g.phase ?? 'Phase B+'} · план`
                    : 'впереди'}
                </div>
                {g.due ? (
                  <div className="mono" style={{ fontSize: 11, color: overdue ? 'var(--status-error, #b3261e)' : undefined }}>
                    {g.due}{g.computed ? ' ⟲' : ''}{overdue ? ' · просрочена' : ''}
                  </div>
                ) : (
                  <div className="secondary" style={{ fontSize: 10.5 }}>дата не задана</div>
                )}
                {/* Планирование длительностями прямо на ленте (находка
                    прогона: «ориентируемся на длительности этапов; в
                    неудобном документе работать сложно») — дни этапа и
                    якорная дата правятся здесь, даты хвоста пересчитает
                    сервер. Сохранение — одной кнопкой в шапке. */}
                {!g.held && (
                  <div style={{ marginTop: 4, display: 'flex', gap: 4, alignItems: 'center' }}>
                    <input type="number" min={1} placeholder="дн."
                      title="длительность этапа до вехи, дней"
                      style={{ width: 52, fontSize: 11 }}
                      value={planEdits[g.gate]?.duration_days ?? g.duration_days ?? ''}
                      onChange={(e) => {
                        const raw = e.target.value
                        const v = raw ? Number(raw) : null
                        setPlanEdits((prev) => ({ ...prev, [g.gate]: { ...prev[g.gate], duration_days: v } }))
                      }} />
                    <input type="date"
                      title="якорная дата (сильнее расчёта); пусто — дата выводится из длительностей"
                      style={{ width: 118, fontSize: 11 }}
                      value={planEdits[g.gate]?.due ?? (g.computed ? '' : g.due ?? '')}
                      onChange={(e) => {
                        setPlanEdits((prev) => ({ ...prev, [g.gate]: { ...prev[g.gate], due: e.target.value || null } }))
                      }} />
                  </div>
                )}
              </div>,
            ]
          })}
        </div>
        <div className="ops__group" style={{ padding: '0 14px 6px' }}>
          Операции фазы — {ops.operations.length}
        </div>
        <div className="opsmap">
          {columns.map((col, ci) => (
            <div key={ci}>
              {col.map((o) => (
                <button key={o.code} className="opsmap__row" style={{ width: '100%' }}
                  title={`${o.name} — ${o.executor}`}
                  onClick={() => o.screen && onGo(o.screen)}>
                  <span className={`ops__state ops__state--${o.state} ${o.returned_to ? 'ops__state--returned' : ''}`} />
                  <span className="mono" style={{ minWidth: 30 }}>{o.code}</span>
                  <span className="name">{o.name}</span>
                  <span className="secondary" style={{ marginLeft: 'auto', fontSize: 11 }}>
                    {o.returned_to ? 'возврат' : STATE_LABEL[o.state] ?? o.state}
                  </span>
                </button>
              ))}
            </div>
          ))}
        </div>
      </div>
    </>
  )
}
