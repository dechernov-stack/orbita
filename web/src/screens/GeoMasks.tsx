// Области приоритета карты спроса (Д2, ответ владельца): документ называет
// область, система создаёт ЗАГОТОВКУ — имя и происхождение есть, границы нет.
// Пустота честная и видимая: «геометрия не задана» — разрыв готовности карты
// спроса, и закрывает его инженер, задав границу здесь.
import { useEffect, useState } from 'react'
import { edit, type StoredSummary } from '../api/edit'
import { useSession } from '../ui/session'

interface MaskDoc {
  name: string
  priority?: boolean
  note?: string
  geometry?: {
    kind: 'bbox' | 'polygon' | 'cap'
    lat_min_deg?: number; lat_max_deg?: number
    lon_min_deg?: number; lon_max_deg?: number
    center_lat_deg?: number; center_lon_deg?: number; radius_km?: number
  }
  provenance?: { import?: { dataset?: string; item_ref?: string } }
}

const EMPTY_BOX = { lat_min_deg: '', lat_max_deg: '', lon_min_deg: '', lon_max_deg: '' }

export function GeoMasks() {
  const { author } = useSession()
  const [rows, setRows] = useState<StoredSummary[]>([])
  const [docs, setDocs] = useState<Record<string, { doc: MaskDoc; version: string }>>({})
  const [box, setBox] = useState<Record<string, typeof EMPTY_BOX>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    edit.list('geo_mask')
      .then((list) => {
        setRows(list)
        list.forEach((r) => {
          edit.object(r.id).then((o) => setDocs((prev) => ({
            ...prev, [r.id]: { doc: o.doc as unknown as MaskDoc, version: o.version },
          })))
        })
      })
      .catch((e) => setError(String(e)))
  }

  useEffect(load, [])

  const setField = (id: string, field: keyof typeof EMPTY_BOX, value: string) =>
    setBox((prev) => ({ ...prev, [id]: { ...(prev[id] ?? EMPTY_BOX), [field]: value } }))

  const saveBox = (id: string) => {
    const b = box[id]
    const entry = docs[id]
    if (!b || !entry || !author) return
    const numbers = {
      lat_min_deg: Number(b.lat_min_deg), lat_max_deg: Number(b.lat_max_deg),
      lon_min_deg: Number(b.lon_min_deg), lon_max_deg: Number(b.lon_max_deg),
    }
    setBusy(id)
    setError(null)
    edit.update(id, { geometry: { kind: 'bbox', ...numbers } }, entry.version, author)
      .then(() => { setBox((prev) => ({ ...prev, [id]: EMPTY_BOX })); load() })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(null))
  }

  if (rows.length === 0) return null

  const maskless = rows.filter((r) => !docs[r.id]?.doc.geometry).length

  return (
    <div className="card">
      <h3>Области приоритета · {rows.length}</h3>
      <div>
        <p className="secondary" style={{ margin: '0 0 6px' }}>
          Область названа источником; граница — работа инженера. Пока её нет,
          в готовности карты спроса стоит разрыв: {maskless} из {rows.length} без границы.
        </p>
        {error && <div className="warn" style={{ padding: 6 }}>{error}</div>}
        {rows.map((r) => {
          const entry = docs[r.id]
          const doc = entry?.doc
          const g = doc?.geometry
          const b = box[r.id] ?? EMPTY_BOX
          return (
            <div key={r.id} style={{ padding: '6px 0', borderBottom: '1px solid var(--line, #2223)' }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'baseline', flexWrap: 'wrap' }}>
                <span className="mono secondary">{r.id}</span>
                <b>{doc?.name ?? r.title}</b>
                {doc?.priority && (
                  <span className="chip" title="источник назвал область приоритетной">приоритет</span>
                )}
                {doc?.provenance?.import?.dataset && (
                  <span className="secondary" title={`блоки: ${doc.provenance.import.item_ref ?? '—'}`}>
                    из {doc.provenance.import.dataset}
                  </span>
                )}
              </div>
              {g ? (
                <div className="secondary mono">
                  {g.kind === 'bbox'
                    ? `широта ${g.lat_min_deg}…${g.lat_max_deg}°, долгота ${g.lon_min_deg}…${g.lon_max_deg}°`
                    : g.kind === 'cap'
                      ? `круг ${g.radius_km} км вокруг ${g.center_lat_deg}°, ${g.center_lon_deg}°`
                      : 'контур задан'}
                </div>
              ) : (
                <div style={{ display: 'flex', gap: 4, alignItems: 'center', flexWrap: 'wrap', marginTop: 4 }}>
                  <span className="amber" title="разрыв готовности карты спроса: границу задаёт инженер">
                    геометрия не задана
                  </span>
                  <span className="secondary" title="границы прямоугольника в градусах">широта</span>
                  {(['lat_min_deg', 'lat_max_deg'] as const).map((f) => (
                    <input key={f} type="number" style={{ width: 62 }} value={b[f]}
                      aria-label={`${f} области ${r.id}`}
                      placeholder={f.endsWith('min_deg') ? 'от' : 'до'}
                      onChange={(e) => setField(r.id, f, e.target.value)} />
                  ))}
                  <span className="secondary">долгота</span>
                  {(['lon_min_deg', 'lon_max_deg'] as const).map((f) => (
                    <input key={f} type="number" style={{ width: 62 }} value={b[f]}
                      aria-label={`${f} области ${r.id}`}
                      placeholder={f.endsWith('min_deg') ? 'от' : 'до'}
                      onChange={(e) => setField(r.id, f, e.target.value)} />
                  ))}
                  <button className="rr-assign" disabled={busy === r.id || !author ||
                    Object.values(b).some((v) => v === '')}
                    title={author ? 'задать границу прямоугольником' : 'представьтесь в шапке'}
                    onClick={() => saveBox(r.id)}>
                    {busy === r.id ? 'сохраняю…' : 'задать границу'}
                  </button>
                </div>
              )}
              {doc?.note && <div className="secondary">{doc.note}</div>}
            </div>
          )
        })}
      </div>
    </div>
  )
}
