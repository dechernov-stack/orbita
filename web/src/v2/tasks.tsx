// «Мои задания» — личный разрез того же состояния (КОНЦЕПЦИЯ §Навигация).
//
// Задание не заводится и не закрывается руками: это незакрытое условие
// сцены, адресованное роли. Закрывается работой в своей сцене — поэтому
// каждая строка ведёт туда, а не открывает форму «отметить сделанным».
import { useEffect, useState } from 'react'
import { api, type TaskRow } from './api'

const РОЛЬ: Record<string, string> = {
  lead: 'руководитель проекта',
  lead_se: 'ведущий системный инженер',
  specialist: 'инженер',
  da_review: 'фиксация решения',
}

export function MyTasks({ project, onGoScene }: {
  project: string | null
  onGoScene: (scene: string) => void
}) {
  const [строки, setСтроки] = useState<TaskRow[] | null>(null)
  const [примечание, setПримечание] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    if (!project) return
    api.myTasks(project)
      .then((r) => { setСтроки(r.items); setПримечание(r.note) })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">
          Проект не выбран.
          <span className="v2-empty__why">Задания появляются в проекте: они адресуют разрывы его сцен.</span>
        </div>
      </div>
    )
  }
  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!строки) return <div className="v2-card"><div className="v2-empty">Читаю разрывы…</div></div>

  const активные = строки.filter((с) => !с.waiting)
  const ожидают = строки.filter((с) => с.waiting)

  return (
    <>
      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Мои задания</span>
          <span className="v2-card__count">{активные.length}</span>
        </div>
        {активные.length === 0 ? (
          <div className="v2-empty">
            Разрывов в работе нет.
            <span className="v2-empty__why">{примечание}</span>
          </div>
        ) : (
          <table className="v2-table">
            <thead><tr><th>Сцена</th><th>Что закрыть</th><th>Кто</th><th /></tr></thead>
            <tbody>
              {активные.map((с, i) => (
                <tr key={`${с.scene}-${i}`}>
                  <td className="v2-mono">{с.scene}</td>
                  <td>{с.what}</td>
                  <td>{РОЛЬ[с.role] ?? с.role}</td>
                  <td>
                    <button type="button" onClick={() => onGoScene(с.scene)}
                      title={`открыть сцену «${с.scene_title}» — разрыв закрывается работой в ней`}>
                      к месту →
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {ожидают.length > 0 && (
        <div className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">Ожидают</span>
            <span className="v2-card__count">{ожидают.length}</span>
          </div>
          <p className="v2-empty__why">
            Сцена ещё закрыта: срок не тикает, работа не начата. Откроется сама, когда закроется предыдущая.
          </p>
          <table className="v2-table">
            <thead><tr><th>Сцена</th><th>Чего ждёт</th></tr></thead>
            <tbody>
              {ожидают.map((с, i) => (
                <tr key={`${с.scene}-${i}`}>
                  <td className="v2-mono">{с.scene} · {с.scene_title}</td>
                  <td>{с.what}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
