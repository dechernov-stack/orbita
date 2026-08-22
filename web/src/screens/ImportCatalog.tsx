// Импорт записи каталога устройств (шаг 14, ADR-024; шаг 16 §2.4).
//
// ПО ОДНОЙ ЗАПИСИ, по действию инженера: массовой синхронизации каталога нет
// как пути — правовой режим источника (sui generis) запрещает выгрузку целиком.
// Возвращается ЧЕРНОВИК с зафиксированным происхождением; хранение идёт
// обычным каналом, тем же фильтром, что рукописный ввод.
import { useState } from 'react'
import { api, ApiError } from '../api/client'

const SOURCES = ['lorawan-devices', 'vendor-datasheet']

const EXAMPLE_DEVICE = '{"name": "…", "vendor": "…"}'
const EXAMPLE_PROFILE = '{"region": "EU863-870", "supportsClassC": false}'

export function ImportCatalog() {
  const [source, setSource] = useState(SOURCES[0])
  const [datasetVersion, setDatasetVersion] = useState('')
  const [retrievedAt, setRetrievedAt] = useState('')
  const [itemRef, setItemRef] = useState('')
  const [device, setDevice] = useState('')
  const [profile, setProfile] = useState('')
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = () => {
    setError(null)
    setDraft(null)
    let deviceJson: Record<string, unknown>
    let profileJson: Record<string, unknown>
    try {
      deviceJson = JSON.parse(device) as Record<string, unknown>
      profileJson = JSON.parse(profile) as Record<string, unknown>
    } catch (e) {
      setError(`запись не разобрана как JSON: ${String(e)}`)
      return
    }
    api
      .importTerminalProfile({
        source,
        dataset_version: datasetVersion,
        retrieved_at: retrievedAt,
        item_ref: itemRef || undefined,
        device: deviceJson,
        profile: profileJson,
      })
      .then(setDraft)
      .catch((e) => setError(e instanceof ApiError ? e.message.slice(0, 300) : String(e)))
  }

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Импорт записи каталога устройств</h2>
        <div className="field">
          <label>Источник</label>
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            {SOURCES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
          <span className="secondary"> источник без описанного правового режима запрещён</span>
        </div>
        <div className="field">
          <label>Версия набора</label>
          <input value={datasetVersion} onChange={(e) => setDatasetVersion(e.target.value)} style={{ width: 140 }} />
          <label> Получено (UTC)</label>
          <input value={retrievedAt} onChange={(e) => setRetrievedAt(e.target.value)}
            placeholder="2026-08-22T00:00:00Z" style={{ width: 180 }} />
          <label> Ссылка записи</label>
          <input value={itemRef} onChange={(e) => setItemRef(e.target.value)} style={{ width: 160 }} />
        </div>
        <div className="field">
          <label>device</label>
          <textarea rows={4} value={device} onChange={(e) => setDevice(e.target.value)}
            placeholder={EXAMPLE_DEVICE} style={{ width: '100%', fontFamily: 'var(--font-mono)' }} />
        </div>
        <div className="field">
          <label>profile</label>
          <textarea rows={4} value={profile} onChange={(e) => setProfile(e.target.value)}
            placeholder={EXAMPLE_PROFILE} style={{ width: '100%', fontFamily: 'var(--font-mono)' }} />
        </div>
        <button type="button" className="tab tab--primary" onClick={run}>
          Импортировать запись
        </button>
        {error && <div className="warn" style={{ padding: 8, marginTop: 8 }}>{error}</div>}
        {draft && (
          <div className="card" style={{ marginTop: 8 }}>
            <h3>Черновик профиля</h3>
            <div>
              <pre className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                {JSON.stringify(draft, null, 2)}
              </pre>
              <p className="secondary">
                Происхождение зафиксировано; хранение — обычным каналом на Ш5 «Входы
                моделирования», тем же фильтром, что рукописный ввод.
              </p>
            </div>
          </div>
        )}
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Правовой режим — до загрузчика</h3>
          <div className="secondary">
            Часть источников допускает извлечение отдельных записей и запрещает выгрузку
            каталога целиком (sui generis, Директива 96/9/EC). Поэтому массового импорта
            здесь нет как пути, а не как кнопки.
          </div>
        </div>
      </aside>
    </div>
  )
}
