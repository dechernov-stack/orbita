// Раздел «Работа» — вход в продукт (ТЗ §4.1.1): лента сцен и точек фазы.
//
// Инженер не выбирает, куда идти: лента показывает, где он сейчас, что
// закрыто и чем именно. Открыть можно любую прожитую сцену — вернуться и
// поправить законно.
import { useCallback, useEffect, useState } from 'react'
import { api, type Phase } from './api'
import { SceneFrame } from './SceneFrame'
import {
  SceneConstraints, SceneGoals, SceneIntent, SceneOpenProject,
  SceneServices, SceneStakeholders, Гейты,
} from './scenes'

export function Work({ project, onProject, wantScene, onScenePicked }: {
  project: string | null
  onProject: (p: string) => void
  /** Сцена, на которую просили открыть работу (переход из заданий). */
  wantScene?: string | null
  onScenePicked?: () => void
}) {
  const [фаза, setФаза] = useState<Phase | null>(null)
  const [сцена, setСцена] = useState<string | null>(null)
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

  useEffect(перечитать, [перечитать])

  useEffect(() => {
    if (wantScene) {
      setСцена(wantScene)
      onScenePicked?.()
    }
  }, [wantScene, onScenePicked])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Сцена 1 · Открыть проект</span>
        </div>
        <p className="v2-question">Что за проект и по какому стандарту его вести</p>
        <SceneOpenProject onOpened={onProject} />
      </div>
    )
  }

  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!фаза) return <div className="v2-card"><div className="v2-empty">Читаю фазу…</div></div>

  const текущая = фаза.scenes.find((s) => s.key === сцена) ?? фаза.scenes[0]

  return (
    <>
      <SceneFrame phase={фаза} scene={текущая} onPick={setСцена}>
        {текущая.key === '1' && (
          <div className="v2-empty">
            Проект открыт: {фаза.project}, стандарт {фаза.standard}.
            <span className="v2-empty__why">Точки фазы заведены с датами по умолчанию — правятся на «Точках».</span>
          </div>
        )}
        {текущая.key === '2' && <SceneIntent project={project} onChanged={перечитать} />}
        {текущая.key === '3' && <SceneStakeholders project={project} onChanged={перечитать} />}
        {текущая.key === '4' && <SceneGoals project={project} onChanged={перечитать} />}
        {текущая.key === '5' && <SceneConstraints project={project} onChanged={перечитать} />}
        {текущая.key === '6' && <SceneServices project={project} onChanged={перечитать} />}
      </SceneFrame>
      <Гейты phase={фаза} onPassed={перечитать} />
    </>
  )
}
