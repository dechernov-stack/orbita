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
import { api, ServerRefusal, type DocBaseline, type DocSection, type DocView, type Gate, type VerificationReport } from './api'
import { useАвтор, отказСловами } from './research'

export function Documents({ project, onGoScene, onGoField }: {
  project: string | null
  /** Переход «к месту»: сцена, которой раздел наполняется, и зачем идём. */
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Переход в поле знаний: там заводятся темы — открытые вопросы фазы. */
  onGoField?: () => void
}) {
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
      <DocumentBody project={project} code={открыт.code} onGoScene={onGoScene} onGoField={onGoField}
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
      <КомплектТочки project={project} gate={(список ?? [])[0]?.gate ?? ''} onOpen={(код) => {
        const д = (список ?? []).find((х) => х.code === код)
        if (д) setОткрыт(д)
      }} />
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
              {д.complete} из {д.total} разделов полны к {д.gate}
            </span>
            {/*
              Базирование — состояние документа, а не подробность: ворота
              зрелости F смотрят именно на него, и без этой строки список
              молчал о том, что документ уже ушёл на точку (владелец, 21.09).
            */}
            <span className={д.baselines > 0 ? 'v2-st v2-st--done' : 'v2-st'}>
              {д.baselines > 0
                ? `базирован «${д.baseline_name}» · ${д.baseline_at}`
                : 'не базирован'}
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * Комплект к точке: где мы и можно ли ехать дальше.
 *
 * Владелец 21.09: «базированные документы не видно, что они уже готовы. Где мы
 * находимся — вообще непонятно… То ли можно ехать дальше — то ли нет». Экран
 * документов показывал только полноту каждого документа по отдельности, а
 * КОМПЛЕКТ — то, чего точка ждёт от документов вместе, — жил на экране точек
 * матрицей зрелости, куда из документов дороги не было.
 *
 * Здесь та же матрица, но с её собственными словами: строки комплекта,
 * сведённые к нашим документам, с причиной у каждой незакрытой.
 */
function КомплектТочки({ project, gate, onOpen }: {
  project: string
  /** Ступень, к которой считаются документы: её комплект и показываем. */
  gate: string
  onOpen: (код: string) => void
}) {
  const [точка, setТочка] = useState<Gate | null>(null)

  useEffect(() => {
    api.points(project)
      .then((р) => setТочка(
        // Та точка, к которой считаются документы. «Ближайшая непройденная»
        // здесь не годится: внутренний обзор идёт первым, а комплекта у него
        // нет вовсе — блок молчал бы ровно там, где вопрос и задан.
        р.items.find((т) => т.key === gate)
          ?? р.items.find((т) => !т.passed && (т.matrix ?? []).length > 0)
          ?? null,
      ))
      .catch(() => setТочка(null))
  }, [project, gate])

  if (!точка) return null
  /** Строки комплекта, сведённые к документу: остальные — реестры и записи. */
  const документы = (точка.matrix ?? []).filter((с) => (с.our_ref ?? '').startsWith('document_template:'))
  if (документы.length === 0) return null
  const держат = документы.filter((с) => с.blocking && с.passed === false)
  const готовы = документы.filter((с) => с.passed !== false)

  return (
    <div className="v2-prop" data-why="почему-нельзя"
      title="комплект документов точки: то, что она ждёт от них вместе">
      <span>
        {точка.title}
        <span className="v2-cnt">
          {' '}комплект {готовы.length} из {документы.length}
          {точка.planned_date ? ` · ${точка.planned_date}` : ''}
        </span>
        <span className="v2-empty__why">
          {держат.length === 0
            ? 'Комплект документов собран: точку держат её остальные условия, если держат.'
            : `Ехать дальше нельзя: ${держат.length} из ${документы.length} не готовы.`}
        </span>
        {держат.map((с) => (
          <span key={с.artifact} className="v2-dim">
            · {с.artifact}
            {с.why ? ` — ${с.why}` : ''}
            {(с.our_ref ?? '').startsWith('document_template:') && (
              <>
                {' '}
                <button type="button" className="v2-link"
                  title="открыть документ и закрыть то, чего не хватает"
                  onClick={() => onOpen((с.our_ref ?? '').replace('document_template:', ''))}>
                  открыть документ
                </button>
              </>
            )}
          </span>
        ))}
      </span>
    </div>
  )
}

/** «не хватает 1 раздела · 2 разделов»: счёт в тексте читается словом. */
function склонение(сколько: number): string {
  const сто = сколько % 100
  if (сто >= 11 && сто <= 14) return 'разделов'
  return [5, 6, 7, 8, 9, 0].includes(сколько % 10) ? 'разделов' : сколько % 10 === 1 ? 'раздела' : 'разделов'
}

/** Документ целиком: разделы с элементами так, как они напечатаются. */
export function DocumentBody({ project, code, section, onClose, onGoScene, onGoField }: {
  project: string
  code: string
  /** Открыть только один раздел — переход из мероприятия. */
  section?: string
  onClose?: () => void
  /** Переход «к месту»: сцена, которой раздел наполняется, и зачем идём. */
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Переход в поле знаний: там заводятся темы — открытые вопросы фазы. */
  onGoField?: () => void
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
  /** Разделы, которых ступень ждёт, а они не полны: из них и складывается счёт. */
  const неполные = вид.sections.filter((р) => р.due_now && !р.complete)

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        {вид.title}
        <span className="v2-cnt">
          {вид.standard} · полнота {вид.complete} из {вид.total} к {вид.gate}
          {вид.not_due_yet > 0 && ` · ещё не ждут: ${вид.not_due_yet}`}
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
        <div className="v2-empty__why">
          {неполные.length === 0
            ? `Документ полон к ${вид.gate}: все разделы, которых ждёт эта ступень, заполнены.`
            : `К ${вид.gate} не хватает ${неполные.length} ${склонение(неполные.length)}: `
              + неполные.map((р) => `${р.no} ${р.title} (${(р.waiting[0] ?? 'нет строк').replace(/\s+/g, ' ').slice(0, 60)})`).join('; ')}
          {вид.not_due_yet > 0 && ` Ещё ${вид.not_due_yet} — к следующим ступеням: их эта точка не ждёт.`}
        </div>
      )}
      {!section && <Baselines project={project} code={code} title={вид.title} gate={вид.gate} />}
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
      {разделы.map((р) => (
        <Section key={р.no} раздел={р} подсвечен={подсвечен}
          onGoScene={onGoScene} onGoField={onGoField} />
      ))}
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

function Section({ раздел, подсвечен, onGoScene, onGoField }: {
  раздел: DocSection
  подсвечен?: string | null
  /** Переход «к месту»: сцена, которой раздел и наполняется, и зачем идём. */
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Переход в поле знаний: там заводятся темы — открытые вопросы фазы. */
  onGoField?: () => void
}) {
  /**
   * Сцены, которых ждёт раздел: названы в самом шаблоне, не в коде.
   *
   * Раздел, который «наполняется по ходу фазы», называет десять сцен разом
   * (§11 отчёта — открытые вопросы): десять кнопок «к месту» — это не адрес,
   * а шум, поэтому дорога предлагается, только когда она одна-две-три.
   */
  const сцены = [...new Set([
    ...раздел.scenes,
    ...раздел.elements.flatMap((э) => э.waiting_scenes),
  ])].filter(Boolean)
  const адресУзнан = сцены.length > 0 && сцены.length <= 3
  return (
    <div className="v2-doc__sec">
      <div className="v2-doc__h">
        <b>{раздел.no} {раздел.title}</b>
        <span className={раздел.complete ? 'v2-st v2-st--done' : 'v2-st'}>
          {раздел.complete ? 'раздел полон' : раздел.waiting.join('; ')}
        </span>
        {/*
          «Ждёт сцен 7» — это адрес, а не жалоба: без перехода человек читает
          его и не знает, куда идти (владелец, 21.09). Сцену называет шаблон
          раздела, экран её не угадывает.
        */}
        {!раздел.complete && onGoScene && адресУзнан && сцены.map((с) => (
          <button key={с} type="button" className="v2-link"
            title={`открыть сцену ${с} — этим разделом она и кончается`}
            onClick={() => onGoScene(
              с,
              `${раздел.no} ${раздел.title} — ${раздел.waiting.join('; ')}`,
            )}>
            к месту: сцена {с}
          </button>
        ))}
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
                  {/*
                    Тема — не выход сцены: она заводится в поле знаний и
                    может появиться на любой. Поэтому §11 «Открытые вопросы»
                    называет десять сцен и ни одного адреса — экран называет
                    его сам, по виду записи, которую просит запрос.
                  */}
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
    </div>
  )
}

/**
 * Базирование документа: состояние, которым он уходит на точку.
 *
 * Ворота KDP-A держатся линией базирования («документ не базирован: нет ни
 * одной линии базирования»), маршрут стоял с шипа C — а нажать было нечего:
 * кнопки не существовало ни на одном экране (владелец, 21.09: «как базировать
 * FAD — непонятно, в интерфейсе нет кнопок»).
 *
 * Имя линии — имя точки: им зовут состояние, с которым документ пришёл на
 * обзор. Линия неизменяема; перебазирование заводит НОВОЕ имя, поэтому
 * занятое имя экран не даёт нажать, а отказ сервера показывает словами.
 */
function Baselines({ project, code, title, gate }: {
  project: string
  code: string
  title: string
  /** Ступень, на которую документ идёт: её именем линию и зовут. */
  gate: string
}) {
  const [линии, setЛинии] = useState<DocBaseline[] | null>(null)
  const [точки, setТочки] = useState<Gate[]>([])
  const [раскрыт, setРаскрыт] = useState(false)
  const [имя, setИмя] = useState('')
  const [автор, setАвтор] = useАвтор()
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)

  const перечитать = () => {
    api.docBaselines(project, code).then((r) => setЛинии(r.items)).catch(() => setЛинии([]))
  }
  useEffect(перечитать, [project, code])
  useEffect(() => {
    api.points(project).then((r) => setТочки(r.items)).catch(() => setТочки([]))
  }, [project])

  /**
   * Что ждёт от ЭТОГО документа точка: строка комплекта из её матрицы
   * зрелости. Без неё экран документа говорит только о себе, а человек
   * спрашивает «можно ехать дальше?» — и ответа не находит.
   */
  const ждёт = точки.find((т) => т.key === gate)?.matrix
    ?.find((с) => с.our_ref === `document_template:${code}`)

  /**
   * Имя по умолчанию — ТА САМАЯ точка, к которой документ считается полным:
   * им он и уходит на обзор. Внутренний обзор в лестницу документов не
   * входит, поэтому «ближайшая непройденная» здесь была бы не та.
   */
  const ближайшая = точки.find((т) => т.key === gate)?.title
    ?? точки.find((т) => !т.passed)?.title ?? ''

  const выбрано = имя.trim() || ближайшая
  const занятоИмя = (линии ?? []).some((л) => л.name === выбрано)
  const безАвтора = !автор.trim()
  /** Почему кнопка заперта — словами на экране, а не подсказкой под курсором. */
  const помеха = безАвтора
    ? 'не названо, кто базирует. Приложение подставляет имя учётки; если поле пусто — впишите себя.'
    : !выбрано
      ? 'у линии нет имени: им зовут состояние, с которым документ идёт на точку.'
      : занятоИмя
        ? `линия «${выбрано}» уже есть. Линия неизменяема — назовите другое имя, например с датой.`
        : null

  const базировать = () => {
    setЗанято(true); setОтказ(null); setИтог(null)
    api.baselineDocument(project, code, { name: выбрано, author: автор })
      .then((л) => {
        setИмя('')
        setИтог(`линия «${л.name}» записана: ${л.elements} элементов` + (л.note ? ` · ${л.note}` : ''))
        перечитать()
      })
      .catch((e) => setОтказ(отказСловами(e)))
      .finally(() => setЗанято(false))
  }

  return (
    <>
      <div className="v2-prop" data-why="почему-нельзя"
        title="базирование: снимок состояния документа под именем точки; ворота смотрят на него">
        <span>
          Базирование
          <span className="v2-cnt">
            {линии === null
              ? ' читаю…'
              : линии.length === 0
                ? ' линий нет'
                : ` ${линии.length} · последняя «${линии[линии.length - 1].name}»`}
          </span>
        </span>
        <button type="button" className="v2-link"
          title={раскрыт ? 'свернуть' : 'записать состояние документа линией точки'}
          onClick={() => setРаскрыт(!раскрыт)}>
          {раскрыт ? 'свернуть' : 'Базировать →'}
        </button>
      </div>

      {раскрыт && (
        <div className="v2-form" data-why="почему-нельзя">
          {ждёт && (
            <div className={ждёт.passed === false && ждёт.blocking ? 'v2-locked' : 'v2-empty__why'}>
              {ждёт.artifact}: {gate} ждёт зрелость «{ждёт.maturity}» —{' '}
              {ждёт.passed === false
                ? `${ждёт.why ?? 'не готов'}${ждёт.blocking ? '. Пока так — точка не пустит.' : '. Точку это не держит.'}`
                : 'условие закрыто.'}
            </div>
          )}
          <div className="v2-empty__why">
            Линия базирования — состояние «{title}», с которым он уходит на точку: ворота
            KDP-A требуют её у документов зрелости F. Линия неизменяема: правка после неё
            видна расхождением, а не переписыванием, — перебазирование заводит новое имя.
          </div>
          {(линии ?? []).map((л) => (
            <div key={л.name} className="v2-note">
              <span className="v2-note__rule">{л.name}</span>
              <span className="v2-dim">
                {л.elements} элементов · {л.by} · {л.at.slice(0, 10)}
                {л.tag ? ` · отметка ${л.tag}` : ''}
                {л.note ? ` · ${л.note}` : ''}
              </span>
            </div>
          ))}
          {отказ && <div className="v2-locked">{отказ}</div>}
          {итог && <div className="v2-empty__why">{итог}</div>}
          <div className="v2-form v2-form--row">
            <label title="имя линии — имя точки, на которую идёт документ">имя линии
              <input list={`v2-точки-${code}`} value={имя} placeholder={ближайшая}
                aria-label={`имя базовой линии документа ${code}`}
                onChange={(e) => setИмя(e.target.value)} />
            </label>
            <datalist id={`v2-точки-${code}`}>
              {точки.map((т) => <option key={т.key} value={т.title} />)}
            </datalist>
            <label title="линию заводит названный человек: пустого автора сервер не принимает">кто базирует
              <input value={автор} placeholder="Иванов"
                onChange={(e) => setАвтор(e.target.value)} />
            </label>
            <button type="button" className="v2-primary"
              disabled={занято || безАвтора || !выбрано || занятоИмя}
              title={безАвтора
                ? 'назовите себя: линию заводит человек'
                : !выбрано
                  ? 'у линии нет имени: ею зовут состояние на точке'
                  : занятоИмя
                    ? `линия «${выбрано}» уже есть: линия неизменяема, назовите другое имя`
                    : `записать состояние документа линией «${выбрано}»`}
              onClick={базировать}>
              {занято ? 'Базирую…' : 'Базировать'}
            </button>
          </div>
          {помеха && <div className="v2-locked">Нажать нельзя: {помеха}</div>}
        </div>
      )}
    </>
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
