// Библиотека v2: полки по видам — что лежит на стенде и в каком количестве.
// Раздел эксперт-режима: читает `/v2/shelves?kind=…` по каждому виду полки и
// показывает записи кодом и заголовком; окно взятия живёт в сцене 7,
// справочники (единицы, глоссарий) — на своих полках. Пустая полка названа
// пустой с подсказкой, чем её наполнить — раздел не притворяется заглушкой.
import { useEffect, useState } from 'react'
import { api } from './api'

type Полка = { kind: string; title: string; hint: string }

const ПОЛКИ: Полка[] = [
  { kind: 'mission_class', title: 'Классы миссии', hint: 'рамки Р, рекомендованные полки, обязательные модели — ложатся в проект при заведении' },
  { kind: 'constraint', title: 'Рамки Р', hint: 'типовые ограничения класса миссии' },
  { kind: 'phase_template', title: 'Шаблоны фаз', hint: 'сцены, точки, экспертизы Pre-A и Phase A' },
  { kind: 'document_template', title: 'Шаблоны документов', hint: 'MCReport · ConOps · FAD · FA · SEMP · OpsCon · ICD · SMA · Project Plan' },
  { kind: 'process_catalog', title: 'Каталог процессов СИ', hint: '17 процессов NPR 7123.1 → механизм → место' },
  { kind: 'lifecycle_process_reference', title: 'Методология ЖЦ', hint: 'стадии и мероприятия Романова' },
  { kind: 'pbs_template', title: 'Каркас PBS', hint: 'узлы состава класса миссии' },
  { kind: 'interface_template', title: 'Стыки', hint: 'типовые интерфейсы с анкетами' },
  { kind: 'architecture_template', title: 'Архитектура Arcadia', hint: 'способности, функции, цепочки, логические компоненты' },
  { kind: 'wbs_template', title: 'Каркас WBS', hint: 'пакеты работ с парами к узлам' },
  { kind: 'component_template_shelf', title: 'Шаблоны компонентов', hint: 'лестница ступеней и анкеты узлов' },
  { kind: 'model_template', title: 'Модели системы', hint: 'М1–М14: входы, выходы, ответ с датой' },
  { kind: 'normative_document', title: 'Нормативы', hint: 'акты с пунктами и порогами' },
  { kind: 'glossary_cross_terms', title: 'Глоссарий', hint: 'термины и их разночтения между стандартами' },
]

type Запись = { code: string; kind: string; doc: Record<string, unknown> }

function заголовок(з: Запись): string {
  const d = з.doc
  for (const k of ['title', 'name', 'statement', 'designation']) {
    const v = d[k]
    if (typeof v === 'string' && v.trim()) return v
  }
  return з.code
}

function размер(з: Запись): string {
  const d = з.doc
  for (const k of ['items', 'scenes', 'sections', 'processes', 'nodes', 'interfaces', 'packages', 'models', 'clauses', 'entries', 'terms', 'blocks', 'default_constraints']) {
    const v = d[k]
    if (Array.isArray(v)) return `${v.length} ${k}`
  }
  return ''
}

export function Library() {
  const [полки, setПолки] = useState<Record<string, Запись[] | null>>({})
  const [раскрыта, setРаскрыта] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    let живо = true
    Promise.all(ПОЛКИ.map((п) => api.shelves(п.kind).then((r) => [п.kind, r.items] as const).catch(() => [п.kind, null] as const)))
      .then((пары) => { if (живо) setПолки(Object.fromEntries(пары)) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [])

  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  const загружено = Object.keys(полки).length > 0
  const всего = Object.values(полки).reduce((n, з) => n + (з?.length ?? 0), 0)

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Библиотека
        <span className="v2-cnt">{загружено ? `полок ${ПОЛКИ.length} · записей ${всего}` : 'читаю полки…'}</span>
      </h3>
      <div className="v2-hint">Полки стенда: что лежит и сколько. Взятие с полки — в сцене 7 окном взятия; наполнение — поставкой (tools/v2/load_shelves.py).</div>
      <table className="v2-table">
        <thead>
          <tr><th>полка</th><th>записей</th><th>что это</th></tr>
        </thead>
        <tbody>
          {ПОЛКИ.map((п) => {
            const записи = полки[п.kind]
            const n = записи?.length ?? 0
            const открыта = раскрыта === п.kind
            return (
              <>
                <tr key={п.kind} className={n === 0 ? 'v2-row--dim' : undefined}>
                  <td>
                    <button type="button" className="v2-link" disabled={n === 0}
                      title={n === 0 ? 'полка пуста — наполнить поставкой: tools/v2/load_shelves.py' : (открыта ? 'свернуть записи' : 'показать записи полки')}
                      onClick={() => setРаскрыта(открыта ? null : п.kind)}>
                      {п.title}
                    </button>
                  </td>
                  <td>{записи === undefined ? '…' : записи === null ? 'нет вида' : n === 0 ? 'пусто' : n}</td>
                  <td className="v2-muted">{п.hint}</td>
                </tr>
                {открыта && записи && записи.map((з) => (
                  <tr key={`${п.kind}:${з.code}`} className="v2-row--sub">
                    <td className="v2-muted">{з.code}</td>
                    <td>{размер(з)}</td>
                    <td>{заголовок(з)}</td>
                  </tr>
                ))}
              </>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
