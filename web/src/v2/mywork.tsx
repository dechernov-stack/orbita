// Рабочий лист специалиста (шип 2, экран 6; РЕЖИМЫ-РАБОТЫ-ДВУХ-РОЛЕЙ).
//
// «Что я делаю сейчас и что мне мешает» — и ничего больше: поручения по
// сроку (просроченные сверху) и одно открытое мероприятие. Карты фазы, чужих
// сцен и реестров целиком здесь нет; сама поверхность мероприятия живёт в
// разделе «Работа» — отсюда в неё уходят, а не копируют её сюда.
//
// «Завершил» — одно движение: отчёта специалист не пишет, ведущий увидит
// закрытие в «Команде» сам.
import { useCallback, useEffect, useState } from 'react'
import './bridge.css'
import { мостикApi, type Куда, type МояРабота } from './bridge.api'
import { Маркер } from './markers'

export function MyWorkScreen({ project, учётка, onGo }: {
  project: string | null
  /** Чей это лист: поручения адресованы учётке, а не роли. */
  учётка?: { login: string; display_name: string } | null
  onGo: (куда: Куда) => void
}) {
  const [вид, setВид] = useState<МояРабота | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const логин = учётка?.login ?? ''

  const перечитать = useCallback(() => {
    if (!project || !логин) return
    мостикApi.мояРабота(project, логин).then(setВид).catch((e) => setОтказ(String(e.message ?? e)))
  }, [project, логин])
  useEffect(перечитать, [перечитать])

  if (!project) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>Моя работа</h3>
        <div className="v2-empty">Проект не выбран.<span className="v2-empty__why">Поручения живут в проекте.</span></div>
      </div>
    )
  }
  if (!логин) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>Моя работа</h3>
        <div className="v2-empty">
          Учётка не выбрана.
          <span className="v2-empty__why">Лист всегда чей-то: войдите, и здесь будут ваши поручения.</span>
        </div>
      </div>
    )
  }
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><h3>Моя работа</h3><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="работа"><h3>Моя работа</h3><div className="v2-empty">Читаю поручения…</div></div>

  const просрочено = вид.assignments.filter((п) => п.overdue).length
  return (
    <div className="v2-bridge">
      <div className="v2-panel" data-why="работа">
        <h3>
          Моя работа · {учётка?.display_name ?? логин}
          <span className="v2-cnt">
            поручений {вид.assignments.length}
            {просрочено > 0 ? ` · просрочено ${просрочено}` : ''}
          </span>
        </h3>
        {вид.assignments.length === 0 ? (
          <div className="v2-empty">
            Поручений нет.
            <span className="v2-empty__why">{вид.note}</span>
          </div>
        ) : (
          <table className="v2-table">
            <thead><tr><th>Что</th><th>Сцена</th><th>Срок</th><th>От кого</th><th>Что мешает</th><th /></tr></thead>
            <tbody>
              {вид.assignments.map((п) => (
                <tr key={п.code}>
                  <td>
                    <Маркер род="мероприятие" состояние={п.overdue ? 'блок' : 'текущее'} подпись={п.what || п.scene_title} />
                    {' '}{п.what || п.scene_title || п.target.ref}
                  </td>
                  <td>{п.scene}{п.scene_title ? ` · ${п.scene_title}` : ''}</td>
                  <td className={п.overdue ? 'v2-bad' : undefined}>
                    {п.due_point_title || п.due_point}
                    {п.due_date ? ` · ${п.due_date}` : ''}
                    {п.overdue && ' · просрочено'}
                  </td>
                  <td>{п.assigned_by}</td>
                  <td>{п.blocks || <span className="v2-dim">ничего</span>}</td>
                  <td className="v2-inline">
                    <button type="button" className="v2-link"
                      title={`открыть сцену «${п.scene_title || п.scene}» — работа закрывается там`}
                      onClick={() => onGo({ section: 'work', scene: п.scene })}>
                      открыть
                    </button>
                    <button type="button" title="закрыть поручение: ведущий увидит это в «Команде», отчёта писать не нужно"
                      onClick={() => мостикApi.завершить(project, п.code).then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))}>
                      Завершил
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {вид.open_activity && (
        <div className="v2-panel" data-why="следующий-клик">
          <h3>Открытое мероприятие</h3>
          <div className="v2-bridge__route">
            <Маркер род="мероприятие" состояние="текущее"
              подпись={`${вид.open_activity.activity} · ${вид.open_activity.activity_name}`} />
            <span>
              <b>{вид.open_activity.activity} · {вид.open_activity.activity_name}</b>
              {' · '}сцена {вид.open_activity.scene} · {вид.open_activity.scene_title}
            </span>
            <button type="button" className="v2-link"
              title="поверхность мероприятия живёт в разделе «Работа» — открыть её там"
              onClick={() => onGo({ section: 'work', scene: вид.open_activity!.scene })}>
              открыть мероприятие
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
