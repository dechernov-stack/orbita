// Круг 3: рамка ведения — задача как рабочее место.
//
// Диагноз владельца: шаг вёл переходом в другой раздел, и контекст задачи
// погибал — «где я и что дальше» приходилось вспоминать. Прыжки по
// интерфейсам это навигация, а не ведение.
//
// Рамка — РЕЖИМ, а не экран: сверху степпер, ниже тот же самый экран
// системы, преднастроенный параметрами шага. Собственных форм у рамки нет
// по построению — она ничего не рисует, кроме этой строки; всё рабочее
// приходит встроенным экраном. Сторож `tools/validate_task_frame.py`
// держит это правило.
import type { PhaseWorkView } from '../api/types'

type Task = PhaseWorkView['items'][number]

export function TaskFrameBar({
  task, step, onStep, onExit, onAdvance, onManualDone, busy, author, onOpenTask, onGo,
}: {
  task: Task
  /** Текущий шаг (индекс): рамка ведёт по нему, а не по порядку экранов. */
  step: number
  onStep: (index: number) => void
  onExit: () => void
  onAdvance: () => void
  /** Ручной шаг (инспекция людей) — отмечается автором, а не вычислением. */
  onManualDone?: () => void
  busy?: boolean
  author?: string
  /** Круг 4: нить потока — переход к соседям задачи по цепочке. */
  onOpenTask?: (taskId: string) => void
  onGo?: (screen: string, kind?: string, doc?: string) => void
}) {
  const current = task.steps[step]
  const done = current?.done ?? false
  const last = step >= task.steps.length - 1
  // ручной шаг: его условие — инспекция, вычислением не закрывается
  const manual = current?.screen === 'inspection'

  return (
    <div className="card" style={{ marginBottom: 8, padding: '8px 12px' }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <button className="np-linkish" onClick={onExit}
          title="выйти в обзор задач — рамка не клетка, прямые маршруты разделов живы">
          ← обзор
        </button>
        <b>{task.order} · {task.name}</b>

        <span style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          {task.steps.map((s, i) => (
            <button key={s.title} className="np-linkish"
              style={{ padding: '0 2px', fontSize: 15, lineHeight: 1 }}
              onClick={() => onStep(i)}
              title={`${i + 1}. ${s.title}${s.done ? ' — сделано' : ''}${s.tally ? ` · ${s.tally}` : ''}`}>
              {s.done ? '●' : i === step ? '◉' : '○'}
            </button>
          ))}
        </span>

        <span className="secondary">
          шаг {step + 1} из {task.steps.length} · «{current?.title ?? ''}»
          {current?.tally && <span className="chip" style={{ marginLeft: 6 }}>{current.tally}</span>}
        </span>

        <span style={{ flex: 1 }} />

        {manual && !done && onManualDone && (
          <button className="rr-assign" onClick={onManualDone} disabled={busy || !author}
            title={author
              ? 'отметить шаг выполненным: инспекцию проводят люди, и отметка несёт ваше имя'
              : 'представьтесь в шапке: отметка подписывается автором'}>
            шаг выполнен
          </button>
        )}
        <button className="np-btn np-pri" onClick={onAdvance}
          disabled={busy || (!done && !manual)}
          title={done
            ? (last ? 'задача пройдена — вернуться в обзор' : 'следующий шаг задачи')
            : manual
              ? 'отметьте выполнение — шаг закрывается человеком'
              : `шаг закроется сам: ${current?.why ?? 'условие ещё не выполнено'}`}>
          {last && done ? 'Завершить →' : 'Дальше →'}
        </button>
      </div>
      {current?.hint && (
        <div className="secondary" style={{ marginTop: 4 }}>{current.hint}</div>
      )}
      {task.flow && <FlowThread task={task} onOpenTask={onOpenTask} onGo={onGo} />}
    </div>
  )
}

/**
 * Круг 4, нить потока: «вход → задача → выход → кого кормит». Без неё рамка
 * ведения — туннель: видно, что делать сейчас, и не видно, откуда пришёл и
 * кто ждёт результата. Цепочка та же, что рисует схему: второй её копии нет.
 */
function FlowThread({ task, onOpenTask, onGo }: {
  task: Task
  onOpenTask?: (taskId: string) => void
  onGo?: (screen: string, kind?: string, doc?: string) => void
}) {
  const flow = task.flow!
  return (
    <div className="fr-thread">
      <span className="secondary">вход:</span>
      {flow.in.length === 0 && <span className="secondary">условий нет</span>}
      {flow.in.map((i) => (i.kind === 'task' && i.id
        ? <button key={i.id} className="np-linkish" onClick={() => onOpenTask?.(i.id!)}
            title={`${i.artifact ?? ''} — ${i.ready ? 'выход готов' : 'выход ещё не готов'}; вести задачу-предшественника`}>
            {i.order} · {i.name} {i.ready ? '✓' : '…'}
          </button>
        : <span key={i.name} className="secondary"
            title={i.ready ? 'условие входа выполнено' : 'условие входа ещё не выполнено'}>
            {i.name} {i.ready ? '✓' : '…'}
          </span>))}

      <span className="fr-thread__arrow">→</span>
      <b>{task.order} · {task.name}</b>
      <span className="fr-thread__arrow">→</span>

      <span className="secondary">выход:</span>
      {flow.out.document_code && onGo
        ? <button className="np-linkish" onClick={() => onGo('docs', undefined, flow.out.document_code)}
            title={`открыть документ «${flow.out.artifact}»; состояние: ${flow.out.state}${
              flow.out.maturity ? `, требуется ${flow.out.maturity}` : ''}`}>
            {flow.out.artifact} ({flow.out.state})
          </button>
        : <span title={`состояние выхода: ${flow.out.state}`}>
            {flow.out.artifact} ({flow.out.state})
          </span>}

      {flow.consumers.length > 0 && <span className="fr-thread__arrow">→</span>}
      {flow.consumers.length > 0 && <span className="secondary">ждут:</span>}
      {flow.consumers.map((c) => (c.kind === 'gate'
        ? <button key={c.name} className="np-linkish"
            onClick={() => onGo?.('readiness', undefined, c.gate)}
            title="открыть готовность к точке: выход задачи зреет к ней">
            ◆ {c.name}
          </button>
        : <button key={c.id} className="np-linkish" onClick={() => c.id && onOpenTask?.(c.id)}
            title="вести задачу, которая ждёт этот выход">
            {c.order} · {c.name}
          </button>))}
    </div>
  )
}
