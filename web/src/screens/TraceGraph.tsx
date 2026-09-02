// Экран «Трассировка и impact» (ADR-046): граф нужда → требование → узел
// состава → интерфейс → событие верификации → документ. Рисует БИБЛИОТЕКА
// (@xyflow/react), раскладку считает dagre при каждом показе — координаты
// не хранятся и не приходят с сервера: граф — проекция модели, а не рисунок.
//
// Клиент ничего не выводит из модели: узлы, рёбра, группы impact и
// кратчайший путь приходят готовыми. Здесь — только отрисовка, фокус,
// глубина и переходы к объектам.
import { useEffect, useMemo, useState } from 'react'
import { ReactFlow, Background, Controls, MiniMap, MarkerType, type Node, type Edge } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import { api } from '../api/client'
import { requestObject, screenOfObject, takeObject } from '../api/intent'
import type { TraceGraphView } from '../api/types'

const KIND_LABEL: Record<string, string> = {
  need: 'нужда', service: 'сервис', goal: 'цель', conops: 'сценарий ConOps',
  requirement: 'требование', node: 'узел состава', interface: 'интерфейс',
  event: 'событие верификации', evidence: 'свидетельство', document: 'документ', missing: 'битая ссылка',
}

const KIND_COLOR: Record<string, string> = {
  need: '#e8f1ff', service: '#eef7ee', goal: '#fff6e0', conops: '#f3f0ff',
  requirement: '#ffffff', node: '#f1f5f9', interface: '#fdf2f8',
  event: '#ecfeff', evidence: '#f0fdf4', document: '#fefce8', missing: '#fee2e2',
}

const EDGE_LABEL: Record<string, string> = {
  trace: 'трассировка', derive: 'декомпозиция', allocation: 'распределение', conflict: 'противоречие',
  side: 'сторона интерфейса', verifies: 'верифицирует', evidence: 'свидетельство',
  inserted_in: 'вставлено в документ', source: 'источник', depends_on: 'зависит',
}

const GROUP_LABEL: Record<string, string> = {
  needs: 'Нужды и источники', parents: 'Родители', children: 'Дети', dependents: 'Зависимые',
  conflicts: 'Противоречия', events: 'События верификации', carriers: 'Носители (узлы)',
  interfaces: 'Интерфейсы', documents: 'Документы со вставкой', broken: 'Битые ссылки',
}

const NODE_W = 190
const NODE_H = 46

/** Раскладка dagre: центры узлов; xyflow принимает центр через nodeOrigin. */
function layout(view: TraceGraphView): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: 18, ranksep: 70 })
  view.nodes.forEach((n) => g.setNode(n.id, { width: NODE_W, height: NODE_H }))
  view.edges.forEach((e) => g.setEdge(e.from, e.to))
  dagre.layout(g)
  const onPath = new Set(view.path ?? [])
  const nodes: Node[] = view.nodes.map((n) => {
    const p = g.node(n.id)
    const isFocus = n.id === view.focus
    return {
      id: n.id,
      position: { x: p.x, y: p.y },
      data: {
        label: (
          <div className="tg-node" title={`${KIND_LABEL[n.kind] ?? n.kind}${n.status ? ` · ${n.status}` : ''}`}>
            <div className="tg-kind">{KIND_LABEL[n.kind] ?? n.kind}{n.status ? ` · ${n.status}` : ''}</div>
            <div className="tg-title"><span className="mono">{n.id.startsWith('DOC:') ? n.id.slice(4) : n.id}</span> {n.title}</div>
          </div>
        ),
      },
      style: {
        width: NODE_W,
        background: KIND_COLOR[n.kind] ?? '#fff',
        border: isFocus ? '2px solid var(--accent)' : onPath.has(n.id) ? '2px solid #d97706' : '1px solid var(--hairline)',
        borderRadius: 6,
        padding: 4,
        fontSize: 11,
      },
    }
  })
  const edges: Edge[] = view.edges.map((e) => {
    const conflict = e.kind === 'conflict'
    const inPath = onPath.has(e.from) && onPath.has(e.to)
    return {
      id: `${e.from}→${e.to}:${e.kind}`,
      source: e.from,
      target: e.to,
      type: 'smoothstep',
      label: EDGE_LABEL[e.kind] ?? e.kind,
      labelStyle: { fontSize: 9, fill: 'var(--muted)' },
      markerEnd: { type: MarkerType.ArrowClosed },
      animated: conflict,
      style: {
        stroke: conflict ? '#dc2626' : inPath ? '#d97706' : 'var(--muted)',
        strokeDasharray: conflict ? '6 4' : undefined,
        strokeWidth: inPath ? 2 : 1,
      },
    }
  })
  return { nodes, edges }
}

export function TraceGraph({ onGo }: { onGo?: (screen: string, kind?: string, target?: string) => void }) {
  const [focus, setFocus] = useState<string>(() => takeObject() ?? '')
  const [focusInput, setFocusInput] = useState<string>(focus)
  const [depth, setDepth] = useState(2)
  const [to, setTo] = useState('')
  const [view, setView] = useState<TraceGraphView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    setError(null)
    api.traceGraph(focus || undefined, depth, to || undefined)
      .then(setView)
      .catch((e) => setError(String(e)))
  }, [focus, depth, to])

  // раскладка пересчитывается на каждый ответ; смена окрестности перемонтирует
  // полотно (key), чтобы библиотека заново вписала граф в окно
  const laid = useMemo(() => (view ? layout(view) : { nodes: [], edges: [] }), [view])

  const open = (id: string) => {
    const screen = screenOfObject(id)
    if (screen && onGo) { requestObject(id); onGo(screen) }
  }

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка графа…</div>

  return (
    <div className="split tg-split">
      <div className="pane" style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div className="rr-tbar" style={{ gap: 8 }}>
          <span className="rr-xk">Фокус</span>
          <input
            className="rr-search"
            style={{ width: 110 }}
            placeholder="RQ-0001"
            value={focusInput}
            onChange={(e) => setFocusInput(e.target.value.trim())}
            onKeyDown={(e) => { if (e.key === 'Enter') setFocus(focusInput) }}
          />
          <button type="button" className="rr-btn" onClick={() => setFocus(focusInput)}>в фокус</button>
          {focus && <button type="button" className="rr-btn" onClick={() => { setFocus(''); setFocusInput(''); setTo('') }}>весь граф</button>}
          <span className="rr-xk">Глубина</span>
          <span className="tabs" style={{ display: 'inline-flex' }}>
            {[1, 2, 3, 4].map((d) => (
              <button key={d} type="button" className={`tab${depth === d ? ' on' : ''}`} aria-selected={depth === d} onClick={() => setDepth(d)}>{d}</button>
            ))}
          </span>
          <span className="rr-xk">Путь до</span>
          <input
            className="rr-search"
            style={{ width: 110 }}
            placeholder="DOC:SRD"
            value={to}
            onChange={(e) => setTo(e.target.value.trim())}
            title="кратчайший путь от фокуса по связям — считает сервер"
          />
          <span style={{ flex: 1 }} />
          <span className="secondary">узлов: {view.nodes.length} · рёбер: {view.edges.length}{view.counts_missing ? ` · битых ссылок: ${view.counts_missing}` : ''}</span>
        </div>
        {view.focus_note && <div className="warn" style={{ padding: '4px 14px' }}>{view.focus_note}</div>}
        {view.path_note && <div className="warn" style={{ padding: '4px 14px' }}>{view.path_note}</div>}
        {view.nodes.length === 0 ? (
          <div className="empty">Графа нет: в проекте нет нужд, требований и узлов состава — трассировке не по чему идти.</div>
        ) : (
          <div className="tg-canvas">
            <ReactFlow
              key={`${view.focus ?? 'all'}:${view.depth}:${view.nodes.length}`}
              nodes={laid.nodes}
              edges={laid.edges}
              nodeOrigin={[0.5, 0.5]}
              fitView
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable
              onNodeClick={(_, n) => setSelected(n.id)}
              onNodeDoubleClick={(_, n) => { setFocus(n.id); setFocusInput(n.id) }}
              proOptions={{ hideAttribution: true }}
              minZoom={0.1}
            >
              <Background />
              <Controls showInteractive={false} />
              <MiniMap pannable zoomable />
            </ReactFlow>
          </div>
        )}
        <div className="tg-legend secondary">
          {Object.entries(KIND_LABEL).map(([k, l]) => (
            <span key={k} className="tg-lg"><span className="tg-sw" style={{ background: KIND_COLOR[k] }} />{l}</span>
          ))}
          <span className="tg-lg">— — противоречие</span>
          <span className="tg-lg">{view.functions_note}</span>
        </div>
      </div>

      <div className="pane" style={{ padding: 12, overflow: 'auto', width: 300 }}>
        <h3 className="pbs-head">Impact {view.focus ? <span className="mono">{view.focus}</span> : <span className="secondary">· выберите фокус</span>}</h3>
        {!view.focus && (
          <div className="secondary" style={{ fontSize: 12 }}>
            Двойной клик по узлу или ввод обозначения — фокус; глубина ограничивает окрестность; «путь до» подсвечивает кратчайший путь.
          </div>
        )}
        {view.groups && Object.entries(view.groups).map(([g, ids]) => (
          <div key={g} className="card" style={{ marginTop: 6 }}>
            <div className="rr-xk">{GROUP_LABEL[g] ?? g} · {ids.length}</div>
            {ids.length === 0
              ? <div className="secondary" style={{ fontSize: 12 }}>— пусто</div>
              : ids.map((id) => (
                <div key={id} style={{ fontSize: 12 }}>
                  <button type="button" className="rr-assign mono" onClick={() => { setFocus(id); setFocusInput(id) }} title="сделать фокусом">{id}</button>
                  {screenOfObject(id) && onGo && (
                    <button type="button" className="rr-assign" style={{ marginLeft: 6 }} onClick={() => open(id)} title="открыть в штатном экране">к месту →</button>
                  )}
                </div>
              ))}
          </div>
        ))}
        {view.path && view.path.length > 0 && (
          <div className="card" style={{ marginTop: 6 }}>
            <div className="rr-xk">Кратчайший путь · {view.path.length} узлов</div>
            <div className="mono" style={{ fontSize: 12 }}>{view.path.join(' → ')}</div>
          </div>
        )}
        {selected && selected !== view.focus && (
          <div className="card" style={{ marginTop: 6 }}>
            <div className="rr-xk">Выбран</div>
            <div className="mono" style={{ fontSize: 12 }}>{selected}</div>
            <button type="button" className="rr-assign" onClick={() => { setFocus(selected); setFocusInput(selected) }}>сделать фокусом</button>
            {screenOfObject(selected) && onGo && (
              <button type="button" className="rr-assign" style={{ marginLeft: 6 }} onClick={() => open(selected)}>к месту →</button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
