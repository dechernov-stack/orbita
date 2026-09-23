// Раздел «Работа» (шип 2, экраны 4 и 7) — поверхность работы есть СХЕМА
// ПРОЦЕССА (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ), показанная ПО РОЛИ (ДИЗАЙН-ПРИНЦИПЫ-V2 §2).
//
// Инженер (плотность «мероприятие») видит одно мероприятие: поверхность во
// всю ширину, строку выходов и рейку 128 px словами — экран `activity.tsx`.
// Ведущий СИ (плотность «сцена») — слева ленту сцен фазы с ромбами точек,
// справа СЦЕНУ В ТЕЛЕ: заголовок, одну строку контекста, поток мероприятий
// скошенными карточками и поверхность выбранного; степпера и правой рейки
// нет. Руководитель и DA (плотность «фаза») — карту фазы стартовым экраном,
// план работ и ленту; поверхности за кликом.
//
// Ничего из построенного не выброшено: лента, схема и условия живут там же,
// меняются поверхности и умолчания видимости.
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import './work.css'
import { SceneModes } from './modes'
import { api, type Activity, type Phase, type Scene } from './api'
import { ActivityScreen, РОЛЬ } from './activity'
import { PhaseBand } from './phaseband'
import { PhasePlan } from './plan'
import { PhaseMap } from './processmap'
import { Маркер, type Состояние } from './markers'
import { режимПоРоли, составЭкрана, type Блок, type Зачем, type Режим } from './density'
import {
  SceneConstraints, SceneGoals, SceneIntent, SceneOpenProject,
  SceneServices, SceneStakeholders,
} from './scenes'
import { Concept } from './concept'
import { Requirements } from './requirements'
import { Costs, Risks, Technologies } from './programmatics'
import { PhaseASurface } from './phasea'
import { ОтветственныеСцен } from './responsibles'

export function Work({ project, onProject, wantScene, wantReason, onScenePicked, роль, режим, onРежим }: {
  project: string | null
  onProject: (p: string) => void
  /** Сцена, на которую просили открыть работу (переход из заданий). */
  wantScene?: string | null
  /**
   * Зачем нас сюда послали — словами того, кто послал: «§2 Анализ
   * альтернатив — «Варианты построения и их метрики»: 0 из 1».
   *
   * Без этой строки переход «к месту» высаживает человека на экран сцены,
   * где восемь карточек и две тысячи пикселей состава, и он спрашивает
   * «куда и что писать» (владелец, 21.09).
   */
  wantReason?: string | null
  onScenePicked?: () => void
  /** Моя роль в проекте: она задаёт плотность по умолчанию. */
  роль: string | null
  /** Плотность, выбранная руками; пусто — умолчание роли. */
  режим?: Режим | null
  onРежим?: (р: Режим) => void
}) {
  const [фаза, setФаза] = useState<Phase | null>(null)
  const [сцена, setСцена] = useState<string | null>(null)
  const [мероприятие, setМероприятие] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Причина перехода держится, пока человек её не закроет: она и есть задание. */
  const [зачем, setЗачем] = useState<string | null>(null)

  /**
   * Подвести к карточке, о которой речь: её ИМЯ есть в причине перехода
   * («Варианты построения и их метрики» → карточка «Варианты построения»).
   * Соответствие не выдумано в коде: сверяются два имени из данных, и, если
   * ни одно не совпало, экран просто остаётся наверху.
   */
  useEffect(() => {
    if (!зачем) return
    const подвести = () => {
      const карточки = [...document.querySelectorAll<HTMLElement>('.v2-card__title, .v2-panel > h3')]
      const своя = карточки.find((э) => {
        const имя = (э.childNodes[0]?.textContent ?? '').trim()
        return имя.length > 3 && зачем.toLowerCase().includes(имя.toLowerCase())
      })
      своя?.scrollIntoView({ block: 'center' })
    }
    // Трижды: реестры сцены догружаются и сдвигают карточку вниз — один
    // прокрут на 700 мс промахивался мимо (проверено на стенде 21.09).
    const таймеры = [600, 1500, 2600].map((мс) => window.setTimeout(подвести, мс))
    return () => { таймеры.forEach((т) => window.clearTimeout(т)) }
  }, [зачем])

  const текущийРежим: Режим = режим ?? режимПоРоли(роль)

  const перечитать = useCallback(() => {
    if (!project) return
    api.phase(project)
      .then((ф) => {
        setФаза(ф)
        setСцена((текущая) => текущая ?? ф.current_scene ?? ф.scenes[0]?.key ?? null)
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])

  // Смена проекта сбрасывает выбранную сцену: иначе схема показывает сцену
  // прежнего проекта — в новом она может быть ещё закрыта.
  useEffect(() => { setСцена(null); setМероприятие(null) }, [project])

  useEffect(перечитать, [перечитать])

  useEffect(() => {
    if (wantScene) {
      setСцена(wantScene)
      setМероприятие(null)
      if (wantReason) setЗачем(wantReason)
      onScenePicked?.()
    }
  }, [wantScene, wantReason, onScenePicked])

  const текущая = useMemo(
    () => фаза?.scenes.find((s) => s.key === сцена) ?? фаза?.scenes[0] ?? null,
    [фаза, сцена],
  )

  if (!project) {
    return (
      <div className="v2-panel" data-why="работа">
        <h3>Сцена 1 · Открыть проект</h3>
        <div className="v2-frag">
          <p className="v2-question">Что за проект и по какому стандарту его вести</p>
          <SceneOpenProject onOpened={onProject} />
        </div>
      </div>
    )
  }

  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  if (!фаза || !текущая) return <div className="v2-panel" data-why="работа"><div className="v2-empty">Читаю фазу…</div></div>

  const состав = составЭкрана(текущийРежим)
  const почему = (ключ: string) => состав.find((б: Блок) => б.key === ключ)
  const картаФазы = состав.some((б) => б.key === 'карта-фазы')
  const схемаСцены = состав.some((б) => б.key === 'схема-сцены')

  // Карта фазы — стартовый экран руководителя: клик по сцене уводит на её
  // работу, и плотность становится «сцена» (одна на выбранную работу).
  if (картаФазы) {
    return (
      <div className="v2-panel" data-why="следующий-клик">
        <h3>
          Карта фазы
          <span className="v2-cnt">
            {фаза.scenes.length} сцен · {фаза.gates.length} точки · дорожек {фаза.lanes.length}
          </span>
        </h3>
        <PhaseMap phase={фаза} onScene={(к) => {
          setСцена(к); setМероприятие(null); onРежим?.('сцена')
        }} />
        <PhasePlan phase={фаза} project={project} onChanged={перечитать} />
        <PhaseBand phase={фаза} current={текущая.key}
          onPick={(к) => { setСцена(к); setМероприятие(null); onРежим?.('сцена') }} />
      </div>
    )
  }

  const делоТекущее = текущая.activities.find((д) => д.code === мероприятие)
    ?? текущая.activities.find((д) => д.state === 'in_progress')
    ?? текущая.activities.find((д) => д.state === 'available')
    ?? текущая.activities[0]

  const поверхность = (
    <>
      {текущая.state === 'locked' && (
        <div className="v2-locked">
          Сцена закрыта: {текущая.entry.filter((у) => !у.passed).map((у) => у.why ?? у.title).join('; ')}.
        </div>
      )}
      {(текущая.depends?.length ?? 0) > 0 && (
        <div className="v2-note-line" title="связи сцен из шаблона фазы: FS — после конца, SS — после начала, FF — конец вместе с концом, INPUT — вход из выхода">
          {текущая.instance_of ? `экземпляр сцены ${текущая.instance_of} на узел ${текущая.node} · ` : ''}
          связи: {текущая.depends!.map((с) => `${с.on} (${с.type}) — ${с.why}`).join('; ')}
        </div>
      )}
      {текущая.key === '1' && (
        <>
          <div className="v2-empty">
            Проект открыт: {фаза.project}, стандарт {фаза.standard}.
            <span className="v2-empty__why">Даты точек задаются планом работ фазы — мероприятие 0.P.</span>
          </div>
          {/* Ответственные сцен назначаются на сцене 1, A1 и в паспорте (истина 23.09). */}
          <ОтветственныеСцен project={project} onChanged={перечитать} />
        </>
      )}
      {текущая.key === '2' && <SceneIntent project={project} onChanged={перечитать} />}
      {текущая.key === '3' && <SceneStakeholders project={project} onChanged={перечитать} />}
      {текущая.key === '4' && <SceneGoals project={project} onChanged={перечитать} />}
      {текущая.key === '5' && <SceneConstraints project={project} onChanged={перечитать} />}
      {текущая.key === '6' && <SceneServices project={project} onChanged={перечитать} />}
      {текущая.key === '7' && <Concept project={project} />}
      {текущая.key === '8' && <Requirements project={project} />}
      {/*
        Сцена 9 без поверхности стояла глухо: условие говорило «назовите режимы
        аппарата в сцене 9», а называть их было негде (проход владельца 20.09).
      */}
      {текущая.key === '9' && <SceneModes project={project} onChanged={перечитать} />}
      {текущая.key === '10' && <Technologies project={project} />}
      {текущая.key === '11' && <Risks project={project} />}
      {текущая.key === '12' && <Costs project={project} />}
      {/*
        Phase A — поверхности через контекст (шип 3): сцена A-ряда открывает
        существующий реестр с отбором по своему узлу, уровню, документу или
        точке; ветки сцен живут в phasea.tsx, сторож читает их оттуда.
      */}
      <PhaseASurface project={project} phase={фаза} scene={текущая} onChanged={перечитать}
        onScene={(к) => { setСцена(к); setМероприятие(null) }} />
    </>
  )

  return (
    <div className={схемаСцены ? 'v2-work2 v2-work2--scene' : 'v2-work2'}>
      {/* Лента сцен фазы слева (320 px) — только у ведущего СИ: инженер видит своё мероприятие. */}
      {схемаСцены && (
        <PhaseBand phase={фаза} current={текущая.key}
          onPick={(к) => { setСцена(к); setМероприятие(null) }}
          onPlan={onРежим ? () => onРежим('фаза') : undefined} />
      )}
      <div className="v2-work2__body">
        {/*
          Зачем мы здесь — первой строкой. Переход «к месту» из документа
          высаживает на сцену, где карточек много, а искомая — не первая;
          строка говорит словами документа, что тут закрывать.
        */}
        {зачем && (
          <div className="v2-prop" data-why="почему-нельзя"
            title="вы пришли сюда из документа: здесь закрывается его раздел">
            <span>
              Сюда вас послал документ
              <span className="v2-empty__why">{зачем}</span>
            </span>
            <button type="button" className="v2-link" title="убрать напоминание"
              onClick={() => setЗачем(null)}>понятно</button>
          </div>
        )}
        {/*
          Сцена в теле как рабочая поверхность (экран 4): заголовок, ОДНА строка
          контекста — роль сцены и её входы из условий, — затем поток
          мероприятий. Шапка сцену не несёт, здесь она названа один раз.
        */}
        {схемаСцены && (
          <>
            <h1 className="v2-scene__h">{текущая.key} · {текущая.title}</h1>
            <div className="v2-scene__ctx" data-why={почему('контекст')?.зачем} title={почему('контекст')?.почему}>
              <span>
                {РОЛЬ[текущая.role] ?? текущая.role}
                {роль === текущая.role ? ' — вы ведёте' : ''}
              </span>
              {текущая.entry.length > 0 && (
                <span>
                  вход:{' '}
                  {текущая.entry.map((у, i) => (
                    <span key={у.check} className={у.passed ? 'v2-scene__in' : 'v2-scene__in v2-scene__in--no'}
                      title={у.passed ? `${у.title} — выполнено` : (у.why ?? `${у.title} — не выполнено`)}>
                      {i > 0 && ', '}{у.title} {у.passed ? '✓' : '☐'}
                    </span>
                  ))}
                </span>
              )}
            </div>
            <ПотокМероприятий scene={текущая} current={делоТекущее?.code ?? null} onPick={setМероприятие}
              зачем={почему('схема-сцены')?.зачем} подсказка={почему('схема-сцены')?.почему} />
          </>
        )}
        <ActivityScreen project={project} phase={фаза} scene={текущая} activity={делоТекущее}
          режим={текущийРежим} роль={роль}
          onPickActivity={setМероприятие}
          onPhaseMap={() => onРежим?.('фаза')}
          onChanged={перечитать}>
          {поверхность}
        </ActivityScreen>
      </div>
    </div>
  )
}

/**
 * Поток мероприятий сцены — короткая горизонтальная цепочка скошенных
 * карточек; между ними стрелка, подписанная артефактом (выход первого — вход
 * второго). Клик по карточке выбирает мероприятие: его поверхность — ниже.
 */
function ПотокМероприятий({ scene, current, onPick, зачем, подсказка }: {
  scene: Scene
  current: string | null
  onPick: (code: string) => void
  зачем?: Зачем
  подсказка?: string
}) {
  if (scene.activities.length === 0) {
    return (
      <div className="v2-empty">
        Мероприятий у сцены нет.
        <span className="v2-empty__why">
          Поток сцены порождается из полки процессов ЖЦ: сцена без мероприятий — та, которую метод ещё не описал.
        </span>
      </div>
    )
  }
  return (
    <div className="v2-flow2" data-why={зачем} title={подсказка}>
      {scene.activities.map((дело, i) => {
        const текущее = дело.code === current
        const прежнее = scene.activities[i - 1]
        return (
          <Fragment key={дело.code}>
            {прежнее && (
              <span className="v2-flow2__arrow" title={`выход «${прежнее.code}» — вход «${дело.code}»`}>
                <i>{подписьСтрелки(прежнее, дело)}</i>
                <b aria-hidden="true">→</b>
              </span>
            )}
            <button type="button" className="v2-flow2__card" aria-pressed={текущее}
              title={дело.goal ? `цель: ${дело.goal}` : `открыть мероприятие ${дело.code}`}
              onClick={() => onPick(дело.code)}>
              <span className="v2-flow2__in">
                <Маркер род="мероприятие" состояние={маркерМероприятия(дело, текущее)} подпись={`${дело.code} · ${дело.name}`} />
                <span className="v2-flow2__t">{дело.code} · {дело.name}</span>
                <span className={дело.state === 'done' ? 'v2-flow2__st v2-flow2__st--done'
                  : текущее ? 'v2-flow2__st v2-flow2__st--cur' : 'v2-flow2__st'}>
                  {словаМероприятия(дело)}
                </span>
              </span>
            </button>
          </Fragment>
        )
      })}
    </div>
  )
}

/** Маркер мероприятия: текущее — кобальт, выполненное — зелёное, закрытое (ждёт входа) — серое. */
function маркерМероприятия(дело: Activity, текущее: boolean): Состояние {
  if (текущее) return 'текущее'
  if (дело.state === 'done') return 'выполнено'
  if (дело.state === 'blocked') return 'закрыто'
  if (дело.state === 'in_progress') return 'текущее'
  return 'пусто'
}

/** Состояние мероприятия словами — рядом с маркером, как везде. */
function словаМероприятия(дело: Activity): string {
  if (дело.state === 'done') return 'выполнено'
  if (дело.state === 'blocked') return дело.blocked_by[0] ? `закрыто · ${дело.blocked_by[0]}` : 'закрыто · ждёт входа'
  if (дело.state === 'in_progress') {
    const свои = дело.outputs.filter((в) => !в.produced_in)
    const есть = свои.filter((в) => в.satisfied).length
    return `в работе · выходов ${есть} из ${свои.length}`
  }
  return дело.state === 'available' ? 'доступно' : 'не начато'
}

/**
 * Подпись стрелки — артефакт: выход первого, который второе называет входом.
 * Входы полки ссылаются на мероприятие ИМЕНЕМ, а не его выходом; тогда
 * подписью идут выходы первого — это и есть то, что течёт дальше.
 */
function подписьСтрелки(от: Activity, к: Activity): string {
  const выходы = от.outputs.map((в) => в.what)
  const общие = выходы.filter((в) => к.inputs.some((вх) => вх.toLowerCase() === в.toLowerCase()))
  return (общие.length > 0 ? общие : выходы).slice(0, 2).join(' · ')
}
