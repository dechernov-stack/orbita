// «Работа фазы» — главный экран проекта после мастера (эталон
// reference-phase-work.html, два состояния). Владелец: «мастер доводит до
// постановки и обрывается; дальше месяцы работы команды, и у них нет лица».
//
// Три проекции одного состояния: ЛЕНТА (по умолчанию) — окна задач от дат
// вех; СПИСОК — группы статуса; КАРТОЧКА — зачем, вход, полоса шагов с
// переходами, разрывы разрезом и выход. Ничего из этого не хранится:
// статусы, окна и сделанность шагов считает сервер.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PhaseWorkView, PhaseWorkTask } from '../api/types'
import { Tooltip } from '../ui/Tooltip'

const STATUS_LABEL: Record<string, string> = {
  in_progress: 'В работе',
  available: 'Доступны',
  waiting: 'Ожидают',
  done: 'Выполнены',
}

const STATUS_ORDER = ['in_progress', 'available', 'waiting', 'done']

export function PhaseWork({ onGo }: { onGo: (screen: string, kind?: string) => void }) {
  const [view, setView] = useState<PhaseWorkView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<'lane' | 'list'>('lane')
  const [open, setOpen] = useState<string | null>(null)

  useEffect(() => {
    api.phaseWork().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>
  if (view.tasks === 0) {
    return (
      <div className="empty">
        {view.empty_why ?? 'Задач фазы на полке нет.'} Реестр задач — данные
        библиотеки, а не код экрана: наполнение правится пакетом полки.
      </div>
    )
  }

  const task = open ? view.items.find((t) => t.id === open) ?? null : null
  if (task) return <TaskCard task={task} onBack={() => setOpen(null)} onGo={onGo} />

  return (
    <>
      <div className="toolbar">
        <h2>Работа фазы</h2>
        <span className="secondary">
          {view.tasks} задач · {view.in_progress} в работе · {view.available} доступна ·{' '}
          {view.waiting} ожидают · {view.done} выполнена
        </span>
        <div className="grow" style={{ flex: 1 }} />
        <button className={`tab${tab === 'lane' ? ' tab--primary' : ''}`} onClick={() => setTab('lane')}
          title="лента: окна задач вычислены от дат вех и зависимостей">
          Лента
        </button>
        <button className={`tab${tab === 'list' ? ' tab--primary' : ''}`} onClick={() => setTab('list')}
          title="список: та же работа группами статуса">
          Список
        </button>
      </div>
      {tab === 'lane' ? <Lane view={view} onOpen={setOpen} /> : <List view={view} onOpen={setOpen} />}
    </>
  )
}

/**
 * Лента (лёгкий Гант). Окна — ТОЛЬКО производные: старт от дедлайна
 * предшественника, конец — дата точки выхода. Ручных длительностей нет, и
 * ввести их некуда по построению.
 */
function Lane({ view, onOpen }: { view: PhaseWorkView; onOpen: (id: string) => void }) {
  if (!view.lane_from || !view.lane_to) {
    return (
      <div className="card">
        <p className="secondary" style={{ margin: 0 }}>
          Дат вех в паспорте нет — окна считать не из чего. Задайте даты точек на
          экране жизненного цикла, и лента появится сама.
        </p>
      </div>
    )
  }
  return (
    <div className="card">
      <div className="secondary" style={{ marginBottom: 8 }}>
        окно {view.lane_from} — {view.lane_to}; сплошная полоса — в работе либо доступна, пунктир — расчётное
        окно от дедлайна входа, красное — до точки осталось меньше недели
      </div>
      <table className="grid">
        <tbody>
          {view.items.map((t) => (
            <tr key={t.id}>
              <td style={{ width: 300 }}>
                <button className="np-linkish" onClick={() => onOpen(t.id)}
                  title={t.why}>
                  {t.order} · {t.name}
                </button>
              </td>
              <td style={{ width: 150 }}>
                <StatusChip task={t} />
              </td>
              <td>
                <LaneBar task={t} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Полоса окна: положение и длина — из дат сервера, здесь только показ. */
function LaneBar({ task }: { task: PhaseWorkTask }) {
  if (!task.end || task.lane_width_pct == null) {
    return <Muted why="у выхода задачи не назначена точка — окно считать не от чего" />
  }
  // доли полосы посчитал сервер: клиент только рисует
  const style: React.CSSProperties = {
    marginLeft: `${task.lane_offset_pct ?? 0}%`,
    width: `${task.lane_width_pct}%`,
    height: 12,
    borderRadius: 6,
    background: task.tight ? 'var(--status-cancelled, #b3261e)' : 'var(--accent)',
    opacity: task.status === 'done' ? 0.35 : 1,
    border: task.status === 'waiting' ? '1px dashed var(--hairline)' : undefined,
  }
  const подпись = task.tight
    ? `окно сжато: до ${task.end} меньше недели, а выход не готов`
    : `${task.start ?? 'сейчас'} — ${task.end}`
  return (
    <Tooltip text={подпись}>
      <span style={{ display: 'block', width: '100%' }}>
        <span style={style} />
      </span>
    </Tooltip>
  )
}

function StatusChip({ task }: { task: PhaseWorkTask }) {
  if (task.status === 'waiting') {
    return (
      <Tooltip text={`ожидает: ${task.waits_on ?? task.input_why}`}>
        <span className="chip">ждёт{task.waits_on ? ` · ${task.waits_on.split(' · ')[0]}` : ''}</span>
      </Tooltip>
    )
  }
  if (task.status === 'done') return <span className="secondary">✓ выполнена</span>
  if (task.status === 'in_progress') {
    const done = task.steps.filter((s) => s.done).length
    return <span className="chip">в работе · шаг {done + 1} из {task.steps.length}</span>
  }
  return <span className="chip">доступна</span>
}

function Muted({ why }: { why: string }) {
  return (
    <Tooltip text={why}>
      <span className="secondary">—</span>
    </Tooltip>
  )
}

/** Список — вторая проекция того же состояния, группами статуса. */
function List({ view, onOpen }: { view: PhaseWorkView; onOpen: (id: string) => void }) {
  return (
    <>
      {STATUS_ORDER.map((st) => {
        const rows = view.items.filter((t) => t.status === st)
        if (rows.length === 0) return null
        return (
          <div className="card" key={st}>
            <h3>{STATUS_LABEL[st]} · {rows.length}</h3>
            <table className="grid">
              <tbody>
                {rows.map((t) => (
                  <tr key={t.id}>
                    <td style={{ width: 340 }}>
                      <button className="np-linkish" onClick={() => onOpen(t.id)} title={t.why}>
                        {t.order} · {t.name}
                      </button>
                    </td>
                    <td>
                      {t.status === 'waiting'
                        ? <span className="secondary">ожидает: {t.waits_on ?? t.input_why}</span>
                        : t.status === 'done'
                          ? <span className="secondary">выход готов</span>
                          : <span className="secondary">
                              {t.steps.find((s) => !s.done)?.title ?? 'шаги пройдены'}
                            </span>}
                    </td>
                    <td style={{ width: 140 }}>
                      {t.gaps.length > 0
                        ? <Tooltip text={t.gaps.join('; ')}>
                            <span className="warn">разрывы · {t.gaps.length}</span>
                          </Tooltip>
                        : <Muted why="открытых разрывов готовности по местам этой задачи нет" />}
                    </td>
                    <td style={{ width: 230 }} className="secondary">
                      выход: {t.artifact}{t.gate ? ` к ${t.gate}` : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      })}
    </>
  )
}

/** Карточка задачи: зачем · вход · полоса шагов · разрывы · выход. */
function TaskCard({ task, onBack, onGo }: {
  task: PhaseWorkTask
  onBack: () => void
  onGo: (screen: string, kind?: string) => void
}) {
  return (
    <>
      <div className="toolbar">
        <button className="tab" onClick={onBack} title="вернуться к работе фазы">← Работа фазы</button>
        <h2>{task.order} · {task.name}</h2>
      </div>

      <div className="card">
        <p style={{ marginTop: 0 }}>{task.why}</p>
        <div className="secondary">
          {task.input_ready
            ? <b>вход готов</b>
            : <>вход не готов: {task.input_why}</>}
        </div>
      </div>

      <div className="card">
        <h3>Шаги</h3>
        {task.steps.map((s, i) => (
          <div key={s.title} style={{ display: 'flex', gap: 10, alignItems: 'baseline', padding: '6px 0' }}>
            <span className="mono secondary" style={{ minWidth: 22 }}>{s.done ? '✓' : i + 1}</span>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: s.done ? 400 : 600, opacity: s.done ? 0.6 : 1 }}>{s.title}</div>
              {s.hint && <div className="secondary">{s.hint}</div>}
              {!s.done && <div className="secondary">готово, когда: {s.why}</div>}
            </div>
            {s.screen
              ? <button className="rr-assign" onClick={() => onGo(s.screen!, s.kind)}
                  title={s.kind
                    ? `открыть место действия с преднастроенной операцией «${s.kind}»`
                    : 'открыть место действия'}>
                  {s.done ? 'к месту' : 'перейти'} →
                </button>
              : <Muted why="у шага нет отдельного места: он закрывается работой в других разделах" />}
          </div>
        ))}
      </div>

      <div className="card">
        <h3>Разрывы задачи · {task.gaps.length}</h3>
        {task.gaps.length === 0
          ? <p className="secondary" style={{ margin: 0 }}>
              По местам этой задачи открытых разрывов готовности нет. Разрывы здесь —
              те же, что в готовности к точке: второго списка не существует.
            </p>
          : <ul style={{ margin: 0, paddingLeft: 18 }}>
              {task.gaps.map((g) => <li key={g}>{g}</li>)}
            </ul>}
      </div>

      <div className="card">
        <h3>Выход</h3>
        <div>{task.artifact}{task.gate ? ` · к точке ${task.gate}` : ''}</div>
        <div className="secondary">
          {task.output_done
            ? 'выход готов — зрелость вычислена готовностью к точке'
            : 'зрелость вычисляется готовностью к точке; ручной отметки нет'}
        </div>
      </div>
    </>
  )
}

/**
 * «Следующий шаг» в шапке — верхушка РАБОТЫ, а не отдельная выдумка: первая
 * незавершённая задача и её первый несделанный шаг, с переходом к
 * преднастроенному месту.
 */
export function NextStepBadge({ tick, onGo }: {
  tick: string
  onGo: (screen: string, kind?: string) => void
}) {
  const [next, setNext] = useState<PhaseWorkView['next'] | null>(null)
  useEffect(() => {
    api.phaseWork().then((v) => setNext(v.next ?? null)).catch(() => setNext(null))
  }, [tick])
  if (!next?.step) return null
  return (
    <button className="header__gate" onClick={() => onGo(next.screen ?? 'phasework', next.kind)}
      title={`задача «${next.name}» — открыть место действия${next.kind ? ' с преднастроенной операцией' : ''}`}>
      <span className="secondary">следующий шаг</span>
      <b>{next.step}</b>
    </button>
  )
}
