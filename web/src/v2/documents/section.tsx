// Тело раздела документа (шип 5 §5): справа от списка разделов.
//
// Порядок чтения: элементы строками (значение · откуда) → связный текст с
// маркером статуса (черновик · отрецензирован · принят) и «кто · когда ·
// движок · секунды» приглушённо → правки изложения историей. Панель раздела —
// пиктограмма и слово: написать связно · править изложение · принять как есть
// · их нет. Фоновое задание считает секунды вслух в самой панели, не модальным
// окном. Пустой раздел не молчит: говорит, каких сцен ждёт, и ведёт к месту.
import { useState } from 'react'
import { api, СЛОВО_РЕНДЕРИНГА, type DocSection, type RenderedSection } from '../api'
import { ИконКнопка } from '../ui/iconbutton'
import { датаКратко, ктоКратко } from '../ui/objectcard'
import { Маркер, type Здоровье } from '../ui/tabs'

/** Статус связного текста маркером: черновик — долг, отрецензирован — долг, принят — в порядке. */
/** Секунды связного текста словами: из журнала ИИ, иначе — секундами окна, где он только что написан. */
export function секундыСвязного(изЖурнала: number | null | undefined, вОкне: number | null): string | null {
  const секунд = изЖурнала ?? вОкне
  return секунд === null || секунд === undefined ? null : `${секунд} с`
}

export function здоровьеТекста(статус: string): Здоровье {
  return статус === 'accepted' ? 'ok' : 'debt'
}

/** Закрыт ли раздел тезисом «их нет»: тезис того же вида, что пишет кнопка «их нет». */
export function закрытТезисомНет(раздел: DocSection): boolean {
  return раздел.elements.some((э) => э.kind === 'statement' && /:\s*нет\.\s*$/.test(э.text ?? ''))
}

export function Раздел({ раздел, подсвечен, onGoScene, onGoField, onЗакрытьСловами, project, code, написанное, onНаписано }: {
  раздел: DocSection
  подсвечен?: string | null
  /** Проект и код документа — для «Написать связно» живой моделью. */
  project?: string
  code?: string
  /** Уже написанный связный текст раздела, если есть. */
  написанное?: RenderedSection | null
  onНаписано?: (текст: RenderedSection) => void
  /** Переход «к месту»: сцена, которой раздел и наполняется, и зачем идём. */
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Переход в поле знаний: там заводятся темы — открытые вопросы фазы. */
  onGoField?: () => void
  /** Закрыть пустой перечень тезисом: «их нет» — тоже ответ. */
  onЗакрытьСловами?: (раздел: string, текст: string) => void
}) {
  /**
   * Сцены, которых ждёт раздел: названы в самом шаблоне, не в коде. Раздел,
   * который «наполняется по ходу фазы», называет десять сцен разом (§11
   * отчёта): десять «к месту» — это шум, поэтому дорога предлагается, только
   * когда она одна-две-три.
   */
  const сцены = [...new Set([...раздел.scenes, ...раздел.elements.flatMap((э) => э.waiting_scenes)])].filter(Boolean)
  const адресУзнан = сцены.length > 0 && сцены.length <= 3
  const [пишу, setПишу] = useState(false)
  const [секунд, setСекунд] = useState(0)
  /** Секунды последнего письма на этом экране: у прежних текстов их нет — рендеринг их не хранит. */
  const [секундыПисьма, setСекундыПисьма] = useState<number | null>(null)
  const [отказПисьма, setОтказПисьма] = useState<string | null>(null)
  /** Рецензия патчами: правка изложения поверх текста модели, «принять как есть». */
  const [правлю, setПравлю] = useState(false)
  const [правка, setПравка] = useState('')
  const [итогРецензии, setИтогРецензии] = useState<string | null>(null)
  const [историяОткрыта, setИсторияОткрыта] = useState(false)
  /**
   * Перечень, который законно бывает пустым (§11 «Открытые вопросы»): строк
   * нет — это не «незакрытые вопросы остались», а «их нет» словами человека
   * (КТ2, 24.09). Статус так и говорит, и кнопка закрытия — в панели.
   */
  const пустоПоПраву = !раздел.complete && раздел.empty_ok_with_statement
    && раздел.elements.every((э) => э.kind === 'statement' || э.rows.length === 0)
  const принятьТекст = (т: RenderedSection) => {
    if (!onНаписано) return
    onНаписано(т)
    if (!т.accepted && т.refusals.length) setОтказПисьма(`текст отклонён сторожем: ${т.refusals.join('; ')}`)
  }
  /**
   * Фоновым заданием (шип 4 §4): стенд отвечает, пока модель пишет; экран
   * считает секунды вслух в панели и опрашивает задание раз в две секунды.
   */
  const написать = () => {
    if (!project || !code || !onНаписано) return
    setПишу(true); setОтказПисьма(null); setСекунд(0)
    let прошло = 0
    const часы = window.setInterval(() => { прошло += 1; setСекунд(прошло) }, 1000)
    const кончить = () => { window.clearInterval(часы); setПишу(false); setСекундыПисьма(прошло) }
    const опрос = (job: string) => {
      api.documentJob(project, code, job).then((з) => {
        if (з.status === 'done' && з.result) { кончить(); принятьТекст(з.result) }
        else if (з.status === 'failed') { кончить(); setОтказПисьма(з.error ?? 'связный текст не написан') }
        else window.setTimeout(() => опрос(job), 2000)
      }).catch((e) => { кончить(); setОтказПисьма(String(e.message ?? e)) })
    }
    api.writeSectionJob(project, code, раздел.no, 'инженер')
      .then((з) => {
        if (з.status === 'done' && з.result) { кончить(); принятьТекст(з.result) }
        else if (з.status === 'failed') { кончить(); setОтказПисьма(з.error ?? 'связный текст не написан') }
        else window.setTimeout(() => опрос(з.job), 2000)
      })
      .catch((e) => { кончить(); setОтказПисьма(String(e.message ?? e)) })
  }
  const сохранитьПравку = () => {
    if (!project || !code || !написанное) return
    setИтогРецензии(null)
    api.reviewSection(project, code, раздел.no, правка, 'инженер')
      .then((т) => {
        if (т.accepted) { setПравлю(false); onНаписано?.(т); setИтогРецензии(`правка сохранена патчем · ${СЛОВО_РЕНДЕРИНГА[т.status] ?? т.status}`) }
        else setИтогРецензии(`правка отклонена сторожем: ${т.refusals.join('; ')}`)
      })
      .catch((e) => setИтогРецензии(String(e.message ?? e)))
  }
  const принятьКакЕсть = () => {
    if (!project || !code) return
    api.acceptRendering(project, code, 'инженер', раздел.no)
      .then((р) => { const т = р.items[0]; if (т) onНаписано?.(т); setИтогРецензии('принято как есть') })
      .catch((e) => setИтогРецензии(String(e.message ?? e)))
  }
  const текст = написанное && написанное.accepted ? написанное : null

  return (
    <div className="v2-doc__sec" aria-label={`раздел ${раздел.no}`}>
      <div className="v2-doc__h">
        <b>{раздел.no} {раздел.title}</b>
        <span className={раздел.complete ? 'v2-st v2-st--done' : 'v2-st'}>
          {раздел.complete ? 'раздел полон' : пустоПоПраву ? 'строк нет — закройте тезисом «их нет» или заведите тему' : раздел.waiting.join('; ')}
        </span>
      </div>

      {/* Панель раздела: пиктограмма и слово; задание — секундами вслух здесь же. */}
      <div className="v2-doc__panel" role="toolbar" aria-label={`действия раздела ${раздел.no}`}>
        {project && code && onНаписано && (
          <ИконКнопка икон="сформировать" сословом className="v2-btn" onClick={написать} disabled={пишу}
            слово={пишу ? `пишу… ${секунд} с` : текст ? 'переписать связно' : 'написать связно'} />
        )}
        {текст && !правлю && (
          <ИконКнопка икон="править" сословом className="v2-btn" слово="править изложение"
            onClick={() => { setПравка(текст.text); setПравлю(true); setИтогРецензии(null) }} />
        )}
        {текст && текст.status !== 'accepted' && !правлю && (
          <ИконКнопка икон="принять" сословом className="v2-btn" слово="принять как есть" onClick={принятьКакЕсть} />
        )}
        {пустоПоПраву && onЗакрытьСловами && (
          <ИконКнопка икон="снять" сословом className="v2-btn" слово="их нет — закрыть тезисом"
            onClick={() => onЗакрытьСловами(раздел.no, `${раздел.title}: нет.`)} />
        )}
        {/*
          «Ждёт сцен 7» — это адрес, а не жалоба: без перехода человек читает
          его и не знает, куда идти (владелец, 21.09). Сцену называет шаблон
          раздела, экран её не угадывает.
        */}
        {!раздел.complete && onGoScene && адресУзнан && сцены.map((с) => (
          <ИконКнопка key={с} икон="к-месту" сословом className="v2-btn" слово={`к месту: сцена ${с}`}
            onClick={() => onGoScene(с, `${раздел.no} ${раздел.title} — ${раздел.waiting.join('; ')}`)} />
        ))}
        {итогРецензии && <span className="v2-dim">{итогРецензии}</span>}
      </div>
      {отказПисьма && <div className="v2-locked">{отказПисьма}</div>}

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
                  {/*
                    «А если их просто нет?» (владелец, 22.09). Список, который
                    законно бывает пустым, нельзя требовать строкой: правильный
                    ответ документа — «их нет», сказанное словами, с именем и
                    датой. Шаблон раздела объявляет, что так можно.
                  */}
                  {(раздел.empty_ok_with_statement || э.empty_ok_with_statement) && onЗакрытьСловами && (
                    <button type="button" className="v2-link"
                      title="закрыть тезисом «нет»: документ напечатает это словами, а не пустой таблицей"
                      onClick={() => onЗакрытьСловами(раздел.no, `${раздел.empty_ok_with_statement ? раздел.title : э.title}: нет.`)}>
                      {раздел.empty_ok_with_statement ? 'их нет — закрыть раздел тезисом' : 'их нет — закрыть перечень тезисом'}
                    </button>
                  )}
                  {/* Тема — не выход сцены: она заводится в поле знаний; адрес экран называет по виду записи. */}
                  {э.select === 'topic' && onGoField && (
                    <button type="button" className="v2-link"
                      title="открыть поле знаний: тема без разрешения и есть открытый вопрос"
                      onClick={onGoField}>
                      к месту: поле знаний → «Завести тему»
                    </button>
                  )}
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

      {текст && (
        <div className="v2-doc__el" data-why="работа" aria-label="связный текст раздела">
          <div className="v2-doc__texthead">
            <Маркер health={здоровьеТекста(текст.status)} title={СЛОВО_РЕНДЕРИНГА[текст.status] ?? текст.status} />
            <b>связный текст</b>
            <span>{СЛОВО_РЕНДЕРИНГА[текст.status] ?? текст.status}</span>
            <span className="v2-dim">
              {[ктоКратко(текст.author ?? undefined), датаКратко(текст.at ?? undefined), текст.model,
                // Секунды — из журнала ИИ (ответ владельца 26.09); только что написанный — секундами окна.
                секундыСвязного(текст.seconds, секундыПисьма)].filter(Boolean).join(' · ')}
              {текст.reviewer ? ` · рецензент ${текст.reviewer}` : ''}
            </span>
          </div>
          {правлю ? (
            <>
              <textarea rows={6} value={правка} onChange={(e) => setПравка(e.target.value)} aria-label="правка изложения" />
              <div className="v2-doc__panel">
                <ИконКнопка икон="принять" сословом className="v2-btn v2-btn--primary" слово="сохранить правку"
                  disabled={!правка.trim() || правка.trim() === текст.text} onClick={сохранитьПравку} />
                <button type="button" className="v2-link" onClick={() => setПравлю(false)} title="оставить текст как был">отмена</button>
              </div>
            </>
          ) : (
            <p className="v2-doc__st">{текст.text}</p>
          )}
          {текст.notes.length > 0 && <div className="v2-dim">пометы: {текст.notes.join('; ')}</div>}
          {текст.patches.length > 0 && (
            <div className="v2-doc__patches">
              <button type="button" className="v2-link" aria-expanded={историяОткрыта} onClick={() => setИсторияОткрыта(!историяОткрыта)}
                title="правки изложения человека поверх текста модели: что стояло, что стало, кто и когда">
                {историяОткрыта ? 'скрыть правки изложения' : `правки изложения: ${текст.patches.length}`}
              </button>
              {историяОткрыта && (
                <ol>
                  {текст.patches.map((п, i) => (
                    <li key={i}>
                      <span className="v2-dim">{ктоКратко(п.author)} · {датаКратко(п.at)}: </span>
                      <s>{п.old}</s> → {п.new}
                    </li>
                  ))}
                </ol>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
