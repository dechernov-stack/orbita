// Проверка документа против поля знаний — эксперт-режим (шип 5 §5).
import { useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, ServerRefusal, type DocView, type VerificationReport } from '../api'
import { useАвтор, отказСловами } from '../research'

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
export function FieldCheck({ project, code, вид, onJump }: {
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
  const [автор, setАвтор] = useАвтор()
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
                    onChange={(e) => setАвтор(e.target.value)} />
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
