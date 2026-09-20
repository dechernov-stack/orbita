// План работ фазы (мероприятие 0.P): даты точек и окна сцен.
//
// Внутренний обзор держался на «план работ фазы не задан: ленте нечего
// показывать», а задать его было НЕГДЕ: маршрут `/v2/plan` есть с волны 3,
// экрана не было ни одного (проход владельца 20.09, после того как все
// двенадцать сцен были прожиты).
//
// Устройство простое: слева точки фазы с плановыми датами, справа сцены с
// окном «с — по». Ничего не придумывается: даты ставит человек, а лента и
// ворота читают их как есть. Пустая дата — это «не запланировано», а не ноль.
import { useCallback, useEffect, useState } from 'react'
import { api, type Phase } from './api'

export function PhasePlan({ phase, project, onChanged }: {
  phase: Phase
  project: string
  onChanged: () => void
}) {
  const [даты, setДаты] = useState<Record<string, string>>({})
  const [окна, setОкна] = useState<Record<string, { start: string; end: string }>>({})
  const [задан, setЗадан] = useState(false)
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [открыт, setОткрыт] = useState(false)

  const перечитать = useCallback(() => {
    api.plan(project)
      .then((п) => {
        setЗадан(п.planned)
        setДаты(Object.fromEntries((п.gate_dates ?? []).map((д) => [д.gate, д.date])))
        setОкна(Object.fromEntries((п.scene_windows ?? []).map((о) => [о.scene, { start: о.start, end: о.end }])))
      })
      .catch(() => undefined)
  }, [project])
  useEffect(перечитать, [перечитать])

  const безОкна = phase.scenes.filter((с) => !окна[с.key]?.start || !окна[с.key]?.end)

  const записать = () => {
    setЗанято(true); setОтказ(null); setИтог(null)
    api.setPlan(project, {
      phase: phase.phase,
      author: 'инженер',
      gate_dates: Object.entries(даты)
        .filter(([, д]) => д)
        .map(([gate, date]) => ({ gate, date })),
      scene_windows: Object.entries(окна)
        .filter(([, о]) => о.start && о.end)
        .map(([scene, о]) => ({ scene, start: о.start, end: о.end })),
    })
      .then(() => { setИтог('план записан: лента и точки читают его как есть'); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        План работ фазы
        <span className="v2-cnt">
          {задан ? 'задан' : 'не задан'}
          {безОкна.length > 0 && ` · без окна ${безОкна.length} из ${phase.scenes.length}`}
        </span>
        <span className="v2-head__spacer" />
        <button type="button" className="v2-chip" aria-pressed={открыт} onClick={() => setОткрыт(!открыт)}
          title="мероприятие 0.P: даты точек и окна сцен">
          {открыт ? 'свернуть' : 'задать план'}
        </button>
      </h3>
      <div className="v2-empty__why">
        Внутренний обзор ждёт план: без дат точек и окон сцен ленте нечего показывать,
        а сроки рисков не с чем сравнивать.
      </div>

      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-empty__why">{итог}</div>}

      {открыт && (
        <div className="v2-form">
          {phase.gates.map((т) => (
            <label key={т.key}>{т.title} — плановая дата
              <input type="date" name={`план.точка.${т.key}`} value={даты[т.key] ?? ''}
                onChange={(e) => setДаты({ ...даты, [т.key]: e.target.value })} />
            </label>
          ))}

          {phase.scenes.map((с) => (
            <span key={с.key} className="v2-field">
              <span className="v2-field__cap">Сцена {с.key} · {с.title}</span>
              <span className="v2-row2">
                <input type="date" name={`план.сцена.${с.key}.start`} aria-label={`сцена ${с.key}: начало`}
                  value={окна[с.key]?.start ?? ''}
                  onChange={(e) => setОкна({
                    ...окна, [с.key]: { start: e.target.value, end: окна[с.key]?.end ?? '' },
                  })} />
                <input type="date" name={`план.сцена.${с.key}.end`} aria-label={`сцена ${с.key}: конец`}
                  value={окна[с.key]?.end ?? ''}
                  onChange={(e) => setОкна({
                    ...окна, [с.key]: { start: окна[с.key]?.start ?? '', end: e.target.value },
                  })} />
              </span>
            </span>
          ))}

          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занято}
              title="записать план: точки получат даты, сцены — окна; пустая дата означает «не запланировано»"
              onClick={записать}>
              {занято ? 'Записываю…' : 'Записать план'}
            </button>
            <span className="v2-empty__why">
              Пустую дату план не записывает: «не запланировано» — это не ноль.
            </span>
          </div>
        </div>
      )}
    </div>
  )
}
