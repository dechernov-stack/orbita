// Лента сцен фазы (шип 2, экран 4): сцены подряд кружками-маркерами, ТОЧКИ
// РОМБАМИ МЕЖДУ НИМИ — там, где они в жизни. Так видно, какие сцены закрывает
// ближайшая точка и что она держит, без отдельного экрана.
//
// Состояние всегда и словом: «выполнена», «в работе · 2 из 3», «закрыта ·
// ждёт 3». Клик по сцене открывает её в теле экрана. Панели с заголовком и
// счётчиками нет: список и есть экран, а счёт ему не нужен (тест действия).
import { Fragment } from 'react'
import type { Gate, Phase, Scene } from './api'
import { Маркер, type Состояние } from './markers'

export function PhaseBand({ phase, current, onPick, onPlan }: {
  phase: Phase
  /** Сцена, открытая в теле экрана: она и отмечена. */
  current: string
  onPick: (key: string) => void
  /** План работ фазы живёт на карте фазы: туда ведёт разрыв «план не задан». */
  onPlan?: () => void
}) {
  const безПлана = phase.scenes.filter((с) => !с.window).length
  const ближайшая = phase.gates.find((т) => !т.passed) ?? null
  /** Точка встаёт после последней сцены, которую она ждёт. */
  const точкиПосле = (ключ: string) =>
    phase.gates.filter((т) => последняяСценаТочки(phase, т.key) === ключ)
  /** Точка, которую не ждёт ни одна сцена, стоит в конце ленты: молчать о ней нельзя. */
  const вКонце = phase.gates.filter((т) => последняяСценаТочки(phase, т.key) === null)

  const маркерТочки = (т: Gate): Состояние =>
    т.passed ? 'выполнено'
      : т.blocking.length > 0 ? 'блок'
        : т.key === ближайшая?.key ? 'текущее' : 'пусто'

  const точка = (т: Gate) => (
    <div key={т.key} className="v2-band__gate"
      title={т.passed
        ? 'точка пройдена'
        : т.blocking.length > 0 ? `держат: ${т.blocking.join('; ')}` : 'условия точки выполнены'}>
      <Маркер род="точка" состояние={маркерТочки(т)} подпись={т.title} />
      <span>
        <b>{т.title}</b>
        {т.planned_date && ` · ${датаКратко(т.planned_date)}`}
        {' · '}
        {т.passed
          ? <span className="v2-ok">пройдена</span>
          : т.blocking.length > 0
            ? <span className="v2-bad">блокирует {т.blocking.length}</span>
            : <span className="v2-ok">условия выполнены</span>}
      </span>
    </div>
  )

  return (
    <nav className="v2-band" aria-label="сцены и точки фазы" data-why="следующий-клик">
      {phase.scenes.map((с) => (
        <Fragment key={с.key}>
          <button type="button" className="v2-band__scene"
            aria-current={с.key === current ? 'true' : undefined}
            title={с.state === 'locked'
              ? `закрыта: ${с.blockers.join('; ') || 'ждёт предыдущую сцену'}`
              : с.state === 'done' ? 'выполнена — открыть, чтобы вернуться и поправить' : 'в работе — открыть сцену'}
            onClick={() => onPick(с.key)}>
            <Маркер род="сцена" состояние={маркерСцены(с)} подпись={`${с.key} · ${с.title}`} />
            <span className="v2-band__t">{с.key} · {с.title}</span>
            <span className={`v2-band__st v2-band__st--${с.state}`}>{состояниеСловами(с)}</span>
          </button>
          {точкиПосле(с.key).map(точка)}
        </Fragment>
      ))}
      {вКонце.map(точка)}
      {безПлана > 0 && (
        <div className="v2-band__plan" title="план работ фазы задаётся мероприятием 0.P на карте фазы">
          план не задан у {безПлана} сцен — к {ближайшая ? `точке «${ближайшая.title}»` : 'ближайшей точке'} это разрыв
          {onPlan && (
            <button type="button" className="v2-link" title="карта фазы: план работ — окна сцен и даты точек"
              onClick={onPlan}>к плану</button>
          )}
        </div>
      )}
    </nav>
  )
}

/** Заливка кружка — состояние сцены: выполнена · в работе (кобальт) · закрыта (графит). */
function маркерСцены(с: Scene): Состояние {
  if (с.state === 'done') return 'выполнено'
  if (с.state === 'locked') return 'закрыто'
  return 'текущее'
}

function состояниеСловами(с: Scene): string {
  if (с.state === 'done') return 'выполнена'
  if (с.state === 'open') return `в работе${прогресс(с)}`
  return `закрыта${ждёт(с)}`
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

/** Последняя сцена, которую ждёт точка: после неё её и рисуют; никакая — null. */
function последняяСценаТочки(phase: Phase, ключ: string): string | null {
  const точка = phase.gates.find((т) => т.key === ключ)
  if (!точка) return null
  const ждут = phase.scenes.filter((с) => с.awaited_by.some((к) => к.includes(точка.title)))
  return ждут.length > 0 ? ждут[ждут.length - 1].key : null
}
