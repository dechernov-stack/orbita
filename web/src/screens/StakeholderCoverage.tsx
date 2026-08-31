// Ф-13 (шип 3): матрица «стейкхолдер × нужды». Три состояния нужды —
// заявлена · покрыта требованием · закрыта верификацией; края видимы:
// стейкхолдер без нужд объясняет пустоту, нужда без носителя показывается
// отдельной группой, а не теряется между строк.
//
// Состояния считает сервер: здесь только показ и переходы.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { StakeholderCoverageView } from '../api/types'
import { Tooltip } from '../ui/Tooltip'
import { SortTh, useSort } from '../ui/sort'

const STATE_LABEL: Record<string, string> = {
  declared: 'заявлена',
  covered: 'покрыта',
  verified: 'закрыта',
}

const STATE_WHY: Record<string, string> = {
  declared: 'нужда названа, но ни одно требование на неё не ссылается',
  covered: 'есть требование с трассой на эту нужду; верификация ещё не закрыта',
  verified: 'покрывающее требование имеет закрывающее событие верификации',
}

const ROLE_LABEL: Record<string, string> = {
  customer: 'заказчик',
  regulator: 'регулятор',
  operator: 'оператор',
  consumer: 'потребитель',
  supplier: 'поставщик',
  partner: 'партнёр',
  established: 'учреждаемый',
}

export function StakeholderCoverage({ onGo }: { onGo?: (screen: string, kind?: string) => void }) {
  const [view, setView] = useState<StakeholderCoverageView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { sorted, sort, toggle } = useSort(view?.rows ?? [], {
    id: (r) => r.id,
    name: (r) => r.name,
    role: (r) => r.role,
    needs: (r) => r.needs,
    covered: (r) => r.covered,
    verified: (r) => r.verified,
  })

  useEffect(() => {
    api.stakeholderCoverage().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>

  return (
    <>
      <div className="card">
        <h3>Покрытие нужд по стейкхолдерам</h3>
        <p className="secondary" style={{ marginTop: 0 }}>
          {view.summary} · заявлено {view.declared} · покрыто {view.covered} · закрыто {view.verified}
        </p>
        {view.rows.length === 0 ? (
          <p className="secondary" style={{ margin: 0 }}>
            Стейкхолдеров в проекте нет. Они заводятся вручную либо приходят урожаем
            разбора записки — акцепт кладёт их в проект.
          </p>
        ) : (
          <table className="grid">
            <thead>
              <tr>
                <SortTh label="Стейкхолдер" sortKey="id" sort={sort} onToggle={toggle} width={110} />
                <SortTh label="Кто это" sortKey="name" sort={sort} onToggle={toggle} />
                <SortTh label="Роль" sortKey="role" sort={sort} onToggle={toggle} width={120} />
                <SortTh label="Нужд" sortKey="needs" sort={sort} onToggle={toggle} width={70} />
                <SortTh label="Покрыто" sortKey="covered" sort={sort} onToggle={toggle} width={90} />
                <SortTh label="Закрыто" sortKey="verified" sort={sort} onToggle={toggle} width={90} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{r.id}</td>
                  <td>
                    {r.name}
                    {r.establishes && (
                      <Tooltip text="организация учреждается проектом: сегодняшних решений и обязательств у неё нет">
                        <span className="chip"> учреждаемый</span>
                      </Tooltip>
                    )}
                    {r.interest && <div className="secondary">{r.interest}</div>}
                    {r.supplies?.map((s) => (
                      <div key={s.id} className="secondary">
                        поставляет <span className="mono">{s.id}</span> {s.name}
                        {s.has_form
                          ? <Tooltip text="у узла есть анкета характеристик — вопрос «чего не хватает» адресуется поставщику">
                              <span className="chip"> анкета есть</span>
                            </Tooltip>
                          : null}
                      </div>
                    ))}
                    {r.empty_why && <div className="secondary">{r.empty_why}</div>}
                  </td>
                  <td>{ROLE_LABEL[r.role] ?? r.role}</td>
                  <td className="num">{r.needs}</td>
                  <td className="num">{r.covered}</td>
                  <td className="num">{r.verified}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {view.rows.filter((r) => r.items.length > 0).map((r) => (
        <div className="card" key={`items-${r.id}`}>
          <h4 style={{ margin: '0 0 6px' }}>{r.name} · нужды</h4>
          <table className="grid">
            <tbody>
              {r.items.map((it) => (
                <tr key={it.id}>
                  <td className="mono" style={{ width: 110 }}>{it.id}</td>
                  <td>{it.statement}</td>
                  <td style={{ width: 130 }}>
                    <Tooltip text={STATE_WHY[it.state]}>
                      <span className={it.state === 'declared' ? 'warn' : 'chip'}>
                        {STATE_LABEL[it.state]}
                      </span>
                    </Tooltip>
                  </td>
                  <td style={{ width: 180 }} className="mono secondary">
                    {it.covered_by?.join(', ') ?? ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}

      {view.without_stakeholder.length > 0 && (
        <div className="card">
          <h4 style={{ margin: '0 0 6px' }}>Нужды без носителя · {view.without_stakeholder.length}</h4>
          <p className="secondary" style={{ marginTop: 0 }}>
            Эти нужды не видны ни в одной строке матрицы: у них не назван стейкхолдер.
            Разрыв мягкий — связь дозревает к MCR.
          </p>
          <table className="grid">
            <tbody>
              {view.without_stakeholder.map((it) => (
                <tr key={it.id}>
                  <td className="mono" style={{ width: 110 }}>{it.id}</td>
                  <td>{it.statement}</td>
                  <td style={{ width: 160 }} className="secondary">
                    {it.named ? `назван словами: ${it.named}` : 'носитель не назван вовсе'}
                  </td>
                  <td style={{ width: 130 }}>
                    <Tooltip text={STATE_WHY[it.state]}>
                      <span className={it.state === 'declared' ? 'warn' : 'chip'}>
                        {STATE_LABEL[it.state]}
                      </span>
                    </Tooltip>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {onGo && (
            <div className="toolbar" style={{ padding: '6px 0' }}>
              <button className="rr-assign" onClick={() => onGo('needs')}
                title="открыть реестр нужд — носитель указывается в карточке нужды">
                назвать носителей →
              </button>
            </div>
          )}
        </div>
      )}
    </>
  )
}
