// Экран «Внешняя модель» (ADR-048, шип G): элементы модели Capella по слоям
// (OA · SA · LA · PA) либо учебная fixture — с баннером, что это не
// интеграция; соответствие узлам состава по external_identity. Только чтение:
// в модель система не пишет никогда.
import { useEffect, useState } from 'react'
import { api, type ExternalModelView } from './api'

const СЛОЙ: Record<string, string> = { OA: 'операционный', SA: 'системный', LA: 'логический', PA: 'физический' }

export function ExternalModelScreen({ project }: { project: string | null }) {
  const [вид, setВид] = useState<ExternalModelView | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  useEffect(() => {
    if (!project) return
    api.externalModel(project).then(setВид).catch((e) => setОтказ(String(e)))
  }, [project])
  if (!project) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Читаю внешнюю модель…</div></div>
  const поСлоям = ['OA', 'SA', 'LA', 'PA'].map((с) => [с, вид.elements.filter((э) => э.layer === с)] as const)
  const имяПоUuid = new Map(вид.elements.map((э) => [э.uuid, э.name]))
  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Внешняя модель
        <span className="v2-cnt">{вид.source === 'capella' ? `Capella · ${вид.model_id}` : 'fixture'} · элементов {вид.elements.length} · соответствий {вид.mapping.length}</span>
      </h3>
      {вид.banner && <div className="v2-empty__why" data-why="почему-нельзя">{вид.banner}</div>}
      {поСлоям.map(([слой, элементы]) => элементы.length > 0 && (
        <div key={слой} className="v2-scroll">
          <table className="v2-tab2">
            <thead><tr><th>{СЛОЙ[слой] ?? слой} ({слой})</th><th>Тип</th><th>Родитель</th><th>UUID</th></tr></thead>
            <tbody>
              {элементы.map((э) => (
                <tr key={э.uuid}>
                  <td>{э.name}</td>
                  <td className="v2-dim">{э.type}</td>
                  <td className="v2-dim">{э.parent_uuid ? (имяПоUuid.get(э.parent_uuid) ?? '—') : '—'}</td>
                  <td className="v2-mono" title="UUID — истина элемента, имя — снимок">{э.uuid.slice(0, 8)}…</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      <div className="v2-note-line">
        Соответствие узлам состава — по полю external_identity узла:{' '}
        {вид.mapping.length === 0
          ? 'ни один узел состава на элемент модели не ссылается'
          : вид.mapping.map((с) => `${с.component} → ${с.name}`).join('; ')}
      </div>
    </div>
  )
}
