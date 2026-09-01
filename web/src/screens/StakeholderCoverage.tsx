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
import { useSession } from '../ui/session'
import { edit } from '../api/edit'

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

export function StakeholderCoverage({ onGo }: {
  onGo?: (screen: string, kind?: string, id?: string) => void
}) {
  const { author } = useSession()
  const [view, setView] = useState<StakeholderCoverageView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  // выбор носителя для нужды: назначение делается на месте, где виден разрыв
  const [carrier, setCarrier] = useState<Record<string, string>>({})
  // роли для носителей, названных в нуждах словами: имя — факт документа,
  // роль — решение инженера, выдумывать её система не вправе
  const [roleFor, setRoleFor] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  /**
   * Назначить нужде носителя. Раньше кнопка вела «в реестр нужд» и на этом
   * заканчивалась: инженер оказывался на другом экране без подсказки, что
   * там делать (наблюдение живого прохода). Связь ставится там, где виден
   * разрыв, и матрица пересчитывается сразу.
   */
  /**
   * Завести стейкхолдеров из имён, УЖЕ НАЗВАННЫХ в нуждах словами.
   *
   * Первый вариант экрана предлагал выбрать носителя из списка — а список
   * был пуст, потому что стейкхолдеров в проекте нет вовсе: выбирать не из
   * чего, и «назвать» не работало ни для одной нужды (наблюдение живого
   * прохода). Имена при этом лежат в самих нуждах: «Минтранс России»,
   * «АО ГЛОНАСС». Система заводит их объектами и связывает нужды разом.
   */
  const завестиНосителей = async (имена: string[]) => {
    if (!author) return
    const carriers = имена
      .filter((имя) => (roleFor[имя] ?? '').length > 0)
      .map((имя) => ({ name: имя, role: roleFor[имя] }))
    if (carriers.length === 0) return
    setBusy(true)
    setNote(null)
    try {
      const r = await api.stakeholdersFromNeeds(carriers, author)
      setNote(`заведено носителей: ${r.count}; связано нужд: ${r.created.reduce((a, c) => a + c.needs, 0)}`)
      await api.stakeholderCoverage().then(setView)
    } catch (e) {
      setNote(String(e))
    } finally {
      setBusy(false)
    }
  }

  const назначить = async (needId: string) => {
    const skId = carrier[needId]
    if (!skId || !author) return
    setBusy(true)
    setNote(null)
    try {
      const fresh = await edit.object(needId)
      const doc = { ...(fresh.doc as Record<string, unknown>), stakeholder_ref: skId }
      await edit.changeWithRef(needId, doc, `носитель нужды назван: ${skId}`)
      setNote(`${needId} → ${skId}: носитель назван, матрица пересчитана`)
      await api.stakeholderCoverage().then(setView)
    } catch (e) {
      setNote(String(e))
    } finally {
      setBusy(false)
    }
  }

  /**
   * Ф-14: второй конец контура библиотеки. Ш2 берёт типовое с полки в
   * проект; здесь проектный факт обобщается в шаблон А2 — отдельным
   * действием инженера, а не побочным эффектом акцепта.
   */
  const generalize = (id: string) => {
    if (!author) return
    api.generalizeStakeholder(id, author)
      .then((r) => {
        setNote(`${id} обобщён в профиль ${r.profile} — полка знает исток, факт знает шаблон`)
        return api.stakeholderCoverage().then(setView)
      })
      .catch((e) => setNote(String(e)))
  }
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
        {note && <div className="secondary" style={{ marginBottom: 6 }}>{note}</div>}
        <p className="secondary" style={{ marginTop: 0 }}>
          {view.summary} · заявлено {view.declared} · покрыто {view.covered} · закрыто {view.verified}
        </p>
        {view.rows.length === 0 ? (
          <p className="secondary" style={{ margin: 0 }}>
            Стейкхолдеров в проекте нет. Они заводятся вручную либо приходят урожаем
            разбора записки — акцепт кладёт их в проект.
          </p>
        ) : (
          <div style={{ overflowX: "auto" }}><table className="grid">
            <thead>
              <tr>
                <SortTh label="Стейкхолдер" sortKey="id" sort={sort} onToggle={toggle} width={110} />
                <SortTh label="Кто это" sortKey="name" sort={sort} onToggle={toggle} />
                <SortTh label="Роль" sortKey="role" sort={sort} onToggle={toggle} width={120} />
                <SortTh label="Нужд" sortKey="needs" sort={sort} onToggle={toggle} width={70} />
                <SortTh label="Покрыто" sortKey="covered" sort={sort} onToggle={toggle} width={90} />
                <SortTh label="Закрыто" sortKey="verified" sort={sort} onToggle={toggle} width={90} />
                <th title="обобщение проектного факта в шаблон полки — второй конец контура библиотеки">
                  На полку
                </th>
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
                  <td style={{ width: 150 }}>
                    <button className="rr-assign" onClick={() => generalize(r.id)} disabled={!author}
                      title={author
                        ? 'обобщить в профиль полки А2: проектная специфика уходит, остаётся шаблон класса'
                        : 'представьтесь в шапке: обобщение пишется на автора'}>
                      обобщить →
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
      </div>

      {view.rows.filter((r) => r.items.length > 0).map((r) => (
        <div className="card" key={`items-${r.id}`}>
          <h4 style={{ margin: '0 0 6px' }}>{r.name} · нужды</h4>
          <div style={{ overflowX: "auto" }}><table className="grid">
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
          </table></div>
        </div>
      ))}

      {/* Носители названы в нуждах словами — система заводит их объектами.
          Имя берётся из документа, роль называет инженер: выдумывать роль
          система не вправе, а перепечатывать имена ему незачем. */}
      {view.rows.length === 0 && view.without_stakeholder.length > 0 && (
        <div className="card">
          <h4 style={{ margin: '0 0 6px' }}>Носители, названные в нуждах</h4>
          <p className="secondary" style={{ marginTop: 0 }}>
            Стейкхолдеров в проекте нет, но в нуждах носители уже названы словами.
            Назовите роль каждому — и он станет объектом проекта, а его нужды свяжутся сами.
          </p>
          {note && <div className="secondary" style={{ marginBottom: 6 }}>{note}</div>}
          <div style={{ overflowX: 'auto' }}>
            <table className="grid">
              <thead>
                <tr><th>Кто назван</th><th style={{ width: 90 }}>Нужд</th><th style={{ width: 210 }}>Роль в проекте</th></tr>
              </thead>
              <tbody>
                {[...new Set(view.without_stakeholder.map((it) => it.named).filter(Boolean))].map((имя) => (
                  <tr key={имя}>
                    <td className="wrap">{имя}</td>
                    <td className="num">
                      {view.without_stakeholder.filter((it) => it.named === имя).length}
                    </td>
                    <td>
                      <select value={roleFor[имя!] ?? ''} style={{ width: '100%' }}
                        title="роль называет инженер: заказчик, регулятор, оператор, потребитель, поставщик, партнёр либо учреждаемая организация"
                        onChange={(e) => setRoleFor((p) => ({ ...p, [имя!]: e.target.value }))}>
                        <option value="">— роль —</option>
                        {Object.entries(ROLE_LABEL).map(([k, v]) => (
                          <option key={k} value={k}>{v}</option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="np-actions" style={{ marginTop: 6 }}>
            <button className="np-btn np-pri" disabled={busy || !author
              || Object.values(roleFor).filter(Boolean).length === 0}
              title={!author
                ? 'представьтесь в шапке: объекты пишутся на автора'
                : Object.values(roleFor).filter(Boolean).length === 0
                  ? 'назовите роль хотя бы одному носителю'
                  : 'завести отмеченных носителей объектами и связать их нужды'}
              onClick={() => void завестиНосителей(
                [...new Set(view.without_stakeholder.map((it) => it.named).filter(Boolean))] as string[],
              )}>
              {busy ? 'Завожу…' : `Завести носителей (${Object.values(roleFor).filter(Boolean).length})`}
            </button>
          </div>
        </div>
      )}

      {view.without_stakeholder.length > 0 && (
        <div className="card">
          <h4 style={{ margin: '0 0 6px' }}>Нужды без носителя · {view.without_stakeholder.length}</h4>
          <p className="secondary" style={{ marginTop: 0 }}>
            Эти нужды не видны ни в одной строке матрицы: у них не назван стейкхолдер.
            Разрыв мягкий — связь дозревает к MCR.
          </p>
          <div style={{ overflowX: "auto" }}><table className="grid">
            <tbody>
              {view.without_stakeholder.map((it) => (
                <tr key={it.id}>
                  <td className="mono" style={{ width: 110 }}>{it.id}</td>
                  <td>{it.statement}</td>
                  <td style={{ width: 150 }} className="secondary">
                    {it.named ? `назван словами: ${it.named}` : 'носитель не назван вовсе'}
                  </td>
                  <td style={{ width: 120 }}>
                    <Tooltip text={STATE_WHY[it.state]}>
                      <span className={it.state === 'declared' ? 'warn' : 'chip'}>
                        {STATE_LABEL[it.state]}
                      </span>
                    </Tooltip>
                  </td>
                  <td style={{ width: 260 }}>
                    <div style={{ display: 'flex', gap: 4 }}>
                      <select value={carrier[it.id] ?? ''} style={{ flex: 1, minWidth: 120 }}
                        title="стейкхолдер проекта, чья это нужда"
                        onChange={(e) => setCarrier((p) => ({ ...p, [it.id]: e.target.value }))}>
                        <option value="">— носитель —</option>
                        {view.rows.map((r) => (
                          <option key={r.id} value={r.id}>{r.name}</option>
                        ))}
                      </select>
                      <button className="rr-assign" disabled={busy || !author || !carrier[it.id]}
                        title={!author
                          ? 'представьтесь в шапке: связь пишется на автора'
                          : !carrier[it.id]
                            ? 'выберите стейкхолдера — чья это нужда'
                            : 'назвать носителя: нужда войдёт в его строку матрицы'}
                        onClick={() => void назначить(it.id)}>
                        назвать
                      </button>
                    </div>
                    {onGo && (
                      <button className="np-linkish" onClick={() => onGo('needs', undefined, it.id)}
                        title="открыть карточку нужды: формулировка, сервисы, история">
                        открыть нужду →
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table></div>
          <p className="secondary" style={{ margin: '6px 0 0' }}>
            Носитель называется прямо здесь — в строке, где виден разрыв.
          </p>
        </div>
      )}
    </>
  )
}
