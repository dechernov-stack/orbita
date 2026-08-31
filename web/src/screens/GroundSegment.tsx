// Наземный сегмент (шаг 12.1, Концепция 5.4; шаг 16 §2.4): рекомендательное
// размещение станций. Ручные станции берутся из ХРАНИМОГО набора и не
// переписываются: подбор строится поверх них, предложенное помечено
// происхождением. Кандидатные площадки называет инженер — система выбирает
// из названного, а не изобретает географию.
import { useState } from 'react'
import { api, ApiError } from '../api/client'
import type { GroundSuggestView } from '../api/types'
import { SortTh, useSort } from '../ui/sort'

interface Candidate {
  id: string
  name: string
  lat_deg: number
  lon_deg: number
}

export function GroundSegment() {
  const [candidates, setCandidates] = useState<Candidate[]>([])
  const [draft, setDraft] = useState({ id: '', name: '', lat: '', lon: '' })
  const [k, setK] = useState(1)
  const [result, setResult] = useState<GroundSuggestView | null>(null)
  const [error, setError] = useState<string | null>(null)

  const add = () => {
    const id = draft.id.trim()
    if (!id || draft.lat === '' || draft.lon === '') return
    setCandidates((prev) => [
      ...prev.filter((c) => c.id !== id),
      { id, name: draft.name.trim(), lat_deg: Number(draft.lat), lon_deg: Number(draft.lon) },
    ])
    setDraft({ id: '', name: '', lat: '', lon: '' })
  }

  const suggest = () => {
    setError(null)
    setResult(null)
    api
      .groundSuggest({ candidates, k })
      .then(setResult)
      .catch((e) => setError(e instanceof ApiError ? e.message.slice(0, 300) : String(e)))
  }

  // Сортировка заголовком (§2.4): площадки — по имени и координатам
  const { sorted, sort, toggle } = useSort(candidates, {
    id: (c) => c.id,
    name: (c) => c.name,
    lat: (c) => c.lat_deg,
    lon: (c) => c.lon_deg,
  })

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Подбор станций</h2>
        <p className="secondary" style={{ maxWidth: 560 }}>
          Хранимые станции не переписываются: подбор считает вклад каждого кандидата поверх
          них и берёт жадно лучший. Предложенное — рекомендация с происхождением, в модель
          станцию заводит инженер на Ш5 «Входы моделирования».
        </p>

        <div className="field">
          <input placeholder="id" value={draft.id} style={{ width: 90 }}
            onChange={(e) => setDraft({ ...draft, id: e.target.value })} />
          <input placeholder="название" value={draft.name} style={{ width: 140 }}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          <input placeholder="широта°" value={draft.lat} style={{ width: 70 }}
            onChange={(e) => setDraft({ ...draft, lat: e.target.value })} />
          <input placeholder="долгота°" value={draft.lon} style={{ width: 70 }}
            onChange={(e) => setDraft({ ...draft, lon: e.target.value })} />
          <button type="button" className="tab" onClick={add}>
            + Кандидат
          </button>
        </div>

        {candidates.length > 0 && (
          <table>
            <thead>
              <tr>
                <SortTh label="id" sortKey="id" sort={sort} onToggle={toggle} width={90} />
                <SortTh label="Название" sortKey="name" sort={sort} onToggle={toggle} />
                <SortTh label="Широта" sortKey="lat" sort={sort} onToggle={toggle} width={90} />
                <SortTh label="Долгота" sortKey="lon" sort={sort} onToggle={toggle} width={90} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((c) => (
                <tr key={c.id} onClick={() => setCandidates((prev) => prev.filter((x) => x.id !== c.id))}
                  title="убрать из кандидатов" style={{ cursor: 'pointer' }}>
                  <td className="mono">{c.id}</td>
                  <td>{c.name}</td>
                  <td className="num">{c.lat_deg}</td>
                  <td className="num">{c.lon_deg}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="field" style={{ marginTop: 8 }}>
          <label>Сколько добавить</label>
          <input type="number" min={1} max={10} value={k} style={{ width: 60 }}
            onChange={(e) => setK(Number(e.target.value))} />
          <button title="кандидатов нет: сначала задайте площадки наземного сегмента" type="button" className="tab tab--primary" disabled={candidates.length === 0} onClick={suggest}>
            Подобрать
          </button>
        </div>

        {error && <div className="warn" style={{ padding: 8 }}>{error}</div>}

        {result && (
          <>
            <h3 style={{ fontSize: 13 }}>Предложение</h3>
            <table>
              <thead>
                <tr>
                  <th style={{ width: 90 }}>id</th>
                  <th>Название</th>
                  <th style={{ width: 110 }}>Происхождение</th>
                  <th style={{ width: 110, textAlign: 'right' }}>Вклад</th>
                </tr>
              </thead>
              <tbody>
                {result.suggested.map((s) => (
                  <tr key={s.id}>
                    <td className="mono">{s.id}</td>
                    <td>{s.name}</td>
                    <td className="secondary">{s.placement}</td>
                    <td className="num">{(s.gain * 100).toFixed(1)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="secondary">
              Покрытие сброса: {(result.coverage_before * 100).toFixed(1)}% →{' '}
              <b>{(result.coverage_after * 100).toFixed(1)}%</b> (считает сервер по хранимым
              станциям и кандидатам).
            </p>
          </>
        )}
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Почему кандидатов называет инженер</h3>
          <div className="secondary">
            Станция — это площадка с энергетикой, каналом и правовым режимом, а не точка на
            карте. Система умеет сравнить названные площадки по вкладу в покрытие, но не
            умеет знать, где площадку можно построить.
          </div>
        </div>
      </aside>
    </div>
  )
}
