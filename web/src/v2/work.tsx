// Раздел «Работа» — вход в продукт (ТЗ §4.1.1, эталон оболочки).
//
// Две колонки: слева лента сцен фазы с точками между ними, справа — рамка
// текущей сцены. Инженер не выбирает, куда идти: лента показывает, где он
// сейчас и чем держится ближайшая точка.
import { useCallback, useEffect, useState } from 'react'
import { api, type Phase } from './api'
import { SceneFrame } from './SceneFrame'
import { PhaseBand } from './phaseband'
import {
  SceneConstraints, SceneGoals, SceneIntent, SceneOpenProject,
  SceneServices, SceneStakeholders,
} from './scenes'
import { Concept } from './concept'
import { Requirements } from './requirements'

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

  // Смена проекта сбрасывает выбранную сцену: иначе рамка показывает сцену
  // прежнего проекта — в новом она может быть ещё закрыта.
  useEffect(() => { setСцена(null) }, [project])

  useEffect(перечитать, [перечитать])

  useEffect(() => {
    if (wantScene) {
      setСцена(wantScene)
      onScenePicked?.()
    }
  }, [wantScene, onScenePicked])

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
  if (!фаза) return <div className="v2-panel"><div className="v2-empty">Читаю фазу…</div></div>

  const текущая = фаза.scenes.find((s) => s.key === сцена) ?? фаза.scenes[0]

  return (
    <div className="v2-two-cols">
      <PhaseBand phase={фаза} current={текущая.key} onPick={setСцена} />
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
        {/* Сцена 7 — состав и базовый вариант; сцена 8 — реестр требований.
            Экраны те же, что в разделах: сцена показывает их внутри рамки. */}
        {текущая.key === '7' && <Concept project={project} />}
        {текущая.key === '8' && <Requirements project={project} />}
      </SceneFrame>
    </div>
  )
}
