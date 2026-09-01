// Круг 4: третий вид «Работы» — СХЕМА, карта потока фазы.
//
// Список отвечает «что», лента — «когда», рамка — «как делать». Здесь —
// «как течёт»: узлы-задачи ярусами зависимостей, рёбра подписаны именами
// артефактов, точки ◆ несут процент из той же готовности к точке, за
// последними воротами — следующая фаза свёрнутым облаком.
//
// Схема — ВЫЧИСЛЕННАЯ ПРОЕКЦИЯ, не рисунок: вся геометрия (координаты узлов,
// вершины ромбов, пути и места подписей) приходит с сервера и нигде не
// хранится. Редактора схемы, перетаскивания и «сохранить раскладку» здесь
// нет по построению — сторож tools/validate_flow_computed.py это держит.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PhaseFlowView } from '../api/types'
import { relTime } from '../ui/relTime'

type Node = NonNullable<PhaseFlowView['nodes']>[number]

export function PhaseFlow({ here, onOpenTask, onGo }: {
  /** «вы здесь»: задача, открытая в рамке ведения. */
  here?: string
  onOpenTask?: (taskId: string) => void
  onGo: (screen: string, kind?: string, doc?: string) => void
}) {
  const [view, setView] = useState<PhaseFlowView | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** Живость процесса: фильтр подсвечивает узлы, где было движение. */
  const [week, setWeek] = useState(false)

  useEffect(() => {
    api.phaseFlow().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>
  if (!view.nodes || !view.width || !view.height) {
    return <div className="empty">{view.empty_why ?? 'Поток фазы рисовать не по чему.'}</div>
  }

  const dim = (n: Node) => week && n.kind === 'task' && !n.recent

  return (
    <div className="card">
      <div className="toolbar" style={{ marginTop: 0 }}>
        <span className="secondary">
          поток фазы {view.phase_label}: задача → артефакт → задача либо точка
        </span>
        <span style={{ flex: 1 }} />
        <button className={`tab${week ? ' tab--primary' : ''}`} onClick={() => setWeek(!week)}
          title="подсветить узлы, где было движение за последнюю неделю; остальные приглушаются">
          За неделю
        </button>
      </div>

      <div className="fl-wrap">
        <svg className="fl-svg" viewBox={`0 0 ${view.width} ${view.height}`}
          style={{ width: view.width, height: view.height }}>
          <defs>
            <marker id="fl-arrow" viewBox="0 0 8 8" refX="7" refY="4"
              markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 1 L 8 4 L 0 7 z" className="fl-arrow" />
            </marker>
          </defs>

          {(view.edges ?? []).map((e) => (
            <g key={`${e.from}→${e.to}`}>
              <path d={e.path} markerEnd="url(#fl-arrow)"
                className={`fl-edge fl-edge--${e.kind}${e.ready ? ' fl-edge--ready' : ''}`} />
              {e.label && (
                <text x={e.label_x} y={e.label_y} className="fl-edge__label"
                  onClick={() => e.document_code && onGo('docs', undefined, e.document_code)}
                  style={{ cursor: e.document_code ? 'pointer' : 'default' }}>
                  <title>
                    {e.full}
                    {e.document_code ? ' — открыть документ' : ' — артефакт документом не оформлен'}
                    {e.ready ? '\nвыход готов' : '\nвыход ещё не готов'}
                  </title>
                  {e.label}
                </text>
              )}
            </g>
          ))}

          {view.nodes.map((n) => (
            <g key={n.id} className={dim(n) ? 'fl-dim' : undefined}>
              {/* ромб точки — по вершинам, посчитанным сервером */}
              {n.kind === 'gate' && <polygon points={n.points} className="fl-gate__shape" />}
              <foreignObject x={n.x} y={n.y} width={n.w} height={n.h}>
                {n.kind === 'task'
                  ? <TaskNode n={n} here={here === n.id} onOpenTask={onOpenTask} />
                  : n.kind === 'gate'
                    ? <GateNode n={n} onGo={onGo} />
                    : <CloudNode n={n} />}
              </foreignObject>
            </g>
          ))}
        </svg>
      </div>

      <div className="secondary fl-legend">
        схема считается каждый раз из зависимостей задач полки — координат она
        не хранит и рисовать её руками негде; клик узла ведёт задачу, клик
        точки — к её готовности, клик подписи ребра открывает артефакт
      </div>
    </div>
  )
}

/** Узел-задача: имя, статус цветом, люди, разрывы, последняя активность. */
function TaskNode({ n, here, onOpenTask }: {
  n: Node
  here: boolean
  onOpenTask?: (taskId: string) => void
}) {
  const подсказка = [
    n.why ?? '',
    n.waits_on ? `ждёт: ${n.waits_on}` : '',
    n.artifact ? `выход: ${n.artifact}${n.gate ? ` к ${n.gate}` : ''}` : '',
    n.activity ? `${n.activity.what} · ${n.activity.author}` : 'содержательных правок ещё не было',
  ].filter(Boolean).join('\n')
  return (
    <button className={`fl-node fl-node--${n.status}${here ? ' fl-node--here' : ''}`}
      onClick={() => onOpenTask?.(n.id)} title={подсказка}>
      <span className="fl-node__head">
        <b>{n.order} · {n.name}</b>
      </span>
      <span className="fl-node__row">
        <span className="secondary">
          {n.status === 'waiting' ? 'ожидает'
            : n.status === 'done' ? 'выполнена'
              : (n.steps_done ?? 0) < (n.steps_total ?? 0)
                ? `шаг ${(n.steps_done ?? 0) + 1} из ${n.steps_total ?? 0}`
                : 'шаги пройдены'}
        </span>
        {(n.gaps ?? 0) > 0 && <span className="fl-chip fl-chip--warn">разрывы · {n.gaps}</span>}
        {here && <span className="fl-chip fl-chip--here">вы здесь</span>}
      </span>
      <span className="fl-node__row">
        <span className="fl-people">
          {(n.people ?? []).map((p) => (
            <span key={p.name} className="fl-ava" title={p.name}>{p.initials}</span>
          ))}
        </span>
        <span className="secondary fl-node__act">
          {n.activity
            ? `${n.activity.initials} · ${relTime(n.activity.at)}`
            : 'движения нет'}
        </span>
      </span>
    </button>
  )
}

/** Точка ◆ — узел-ворота: процент берётся из готовности, не считается заново. */
function GateNode({ n, onGo }: { n: Node; onGo: (s: string, k?: string, d?: string) => void }) {
  return (
    <button className="fl-gate" onClick={() => onGo('readiness', undefined, n.gate)}
      title={`${n.label}${n.due ? ` · ${n.due}` : ''}\n${n.note ?? ''}${
        n.blocking_open ? `\nблокирует фиксацию: ${n.blocking_open}` : ''}\nоткрыть готовность к точке`}>
      <span className="fl-gate__name">{n.gate}</span>
      <span className="fl-gate__pct">{n.pct != null ? `${n.pct}%` : '—'}</span>
    </button>
  )
}

/** Следующая фаза — свёрнутым облаком: ИС её показывает, а не проводит. */
function CloudNode({ n }: { n: Node }) {
  return (
    <span className="fl-cloud" title={n.note}>
      <b>{n.name}</b>
      <span className="secondary">за воротами</span>
    </span>
  )
}
