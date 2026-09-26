// Сцены 1–6 Pre-A (СЦЕНАРИЙ-PRE-A). Каждая сцена — один вопрос и его выход.
//
// Ни одна сцена не решает, открыта ли она: это сказал сервер. Здесь только
// формы ввода; поверхности сцен 3–6 — ТЕ ЖЕ реестры, что на Постановке
// (registry/*.tsx, шип 5 §2): второй копии таблицы сторон, нужд, целей,
// сервисов и ограничений здесь нет.
import { useEffect, useRef, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import {
  api, ServerRefusal,
  type ReconcileAction, type ReconcileCandidate, type ReconcileItem, type ReconcileRun,
} from './api'
import { Source } from './knowledgefield'
import { РеестрОграничений } from './registry/constraints'
import { КЛАССЫ, useПостановка } from './registry/data'
import { РеестрЦелей } from './registry/goals'
import { РеестрНужд } from './registry/needs'
import { РеестрСервисов } from './registry/services'
import { РеестрСторон } from './registry/stakeholders'
import { ДЕЙСТВИЕ_СВЕРКИ } from './words'
import { запомнитьАвтора, запомнитьРоль, отказСловами, прочитатьАвтора, прочитатьРоль } from './research'

/** Сцена 1 — открыть проект. Точки заводятся сразу, с датами по умолчанию. */
export function SceneOpenProject({ onOpened }: { onOpened: (project: string) => void }) {
  const [имя, setИмя] = useState('')
  const [код, setКод] = useState('')
  const [класс, setКласс] = useState('НОО · связь и IoT')
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)

  const открыть = () => {
    setЗанято(true); setОтказ(null)
    api.openProject({ name: имя, code: код || undefined, mission_class: класс, standard: 'NASA-7120' })
      .then((фаза) => onOpened(фаза.project))
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-form">
      {отказ && <div className="v2-locked">{отказ}</div>}
      <label>Название проекта
        <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Национальная платформа IoT" />
      </label>
      <label>Код проекта
        <input value={код} onChange={(e) => setКод(e.target.value)} placeholder="PJ-0001" />
      </label>
      <label>Класс миссии
        <input value={класс} onChange={(e) => setКласс(e.target.value)} />
      </label>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" onClick={открыть}
          disabled={занято || !имя.trim()}
          title={!имя.trim() ? 'дайте проекту название — по нему его узнают в портфеле' : 'завести проект и фазу с тремя точками'}>
          {занято ? 'Завожу…' : 'Открыть проект'}
        </button>
        <span className="v2-empty__why">Стандарт — NASA-7120: имена точек будут MCR и KDP-A.</span>
      </div>
    </div>
  )
}

/** Сцена 2 — замысел: четыре поля либо связный абзац; принять явно. */
export function SceneIntent({ project, onChanged }: { project: string; onChanged: () => void }) {
  // З-02 (ПМИ-5, 12.09): на пустом проекте первое действие — загрузить
  // записку миссии: замысел, стороны и нужды предложит разбор; форма руками —
  // второй путь. Как только материал есть, приглашение уступает место форме.
  const [материалов, setМатериалов] = useState<number | null>(null)
  const [разобрано, setРазобрано] = useState<string | null>(null)
  const [отказЗагрузки, setОтказЗагрузки] = useState<string | null>(null)
  useEffect(() => {
    api.entities(project, 'material').then((r) => setМатериалов(r.items.length)).catch(() => setМатериалов(0))
  }, [project, разобрано])
  const [поля, setПоля] = useState({ for_whom: '', what: '', where: '', horizon: '' })
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    api.entities(project, 'intent').then((r) => {
      const первый = r.items[0]
      if (первый) {
        setПоля({
          for_whom: String(первый.doc.for_whom ?? ''),
          what: String(первый.doc.what ?? ''),
          where: String(первый.doc.where ?? ''),
          horizon: String(первый.doc.horizon ?? ''),
        })
      }
    }).catch(() => undefined)
  }, [project])

  const полон = Object.values(поля).every((v) => v.trim() !== '')

  const сохранить = (принять: boolean) => {
    setЗанято(true); setОтказ(null)
    api.intent(project, { ...поля, accepted: принять })
      .then(() => onChanged())
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div>
      {материалов === 0 && !разобрано && (
        <div className="v2-panel" data-why="следующий-клик">
          <h3>Загрузите записку миссии</h3>
          <div className="v2-hint">
            Замысел, стороны и нужды предложит разбор — принять их можно в поле знаний.
            Заполнить форму руками — второй путь, ниже.
          </div>
          {отказЗагрузки && <div className="v2-locked">{отказЗагрузки}</div>}
          <Source project={project}
            onParsed={(итог) => { setРазобрано(`${итог.note} · план из поля знаний: ${итог.task}`); onChanged() }}
            onError={setОтказЗагрузки} />
        </div>
      )}
      {разобрано && <div className="v2-hint" data-why="следующий-клик">{разобрано} — план действий ждёт акцепта в поле знаний.</div>}
    <div className="v2-form">
      {отказ && <div className="v2-locked">{отказ}</div>}
      {([
        ['for_whom', 'Для кого', 'перевозчики опасных грузов, операторы БВС'],
        ['what', 'Что делает', 'передаёт короткие сообщения от датчиков'],
        ['where', 'Где', 'Арктика, СМП, Сибирь и Дальний Восток'],
        ['horizon', 'Горизонт', 'к 2033 году, около 150 аппаратов'],
      ] as const).map(([поле, подпись, подсказка]) => (
        <label key={поле}>{подпись}
          <textarea rows={поля[поле].length > 90 ? 3 : 1} value={поля[поле]} placeholder={подсказка}
            autoComplete="off" onChange={(e) => setПоля({ ...поля, [поле]: e.target.value })} />
        </label>
      ))}
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" onClick={() => сохранить(true)}
          disabled={занято || !полон}
          title={полон
            ? 'принять замысел — сцена 3 откроется сама'
            : 'нужны все четыре поля: замысел без «где» или «горизонта» не замысел'}>
          {занято ? 'Принимаю…' : 'Принять замысел'}
        </button>
        <button type="button" onClick={() => сохранить(false)} disabled={занято}
          title="сохранить черновиком: сцена 3 останется закрытой">
          сохранить черновик
        </button>
      </div>
    </div>
    </div>
  )
}

/**
 * Действие по находке словами. Перечень предлагает сервер кодами, а слово
 * нужно человеку: вторых вердиктов здесь нет — вердикт и вопрос приходят с
 * сервера готовыми словами и печатаются как есть.
 */
/** Слова действий сверки — одной таблицей на весь клиент (words.ts). */
const ДЕЙСТВИЕ: Record<ReconcileAction, string> = ДЕЙСТВИЕ_СВЕРКИ as Record<ReconcileAction, string>

/** Дата провенанса по-человечески: 12.09.2026, а не машинная запись. */
function датаКратко(когда: string): string {
  const [г, м, д] = когда.slice(0, 10).split('-')
  return д && м && г ? `${д}.${м}.${г}` : когда
}

/** Провенанс кандидата: у руки эксперта якоря нет — есть учётка, роль и дата. */
function провенанс(источник: ReconcileItem['source']): string {
  return 'account' in источник
    ? `эксперт: ${источник.account}, ${источник.role}, ${датаКратко(источник.at)}`
    : `документ ${источник.material}, якорь ${источник.anchor}`
}

/**
 * Ручной ввод идёт через сверку (СВЕРКА-РУЧНОГО-ВВОДА, manual_input_rule).
 *
 * Очередь кандидатов копится, пока человек печатает, и уходит ОДНИМ запросом:
 * молчание 1200 мс, уход из поля либо нажатие «Сверить». Десять строк ввода —
 * один запуск и одна запись в журнале ИИ; окно накопления тут и есть цена
 * токенов.
 *
 * Сверка ничего не меняет: она заводит кандидат-факты и запуск. Модель
 * меняется только решением человека по находке.
 */
function useСверка(project: string) {
  const [run, setRun] = useState<ReconcileRun | null>(null)
  const [ждущие, setЖдущие] = useState<string[]>([])
  const [сверяю, setСверяю] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Чего сверке не хватает от человека: подсказка, а не отказ. */
  const [подсказка, setПодсказка] = useState<string | null>(null)
  /** Поле знаний v2 на проекте выключено: ввод идёт прежним порядком. */
  const [выключена, setВыключена] = useState(false)
  const [автор, setАвтор] = useState(прочитатьАвтора)
  const [роль, setРоль] = useState(прочитатьРоль)
  const [поСмыслу, setПоСмыслу] = useState(true)
  const очередь = useRef<Record<string, ReconcileCandidate>>({})
  const таймер = useRef<number | null>(null)
  const свежее = useRef({ автор, роль, поСмыслу, выключена })
  свежее.current = { автор, роль, поСмыслу, выключена }

  useEffect(() => () => { if (таймер.current !== null) window.clearTimeout(таймер.current) }, [])

  /** Карточку сверки забывает ИЗМЕНЁННЫЙ ввод: прежняя находка была о другом. */
  const забыть = (localId: string) =>
    setRun((р) => (р ? { ...р, items: р.items.filter((п) => п.local_id !== localId) } : р))

  const сверить = () => {
    if (таймер.current !== null) { window.clearTimeout(таймер.current); таймер.current = null }
    const пакет = Object.values(очередь.current)
    const { автор: кто, роль: чем, поСмыслу: смысл, выключена: нет } = свежее.current
    if (пакет.length === 0 || нет) return
    // Без имени и роли сверки не бывает: ручной ввод — это факт эксперта, а
    // у него есть учётка, роль и дата. Это подсказка, а не отказ: на проекте
    // без поля знаний v2 ввод и так сохраняется прежним порядком.
    if (!кто.trim() || !чем.trim()) {
      setПодсказка('назовите себя и роль — тогда ввод пойдёт через сверку')
      return
    }
    очередь.current = {}
    setЖдущие([])
    setСверяю(true)
    setОтказ(null)
    setПодсказка(null)
    api.reconcile(project, пакет, кто, чем, смысл)
      .then(setRun)
      .catch((e) => {
        if (e instanceof ServerRefusal && e.status === 409) setВыключена(true)
        else setОтказ(отказСловами(e))
      })
      .finally(() => setСверяю(false))
  }

  const поставить = (кандидат: ReconcileCandidate) => {
    if (свежее.current.выключена) return
    очередь.current = { ...очередь.current, [кандидат.local_id]: кандидат }
    setЖдущие(Object.keys(очередь.current))
    забыть(кандидат.local_id)
    if (таймер.current !== null) window.clearTimeout(таймер.current)
    таймер.current = window.setTimeout(сверить, 1200)
  }

  const убрать = (localId: string) => {
    const оставшиеся = { ...очередь.current }
    delete оставшиеся[localId]
    очередь.current = оставшиеся
    setЖдущие(Object.keys(оставшиеся))
  }

  /** Карточка кандидата по местному номеру строки ввода: находки вернулись в неё. */
  const предмет = (localId: string): ReconcileItem | null =>
    run?.items.find((п) => п.local_id === localId) ?? null

  /** Ссылка на сверку для ворот сохранения: запуск и местный номер строки. */
  const ссылка = (localId: string): string | null =>
    run && предмет(localId) ? `${run.run}#${localId}` : null

  return {
    run, setRun, ждущие, сверяю, отказ, подсказка, выключена, автор, setАвтор, роль, setРоль,
    поСмыслу, setПоСмыслу, поставить, убрать, забыть, сверить, предмет, ссылка,
  }
}

/** Кто вводит: без имени и роли ручной факт эксперта не заводится. */
function КтоВводит({ автор, роль, onАвтор, onРоль }: {
  автор: string
  роль: string
  onАвтор: (имя: string) => void
  onРоль: (роль: string) => void
}) {
  return (
    <>
      <label title="учётка автора: у ручного факта эксперта нет якоря — есть человек">кто вводит
        <input value={автор} placeholder="Иванов"
          onChange={(e) => { const имя = e.target.value; onАвтор(имя); запомнитьАвтора(имя) }} />
      </label>
      <label title="роль в проекте: она уходит в провенанс ручного факта">роль
        <input value={роль} placeholder="ведущий СИ"
          onChange={(e) => { const что = e.target.value; onРоль(что); запомнитьРоль(что) }} />
      </label>
    </>
  )
}

/**
 * Находки сверки под формой ввода: что нашли, чем сравнивали и что
 * предлагается сделать. Одно действие на карточку, и делает его человек —
 * ни одного слияния и ни одной правки без нажатия.
 */
function ПанельСверки({ project, run, автор, onApplied }: {
  project: string
  run: ReconcileRun
  автор: string
  onApplied: (итог: string) => void
}) {
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [ask, askConfirm, closeConfirm] = useConfirm()

  const решить = (item: ReconcileItem, номер: number, действие: ReconcileAction, цель: string | null) => {
    askConfirm({
      question: `${ДЕЙСТВИЕ[действие]}: кандидат «${item.local_id}»`
        + `${цель ? ` и принятое ${цель}` : ''}. Решение меняет модель — отменить его нельзя.`,
      ok: ДЕЙСТВИЕ[действие],
      input: { label: 'почему так решили', placeholder: 'та же нужда другими словами', required: true },
      onOk: (причина) => {
        setЗанято(true)
        setОтказ(null)
        api.reconcileApply(project, run.run, {
          local_id: item.local_id, finding: номер, action: действие,
          target: цель ?? undefined, reason: причина, author: автор,
        })
          .then((и) => onApplied(`${и.note} · доля знаний ${и.coverage}%`))
          .catch((e) => setОтказ(отказСловами(e)))
          .finally(() => setЗанято(false))
      },
    })
  }

  return (
    <div className="v2-form" data-why="почему-нельзя">
      <div className="v2-note-line">
        Сверка {run.run} · {run.note}
        {run.ai_called ? ' · служба спрошена по смыслу' : ' · по ключу, без вызова службы'}
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {run.items.map((п) => (
        <div key={п.local_id} className="v2-card__body">
          <div>
            <span className="v2-mono">{п.local_id}</span> · {п.verdict_word} · {п.note}
          </div>
          <div className="v2-dim">{провенанс(п.source)} · кандидат-факт {п.candidate_fact}</div>
          {п.blocking.length > 0 && (
            <div className="v2-warn">не закрыто обязательное: {п.blocking.join(' · ')}</div>
          )}
          {п.decided && <div className="v2-dim">решение принято: {ДЕЙСТВИЕ[п.decided]}</div>}
          {п.findings.length === 0 && <div className="v2-dim">находок нет: сверять было не с чем</div>}
          <ul className="v2-checks">
            {п.findings.map((н, номер) => (
              <li key={`${п.local_id}-${номер}`} className={н.blocking ? 'v2-check v2-check--no' : 'v2-check'}>
                <span>{н.blocking ? '☐' : '·'}</span>
                <span className="v2-check__t">
                  <b>{н.question_word}</b>
                  {н.target && <span className="v2-mono"> {н.target}</span>}
                  <span className="v2-dim"> · нашли {н.match} · уверенность {н.confidence}</span>
                  {н.missing && <span className="v2-warn"> · не закрыто: {н.missing}</span>}
                  {н.difference && (
                    <div>
                      {н.difference.field}: у вас «{н.difference.mine}», в принятом «{н.difference.theirs}»
                      {' — '}{н.difference.comparison}
                      {н.difference.reason && <span className="v2-dim"> ({н.difference.reason})</span>}
                    </div>
                  )}
                  {н.compared_fields.length > 0 && (
                    <div className="v2-dim">сравнивали по полям: {н.compared_fields.join(' · ')}</div>
                  )}
                  {н.basis.length > 0 && <div className="v2-dim">основания: {н.basis.join(' · ')}</div>}
                </span>
                {!п.decided && н.offers.map((д) => (
                  <button key={д} type="button" className="v2-link" disabled={занято || !автор.trim()}
                    title={!автор.trim()
                      ? 'назовите себя: решение по находке ставит человек'
                      : `${ДЕЙСТВИЕ[д]} — спросит причину и изменит модель`}
                    onClick={() => решить(п, номер, д, н.target)}>
                    {ДЕЙСТВИЕ[д]}
                  </button>
                ))}
              </li>
            ))}
          </ul>
        </div>
      ))}
      <ConfirmBox request={ask} onClose={closeConfirm} />
    </div>
  )
}

/** Сцена 3 — стейкхолдеры и их нужды: у каждой нужды есть носитель. */
export function SceneStakeholders({ project, onChanged }: { project: string; onChanged: () => void }) {
  const { данные, перечитать } = useПостановка(project, { факты: true })
  const [имя, setИмя] = useState('')
  const [роль, setРоль] = useState('customer')
  const [нужда, setНужда] = useState('')
  const [носитель, setНоситель] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Что сказала сверка о сохранённом: провенанс ручного факта эксперта. */
  const [след, setСлед] = useState<string | null>(null)
  const сверка = useСверка(project)
  const изменилось = () => { перечитать(); onChanged() }

  // Ворота поля знаний v2: введённое попадает в модель только со ссылкой на
  // сверку и решением человека. На проекте без флага полей в теле просто нет
  // — сервер их и не спрашивает, и прежний путь сохранения не меняется.
  const черезСверку = (localId: string, решение: string) => {
    const ссылка = сверка.ссылка(localId)
    return ссылка ? { reconcile: ссылка, decision: решение } : {}
  }

  const добавитьСторону = () => {
    setОтказ(null)
    const карточка = сверка.предмет('c2')
    api.addStakeholder(project, {
      name: имя, role: роль, author: сверка.автор || undefined,
      ...черезСверку('c2', 'завести новой стороной'),
    })
      .then(() => {
        setИмя('')
        сверка.убрать('c2')
        сверка.забыть('c2')
        setСлед(карточка ? `сторона заведена · ${провенанс(карточка.source)}` : null)
        изменилось()
      })
      .catch((e) => setОтказ(отказСловами(e)))
  }

  const добавитьНужду = () => {
    setОтказ(null)
    const карточка = сверка.предмет('c1')
    api.addNeed(project, {
      statement: нужда, owner: носитель, author: сверка.автор || undefined,
      ...черезСверку('c1', 'завести новой нуждой'),
    })
      .then(() => {
        setНужда('')
        сверка.убрать('c1')
        сверка.забыть('c1')
        setСлед(карточка ? `нужда заведена · ${провенанс(карточка.source)}` : null)
        изменилось()
      })
      .catch((e) => setОтказ(отказСловами(e)))
  }

  /** Кандидат нужды в очередь: сверка уйдёт одним запросом со всем пакетом. */
  const вОчередьНужды = (текст: string, владелец: string) => {
    if (!текст.trim() || !владелец) { сверка.убрать('c1'); return }
    сверка.поставить({
      local_id: 'c1', concept: 'need', origin: 'manual',
      payload: { statement: текст.trim(), owner: владелец },
    })
  }

  const вОчередьСтороны = (текст: string, чем: string) => {
    if (!текст.trim()) { сверка.убрать('c2'); return }
    сверка.поставить({
      local_id: 'c2', concept: 'stakeholder', origin: 'manual',
      payload: { name: текст.trim(), role: чем },
    })
  }

  const карточкаНужды = сверка.предмет('c1')
  const держит = карточкаНужды?.blocking ?? []

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {сверка.отказ && <div className="v2-locked">{сверка.отказ}</div>}
      {след && <div className="v2-note-line">{след}</div>}
      {!сверка.выключена && (
        <div className="v2-form v2-form--row">
          <КтоВводит автор={сверка.автор} роль={сверка.роль}
            onАвтор={сверка.setАвтор} onРоль={сверка.setРоль} />
          <label className="v2-check" title="вторая ступень сверки: там, где ключ не совпал, службу спрашивают по смыслу — это токены">
            <input type="checkbox" checked={сверка.поСмыслу}
              onChange={(e) => сверка.setПоСмыслу(e.target.checked)} />
            спрашивать по смыслу
          </label>
          <button type="button" disabled={сверка.сверяю || сверка.ждущие.length === 0}
            title={сверка.ждущие.length === 0
              ? 'очередь пуста: сверять нечего — наберите строку ввода'
              : `сверить не дожидаясь паузы: в очереди строк ${сверка.ждущие.length}`}
            onClick={сверка.сверить}>
            {сверка.сверяю ? 'Сверяю…' : 'Сверить'}
          </button>
          <span className="v2-dim">
            {сверка.подсказка
              ?? (сверка.ждущие.length > 0
                ? `в очереди: ${сверка.ждущие.join(' · ')} — уйдут одним запросом`
                : 'ввод уходит на сверку одним запросом')}
          </span>
        </div>
      )}
      <div className="v2-form v2-form--row">
        <input value={имя} placeholder="Минтранс России" aria-label="новая сторона"
          onChange={(e) => { const текст = e.target.value; setИмя(текст); вОчередьСтороны(текст, роль) }}
          onBlur={сверка.сверить} />
        <select value={роль} title="роль стороны в проекте" aria-label="роль новой стороны"
          onChange={(e) => { const что = e.target.value; setРоль(что); вОчередьСтороны(имя, что) }}>
          {(данные.виды.stakeholder?.enums?.role ?? ['customer', 'regulator', 'operator', 'consumer', 'partner', 'established']).map((к) => (
            <option key={к} value={к}>{данные.виды.stakeholder?.enum_labels?.role?.[к] ?? к}</option>
          ))}
        </select>
        <button type="button" onClick={добавитьСторону} disabled={!имя.trim()}
          title={имя.trim() ? 'завести сторону' : 'назовите сторону'}>Добавить сторону</button>
      </div>

      <РеестрСторон project={project} данные={данные} onChanged={изменилось}
        onНуждаИзИнтереса={(формулировка, код) => { setНужда(формулировка); setНоситель(код); вОчередьНужды(формулировка, код) }} />

      <div className="v2-form v2-form--row">
        <input value={нужда} placeholder="перевозчику нужна телеметрия груза в пути" aria-label="новая нужда"
          onChange={(e) => { const текст = e.target.value; setНужда(текст); вОчередьНужды(текст, носитель) }}
          onBlur={сверка.сверить} />
        <select value={носитель} title="носитель нужды" aria-label="носитель новой нужды"
          onChange={(e) => { const кто = e.target.value; setНоситель(кто); вОчередьНужды(нужда, кто) }}>
          <option value="">— чья нужда —</option>
          {данные.стороны.map((с) => <option key={с.id} value={с.code}>{String(с.doc.name ?? с.code)}</option>)}
        </select>
        <button type="button" onClick={добавитьНужду}
          disabled={!нужда.trim() || !носитель || держит.length > 0}
          title={!носитель ? 'у нужды обязан быть носитель — иначе за неё никто не отвечает'
            : держит.length > 0
              ? `сверка держит: ${держит.join(' · ')} — закройте это решением по находке, и кнопка оживёт`
              : 'завести нужду'}>
          Добавить нужду
        </button>
      </div>

      {/* Находки сверки — под формой ввода, а не поверх неё: подсказка
          показывается там, где человек печатает, и решает он сам. */}
      {сверка.run && (
        <ПанельСверки project={project} run={сверка.run} автор={сверка.автор}
          onApplied={(итог) => {
            setСлед(итог)
            api.reconcileRun(project, сверка.run!.run).then(сверка.setRun).catch(() => undefined)
            изменилось()
          }} />
      )}

      <РеестрНужд project={project} данные={данные} onChanged={изменилось} />
    </div>
  )
}

/** Сцена 4 — цели: показатель, год и связь с нуждами. */
/**
 * Критерии оценки миссии — поверхность сцены 4 (журнал ПМИ-7, З-08; Романов
 * 0.5: критерии после целей). Базовый набор — из истины (покрытие A′ · P95 ·
 * стоимость ЖЦ · риск TRL) предложением; порог — TBR до сцены 7. Сцена 4
 * закрывается и без критериев: их минимум — ноль.
 */
function КритерииОценкиМиссии({ project, onChanged }: { project: string; onChanged: () => void }) {
  type Критерий = { code: string; key: string; title: string; group: string; worse_if: string; threshold: number | null }
  type База = { key: string; title: string; direction: string; group: string; worse_if: string }
  const [критерии, setКритерии] = useState<Критерий[]>([])
  const [база, setБаза] = useState<База[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const ГРУППА: Record<string, string> = { A: 'покрытие', B: 'задержка', V: 'стоимость', G: 'риск' }
  const перечитать = () => {
    api.criteria(project).then((r) => setКритерии(r.items)).catch(() => setКритерии([]))
    api.criteriaBase().then((r) => setБаза(r.items)).catch(() => setБаза([]))
  }
  useEffect(перечитать, [project])
  const нехватает = база.filter((б) => !критерии.some((к) => к.key === б.key))
  const предложить = () => {
    setЗанято(true); setОтказ(null)
    Promise.all(нехватает.map((б) => api.setCriterion(project, {
      key: б.key, title: б.title, group: б.group, direction: б.direction, scene: '4', author: 'инженер',
    })))
      .then(() => { перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }
  const порог = (к: Критерий, значение: string) => {
    const число = значение.trim() === '' ? null : Number(значение)
    if (число !== null && Number.isNaN(число)) { setОтказ(`порог «${значение}» — не число`); return }
    if (число === к.threshold) return
    api.setCriterion(project, { key: к.key, threshold: число, author: 'инженер' })
      .then(перечитать)
      .catch((e) => setОтказ(String(e.message ?? e)))
  }
  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Критерии оценки миссии</span>
        <span className="v2-card__count">{критерии.length}{критерии.some((к) => к.threshold === null) ? ` · порог TBR ${критерии.filter((к) => к.threshold === null).length}` : ''}</span>
      </div>
      <span className="v2-empty__why">
        Чем сравнивать варианты на сцене 7: критерий называется здесь, порог — числом к сцене 7 (до него TBR). Выход мероприятия 0.5, минимум — ноль.
      </span>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {критерии.length > 0 && (
        <table className="v2-table">
          <thead><tr><th>Критерий</th><th>Группа</th><th>Хуже, если</th><th>Порог</th></tr></thead>
          <tbody>
            {критерии.map((к) => (
              <tr key={к.code}>
                <td><span className="v2-mono">{к.key}</span> {к.title}</td>
                <td>{ГРУППА[к.group] ?? к.group}</td>
                <td>{к.worse_if === 'less' ? 'меньше' : 'больше'}</td>
                <td>
                  <input type="number" defaultValue={к.threshold ?? ''} placeholder="TBR" aria-label={`порог критерия ${к.key}`}
                    onBlur={(e) => порог(к, e.target.value)} style={{ width: 90 }} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {нехватает.length > 0 && (
        <div className="v2-form__actions">
          <button type="button" className="v2-chip" disabled={занято} onClick={предложить}
            title={`завести базовый набор истины без порогов: ${нехватает.map((б) => б.title).join(' · ')}`}>
            {занято ? 'Завожу…' : `Предложить базовый набор (${нехватает.length})`}
          </button>
        </div>
      )}
    </div>
  )
}

export function SceneGoals({ project, onChanged }: { project: string; onChanged: () => void }) {
  const { данные, перечитать } = useПостановка(project)
  const { нужды, стороны } = данные
  const [формулировка, setФормулировка] = useState('')
  const [год, setГод] = useState('2033')
  const [покрывает, setПокрывает] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const изменилось = () => { перечитать(); onChanged() }

  const добавить = () => {
    setОтказ(null)
    api.addGoal(project, { statement: формулировка, year: Number(год), covers: покрывает })
      .then(() => { setФормулировка(''); setПокрывает([]); изменилось() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const безЦели = нужды.filter((n) => (n.covered_by ?? []).length === 0)
  /**
   * Нужды — по формулировке, носители перечнем (журнал ПМИ-7, З-06): до
   * миграции «нужда — много носителей» одна и та же нужда лежит копией у
   * каждой стороны, и список показывал «суверенитет данных» четырьмя строками.
   * Галка группы отмечает все её копии: цель закрывает нужду у всех носителей.
   */
  const группыНужд = (() => {
    const ключ = (s: string) => s.toLowerCase().replace(/ё/g, 'е').replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
    const карта = new Map<string, { формулировка: string; копии: typeof нужды }>()
    нужды.forEach((n) => {
      const к = ключ(String(n.doc.statement ?? n.code))
      const г = карта.get(к) ?? { формулировка: String(n.doc.statement ?? n.code), копии: [] }
      г.копии.push(n); карта.set(к, г)
    })
    return [...карта.values()]
  })()
  // Носители нужды — связями owns (истина 24.09: нужда — много носителей);
  // прежнее строковое поле stakeholder читается, пока копии не смигрированы.
  const носитель = (n: (typeof нужды)[number]) => {
    const поСвязям = (n.owned_by ?? []).map((id) => стороны.find((с) => с.id === id)?.code ?? id)
    if (поСвязям.length > 0) return поСвязям.join(', ')
    const с = n.doc.stakeholder
    return typeof с === 'string' ? с : (с && typeof с === 'object' && 'code' in (с as object)) ? String((с as { code?: string }).code ?? '') : ''
  }

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form">
        <label>Цель с измеримым результатом
          <input value={формулировка} onChange={(e) => setФормулировка(e.target.value)}
            placeholder="отслеживаемость 100% объектов перечня" />
        </label>
        <label>Год
          <input value={год} onChange={(e) => setГод(e.target.value)} style={{ width: 90 }} />
        </label>
        <div>
          <div className="v2-empty__why">Какие нужды закрывает ({группыНужд.length} формулировок · {нужды.length} записей):</div>
          {группыНужд.map((г) => {
            const коды = г.копии.map((n) => n.code)
            const все = коды.every((к) => покрывает.includes(к))
            return (
              <label key={коды.join('+')} className="v2-check">
                <input type="checkbox" checked={все} aria-label={`нужда «${г.формулировка}»`}
                  onChange={(e) => setПокрывает(e.target.checked
                    ? [...покрывает.filter((x) => !коды.includes(x)), ...коды]
                    : покрывает.filter((x) => !коды.includes(x)))} />
                {г.формулировка}
                {г.копии.length > 1 && (
                  <span className="v2-dim"> · носители: {г.копии.map((n) => носитель(n) || n.code).join(', ')}</span>
                )}
              </label>
            )
          })}
        </div>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" onClick={добавить}
            disabled={!формулировка.trim() || покрывает.length === 0}
            title={покрывает.length === 0
              ? 'цель, не закрывающая ни одной нужды, ни к чему не ведёт'
              : 'завести цель'}>
            Добавить цель
          </button>
        </div>
      </div>

      <РеестрЦелей project={project} данные={данные} onChanged={изменилось} />

      {безЦели.length > 0 && (
        <div className="v2-warn">
          Нужд без цели: {безЦели.length} — пока они есть, сцена 4 не закроется.
        </div>
      )}
      <КритерииОценкиМиссии project={project} onChanged={onChanged} />
    </div>
  )
}

export function SceneConstraints({ project, onChanged }: { project: string; onChanged: () => void }) {
  const { данные, перечитать } = useПостановка(project)
  const [текст, setТекст] = useState('')
  const [категория, setКатегория] = useState('technical')
  const [отказ, setОтказ] = useState<string | null>(null)
  const изменилось = () => { перечитать(); onChanged() }
  const вид = данные.виды.constraint

  const добавить = () => {
    setОтказ(null)
    api.addConstraint(project, { text: текст, category: категория })
      .then(() => { setТекст(''); изменилось() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form v2-form--row">
        <input value={текст} onChange={(e) => setТекст(e.target.value)} aria-label="новое ограничение"
          placeholder="полезная нагрузка — только регенеративная" />
        <select value={категория} onChange={(e) => setКатегория(e.target.value)} aria-label="тип нового ограничения"
          title="тип ограничения — полем, а не буквой кода">
          {(вид?.enums?.type ?? ['technical', 'programmatic', 'launch', 'regulatory', 'financial']).map((к) => (
            <option key={к} value={к}>{вид?.enum_labels?.type?.[к] ?? к}</option>
          ))}
        </select>
        <button type="button" onClick={добавить} disabled={!текст.trim()}
          title={текст.trim() ? 'завести ограничение с кодом Р-серии' : 'сформулируйте ограничение'}>
          Добавить
        </button>
      </div>

      <РеестрОграничений project={project} данные={данные} onChanged={изменилось} />
    </div>
  )
}

/** Сцена 6 — сервисы: что система даёт кому и с каким качеством. */
export function SceneServices({ project, onChanged }: { project: string; onChanged: () => void }) {
  const { данные, перечитать } = useПостановка(project)
  const { нужды } = данные
  const [имя, setИмя] = useState('')
  const [класс, setКласс] = useState('B′')
  const [покрывает, setПокрывает] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const изменилось = () => { перечитать(); onChanged() }

  const добавить = () => {
    setОтказ(null)
    api.addService(project, { name: имя, qos_class: класс, covers: покрывает })
      .then(() => { setИмя(''); setПокрывает([]); изменилось() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const безСервиса = нужды.filter((n) => (n.covered_by ?? []).every((c) => !c.startsWith('service')))

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form">
        <label>Сервис
          <input value={имя} onChange={(e) => setИмя(e.target.value)}
            placeholder="передача коротких сообщений от датчиков" />
        </label>
        <label>Класс обслуживания
          <select value={класс} onChange={(e) => setКласс(e.target.value)}>
            {КЛАССЫ.map(([v, t]) => <option key={v} value={v}>{t}</option>)}
          </select>
        </label>
        <div>
          <div className="v2-empty__why">Какие нужды покрывает:</div>
          {нужды.map((n) => (
            <label key={n.id} className="v2-check">
              <input type="checkbox" checked={покрывает.includes(n.code)}
                onChange={(e) => setПокрывает(e.target.checked
                  ? [...покрывает, n.code]
                  : покрывает.filter((x) => x !== n.code))} />
              {String(n.doc.statement ?? n.code)}
            </label>
          ))}
        </div>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" onClick={добавить}
            disabled={!имя.trim() || покрывает.length === 0}
            title={покрывает.length === 0
              ? 'сервис, не покрывающий ни одной нужды, никому не нужен'
              : 'завести сервис'}>
            Добавить сервис
          </button>
        </div>
      </div>

      <РеестрСервисов project={project} данные={данные} onChanged={изменилось} />

      {безСервиса.length > 0 && (
        <div className="v2-warn">
          Нужд без сервиса: {безСервиса.length} — пока они есть, сцена 6 не закроется.
        </div>
      )}

      {/* Класс обслуживания у покрытых нужд закрывается здесь (TBR до сцены 6):
          тот же реестр нужд, открытый на чипе «TBR», с массовым «назначить класс». */}
      <РеестрНужд project={project} данные={данные} onChanged={изменилось} отбор="tbr" />
    </div>
  )
}
