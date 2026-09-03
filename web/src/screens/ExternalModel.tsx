// Экран «Внешняя модель» (ADR-048): элементы модели Capella как узлы-ссылки
// (UUID — истина, имя — снимок с датой), связи требований с обоснованием и
// баннер честности: пока адаптер выключен или среди элементов есть fixture,
// экран говорит «не доказательство интеграции». В модель система не пишет.
import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { ExternalModelView } from '../api/types'
import { useSession } from '../ui/session'
import { KindRegistry } from './KindRegistry'

export function ExternalModel({ onGo }: { onGo?: (screen: string, kind?: string, target?: string) => void }) {
  const { label } = useSession()
  const [view, setView] = useState<ExternalModelView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = () => api.externalModel().then(setView).catch((e) => setError(String(e)))
  useEffect(() => { void load() }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка внешней модели…</div>

  return (
    <div className="pane" style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {view.fixture_banner && (
        <div className="warn" style={{ padding: '6px 14px' }} role="status">
          {view.banner}
        </div>
      )}
      <div className="rr-tbar" style={{ gap: 8 }}>
        <span className="secondary">элементов: {view.elements.length} · связей: {view.links.length} · адаптер: {view.adapter_enabled ? 'включён' : 'выключен'}</span>
        <span style={{ flex: 1 }} />
        <button
          type="button"
          className="rr-btn"
          title={view.adapter_enabled ? 'прочитать модель адаптером (только чтение) и обновить снимки имён' : 'адаптер Capella выключен — обновление честно откажет'}
          onClick={() => {
            setBusy(true)
            api.capellaRefresh()
              .then((r) => { setNotice(`обновлено из ${r.model_id}: новых ${r.created}, обновлено ${r.updated}`); void load() })
              .catch((e) => setNotice(e instanceof ApiError ? e.message : String(e)))
              .finally(() => setBusy(false))
          }}
        >
          {busy ? 'Читаем модель…' : 'Обновить из Capella'}
        </button>
      </div>
      {notice && <div className="warn" style={{ padding: '4px 14px' }}>{notice}</div>}
      <div style={{ padding: '4px 14px' }}>
        <h3 className="pbs-head">Элементы модели <span className="secondary">· {view.elements.length}</span></h3>
        {view.elements.length === 0
          ? <div className="empty">Элементов нет: загрузите fixture пакетом или включите адаптер с моделью.</div>
          : (
            <table>
              <thead><tr><th style={{ width: 80 }}>ID</th><th>Имя (снимок)</th><th style={{ width: 170 }}>Тип</th><th style={{ width: 120 }}>Слой</th><th style={{ width: 150 }}>Снимок от</th></tr></thead>
              <tbody>
                {view.elements.map((e) => (
                  <tr key={e.id} className="pbs-row" style={{ color: 'var(--muted)' }} title={`uuid ${e.uuid} · модель ${e.model_id}`}>
                    <td className="mono">{e.id}</td>
                    <td>{e.name}{e.fixture && <span className="chip" style={{ marginLeft: 6 }}>fixture</span>}</td>
                    <td>{label('model_element_type', e.type)}</td>
                    <td>{label('model_element_layer', e.layer)}</td>
                    <td>{e.refreshed_at ? e.refreshed_at.slice(0, 10) : 'не обновлялся'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        <h3 className="pbs-head">Связи требований <span className="secondary">· {view.links.length}</span></h3>
        {view.links.length === 0
          ? <div className="empty">Связей пока нет: заведите arch_link — требование → элемент, вид и обоснование обязательны.</div>
          : (
            <table>
              <thead><tr><th style={{ width: 80 }}>ID</th><th style={{ width: 90 }}>Требование</th><th style={{ width: 90 }}>Элемент</th><th style={{ width: 140 }}>Вид</th><th>Обоснование</th></tr></thead>
              <tbody>
                {view.links.map((l) => (
                  <tr key={l.id}>
                    <td className="mono">{l.id}</td>
                    <td><button type="button" className="rr-assign mono" onClick={() => onGo?.('req', 'requirement', l.requirement)}>{l.requirement}</button></td>
                    <td className="mono">{l.element}</td>
                    <td>{label('arch_link_relation', l.relation)}</td>
                    <td>{l.rationale}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
      </div>
      <div style={{ flex: 1, minHeight: 0, display: 'grid' }}>
        <KindRegistry kinds={['model_element', 'arch_link']} title="Правка элементов и связей" />
      </div>
    </div>
  )
}
