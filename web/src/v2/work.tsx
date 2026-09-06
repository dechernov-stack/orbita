// Раздел «Работа» — поверхность работы есть СХЕМА ПРОЦЕССА
// (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ).
//
// Сверху — схема фазы дорожками; выбрал сцену — её мероприятия схемой;
// выбрал мероприятие — его рабочая поверхность снизу. Единица работы —
// мероприятие метода, а не форма: у него цель, входы и выходы-счётчики.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type Phase } from './api'
import { SceneFrame } from './SceneFrame'
import { PhaseBand } from './phaseband'
import { ActivityCard, PhaseMap, SceneMap } from './processmap'
import {
  SceneConstraints, SceneGoals, SceneIntent, SceneOpenProject,
  SceneServices, SceneStakeholders,
} from './scenes'
import { Concept } from './concept'
import { Requirements } from './requirements'
import { Costs, Risks, Technologies } from './programmatics'

export function Work({ project, onProject, wantScene, onScenePicked, onScene }: {
  project: string | null
  onProject: (p: string) => void
  /** Сцена, на которую просили открыть работу (переход из заданий). */
  wantScene?: string | null
  onScenePicked?: () => void
  /** Открытая сцена — шапке: контекст обязан совпадать с экраном. */
  onScene?: (key: string | null) => void
}) {
  const [фаза, setФаза] = useState<Phase | null>(null)
  const [сцена, setСцена] = useState<string | null>(null)
  const [мероприятие, setМероприятие] = useState<string | null>(null)
  const [видСхемы, setВидСхемы] = useState<'phase' | 'scene'>('phase')
  const [отказ, setОтказ] = useState<string | null>(null)

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
  useEffect(() => { setСцена(null); setМероприятие(null); setВидСхемы('phase') }, [project])

  useEffect(перечитать, [перечитать])

  useEffect(() => {
    if (wantScene) {
      setСцена(wantScene)
      setВидСхемы('scene')
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
      <div className="v2-panel">
        <h3>Сцена 1 · Открыть проект</h3>
        <div className="v2-frag">
          <p className="v2-question">Что за проект и по какому стандарту его вести</p>
          <SceneOpenProject onOpened={onProject} />
        </div>
      </div>
    )
  }

  if (отказ) return <div className="v2-panel"><div className="v2-locked">{отказ}</div></div>
  if (!фаза || !текущая) return <div className="v2-panel"><div className="v2-empty">Читаю фазу…</div></div>

  const делоТекущее = текущая.activities.find((д) => д.code === мероприятие)
    ?? текущая.activities.find((д) => д.state === 'in_progress')
    ?? текущая.activities.find((д) => д.state === 'available')
    ?? текущая.activities[0]

  return (
    <>
      <div className="v2-panel">
        <h3>
          {видСхемы === 'phase' ? 'Схема фазы' : `Схема сцены ${текущая.key} · ${текущая.title}`}
          <span className="v2-cnt">
            {видСхемы === 'phase'
              ? `${фаза.scenes.length} сцен · ${фаза.gates.length} точки · дорожек ${фаза.lanes.length}`
              : `${текущая.activities.length} мероприятий · роль: ${текущая.role}`}
            {'  '}
            <button type="button" className="v2-link"
              title={видСхемы === 'phase' ? 'к схеме мероприятий сцены' : 'к схеме фазы дорожками'}
              onClick={() => setВидСхемы(видСхемы === 'phase' ? 'scene' : 'phase')}>
              {видСхемы === 'phase' ? 'сцена →' : '← фаза'}
            </button>
          </span>
        </h3>
        {видСхемы === 'phase'
          ? <PhaseMap phase={фаза} onScene={(к) => { setСцена(к); setМероприятие(null); setВидСхемы('scene') }} />
          : <SceneMap scene={текущая} current={делоТекущее?.code ?? null} onPick={setМероприятие} />}
      </div>

      <div className="v2-two-cols">
        <PhaseBand phase={фаза} current={текущая.key}
          onPick={(к) => { setСцена(к); setМероприятие(null); setВидСхемы('scene') }} />
        <SceneFrame phase={фаза} scene={текущая} activity={делоТекущее}
          onPick={(к) => { setСцена(к); setМероприятие(null) }}>
          {делоТекущее && <ActivityCard activity={делоТекущее} />}
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
        </SceneFrame>
      </div>
    </>
  )
}
