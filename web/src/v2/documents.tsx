// Экран «Документы» (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС §3).
//
// Минимум по `ДИЗАЙН-ПРИНЦИПЫ-V2`: список документов с полнотой к ступени,
// открытие — документ целиком разделами, печать — файлом с сервера. Ни
// панели истории, ни схемы: контекст живёт в шапке.
//
// Пустой раздел не молчит: он говорит, каких сцен ждёт, — по этой строке
// владелец проверяет проход, не открывая ни одной формы.
import { useEffect, useState } from 'react'
import { api, type DocSection, type DocView } from './api'

export function Documents({ project }: { project: string | null }) {
  const [список, setСписок] = useState<DocView[] | null>(null)
  const [открыт, setОткрыт] = useState<DocView | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = () => {
    if (!project) return
    api.documents(project)
      .then((r) => setСписок(r.items))
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  useEffect(перечитать, [project])

  if (!project) return <div className="v2-panel"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel"><div className="v2-locked">{отказ}</div></div>

  if (открыт) {
    return (
      <DocumentBody project={project} code={открыт.code}
        onClose={() => { setОткрыт(null); перечитать() }} />
    )
  }

  return (
    <div className="v2-panel">
      <h3>
        Документы
        <span className="v2-cnt">
          {список === null ? 'читаю…' : `${список.length} в проекте`}
        </span>
      </h3>
      {список !== null && список.length === 0 && (
        <div className="v2-empty">
          Документов нет.
          <button type="button" className="v2-link"
            title="завести отчёт о концепции миссии по шаблону полки"
            onClick={() => project && api.ensureDocument(project, 'mcreport', 'инженер')
              .then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))}>
            {' '}завести MCReport
          </button>
        </div>
      )}
      <div className="v2-list">
        {(список ?? []).map((д) => (
          <button key={д.code} type="button" className="v2-row v2-row--doc"
            title={`${д.standard} — открыть документ разделами`}
            onClick={() => setОткрыт(д)}>
            <span>{д.title}</span>
            <span className="v2-dim">{д.standard}</span>
            <span className={д.complete === д.total ? 'v2-st v2-st--done' : 'v2-st'}>
              {д.complete} из {д.total} разделов полны
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

/** Документ целиком: разделы с элементами так, как они напечатаются. */
export function DocumentBody({ project, code, section, onClose }: {
  project: string
  code: string
  /** Открыть только один раздел — переход из мероприятия. */
  section?: string
  onClose?: () => void
}) {
  const [вид, setВид] = useState<DocView | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [тезис, setТезис] = useState('')
  const [куда, setКуда] = useState(section ?? '')

  const перечитать = () => {
    api.document(project, code)
      .then((d) => { setВид(d); if (!куда) setКуда(d.sections[0]?.no ?? '') })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }
  useEffect(перечитать, [project, code])

  if (отказ) return <div className="v2-panel"><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel"><div className="v2-empty">Читаю документ…</div></div>

  const разделы = section ? вид.sections.filter((р) => р.no === section) : вид.sections

  return (
    <div className="v2-panel">
      <h3>
        {вид.title}
        <span className="v2-cnt">
          {вид.standard} · полнота {вид.complete} из {вид.total}
          {'  '}
          <a className="v2-link" href={api.printUrl(project, code)} target="_blank" rel="noreferrer"
            title="печать: файл собирает сервер теми же строками, что на экране">печать</a>
          {onClose && (
            <>
              {'  '}
              <button type="button" className="v2-link" title="к списку документов"
                onClick={onClose}>← к списку</button>
            </>
          )}
        </span>
      </h3>
      {разделы.map((р) => <Section key={р.no} раздел={р} />)}
      {!section && (
        <div className="v2-doc__add">
          <select value={куда} onChange={(e) => setКуда(e.target.value)}
            title="раздел, в который встанет тезис">
            {вид.sections.map((р) => <option key={р.no} value={р.no}>{р.no} {р.title}</option>)}
          </select>
          <input value={тезис} onChange={(e) => setТезис(e.target.value)}
            placeholder="тезис раздела — единственный свободный текст документа" />
          <button type="button" disabled={!тезис.trim()}
            title={тезис.trim() ? 'добавить тезис в раздел' : 'тезис пуст: писать нечего'}
            onClick={() => api.addStatement(project, code, { section: куда, text: тезис, author: 'инженер' })
              .then(() => { setТезис(''); перечитать() })
              .catch((e) => setОтказ(String(e.message ?? e)))}>
            Добавить тезис
          </button>
        </div>
      )}
    </div>
  )
}

function Section({ раздел }: { раздел: DocSection }) {
  return (
    <div className="v2-doc__sec">
      <div className="v2-doc__h">
        <b>{раздел.no} {раздел.title}</b>
        <span className={раздел.complete ? 'v2-st v2-st--done' : 'v2-st'}>
          {раздел.complete ? 'раздел полон' : раздел.waiting.join('; ')}
        </span>
      </div>
      {раздел.elements.map((э) => (
        <div key={э.code} className="v2-doc__el">
          {э.kind === 'statement' ? (
            <>
              <p className="v2-doc__st">{э.text}</p>
              {э.notes.map((н) => <div key={н} className="v2-warn">{н}</div>)}
            </>
          ) : (
            <>
              <div className="v2-dim">{э.title}</div>
              {э.rows.length === 0 ? (
                <div className="v2-empty">
                  Сведений нет.
                  <span className="v2-empty__why">
                    {э.waiting_scenes.length > 0
                      ? `Раздел ждёт сцен ${э.waiting_scenes.join(', ')}: пока они не прожиты, писать сюда нечего.`
                      : 'Запрос не дал строк.'}
                  </span>
                </div>
              ) : (
                <table className="v2-tab2">
                  <thead>
                    <tr>{э.columns.map((к) => <th key={к}>{к}</th>)}</tr>
                  </thead>
                  <tbody>
                    {э.rows.map((строка, i) => (
                      <tr key={i}>{строка.map((з, j) => <td key={j}>{з || '—'}</td>)}</tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </div>
      ))}
    </div>
  )
}
