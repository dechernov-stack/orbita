// Матрица покрытия нужд (МОДЕЛЬ-ДАННЫХ §5: всё вычисляется).
//
// Клетка не бывает просто пустой: если нужда не покрыта, строка говорит,
// чего именно не хватает — цели, сервиса или носителя. Края матрицы видны:
// стороны без нужд показаны отдельно, чтобы не потеряться между строк.
import { useCallback, useEffect, useState } from 'react'
import { api, type CoverageMatrix, type CoverageNeed, type EntityRow } from './api'

/**
 * З-04: сетка «влияние × интерес» — стороны по влиянию на проект (решает ·
 * влияет · информируется) и силе 1–5; цвет — отношение. Ячейка пустая —
 * пустая: разбор предлагает влияние из формулировок, инженер правит карандашом.
 */
function СеткаВлияния({ project, стороны, onChanged }: {
  project: string
  стороны: EntityRow[]
  onChanged: () => void
}) {
  const колонки: [string, string][] = [['informed', 'информируется'], ['influences', 'влияет'], ['decides', 'решает']]
  const строки = [5, 4, 3, 2, 1]
  /**
   * Сила стороны на сетке: оценка человека, а без неё — предложение [П] из
   * роли по карте истины (журнал ПМИ-7, З-02: «сила ставится карандашом по
   * одной — 18 раз; сетка пустая»). Предложение серым; согласие — 0 действий;
   * правка — кликом по ячейке: выбранная сторона переносится в неё.
   */
  const сила = (с: EntityRow): number | null => {
    const своя = Number(с.doc.power)
    if (своя >= 1 && своя <= 5) return своя
    const п = Number((с as EntityRow & { power_proposed?: number }).power_proposed)
    return п >= 1 && п <= 5 ? п : null
  }
  const предложена = (с: EntityRow) => !(Number(с.doc.power) >= 1) && сила(с) !== null
  const без = стороны.filter((с) => !с.doc.influence || сила(с) === null)
  const тон = (attitude: unknown) => attitude === 'supports' ? 'v2-ok' : attitude === 'resists' ? 'v2-warn' : 'v2-muted'
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занята, setЗанята] = useState<string | null>(null)
  /** Сторона, выбранная для переноса: следующий клик по ячейке ставит ей силу и влияние. */
  const [выбрана, setВыбрана] = useState<string | null>(null)

  /**
   * Сила стороны — оценка человека 1–5 (истина: «power: оценка человека 1–5
   * на сцене 3; по умолчанию пусто»). Влияние считает система по роли, и
   * инженер правит его на месте — здесь же, где смотрит на матрицу: до 18.09
   * сетка отправляла «задать в сцене 3 карандашом», и владелец видел пустую
   * матрицу из восемнадцати сторон (проход владельца).
   */
  const поправить = (с: EntityRow, поля: Record<string, unknown>) => {
    setЗанята(с.code); setОтказ(null)
    api.patchEntity(project, с.code, поля, 'инженер', 'оценка стороны на матрице влияния')
      .then(() => onChanged())
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанята(null))
  }

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Влияние × сила</span>
        <span className="v2-card__count">{стороны.length}</span>
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-empty__why">
        {выбрана
          ? `выбрана ${выбрана}: кликните по ячейке — сторона переедет туда (сила и влияние запишутся)`
          : 'серым — сила предложена по роли [П]: согласие ничего не требует; чтобы перенести сторону, кликните по ней, затем по ячейке'}
      </div>
      <table className="v2-table">
        <thead><tr><th>сила</th>{колонки.map(([k, t]) => <th key={k}>{t}</th>)}</tr></thead>
        <tbody>
          {строки.map((балл) => (
            <tr key={балл}>
              <td className="v2-mono">{балл}</td>
              {колонки.map(([k]) => (
                <td key={k}
                  role={выбрана ? 'button' : undefined}
                  aria-label={выбрана ? `перенести ${выбрана}: сила ${балл}, ${k}` : undefined}
                  className={выбрана ? 'v2-row--cur' : undefined}
                  onClick={() => {
                    if (!выбрана) return
                    const с = стороны.find((x) => x.code === выбрана)
                    setВыбрана(null)
                    if (с) поправить(с, { power: String(балл), influence: k })
                  }}>
                  {стороны.filter((с) => с.doc.influence === k && сила(с) === балл).map((с) => (
                    <button key={с.id} type="button" className={`v2-link ${тон(с.doc.attitude)}`}
                      aria-pressed={выбрана === с.code}
                      title={`${с.code} · ${String(с.doc.role ?? '')}${с.doc.attitude ? ` · ${String(с.doc.attitude)}` : ''}${предложена(с) ? ' · сила предложена по роли' : ''} — кликните, чтобы перенести`}
                      onClick={(e) => { e.stopPropagation(); setВыбрана(выбрана === с.code ? null : с.code) }}>
                      {предложена(с) ? <span className="v2-dim">[П] {String(с.doc.name ?? с.code)}</span> : String(с.doc.name ?? с.code)}
                    </button>
                  ))}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {без.length > 0 && (
        <>
          <div className="v2-hint">
            Без влияния или силы: {без.length} — у роли без карты силы предложения нет; влияние
            считает система по роли и правится здесь же.
          </div>
          <table className="v2-table">
            <thead><tr><th>Сторона</th><th>Роль</th><th>Влияние</th><th>Сила</th></tr></thead>
            <tbody>
              {без.map((с) => (
                <tr key={с.id}>
                  <td><span className="v2-mono">{с.code}</span> {String(с.doc.name ?? '')}</td>
                  <td className="v2-dim">{String(с.doc.role ?? '—')}</td>
                  <td>
                    <select value={String(с.doc.influence ?? '')} disabled={занята === с.code}
                      title="влияние считает система по роли; инженер правит на месте"
                      onChange={(e) => поправить(с, { influence: e.target.value })}>
                      <option value="">— не задано —</option>
                      {колонки.map(([k, t]) => <option key={k} value={k}>{t}</option>)}
                    </select>
                  </td>
                  <td>
                    <select value={String(с.doc.power ?? '')} disabled={занята === с.code}
                      title="сила 1–5 — оценка человека; матрица без неё сторону не разместит"
                      onChange={(e) => поправить(с, { power: e.target.value })}>
                      <option value="">— не задана —</option>
                      {строки.map((n) => <option key={n} value={String(n)}>{n}</option>)}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}

/**
 * Куда идти, чтобы разрыв закрылся. Строка матрицы называет не только «чего
 * нет», но и место работы: носитель — сцена 3, цель — 4, сервис — 6, а разом
 * по всем нуждам — «Раздача нужд» в поле знаний (владелец 18.09: «нельзя
 * ничего отредактировать»).
 */
function кудаИдти(н: CoverageNeed): string {
  if (!н.owner) return 'закрывается на сцене 3: назначьте носителя нужде'
  if (н.goals.length === 0 && н.services.length === 0) {
    return 'закрывается на сценах 4 и 6 — или разом: «Поле знаний» → «Постановка из поля» → «Раздать нужды по целям и сервисам»'
  }
  if (н.goals.length === 0) return 'закрывается на сцене 4: свяжите нужду с целью — или раздачей нужд в поле знаний'
  return 'закрывается на сцене 6: свяжите нужду с сервисом — или раздачей нужд в поле знаний'
}

export function Coverage({ project }: { project: string | null }) {
  const [стороны, setСтороны] = useState<EntityRow[]>([])
  const [матрица, setМатрица] = useState<CoverageMatrix | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    if (!project) return
    api.coverage(project).then(setМатрица).catch((e) => setОтказ(String(e.message ?? e)))
    api.entities(project, 'stakeholder').then((r) => setСтороны(r.items)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">
          Проект не выбран.
          <span className="v2-empty__why">Матрица считается по связям проекта.</span>
        </div>
      </div>
    )
  }
  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!матрица) return <div className="v2-card"><div className="v2-empty">Считаю покрытие…</div></div>

  return (
    <>
      <СеткаВлияния project={project} стороны={стороны} onChanged={перечитать} />
      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Покрытие нужд</span>
          <span className="v2-card__count">{матрица.covered} из {матрица.total}</span>
        </div>
        <p className="v2-empty__why">{матрица.summary}</p>
        {матрица.needs.length > 0 && (
          <table className="v2-table">
            <thead>
              <tr><th>Нужда</th><th>Чья</th><th>Цели</th><th>Сервисы</th><th>Состояние</th></tr>
            </thead>
            <tbody>
              {матрица.needs.map((н) => (
                <tr key={н.code}>
                  <td>
                    <span className="v2-mono">{н.code}</span> {н.statement}
                  </td>
                  <td>{н.owner ?? <span className="v2-warn">ничья</span>}</td>
                  <td>{н.goals.length === 0 ? '—' : н.goals.join('; ')}</td>
                  <td>
                    {н.services.length === 0
                      ? (н.expected === 'service' ? '—' : <span className="v2-dim" title="истина: сервиса ждёт не всякая нужда">не ждёт</span>)
                      : н.services.join('; ')}
                  </td>
                  <td>
                    {н.covered
                      ? <span className="v2-ok">покрыта</span>
                      : <span className="v2-warn">{н.gap}</span>}
                    {н.note && <div className="v2-dim">{н.note}</div>}
                    {!н.covered && (
                      <div className="v2-dim" data-why="следующий-клик">{кудаИдти(н)}</div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {матрица.stakeholders_without_needs.length > 0 && (
        <div className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">Стороны без нужд</span>
            <span className="v2-card__count">{матрица.stakeholders_without_needs.length}</span>
          </div>
          <p className="v2-empty__why">
            Эти стороны названы, но чего они хотят — не записано. Пока так, сцена 3 не закроется.
          </p>
          <ul className="v2-why">
            {матрица.stakeholders_without_needs.map((с) => <li key={с}>{с}</li>)}
          </ul>
        </div>
      )}
    </>
  )
}
