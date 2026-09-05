// Рамка сцены (ТЗ §4.1.1): степпер, вопрос сцены, шаги и панель условий
// выхода. Внутрь рамки встраивается фрагмент экрана — только тот, что нужен
// шагу, без чужих первичных действий.
//
// Панель условий — главное место рамки: она объясняет, ЧЕМ сцена держится,
// и делает это словами сервера, а не общим «нельзя».
import type { ReactNode } from 'react'
import type { Phase, Scene } from './api'

export function SceneFrame({ phase, scene, onPick, children }: {
  phase: Phase
  scene: Scene
  onPick: (key: string) => void
  children: ReactNode
}) {
  return (
    <>
      <div className="v2-stepper" role="tablist" aria-label="сцены фазы">
        {phase.scenes.map((s) => (
          <button key={s.key} type="button" role="tab"
            className={`v2-step v2-step--${s.state}`}
            aria-selected={s.key === scene.key}
            aria-current={s.key === phase.current_scene ? 'step' : undefined}
            disabled={false}
            title={
              s.state === 'locked'
                ? `сцена закрыта: ${s.blockers.join('; ')}`
                : s.state === 'done'
                  ? 'сцена прожита — можно вернуться и поправить'
                  : 'текущая сцена'
            }
            onClick={() => onPick(s.key)}>
            <span className="v2-step__n">{s.key}</span>
            <span className="v2-step__t">{s.title}</span>
            <span className="v2-step__s">
              {s.state === 'done' ? 'прожита' : s.state === 'open' ? 'идёт' : 'закрыта'}
            </span>
          </button>
        ))}
      </div>

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">{scene.title}</span>
          <span className="v2-card__count">{scene.role === 'lead' ? 'руководитель' : scene.role === 'lead_se' ? 'ведущий СИ' : scene.role}</span>
        </div>
        <p className="v2-question">{scene.question}</p>

        {scene.state === 'locked' ? (
          <div className="v2-locked">
            <b>Сцена закрыта.</b>
            <ul className="v2-why">
              {scene.blockers.map((b) => <li key={b}>{b}</li>)}
            </ul>
            <span className="v2-empty__why">
              Условие держит движок процесса: пока оно не выполнено, шаги этой сцены не начинаются.
            </span>
          </div>
        ) : (
          children
        )}
      </div>

      {scene.state !== 'locked' && (
        <div className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">Условия выхода</span>
            <span className="v2-card__count">
              {scene.blockers.length === 0 ? 'выполнены' : `осталось ${scene.blockers.length}`}
            </span>
          </div>
          {scene.blockers.length === 0 ? (
            <div className="v2-ok">Сцена прожита: выход закрыт, следующая открылась сама.</div>
          ) : (
            <ul className="v2-why">
              {scene.blockers.map((b) => <li key={b}>{b}</li>)}
            </ul>
          )}
        </div>
      )}

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Шаги сцены</span>
          <span className="v2-card__count">{scene.steps.length}</span>
        </div>
        <ol className="v2-steps">
          {scene.steps.map((шаг) => (
            <li key={шаг.title} className={шаг.done ? 'v2-steps__done' : undefined}>
              <b>{шаг.title}</b>
              <span className="v2-empty__why">{шаг.hint}</span>
            </li>
          ))}
        </ol>
      </div>
    </>
  )
}
