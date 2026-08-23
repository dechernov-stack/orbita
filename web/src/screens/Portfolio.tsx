// Портфель проектов (блок D, дизайн: экран portfolio + create). Карточка —
// имя, фаза, ближайшая точка с числом незакрытого; клик выбирает проект и
// ведёт в жизненный цикл. Создание — имя, фаза, точки по фазе; проект
// создаётся пустым, без образцов (ADR-028).
import { useEffect, useState } from 'react'
import { edit, type StoredSummary } from '../api/edit'
import { currentProject, selectProject } from '../api/project'
import { useSession } from '../ui/session'

interface CardInfo {
  id: string
  name: string
  phase: string
  nextGate: string | null
  unclosed: number | null
}

const PHASE_LABEL: Record<string, string> = { pre_phase_a: 'Pre-Phase A', phase_a: 'Phase A' }

const GATES_BY_PHASE: Record<string, string[]> = {
  pre_phase_a: ['internal_review', 'MCR', 'KDP-A', 'SRR', 'SDR', 'KDP-B'],
  phase_a: ['SRR', 'SDR', 'KDP-B'],
}

async function getJson<T>(path: string): Promise<T> {
  const r = await fetch(`/api${path}`, { headers: { Accept: 'application/json' } })
  if (!r.ok) throw new Error(await r.text())
  return (await r.json()) as T
}

export function Portfolio({ onOpen }: { onOpen: () => void }) {
  const { author } = useSession()
  const [cards, setCards] = useState<CardInfo[] | null>(null)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [phase, setPhase] = useState('pre_phase_a')
  const [dates, setDates] = useState<Record<string, string>>({})
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

  const create = async () => {
    setError(null)
    try {
      const milestones = GATES_BY_PHASE[phase].map((g) =>
        dates[g] ? { gate: g, due: dates[g] } : { gate: g },
      )
      const saved = await edit.create('project', { name, phase, milestones }, author)
      selectProject(saved.id)
      onOpen()
    } catch (e) {
      setError(String(e))
    }
  }

  if (error && !creating) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (cards == null) return <div className="empty">Загрузка портфеля…</div>

  if (creating) {
    return (
      <>
        <div className="toolbar">
          <h2>Создание проекта</h2>
          <div className="grow" />
          <button className="btn" onClick={() => setCreating(false)}>Отмена</button>
          <button className="btn btn--primary" disabled={!name.trim() || !author} onClick={create}
            title={author ? '' : 'представьтесь в шапке'}>
            Создать проект
          </button>
        </div>
        <div className="workarea" style={{ padding: 14, maxWidth: 640 }}>
          {error && <div className="notice notice--blocked">{error}</div>}
          <div className="field">
            <label>Наименование проекта</label>
            <input style={{ width: '100%' }} value={name} onChange={(e) => setName(e.target.value)}
              placeholder="Национальная спутниковая платформа IoT" />
          </div>
          <div className="field">
            <label>Фаза</label>
            <div className="tabs">
              {Object.entries(PHASE_LABEL).map(([k, v]) => (
                <button key={k} className="tab" aria-selected={phase === k} onClick={() => setPhase(k)}>
                  {v}
                </button>
              ))}
            </div>
          </div>
          <div className="field">
            <label>Контрольные точки (набор — по фазе; даты правятся)</label>
            <table style={{ maxWidth: 420 }}>
              <thead>
                <tr><th>Точка</th><th style={{ width: 160 }}>Плановая дата</th></tr>
              </thead>
              <tbody>
                {GATES_BY_PHASE[phase].map((g) => (
                  <tr key={g}>
                    <td className="mono">{g}</td>
                    <td>
                      <input value={dates[g] ?? ''} placeholder="2027-02-15"
                        onChange={(e) => setDates({ ...dates, [g]: e.target.value })} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="hint secondary">Проект создаётся пустым: наполнение — операциями фазы.</p>
          </div>
        </div>
      </>
    )
  }

  return (
    <>
      <div className="toolbar">
        <h2>Портфель проектов</h2>
        <span className="count">{cards.length}</span>
        <div className="grow" />
        <button className="btn btn--primary" onClick={() => setCreating(true)}>Создать проект</button>
      </div>
      {cards.length === 0 ? (
        <div className="workarea" style={{ display: 'grid', placeItems: 'center' }}>
          <div style={{ maxWidth: 460, textAlign: 'center' }}>
            <h3 style={{ fontSize: 18, marginBottom: 8 }}>Проектов пока нет</h3>
            <p className="secondary">
              «Орбита» ведёт космические проекты через стадию Формулирования:
              Pre-Phase A → Phase A → решение KDP B. Создайте первый проект —
              и система поведёт его по контрольным точкам.
            </p>
            <button className="btn btn--primary" onClick={() => setCreating(true)}>
              Создать первый проект
            </button>
          </div>
        </div>
      ) : (
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
      )}
    </>
  )
}
