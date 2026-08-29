// Справочники (Ф-03): глоссарий и словарь единиц — ПРОСМОТРОМ, тем самым
// источником, каким пользуются подсказки и границы пачек. Закрывает
// «глоссарий недоступен пользователю»: правится он данными полки LIB
// («Загрузить пачкой» в область LIB), не этим экраном.
import { useEffect, useState } from 'react'
import { api } from '../api/client'

type GlossaryEntry = Awaited<ReturnType<typeof api.glossary>>[number]
type UnitDimension = Awaited<ReturnType<typeof api.unitRegistry>>[number]
type PropertyForm = Awaited<ReturnType<typeof api.propertyForms>>[number]

const CONVERSION_LABEL: Record<string, string> = {
  linear: 'линейная',
  shift: 'со сдвигом',
  log: 'логарифмическая',
  rate: 'по курсу на дату',
  none: 'счётная',
}

export function References() {
  const [tab, setTab] = useState<'glossary' | 'units' | 'forms'>('glossary')
  const [glossary, setGlossary] = useState<GlossaryEntry[] | null>(null)
  const [units, setUnits] = useState<UnitDimension[] | null>(null)
  const [forms, setForms] = useState<PropertyForm[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.glossary().then(setGlossary).catch((e) => setError(String(e)))
    api.unitRegistry().then(setUnits).catch((e) => setError(String(e)))
    api.propertyForms().then(setForms).catch(() => setForms([]))
  }, [])

  const kinds = (glossary ?? []).filter((e) => e.sd_kind)
  const terms = (glossary ?? []).filter((e) => !e.sd_kind)

  return (
    <div style={{ padding: '8px 0', overflowY: 'auto' }}>
      <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
        <button className="tab" aria-selected={tab === 'glossary'} onClick={() => setTab('glossary')}
          title="термины системы и типы документов с брифами — источник подсказок">
          Глоссарий{glossary ? ` · ${glossary.length}` : ''}
        </button>
        <button className="tab" aria-selected={tab === 'units'} onClick={() => setTab('units')}
          title="размерности и единицы — этим же словарём канонизируются пачки и загрузки">
          Единицы{units ? ` · ${units.length}` : ''}
        </button>
        <button className="tab" aria-selected={tab === 'forms'} onClick={() => setTab('forms')}
          title="анкеты характеристик носителей — ими библиотека запрашивает данные">
          Анкеты{forms ? ` · ${forms.length}` : ''}
        </button>
      </div>
      {error && <div className="warn" style={{ padding: 8 }}>Ошибка: {error}</div>}
      {tab === 'forms' && forms && (
        forms.length === 0 ? (
          <div className="empty">
            Анкет характеристик нет — вносятся «Загрузить пачкой» в область LIB
            (объект property_form): библиотека тогда сама запросит данные.
          </div>
        ) : (
          <>
            {forms.map((f) => (
              <div className="card" key={f.id}>
                <h3>{f.name} · <span className="mono secondary">{f.id}</span></h3>
                <div style={{ overflowX: 'auto' }}>
                  {f.note && <p className="secondary" style={{ marginTop: 0 }}>{f.note}</p>}
                  <table className="rr-table">
                    <thead>
                      <tr>
                        <th>Характеристика</th>
                        <th title="единица справочника — в ней система ждёт значение">Единица</th>
                        <th title="обязательное поле становится разрывом готовности, пока не задано">Обязательно</th>
                        <th>Подсказка</th>
                      </tr>
                    </thead>
                    <tbody>
                      {f.fields.map((fl) => (
                        <tr key={fl.key}>
                          <td>{fl.name} <span className="mono secondary">{fl.key}</span></td>
                          <td className="mono">{fl.unit ?? '—'}</td>
                          <td>{fl.required ? 'да' : '—'}</td>
                          <td className="secondary">{fl.hint ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </>
        )
      )}
      {tab === 'glossary' && glossary && (
        glossary.length === 0 ? (
          <div className="empty">
            Глоссарий пуст — запись вносится «Загрузить пачкой» в область LIB
            (объект glossary, полка справочников).
          </div>
        ) : (
          <>
            <div className="card">
              <h3>Типы документов</h3>
              <div>
                {kinds.map((e) => (
                  <div key={e.term} style={{ padding: '5px 0', borderBottom: '1px solid var(--line, #2223)' }}>
                    <b>{e.term}</b>{' '}
                    <span className="secondary mono">{e.sd_kind}</span>
                    <div>{e.brief}</div>
                    {e.extracts && <div className="secondary">Извлечения: {e.extracts}</div>}
                    {e.card_hint && <div className="secondary">В карточке важно: {e.card_hint}</div>}
                  </div>
                ))}
              </div>
            </div>
            <div className="card">
              <h3>Термины</h3>
              <div>
                {terms.map((e) => (
                  <div key={e.term} style={{ padding: '5px 0', borderBottom: '1px solid var(--line, #2223)' }}>
                    <b>{e.term}</b>
                    <div>{e.brief}</div>
                    {e.not_to_confuse && <div className="secondary">Не путать с: {e.not_to_confuse}</div>}
                  </div>
                ))}
              </div>
            </div>
          </>
        )
      )}
      {tab === 'units' && units && (
        units.length === 0 ? (
          <div className="empty">
            Справочник единиц пуст — вносится «Загрузить пачкой» в область LIB
            (объект unit_registry); до сида границы пачек молчат.
          </div>
        ) : (
          <div className="card">
            <h3>Размерности и единицы</h3>
            <div style={{ overflowX: 'auto' }}>
              <table className="rr-table">
                <thead>
                  <tr>
                    <th>Размерность</th>
                    <th title="единица хранения: в неё конвертируется всё входящее">Канон</th>
                    <th>Конверсия</th>
                    <th title="единицы, принимаемые на входе; пачка с иной единицей получает отказ с адресом этой полки">
                      Принимаемые единицы
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {units.map((d) => (
                    <tr key={d.name}>
                      <td>{d.name}</td>
                      <td className="mono">{d.canon}</td>
                      <td>{CONVERSION_LABEL[d.conversion] ?? d.conversion}</td>
                      <td className="mono">
                        {(d.inputs ?? []).map((u) => u.unit).join(', ') || '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )
      )}
    </div>
  )
}
