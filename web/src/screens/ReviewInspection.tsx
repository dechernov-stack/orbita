// Инспекция обзора (NASA SEH App. C): единственный экран системы, где
// состояние ставится РУКОЙ.
//
// Везде остальное вычисляется: ручная галочка — обещание, а не факт. Но
// «прочитать формулировку вслух двумя инженерами и понять одинаково» машине
// не поручить, и владелец оговорил это исключением. Поэтому отметка есть —
// и она несёт автора, время и, если нужно, замечание словами.
//
// Чек-лист не дублирует готовность: вычислимое (трассировка, методы
// верификации, TBD без владельца) живёт разрывами и пометами линта, а здесь
// у каждого пункта стоит адрес — где это смотреть.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useSession } from '../ui/session'
import { Tooltip, Muted } from '../ui/Tooltip'
import type { ReviewChecklistView } from '../api/types'

export function ReviewInspection({ onGo }: { onGo?: (screen: string) => void }) {
  const { author } = useSession()
  const [view, setView] = useState<ReviewChecklistView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<Record<string, string>>({})

  const reload = () => api.reviewChecklist().then(setView).catch((e) => setError(String(e)))
  useEffect(() => { reload() }, [])

  const toggle = (checklist: string, item: string, checked: boolean) => {
    if (!author) return
    api.reviewCheck(checklist, item, author, checked, note[`${checklist}/${item}`])
      .then(setView)
      .catch((e) => setError(String(e)))
  }

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>

  return (
    <>
      <div className="card">
        <h3>Инспекция обзора</h3>
        <p className="secondary" style={{ marginTop: 0 }}>{view.summary}</p>
        <p className="secondary" style={{ margin: 0 }}>
          Это единственное место, где отметка ставится рукой: однозначность формулировок
          проверяют люди. Всё вычислимое — в готовности к точке и пометах реестра.
        </p>
      </div>

      {view.checklists.map((c) => (
        <div className="card" key={c.id}>
          <h4 style={{ margin: '0 0 4px' }}>
            {c.name} <span className="mono secondary">{c.id}</span>
          </h4>
          {c.source && <p className="secondary" style={{ marginTop: 0 }}>Источник: {c.source}</p>}
          <table className="grid">
            <thead>
              <tr>
                <th style={{ width: 30 }} />
                <th>Что проверяется</th>
                <th style={{ width: 190 }}>Где смотреть</th>
                <th style={{ width: 210 }}>Кто и когда</th>
              </tr>
            </thead>
            <tbody>
              {c.items.map((it) => (
                <tr key={it.key}>
                  <td>
                    <input type="checkbox" checked={it.checked} disabled={!author}
                      title={author
                        ? (it.checked ? 'снять отметку' : 'отметить: проверено мной')
                        : 'представьтесь в шапке: отметка подписывается вашим именем'}
                      onChange={() => toggle(c.id, it.key, it.checked)} />
                  </td>
                  <td className="wrap">
                    {it.title}
                    {it.hint && <div className="secondary">{it.hint}</div>}
                    {it.note && <div className="secondary">замечание: {it.note}</div>}
                  </td>
                  <td className="wrap secondary">
                    {it.evidence}
                    {it.screen && onGo && (
                      <div>
                        <button className="np-linkish" onClick={() => onGo(it.screen!)}
                          title="открыть место, где этот пункт проверяется">
                          к месту →
                        </button>
                      </div>
                    )}
                  </td>
                  <td>
                    {it.checked ? (
                      <Tooltip text="инспекция людей: отметка несёт того, кто её поставил">
                        <span>{it.author} · {it.at}</span>
                      </Tooltip>
                    ) : (
                      <>
                        <Muted why="пункт ещё не проверен человеком" />
                        {author && (
                          <input placeholder="замечание (необязательно)" style={{ width: '100%', marginTop: 4 }}
                            value={note[`${c.id}/${it.key}`] ?? ''}
                            onChange={(e) => setNote((p) => ({ ...p, [`${c.id}/${it.key}`]: e.target.value }))} />
                        )}
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </>
  )
}
