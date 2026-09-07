// Лента сцен фазы по эталону: сцены подряд, ТОЧКИ МЕЖДУ НИМИ.
//
// Точка стоит там, где она в жизни: между сценами. Так видно, какие сцены
// закрывает ближайшая точка и что она держит — без отдельного экрана.
import type { Phase, Scene } from './api'

export function PhaseBand({ phase, current, onPick }: {
  phase: Phase
  current: string
  onPick: (key: string) => void
}) {
  const безПлана = phase.scenes.filter((с) => !с.window).length
  const прожито = phase.scenes.filter((с) => с.state === 'done').length
  const вРаботе = phase.scenes.filter((с) => с.state === 'open').length
  const закрыто = phase.scenes.length - прожито - вРаботе

  /** Точка встаёт после последней сцены, которую она ждёт. */
  const точкаПосле = (ключ: string) =>
    phase.gates.filter((т) =>
      т.blocking.concat(т.passed ? [] : []).length >= 0 &&
      последняяСценаТочки(phase, т.key) === ключ)

  return (
    <div className="v2-panel" data-why="следующий-клик">
      <h3>
        Сцены фазы
        <span className="v2-cnt">
          {прожито} выполнены · {вРаботе} в работе · {закрыто} закрыты
        </span>
      </h3>
      {безПлана > 0 && (
        <div className="v2-note-line" title="план работ фазы задаётся мероприятием 0.P">
          план не задан у {безПлана} сцен — к внутреннему обзору это разрыв
        </div>
      )}
      <div className="v2-list">
        {phase.scenes.map((с) => (
          <div key={с.key}>
            <button type="button"
              className={с.key === current ? 'v2-row v2-row--cur' : 'v2-row'}
              title={с.state === 'locked'
                ? `закрыта: ${с.blockers.join('; ')}`
                : с.state === 'done' ? 'выполнена — можно вернуться и поправить' : 'в работе'}
              onClick={() => onPick(с.key)}>
              <span className="v2-row__n">{с.key}</span>
              <span className="v2-row__t">{с.title}</span>
              <span className={`v2-st v2-st--${с.state}`}>
                {с.state === 'done' ? 'выполнена'
                  : с.state === 'open' ? `в работе${прогресс(с)}`
                    : `закрыта${ждёт(с)}`}
                {с.window && (
                  <span className="v2-win" title="окно плана работ фазы">
                    {датаКратко(с.window.start)}–{датаКратко(с.window.end)}
                  </span>
                )}
              </span>
            </button>
            {точкаПосле(с.key).map((т) => (
              <div key={т.key} className="v2-gate">
                ◆ {т.title}
                {т.planned_date && <> · {датаКратко(т.planned_date)}</>}
                {' · '}
                {т.passed
                  ? <span className="v2-ok">пройдена</span>
                  : т.blocking.length > 0
                    ? <span className="v2-bad" title={т.blocking.join('; ')}>
                      {т.blocking.length} блокирующих
                    </span>
                    : <span className="v2-ok">условия выполнены</span>}
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}

/** Счёт идёт по МЕРОПРИЯТИЯМ: единица работы — блок метода, а не шаг формы. */
function прогресс(с: Scene): string {
  const дела = с.activities
  if (дела.length === 0) return ''
  const готово = дела.filter((д) => д.state === 'done').length
  return ` · ${готово} из ${дела.length}`
}

function ждёт(с: { blockers: string[] }): string {
  const первый = с.blockers[0] ?? ''
  const сцена = первый.match(/сцена (\d+)/)
  return сцена ? ` · ждёт ${сцена[1]}` : ''
}

function датаКратко(дата: string): string {
  const [, м, д] = дата.split('-')
  return д && м ? `${д}.${м}` : дата
}

/** Последняя сцена, которую ждёт точка: после неё её и рисуют. */
function последняяСценаТочки(phase: Phase, ключ: string): string | null {
  const точка = phase.gates.find((т) => т.key === ключ)
  if (!точка) return null
  const ждёт = phase.scenes.filter((с) => с.awaited_by.some((к) => к.includes(точка.title)))
  return ждёт.length > 0 ? ждёт[ждёт.length - 1].key : null
}
