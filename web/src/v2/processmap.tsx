// Схема процесса (РЕШЕНИЕ-ПРОЦЕСС-НА-ЭКРАНЕ): поверхность работы — та же
// схема, которую исполняет движок.
//
// Два уровня одной схемы: фаза (три дорожки, блоки-сцены, точки-ромбы) и
// сцена (мероприятия с потоками между ними). Рисует БИБЛИОТЕКА
// (@xyflow/react), раскладку считает dagre при каждом показе — координат
// мы не храним и с сервера не получаем: схема есть проекция состояния
// движка, а не картинка.
import { useMemo } from 'react'
import { ReactFlow, Background, Controls, MarkerType, type Edge, type Node } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import type { Activity, Phase, Scene } from './api'

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

/** Уровень фазы: три дорожки, сцены и точки. Клик по сцене — её схема. */
export function PhaseMap({ phase, onScene }: { phase: Phase; onScene: (key: string) => void }) {
  const { nodes, edges } = useMemo(() => {
    const все: Node[] = []
    const рёбра: Edge[] = []
    let полоса = 0
    let сдвиг = 0

    /**
     * Дорожка кладётся отдельной полосой: dagre считает её саму по себе, а
     * полосы разносятся по фактической высоте — иначе ветвление одной
     * дорожки (сцены и точки) наезжает на соседнюю.
     */
    const положить = (заголовок: string, узлы: Node[], связи: Edge[]) => {
      if (узлы.length === 0) return
      const разложенные = раскладка(узлы, связи, 'LR')
      const верхДорожки = Math.min(...разложенные.map((у) => у.position.y))
      const низДорожки = Math.max(...разложенные.map((у) => у.position.y))
      все.push({
        id: `lane-${полоса}`,
        position: { x: -170, y: сдвиг },
        data: { label: <div className="v2-lane-name">{заголовок}</div> },
        style: { width: 150, height: H, border: 'none', background: 'transparent', fontSize: 12 },
        selectable: false,
        draggable: false,
      })
      разложенные.forEach((у) => все.push({
        ...у,
        position: { x: у.position.x, y: у.position.y - верхДорожки + сдвиг },
      }))
      рёбра.push(...связи)
      сдвиг += низДорожки - верхДорожки + H + 48
      полоса += 1
    }

    // Дорожка проектирования: ОДНА цепочка — сцены подряд, точка встаёт
    // между сценами там, где она в жизни. Ветвление ромбов вверх делало из
    // дорожки два ряда, и полоса переставала читаться слева направо.
    const сцены = phase.scenes
    const узлыПроектирования: Node[] = []
    const связиПроектирования: Edge[] = []
    const точкаПосле = (ключ: string) => phase.gates.filter((т) => {
      const ждёт = сцены.filter((с) => с.awaited_by.some((к) => к.includes(т.title)))
      return ждёт[ждёт.length - 1]?.key === ключ
    })
    let предыдущийУзел: string | null = null
    let подписьПотока: string | undefined
    сцены.forEach((с) => {
      const id = `s-${с.key}`
      узлыПроектирования.push(блок(
        id,
        `${с.key} · ${с.title}`,
        подписьСцены(с),
        с.state === 'done' ? 'done' : с.state === 'open' ? 'in_progress' : 'blocked',
        с.key === phase.current_scene,
        () => onScene(с.key),
        с.state === 'locked'
          ? `открыть сцену — закрыта: ${с.blockers[0] ?? 'ждёт предыдущую'}`
          : 'открыть схему мероприятий сцены',
      ))
      if (предыдущийУзел) {
        связиПроектирования.push({
          id: `e-${предыдущийУзел}-${id}`,
          source: предыдущийУзел,
          target: id,
          label: подписьПотока,
          labelStyle: { fontSize: 12, fill: 'var(--ink-soft)' },
          markerEnd: { type: MarkerType.ArrowClosed },
        })
      }
      предыдущийУзел = id
      подписьПотока = с.output || undefined

      точкаПосле(с.key).forEach((т) => {
        const идТочки = `g-${т.key}`
        узлыПроектирования.push(блок(
          идТочки,
          `◆ ${т.title}`,
          т.passed ? 'пройдена' : т.blocking.length > 0 ? `блокирующих ${т.blocking.length}` : 'условия выполнены',
          'gate',
          false,
          undefined,
          т.blocking.length > 0 ? `держат: ${т.blocking.join('; ')}` : 'условия точки выполнены',
        ))
        связиПроектирования.push({
          id: `e-${предыдущийУзел}-${идТочки}`,
          source: предыдущийУзел!,
          target: идТочки,
          markerEnd: { type: MarkerType.ArrowClosed },
        })
        предыдущийУзел = идТочки
        подписьПотока = undefined
      })
    })
    положить('Проектирование', узлыПроектирования, связиПроектирования)

    // Дорожки без сцен: мероприятия метода ждут артефактов из сцен.
    phase.lanes.filter((л) => л.of === 'activities').forEach((дорожка) => {
      const узлы: Node[] = []
      const связи: Edge[] = []
      дорожка.activities.forEach((дело, i) => {
        узлы.push(блок(
          `a-${дорожка.key}-${дело.code}`,
          `${дело.code} · ${дело.name}`,
          дело.state === 'done'
            ? 'выполнено'
            : дело.inputs[0]
              ? `ждёт: ${дело.inputs[0].slice(0, 34)}`
              : 'не начато',
          дело.state === 'done' ? 'done' : 'not_started',
          false,
          undefined,
          `${дело.goal || дело.name}${дело.inputs.length > 0 ? ` · вход: ${дело.inputs.join(' · ')}` : ''}`,
        ))
        const предыдущее = дорожка.activities[i - 1]
        if (предыдущее) {
          связи.push({
            id: `e-${дорожка.key}-${предыдущее.code}-${дело.code}`,
            source: `a-${дорожка.key}-${предыдущее.code}`,
            target: `a-${дорожка.key}-${дело.code}`,
            markerEnd: { type: MarkerType.ArrowClosed },
          })
        }
      })
      положить(дорожка.title, узлы, связи)
    })

    return { nodes: все, edges: рёбра }
  }, [phase])

  // Схема фазы длинная: вписывать её целиком — значит сделать нечитаемой.
  // Показываем окрестность текущей сцены в честном масштабе, остальное —
  // панорамой и колесом (это умеет библиотека).
  const фокус = phase.current_scene ?? phase.scenes[0]?.key
  return (
    <div className="v2-map" style={{ height: 340 }}>
      <ReactFlow nodes={nodes} edges={edges} nodeOrigin={[0.5, 0.5]} fitView
        fitViewOptions={{ nodes: фокус ? [{ id: `s-${фокус}` }] : undefined, maxZoom: 1, minZoom: 0.25, padding: 3 }}
        minZoom={0.2}
        nodesDraggable={false} nodesConnectable={false} elementsSelectable
        onNodeClick={(_, узел) => {
          if (узел.id.startsWith('s-')) onScene(узел.id.slice(2))
        }}>
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}

function подписьСцены(с: Scene): string {
  if (с.state === 'done') return 'выполнена'
  if (с.state === 'locked') return с.blockers[0] ? `ждёт: ${с.blockers[0].slice(0, 28)}` : 'закрыта'
  const дела = с.activities
  const готово = дела.filter((д) => д.state === 'done').length
  return дела.length > 0 ? `в работе · ${готово} из ${дела.length}` : 'в работе'
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
