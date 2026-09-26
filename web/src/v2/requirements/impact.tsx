// «Что заденет правка» — граф влияния связями; рисует библиотека (ADR-046).
import { useEffect, useMemo, useState } from 'react'
import { ReactFlow, Background, Controls, MarkerType, type Node, type Edge } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import { api, type ImpactGraph } from '../api'

/**
 * «Что заденет правка» — граф влияния СВЯЗЯМИ, а не догадкой по таблице.
 *
 * Прежде соседство считалось здесь же: общий носитель и упоминание кода в
 * источниках. Это давало ответ, похожий на правду, но видело только
 * требования и только те, что уже загружены на экран. Настоящие нити
 * лежат в реестре связей и ведут дальше — к узлам, функциям, моделям, — и
 * ходить по ним умеет сервер. Глубина спрашивается: «на два шага» и «на
 * четыре» — разные ответы, и человек выбирает, насколько далеко смотреть.
 *
 * Рисует БИБЛИОТЕКА (ADR-046): раскладку считает dagre при каждом показе,
 * координаты не хранятся ни на сервере, ни в браузере — иначе картинка
 * однажды начнёт врать про вчерашние связи.
 */
export function Impact({ код, project }: { код: string; project: string }) {
  const [граф, setГраф] = useState<ImpactGraph | null>(null)
  const [глубина, setГлубина] = useState(2)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    let живо = true
    setГраф(null); setОтказ(null)
    api.impact(project, код, глубина)
      .then((г) => { if (живо) setГраф(г) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [project, код, глубина])

  const разложено = useMemo(() => (граф ? раскладка(граф) : { nodes: [], edges: [] }), [граф])

  return (
    <div className="v2-card" data-why="почему-нельзя">
      <div className="v2-card__head">
        <span className="v2-card__title">Что заденет правка {код}</span>
        <span className="v2-card__count">{граф ? граф.nodes.length - 1 : '…'}</span>
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {!отказ && !граф && <div className="v2-empty">читаю связи…</div>}
      {!отказ && граф && <ImpactCanvas граф={граф} код={код} глубина={глубина}
        setГлубина={setГлубина} разложено={разложено} />}
    </div>
  )
}

function ImpactCanvas({ граф, код, глубина, setГлубина, разложено }: {
  граф: ImpactGraph
  код: string
  глубина: number
  setГлубина: (г: number) => void
  разложено: { nodes: Node[]; edges: Edge[] }
}) {
  return (
    <>
      <div className="v2-kf__bar">
        <span className="v2-dim">на шагов:</span>
        {[1, 2, 4].map((г) => (
          <button key={г} type="button"
            className={глубина === г ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title={`пройти по связям на ${г} ${г === 1 ? 'шаг' : 'шага'} от требования`}
            onClick={() => setГлубина(г)}>{г}</button>
        ))}
      </div>
      {граф.nodes.length <= 1 ? (
        <div className="v2-dim">
          ничего: у требования нет связей — ни носителя, ни выведенных из него.
        </div>
      ) : (
        <>
          <div className="v2-imp__canvas">
            <ReactFlow
              key={`${код}:${глубина}:${граф.nodes.length}`}
              nodes={разложено.nodes}
              edges={разложено.edges}
              nodeOrigin={[0.5, 0.5]}
              fitView
              // Подгонка после готовности: раскладка dagre даёт координаты в
              // своих единицах, и без неё граф открывается не в кадре.
              onInit={(инстанс) => { setTimeout(() => инстанс.fitView(), 0) }}
              nodesDraggable={false}
              nodesConnectable={false}
              proOptions={{ hideAttribution: true }}
              minZoom={0.2}
              // Без потолка подгонка раздувает три узла во весь экран.
              maxZoom={1.2}
            >
              <Background />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
          <div className="v2-dim">
            {граф.summary || 'после правки эти связи станут подозрительными — их придётся подтвердить.'}
          </div>
        </>
      )}
    </>
  )
}

const ШИР = 168
const ВЫС = 54

/** Раскладка dagre при каждом показе: координаты нигде не живут. */
function раскладка(граф: ImpactGraph): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: 22, ranksep: 90 })
  граф.nodes.forEach((у) => g.setNode(у.id, { width: ШИР, height: ВЫС }))
  граф.edges.forEach((р) => g.setEdge(р.from, р.to))
  dagre.layout(g)
  return {
    nodes: граф.nodes.map((у) => {
      const т = g.node(у.id)
      const корень = у.depth === 0
      return {
        id: у.id,
        position: { x: т.x, y: т.y },
        // Размер задан явно, а не оставлен на обмер: пока библиотека не
        // обмерила узел, она держит его скрытым, а обмер идёт наблюдателем
        // размеров — во вкладке, которую браузер не рисует (фон, свёрнутое
        // окно), наблюдатель молчит, и граф остаётся пустым полотном.
        width: ШИР,
        height: ВЫС,
        data: {
          label: (
            <div className="v2-imp__node" title={`${у.kind}: ${у.title}`}>
              <div className="v2-imp__kind">{ВИД_УЗЛА[у.kind] ?? у.kind}</div>
              <div className="v2-mono">{у.code}</div>
            </div>
          ),
        },
        style: {
          width: ШИР,
          fontSize: 12,
          borderRadius: 6,
          border: корень ? '2px solid var(--accent)' : '1px solid var(--line)',
          background: 'var(--ground)',
        },
      } as Node
    }),
    // Подписью идёт ТИП связи, а не её обоснование: обоснование бывает
    // целым предложением («поведение исполняется на бортовой вычислительной
    // машине») и ложится поверх соседних узлов. Причина — в подсказке.
    edges: граф.edges.map((р, i) => ({
      id: `${р.from}->${р.to}:${i}`,
      source: р.from,
      target: р.to,
      label: СВЯЗЬ[р.type] ?? р.type,
      labelStyle: { fontSize: 12 },
      labelShowBg: true,
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { stroke: 'var(--line)' },
      data: { why: р.why },
    } as Edge)),
  }
}

/** Тип связи по-русски: подпись ребра короткая, причина — в подсказке. */
const СВЯЗЬ: Record<string, string> = {
  carrier: 'носитель',
  target: 'носитель анкеты',
  deployed_on: 'развёрнуто на',
  derived_from_fact: 'из факта',
  derived_from: 'выведено из',
  owns: 'владеет',
  covers: 'покрывает',
  snapshot_of: 'напечатано в',
  verifies: 'проверяет',
  allocation: 'размещено на',
  trace: 'трассировка',
  conflicts_with: 'противоречит',
}

/** Вид узла по-русски: латинское имя вида инженеру ничего не говорит. */
const ВИД_УЗЛА: Record<string, string> = {
  requirement: 'требование',
  component: 'узел',
  need: 'нужда',
  goal: 'цель',
  service: 'сервис',
  interface: 'стык',
  function: 'функция',
  model: 'модель',
  parameter: 'анкета',
  constraint: 'ограничение',
  stakeholder: 'сторона',
  technology: 'технология',
  risk: 'риск',
  document: 'документ',
  event: 'событие',
}
