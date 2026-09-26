// Документ (шип 5 §5): слева список разделов 220 px — номер · имя · маркер
// полноты · «их нет» словом, справа тело выбранного раздела. Шапка — одна
// строка: заголовок, стандарт, полнота к ступени, печать пиктограммой и
// словом; PDF собирается фоновым заданием с секундами вслух. Базирование —
// панелью под шапкой; проверка против поля — только в эксперт-режиме.
//
// Для мероприятия (поверхность сцены) документ открывается одним разделом —
// без списка: работа идёт в нём, а не по всему документу.
import { useEffect, useState } from 'react'
import { api, type DocumentJob, type DocView, type RenderedSection } from '../api'
import { ИконКнопка } from '../ui/iconbutton'
import { Маркер } from '../ui/tabs'
import { Baselines } from './baselines'
import { FieldCheck } from './fieldcheck'
import { Раздел, закрытТезисомНет } from './section'

/** «не хватает 1 раздела · 2 разделов»: счёт в тексте читается словом. */
function склонение(сколько: number): string {
  const сто = сколько % 100
  if (сто >= 11 && сто <= 14) return 'разделов'
  return [5, 6, 7, 8, 9, 0].includes(сколько % 10) ? 'разделов' : сколько % 10 === 1 ? 'раздела' : 'разделов'
}

export function DocumentBody({ project, code, section, onClose, onGoScene, onGoField, expert = false }: {
  project: string
  code: string
  /** Открыть только один раздел — переход из мероприятия. */
  section?: string
  onClose?: () => void
  /** Переход «к месту»: сцена, которой раздел наполняется, и зачем идём. */
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Переход в поле знаний: там заводятся темы — открытые вопросы фазы. */
  onGoField?: () => void
  /** Эксперт-режим: проверка документа против поля видна только в нём. */
  expert?: boolean
}) {
  const [вид, setВид] = useState<DocView | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [тезис, setТезис] = useState('')
  const [выбран, setВыбран] = useState(section ?? '')
  /** Место находки верификации, к которому перешли: элемент раздела. */
  const [подсвечен, setПодсвечен] = useState<string | null>(null)
  /** Связные тексты разделов, уже написанные моделью и принятые сторожем. */
  const [тексты, setТексты] = useState<Record<string, RenderedSection>>({})
  /** Печать фоновым заданием (шип 4 §4): Typst собирает PDF, экран считает секунды и даёт ссылку. */
  const [печать, setПечать] = useState<{ job: string; sec: number; готово?: DocumentJob; ошибка?: string } | null>(null)
  const собратьPdf = () => {
    api.printJob(project, code, 'auto').then((з) => {
      setПечать({ job: з.job, sec: 0 })
      const опрос = () => {
        api.documentJob(project, code, з.job).then((т) => {
          if (т.status === 'done') setПечать({ job: з.job, sec: т.elapsed_seconds, готово: т })
          else if (т.status === 'failed') setПечать({ job: з.job, sec: т.elapsed_seconds, ошибка: т.error ?? 'печать не состоялась' })
          else { setПечать({ job: з.job, sec: т.elapsed_seconds }); window.setTimeout(опрос, 2000) }
        }).catch((e) => setПечать({ job: з.job, sec: 0, ошибка: String(e.message ?? e) }))
      }
      window.setTimeout(опрос, 1500)
    }).catch((e) => setПечать({ job: '', sec: 0, ошибка: String(e.message ?? e) }))
  }
  useEffect(() => {
    api.renderings(project, code)
      .then((о) => setТексты(Object.fromEntries(о.items.filter((т) => т.accepted).map((т) => [т.section, т]))))
      .catch(() => setТексты({}))
  }, [project, code])

  const перечитать = () => {
    api.document(project, code)
      .then((d) => { setВид(d); setВыбран((было) => было || (d.sections.find((р) => р.due_now && !р.complete) ?? d.sections[0])?.no || '') })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }
  useEffect(перечитать, [project, code])

  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="работа"><div className="v2-empty">Читаю документ…</div></div>

  const раздел = вид.sections.find((р) => р.no === выбран) ?? вид.sections[0]
  const раздел_ = (р: typeof раздел) => (
    <Раздел key={р.no} раздел={р} подсвечен={подсвечен} onGoScene={onGoScene} onGoField={onGoField}
      project={project} code={code} написанное={тексты[р.no] ?? null}
      onНаписано={(т) => { if (т.accepted) setТексты((тт) => ({ ...тт, [р.no]: т })) }}
      onЗакрытьСловами={(номер, текст) => { setВыбран(номер); setТезис(текст) }} />
  )
  // Мероприятие открывает документ одним разделом: без списка и шапки документа.
  if (section) {
    const один = вид.sections.find((р) => р.no === section)
    return <div className="v2-panel" data-why="работа">{один ? раздел_(один) : <div className="v2-empty">Раздела {section} в документе нет.</div>}</div>
  }
  /** Разделы, которых ступень ждёт, а они не полны: из них и складывается счёт. */
  const неполные = вид.sections.filter((р) => р.due_now && !р.complete)

  return (
    <div className="v2-panel" data-why="работа" aria-label={`документ ${вид.title}`}>
      <div className="v2-doc__head">
        <b>{вид.title}</b>
        <span className="v2-dim">
          {вид.standard} · полнота {вид.complete} из {вид.total} к {вид.gate}
          {вид.not_due_yet > 0 && ` · ещё не ждут: ${вид.not_due_yet}`}
        </span>
        <span className="v2-chips__sp" />
        <a className="v2-link" href={api.printUrl(project, code)} target="_blank" rel="noreferrer"
          title="печать: файл собирает сервер теми же строками, что на экране">печать</a>
        <ИконКнопка икон="печать" сословом className="v2-btn" disabled={печать !== null && !печать.готово && !печать.ошибка}
          слово={печать && !печать.готово && !печать.ошибка ? `собираю PDF… ${печать.sec} с` : 'собрать PDF'} onClick={собратьPdf} />
        {печать?.готово && (
          <a className="v2-link" href={api.printUrl(project, code, { job: печать.job })} target="_blank" rel="noreferrer"
            title={`движок ${печать.готово.engine ?? '—'} · ${печать.готово.size} байт`}>скачать PDF ({печать.готово.engine ?? 'pdf'})</a>
        )}
        {печать?.ошибка && <span className="v2-warn">{печать.ошибка}</span>}
        {onClose && <ИконКнопка икон="свернуть" сословом className="v2-btn" слово="закрыть документ" onClick={onClose} />}
      </div>
      <div className="v2-empty__why">
        {неполные.length === 0
          ? `Документ полон к ${вид.gate}: все разделы, которых ждёт эта ступень, заполнены.`
          : `К ${вид.gate} не хватает ${неполные.length} ${склонение(неполные.length)}: `
            + неполные.map((р) => `${р.no} ${р.title}`).join('; ')}
        {вид.not_due_yet > 0 && ` Ещё ${вид.not_due_yet} — к следующим ступеням: их эта точка не ждёт.`}
      </div>
      <Baselines project={project} code={code} title={вид.title} gate={вид.gate} />
      {expert && (
        <FieldCheck project={project} code={code} вид={вид} onJump={(элемент) => {
          const адрес = элемент.split('#')[0]
          const где = вид.sections.find((р) => р.elements.some((э) => э.code === адрес))
          if (где) setВыбран(где.no)
          setПодсвечен(адрес)
          // Переход «к месту»: находка названа адресом элемента — экран открывает его раздел.
          window.setTimeout(() => window.document.getElementById(`v2-el-${адрес}`)?.scrollIntoView({ block: 'center' }), 0)
        }} />
      )}
      <div className="v2-doc__grid">
        <nav className="v2-doc__nav" aria-label="разделы документа">
          {вид.sections.map((р) => {
            const нет = закрытТезисомНет(р)
            return (
              <button key={р.no} type="button" className={р.no === раздел?.no ? 'v2-doc__navi v2-doc__navi--on' : 'v2-doc__navi'}
                aria-current={р.no === раздел?.no ? 'true' : undefined}
                title={р.complete ? 'раздел полон' : р.due_now ? `не полон к ${вид.gate}: ${р.waiting.join('; ') || 'строк нет'}` : `ждёт ступени ${р.expected_by}`}
                onClick={() => setВыбран(р.no)}>
                <span className="v2-mono">{р.no}</span>
                <span className="v2-doc__navt">{р.title}</span>
                {р.due_now || р.complete
                  ? <Маркер health={р.complete ? 'ok' : 'debt'} title={р.complete ? 'раздел полон' : 'не полон к ступени'} />
                  : <span className="v2-dim">позже</span>}
                {нет && <span className="v2-dim">их нет</span>}
              </button>
            )
          })}
        </nav>
        <div className="v2-doc__main">
          {раздел && раздел_(раздел)}
          <div className="v2-doc__add">
            <input value={тезис} onChange={(e) => setТезис(e.target.value)} aria-label={`тезис раздела ${раздел?.no ?? ''}`}
              placeholder="тезис раздела — единственный свободный текст документа" />
            <button type="button" disabled={!тезис.trim() || !раздел}
              title={тезис.trim() ? `добавить тезис в раздел ${раздел?.no ?? ''}` : 'тезис пуст: писать нечего'}
              onClick={() => раздел && api.addStatement(project, code, { section: раздел.no, text: тезис, author: 'инженер' })
                .then(() => { setТезис(''); перечитать() })
                .catch((e) => setОтказ(String(e.message ?? e)))}>
              Добавить тезис
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
