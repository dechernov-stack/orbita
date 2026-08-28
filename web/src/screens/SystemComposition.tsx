// Экран «Состав системы» (шаг 16 §3.3): компоненты и интерфейсы хранятся
// с шага 1, но выбрать их было негде — существовал один экран спецификации
// с зашитым идентификатором. Образец — экран 3: список слева, карточка справа.
//
// Из строки спецификации — переход к требованию, из карточки требования —
// обратно к элементу, на который оно распределено.
import { useEffect, useState } from 'react'
import { SortTh, useSort } from '../ui/sort'
import { api } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import type { RequirementCard } from '../api/types'
import { ComponentSpec } from './ComponentSpec'

export function SystemComposition() {
  const [items, setItems] = useState<StoredSummary[]>([])
  // П-Б: сортировка заголовком — общий компонент, клиентская
  const { sorted: sortedItems, sort, toggle } = useSort(items, {
    id: (r) => r.id,
    title: (r) => r.title ?? '',
  })
  const [selected, setSelected] = useState<string | null>(null)
  const [reqId, setReqId] = useState<string | null>(null)
  const [card, setCard] = useState<RequirementCard | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([edit.list('component'), edit.list('interface')])
      .then(([components, interfaces]) => {
        const all = [...components, ...interfaces]
        setItems(all)
        if (all.length > 0) setSelected((cur) => cur ?? all[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!reqId) {
      setCard(null)
      return
    }
    api.requirementCard(reqId).then(setCard).catch((e) => setError(String(e)))
  }, [reqId])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (items.length === 0)
    return (
      <div className="empty">
        Компонентов и интерфейсов в модели нет: заведите их на Ш4 «Состав и интерфейсы».
      </div>
    )

  return (
    <div
      style={{
        gridArea: 'main',
        display: 'grid',
        gridTemplateColumns: '220px minmax(0, 1fr)',
        minHeight: 0,
        minWidth: 0,
      }}
    >
      <div className="pane" style={{ borderRight: '1px solid var(--border)' }}>
        <h3 style={{ fontSize: 13, margin: '10px 8px 4px' }}>Состав системы</h3>
        <table>
          <thead>
            <tr>
              <SortTh label="ID" sortKey="id" sort={sort} onToggle={toggle} width={80} />
              <SortTh label="Название" sortKey="title" sort={sort} onToggle={toggle} />
            </tr>
          </thead>
          <tbody>
            {sortedItems.map((item) => (
              <tr
                key={item.id}
                onClick={() => {
                  setSelected(item.id)
                  setReqId(null)
                }}
                style={{
                  cursor: 'pointer',
                  background: item.id === selected ? 'rgba(11,95,255,0.08)' : undefined,
                }}
              >
                <td className="mono" style={{ width: 80 }}>{item.id}</td>
                <td className="truncate">{item.title ?? item.type}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ minWidth: 0, minHeight: 0, display: 'grid' }}>
        {reqId && card ? (
          <div className="pane" style={{ padding: 16 }}>
            <button type="button" className="tab" onClick={() => setReqId(null)}>
              ← к элементу {selected}
            </button>
            <h2 style={{ fontSize: 15 }}>
              <span className="id">{card.row.id}</span> {card.row.statement}
            </h2>
            <div className="field">
              <label>Статус</label>
              {card.row.status}
            </div>
            {card.successCriterion && (
              <div className="field">
                <label>Критерий успеха</label>
                <span className="mono">{card.successCriterion}</span>
              </div>
            )}
            <div className="field">
              <label>Источники</label>
              {card.sources.map((s) => (
                <span key={s} className="chip">{s}</span>
              ))}
            </div>
            <div className="field">
              <label>Распределено на</label>
              {/* обратно к элементу: распределение и есть обратная нить */}
              {card.allocatedTo.map((a) => (
                <button
                  key={a}
                  type="button"
                  className="tab"
                  onClick={() => {
                    setSelected(a)
                    setReqId(null)
                  }}
                >
                  {a}
                </button>
              ))}
            </div>
          </div>
        ) : (
          selected && <ComponentSpec componentId={selected} onSelectRequirement={setReqId} />
        )}
      </div>
    </div>
  )
}
