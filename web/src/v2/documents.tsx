// Экран «Документы» (ЗАДАНИЕ-ДОКУМЕНТЫ-СЕЙЧАС §3).
//
// Минимум по `ДИЗАЙН-ПРИНЦИПЫ-V2`: список документов с полнотой к ступени,
// открытие — документ целиком разделами, печать — файлом с сервера. Ни
// панели истории, ни схемы: контекст живёт в шапке.
//
// Пустой раздел не молчит: он говорит, каких сцен ждёт, — по этой строке
// владелец проверяет проход, не открывая ни одной формы.
import { useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { api, ServerRefusal, type DocSection, type DocView, type VerificationReport } from './api'
import { запомнитьАвтора, отказСловами, прочитатьАвтора } from './research'

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

  if (!project) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>

  if (открыт) {
    return (
      <DocumentBody project={project} code={открыт.code}
        onClose={() => { setОткрыт(null); перечитать() }} />
    )
  }

  return (
    <div className="v2-panel" data-why="следующий-клик">
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
  /** Место находки верификации, к которому перешли: элемент раздела. */
  const [подсвечен, setПодсвечен] = useState<string | null>(null)

  const перечитать = () => {
    api.document(project, code)
      .then((d) => { setВид(d); if (!куда) setКуда(d.sections[0]?.no ?? '') })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }
  useEffect(перечитать, [project, code])

  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="работа"><div className="v2-empty">Читаю документ…</div></div>

  const разделы = section ? вид.sections.filter((р) => р.no === section) : вид.sections

  return (
    <div className="v2-panel" data-why="работа">
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
      {!section && (
        <FieldCheck project={project} code={code} вид={вид} onJump={(элемент) => {
          const адрес = элемент.split('#')[0]
          setПодсвечен(адрес)
          // Переход «к месту»: находка названа адресом элемента, а не
          // документом вообще, — экран и открывает именно его.
          window.setTimeout(
            () => window.document.getElementById(`v2-el-${адрес}`)?.scrollIntoView({ block: 'center' }),
            0,
          )
        }} />
      )}
      {разделы.map((р) => <Section key={р.no} раздел={р} подсвечен={подсвечен} />)}
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

function Section({ раздел, подсвечен }: { раздел: DocSection; подсвечен?: string | null }) {
  return (
    <div className="v2-doc__sec">
      <div className="v2-doc__h">
        <b>{раздел.no} {раздел.title}</b>
        <span className={раздел.complete ? 'v2-st v2-st--done' : 'v2-st'}>
          {раздел.complete ? 'раздел полон' : раздел.waiting.join('; ')}
        </span>
      </div>
      {раздел.elements.map((э) => (
        <div key={э.code} id={`v2-el-${э.code}`}
          className={подсвечен === э.code ? 'v2-doc__el v2-row--cur' : 'v2-doc__el'}>
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

/**
 * Проверка документа против поля знаний (ЗНАНИЯ-V2, мера ПМИ-6 п. 2.10).
 *
 * Отчёт — снимок расхождений, а не список правок: кнопки «исправить всё»
 * здесь нет и быть не может. Проверка не меняет ни версию документа, ни
 * версии сущностей, ни диспозиции фактов — «правок молча: 0». Всё, что
 * отчёт умеет, — довести человека до места: к элементу раздела, к его
 * опорам (факты, на которых он держится) и в замечания обзора. Сущность и
 * материал находка называет СЛОВАМИ в объяснении, кодов в отчёте нет —
 * поэтому ссылок на них здесь тоже нет: выдумывать адрес нельзя.
 *
 * Счётчики по родам расхождения и слова рода приходят с сервера: клиент их
 * не считает и второго словаря не заводит.
 */
function FieldCheck({ project, code, вид, onJump }: {
  project: string
  code: string
  вид: DocView
  onJump: (element: string) => void
}) {
  const [отчёты, setОтчёты] = useState<VerificationReport[] | null>(null)
  const [открыт, setОткрыт] = useState<string | null>(null)
  const [раскрыта, setРаскрыта] = useState(false)
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Поле знаний v2 на проекте выключено: слова сервера показываются как есть. */
  const [выключено, setВыключено] = useState<string | null>(null)
  const [подпись, setПодпись] = useState<string | null>(null)
  const [автор, setАвтор] = useState(прочитатьАвтора)
  const [ask, askConfirm, closeConfirm] = useConfirm()

  const перечитать = () => {
    setОтказ(null)
    setВыключено(null)
    api.verification(project, code)
      .then((r) => setОтчёты(r.items))
      .catch((e) => {
        setОтчёты([])
        if (e instanceof ServerRefusal && e.status === 409) setВыключено(e.body.what_to_do ?? e.message)
        else setОтказ(отказСловами(e))
      })
  }

  useEffect(() => { if (раскрыта) перечитать() }, [project, code, раскрыта])

  const все = отчёты ?? []
  const отчёт = все.find((о) => о.code === открыт) ?? (все.length > 0 ? все[все.length - 1] : null)
  const безАвтора = !автор.trim()

  /** Слово рода — из находки того же рода: второго словаря вердиктов не бывает. */
  const словоРода = (род: string) => отчёт?.items.find((п) => п.issue === род)?.issue_word ?? род

  /** Опоры места: факты, на которых держится элемент раздела, — их называет сам документ. */
  const опоры = (element: string) => {
    const адрес = element.split('#')[0]
    return вид.sections.flatMap((р) => р.elements).find((э) => э.code === адрес)?.supports ?? []
  }

  const проверить = () => {
    setЗанято(true)
    setОтказ(null)
    setПодпись(null)
    api.verifyDocument(project, code, автор)
      .then((о) => { setОткрыт(о.code); setПодпись(о.summary); перечитать() })
      .catch((e) => setОтказ(отказСловами(e)))
      .finally(() => setЗанято(false))
  }

  const вЗамечания = (номер: number) => {
    if (!отчёт) return
    setЗанято(true)
    setОтказ(null)
    api.verificationFinding(project, отчёт.code, номер, { author: автор })
      .then((з) => setПодпись(`замечание ${з.code} заведено · возврат в сцену ${з.scene}`))
      .catch((e) => setОтказ(отказСловами(e)))
      .finally(() => setЗанято(false))
  }

  const закрыть = () => {
    if (!отчёт) return
    askConfirm({
      question: `Закрыть отчёт ${отчёт.code}: находок ${отчёт.total}. Закрытие — решение человека, `
        + 'а не срок давности: расхождения останутся в отчёте как были.',
      ok: 'Закрыть отчёт',
      input: { label: 'почему закрываем', placeholder: 'разошлись по замечаниям обзора', required: true },
      onOk: (причина) => {
        setЗанято(true)
        api.closeVerification(project, отчёт.code, автор, причина)
          .then(() => { setПодпись(`отчёт ${отчёт.code} закрыт`); перечитать() })
          .catch((e) => setОтказ(отказСловами(e)))
          .finally(() => setЗанято(false))
      },
    })
  }

  return (
    <>
      <div className="v2-prop" data-why="почему-нельзя"
        title="сверить тезисы и числа документа с полем знаний: отчёт расхождений, ни одной правки">
        <span>
          Документ против поля
          {отчёт ? <span className="v2-cnt"> {отчёт.code} · {отчёт.summary}</span> : null}
        </span>
        <button type="button" className="v2-link"
          title={раскрыта ? 'свернуть проверку' : 'проверить тезисы и числа против поля знаний'}
          onClick={() => setРаскрыта(!раскрыта)}>
          {раскрыта ? 'свернуть' : 'Проверить против поля →'}
        </button>
      </div>

      {раскрыта && (
        <div className="v2-form" data-why="почему-нельзя">
          {выключено && <div className="v2-locked">{выключено}</div>}
          {отказ && <div className="v2-locked">{отказ}</div>}
          {!выключено && (
            <>
              <div className="v2-form v2-form--row">
                <label title="проверку заводит названный человек: пустого автора сервер не принимает">кто проверяет
                  <input value={автор} placeholder="Иванов"
                    onChange={(e) => { const имя = e.target.value; setАвтор(имя); запомнитьАвтора(имя) }} />
                </label>
                <button type="button" className="v2-primary" disabled={занято || безАвтора}
                  title={безАвтора
                    ? 'назовите себя: проверку заводит человек'
                    : 'новый отчёт: тезисы без оснований, числа против фактов, противоречия и устаревшее'}
                  onClick={проверить}>Проверить против поля</button>
                {все.length > 1 && (
                  <label title="история расхождения: каждая проверка заводит свой отчёт">отчёт
                    <select value={отчёт?.code ?? ''} onChange={(e) => setОткрыт(e.target.value)}>
                      {все.map((о) => (
                        <option key={о.code} value={о.code}>{о.code} · {о.at.slice(0, 10)}</option>
                      ))}
                    </select>
                  </label>
                )}
                {подпись && <span className="v2-dim">{подпись}</span>}
              </div>

              {отчёт && (
                <>
                  <div className="v2-note-line">
                    {отчёт.summary}
                    {отчёт.baseline_name ? ` · против базирования «${отчёт.baseline_name}»` : ''}
                    {отчёт.open ? '' : ' · отчёт закрыт'}
                  </div>
                  <div className="v2-form v2-form--row">
                    {Object.entries(отчёт.by_issue).map(([род, сколько]) => (
                      <span key={род} className="v2-chip" title="счёт по роду расхождения считает сервер">
                        {словоРода(род)}: {сколько}
                      </span>
                    ))}
                    <span className="v2-chip" title="всего находок в отчёте">всего: {отчёт.total}</span>
                  </div>
                  <div className="v2-empty__why">
                    Отсюда ничего не правится: находка — повод решить в поле знаний или в обзоре,
                    а документ молча не меняется.
                  </div>
                  <ul className="v2-checks">
                    {отчёт.items.map((п) => (
                      <li key={п.n} className="v2-check v2-check--no">
                        <span>☐</span>
                        <span className="v2-check__t">
                          <span className="v2-mono">{п.n}</span> {п.issue_word}: {п.detail}
                          {п.proposal && <div className="v2-muted">что делать: {п.proposal}</div>}
                          <div className="v2-dim">
                            место: <span className="v2-mono">{п.element}</span>
                            {опоры(п.element).length > 0
                              ? <span> · опоры: {опоры(п.element).join(' · ')}</span>
                              : <span> · опор у места нет</span>}
                          </div>
                        </span>
                        <button type="button" className="v2-link"
                          title="открыть место находки в документе — тот самый элемент раздела"
                          onClick={() => onJump(п.element)}>к месту</button>
                        <button type="button" className="v2-link" disabled={занято || безАвтора || !отчёт.open}
                          title={безАвтора ? 'назовите себя: замечание заводит человек'
                            : !отчёт.open ? 'отчёт закрыт: перенос находок из него больше не идёт'
                              : 'перенести находку в замечания обзора — возврат в сцену раздела'}
                          onClick={() => вЗамечания(п.n)}>в замечания обзора</button>
                      </li>
                    ))}
                    {отчёт.items.length === 0 && (
                      <li className="v2-empty">
                        Находок нет.
                        <span className="v2-empty__why">{отчёт.summary}</span>
                      </li>
                    )}
                  </ul>
                  <div className="v2-form__actions">
                    <button type="button" disabled={занято || безАвтора || !отчёт.open}
                      title={безАвтора ? 'назовите себя: закрывает отчёт человек'
                        : !отчёт.open ? 'отчёт уже закрыт' : 'закрыть отчёт с причиной'}
                      onClick={закрыть}>Закрыть отчёт</button>
                  </div>
                </>
              )}
              {отчёты !== null && все.length === 0 && (
                <div className="v2-empty">
                  Документ против поля ещё не проверяли.
                  <span className="v2-empty__why">
                    Проверка идёт после базирования: она показывает расхождения, а правит их человек.
                  </span>
                </div>
              )}
            </>
          )}
        </div>
      )}
      <ConfirmBox request={ask} onClose={closeConfirm} />
    </>
  )
}
