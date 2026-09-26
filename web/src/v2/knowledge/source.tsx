// Вход в поле знаний (шип 5 §3.1): загрузка источника с заданием и выгрузка картины.
import { useEffect, useState } from 'react'
import { api, РОЛИ_ДОКУМЕНТА, type Authority, type DocumentRole, type GapBrief } from '../api'
import { КартаПробелов, Партия } from '../dossier'
import { РАНГ, РАНГИ_ИСТОЧНИКА } from './common'

/** Вход в поле: текст, файл или ссылка + задание → разбор. Экспорт — сцена 2 зовёт его на пустом проекте (З-02). */
export function Source({ project, onParsed, onError, onRead, новаяВерсия = '' }: {
  project: string
  onParsed: (итог: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => void
  onError: (e: string) => void
  /** Документ прочитан в постановку: экран переходит к предложениям. */
  onRead?: (run: string, note: string) => void
  /** «Загрузить версию» у строки документа (шип 5 §3.1): прежняя версия выбрана сразу. */
  новаяВерсия?: string
}) {
  const [имя, setИмя] = useState('')
  const [текст, setТекст] = useState('')
  const [ссылка, setСсылка] = useState('')
  const [вид, setВид] = useState('mission_memo')
  const [задание, setЗадание] = useState('разбери по сущностям')
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)
  const [прежние, setПрежние] = useState<{ code: string; name: string }[]>([])
  const [прежний, setПрежний] = useState(новаяВерсия)
  // Ранг доверия называет ЧЕЛОВЕК, режим разбора выводит разбор по профилю
  // содержимого: тип документа полем ввода на проекте поля знаний v2 не бывает.
  const [ранг, setРанг] = useState<Authority | ''>('')
  const [рольДок, setРольДок] = useState<DocumentRole | ''>('')
  const [читаю, setЧитаю] = useState(0)
  const [знанияV2, setЗнанияV2] = useState(false)
  const [поставлен, setПоставлен] = useState<string | null>(null)
  // Устав проверяется по двенадцати пунктам записки при загрузке: короткая
  // карта приходит ответом, полная — по кнопке (ТРЕБОВАНИЯ-К-ЗАПИСКЕ-МИССИИ).
  const [пробелы, setПробелы] = useState<{ code: string; карта: GapBrief } | null>(null)
  const [картаОткрыта, setКартаОткрыта] = useState(false)

  useEffect(() => {
    api.materials(project).then((r) => setПрежние(r.items)).catch(() => setПрежние([]))
    // Признак поля знаний v2 даёт сервер: там, где синтеза нет, форма
    // остаётся прежней — с типом входного и без ранга.
    api.synthesisPending(project).then(() => setЗнанияV2(true)).catch(() => setЗнанияV2(false))
  }, [project])

  // Задание — по типу входного (каталог заданий, ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА §3);
  // строка остаётся редактируемой: узел или намерение инженер уточняет сам.
  const заданиеПоТипу: Record<string, string> = {
    mission_memo: 'разбери по сущностям',
    tor: 'это ТЗ — оцени против нужд',
    datasheet: 'обнови параметры ‹код узла›',
    normative: 'это норматив — заведи',
    analysis: 'разбери по сущностям',
    reference: 'просто в контекст',
  }
  const сменитьТип = (т: string) => { setВид(т); setЗадание(заданиеПоТипу[т] ?? 'разбери по сущностям') }

  // Текстовый файл читается В БРАУЗЕРЕ и уходит текстом; двоичный (docx ·
  // pdf · xlsx · pptx) уходит base64, текст извлекает сервер тем же
  // извлекателем, что у документов v1 (замечание ПМИ-5, 12.09).
  const [двоичный, setДвоичный] = useState<{ name: string; base64: string } | null>(null)
  const файл = (f: File | null) => {
    if (!f) { setДвоичный(null); return }
    if (!имя.trim()) setИмя(f.name.replace(/\.[^.]+$/, ''))
    const текстовый = /\.(txt|md|csv|markdown)$/i.test(f.name) || f.type.startsWith('text/')
    if (текстовый) {
      setДвоичный(null)
      f.text().then(setТекст).catch(() => onError('файл не прочитался: приложите текстовый'))
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const url = String(reader.result ?? '')
      setДвоичный({ name: f.name, base64: url.substring(url.indexOf(',') + 1) })
      setТекст('')
    }
    reader.onerror = () => onError('файл не прочитался')
    reader.readAsDataURL(f)
  }

  // Разбор — фоновой задачей (ADR-069): стенд отвечает, пока модель думает;
  // статус опрашивается раз в три секунды, готовый ответ применяется при опросе.
  const разобрать = () => {
    setЗанято(true); setИтог(null)
    const готово = (р: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => {
      setИтог(`${р.note}${р.refusals.length ? ` · отклонено: ${р.refusals.slice(0, 3).join('; ')}` : ''}`)
      onParsed(р)
      setЗанято(false)
    }
    const опрос = (job: string) => {
      api.atomizeJobStatus(project, job).then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job}: ${з.elapsed_seconds} с — стенд отвечает, страницу можно не держать`); window.setTimeout(() => опрос(job), 3000) }
      }).catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
    }
    api.putMaterial(project, {
      name: имя, text: текст, url: ссылка, author: 'инженер', supersedes: прежний || undefined,
      // Ранг — с формы; тип входного остаётся только там, где поля знаний v2
      // нет: иначе режим разбора снова читался бы с типа файла.
      ...(знанияV2 ? { rank: ранг || undefined, role: рольДок || undefined } : { kind: вид }),
      ...(двоичный ? { filename: двоичный.name, file_base64: двоичный.base64 } : {}),
    })
      .then((м) => {
        // Показывается ранг, который ПОСТАВИЛО ядро, а не тот, что отправлен.
        if (м.rank) {
          setПоставлен(`ранг источника: ${РАНГ[м.rank] ?? м.rank}`
            + (м.rank_note ? ` · ${м.rank_note}` : ''))
        }
        setПробелы(м.gaps ? { code: м.code, карта: м.gaps } : null)
        return api.atomizeJob(project, м.code, задание, 'инженер')
      })
      .then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job} — стенд отвечает`); window.setTimeout(() => опрос(з.job), 3000) }
      })
      .catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
  }

  /**
   * Прочитать документ В ПОСТАНОВКУ — один вызов вместо связки «атомизация по
   * предикатам → формирование по фильтрам» (РЕШЕНИЕ-ЧИТАТЬ-СМЫСЛ, 15.09).
   * Модель читает смыслом и отдаёт понятия сразу, каждое с цитатой и якорем.
   *
   * Вызов идёт минуту и больше: экран считает секунды вслух, иначе ожидание
   * читается как «ничего не происходит».
   */
  const прочитать = () => {
    setЗанято(true); setИтог(''); setЧитаю(1)
    const часы = window.setInterval(() => setЧитаю((с) => с + 1), 1000)
    const кончить = () => { window.clearInterval(часы); setЧитаю(0); setЗанято(false) }
    api.putMaterial(project, {
      name: имя, text: текст, url: ссылка, author: 'инженер', supersedes: прежний || undefined,
      rank: ранг || undefined, role: рольДок || undefined,
      ...(двоичный ? { filename: двоичный.name, file_base64: двоичный.base64 } : {}),
    })
      .then((м) => {
        if (м.rank) setПоставлен(`ранг источника: ${РАНГ[м.rank] ?? м.rank}`)
        setПробелы(м.gaps ? { code: м.code, карта: м.gaps } : null)
        return api.readDocument(project, м.code, 'инженер')
      })
      .then((р) => {
        кончить()
        setИтог(`${р.note} · запуск ${р.run}`)
        onRead?.(р.run, р.note)
      })
      .catch((e) => { кончить(); onError(String(e.message ?? e)) })
  }

  const есть = текст.trim().length > 0 || ссылка.trim().length > 0 || двоичный !== null

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <label>название
          <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Записка миссии" />
        </label>
        {знанияV2 ? (
          <label>ранг доверия
            <select value={ранг} onChange={(e) => setРанг(e.target.value as Authority | '')}
              title="чего источник стоит: обязательный — заказчик, регулятор, директива; справочный — аналитика, даташит; сомнительный — непроверенное">
              <option value="">— назовите ранг —</option>
              {РАНГИ_ИСТОЧНИКА.map((р) => <option key={р} value={р}>{РАНГ[р]}</option>)}
            </select>
          </label>
        ) : (
          <label>тип
            <select value={вид} onChange={(e) => сменитьТип(e.target.value)}>
              <option value="mission_memo">записка миссии</option>
              <option value="tor">техническое задание</option>
              <option value="normative">норматив</option>
              <option value="datasheet">даташит</option>
              <option value="analysis">анализ</option>
              <option value="reference">справочный</option>
            </select>
          </label>
        )}
      </div>
      <label>текст
        <textarea rows={4} value={текст} autoComplete="off" onChange={(e) => setТекст(e.target.value)}
          placeholder="вставьте текст — либо приложите файл или дайте ссылку ниже" />
      </label>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr' }}>
        <label>файл
          <input type="file" accept=".txt,.md,.csv,.docx,.pdf,.xlsx,.pptx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.presentationml.presentation"
            title="txt · md · csv читаются в браузере; docx · pdf · xlsx · pptx — текст извлекает сервер"
            onChange={(e) => файл(e.target.files?.[0] ?? null)} />
          {двоичный && <span className="v2-muted"> {двоичный.name}: текст извлечёт сервер</span>}
        </label>
        <label>ссылка
          <input value={ссылка} onChange={(e) => setСсылка(e.target.value)} placeholder="https://…" />
        </label>
      </div>
      {знанияV2 && (
        <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr' }}>
          <label>роль документа
            <select value={рольДок} onChange={(e) => setРольДок(e.target.value as DocumentRole | '')}
              title="ролью решается, ЧТО из документа может образоваться: цели рождает только устав, издатель норматива стороной не бывает">
              <option value="">— назовите роль —</option>
              {РОЛИ_ДОКУМЕНТА.map((р) => <option key={р.code} value={р.code}>{р.word}</option>)}
            </select>
          </label>
          <div className="v2-note-line">
            {рольДок
              ? РОЛИ_ДОКУМЕНТА.find((р) => р.code === рольДок)?.hint
              : 'без роли документ читается как обстановка — она беднее всех правами: ни целей, ни сервисов'}
          </div>
        </div>
      )}
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <label>задание
          <input value={задание} onChange={(e) => setЗадание(e.target.value)}
            placeholder="разбери по сущностям · это норматив — заведи · сравни с нашим" />
        </label>
        <label>новая версия материала
          <select value={прежний} onChange={(e) => setПрежний(e.target.value)}
            title="прежняя версия того же входного: изменённые блоки пометят свои факты и сущности «источник обновлён»">
            <option value="">— нет, новый источник —</option>
            {прежние.map((м) => <option key={м.code} value={м.code}>{м.code} · {м.name}</option>)}
          </select>
        </label>
      </div>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary"
          disabled={занято || !есть || !имя.trim() || (знанияV2 && !ранг)}
          title={!имя.trim() ? 'дайте источнику название'
            : !есть ? 'нужен текст, файл или ссылка'
              : знанияV2 && !ранг ? 'назовите ранг доверия: чего этот источник стоит, решает человек'
                : 'положить материал и разобрать: факты и темы лягут в поле'}
          onClick={разобрать}>
          {занято ? 'Разбираю…' : 'Разобрать'}
        </button>
        {знанияV2 && (
          <button type="button" className="v2-primary"
            disabled={занято || !есть || !имя.trim() || !ранг}
            title={!имя.trim() ? 'дайте источнику название'
              : !есть ? 'нужен текст, файл или ссылка'
                : !ранг ? 'назовите ранг доверия'
                  : !рольДок ? 'роль не названа: документ прочтётся как обстановка — ни целей, ни сервисов'
                    : 'один вызов: документ читается смыслом и даёт постановку сразу — стороны, нужды, цели, рамки, вехи; у каждого пункта цитата и якорь'}
            onClick={прочитать}>
            {читаю > 0 ? `Читаю… ${читаю} с` : 'Прочитать документ'}
          </button>
        )}
        {поставлен && <span className="v2-dim">{поставлен}</span>}
        {итог && <span className="v2-dim">{итог}</span>}
      </div>
      {пробелы && (
        <div className="v2-note-line" data-why="почему-нельзя" aria-label="пробелы устава">
          карта пробелов устава: найдено {пробелы.карта.present} из 12
          {пробелы.карта.missing.length > 0 && <> · не найдено: {пробелы.карта.missing.join(', ')}</>}
          {пробелы.карта.blocked_scenes.length > 0 && <> · заперты сцены {пробелы.карта.blocked_scenes.join(', ')}</>}
          {' '}
          <button type="button" className="v2-link" onClick={() => setКартаОткрыта(!картаОткрыта)}
            title="двенадцать пунктов записки: что есть, чего нет, какую сцену пункт закрывает">
            {картаОткрыта ? 'скрыть карту' : 'показать карту'}
          </button>
        </div>
      )}
      {пробелы && картаОткрыта && <КартаПробелов project={project} code={пробелы.code} onError={onError} />}
      {знанияV2 && (
        <Партия project={project} ранг={ранг} роль={рольДок}
          onDone={() => onParsed({ task: '', note: 'партия каталога разобрана', accepted: 0, refused: 0, refusals: [] })}
          onError={onError} />
      )}
    </div>
  )
}

/**
 * Выгрузка картины (шип 4 §5): срез по задаче — пять блоков с отпечатком;
 * срезы перечисляет сервер, экран только выбирает.
 */
export function ВыгрузкаКартины({ project }: { project: string }) {
  const [срезы, setСрезы] = useState<{ key: string; title: string }[]>([])
  const [срез, setСрез] = useState('reading')
  useEffect(() => { api.pictureTasks().then((r) => setСрезы(r.items)).catch(() => setСрезы([])) }, [])
  if (!project || срезы.length === 0) return null
  return (
    <span className="v2-chip" aria-label="выгрузка картины" title="картина внешней модели пятью блоками: срез, принятые объекты кодами, цепочка задачи, вопросы, права и формат — с отпечатком для сверки">
      <select value={срез} onChange={(e) => setСрез(e.target.value)} aria-label="срез картины">
        {срезы.map((с) => <option key={с.key} value={с.key}>{с.title}</option>)}
      </select>
      {' '}
      <a className="v2-link" href={api.pictureZipUrl(project, срез)} target="_blank" rel="noreferrer">Выгрузка картины</a>
    </span>
  )
}

