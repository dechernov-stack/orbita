// О-10 «Жизненный цикл» (БРИФ-ЖИЗНЕННЫЙ-ЦИКЛ, эталон reference-lifecycle,
// круг 1): лента вех — главная и единственная (пройденные зелёным с датой
// факта и решением; ближайшая — акцентом со счётчиком незакрытого, клик →
// готовность, и фиксацией «фиксирует DA»; будущие — план ОДНОЙ строкой,
// «дата не задана» — тихо, водопад запрещён; горизонт следующей фазы —
// тихой подписью). Возврат — полосой между лентой и паспортом. Активности
// и операций на экране нет (бриф §5, §8) — активность живёт в портфеле,
// мастер-механика операций — отдельно.
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import { countPhrase } from '../ui/countPhrase'
import { DateInput } from '../ui/DateInput'
import { useSession } from '../ui/session'

interface GateRow {
  gate: string
  label?: string
  due?: string | null
  held?: boolean
  held_at?: string
  decision_rationale?: string
  /** Точка в горизонте ИС (ворота ведут) или плановая веха Phase B–F. */
  in_scope?: boolean
  phase?: string
  open_count?: number
  overdue?: boolean
  /** Ф-01: опору календаря считает сервер — точка, от которой открывается
   *  эта; на границе фаз это последняя точка предыдущей фазы. */
  opens_from?: {
    gate: string
    label?: string
    due?: string
    phase_boundary?: boolean
    note?: string
  }
}

interface GatesView {
  gates: GateRow[]
  phase?: string
  return?: { gate: string; reason: string; at: string; open_reviews: number }
  passport?: {
    owner: string
    mission_class?: { id: string; name: string }
    constraints?: Array<{ code?: string; text: string }>
    start_path?: {
      status: string
      step: number
      profile_ref?: string
      created_counts?: Record<string, number>
    }
  }
}

const CHANGE_REF = 'планирование дат жизненного цикла'

function shortDate(iso?: string | null): string | null {
  if (!iso) return null
  const m = iso.slice(0, 10).match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return m ? `${m[3]}.${m[2]}.${m[1]}` : null
}

export function Lifecycle({ project, onGo }: {
  project: string
  onGo: (screen: string) => void
}) {
  const { author } = useSession()
  const [view, setView] = useState<GatesView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [fixOpen, setFixOpen] = useState(false)
  const [rationale, setRationale] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    api.gates()
      .then((g) => setView(g as unknown as GatesView))
      .catch((e) => setError(String(e)))
  }, [])
  useEffect(load, [load, project])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>

  const gates = view.gates
  const nextAt = gates.findIndex((g) => !g.held)
  const horizonAt = gates.findIndex((g) => g.in_scope === false)
  const lastScoped = [...gates].reverse().find((g) => g.in_scope !== false)?.gate

  /** Правка даты вехи — процедурой с основанием: паспорт может быть базирован. */
  const setDue = async (gate: string, iso: string) => {
    try {
      const cur = await edit.object(project)
      const doc = cur.doc as { milestones?: Array<Record<string, unknown>> }
      const milestones = (doc.milestones ?? []).map((m) => {
        if (m.gate !== gate) return m
        const next = { ...m }
        if (iso) next.due = iso
        else delete next.due
        return next
      })
      await edit.changeWithRef(project, { ...(cur.doc as object), milestones }, CHANGE_REF)
      setNotice(null)
      load()
    } catch (e) {
      setNotice(String(e))
    }
  }

  const fix = () => {
    if (busy || nextAt < 0) return
    setBusy(true)
    api.gatePass(gates[nextAt].gate, author || 'инженер', rationale)
      .then(() => { setFixOpen(false); setRationale(''); setNotice(null); load() })
      .catch((e) => setNotice(String(e)))
      .finally(() => setBusy(false))
  }

  const sp = view.passport?.start_path
  const counts = sp?.created_counts ?? {}

  return (
    <>
      <div className="toolbar">
        <h2>Жизненный цикл</h2>
      </div>
      <div className="workarea" style={{ padding: '14px 20px' }}>
        <div className="lc2-lane">
          {gates.map((g, i) => {
            const past = Boolean(g.held)
            const next = i === nextAt
            const horizon = g.in_scope === false
            const returned = Boolean(view.return && view.return.gate === g.gate)
            return (
              <div key={g.gate} className={`lc2-g${past ? ' past' : ''}${next ? ' next' : ''}`}>
                {i < gates.length - 1 && <span className="lc2-lnk" />}
                <span className="lc2-dot"
                  title={past ? 'точка пройдена' : returned ? 'действует возврат от точки' : 'точка впереди'}>
                  {past ? '✓' : returned ? '↩' : ''}
                </span>
                <div className="lc2-nm" style={horizon ? { color: 'var(--text-secondary)' } : undefined}>
                  {g.label ?? g.gate}
                </div>
                {past ? (
                  <div className="lc2-dt">пройден <span className="mono">{shortDate(g.held_at) ?? shortDate(g.due) ?? ''}</span></div>
                ) : horizon ? (
                  <div className="lc2-dt quiet">{g.phase ?? 'Phase B+'}</div>
                ) : (
                  // план — ОДНОЙ строкой: DateInput с опорой (бриф §6);
                  // пустая дата — тихое «дд.мм.гггг» самого компонента
                  <div className="lc2-dt">
                    <DateInput
                      iso={g.due ?? ''}
                      anchor={g.opens_from?.due}
                      anchorName={g.opens_from?.label ?? g.opens_from?.gate}
                      name={g.label ?? g.gate}
                      width={136}
                      onChange={(v) => void setDue(g.gate, v)}
                    />
                    {g.opens_from?.phase_boundary && (
                      <div className="secondary" title={g.opens_from.note}>
                        граница фаз: от {g.opens_from.label ?? g.opens_from.gate}
                      </div>
                    )}
                  </div>
                )}
                {past && g.decision_rationale && (
                  <div className="lc2-st"><span className="lc2-pass">{g.decision_rationale}</span></div>
                )}
                {next && !horizon && (
                  <>
                    {g.open_count !== undefined && (
                      <div className="lc2-st">
                        <button className="lc2-cnt" title="что мешает точке — в готовность"
                          onClick={() => onGo('readiness')}>
                          не закрыто · {g.open_count}
                        </button>
                      </div>
                    )}
                    <div className="lc2-fix">
                      {fixOpen ? (
                        <span style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                          <input
                            placeholder="основание решения"
                            value={rationale}
                            onChange={(e) => setRationale(e.target.value)}
                            style={{ width: 180 }}
                          />
                          <button className="rr-btn rr-btn--pri" disabled={busy || !rationale.trim()} onClick={fix}>
                            Провести
                          </button>
                          <button className="rr-btn" onClick={() => setFixOpen(false)}>Отмена</button>
                        </span>
                      ) : (
                        <button className="rr-btn rr-btn--pri" onClick={() => setFixOpen(true)}>
                          Зафиксировать прохождение
                        </button>
                      )}
                      <div className="lc2-rolehint">фиксирует DA</div>
                    </div>
                  </>
                )}
                {horizon && i === horizonAt && (
                  <div className="lc2-hz">
                    проверки появятся с регламентом {g.phase ?? 'Phase B'}
                    {lastScoped ? ` после ${lastScoped}` : ''}
                  </div>
                )}
              </div>
            )
          })}
        </div>

        {view.return && (
          <div className="lc2-retband">
            <b>Возврат {view.return.gate}{shortDate(view.return.at) ? ` · ${shortDate(view.return.at)}` : ''}.</b>
            <span>{view.return.reason}</span>
            <span style={{ flex: 1 }} />
            <button className="lc2-retlink" onClick={() => onGo('rfa')}>
              замечания · {view.return.open_reviews} →
            </button>
          </div>
        )}

        {notice && <div className="warn" style={{ padding: '6px 10px', marginBottom: 12 }}>{notice}</div>}

        {view.passport && (
          <div className="lc2-pass2">
            <div className="lc2-k">
              Паспорт проекта
              <button className="rr-assign" onClick={() => onGo('projreg')}>изменить</button>
            </div>
            <div className="lc2-prow">
              <span className="l">Руководитель</span><span>{view.passport.owner}</span>
              {view.passport.mission_class && (
                <>
                  <span className="l">Класс миссии</span>
                  <span>{view.passport.mission_class.name}</span>
                </>
              )}
              {(view.passport.constraints ?? []).length > 0 && (
                <>
                  <span className="l">Ограничения</span>
                  <span className="lc2-pchips">
                    {(view.passport.constraints ?? []).map((c, i) => (
                      <span key={`${c.code ?? ''}-${i}`} className="lc2-pchip">
                        {c.text}{c.code ? ` (${c.code})` : ''}
                      </span>
                    ))}
                  </span>
                </>
              )}
            </div>
            {sp && (
              <div className="lc2-startline">
                {sp.status === 'done' ? (
                  <>
                    Начало проекта пройдено
                    {Object.keys(counts).length > 0 &&
                      `: ${Object.entries(counts).map(([t, n]) => countPhrase(t, n)).join(' · ')}`}
                    {sp.profile_ref ? ` · профиль ${sp.profile_ref}` : ''}
                    {' · '}
                    <button className="rr-assign" onClick={() => onGo('startpath')}>настроить заново</button>
                  </>
                ) : sp.status === 'in_progress' ? (
                  <>
                    Начало проекта: шаг {sp.step} из 3 ·{' '}
                    <button className="rr-assign" onClick={() => onGo('startpath')}>продолжить</button>
                  </>
                ) : (
                  <>Начало проекта пропущено.</>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </>
  )
}
