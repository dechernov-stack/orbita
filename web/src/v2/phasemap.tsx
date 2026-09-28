// Карта фазы строками (27.09, владелец: «работать с такой картинкой
// невозможно»; эталон reference-phase-map-gantt.html). Одна карта на обе
// фазы; прежняя «станционная» (станции на линии, недели на оси) ушла: на 28
// сценах без плана она разваливалась — подписи налезали, сцены уходили
// списком вправо, мероприятия ложились на дорожку «Моделирование».
//
// Строение. Слева колонка 420 px — сцена кружком состояния, имя целиком,
// «держит SRR» красным словом, ответственный справа; полосы дорожек.
// Справа — время МЕСЯЦАМИ: окно сцены — полоса, прогресс — заливкой,
// просрочка — янтарём, окно по умолчанию — штриховкой. Точки — ромб с
// коротким именем и датой на оси и числом невыполненных условий; через все
// строки — тонкая вертикаль. «Сегодня» — кобальт. Экземпляры аванпроекта —
// одна строка «N из M» с раскрытием. Мероприятий на карте нет — они в теле
// сцены. Нет плана — янтарная полоса «план не утверждён» с окнами по
// умолчанию (их считает сервер) и «утвердить план» теми же маршрутами плана.
import { Fragment, useCallback, useEffect, useMemo, useState, type ReactElement } from 'react'
import { РОЛЬ } from './activity'
import { api, type Phase, type Scene } from './api'
import {
  деньМесяц, короткоеИмя, месяцыОси, место, полосыКарты, рамкиКарты, рядыТочек, строкиКарты, type Строка,
} from './phasemap.data'
import './phasemap.css'
import { useАвтор } from './research'
import { умолчаниеПоРоли } from './responsibles'
import { Вкладки, useВкладка } from './ui/tabs'

type Вид = 'дорожки' | 'порядок' | 'держащие'
const ВИДЫ: readonly Вид[] = ['дорожки', 'порядок', 'держащие']

type ОкноПлана = { scene: string; start: string; end: string; responsible?: string }

const СЛОВО_СОСТОЯНИЯ: Record<Строка['state'], string> = {
  done: 'выполнена', cur: 'идёт', debt: 'есть долг: окно прошло', idle: 'не начата',
}

/** Сегодня по часам машины — дата, а не время: карта живёт днями. */
function сегодняшняя(): string {
  const д = new Date()
  const м = String(д.getMonth() + 1).padStart(2, '0')
  const день = String(д.getDate()).padStart(2, '0')
  return `${д.getFullYear()}-${м}-${день}`
}

export function КартаФазы({ project, phase, onScene, onPoint, onEditPlan, onChanged }: {
  project: string
  phase: Phase
  /** Клик по строке — сцена в теле. */
  onScene: (ключ: string) => void
  /** Клик по ромбу — карточка точки. */
  onPoint?: (ключ: string) => void
  /** «Править окна» — редактор плана фазы. */
  onEditPlan?: () => void
  onChanged: () => void
}) {
  const [вид, setВид] = useВкладка<Вид>('карта-фазы', 'дорожки', ВИДЫ)
  const [план, setПлан] = useState<ОкноПлана[]>([])
  const [роли, setРоли] = useState<Record<string, string>>({})
  const [учётки, setУчётки] = useState<{ login: string; display_name: string }[]>([])
  const [раскрыты, setРаскрыты] = useState<Set<string>>(new Set())
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [автор] = useАвтор()
  const сегодня = сегодняшняя()

  const перечитать = useCallback(() => {
    api.plan(project).then((п) => setПлан(п.scene_windows ?? [])).catch(() => setПлан([]))
    api.projectRoles(project).then(setРоли).catch(() => setРоли({}))
    fetch('/api/auth/users').then((r) => (r.ok ? r.json() : { users: [] }))
      .then((д) => setУчётки(д.users ?? [])).catch(() => setУчётки([]))
  }, [project])
  useEffect(перечитать, [перечитать, phase])

  const ответственный = useCallback((с: Scene) => {
    const имя = (логин: string) => учётки.find((у) => у.login === логин)?.display_name ?? логин
    const назначен = план.find((о) => о.scene === (с.instance_of ?? с.key))?.responsible
    if (назначен) return имя(назначен)
    const поРоли = умолчаниеПоРоли(роли, с.role)
    return поРоли ? имя(поРоли) : (РОЛЬ[с.role] ?? с.role)
  }, [план, роли, учётки])

  const строки = useMemo(() => строкиКарты(phase, ответственный, сегодня), [phase, ответственный, сегодня])
  const полосы = useMemo(() => полосыКарты(строки, (р) => РОЛЬ[р] ?? р), [строки])
  const рамки = рамкиКарты(строки, phase.gates, сегодня)
  const месяцы = месяцыОси(рамки)
  const точки = phase.gates.filter((т) => т.planned_date)
    .sort((а, б) => String(а.planned_date).localeCompare(String(б.planned_date)))
  const ряды = рядыТочек(точки.map((т) => место(String(т.planned_date).slice(0, 10), рамки)))
  const экземпляров = phase.scenes.filter((с) => с.instance_of).length
  // Сцены шаблона: экземпляры аванпроекта — одна сцена «A4», их число — отдельно.
  const сценШаблона = new Set(phase.scenes.map((с) => с.instance_of ?? с.key)).size
  const безОкна = phase.scenes.filter((с) => !с.window).length
  // «Только держащие точку» — сцены, без которых не пройти БЛИЖАЙШУЮ точку (правка 27.09).
  const держащие = строки.filter((с) => с.ближнюю)
  const можноУтвердить = phase.scenes.some((с) => с.default_window)

  const утвердить = () => {
    // Утверждённые окна остаются, недостающие — по умолчанию; ответственные — из плана.
    const окна = phase.scenes.filter((с) => !с.instance_of).flatMap((с) => {
      const было = план.find((о) => о.scene === с.key)
      if (было) return [было]
      const д = с.default_window
      return д ? [{ scene: с.key, start: д.start, end: д.end }] : []
    })
    setЗанято(true); setОтказ(null)
    api.setPlan(project, {
      phase: phase.phase, author: автор || 'инженер',
      gate_dates: точки.map((т) => ({ gate: т.key, date: String(т.planned_date).slice(0, 10) })),
      scene_windows: окна,
    })
      .then(() => { перечитать(); onChanged() })
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
      .finally(() => setЗанято(false))
  }

  const полоса = (с: Строка) => {
    if (!с.окно) return null
    const л = место(с.окно.start, рамки)
    const классы = ['v2-gantt__bar', с.state === 'done' ? 'v2-gantt__bar--done' : '', с.просрочено ? 'v2-gantt__bar--late' : '',
      с.окно.поУмолчанию ? 'v2-gantt__bar--draft' : ''].filter(Boolean).join(' ')
    return (
      <div className={классы} style={{ left: `${л}%`, width: `${место(с.окно.end, рамки) - л}%` }}
        title={`${с.title} · ${деньМесяц(с.окно.start)} — ${деньМесяц(с.окно.end)}${с.окно.поУмолчанию ? ' · окно по умолчанию' : ''}`}>
        {с.прогресс > 0 && с.state !== 'done' && <div className="v2-gantt__p" style={{ width: `${с.прогресс * 100}%` }} />}
      </div>
    )
  }

  const строка = (с: Строка, вложенная = false): ReactElement => {
    const раскрыта = раскрыты.has(с.key)
    const куда = с.экземпляры.length > 0 ? (с.экземпляры.find((э) => э.state !== 'done') ?? с.экземпляры[0]).key : с.key
    return (
      <Fragment key={с.key}>
        <div className={`v2-gantt__row${вложенная ? ' v2-gantt__row--sub' : ''}${phase.current_scene === с.key ? ' v2-gantt__row--cur' : ''}`}
          onClick={() => onScene(куда)}>
          <div className="v2-gantt__n">
            <i className={`v2-gantt__c v2-gantt__c--${с.state}`} title={СЛОВО_СОСТОЯНИЯ[с.state]} />
            {с.экземпляры.length > 0 && (
              <button type="button" className="v2-link v2-gantt__fold" aria-expanded={раскрыта}
                aria-label={раскрыта ? 'свернуть экземпляры' : 'раскрыть экземпляры по элементам'}
                title={раскрыта ? 'свернуть экземпляры' : 'раскрыть экземпляры по элементам'}
                onClick={(e) => {
                  e.stopPropagation()
                  setРаскрыты((было) => { const н = new Set(было); if (н.has(с.key)) н.delete(с.key); else н.add(с.key); return н })
                }}>
                {раскрыта ? '▾' : '▸'}
              </button>
            )}
            <button type="button" className="v2-link v2-gantt__title" title={с.title} onClick={(e) => { e.stopPropagation(); onScene(куда) }}>
              {с.title}
            </button>
            {с.счёт && <span className="v2-gantt__cnt">{с.счёт}</span>}
            {/* Красным — только то, что держит ближайшую точку; прочее — серым «к SDR/MDR». */}
            {с.ближнюю
              ? <span className="v2-gantt__blk">держит {с.держит[0]}</span>
              : с.держит.length > 0 && <span className="v2-gantt__to">к {с.держит[0]}</span>}
            {с.кто && <span className="v2-gantt__who">{с.кто}</span>}
          </div>
          <div className="v2-gantt__t">{полоса(с)}</div>
        </div>
        {раскрыта && с.экземпляры.map((э) => строка(э, true))}
      </Fragment>
    )
  }

  // Фаза завершена (§0.4): проект ушёл за последнюю точку в фазу без шаблона
  // (Phase B после KDP-B). Сцены прежней фазы показывать «текущими» нельзя —
  // говорим словами, без мнимых сцен и «переоткрытого» A1.
  const завершена = Boolean(phase.template_phase && phase.phase !== phase.template_phase)
  if (завершена) {
    const последняя = [...phase.gates].filter((g) => g.passed).sort((a, b) => a.order - b.order).at(-1)
    return (
      <div className="v2-gantt" aria-label={`фаза ${phase.template_phase} завершена`}>
        <div className="v2-gantt__head"><b>Карта фазы · {phase.template_phase}</b></div>
        <div className="v2-gantt__done" data-why="работа">
          <b>«{phase.template_phase}» завершена.</b>
          {последняя && (
            <span> Решение «{короткоеИмя(последняя)}»{последняя.planned_date ? ` от ${деньМесяц(последняя.planned_date)}` : ''}.</span>
          )}
          <span> Шаблон «{phase.phase}» не заведён — следующая фаза вне области ИС.</span>
        </div>
      </div>
    )
  }

  return (
    <div className="v2-gantt" aria-label={`карта фазы ${phase.phase}`}>
      <div className="v2-gantt__head">
        <b>Карта фазы · {phase.phase}</b>
        <span className="v2-gantt__muted">
          сцен {сценШаблона}{экземпляров > 0 ? ` (+${экземпляров} экземпляров)` : ''} · точек {точки.length}
          {' · '}дорожек {полосы.length} · <span className="v2-gantt__now-t">сегодня {деньМесяц(сегодня)}</span>
        </span>
      </div>
      <Вкладки<Вид> label="вид карты фазы" current={вид} onChange={setВид} items={[
        { key: 'дорожки', word: 'по дорожкам', hint: 'сцены полосами дорожек: проектирование, планирование и управление' },
        { key: 'порядок', word: 'по порядку', hint: 'сцены в порядке шаблона фазы' },
        { key: 'держащие', word: 'только держащие точку', count: держащие.length, hint: 'сцены, без которых не пройти ближайшую точку' },
      ]} />
      {безОкна > 0 && (
        <div className="v2-gantt__plan" data-why="следующий-клик">
          <b>План фазы не утверждён.</b>
          <span>Штриховка — окна по умолчанию от длительностей шаблона между точками{безОкна < phase.scenes.length ? ` (без окна ${безОкна})` : ''}.</span>
          <span className="v2-gantt__sp" />
          {onEditPlan && <button type="button" className="v2-link" title="редактор окон сцен и дат точек" onClick={onEditPlan}>править окна</button>}
          <button type="button" className="v2-primary" disabled={занято || !можноУтвердить}
            title={можноУтвердить ? 'записать окна по умолчанию в план фазы; утверждённые окна остаются' : 'у точек фазы нет дат: окна по умолчанию считать не от чего'}
            onClick={утвердить}>
            {занято ? 'Записываю…' : 'утвердить план'}
          </button>
        </div>
      )}
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-gantt__grid">
        <div className="v2-gantt__row v2-gantt__axis">
          <div className="v2-gantt__n">сцена · ответственный</div>
          <div className="v2-gantt__t">
            {месяцы.map((м) => <div key={м.дата} className="v2-gantt__month" style={{ left: `${место(м.дата, рамки)}%` }}>{м.подпись}</div>)}
            {точки.map((т, i) => {
              const дата = String(т.planned_date).slice(0, 10)
              const держат = т.passed ? 0 : т.blocking.length
              return (
                <button key={т.key} type="button"
                  className={`v2-gantt__pt${т.passed ? ' v2-gantt__pt--ok' : держат > 0 ? ' v2-gantt__pt--blk' : ''}${ряды[i] ? ' v2-gantt__pt--low' : ''}${место(дата, рамки) > 88 ? ' v2-gantt__pt--end' : ''}`}
                  style={{ left: `${место(дата, рамки)}%` }}
                  title={`${т.title} · ${деньМесяц(дата)} · ${т.passed ? 'пройдена' : `не выполнено условий: ${держат}`}`}
                  onClick={() => onPoint?.(т.key)}>
                  <i />{короткоеИмя(т)} {деньМесяц(дата)}{держат > 0 && <span className="v2-gantt__k">{держат}</span>}
                </button>
              )
            })}
          </div>
        </div>
        {вид === 'дорожки'
          ? полосы.map((п) => (
            <Fragment key={п.key}>
              <div className="v2-gantt__band"><div className="v2-gantt__n">{п.title}<span>{п.строки.length} · {п.роль}</span></div><div /></div>
              {п.строки.map((с) => строка(с))}
            </Fragment>
          ))
          : (вид === 'порядок' ? строки : держащие).map((с) => строка(с))}
        {вид === 'держащие' && держащие.length === 0 && <div className="v2-empty">Сцен, что держат ближайшую точку, нет.</div>}
        <div className="v2-gantt__over" aria-hidden="true">
          {точки.map((т) => (
            <div key={т.key} className={`v2-gantt__vline${!т.passed && т.blocking.length > 0 ? ' v2-gantt__vline--blk' : ''}`}
              style={{ left: `${место(String(т.planned_date).slice(0, 10), рамки)}%` }} />
          ))}
          <div className="v2-gantt__today" style={{ left: `${место(сегодня, рамки)}%` }} />
        </div>
      </div>
      <div className="v2-gantt__legend">
        <span><i className="v2-gantt__c v2-gantt__c--done" /> выполнена</span>
        <span><i className="v2-gantt__c v2-gantt__c--cur" /> идёт</span>
        <span><i className="v2-gantt__c v2-gantt__c--debt" /> есть долг</span>
        <span><i className="v2-gantt__c v2-gantt__c--idle" /> не начата</span>
        {/* Образцы — те же классы, что у полос: заливка прогресса, штриховка, янтарь. */}
        <span><span className="v2-gantt__swatch"><span className="v2-gantt__p" style={{ width: '45%' }} /></span> окно плана · заливка — прогресс</span>
        <span><span className="v2-gantt__swatch v2-gantt__bar--draft" /> окно по умолчанию</span>
        <span><span className="v2-gantt__swatch v2-gantt__bar--late"><span className="v2-gantt__p" style={{ width: '45%' }} /></span> просрочено</span>
        <span className="v2-gantt__legend-blk">◆ 7 — держит точку: столько условий не выполнено</span>
      </div>
    </div>
  )
}
