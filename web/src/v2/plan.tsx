// План работ фазы (мероприятие 0.P): даты точек и окна сцен.
//
// Первая версия (20.09) была перечнем из 27 полей подряд — владелец назвал её
// «записками сумасшедшего», и справедливо: точки и сцены шли вперемешку, связи
// между датами не было никакой, окно можно было задать задом наперёд (сцена 2:
// начало 10.10, конец 25.09 — так и легло в план), а после заполнения экран
// продолжал требовать план, потому что подсказка была написана навсегда.
//
// Устройство теперь такое:
//   · точки идут ПО ПОРЯДКУ, у каждой одна дата; порядок проверяется словами;
//   · сцены сгруппированы под ТОЙ точкой, которая их ждёт (`scene.awaited_by`),
//     у каждой окно «с — по»;
//   · «Разложить окна» раскладывает сцены между предыдущей точкой и своей —
//     это и есть связь по датам, а правит человек;
//   · шапка говорит состояние («даты 3 из 3 · окна 12 из 12»), а не требует
//     того, что уже сделано.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type Gate, type Phase, type Scene } from './api'

type Окно = { start: string; end: string }

export function PhasePlan({ phase, project, onChanged }: {
  phase: Phase
  project: string
  onChanged: () => void
}) {
  const [даты, setДаты] = useState<Record<string, string>>({})
  const [окна, setОкна] = useState<Record<string, Окно>>({})
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

  /** Точки фазы по порядку; вехи технологий план работ не ведёт. */
  const точки = useMemo(
    () => [...phase.gates].sort((а, б) => а.order - б.order),
    [phase.gates],
  )

  /** Сцены под своей точкой: точка ждёт сцену — значит закрывает её окно. */
  const поТочкам = useMemo(() => {
    const карта = new Map<string, Scene[]>()
    точки.forEach((т) => карта.set(т.key, []))
    const прочие: Scene[] = []
    phase.scenes.forEach((с) => {
      const своя = точки.find((т) => с.awaited_by.some((имя) => имя.includes(т.title)))
      if (своя) карта.get(своя.key)!.push(с)
      else прочие.push(с)
    })
    return { карта, прочие }
  }, [phase.scenes, точки])

  const безОкна = phase.scenes.filter((с) => !окна[с.key]?.start || !окна[с.key]?.end)
  const безДаты = точки.filter((т) => !даты[т.key])

  /** Что не сходится в плане — словами, до записи. */
  const замечания = useMemo(() => {
    const список: string[] = []
    точки.forEach((т, i) => {
      const прежняя = точки[i - 1]
      if (прежняя && даты[прежняя.key] && даты[т.key] && даты[т.key] < даты[прежняя.key]) {
        список.push(`${т.title} (${даты[т.key]}) раньше, чем ${прежняя.title} (${даты[прежняя.key]})`)
      }
    })
    phase.scenes.forEach((с) => {
      const о = окна[с.key]
      if (о?.start && о?.end && о.end < о.start) {
        список.push(`сцена ${с.key}: конец ${о.end} раньше начала ${о.start}`)
      }
      const своя = точки.find((т) => (поТочкам.карта.get(т.key) ?? []).some((х) => х.key === с.key))
      if (своя && о?.end && даты[своя.key] && о.end > даты[своя.key]) {
        список.push(`сцена ${с.key} кончается ${о.end}, а её точка «${своя.title}» — ${даты[своя.key]}`)
      }
    })
    return список
  }, [точки, даты, окна, phase.scenes, поТочкам])

  /**
   * Разложить окна по датам точек: сцены каждой точки делят отрезок от
   * предыдущей точки (или от сегодня) до своей. Ровно, без выходных и
   * трудоёмкостей — это предложение, а не расчёт: человек правит.
   */
  const разложить = () => {
    const сегодня = new Date().toISOString().slice(0, 10)
    const новые: Record<string, Окно> = { ...окна }
    let начало = сегодня
    точки.forEach((т) => {
      const свои = поТочкам.карта.get(т.key) ?? []
      const конец = даты[т.key]
      if (!конец || свои.length === 0) return
      const от = new Date(начало).getTime()
      const до = new Date(конец).getTime()
      if (!(до > от)) return
      const шаг = (до - от) / свои.length
      свои.forEach((с, i) => {
        const с1 = new Date(от + шаг * i).toISOString().slice(0, 10)
        const с2 = new Date(от + шаг * (i + 1)).toISOString().slice(0, 10)
        новые[с.key] = { start: с1, end: с2 }
      })
      начало = конец
    })
    поТочкам.прочие.forEach((с) => {
      if (!новые[с.key] && точки.length > 0 && даты[точки[0].key]) {
        новые[с.key] = { start: сегодня, end: даты[точки[0].key] }
      }
    })
    setОкна(новые)
    setИтог('окна разложены по датам точек — поправьте, где надо, и запишите')
  }

  const записать = () => {
    setЗанято(true); setОтказ(null); setИтог(null)
    api.setPlan(project, {
      phase: phase.phase,
      author: 'инженер',
      gate_dates: Object.entries(даты).filter(([, д]) => д).map(([gate, date]) => ({ gate, date })),
      scene_windows: Object.entries(окна)
        .filter(([, о]) => о.start && о.end)
        .map(([scene, о]) => ({ scene, start: о.start, end: о.end })),
    })
      .then(() => { setИтог('план записан: лента и точки читают его как есть'); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  const строкаСцены = (с: Scene, точка?: Gate) => (
    <span key={с.key} className="v2-field">
      <span className="v2-field__cap">
        Сцена {с.key} · {с.title}
        {с.state === 'done' && <span className="v2-dim"> · выполнена</span>}
      </span>
      <span className="v2-row2">
        <input type="date" name={`план.сцена.${с.key}.start`} aria-label={`сцена ${с.key}: начало`}
          value={окна[с.key]?.start ?? ''}
          max={окна[с.key]?.end || undefined}
          onChange={(e) => setОкна({ ...окна, [с.key]: { start: e.target.value, end: окна[с.key]?.end ?? '' } })} />
        <input type="date" name={`план.сцена.${с.key}.end`} aria-label={`сцена ${с.key}: конец`}
          value={окна[с.key]?.end ?? ''}
          min={окна[с.key]?.start || undefined}
          max={точка ? (даты[точка.key] || undefined) : undefined}
          onChange={(e) => setОкна({ ...окна, [с.key]: { start: окна[с.key]?.start ?? '', end: e.target.value } })} />
      </span>
    </span>
  )

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        План работ фазы
        <span className="v2-cnt">
          {задан ? 'задан' : 'не задан'}
          {` · даты точек ${точки.length - безДаты.length} из ${точки.length}`}
          {` · окна сцен ${phase.scenes.length - безОкна.length} из ${phase.scenes.length}`}
        </span>
        <span className="v2-head__spacer" />
        <button type="button" className="v2-chip" aria-pressed={открыт} onClick={() => setОткрыт(!открыт)}
          title="мероприятие 0.P: даты точек и окна сцен">
          {открыт ? 'свернуть' : задан ? 'править план' : 'задать план'}
        </button>
      </h3>

      {безДаты.length > 0 || безОкна.length > 0 ? (
        <div className="v2-empty__why">
          Внутренний обзор ждёт план: нужны даты у всех точек и окно у каждой сцены —
          без них ленте нечего показывать, а сроки рисков не с чем сравнивать.
          {безДаты.length > 0 && ` Нет даты: ${безДаты.map((т) => т.title).join(', ')}.`}
          {безОкна.length > 0 && ` Нет окна у сцен: ${безОкна.map((с) => с.key).join(', ')}.`}
        </div>
      ) : (
        <div className="v2-empty__why">
          План задан целиком: у каждой точки дата, у каждой сцены окно. Лента читает его как есть.
        </div>
      )}

      {замечания.length > 0 && (
        <div className="v2-locked">
          План не сходится:
          <ul>{замечания.slice(0, 6).map((з) => <li key={з}>{з}</li>)}</ul>
          Записать можно, но лента и сроки рисков прочитают это буквально.
        </div>
      )}
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-empty__why">{итог}</div>}

      {открыт && (
        <div className="v2-form">
          <span className="v2-field__cap">Точки фазы — по одной дате, по порядку</span>
          {точки.map((т, i) => (
            <label key={т.key}>
              {т.title}
              {i > 0 && даты[точки[i - 1].key] && <span className="v2-dim"> · после {даты[точки[i - 1].key]}</span>}
              <input type="date" name={`план.точка.${т.key}`} value={даты[т.key] ?? ''}
                min={i > 0 ? (даты[точки[i - 1].key] || undefined) : undefined}
                onChange={(e) => setДаты({ ...даты, [т.key]: e.target.value })} />
            </label>
          ))}

          <div className="v2-form__actions">
            <button type="button" className="v2-chip" disabled={безДаты.length > 0}
              title={безДаты.length > 0
                ? 'сначала даты точек: от них и раскладываются окна'
                : 'разложить сцены между предыдущей точкой и своей — предложение, которое можно править'}
              onClick={разложить}>
              Разложить окна по точкам
            </button>
          </div>

          {точки.map((т) => {
            const свои = поТочкам.карта.get(т.key) ?? []
            if (свои.length === 0) return null
            return (
              <div key={т.key} className="v2-note">
                <span className="v2-note__rule">{т.title}</span>
                <span className="v2-dim">
                  {даты[т.key] ? `дата ${даты[т.key]}` : 'даты нет'} · закрывает сцены {свои.map((с) => с.key).join(', ')}
                </span>
                {свои.map((с) => строкаСцены(с, т))}
              </div>
            )
          })}

          {поТочкам.прочие.length > 0 && (
            <div className="v2-note">
              <span className="v2-note__rule">вне точек</span>
              <span className="v2-dim">эти сцены не ждёт ни одна точка фазы</span>
              {поТочкам.прочие.map((с) => строкаСцены(с))}
            </div>
          )}

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
