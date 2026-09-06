// Рамка сцены по эталону (`эталоны/reference-shell-v2.html`).
//
// Четыре части, и каждая отвечает на свой вопрос:
//   · степпер — где я в сцене и почему «Дальше» пока серая;
//   · нить потока — откуда пришло, что даёт, кто ждёт;
//   · фрагмент — только своё действие, без чужих первичных кнопок;
//   · панель условий — из чего сцена состоит: ✓ и ☐ со ссылкой к месту.
import type { ReactNode } from 'react'
import type { Condition, Phase, Scene } from './api'

export function SceneFrame({ phase, scene, onPick, children }: {
  phase: Phase
  scene: Scene
  onPick: (key: string) => void
  children: ReactNode
}) {
  const шагов = scene.steps.length
  const текущий = scene.steps.findIndex((ш) => !ш.done)
  const номерШага = текущий === -1 ? шагов : текущий + 1
  const имяШага = scene.steps[текущий === -1 ? шагов - 1 : текущий]?.title ?? ''
  const дальшеМожно = scene.state === 'done'
  const причина = scene.blockers[0]
  const следующая = phase.scenes.find((с) => с.order === scene.order + 1)
  const ближайшая = phase.gates.find((т) => !т.passed)

  return (
    <div className="v2-frame">
      <div className="v2-stepper">
        <button type="button" className="v2-link" title="к ленте сцен фазы"
          onClick={() => onPick(phase.current_scene ?? scene.key)}>← обзор</button>
        <b>{scene.key} · {scene.title}</b>
        <span className="v2-dots" aria-hidden="true">
          {scene.steps.map((ш, i) => (
            <span key={i} className={ш.done ? 'v2-dot v2-dot--f' : 'v2-dot'} />
          ))}
        </span>
        <span className="v2-dim">
          {шагов > 0 ? `шаг ${номерШага} из ${шагов}${имяШага ? ` · «${имяШага}»` : ''}` : scene.question}
        </span>
        <button type="button"
          className={дальшеМожно ? 'v2-next v2-next--on' : 'v2-next'}
          disabled={!дальшеМожно || !следующая}
          title={дальшеМожно
            ? (следующая ? `перейти к сцене ${следующая.key} · ${следующая.title}` : 'это последняя сцена фазы')
            : `условие не выполнено: ${причина ?? 'сцена ещё в работе'}`}
          onClick={() => следующая && onPick(следующая.key)}>
          Дальше →
          {!дальшеМожно && причина && <span className="v2-next__why">{причина}</span>}
        </button>
      </div>

      <div className="v2-flow">
        <span className="v2-dim">вход: </span>
        {scene.entry.length === 0 && scene.input_flows.length === 0 ? '—' : (
          <>
            {scene.entry.map((у) => (
              <span key={у.check} className={у.passed ? 'v2-ok' : 'v2-warn'}>
                {у.title}{у.passed ? ' ✓' : ' ☐'}{' '}
              </span>
            ))}
            {scene.input_flows.length > 0 && (
              <span className="v2-dim" title="входные потоки процесса ЖЦ — данными полки">
                · {scene.input_flows.join(' · ')}
              </span>
            )}
          </>
        )}
        {' → '}<b>{scene.key} · {scene.title}</b>
        {' → '}<span className="v2-dim">выход: </span>{scene.output || '—'}
        {scene.awaited_by.length > 0 && (
          <>{' → '}<span className="v2-dim">ждут: </span>{scene.awaited_by.join(' · ')}</>
        )}
      </div>

      <div className="v2-body">
        <div className="v2-frag">{children}</div>
        <aside className="v2-cond">
          <h4>Условия выхода</h4>
          <ul>
            {scene.exit.map((у) => <Условие key={у.check} у={у} />)}
          </ul>
          {ближайшая && (
            <>
              <h4>К ближайшей точке</h4>
              <ul>
                {ближайшая.blocking.length === 0
                  ? <li className="v2-c v2-c--ok">{ближайшая.title}: условия выполнены</li>
                  : ближайшая.blocking.map((б, i) => (
                    <li key={i} className="v2-c v2-c--no">{б}</li>
                  ))}
              </ul>
            </>
          )}
        </aside>
      </div>
    </div>
  )
}

function Условие({ у }: { у: Condition }) {
  return (
    <li className={у.passed ? 'v2-c v2-c--ok' : 'v2-c v2-c--no'}
      title={у.passed ? 'условие выполнено' : (у.why ?? 'условие не выполнено')}>
      {у.title}
      {!у.passed && у.why && <span className="v2-empty__why">{у.why}</span>}
    </li>
  )
}
