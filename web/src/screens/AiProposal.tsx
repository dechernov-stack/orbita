// Экран 8 — предложение ИИ: пакет, ответ модели, фильтр, diff, акцепт.
//
// Генерация происходит ВНЕ системы (канал 1, TZ-AI-001): пакет копируют во
// внешний интерфейс LLM, ответ вставляют обратно. Разбор и структурный фильтр
// выполняет сервер — до инженера доходит только состоятельное.
//
// Массового акцепта здесь нет намеренно: применяются выбранные поля выбранного
// предложения. «Улучшить тысячу требований» одной кнопкой — обход управления
// конфигурацией (STEP-5, ловушка 2).
import { useState } from 'react'
import { api } from '../api/client'
import type { AnswerReport, PromptPackage, ScreenedProposal } from '../api/types'

const DEFAULT_TASK =
  'Проверь формулировки требований и предложи исправления. Верни объекты по схеме ответа.'

export function AiProposal() {
  const [pkg, setPkg] = useState<PromptPackage | null>(null)
  const [raw, setRaw] = useState('')
  const [report, setReport] = useState<AnswerReport | null>(null)
  const [selected, setSelected] = useState<Record<string, Set<string>>>({})
  const [accepted, setAccepted] = useState<Record<string, string[]>>({})
  const [error, setError] = useState<string | null>(null)

  const kind = 'requirement_quality'
  const context = { scope: 'демо-проект «Орбита-IoT»' }

  const build = () => {
    setError(null)
    api.buildPackage(kind, context, DEFAULT_TASK).then(setPkg).catch((e) => setError(String(e)))
  }

  const submit = () => {
    setError(null)
    api.submitAnswer(kind, context, DEFAULT_TASK, raw).then(setReport).catch((e) => setError(String(e)))
  }

  const toggleField = (proposalId: string, field: string) => {
    setSelected((prev) => {
      const fields = new Set(prev[proposalId] ?? [])
      if (fields.has(field)) fields.delete(field)
      else fields.add(field)
      return { ...prev, [proposalId]: fields }
    })
  }

  /** Акцепт одного предложения по выбранным полям — по одному, осознанно. */
  const accept = (proposal: ScreenedProposal) => {
    const targetId = String(proposal.item.id)
    const fields = [...(selected[targetId] ?? new Set<string>())]
    if (fields.length === 0 || !report) return
    setError(null)
    api
      .acceptProposal({
        target_id: targetId,
        proposal: proposal.item,
        selected: fields,
        package_id: report.package_id,
        llm: 'внешний интерфейс',
        by: 'инженер',
      })
      .then(() => setAccepted((prev) => ({ ...prev, [targetId]: fields })))
      .catch((e) => setError(String(e)))
  }

  return (
    <div className="split">
      <div className="pane" style={{ padding: 16 }}>
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Промпт-пакет</h2>
        <p className="secondary">
          Генерация происходит вне системы: пакет копируется во внешний интерфейс LLM, ответ
          вставляется обратно. Схема ответа — поле пакета, а не текст в задании.
        </p>
        <button className="tab" onClick={build}>
          Собрать пакет
        </button>
        {pkg && (
          <div className="card" style={{ marginTop: 12 }}>
            <h3>
              <span className="id">{pkg.id}</span> · {pkg.kind}
            </h3>
            <div>
              <textarea
                readOnly
                value={JSON.stringify(pkg, null, 1)}
                style={{ width: '100%', height: 160, fontFamily: 'var(--font-mono)', fontSize: 12 }}
              />
            </div>
          </div>
        )}

        <h2 style={{ fontSize: 15 }}>Ответ модели</h2>
        <textarea
          value={raw}
          onChange={(e) => setRaw(e.target.value)}
          placeholder="Вставьте ответ LLM (допускается обрамление ```json)"
          style={{ width: '100%', height: 140, fontFamily: 'var(--font-mono)', fontSize: 12 }}
        />
        <div>
          <button className="tab" onClick={submit} disabled={!raw.trim()}>
            Разобрать и отфильтровать
          </button>
        </div>

        {error && <div className="warn">Ошибка: {error}</div>}

        {report && (
          <div style={{ marginTop: 12 }}>
            <h2 style={{ fontSize: 15 }}>Отчёт по пакету</h2>
            <div style={{ display: 'flex', gap: 24, marginBottom: 12 }}>
              <Stat label="Предложено" value={report.proposed} />
              <Stat label="Дошло до инженера" value={report.shown.length} />
              <Stat label="В переделку" value={report.rework.rejected} warn={report.rework.rejected > 0} />
              <Stat label="Не разобрано" value={report.malformed.length} warn={report.malformed.length > 0} />
            </div>

            {report.shown.map((proposal) => (
              <Proposal
                key={String(proposal.item.id)}
                proposal={proposal}
                selected={selected[String(proposal.item.id)] ?? new Set()}
                accepted={accepted[String(proposal.item.id)]}
                onToggle={(field) => toggleField(String(proposal.item.id), field)}
                onAccept={() => accept(proposal)}
              />
            ))}
            {report.shown.length === 0 && (
              <div className="secondary">
                Ни одно предложение не прошло структурный фильтр — до инженера они не доходят.
              </div>
            )}
          </div>
        )}
      </div>

      <aside className="pane pane--side">
        <div className="card">
          <h3>Отбраковано по правилам</h3>
          <div>
            {report ? (
              Object.entries(report.by_rule).length === 0 ? (
                <span className="secondary">замечаний нет</span>
              ) : (
                Object.entries(report.by_rule).map(([rule, count]) => (
                  <div key={rule} className="field">
                    <label>{rule}</label>
                    <span className="mono">{count}</span>
                  </div>
                ))
              )
            ) : (
              <span className="secondary">ответ ещё не разобран</span>
            )}
          </div>
        </div>

        {report && report.rework.rework.length > 0 && (
          <div className="card">
            <h3>Очередь переделки</h3>
            <div>
              <p className="secondary">
                Эти предложения инженеру не показываются: они возвращаются модели вместе
                с замечаниями.
              </p>
              {report.rework.rework.map((entry, i) => (
                <div key={i} className="field">
                  <label>{String((entry.item as Record<string, unknown>)?.id ?? '—')}</label>
                  {entry.issues.map((issue) => (
                    <div key={issue} className="amber">
                      △ {issue}
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>
        )}
      </aside>
    </div>
  )
}

/**
 * Значение поля для показа в diff. Структурное поле выводится компактным JSON,
 * а не «[object Object]»: принимать изменение, которого не видно, нельзя —
 * это акцепт вслепую, а он и есть то, чего экран должен не допустить.
 */
function show(field: unknown): string {
  if (field === undefined) return '—'
  if (field === null) return 'null'
  return typeof field === 'object' ? JSON.stringify(field) : String(field)
}

function Stat({ label, value, warn }: { label: string; value: number; warn?: boolean }) {
  return (
    <div>
      <div className="secondary">{label}</div>
      <div className={`mono${warn ? ' warn' : ''}`} style={{ fontSize: 20 }}>
        {value}
      </div>
    </div>
  )
}

/** Предложение как построчный diff: применяются только выбранные поля. */
function Proposal({
  proposal,
  selected,
  accepted,
  onToggle,
  onAccept,
}: {
  proposal: ScreenedProposal
  selected: Set<string>
  accepted?: string[]
  onToggle: (field: string) => void
  onAccept: () => void
}) {
  const entries = Object.entries(proposal.diff).filter(([, d]) => d.op === 'add' || d.op === 'change')
  return (
    <div className="card">
      <h3>
        <span className="id">{String(proposal.item.id)}</span>
      </h3>
      <div>
        {entries.length === 0 && <span className="secondary">изменений нет</span>}
        <table>
          <thead>
            <tr>
              <th style={{ width: 40 }} />
              <th style={{ width: 140 }}>Поле</th>
              <th style={{ width: 80 }}>Что</th>
              <th>Было → Станет</th>
            </tr>
          </thead>
          <tbody>
            {entries.map(([field, entry]) => (
              <tr key={field} onClick={() => onToggle(field)}>
                <td>
                  <input type="checkbox" checked={selected.has(field)} readOnly />
                </td>
                <td className="mono">{field}</td>
                <td className="secondary">{entry.op === 'add' ? 'добавить' : 'изменить'}</td>
                <td>
                  <span className="truncate" title={`${show(entry.from)} → ${show(entry.to)}`}>
                    {entry.op === 'change' && <span className="secondary">{show(entry.from)} → </span>}
                    {show(entry.to)}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="secondary">
          Выбрано полей: {selected.size}. Акцепт применяет только их; ручного повторного ввода
          значений не требуется.
        </p>
        {accepted ? (
          <div className="secondary">
            Принято полей: {accepted.length} ({accepted.join(', ')}). Происхождение поля —
            предложение ИИ, акцептовано инженером (TZ-AI-004).
          </div>
        ) : (
          <button className="tab" onClick={onAccept} disabled={selected.size === 0}>
            Применить выбранные поля
          </button>
        )}
      </div>
    </div>
  )
}
