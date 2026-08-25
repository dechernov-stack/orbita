// Портфель проектов (блок D). Карточка — имя, фаза, ближайшая точка с числом
// незакрытого; клик выбирает проект и ведёт в жизненный цикл. Создание живёт
// на отдельном экране «Создать проект» (конвейер экранов, №1, эталон
// docs/ui/reference2/): пустой портфель сразу ведёт туда — «первая установка».
import { useEffect, useState } from 'react'
import { edit, type StoredSummary } from '../api/edit'
import { currentProject, selectProject } from '../api/project'

interface CardInfo {
  id: string
  name: string
  phase: string
  nextGate: string | null
  unclosed: number | null
}

const PHASE_LABEL: Record<string, string> = { pre_phase_a: 'Pre-Phase A', phase_a: 'Phase A' }

async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(`/api${path}`, { headers: { Accept: 'application/json' } })
  if (!r.ok) throw new Error(await r.text())
  return (await r.json()) as T
}

export function Portfolio({ onOpen, onNew, onFirstRun }: {
  onOpen: () => void
  onNew: () => void
  onFirstRun: () => void
}) {
  const [cards, setCards] = useState<CardInfo[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    edit
      .list('project')
      .then(async (rows: StoredSummary[]) => {
        const infos = await Promise.all(
          rows.map(async (r) => {
            const doc = (await edit.object(r.id)).doc as {
              name?: string
              phase?: string
              milestones?: Array<{ gate: string; held?: boolean }>
            }
            const next = (doc.milestones ?? []).find((m) => !m.held)?.gate ?? null
            let unclosed: number | null = null
            if (next) {
              try {
                const issues = await getJson<{ issues: string[] }>(
                  `/gates/${encodeURIComponent(next)}/issues?project=${r.id}`,
                )
                unclosed = issues.issues.length
              } catch {
                unclosed = null
              }
            }
            return {
              id: r.id,
              name: doc.name ?? r.id,
              phase: doc.phase ?? '',
              nextGate: next,
              unclosed,
            }
          }),
        )
        setCards(infos)
      })
      .catch((e) => setError(String(e)))
  }

  useEffect(load, [])

  // Первая установка: проектов нет — сразу форма первого проекта (эталон, S2)
  const empty = cards != null && cards.length === 0
  useEffect(() => {
    if (empty) onFirstRun()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [empty])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (cards == null || empty) return <div className="empty">Загрузка портфеля…</div>

  return (
    <>
      <div className="toolbar">
        <h2>Портфель проектов</h2>
        <span className="count">{cards.length}</span>
        <div className="grow" />
        <button className="btn btn--primary" onClick={onNew}>Создать проект</button>
      </div>
      <div className="workarea">
        <div className="cards">
          {cards.map((c) => (
            <button key={c.id} className="pcard"
              onClick={() => { selectProject(c.id); onOpen() }}>
              <div className="pcard__head">
                <h3>{c.name}</h3>
                <span className="secondary">{PHASE_LABEL[c.phase] ?? c.phase}</span>
              </div>
              <div className="pcard__gate">
                {c.nextGate ? (
                  <>
                    <span>Ближайшая точка: <b className="mono">{c.nextGate}</b></span>
                    <span className="grow" />
                    {c.unclosed != null && (
                      <span className="mono" style={{ color: c.unclosed === 0 ? 'var(--status-baseline)' : c.unclosed > 10 ? 'var(--status-error)' : 'var(--status-approved)' }}>
                        {c.unclosed === 0 ? 'готово' : `не закрыто: ${c.unclosed}`}
                      </span>
                    )}
                  </>
                ) : (
                  <span className="secondary">Все точки пройдены</span>
                )}
              </div>
              <div className="pcard__foot">
                <span className="mono">{c.id}</span>
                {currentProject() === c.id && <span>· текущий</span>}
              </div>
            </button>
          ))}
        </div>
      </div>
    </>
  )
}
