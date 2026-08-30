// Экран «Предложения ИИ»: пакет, ответ модели, фильтр, diff, акцепт.
//
// Генерация происходит ВНЕ системы (канал 1, TZ-AI-001): пакет копируют во
// внешний интерфейс LLM, ответ вставляют обратно. Разбор и структурный фильтр
// выполняет сервер — до инженера доходит только состоятельное.
//
// Пачечный акцепт (блок E) — только у ПОРОЖДАЮЩИХ видов (цели, нужды,
// сервисы, требования из постановки): объём в сотни объектов приходит отсюда,
// и до акцепта каждый виден списком. Для видов, ПРАВЯЩИХ существующие
// объекты (качество, декомпозиция, верификация), массового акцепта нет
// намеренно: «улучшить тысячу требований» одной кнопкой — обход управления
// конфигурацией (STEP-5, ловушка 2).
import { useEffect, useState } from 'react'
import { api, type BatchReport } from '../api/client'
import { useSession } from '../ui/session'
import type {
  UnacceptedAiRow, AnswerReport, PromptPackage, ScreenedProposal } from '../api/types'

/** Виды пакетов канала: порождающие принимаются пачкой, правящие — по одному. */
const KINDS: Array<{ id: string; title: string; task: string; generative: boolean }> = [
  { id: 'mission_to_goals', title: 'Постановка → цели миссии',
    task: 'Из постановки миссии сформулируй цели и задачи с MOE. Верни объекты по схеме ответа.',
    generative: true },
  { id: 'mission_to_needs', title: 'Постановка → нужды',
    task: 'Из постановки миссии выведи нужды стейкхолдеров. Верни объекты по схеме ответа.',
    generative: true },
  { id: 'needs_to_services', title: 'Нужды → сервисы',
    task: 'По нуждам предложи сервисы с QoS-профилями по классам потребителей. Верни объекты по схеме ответа.',
    generative: true },
  { id: 'services_to_requirements', title: 'Сервисы → требования',
    task: 'По сервисам и их QoS сформулируй системные требования с условиями и планом верификации. Верни объекты по схеме ответа.',
    generative: true },
  { id: 'risk_register', title: 'Сценарий → риски',
    task: 'По описанию сценария предложи записи реестра рисков. Верни объекты по схеме ответа.',
    generative: true },
  { id: 'requirement_quality', title: 'Качество требований',
    task: 'Проверь формулировки требований и предложи исправления. Верни объекты по схеме ответа.',
    generative: false },
]

export function AiProposal() {
  const [pkg, setPkg] = useState<PromptPackage | null>(null)
  const [raw, setRaw] = useState('')
  const [report, setReport] = useState<AnswerReport | null>(null)
  const [selected, setSelected] = useState<Record<string, Set<string>>>({})
  const [accepted, setAccepted] = useState<Record<string, string[]>>({})
  const [error, setError] = useState<string | null>(null)
  const [unaccepted, setUnaccepted] = useState<UnacceptedAiRow[]>([])
  const { author } = useSession()
  const [kindId, setKindId] = useState(KINDS[0].id)
  const [mission, setMission] = useState('')
  const [batchReport, setBatchReport] = useState<BatchReport | null>(null)

  // Список неакцептованного живёт здесь (шаг 16 §2.4, TZ-AI-004): предложение,
  // принятое «на словах», но не акцептованное, — незакрытая работа инженера
  useEffect(() => {
    api.unacceptedAi().then(setUnaccepted).catch((e) => setError(String(e)))
  }, [accepted])

  const chosen = KINDS.find((k) => k.id === kindId)!
  const kind = chosen.id
  const context = { mission: mission || 'проект текущего портфеля' }

  const build = () => {
    setError(null)
    api.buildPackage(kind, context, chosen.task).then(setPkg).catch((e) => setError(String(e)))
  }

  const submit = () => {
    setError(null)
    setBatchReport(null)
    api.submitAnswer(kind, context, chosen.task, raw).then(setReport).catch((e) => setError(String(e)))
  }

  /** Блок E: пачка показанных предложений принимается одним действием. */
  const acceptAll = () => {
    if (!report || !author) return
    setError(null)
    api
      .acceptBatch(report.package_id, 'внешняя LLM', author, report.shown.map((p) => p.item))
      .then((r) => {
        setBatchReport(r)
        setAccepted((prev) => ({
          ...prev,
          ...Object.fromEntries(report.shown.map((p) => [String(p.item.id), ['пачкой']])),
        }))
      })
      .catch((e) => setError(String(e)))
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
        {unaccepted.length > 0 && (
          <div className="warn" style={{ padding: 8, marginBottom: 8 }}>
            Неакцептовано ({unaccepted.length}):{' '}
            {unaccepted.map((u) => `${u.object_id} · ${u.name}`).join('; ')}
          </div>
        )}
        <h2 style={{ fontSize: 15, marginTop: 0 }}>Промпт-пакет</h2>
        <p className="secondary">
          Генерация происходит вне системы: пакет копируется во внешний интерфейс LLM, ответ
          вставляется обратно. Схема ответа — поле пакета, а не текст в задании.
        </p>
        <div className="field">
          <label>Вид пакета (канал О2–О4: объём приходит пачками)</label>
          <div className="tabs" style={{ flexWrap: 'wrap' }}>
            {KINDS.map((k) => (
              <button key={k.id} className="tab" aria-selected={k.id === kindId}
                onClick={() => { setKindId(k.id); setPkg(null); setReport(null) }}>
                {k.title}
              </button>
            ))}
          </div>
        </div>
        <div className="field">
          <label>Постановка миссии / контекст пакета</label>
          <textarea rows={3} style={{ width: '100%' }} value={mission}
            onChange={(e) => setMission(e.target.value)}
            placeholder="Национальная спутниковая платформа IoT: сбор телеметрии…" />
        </div>
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
          <button title="вставьте ответ службы пакетом — кнопка оживёт" className="tab" onClick={submit} disabled={!raw.trim()}>
            Разобрать и отфильтровать
          </button>
        </div>

        {error && <div className="warn">Ошибка: {error}</div>}
        {report && chosen.generative && report.shown.length > 0 && (
          <div className="field" style={{ marginTop: 8 }}>
            <button className="tab tab--primary" onClick={acceptAll} disabled={!author}
              title={author ? '' : 'представьтесь в шапке'}>
              Принять пачкой ({report.shown.length})
            </button>
            <span className="secondary"> порядок вставки разрешит сервер; всё или ничего</span>
          </div>
        )}
        {batchReport && (
          <div className={batchReport.problems.length ? 'notice notice--blocked' : 'notice'}>
            {batchReport.problems.length === 0
              ? <>Принято пачкой: <b className="mono">{batchReport.written}</b></>
              : <>Пачка отклонена: {batchReport.problems.slice(0, 5).map((p) => `${p.id ?? p.index}: ${p.message}`).join('; ')}</>}
          </div>
        )}

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
          <button title="отметьте хотя бы одно предложение слева — акцепт идёт по выбранным" className="tab" onClick={onAccept} disabled={selected.size === 0}>
            Применить выбранные поля
          </button>
        )}
      </div>
    </div>
  )
}
