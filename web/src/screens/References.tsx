// Справочники (Ф-03): глоссарий и словарь единиц — ПРОСМОТРОМ, тем самым
// источником, каким пользуются подсказки и границы пачек. Закрывает
// «глоссарий недоступен пользователю»: правится он данными полки LIB
// («Загрузить пачкой» в область LIB), не этим экраном.
import { useEffect, useState } from 'react'
import { edit } from '../api/edit'
import { useSession } from '../ui/session'
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
  // Ф-15: справочники ВЕДУТСЯ, а не только читаются. Правка не бесплатна —
  // до сохранения показываем объём последствий, который считает сервер.
  const [impact, setImpact] = useState<string | null>(null)
  const [draftUnit, setDraftUnit] = useState({
    dimension: '', canon: '', unit: '', factor: '1', spellings: '',
  })
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<string | null>(null)

  useEffect(() => {
    api.glossary().then(setGlossary).catch((e) => setError(String(e)))
    api.unitRegistry().then(setUnits).catch((e) => setError(String(e)))
    api.propertyForms().then(setForms).catch(() => setForms([]))
  }, [])

  const kinds = (glossary ?? []).filter((e) => e.sd_kind)
  const terms = (glossary ?? []).filter((e) => !e.sd_kind)

  const { author } = useSession()

  /**
   * Ф-15: внесение единицы В СПРАВОЧНИК, а не в обход него. Правка идёт тем
   * же путём, что ручная правка любого объекта: чтение свежей версии, правка
   * документа, запись с основанием. Право проверяет сервер (ведущий СИ и
   * руководитель) — отказ приходит с именем права, а не молчанием.
   */
  const addUnit = async () => {
    if (!author) return
    setBusy(true)
    setNote(null)
    try {
      const impactView = await api.registryImpact('unit_registry').catch(() => null)
      if (impactView) setImpact(impactView.warning)
      const list = await edit.list('unit_registry')
      if (list.length === 0) {
        setNote('справочника единиц на полке нет — сначала залейте его пачкой')
        return
      }
      const fresh = await edit.object(list[0].id)
      const doc = { ...(fresh.doc as Record<string, unknown>) }
      const dims = [...((doc.dimensions as Array<Record<string, unknown>>) ?? [])]
      const spellings = draftUnit.spellings.split(',').map((x) => x.trim()).filter(Boolean)
      if (draftUnit.canon.trim()) {
        // новая размерность: введённая единица становится её каноном
        dims.push({
          name: draftUnit.canon.trim(),
          canon: draftUnit.unit.trim(),
          conversion: 'linear',
          ...(spellings.length > 0 ? { spellings } : {}),
        })
      } else {
        const target = dims.find((d) => d.canon === draftUnit.dimension)
        if (!target) {
          setNote('размерность не найдена — выберите её в списке')
          return
        }
        const inputs = [...((target.inputs as Array<Record<string, unknown>>) ?? [])]
        inputs.push({
          unit: draftUnit.unit.trim(),
          factor: Number(draftUnit.factor) || 1,
          ...(spellings.length > 0 ? { spellings } : {}),
        })
        target.inputs = inputs
      }
      doc.dimensions = dims
      await edit.changeWithRef(list[0].id, doc, `Ф-15: единица «${draftUnit.unit.trim()}» внесена с экрана справочников`)
      setDraftUnit({ dimension: '', canon: '', unit: '', factor: '1', spellings: '' })
      setNote(`единица «${draftUnit.unit.trim()}» внесена — граница пакетов принимает её сразу`)
      api.unitRegistry().then(setUnits).catch(() => undefined)
    } catch (e) {
      setNote(String(e))
    } finally {
      setBusy(false)
    }
  }

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
            {/* Ф-15: справочник ведётся с экрана. Решение справочника прямо
                обещало «нужна новая единица — открываем и вставляем»; до сих
                пор внести её можно было только пачкой. */}
            <div className="rr-expand" style={{ display: 'block', padding: '8px 10px', marginBottom: 8 }}>
              <div className="secondary" style={{ marginBottom: 4 }}>
                Добавить единицу в существующую размерность
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                <select value={draftUnit.dimension}
                  onChange={(e) => setDraftUnit({ ...draftUnit, dimension: e.target.value })}
                  title="размерность, в которую ложится единица">
                  <option value="">— размерность —</option>
                  {units.map((d) => <option key={d.canon} value={d.canon}>{d.name} · {d.canon}</option>)}
                </select>
                <input placeholder="новая размерность (если её нет)" value={draftUnit.canon}
                  style={{ width: 190 }}
                  title="имя новой размерности; единица ниже станет её каноном"
                  onChange={(e) => setDraftUnit({ ...draftUnit, canon: e.target.value })} />
                <input placeholder="единица, например Вт/кг" value={draftUnit.unit}
                  onChange={(e) => setDraftUnit({ ...draftUnit, unit: e.target.value })} />
                <input placeholder="коэффициент к канону" value={draftUnit.factor} style={{ width: 140 }}
                  title="во сколько раз единица больше канона; для новой размерности — 1"
                  onChange={(e) => setDraftUnit({ ...draftUnit, factor: e.target.value })} />
                <input placeholder="написания через запятую" value={draftUnit.spellings}
                  style={{ flex: 1, minWidth: 200 }}
                  onChange={(e) => setDraftUnit({ ...draftUnit, spellings: e.target.value })} />
                <button className="rr-assign"
                  disabled={busy || !draftUnit.unit.trim() || !author
                    || (!draftUnit.dimension && !draftUnit.canon.trim())}
                  title={!author
                    ? 'представьтесь в шапке: правка справочника пишется на автора'
                    : !draftUnit.unit.trim()
                      ? 'назовите единицу'
                      : (!draftUnit.dimension && !draftUnit.canon.trim())
                        ? 'выберите размерность либо заведите новую с её каноном'
                        : 'внести в справочник — граница пакетов примет единицу сразу'}
                  onClick={() => void addUnit()}>
                  {busy ? 'Вношу…' : 'Внести'}
                </button>
              </div>
              {impact && <div className="warn" style={{ marginTop: 6, padding: 6 }}>{impact}</div>}
              {note && <div className="secondary" style={{ marginTop: 4 }}>{note}</div>}
            </div>
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
