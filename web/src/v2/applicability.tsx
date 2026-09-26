// Применимость (шип 4 §5): матрица на Постановке — чужая нужда × наши сервисы
// → вердикт · чего не хватает · предложение; «не наш профиль» — со ссылкой на
// границу устава. Считает сервер; здесь только показ и решение человека.
import { useCallback, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { api, type ApplicabilityMatrix, type OpportunityRow } from './api'
import { Чипы, отобрать, type ЧипОтбора } from './ui/chips'
import { ИконКнопка } from './ui/iconbutton'

const СЛОВО_РЕШЕНИЯ_ПРИМЕНИМОСТИ: Record<string, string> = {
  proposed: 'предложена',
  accepted: 'принята',
  rejected: 'отклонена',
}

function отказСловами(e: unknown): string {
  const о = e as { message?: string }
  return String(о?.message ?? e)
}

export function Применимость({ project, onChanged }: { project: string; onChanged?: () => void }) {
  const [матрица, setМатрица] = useState<ApplicabilityMatrix | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрытьВопрос] = useConfirm()
  /** Чипы отбора по вердикту (шип 5 §2): значения из данных. */
  const [включены, setВключены] = useState<Set<string>>(new Set())

  const перечитать = useCallback(() => {
    api.opportunities(project).then(setМатрица).catch((e) => setОтказ(отказСловами(e)))
  }, [project])
  useEffect(перечитать, [перечитать])

  const решить = (с: OpportunityRow, status: 'accepted' | 'rejected') => {
    const действие = () => (reason: string) => api.decideOpportunity(project, с.code, status, reason)
      .then((р) => { setИтог(`${р.code}: ${СЛОВО_РЕШЕНИЯ_ПРИМЕНИМОСТИ[р.status] ?? р.status}`); перечитать(); onChanged?.() })
      .catch((e) => setОтказ(отказСловами(e)))
    if (status === 'rejected') {
      спросить({
        question: `Отклонить применимость «${с.external_item}»? Возможность останется в проекте отклонённой, с причиной.`,
        ok: 'Отклонить',
        input: { label: 'почему это не наш случай', required: true },
        onOk: действие(),
      })
    } else {
      действие()('')
    }
  }

  if (отказ) return <div className="v2-locked">{отказ}</div>
  if (!матрица) return <div className="v2-empty">Считаю применимость…</div>
  const вердикты = Array.from(new Set(матрица.rows.map((с) => с.verdict)))
  const чипы: ЧипОтбора<OpportunityRow>[] = вердикты.map((в) => ({
    key: в, word: матрица.rows.find((с) => с.verdict === в)?.verdict_word ?? в, group: 'вердикт', test: (с: OpportunityRow) => с.verdict === в,
  }))
  const видимые = отобрать(матрица.rows, чипы, включены)
  return (
    <div data-why="работа" aria-label="матрица применимости">
      <p className="v2-empty__why">{матрица.note}</p>
      {итог && <div className="v2-note-line">{итог}</div>}
      {матрица.rows.length > 0 && (
        <Чипы строки={матрица.rows} чипы={чипы} включены={включены} label="отбор по вердикту"
          onToggle={(к) => setВключены((было) => { const стало = new Set(было); if (стало.has(к)) стало.delete(к); else стало.add(к); return стало })} />
      )}
      {матрица.rows.length > 0 && (
        <table className="v2-table">
          <thead>
            <tr><th>Чужая нужда</th><th>Чья</th><th>Вердикт</th><th>Наши сервисы</th><th>Чего не хватает</th><th>Предложение</th><th>Основание</th><th>Решение</th></tr>
          </thead>
          <tbody>
            {видимые.map((с) => (
              <tr key={с.code}>
                <td><span className="v2-mono">{с.code}</span> {с.external_item}{с.scale && <span className="v2-muted"> · {с.scale}</span>}</td>
                <td>{с.owner ?? '—'}</td>
                <td>
                  <span className={с.verdict === 'not_our_profile' ? 'v2-chip v2-chip--warn' : 'v2-chip'}>{с.verdict_word}</span>
                  {с.charter_boundary && (
                    <div className="v2-dim" title={`граница устава: ${с.charter_boundary.material} ${с.charter_boundary.anchor}`}>
                      граница устава <span className="v2-mono">{с.charter_boundary.anchor}</span>: {с.charter_boundary.text.slice(0, 120)}
                    </div>
                  )}
                  {с.rationale && <div className="v2-dim">{с.rationale}</div>}
                </td>
                <td>{с.our_service_names.length === 0 ? '—' : с.our_service_names.join('; ')}</td>
                <td>{с.missing ?? '—'}</td>
                <td>{с.proposal_word ?? '—'}</td>
                <td>
                  {с.external
                    ? <span title={с.external.quote ?? ''}>{с.external.material_name ?? с.external.material ?? '—'} <span className="v2-mono">{с.external.anchor ?? ''}</span></span>
                    : <span className="v2-muted">без следа</span>}
                </td>
                <td className="v2-acts">
                  <span className="v2-dim">{СЛОВО_РЕШЕНИЯ_ПРИМЕНИМОСТИ[с.status] ?? с.status}</span>
                  {с.status !== 'accepted' && (
                    <ИконКнопка икон="принять" слово="принять применимость" onClick={() => решить(с, 'accepted')} />
                  )}
                  {с.status !== 'rejected' && (
                    <ИконКнопка икон="отклонить" слово="отклонить с причиной" onClick={() => решить(с, 'rejected')} />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {матрица.external_targets.length > 0 && (
        <div data-why="работа" aria-label="чужие цели">
          <div className="v2-facet__title">Чужие цели — фактами, не целями проекта · {матрица.external_targets.length}</div>
          <table className="v2-table">
            <thead><tr><th>Чья</th><th>Цель</th><th>Документ</th><th>Обосновывает наши цели</th></tr></thead>
            <tbody>
              {матрица.external_targets.map((ц) => (
                <tr key={ц.code}>
                  <td>{ц.owner}</td>
                  <td title={ц.quote ?? ''}><span className="v2-mono">{ц.code}</span> {ц.statement}</td>
                  <td>{ц.material_name ?? ц.material ?? '—'} <span className="v2-mono">{ц.anchor ?? ''}</span></td>
                  <td>{ц.grounds.length === 0 ? <span className="v2-muted">пока ни одной</span> : ц.grounds.join(' · ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}
