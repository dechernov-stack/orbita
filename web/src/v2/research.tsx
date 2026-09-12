// Исследование полноты — внешний контур (ЗНАНИЯ-V2, мера ПМИ-6 п. 2.4).
//
// Панель одна на все места входа: сцена (3 стороны · 4 повестка · 5 нормы ·
// 7 аналоги · 12 бенчмарки) и точка (внутренний обзор · MCR) отличаются
// только полем `trigger`. Поэтому она и живёт отдельным файлом: экран
// мероприятия, экран точек и документы зовут ОДНУ панель, а не три похожие.
//
// Контур ручной и внешний: продукт собирает вопросы из среза поля, отдаёт
// промпт файлом ЧЕЛОВЕКУ, принимает принесённый результат материалом и ждёт,
// пока человек подтвердит источники. Модель здесь не зовётся ни разу —
// журнал ИИ после формулирования и запуска не прирастает.
//
// Ни один шаг цикла не делается сам: формулирование, выдача наружу, приём
// результата, разбор, подтверждение источников и следующий цикл — шесть
// отдельных нажатий человека. Счётчики принятого и состояние цикла приходят
// с сервера готовыми; здесь они только показываются.
import { useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import {
  api, ServerRefusal,
  type FactRow, type ResearchAccepted, type ResearchPlace, type ResearchTask,
} from './api'

/** Состояние цикла словами: служебное имя статуса человеку ничего не говорит. */
const СОСТОЯНИЕ: Record<string, string> = {
  formulated: 'вопросы сформулированы',
  launched: 'отдано во внешний контур',
  received: 'результат принят',
  parsed: 'результат разобран',
}

/** Пять классов итога — ровно те, по которым сервер считает принятое. */
const КЛАССЫ: [keyof ResearchAccepted, string][] = [
  ['stakeholders', 'стороны'],
  ['programs', 'программы'],
  ['norms', 'нормы'],
  ['applications', 'применения'],
  ['analogs', 'аналоги'],
]

const ИМЯ_АВТОРА = 'orbita.v2.author'

/** Имя человека живёт в браузере: наружу ходит названный человек, а не служба. */
export function прочитатьАвтора(): string {
  try {
    return localStorage.getItem(ИМЯ_АВТОРА) ?? ''
  } catch {
    // приватное окно или запрет хранилища: имя спросим заново, работа не встанет
    return ''
  }
}

export function запомнитьАвтора(имя: string): void {
  try {
    localStorage.setItem(ИМЯ_АВТОРА, имя)
  } catch {
    /* хранилище закрыто — имя живёт до перезагрузки страницы */
  }
}

const ИМЯ_РОЛИ = 'orbita.v2.role'

/** Роль в проекте: у руки эксперта якоря нет — есть учётка, роль и дата. */
export function прочитатьРоль(): string {
  try {
    return localStorage.getItem(ИМЯ_РОЛИ) ?? ''
  } catch {
    return ''
  }
}

export function запомнитьРоль(роль: string): void {
  try {
    localStorage.setItem(ИМЯ_РОЛИ, роль)
  } catch {
    /* хранилище закрыто — роль живёт до перезагрузки страницы */
  }
}

/**
 * Панель исследования полноты для одного места входа.
 *
 * @param trigger место: ровно одно из двух — сцена либо точка (истина схем
 *   `research_task.trigger_scene`; оба названных сервер отбивает словами)
 * @param onChanged принятое из результата меняет поле — вызывающий экран
 *   перечитывает свои списки
 */
export function ResearchPanel({ project, trigger, onChanged }: {
  project: string
  trigger: ResearchPlace
  onChanged?: () => void
}) {
  const [открыта, setОткрыта] = useState(false)
  const [задачи, setЗадачи] = useState<ResearchTask[] | null>(null)
  const [тик, setТик] = useState(0)
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Поле знаний v2 на проекте выключено: слова сервера показываются как есть. */
  const [выключено, setВыключено] = useState<string | null>(null)
  const [автор, setАвтор] = useState(прочитатьАвтора)
  const [черновик, setЧерновик] = useState<string[]>([])
  const [вопрос, setВопрос] = useState('')
  const [занято, setЗанято] = useState(false)
  const [подпись, setПодпись] = useState<string | null>(null)
  const [имяРезультата, setИмяРезультата] = useState('')
  const [текстРезультата, setТекстРезультата] = useState('')
  const [факты, setФакты] = useState<FactRow[]>([])
  const [выбраны, setВыбраны] = useState<string[]>([])
  const [ask, askConfirm, closeConfirm] = useConfirm()

  // Место — примитивами: объект `trigger` вызывающий экран собирает заново
  // на каждом приходе, и зависимость по нему гоняла бы чтение по кругу.
  const сцена = trigger.scene ?? ''
  const точка = trigger.gate ?? ''
  const свои = (задачи ?? []).filter((з) => (сцена ? з.trigger.scene === сцена : з.trigger.gate === точка))
  const задача = свои.length > 0 ? свои[свои.length - 1] : null
  const материал = задача?.result_material ?? ''

  useEffect(() => {
    if (!открыта) return
    setОтказ(null)
    setВыключено(null)
    api.research(project)
      .then((r) => setЗадачи(r.items))
      .catch((e) => {
        setЗадачи([])
        if (e instanceof ServerRefusal && e.status === 409) setВыключено(e.body.what_to_do ?? e.message)
        else setОтказ(String(e.message ?? e))
      })
  }, [project, сцена, точка, открыта, тик])

  // Факты результата — только своего материала: подтверждать чужое эта
  // задача не вправе, и сервер откажет; спрашивать нечего.
  useEffect(() => {
    if (!материал) { setФакты([]); return }
    api.facts(project)
      .then((r) => setФакты(r.items.filter((ф) => ф.material === материал)))
      .catch(() => setФакты([]))
  }, [project, материал, тик])

  const перечитать = () => { setТик((т) => т + 1); onChanged?.() }

  const шаг = (что: () => Promise<unknown>, слово: string) => {
    setЗанято(true)
    setОтказ(null)
    setПодпись(null)
    что()
      .then(() => { setПодпись(слово); перечитать() })
      .catch((e) => setОтказ(отказСловами(e)))
      .finally(() => setЗанято(false))
  }

  const сформулировать = () => шаг(
    () => api.formulateResearch(project, сцена ? { scene: сцена } : { gate: точка }, автор, черновик)
      .then((з) => { setЧерновик([]); return з }),
    'вопросы собраны',
  )

  const следующийЦикл = () => задача && шаг(
    () => api.nextResearch(project, задача.id, автор, черновик)
      .then((з) => { setЧерновик([]); return з }),
    'заведён следующий цикл по остаткам',
  )

  const отдать = () => задача && шаг(
    () => api.launchResearch(project, задача.id, автор),
    'отдано во внешний контур: задача ждёт результата',
  )

  const принять = () => задача && шаг(
    () => api.researchResult(project, задача.id, имяРезультата.trim(), текстРезультата, автор)
      .then((з) => { setИмяРезультата(''); setТекстРезультата(''); return з }),
    'результат принят материалом сомнительного ранга',
  )

  // Разбор результата — ОБЩИЙ путь загрузки фоновой задачей (ADR-069):
  // второго пути атомизации у исследования нет, и статус «разобрано» задача
  // выводит по фактам своего материала сама.
  const разобрать = () => {
    if (!материал) return
    setЗанято(true)
    setОтказ(null)
    const опрос = (job: string) => {
      api.atomizeJobStatus(project, job).then((з) => {
        if (з.status === 'done') { setПодпись(з.note ?? 'результат разобран'); setЗанято(false); перечитать() }
        else if (з.status === 'failed') { setОтказ(з.error ?? 'разбор не удался'); setЗанято(false) }
        else {
          setПодпись(`разбор идёт фоновой задачей ${з.job}: ${з.elapsed_seconds} с — страницу можно не держать`)
          window.setTimeout(() => опрос(job), 3000)
        }
      }).catch((e) => { setОтказ(отказСловами(e)); setЗанято(false) })
    }
    api.parseResearch(project, материал, 'разбери по сущностям', автор)
      .then((з) => {
        if (з.status === 'done') { setПодпись(з.note ?? 'результат разобран'); setЗанято(false); перечитать() }
        else if (з.status === 'failed') { setОтказ(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setПодпись(`разбор идёт фоновой задачей ${з.job} — стенд отвечает`); опрос(з.job) }
      })
      .catch((e) => { setОтказ(отказСловами(e)); setЗанято(false) })
  }

  const подтвердить = () => {
    if (!задача) return
    askConfirm({
      question: `Подтвердить источники: фактов ${выбраны.length}. Материал результата поднимется `
        + 'до справочного, а подтверждённые факты станут подтверждёнными вторым источником.',
      ok: 'Подтвердить',
      onOk: () => шаг(
        () => api.confirmSources(project, задача.id, выбраны, автор).then((з) => { setВыбраны([]); return з }),
        'источники подтверждены',
      ),
    })
  }

  const копировать = () => {
    if (!задача) return
    setПодпись(null)
    fetch(api.researchPromptUrl(project, задача.id))
      .then((r) => r.text())
      .then((t) => (navigator.clipboard
        ? navigator.clipboard.writeText(t)
        : Promise.reject(new Error('буфер недоступен'))))
      .then(() => setПодпись('промпт в буфере: вставьте его во внешний контур'))
      .catch(() => setПодпись('буфер закрыт браузером — скачайте файл .md'))
  }

  const снять = (что: string) => setЧерновик(черновик.filter((в) => в !== что))
  const дописать = () => {
    const текст = вопрос.trim()
    if (!текст) return
    setЧерновик([...черновик, текст])
    setВопрос('')
  }

  const безАвтора = !автор.trim()
  const итог = задача?.accepted
  const состояние = задача
    ? `${задача.place} · цикл ${задача.iteration} · ${СОСТОЯНИЕ[задача.status] ?? задача.status}`
    : `${сцена ? `сцена ${сцена}` : `точка ${точка}`} · исследования ещё не было`

  return (
    <>
      <div className="v2-prop" data-why="следующий-клик"
        title="внешний контур: вопросы из поля, промпт файлом человеку, результат — материалом сомнительного ранга">
        <span>Полнота по месту: {состояние}</span>
        <button type="button" className="v2-link"
          title={открыта ? 'свернуть панель исследования' : 'спросить у внешнего контура то, чего в поле нет'}
          onClick={() => setОткрыта(!открыта)}>
          {открыта ? 'свернуть' : 'Исследовать полноту →'}
        </button>
      </div>

      {открыта && (
        <div className="v2-form" data-why="работа">
          {выключено && <div className="v2-locked">{выключено}</div>}
          {отказ && <div className="v2-locked">{отказ}</div>}
          {!выключено && (
            <>
              <div className="v2-form v2-form--row">
                <label title="наружу ходит названный человек: пустого автора сервер не принимает">кто ведёт
                  <input value={автор} placeholder="Иванов"
                    onChange={(e) => { const имя = e.target.value; setАвтор(имя); запомнитьАвтора(имя) }} />
                </label>
                {задача && <span className="v2-dim">{задача.note}</span>}
              </div>

              <h4 className="v2-h4">Вопросы</h4>
              {задача && (
                <ul className="v2-checks">
                  {задача.questions.map((в) => (
                    <li key={в} className="v2-check">
                      <span>·</span>
                      <span className="v2-check__t">{в}</span>
                    </li>
                  ))}
                  {задача.questions.length === 0 && <li className="v2-empty">вопросов в задаче нет</li>}
                </ul>
              )}
              <div className="v2-form v2-form--row">
                <input value={вопрос} onChange={(e) => setВопрос(e.target.value)}
                  placeholder="сторона: кто ещё отвечает за перевозки опасных грузов?" />
                <button type="button" disabled={!вопрос.trim()}
                  title={вопрос.trim() ? 'дописать свой вопрос' : 'вопрос пуст: дописывать нечего'}
                  onClick={дописать}>Дописать вопрос</button>
                {задача && задача.questions.length > 0 && (
                  <button type="button" className="v2-link"
                    title="перенести вопросы цикла в черновик: снятое туда не пойдёт"
                    onClick={() => setЧерновик(задача.questions)}>перенести вопросы цикла</button>
                )}
              </div>
              {черновик.length > 0 && (
                <ul className="v2-checks">
                  {черновик.map((в) => (
                    <li key={в} className="v2-check">
                      <span>·</span>
                      <span className="v2-check__t">{в}</span>
                      <button type="button" className="v2-link" title="снять вопрос из черновика"
                        onClick={() => снять(в)}>снять</button>
                    </li>
                  ))}
                </ul>
              )}
              {черновик.length === 0 && (
                <div className="v2-empty__why">
                  Черновик пуст — поле соберёт вопросы само по пяти классам: сторона · программа ·
                  норма · применение · аналог. Шестого класса не бывает.
                </div>
              )}

              <div className="v2-form__actions">
                {!задача && (
                  <button type="button" className="v2-primary" disabled={занято || безАвтора}
                    title={безАвтора
                      ? 'назовите себя: вопросы задаёт человек, а не служба'
                      : 'собрать вопросы по срезу поля — вызова модели здесь нет'}
                    onClick={сформулировать}>Сформулировать вопросы</button>
                )}
                {задача && (
                  <>
                    <button type="button" className="v2-link" disabled={занято}
                      title="скопировать промпт целиком: файл собирает сервер"
                      onClick={копировать}>Копировать промпт</button>
                    <a className="v2-link" href={api.researchPromptUrl(project, задача.id)}
                      target="_blank" rel="noreferrer"
                      title="промпт файлом .md — его и отдают во внешний контур">Скачать .md</a>
                    <button type="button" className="v2-primary"
                      disabled={занято || безАвтора || задача.status !== 'formulated'}
                      title={безАвтора
                        ? 'назовите себя: наружу ходит человек'
                        : задача.status === 'formulated'
                          ? 'отметить, что промпт отдан наружу: задача станет ждать результата'
                          : `цикл уже ${СОСТОЯНИЕ[задача.status] ?? задача.status}: отдавать второй раз нечего`}
                      onClick={отдать}>Отдать во внешний контур</button>
                  </>
                )}
                {подпись && <span className="v2-dim">{подпись}</span>}
              </div>

              {задача && задача.status === 'launched' && (
                <>
                  <h4 className="v2-h4">Результат</h4>
                  <div className="v2-form v2-form--row">
                    <label>название
                      <input value={имяРезультата} onChange={(e) => setИмяРезультата(e.target.value)}
                        placeholder="Исследование полноты: стороны" />
                    </label>
                    <label title="txt · md · csv читаются в браузере; иной файл приложите текстом">файл
                      <input type="file" accept=".txt,.md,.csv,text/plain,text/markdown"
                        onChange={(e) => файлТекстом(e.target.files?.[0] ?? null,
                          (имя, текст) => {
                            if (!имяРезультата.trim()) setИмяРезультата(имя)
                            setТекстРезультата(текст)
                          },
                          () => setОтказ('файл не прочитался: приложите текстовый (.txt · .md · .csv)'))} />
                    </label>
                  </div>
                  <label>текст результата
                    <textarea rows={4} value={текстРезультата}
                      onChange={(e) => setТекстРезультата(e.target.value)}
                      placeholder="вставьте ответ внешнего контура — со ссылками на источники" />
                  </label>
                  <div className="v2-form__actions">
                    <button type="button" className="v2-primary"
                      disabled={занято || безАвтора || !имяРезультата.trim() || !текстРезультата.trim()}
                      title={безАвтора ? 'назовите себя: результат принёс человек'
                        : !имяРезультата.trim() ? 'дайте результату название: материал без имени в поле не ляжет'
                          : !текстРезультата.trim() ? 'результат пуст: принимать нечего'
                            : 'принять результат материалом — ранг сомнительный до подтверждения источников'}
                      onClick={принять}>Принять результат</button>
                  </div>
                </>
              )}

              {материал && (
                <>
                  <h4 className="v2-h4">
                    Источники результата
                    <span className="v2-cnt"> материал {материал} · фактов {факты.length}</span>
                  </h4>
                  <div className="v2-form__actions">
                    <button type="button" disabled={занято || факты.length > 0}
                      title={факты.length > 0
                        ? 'факты результата уже в поле: разбирать второй раз нечего'
                        : 'разобрать материал результата общим путём загрузки — фоновой задачей'}
                      onClick={разобрать}>Разобрать результат</button>
                  </div>
                  <ul className="v2-checks">
                    {факты.map((ф) => (
                      <li key={ф.id} className="v2-check">
                        <input type="checkbox" checked={выбраны.includes(ф.id)}
                          title="подтвердить источник этого факта"
                          onChange={(e) => setВыбраны(e.target.checked
                            ? [...выбраны, ф.id]
                            : выбраны.filter((x) => x !== ф.id))} />
                        <span className="v2-check__t">
                          {ф.subject} · {ф.predicate} · {ф.value}
                          {ф.anchor && <span className="v2-dim"> · якорь {ф.anchor}</span>}
                        </span>
                      </li>
                    ))}
                    {факты.length === 0 && (
                      <li className="v2-empty">
                        Фактов результата нет.
                        <span className="v2-empty__why">Пока материал не разобран, подтверждать нечего.</span>
                      </li>
                    )}
                  </ul>
                  <div className="v2-form__actions">
                    <button type="button" className="v2-primary"
                      disabled={занято || безАвтора || выбраны.length === 0}
                      title={безАвтора ? 'назовите себя: источники смотрел человек'
                        : выбраны.length === 0 ? 'отметьте факты, источники которых вы проверили'
                          : 'поднять материал до справочного, а отмеченные факты — до подтверждённых'}
                      onClick={подтвердить}>Подтвердить источники</button>
                  </div>
                </>
              )}

              {итог && (
                <>
                  <h4 className="v2-h4">
                    Принято из результата
                    <span className="v2-cnt"> всего {задача?.accepted_total}</span>
                  </h4>
                  <div className="v2-note-line">
                    {КЛАССЫ.map(([ключ, слово]) => `${слово}: ${итог[ключ]}`).join(' · ')}
                  </div>
                  {задача && задача.open.length > 0 && (
                    <div className="v2-empty__why">
                      Ответа нет по классам: {задача.open.join(' · ')} — ими пойдёт следующий цикл.
                    </div>
                  )}
                  {задача && (
                    <div className="v2-form__actions">
                      <button type="button" disabled={занято || безАвтора || задача.open.length === 0}
                        title={безАвтора ? 'назовите себя: цикл продолжает человек'
                          : задача.open.length === 0
                            ? 'принято по всем пяти классам: следующего цикла не нужно — исследование окончено'
                            : 'спросить по остаткам: классы без ответа плюс дописанное в черновике'}
                        onClick={следующийЦикл}>Следующий цикл по остаткам</button>
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </div>
      )}
      <ConfirmBox request={ask} onClose={closeConfirm} />
    </>
  )
}

/** Текстовый файл читается В БРАУЗЕРЕ: результат уходит на сервер текстом. */
function файлТекстом(
  файл: File | null,
  готово: (имя: string, текст: string) => void,
  ошибка: () => void,
): void {
  if (!файл) return
  файл.text()
    .then((текст) => готово(файл.name.replace(/\.[^.]+$/, ''), текст))
    .catch(ошибка)
}

/** Отказ сервера словами: причина и «что делать» показываются как есть. */
export function отказСловами(e: unknown): string {
  if (e instanceof ServerRefusal) {
    const что = e.body.what_to_do ? ` — ${e.body.what_to_do}` : ''
    return `${e.message}${что}`
  }
  return String((e as Error)?.message ?? e)
}
