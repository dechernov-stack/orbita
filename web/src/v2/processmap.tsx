// Схема сцены (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ): «что за чем» — поток мероприятий
// сцены без календаря. Раскладку считает БИБЛИОТЕКА (@xyflow/react +
// @dagrejs/dagre) при каждом показе, как и граф трассировки (ADR-046).
//
// Уровень ФАЗЫ («когда») с 27.09 — карта строками (phasemap.tsx): прежняя
// «станционная» карта с неделями на оси жила здесь и ушла — на 28 сценах
// без плана она разваливалась (владелец: «работать с такой картинкой невозможно»).
import { useMemo } from 'react'
import { ReactFlow, Background, MarkerType, type Edge, type Node } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import type { Activity, Scene } from './api'

const W = 210
const H = 76

/**
 * Цвет несёт состояние вместе с текстом — цвет один смысла не несёт.
 * Значения — ТОКЕНАМИ: один цвет акцента на продукт (§5 дизайна v2),
 * и второй его копии в коде схемы быть не должно.
 */
const ЦВЕТ: Record<string, string> = {
  done: 'var(--v2-done)',
  in_progress: 'var(--accent-soft)',
  available: 'var(--ground)',
  not_started: 'var(--ground)',
  blocked: 'var(--ground-soft)',
  gate: 'var(--v2-gate)',
}

const СОСТОЯНИЕ: Record<string, string> = {
  done: 'выполнено',
  in_progress: 'в работе',
  available: 'доступно',
  not_started: 'не начато',
  blocked: 'ждёт входа',
}

function раскладка(узлы: Node[], рёбра: Edge[], направление: 'LR' | 'TB'): Node[] {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: направление, nodesep: 24, ranksep: 80 })
  узлы.forEach((у) => g.setNode(у.id, { width: W, height: H }))
  рёбра.forEach((р) => g.setEdge(р.source, р.target))
  dagre.layout(g)
  return узлы.map((у) => ({ ...у, position: { x: g.node(у.id).x, y: g.node(у.id).y } }))
}

/**
 * Блок схемы. Кликабельный — НАСТОЯЩАЯ КНОПКА внутри узла, а не обработчик
 * на полотне: полотно съедает клик, стоит мыши дрогнуть (это уже панорама),
 * и блок кажется мёртвым. Кнопка ловит клик сама и несёт курсор-указатель.
 */
function блок(
  id: string,
  заголовок: string,
  подпись: string,
  состояние: string,
  текущий = false,
  onClick?: () => void,
  подсказка?: string,
): Node {
  const содержимое = (
    <span className="v2-node">
      <b>{заголовок}</b>
      <span className="v2-node__st">{подпись}</span>
    </span>
  )
  return {
    id,
    position: { x: 0, y: 0 },
    data: {
      label: onClick
        ? (
          <button type="button" className="v2-node__btn" title={подсказка ?? 'открыть'}
            onClick={(e) => { e.stopPropagation(); onClick() }}>
            {содержимое}
          </button>
        )
        : <span className="v2-node__flat" title={подсказка}>{содержимое}</span>,
    },
    style: {
      width: W,
      height: H,
      background: ЦВЕТ[состояние] ?? 'var(--ground)',
      border: текущий ? '2px solid var(--accent)' : '1px solid var(--line)',
      borderRadius: 8,
      padding: 0,
      fontSize: 12,
      cursor: onClick ? 'pointer' : 'default',
    },
  }
}

/** Уровень сцены: мероприятия с потоками. Клик — рабочая поверхность. */
export function SceneMap({ scene, current, onPick }: {
  scene: Scene
  current: string | null
  onPick: (code: string) => void
}) {
  const { nodes, edges } = useMemo(() => {
    const узлы: Node[] = scene.activities.map((дело) => блок(
      `a-${дело.code}`,
      `${дело.code} · ${дело.name}`,
      подписьМероприятия(дело),
      дело.state,
      дело.code === current,
      () => onPick(дело.code),
      дело.goal,
    ))
    const рёбра: Edge[] = scene.activities.slice(1).map((дело, i) => {
      const предыдущее = scene.activities[i]
      return {
        id: `e-${предыдущее.code}-${дело.code}`,
        source: `a-${предыдущее.code}`,
        target: `a-${дело.code}`,
        // Стрелка подписана артефактами: это и есть поток метода.
        label: предыдущее.outputs.map((в) => в.what).slice(0, 2).join(' · '),
        labelStyle: { fontSize: 12, fill: 'var(--ink-soft)' },
        markerEnd: { type: MarkerType.ArrowClosed },
      }
    })
    return { nodes: раскладка(узлы, рёбра, 'LR'), edges: рёбра }
  }, [scene, current])

  if (scene.activities.length === 0) {
    return (
      <div className="v2-empty">
        Мероприятий у сцены пока нет.
        <span className="v2-empty__why">
          Схема сцены порождается из полки процессов ЖЦ: сцена без мероприятий — это сцена,
          которую метод ещё не описал.
        </span>
      </div>
    )
  }

  return (
    <div className="v2-map" style={{ height: 160 }}>
      <ReactFlow nodes={nodes} edges={edges} nodeOrigin={[0.5, 0.5]} fitView fitViewOptions={{ maxZoom: 1, padding: 0.15 }}
        nodesDraggable={false} nodesConnectable={false} elementsSelectable
        onNodeClick={(_, узел) => onPick(узел.id.slice(2))}>
        <Background />
      </ReactFlow>
    </div>
  )
}

function подписьМероприятия(дело: Activity): string {
  const свои = дело.outputs.filter((в) => !в.produced_in)
  const есть = свои.filter((в) => в.satisfied).length
  const счёт = свои.map((в) => `${в.what}: ${в.count}`).slice(0, 2).join(' · ')
  return `${СОСТОЯНИЕ[дело.state]}${свои.length > 0 ? ` · выходов ${есть} из ${свои.length}` : ''}${счёт ? ` · ${счёт}` : ''}`
}
