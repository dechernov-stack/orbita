// Вкладки матриц на экране требований (шаг 16 §2.4): трассировка, верификация,
// валидация. Отчёт идёт туда, где принимается решение, — отдельного экрана
// «Отчёты» нет намеренно: свалка отчётов равносильна их отсутствию.
//
// Все строки и разрывы посчитаны сервером; экран отображает. Пустой ответ и
// ответ, который не пришёл, — разные состояния, и показываются по-разному.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import type { TraceMatrixView, ValidationRow, VerificationMatrixFlatView } from '../api/types'

export type MatrixKind = 'trace' | 'verification' | 'validation'

export function RequirementMatrices({ kind }: { kind: MatrixKind }) {
  const [trace, setTrace] = useState<TraceMatrixView | null>(null)
  const [verification, setVerification] = useState<VerificationMatrixFlatView | null>(null)
  const [validation, setValidation] = useState<ValidationRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [link, setLink] = useState({ from: '', to: '' })
  const [linkReport, setLinkReport] = useState<string | null>(null)

  useEffect(() => {
    setError(null)
    if (kind === 'trace') api.traceMatrix().then(setTrace).catch((e) => setError(String(e)))
    if (kind === 'verification')
      api.verificationMatrix().then(setVerification).catch((e) => setError(String(e)))
    if (kind === 'validation')
      api.validationMatrix().then(setValidation).catch((e) => setError(String(e)))
  }, [kind])

  if (error) return <div className="warn" style={{ padding: 8 }}>Ошибка: {error}</div>

  if (kind === 'trace') {
    if (!trace) return <div className="empty">Загрузка матрицы…</div>
    if (trace.rows.length === 0)
      return <div className="empty">Требований нет — матрице трассировки не из чего строиться.</div>
    return (
      <div style={{ padding: '0 0 12px' }}>
        {trace.gaps.length > 0 && (
          <div className="warn" style={{ padding: 8 }}>
            Разрывы трассировки ({trace.gaps.length}):{' '}
            {trace.gaps.map((g) => `${g.requirement} — ${g.missing}`).join('; ')}
          </div>
        )}
        <table>
          <thead>
            <tr>
              <th style={{ width: 90 }}>Требование</th>
              <th>Нужды</th>
              <th>Сервисы</th>
              <th>Элементы</th>
              <th style={{ width: 110 }}>Метод</th>
            </tr>
          </thead>
          <tbody>
            {trace.rows.map((r) => (
              <tr key={r.requirement}>
                <td className="mono">{r.requirement}</td>
                <td className="mono">{r.needs.join(', ') || '—'}</td>
                <td className="mono">
                  {r.services.map((s) => `${s.id} (${s.consumer_class})`).join(', ') || '—'}
                </td>
                <td className="mono">{r.elements.join(', ') || '—'}</td>
                <td>{r.method ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  if (kind === 'verification') {
    if (!verification) return <div className="empty">Загрузка матрицы…</div>
    return (
      <div style={{ padding: '0 0 12px' }}>
        {verification.gaps.length > 0 && (
          <div className="warn" style={{ padding: 8 }}>
            Разрывы ({verification.gaps.length}):{' '}
            {verification.gaps
              .map((g) => `${g.requirement}${g.event ? ` / ${g.event}` : ''} — ${g.reason}`)
              .join('; ')}
          </div>
        )}
        {verification.unverified.length > 0 && (
          <div className="warn" style={{ padding: 8 }}>
            Не верифицировано ({verification.unverified.length}):{' '}
            <span className="mono">{verification.unverified.join(', ')}</span>
          </div>
        )}
        {/* Единственный оставшийся вид ручной связи (ADR-027): событие ↔ требование */}
        <div className="field" style={{ padding: '0 8px' }}>
          <input placeholder="требование (RQ-…)" value={link.from} style={{ width: 140 }}
            onChange={(e) => setLink({ ...link, from: e.target.value })} />
          <input placeholder="свидетельство (EV-…)" value={link.to} style={{ width: 150 }}
            onChange={(e) => setLink({ ...link, to: e.target.value })} />
          <button title="выберите оба конца связи: откуда и куда" type="button" className="tab" disabled={!link.from || !link.to}
            onClick={() => {
              setLinkReport(null)
              edit.addVerificationLink(link.from, link.to)
                .then(() => { setLinkReport(`связано: ${link.from} ↔ ${link.to}`); setLink({ from: '', to: '' }) })
                .catch((e) => setLinkReport(String(e).slice(0, 200)))
            }}>
            Связать (verification)
          </button>
          {linkReport && <span className="secondary"> {linkReport}</span>}
        </div>
        {verification.rows.length === 0 ? (
          <div className="empty">Событий верификации нет: план пуст, и это видно по разрывам выше.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th style={{ width: 90 }}>Требование</th>
                <th style={{ width: 90 }}>Событие</th>
                <th style={{ width: 90 }}>Метод</th>
                <th style={{ width: 90 }}>Уровень</th>
                <th style={{ width: 80 }}>Закрывает</th>
                <th>Подход</th>
                <th style={{ width: 100 }}>Статус</th>
                <th style={{ width: 110 }}>Свидетельство</th>
              </tr>
            </thead>
            <tbody>
              {verification.rows.map((r) => (
                <tr key={`${r.requirement}-${r.event}`}>
                  <td className="mono">{r.requirement}</td>
                  <td className="mono">{r.event}</td>
                  <td>{r.method ?? '—'}</td>
                  <td>{r.level ?? '—'}</td>
                  <td>{r.closes ? 'да' : '—'}</td>
                  <td>{r.approach || '—'}</td>
                  <td>{r.status ?? '—'}</td>
                  <td className="mono">
                    {r.evidence_ref ?? '—'}
                    {r.evidence_stale && <span className="warn"> устарело</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    )
  }

  if (!validation) return <div className="empty">Загрузка матрицы…</div>
  if (validation.length === 0)
    return (
      <div className="empty">
        Валидаций нет: цели валидации заводятся на Ш7 «Ведение реестров».
      </div>
    )
  return (
    <table>
      <thead>
        <tr>
          <th style={{ width: 90 }}>Валидация</th>
          <th>Цель</th>
          <th style={{ width: 100 }}>ConOps</th>
          <th style={{ width: 110 }}>Вид продукта</th>
          <th style={{ width: 100 }}>Метод</th>
          <th style={{ width: 90 }}>Фаза</th>
          <th style={{ width: 100 }}>Статус</th>
          <th style={{ width: 110 }}>Свидетельство</th>
        </tr>
      </thead>
      <tbody>
        {validation.map((v) => (
          <tr key={v.validation}>
            <td className="mono">{v.validation}</td>
            <td>{v.target}</td>
            <td className="mono">{v.conops_ref ?? '—'}</td>
            <td>{v.product_kind ?? '—'}</td>
            <td>{v.method ?? '—'}</td>
            <td>{v.phase ?? '—'}</td>
            <td>{v.status ?? '—'}</td>
            <td className="mono">{v.evidence_ref ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
