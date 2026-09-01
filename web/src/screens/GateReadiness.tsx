// О-11 «Готовность к точке» (БРИФ-ГОТОВНОСТЬ, эталон reference-readiness,
// круг 1): телефонная книга умерла — проверки АГРЕГАТАМИ по группам со
// схлопыванием; «Блокирует фиксацию» — первая и всегда раскрыта, закрытые
// группы свёрнуты. Каждая строка: имя человеческим языком · число ·
// «к месту →». Готовность вычисляется, не отмечается — ручных галочек нет;
// «0 объектов» — разрыв; неприменимое — серым с обоснованием (tailoring,
// след с автором, отмена возможна). Фиксации здесь НЕТ — она на цикле у DA.
//
// Механика возврата (объявить/снять) осталась внизу тихо — эталон её не
// рисует, дом возврата — вопрос владельцу в отчёте; функция не потеряна.
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import { DateInput } from '../ui/DateInput'
import { Select } from '../ui/Select'
import { useSession } from '../ui/session'

interface CheckRow {
  id: string
  title: string
  state: 'open' | 'closed' | 'na'
  blocking: boolean
  note: string
  place?: string
  na_rationale?: string
  na_author?: string
  na_at?: string
}

interface ReadinessView {
  horizon_done?: boolean
  gate: string
  label: string
  due?: string
  open_total: number
  blocking_open: number
  total: number
  na_total: number
  groups: Array<{ key: string; title: string; open: number; checks: CheckRow[] }>
}

function shortDate(iso?: string): string | null {
  const m = iso?.match(/^(\d{4})-(\d{2})-(\d{2})/)
  return m ? `${m[3]}.${m[2]}.${m[1]}` : null
}

export function GateReadiness({ project, gate, onGo }: {
  project: string
  /**
   * Круг 4: точка, названная переходом (клик ромба на схеме потока). Без неё
   * экран открывает ближайшую непройденную — «готовность SDR» вела бы к SRR
   * и врала бы молча.
   */
  gate?: string
  onGo: (screen: string) => void
}) {
  const { author } = useSession()
  const [view, setView] = useState<ReadinessView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [collapsed, setCollapsed] = useState<ReadonlySet<string> | null>(null)
  const [naFor, setNaFor] = useState<string | null>(null)
  const [naText, setNaText] = useState('')
  const [activeReturn, setActiveReturn] = useState<{ gate: string; reason: string } | null>(null)
  /** МВП-П1: назначение задания — с разрыва и по группе, с числом. */
  const { authEnabled } = useSession()
  const [users, setUsers] = useState<Array<{ login: string; display_name: string }>>([])
  const [assignFor, setAssignFor] = useState<{ kind: 'check' | 'group'; key: string } | null>(null)
  const [asWho, setAsWho] = useState('')
  const [asDue, setAsDue] = useState('')
  const [asNote, setAsNote] = useState('')

  useEffect(() => {
    if (authEnabled) api.authUsers().then((r) => setUsers(r.users)).catch(() => setUsers([]))
  }, [authEnabled])

  const doAssign = (gaps: Array<{ id: string; title: string; place?: string | null }>) => {
    if (!view || !asWho.trim() || gaps.length === 0) return
    if (gaps.length > 1 && !window.confirm(
      `Назначить разрывов: ${gaps.length} — на ${asWho.trim()}?`,
    )) return
    api.tasksAssign({
      gate: view.gate, gaps, assignee: asWho.trim(),
      due: asDue || undefined, note: asNote.trim() || undefined,
      author: author || 'инженер',
    })
      .then((r) => {
        setNotice(`назначено: ${r.created.length}` +
          (r.existing.length ? `; уже были: ${r.existing.length}` : '') +
          ` — «${asWho.trim()}» увидит их в «Моих заданиях»`)
        setAssignFor(null)
        setAsNote('')
        // бейдж «мои» в шапке узнаёт о назначении сразу, не дожидаясь смены экрана
        window.dispatchEvent(new Event('orbita:tasks-changed'))
      })
      .catch((e) => setNotice(String(e)))
  }

  const assignForm = (gaps: Array<{ id: string; title: string; place?: string | null }>) => (
    <span style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
      {authEnabled && users.length > 0 ? (
        <Select value={asWho} placeholder="исполнитель" width={170}
          options={users.map((u) => ({ key: u.login, title: `${u.display_name} (${u.login})` }))}
          onChange={setAsWho} />
      ) : (
        <input placeholder="исполнитель" value={asWho} style={{ width: 150 }}
          onChange={(e) => setAsWho(e.target.value)} />
      )}
      <DateInput iso={asDue} name="Срок" width={128} onChange={setAsDue} />
      <input placeholder="комментарий" value={asNote} style={{ width: 150 }}
        onChange={(e) => setAsNote(e.target.value)} />
      <button title="назовите исполнителя — задание адресуется человеку, не воздуху" className="rr-btn" disabled={!asWho.trim()} onClick={() => doAssign(gaps)}>
        назначить{gaps.length > 1 ? ` · ${gaps.length}` : ''}
      </button>
      <button className="rr-assign" onClick={() => setAssignFor(null)}>отмена</button>
    </span>
  )

  const load = useCallback(() => {
    api.gateReadiness(gate)
      .then((v) => {
        setView(v)
        // закрытые группы схлопнуты по умолчанию; «Блокирует» всегда раскрыта
        setCollapsed((prev) => prev ?? new Set(
          (v.groups ?? []).filter((g) => g.key !== 'blocking' && g.open === 0).map((g) => g.key),
        ))
      })
      .catch((e) => setError(String(e)))
    edit.object(project)
      .then((p) => {
        const doc = p.doc as { return?: { gate: string; reason: string } }
        setActiveReturn(doc.return ?? null)
      })
      .catch(() => setActiveReturn(null))
  }, [project, gate])
  useEffect(load, [load])

  // свежесть перечня: инженер вернулся чинить — перечень пересчитался
  useEffect(() => {
    const onFocus = () => load()
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onFocus)
    return () => {
      window.removeEventListener('focus', onFocus)
      document.removeEventListener('visibilitychange', onFocus)
    }
  }, [load])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (view?.horizon_done) {
    return (
      <div className="empty">
        Вехи горизонта Формулирования пройдены — готовить нечего. Дальние точки
        видны на жизненном цикле; их проверки появятся с регламентом следующей фазы.
      </div>
    )
  }
  if (!view || !collapsed) return <div className="empty">Загрузка…</div>

  const toggle = (key: string) => {
    if (key === 'blocking') return
    setCollapsed((prev) => {
      const next = new Set(prev ?? [])
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const markNa = (check: string) => {
    api.gateReadinessNa(check, naText, author || 'инженер')
      .then(() => { setNaFor(null); setNaText(''); setNotice(null); load() })
      .catch((e) => setNotice(String(e)))
  }
  const unmarkNa = (check: string) => {
    api.gateReadinessNaRemove(check, author || 'инженер')
      .then(() => { setNotice(null); load() })
      .catch((e) => setNotice(String(e)))
  }

  const ready = view.open_total === 0

  return (
    <>
      <div className="toolbar">
        <h2>Готовность к точке · {view.label}</h2>
        <span className={`gr-big${ready ? ' ok' : ''}`}>
          {ready ? `закрыто всё · ${view.total} из ${view.total}` : `не закрыто · ${view.open_total}`}
        </span>
        {!ready && <span className="secondary">блокирует фиксацию · {view.blocking_open}</span>}
        {shortDate(view.due) && <span className="secondary">план: <span className="mono">{shortDate(view.due)}</span></span>}
      </div>
      <div className="workarea" style={{ padding: '10px 16px', overflow: 'auto' }}>
        {ready && (
          <div className="gr-okband">
            <b>Готово к фиксации.</b>
            <span>
              Все проверки закрыты{view.na_total > 0 ? `; неприменимых — ${view.na_total}, с обоснованием` : ''}.
            </span>
            <span style={{ flex: 1 }} />
            <button className="rr-assign" onClick={() => onGo('lifecycle')}>
              к фиксации на жизненном цикле →
            </button>
          </div>
        )}
        {notice && <div className="warn" style={{ padding: '6px 10px', marginBottom: 10 }}>{notice}</div>}

        {view.groups.map((g) => {
          const isCollapsed = collapsed.has(g.key)
          return (
            <div key={g.key} className="gr-grp">
              <button
                type="button"
                className={`gr-gh${g.key === 'blocking' ? ' block' : ''}`}
                onClick={() => toggle(g.key)}
              >
                <span className="rr-chev">{isCollapsed ? '▸' : '▾'}</span>
                {g.title}
                {g.open > 0
                  ? <span className="gr-n bad">· {g.open}</span>
                  : <span className="gr-n okc">· закрыто</span>}
              </button>
              {/* МВП-П1: по группе — массово, с подтверждением числом */}
              {!isCollapsed && g.open > 1 && (
                <div className="gr-chk" style={{ background: 'transparent' }}>
                  <span className="gr-st na" title="назначение группой" />
                  <span className="gr-tx secondary">открытые разрывы группы — одному исполнителю</span>
                  {assignFor?.kind === 'group' && assignFor.key === g.key
                    ? assignForm(g.checks.filter((c) => c.state === 'open')
                        .map((c) => ({ id: c.id, title: c.title, place: c.place })))
                    : (
                      <button className="gr-nabtn"
                        onClick={() => { setAssignFor({ kind: 'group', key: g.key }) }}>
                        назначить группу · {g.open}…
                      </button>
                    )}
                </div>
              )}
              {!isCollapsed && g.checks.map((c) => (
                <div key={c.id} className={`gr-chk${c.state === 'closed' ? ' closed' : ''}`}>
                  <span className={`gr-st ${c.state === 'open' ? 'bad' : c.state === 'na' ? 'na' : 'okd'}`}
                    title={c.state === 'open' ? 'разрыв открыт' : c.state === 'na' ? 'неприменимо (tailoring)' : 'закрыто'} />
                  <span className="gr-tx" style={c.state === 'na' ? { color: 'var(--text-secondary)' } : undefined}>
                    {c.title}
                  </span>
                  {c.state === 'na' ? (
                    <span className="gr-nawhy">
                      неприменимо: {c.na_rationale} — {c.na_author}{c.na_at ? `, ${shortDate(c.na_at) ?? c.na_at}` : ''}
                      {' '}
                      <button className="rr-assign" onClick={() => unmarkNa(c.id)}>вернуть</button>
                    </span>
                  ) : (
                    <>
                      <span className="gr-num">{c.note}</span>
                      {c.state === 'open' && c.place && (
                        <button className="rr-assign" onClick={() => onGo(c.place!)}>к месту →</button>
                      )}
                      {c.state === 'open' && (
                        assignFor?.kind === 'check' && assignFor.key === c.id
                          ? assignForm([{ id: c.id, title: c.title, place: c.place }])
                          : (
                            <button className="gr-nabtn"
                              title="задание исполнителю: разрыв закроется — задание закроется само"
                              onClick={() => setAssignFor({ kind: 'check', key: c.id })}>
                              назначить…
                            </button>
                          )
                      )}
                      {c.state === 'open' && (
                        naFor === c.id ? (
                          <span style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                            <input
                              placeholder="обоснование неприменимости"
                              value={naText}
                              onChange={(e) => setNaText(e.target.value)}
                              style={{ width: 220 }}
                            />
                            <button title="обоснование неприменимости — не меньше десяти знаков: «н/п» без причины не принимается" className="rr-btn" disabled={naText.trim().length < 10}
                              onClick={() => markNa(c.id)}>
                              записать
                            </button>
                            <button className="rr-assign" onClick={() => { setNaFor(null); setNaText('') }}>
                              отмена
                            </button>
                          </span>
                        ) : (
                          <button className="gr-nabtn" title="tailoring: след с автором, отмена возможна"
                            onClick={() => { setNaFor(c.id); setNaText('') }}>
                            неприменимо…
                          </button>
                        )
                      )}
                    </>
                  )}
                </div>
              ))}
            </div>
          )
        })}

        {/* возврат — механика решения точки; тихо внизу до своего дома */}
        <div className="gr-retops">
          {activeReturn ? (
            <>
              <span className="secondary">
                Действует возврат {activeReturn.gate}: {activeReturn.reason}
              </span>
              <button className="rr-btn" onClick={() => {
                const note = window.prompt('Как снята причина возврата:')
                if (!note) return
                api.gateReturnResolve(author || 'инженер', note)
                  .then(() => { setNotice(null); load() })
                  .catch((e) => setNotice(String(e)))
              }}>
                Снять возврат
              </button>
            </>
          ) : (
            <button className="rr-assign" onClick={() => {
              const reason = window.prompt('Причина возврата (заключение обзора):')
              if (!reason) return
              api.gateReturn(view.gate, author || 'инженер', reason, [])
                .then(() => { setNotice(null); load() })
                .catch((e) => setNotice(String(e)))
            }}>
              возврат по заключению…
            </button>
          )}
        </div>
      </div>
    </>
  )
}
