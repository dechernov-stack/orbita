// Подозрительные связи и базирование требований.
import { useState } from 'react'
import { api, type BaselineRow, type Blocker, type SuspectRow } from '../api'
import { сводкаПомех } from './common'

export function Подозрения({ project, строки, onConfirmed }: {
  project: string; строки: SuspectRow[]; onConfirmed: () => void
}) {
  if (строки.length === 0) return null
  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Подозрительные связи</span>
        <span className="v2-card__count">{строки.length}</span>
      </div>
      <div className="v2-empty__why">
        Конец связи изменился после снимка. Подозрение снимает человек — это разрыв к следующей точке.
      </div>
      {строки.map((с) => (
        <div key={с.link} className="v2-note">
          <span className="v2-note__rule">{с.type}</span>
          <span>{с.from} → {с.to}: {с.why}</span>
          <button type="button" className="v2-chip"
            title="подтвердить, что связь по-прежнему верна"
            onClick={() => api.confirmSuspect(project, с.link, 'стенд').then(onConfirmed).catch(() => undefined)}>
            подтвердить
          </button>
        </div>
      ))}
    </div>
  )
}

export function Базирование({ project, снимки, помехи, onDone }: {
  project: string; снимки: BaselineRow[]; помехи: Blocker[]; onDone: () => void
}) {
  const [имя, setИмя] = useState('SRR')
  const [класс, setКласс] = useState('functional')
  const [отказ, setОтказ] = useState<Blocker[] | null>(null)
  const [занято, setЗанято] = useState(false)

  const базировать = () => {
    setЗанято(true); setОтказ(null)
    api.baseline(project, { name: имя, kind: класс, gate: имя, author: 'стенд' })
      .then(() => onDone())
      .catch((e) => setОтказ([{ code: '—', rule: '—', what: String(e.message ?? e) }]))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Базирование</span>
        <span className="v2-card__count">{снимки.length}</span>
      </div>

      {снимки.map((с) => (
        <div key={с.name} className="v2-note">
          <span className="v2-note__rule">{с.name}</span>
          <span>
            {с.kind} · точка {с.gate} · {с.items.length} объектов
            {с.items.length - с.firm > 0 && ` · условных ${с.items.length - с.firm}`}
          </span>
          {с.items.filter((э) => э.firmness === 'conditional').map((э) => (
            <span key={э.ref} className="v2-empty__why">{э.code}: {э.why}</span>
          ))}
        </div>
      ))}

      {/*
        «Как базировать — неясно» (владелец 19.09): список из сорока одной
        помехи по одной штуке не говорит, ЧТО делать. Помехи сведены по сути:
        сколько требований ждут одного и того же и где это правится.
      */}
      {помехи.length > 0 && (
        <div className="v2-locked">
          Базировать пока нельзя — {помехи.length} помех у {new Set(помехи.map((п) => п.code)).size} записей.
          <ul>
            {сводкаПомех(помехи).map((с) => (
              <li key={с.суть}>
                {с.суть} — <b>{с.сколько}</b>: {с.где}
                <span className="v2-dim"> ({с.коды})</span>
              </li>
            ))}
          </ul>
          <span className="v2-empty__why">
            Помехи закрываются в карточке требования: откройте строку реестра и заполните
            названные поля — они подписаны стадией («Носитель · baseline»).
          </span>
        </div>
      )}

      <div className="v2-form__actions">
        <label>Имя снимка
          <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="SRR" />
        </label>
        <label>Класс
          <select value={класс} onChange={(e) => setКласс(e.target.value)}>
            <option value="functional">функциональная</option>
            <option value="allocated">распределённая</option>
            <option value="product">изделия</option>
          </select>
        </label>
        <button type="button" className="v2-primary" disabled={занято || помехи.length > 0}
          title={помехи.length > 0
            ? `сначала закройте помехи: ${помехи[0].what}`
            : 'снять снимок набора: версии зафиксируются, незрелые технологии войдут условными'}
          onClick={базировать}>
          {занято ? 'Снимаю…' : 'Базировать'}
        </button>
      </div>

      {отказ && <div className="v2-locked">{отказ.map((п) => п.what).join('; ')}</div>}
    </div>
  )
}
