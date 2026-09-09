// Раздел «Работа» — поверхность работы есть СХЕМА ПРОЦЕССА
// (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ), но показывается она ПО РОЛИ
// (ДИЗАЙН-ПРИНЦИПЫ-V2 §2).
//
// Инженер видит одно мероприятие: рабочую поверхность во всю ширину,
// строку выходов и рейку со счётчиками. Ведущий СИ — плюс компактную
// схему мероприятий сцены сверху. Руководитель и DA — карту фазы
// стартовым экраном, поверхности за кликом.
//
// Ничего из построенного не выброшено: схемы, лента и условия живут там
// же, меняются умолчания видимости.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type Phase } from './api'
import { ActivityScreen } from './activity'
import { PhaseBand } from './phaseband'
import { PhaseMap, SceneMap } from './processmap'
import { режимПоРоли, составЭкрана, type Режим } from './density'
import {
  SceneConstraints, SceneGoals, SceneIntent, SceneOpenProject,
  SceneServices, SceneStakeholders,
} from './scenes'
import { Concept } from './concept'
import { Requirements } from './requirements'
import { Costs, Risks, Technologies } from './programmatics'

export function Work({ project, onProject, wantScene, onScenePicked, onScene, роль, режим, onРежим }: {
  project: string | null
  onProject: (p: string) => void
  /** Сцена, на которую просили открыть работу (переход из заданий). */
  wantScene?: string | null
  onScenePicked?: () => void
  /** Открытая сцена — шапке: контекст обязан совпадать с экраном. */
  onScene?: (key: string | null) => void
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
      onScenePicked?.()
    }
  }, [wantScene, onScenePicked])

  const текущая = useMemo(
    () => фаза?.scenes.find((s) => s.key === сцена) ?? фаза?.scenes[0] ?? null,
    [фаза, сцена],
  )

  // Шапка показывает ту сцену, что открыта на экране, а не «текущую вообще».
  useEffect(() => { onScene?.(текущая?.key ?? null) }, [текущая?.key, onScene])

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
        <div className="v2-empty">
          Проект открыт: {фаза.project}, стандарт {фаза.standard}.
          <span className="v2-empty__why">Даты точек задаются планом работ фазы — мероприятие 0.P.</span>
        </div>
      )}
      {текущая.key === '2' && <SceneIntent project={project} onChanged={перечитать} />}
      {текущая.key === '3' && <SceneStakeholders project={project} onChanged={перечитать} />}
      {текущая.key === '4' && <SceneGoals project={project} onChanged={перечитать} />}
      {текущая.key === '5' && <SceneConstraints project={project} onChanged={перечитать} />}
      {текущая.key === '6' && <SceneServices project={project} onChanged={перечитать} />}
      {текущая.key === '7' && <Concept project={project} />}
      {текущая.key === '8' && <Requirements project={project} />}
      {текущая.key === '10' && <Technologies project={project} />}
      {текущая.key === '11' && <Risks project={project} />}
      {текущая.key === '12' && <Costs project={project} />}
    </>
  )

  return (
    <>
      {схемаСцены && (
        <div className="v2-scenemap" data-why="следующий-клик"
          title="мероприятия сцены: клик открывает поверхность">
          <SceneMap scene={текущая} current={делоТекущее?.code ?? null} onPick={setМероприятие} />
        </div>
      )}
      <ActivityScreen project={project} phase={фаза} scene={текущая} activity={делоТекущее}
        режим={текущийРежим} роль={роль}
        onPickActivity={setМероприятие}
        onPhaseMap={() => onРежим?.('фаза')}
        onChanged={перечитать}>
        {поверхность}
      </ActivityScreen>
    </>
  )
}
