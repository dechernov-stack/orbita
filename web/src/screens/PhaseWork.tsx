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

export function PhaseWork({ onGo }: { onGo: (screen: string, kind?: string, doc?: string) => void }) {
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
        {/* Круг 2: заголовок один — второй давала рейка раздела */}
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
      {/* Круг 2: шкала дат сверху — вехи ◆ именами и линия «сегодня».
          Положения посчитал сервер; здесь только рисование. */}
      <div className="pw-scale">
        <span className="pw-scale__edge">{view.lane_from}</span>
        <div className="pw-scale__track">
          {/* Подсказка здесь — атрибутом, а не обёрткой: обёртка Tooltip
              становится родителем позиционирования, и метки вех слипались
              в левом углу вместо своих долей шкалы. */}
          {(view.scale ?? []).map((m) => (
            <span key={m.gate}
              className={`pw-scale__gate${m.at_pct > 90 ? ' pw-scale__gate--right' : ''}`}
              style={{ left: `${m.at_pct}%` }}
              title={`${m.gate}: ${m.date}`}>
              ◆ {m.gate}
            </span>
          ))}
          {view.today_pct != null && (
            <span className="pw-scale__today" style={{ left: `${view.today_pct}%` }}
              title={`сегодня: ${view.today}`} />
          )}
        </div>
        <span className="pw-scale__edge">{view.lane_to}</span>
      </div>

      <table className="grid pw-lane">
        <tbody>
          {view.items.map((t) => (
            /* Правило Т-1: цель клика — вся строка, синее оставлено действиям */
            <tr key={t.id} className="pw-row" onClick={() => onOpen(t.id)} title={t.why}>
              <td style={{ width: 300 }}>
                <div>{t.order} · {t.name}</div>
                <div className="secondary pw-row__status">{statusText(t)}</div>
              </td>
              <td>
                <LaneBar task={t} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Круг 2: легенда — короткой строкой ПОД лентой, не абзацем над ней */}
      <div className="secondary pw-legend">
        окна — расчётные доли интервала до точки по порядку зависимостей, не обещание сроков;
        красное — до точки меньше недели при неготовом выходе
      </div>
    </div>
  )
}

/** Статус — текстом второй строкой имени (эталон списка), не пилюлей. */
function statusText(task: PhaseWorkTask): string {
  if (task.status === 'waiting') return `ждёт: ${task.waits_on ?? task.input_why}`
  if (task.status === 'done') return 'выполнена'
  if (task.status === 'in_progress') {
    const done = task.steps.filter((s) => s.done).length
    return `в работе · шаг ${done + 1} из ${task.steps.length}`
  }
  return 'доступна'
}

/** Полоса окна: доли посчитал сервер, здесь показ и подпись внутри. */
function LaneBar({ task }: { task: PhaseWorkTask }) {
  if (!task.end || task.lane_width_pct == null) {
    return <Muted why="у выхода задачи не назначена точка — окно считать не от чего" />
  }
  const подписьВнутри = task.status === 'waiting'
    ? `ждёт ${task.waits_on?.split(' · ')[0] ?? ''}`.trim()
    : task.gaps.length > 0
      ? `${statusShort(task)} · разрывы ${task.gaps.length}`
      : statusShort(task)
  const подсказка = [
    `окно ${task.lane_start ?? task.start ?? 'сейчас'} — ${task.lane_end ?? task.end}`,
    `ярус ${task.tier ?? 1} из ${task.tiers ?? 1} до точки ${task.gate ?? '—'}`,
    task.waits_on ? `ждёт: ${task.waits_on}` : 'вход готов',
    task.tight ? 'окно сжато: до точки меньше недели, а выход не готов' : '',
  ].filter(Boolean).join('\n')
  return (
    <Tooltip text={подсказка}>
      <span className="pw-bar__track">
        <span
          className={`pw-bar${task.tight ? ' pw-bar--tight' : ''}` +
            (task.status === 'waiting' ? ' pw-bar--waiting' : '') +
            (task.status === 'done' ? ' pw-bar--done' : '')}
          style={{ marginLeft: `${task.lane_offset_pct ?? 0}%`, width: `${task.lane_width_pct}%` }}
        >
          <span className="pw-bar__label">{подписьВнутри}</span>
        </span>
      </span>
    </Tooltip>
  )
}

/** Короткий статус для подписи внутри полосы. */
function statusShort(task: PhaseWorkTask): string {
  if (task.status === 'done') return 'выполнена'
  if (task.status === 'in_progress') return 'в работе'
  return 'доступна'
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
  onGo: (screen: string, kind?: string, doc?: string) => void
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
