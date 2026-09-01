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
import { useSession } from '../ui/session'
import { PhaseFlow } from './PhaseFlow'
import { PhaseGanttChart } from './PhaseGantt'

const STATUS_LABEL: Record<string, string> = {
  in_progress: 'В работе',
  available: 'Доступны',
  waiting: 'Ожидают',
  done: 'Выполнены',
}

const STATUS_ORDER = ['in_progress', 'available', 'waiting', 'done']

export function PhaseWork({ onGo, onLead, here }: {
  onGo: (screen: string, kind?: string, doc?: string) => void
  /**
   * Круг 3: открыть задачу в рамке ведения — режим работы, а не переход.
   * Круг 6: с конкретного шага, если ведут с подзадачи полотна.
   */
  onLead?: (taskId: string, step?: number) => void
  /** Круг 4: «вы здесь» — задача, открытая в рамке ведения. */
  here?: string
}) {
  const [view, setView] = useState<PhaseWorkView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<'lane' | 'list' | 'flow'>('lane')
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
  if (task) return <TaskCard task={task} onBack={() => setOpen(null)} onGo={onGo} onLead={onLead} />

  return (
    <>
      <div className="toolbar">
        {/* Круг 2: заголовок один — второй давала рейка раздела */}
        <span className="secondary">
          {view.tasks} задач · {view.in_progress} в работе · {view.available} доступна ·{' '}
          {view.waiting} ожидают · {view.done} выполнена
        </span>
        <div className="grow" style={{ flex: 1 }} />
        <button className={`tab${tab === 'lane' ? ' tab--primary' : ''}`} onClick={() => setTab('lane')}
          title="Гант: полосы по плану руководителя, стрелки зависимостей, вехи ромбами">
          Гант
        </button>
        <button className={`tab${tab === 'list' ? ' tab--primary' : ''}`} onClick={() => setTab('list')}
          title="список: та же работа группами статуса">
          Список
        </button>
        <button className={`tab${tab === 'flow' ? ' tab--primary' : ''}`} onClick={() => setTab('flow')}
          title="схема: как течёт работа — артефакты между задачами и точками">
          Схема
        </button>
      </div>
      {tab === 'lane' ? <PhaseGanttChart onOpen={setOpen} onLead={onLead} onGo={onGo} />
        : tab === 'list' ? <List view={view} onOpen={setOpen} />
          : <PhaseFlow here={here} onOpenTask={onLead} onGo={onGo} />}
    </>
  )
}

/** Пустое место экрана: подсказка вместо голого прочерка. */
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
function TaskCard({ task, onBack, onGo, onLead }: {
  onLead?: (taskId: string, step?: number) => void
  task: PhaseWorkTask
  onBack: () => void
  onGo: (screen: string, kind?: string, doc?: string) => void
}) {
  return (
    <>
      <div className="toolbar" style={{ gap: 8 }}>
        <button className="tab" onClick={onBack} title="вернуться к работе фазы">← Работа фазы</button>
        <h2>{task.order} · {task.name}</h2>
        <span style={{ flex: 1 }} />
        {onLead && (
          <button className="tab tab--primary" onClick={() => onLead(task.id)}
            title="вести задачу: степпер сверху, рабочий экран шага снизу — контекст задачи не теряется">
            Вести задачу →
          </button>
        )}
      </div>

      <PlanCard task={task} />

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
              ? <button className="rr-assign" onClick={() => onGo(s.screen!, s.kind, s.document_code)}
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
 * Круг 5: план задачи в карточке. Ставится он перетаскиванием полосы на
 * Ганте — здесь он ВИДЕН и здесь же снимается: иначе снять поставленное
 * было бы нечем. План — намерение руководителя, статуса он не касается.
 */
function PlanCard({ task }: { task: PhaseWorkTask }) {
  const { author } = useSession()
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [plan, setPlan] = useState(task.plan)

  return (
    <div className="card">
      <h3>План работ</h3>
      {plan
        ? <div>
            {plan.start} — {plan.end}
            <span className="secondary"> · поставил {plan.author}</span>
          </div>
        : <p className="secondary" style={{ margin: 0 }}>
            План не задан: на Ганте полоса задачи — расчётная сетка интервала до
            точки по порядку зависимостей, а не обещание сроков. Задать план —
            потянуть полосу на вкладке «Гант».
          </p>}
      {notice && <div className="secondary" style={{ marginTop: 6 }}>{notice}</div>}
      {plan && (
        <button className="rr-assign" style={{ marginTop: 8 }} disabled={busy || !author}
          title={author
            ? 'снять план: полоса вернётся в расчётную сетку'
            : 'представьтесь в шапке: правка плана подписывается автором'}
          onClick={() => {
            setBusy(true)
            api.phaseWorkPlan({ task: task.id, clear: true, author: author || '' })
              .then(() => { setPlan(undefined); setNotice('план снят') })
              .catch((e) => setNotice(String(e)))
              .finally(() => setBusy(false))
          }}>
          снять план
        </button>
      )}
    </div>
  )
}

/**
 * «Следующий шаг» в шапке — верхушка РАБОТЫ, а не отдельная выдумка: первая
 * незавершённая задача и её первый несделанный шаг, с переходом к
 * преднастроенному месту.
 */
export function NextStepBadge({ tick, onGo }: {
  tick: string
  onGo: (screen: string, kind?: string, doc?: string) => void
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
