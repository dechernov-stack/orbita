// «Мои задания» (МВП-П1) — личный разрез готовности: разрывы участника со
// ссылками «к месту», просроченное первым, «ожидают» отдельно от активных.
// Статус вычисляется закрытием разрыва — ручного «сделано» здесь нет.
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import { useSession } from '../ui/session'

type TaskRow = Awaited<ReturnType<typeof api.myTasks>>['tasks'][number]

function shortDate(iso?: string): string | null {
  const m = iso?.match(/^(\d{4})-(\d{2})-(\d{2})/)
  return m ? `${m[3]}.${m[2]}.${m[1]}` : null
}

/** Счётчик «моих» в шапке (МВП-П1): активные и просроченные — числом. */
export function MyTasksBadge({ tick, onGo }: { tick: string; onGo: (screen: string) => void }) {
  const { author, user } = useSession()
  const me = user?.login ?? author
  const [counts, setCounts] = useState<{ active: number; overdue: number } | null>(null)

  const load = useCallback(() => {
    if (!me) { setCounts(null); return }
    api.myTasks(me)
      .then((v) => setCounts(v.counts))
      .catch(() => setCounts(null))
  }, [me])
  useEffect(load, [load, tick])
  useEffect(() => {
    const onFocus = () => load()
    window.addEventListener('focus', onFocus)
    window.addEventListener('orbita:tasks-changed', onFocus)
    return () => {
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('orbita:tasks-changed', onFocus)
    }
  }, [load])

  if (!counts || counts.active + counts.overdue === 0) return null
  return (
    <button className="header__gate" onClick={() => onGo('mytasks')}
      title="мои задания — разрывы, назначенные вам; просроченное первым">
      <span className="secondary">мои</span>
      <span className={`count${counts.overdue > 0 ? '' : ' count--ready'}`}>
        {counts.active + counts.overdue}{counts.overdue > 0 ? ` · просрочено ${counts.overdue}` : ''}
      </span>
    </button>
  )
}

export function MyTasks({ onGo }: { onGo: (screen: string) => void }) {
  const { author, user, authEnabled } = useSession()
  const me = user?.login ?? author
  const isLead = Boolean(user && Object.values(user.roles).some((r) => r === 'lead' || r === 'lead_se'))
  const canAll = !authEnabled || isLead
  const [all, setAll] = useState(false)
  const [view, setView] = useState<Awaited<ReturnType<typeof api.myTasks>> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(() => {
    api.myTasks(all ? undefined : me || undefined)
      .then(setView)
      .catch((e) => setError(String(e)))
  }, [all, me])
  useEffect(load, [load])
  // свежесть: вернулись с места починки — состояние пересчиталось
  useEffect(() => {
    const onFocus = () => load()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [load])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>

  const groups: Array<{ key: string; title: string; rows: TaskRow[] }> = [
    { key: 'overdue', title: 'Просроченные', rows: view.tasks.filter((t) => t.overdue) },
    { key: 'active', title: 'Активные', rows: view.tasks.filter((t) => t.state === 'active' && !t.overdue) },
    { key: 'waiting', title: 'Ожидают', rows: view.tasks.filter((t) => t.state === 'waiting') },
    { key: 'done', title: 'Закрытые', rows: view.tasks.filter((t) => t.state === 'done') },
  ]

  const drop = (t: TaskRow) => {
    if (!window.confirm(`Снять задание «${t.title}» с ${t.assignee}? Мягкая отмена, история сохраняется.`)) return
    edit.cancel(t.id, author || 'инженер')
      .then(() => { setNotice(null); load() })
      .catch((e) => setNotice(String(e)))
  }

  return (
    <>
      <div className="toolbar">
        <h2>Моя работа</h2>
        <span className="secondary">
          активных {view.counts.active}
          {view.counts.overdue > 0 && <b className="warn"> · просрочено {view.counts.overdue}</b>}
          {view.counts.waiting > 0 && <> · ожидают {view.counts.waiting}</>}
          {view.counts.works > 0 && <> · работ фазы {view.counts.works}</>}
        </span>
        <div className="grow" />
        {canAll && (
          <button className="rr-assign" onClick={() => setAll((v) => !v)}
            title="руководитель видит всё; каждый — своё">
            {all ? 'только мои' : 'все задания проекта'}
          </button>
        )}
      </div>
      <div className="workarea" style={{ padding: '10px 16px', overflow: 'auto' }}>
        {notice && <div className="warn" style={{ padding: '6px 10px', marginBottom: 8 }}>{notice}</div>}
        {/* Круг 8: «моё» — это и задания с разрывов, и работы фазы, где я
            ответственный. Два списка на одном экране, а не два экрана. */}
        {view.works.length > 0 && (
          <div className="gr-grp">
            <div className="gr-gh" style={{ cursor: 'default' }}>
              Работы фазы<span className="gr-n okc">· {view.works.length}</span>
            </div>
            <table className="grid">
              <tbody>
                {view.works.map((w) => (
                  <tr key={w.id}>
                    <td style={{ width: 340 }}>{w.name}</td>
                    <td className="secondary">
                      {w.kind === 'step'
                        ? (w.done ? 'шаг сделан' : 'шаг не сделан')
                        : (w.next_step ? `дальше: ${w.next_step}` : 'шаги пройдены')}
                      {(w.gaps ?? 0) > 0 && <span className="warn"> · разрывы {w.gaps}</span>}
                    </td>
                    <td style={{ width: 120 }} className="secondary">{w.gate ? `к ${w.gate}` : ''}</td>
                    <td style={{ width: 130 }}>
                      <button className="rr-assign" onClick={() => onGo(w.place ?? 'phasework')}
                        title={w.place ? 'открыть место работы' : 'открыть работу фазы'}>
                        {w.place ? 'к месту →' : 'к работе →'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {view.tasks.length === 0 && view.works.length === 0 && (
          <div className="empty">
            Ни заданий, ни работ на вас нет. Задания назначаются с экрана
            «Готовность» (на разрыве «назначить…»), ответственный за работу
            фазы — в таблице Ганта на вкладке «Работа».
          </div>
        )}
        {groups.filter((g) => g.rows.length > 0).map((g) => (
          <div key={g.key} className="gr-grp">
            <div className="gr-gh" style={{ cursor: 'default' }}>
              {g.title}<span className={`gr-n ${g.key === 'overdue' ? 'bad' : 'okc'}`}>· {g.rows.length}</span>
            </div>
            {g.rows.map((t) => (
              <div key={t.id} className={`gr-chk${t.state === 'done' ? ' closed' : ''}`}>
                <span className={`gr-st ${t.overdue ? 'bad' : t.state === 'done' ? 'okd' : t.state === 'waiting' ? 'na' : 'bad'}`}
                  title={t.overdue ? 'просрочено' : t.state === 'done' ? 'разрыв закрыт — задание закрыто' : t.state === 'waiting' ? `ожидает: ${t.waits_on}` : 'активно'} />
                <span className="gr-tx" style={t.state === 'done' ? { color: 'var(--text-secondary)' } : undefined}>
                  {t.title}
                  <span className="secondary"> · {t.gate}</span>
                  {all && <span className="secondary"> · {t.assignee}</span>}
                  {t.note && <span className="secondary" title={t.note}> · {t.note.length > 40 ? `${t.note.slice(0, 40)}…` : t.note}</span>}
                </span>
                {t.state === 'waiting' && (
                  <span className="gr-num" title="срок не тикает, пока вход не готов; вход закроется — задание станет активным само">
                    ожидает: {t.waits_on}
                  </span>
                )}
                {t.due && (
                  <span className={`gr-num${t.overdue ? ' bad' : ''}`}
                    title={t.overdue ? 'срок прошёл' : 'срок задания'}>
                    к {shortDate(t.due)}
                  </span>
                )}
                {t.state !== 'done' && t.place && (
                  <button className="rr-assign" onClick={() => onGo(t.place!)}>к месту →</button>
                )}
                {t.state !== 'done' && canAll && (
                  <button className="rr-assign" title="мягкая отмена задания, история сохраняется"
                    onClick={() => drop(t)}>
                    снять
                  </button>
                )}
              </div>
            ))}
          </div>
        ))}
      </div>
    </>
  )
}
