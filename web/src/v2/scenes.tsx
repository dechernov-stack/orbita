// Сцены 1–4 Pre-A (СЦЕНАРИЙ-PRE-A). Каждая сцена — один вопрос и его выход.
//
// Ни одна сцена не решает, открыта ли она: это сказал сервер. Здесь только
// формы и списки, встроенные в рамку.
import React, { useEffect, useRef, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import {
  api, ServerRefusal,
  type EntityRow, type ReconcileAction, type ReconcileCandidate, type ReconcileItem, type ReconcileRun,
} from './api'
import { Source } from './knowledgefield'
import { запомнитьАвтора, запомнитьРоль, отказСловами, прочитатьАвтора, прочитатьРоль } from './research'

/**
 * З-03: правка принятой сущности на месте — карандаш в строке, поля в той же
 * строке, новая версия с провенансом «правка инженера». Пустое значение
 * снимает поле. Что править — задаёт вызывающая сцена списком полей.
 */
type Поле = { key: string; label: string; kind?: 'text' | 'number' | 'select'; options?: [string, string][] }

function ПравкаСтроки({ project, row, поля, colSpan, onSaved, onCancel }: {
  project: string; row: EntityRow; поля: Поле[]; colSpan: number; onSaved: () => void; onCancel: () => void
}) {
  const [значения, setЗначения] = useState<Record<string, string>>(
    Object.fromEntries(поля.map((п) => [п.key, row.doc[п.key] == null ? '' : String(row.doc[п.key])])),
  )
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const сохранить = () => {
    setЗанято(true); setОтказ(null)
    const fields: Record<string, unknown> = {}
    поля.forEach((п) => {
      const v = значения[п.key]
      fields[п.key] = п.kind === 'number' ? (v === '' ? '' : Number(v)) : v
    })
    api.patchEntity(project, row.code, fields, 'инженер')
      .then(() => { setЗанято(false); onSaved() })
      .catch((e) => { setЗанято(false); setОтказ(String(e.message ?? e)) })
  }
  return (
    <tr className="v2-row--edit">
      <td colSpan={colSpan}>
        <div className="v2-form v2-form--row" data-why="работа">
          <span className="v2-mono">{row.code}</span>
          {поля.map((п) => (
            <label key={п.key} title={п.label}>
              {п.label}
              {п.kind === 'select' ? (
                <select value={значения[п.key]} onChange={(e) => setЗначения({ ...значения, [п.key]: e.target.value })}>
                  <option value="">—</option>
                  {(п.options ?? []).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
                </select>
              ) : (
                <input type={п.kind === 'number' ? 'number' : 'text'} value={значения[п.key]}
                  onChange={(e) => setЗначения({ ...значения, [п.key]: e.target.value })} />
              )}
            </label>
          ))}
          <button type="button" className="v2-primary" onClick={сохранить} disabled={занято}
            title={занято ? 'сохраняю' : 'сохранить новой версией — провенанс «правка инженера»'}>Сохранить</button>
          <button type="button" onClick={onCancel} title="отменить правку, ничего не менять">Отмена</button>
          {отказ && <span className="v2-locked">{отказ}</span>}
        </div>
      </td>
    </tr>
  )
}

function Карандаш({ onClick }: { onClick: () => void }) {
  return <button type="button" className="v2-link" onClick={onClick} title="править на месте: новая версия, провенанс «правка инженера»">✎</button>
}

const РОЛИ_СТОРОН: [string, string][] = [
  ['customer', 'заказчик'], ['regulator', 'регулятор'], ['operator', 'оператор'], ['consumer', 'потребитель'],
  ['supplier', 'поставщик'], ['partner', 'партнёр'], ['established', 'учреждаемый'],
]
const ВЛИЯНИЕ: [string, string][] = [['decides', 'решает'], ['influences', 'влияет'], ['informed', 'информируется']]
const ОТНОШЕНИЕ: [string, string][] = [['supports', 'поддерживает'], ['neutral', 'нейтрален'], ['resists', 'сопротивляется']]

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
            onChange={(e) => setПоля({ ...поля, [поле]: e.target.value })} />
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
const ДЕЙСТВИЕ: Record<ReconcileAction, string> = {
  accept_new: 'завести новым',
  merge_into: 'слить в принятое',
  refine: 'уточнить принятое',
  generalize: 'обобщить принятое',
  link_basis: 'привязать основание',
  mark_contested: 'пометить спорным',
  fix_input: 'поправить ввод',
  dismiss: 'отклонить находку',
}

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
  const [стороны, setСтороны] = useState<EntityRow[]>([])
  const [нужды, setНужды] = useState<EntityRow[]>([])
  const [имя, setИмя] = useState('')
  const [роль, setРоль] = useState('customer')
  const [нужда, setНужда] = useState('')
  const [носитель, setНоситель] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Что сказала сверка о сохранённом: провенанс ручного факта эксперта. */
  const [след, setСлед] = useState<string | null>(null)
  const сверка = useСверка(project)

  const перечитать = () => {
    api.entities(project, 'stakeholder').then((r) => setСтороны(r.items)).catch(() => undefined)
    api.entities(project, 'need').then((r) => setНужды(r.items)).catch(() => undefined)
  }
  useEffect(перечитать, [project])

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
        перечитать()
        onChanged()
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
        перечитать()
        onChanged()
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

  const нуждыСтороны = (id: string) => нужды.filter((n) => (n.owned_by ?? []).includes(id))
  const [правка, setПравка] = useState<string | null>(null)
  const [открыта, setОткрыта] = useState<string | null>(null)

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
        <input value={имя} placeholder="Минтранс России"
          onChange={(e) => { const текст = e.target.value; setИмя(текст); вОчередьСтороны(текст, роль) }}
          onBlur={сверка.сверить} />
        <select value={роль} title="роль стороны в проекте"
          onChange={(e) => { const что = e.target.value; setРоль(что); вОчередьСтороны(имя, что) }}>
          <option value="customer">заказчик</option>
          <option value="regulator">регулятор</option>
          <option value="operator">оператор</option>
          <option value="consumer">потребитель</option>
          <option value="partner">партнёр</option>
          <option value="established">учреждаемый</option>
        </select>
        <button type="button" onClick={добавитьСторону} disabled={!имя.trim()}
          title={имя.trim() ? 'завести сторону' : 'назовите сторону'}>Добавить сторону</button>
      </div>

      <table className="v2-table">
        <thead><tr><th>Код</th><th>Сторона</th><th>Роль · влияние</th><th>Нужды</th></tr></thead>
        <tbody>
          {стороны.map((с) => открыта === с.code && правка !== с.code ? (
            <React.Fragment key={с.id}>
              <tr className="v2-card-row">
                <td className="v2-mono">{с.code} <Карандаш onClick={() => setПравка(с.code)} /> <button type="button" className="v2-link" onClick={() => setОткрыта(null)} title="свернуть карточку">▴</button></td>
                <td colSpan={3}>
                  <div className="v2-card__body" data-why="работа">
                    <div><b>{String(с.doc.name ?? '')}</b> · роль {РОЛИ_СТОРОН.find(([v]) => v === с.doc.role)?.[1] ?? String(с.doc.role ?? '')}
                      {с.doc.scale ? <span> · масштаб {String(с.doc.scale)}</span> : null}</div>
                    <div>влияние: {ВЛИЯНИЕ.find(([v]) => v === с.doc.influence)?.[1] ?? <span className="v2-warn">не задано — карандаш</span>}
                      {с.doc.power ? <span> · сила {String(с.doc.power)} из 5</span> : null}
                      {с.doc.attitude ? <span> · {ОТНОШЕНИЕ.find(([v]) => v === с.doc.attitude)?.[1]}</span> : null}</div>
                    {с.doc.interest ? <div>интерес: {String(с.doc.interest)}</div> : null}
                    <div>нужды ({нуждыСтороны(с.id).length}):
                      {нуждыСтороны(с.id).length === 0 ? <span className="v2-warn"> нет — сцена не закроется</span> : null}
                      <ul>{нуждыСтороны(с.id).map((n) => <li key={n.id}>{String(n.doc.statement ?? '')}{n.doc.notes ? <span className="v2-muted"> · {String(n.doc.notes)}</span> : null}</li>)}</ul>
                    </div>
                    {с.doc.notes ? <div className="v2-muted">основания: {String(с.doc.notes)}</div> : null}
                    <div className="v2-muted">версия {String((с as unknown as { version?: number }).version ?? '')} · статус {с.status}</div>
                  </div>
                </td>
              </tr>
            </React.Fragment>
          ) : правка === с.code ? (
            <ПравкаСтроки key={с.id} project={project} row={с} colSpan={4}
              поля={[
                { key: 'name', label: 'имя' }, { key: 'role', label: 'роль', kind: 'select', options: РОЛИ_СТОРОН },
                { key: 'interest', label: 'интерес' },
                { key: 'influence', label: 'влияние', kind: 'select', options: ВЛИЯНИЕ }, { key: 'power', label: 'сила 1–5', kind: 'number' },
                { key: 'attitude', label: 'отношение', kind: 'select', options: ОТНОШЕНИЕ },
              ]}
              onSaved={() => { setПравка(null); перечитать(); onChanged() }} onCancel={() => setПравка(null)} />
          ) : (
            <tr key={с.id}>
              <td className="v2-mono">{с.code} <Карандаш onClick={() => setПравка(с.code)} /> <button type="button" className="v2-link" onClick={() => setОткрыта(с.code)} title="карточка стороны: кто и какой, влияние, нужды, основания">▾</button></td>
              <td>{String(с.doc.name ?? '')}</td>
              <td>
                {String(с.doc.role ?? '')}
                {с.doc.influence ? <span className="v2-muted"> · {String(с.doc.influence)}{с.doc.power ? ` ${String(с.doc.power)}` : ''}</span> : null}
              </td>
              <td>
                {нуждыСтороны(с.id).length === 0
                  ? <span className="v2-warn">нужд нет — сцена не закроется</span>
                  : нуждыСтороны(с.id).map((n) => правка === n.code ? (
                    <ПравкаСтроки key={n.id} project={project} row={n} colSpan={1}
                      поля={[{ key: 'statement', label: 'формулировка' }]}
                      onSaved={() => { setПравка(null); перечитать(); onChanged() }} onCancel={() => setПравка(null)} />
                  ) : (
                    <div key={n.id}>{String(n.doc.statement ?? '')} <Карандаш onClick={() => setПравка(n.code)} /></div>
                  ))}
              </td>
            </tr>
          ))}
          {стороны.length === 0 && (
            <tr><td colSpan={4} className="v2-empty">
              Сторон пока нет.
              <span className="v2-empty__why">Круг шире потребителей: регуляторы, операторы, учреждаемые организации.</span>
            </td></tr>
          )}
        </tbody>
      </table>

      <div className="v2-form v2-form--row">
        <input value={нужда} placeholder="перевозчику нужна телеметрия груза в пути"
          onChange={(e) => { const текст = e.target.value; setНужда(текст); вОчередьНужды(текст, носитель) }}
          onBlur={сверка.сверить} />
        <select value={носитель} title="носитель нужды"
          onChange={(e) => { const кто = e.target.value; setНоситель(кто); вОчередьНужды(нужда, кто) }}>
          <option value="">— чья нужда —</option>
          {стороны.map((с) => <option key={с.id} value={с.code}>{String(с.doc.name ?? с.code)}</option>)}
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
            перечитать()
            onChanged()
          }} />
      )}
    </div>
  )
}

/** Сцена 4 — цели: показатель, год и связь с нуждами. */
export function SceneGoals({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [цели, setЦели] = useState<EntityRow[]>([])
  const [нужды, setНужды] = useState<EntityRow[]>([])
  const [формулировка, setФормулировка] = useState('')
  const [год, setГод] = useState('2033')
  const [покрывает, setПокрывает] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [правка, setПравка] = useState<string | null>(null)

  const перечитать = () => {
    api.entities(project, 'goal').then((r) => setЦели(r.items)).catch(() => undefined)
    api.entities(project, 'need').then((r) => setНужды(r.items)).catch(() => undefined)
  }
  useEffect(перечитать, [project])

  const добавить = () => {
    setОтказ(null)
    api.addGoal(project, { statement: формулировка, year: Number(год), covers: покрывает })
      .then(() => { setФормулировка(''); setПокрывает([]); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const безЦели = нужды.filter((n) => (n.covered_by ?? []).length === 0)

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
          <div className="v2-empty__why">Какие нужды закрывает:</div>
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
            disabled={!формулировка.trim() || покрывает.length === 0}
            title={покрывает.length === 0
              ? 'цель, не закрывающая ни одной нужды, ни к чему не ведёт'
              : 'завести цель'}>
            Добавить цель
          </button>
        </div>
      </div>

      <table className="v2-table">
        <thead><tr><th>Код</th><th>Цель</th><th>Год</th><th>Закрывает нужд</th></tr></thead>
        <tbody>
          {цели.map((ц) => правка === ц.code ? (
            <ПравкаСтроки key={ц.id} project={project} row={ц} colSpan={4}
              поля={[{ key: 'statement', label: 'цель' }, { key: 'year', label: 'год', kind: 'number' }]}
              onSaved={() => { setПравка(null); перечитать(); onChanged() }} onCancel={() => setПравка(null)} />
          ) : (
            <tr key={ц.id}>
              <td className="v2-mono">{ц.code} <Карандаш onClick={() => setПравка(ц.code)} /></td>
              <td>{String(ц.doc.statement ?? '')}</td>
              <td>{String(ц.doc.year ?? '')}</td>
              <td>{нужды.filter((n) => (n.covered_by ?? []).includes(ц.id)).length}</td>
            </tr>
          ))}
          {цели.length === 0 && (
            <tr><td colSpan={4} className="v2-empty">Целей пока нет.</td></tr>
          )}
        </tbody>
      </table>

      {безЦели.length > 0 && (
        <div className="v2-warn">
          Нужд без цели: {безЦели.length} — пока они есть, сцена 4 не закроется.
        </div>
      )}
    </div>
  )
}

export function SceneConstraints({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [ограничения, setОграничения] = useState<EntityRow[]>([])
  const [текст, setТекст] = useState('')
  const [категория, setКатегория] = useState('техническое')
  const [отказ, setОтказ] = useState<string | null>(null)
  const [правка, setПравка] = useState<string | null>(null)

  const перечитать = () => {
    api.entities(project, 'constraint').then((r) => setОграничения(r.items)).catch(() => undefined)
  }
  useEffect(перечитать, [project])

  const добавить = () => {
    setОтказ(null)
    api.addConstraint(project, { text: текст, category: категория })
      .then(() => { setТекст(''); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form v2-form--row">
        <input value={текст} onChange={(e) => setТекст(e.target.value)}
          placeholder="полезная нагрузка — только регенеративная" />
        <select value={категория} onChange={(e) => setКатегория(e.target.value)}
          title="группа ограничения — полем, а не буквой кода">
          <option value="техническое">техническое</option>
          <option value="программное">программное</option>
          <option value="пусковое">пусковое</option>
          <option value="регуляторное">регуляторное</option>
        </select>
        <button type="button" onClick={добавить} disabled={!текст.trim()}
          title={текст.trim() ? 'завести ограничение с кодом Р-серии' : 'сформулируйте ограничение'}>
          Добавить
        </button>
      </div>

      <table className="v2-table">
        <thead><tr><th>Код</th><th>Ограничение</th><th>Группа</th></tr></thead>
        <tbody>
          {ограничения.map((о) => правка === о.code ? (
            <ПравкаСтроки key={о.id} project={project} row={о} colSpan={3}
              поля={[{ key: 'statement', label: 'ограничение' }, { key: 'text', label: 'текст' }, { key: 'category', label: 'группа' }]}
              onSaved={() => { setПравка(null); перечитать(); onChanged() }} onCancel={() => setПравка(null)} />
          ) : (
            <tr key={о.id}>
              <td className="v2-mono" title="код стабилен: на него ссылаются промпты и трассировки">{о.code} <Карандаш onClick={() => setПравка(о.code)} /></td>
              <td>{String(о.doc.text ?? о.doc.statement ?? '')}</td>
              <td>{String(о.doc.category ?? о.doc.type ?? '')}</td>
            </tr>
          ))}
          {ограничения.length === 0 && (
            <tr><td colSpan={3} className="v2-empty">
              Ограничений пока нет.
              <span className="v2-empty__why">Рамки задаются здесь и дальше работают запретами для службы и проверок.</span>
            </td></tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

/** Сцена 6 — сервисы: что система даёт кому и с каким качеством. */
export function SceneServices({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [сервисы, setСервисы] = useState<EntityRow[]>([])
  const [нужды, setНужды] = useState<EntityRow[]>([])
  const [имя, setИмя] = useState('')
  const [класс, setКласс] = useState('B′')
  const [покрывает, setПокрывает] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [правка, setПравка] = useState<string | null>(null)

  const перечитать = () => {
    api.entities(project, 'service').then((r) => setСервисы(r.items)).catch(() => undefined)
    api.entities(project, 'need').then((r) => setНужды(r.items)).catch(() => undefined)
  }
  useEffect(перечитать, [project])

  const добавить = () => {
    setОтказ(null)
    api.addService(project, { name: имя, qos_class: класс, covers: покрывает })
      .then(() => { setИмя(''); setПокрывает([]); перечитать(); onChanged() })
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
            <option value="A′">A′ — односторонний</option>
            <option value="B′">B′ — с подтверждением</option>
            <option value="C′">C′ — оперативного управления</option>
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

      <table className="v2-table">
        <thead><tr><th>Код</th><th>Сервис</th><th>Класс</th><th>Покрывает нужд</th></tr></thead>
        <tbody>
          {сервисы.map((с) => правка === с.code ? (
            <ПравкаСтроки key={с.id} project={project} row={с} colSpan={4}
              поля={[{ key: 'name', label: 'сервис' }, { key: 'qos_class', label: 'класс' }]}
              onSaved={() => { setПравка(null); перечитать(); onChanged() }} onCancel={() => setПравка(null)} />
          ) : (
            <tr key={с.id}>
              <td className="v2-mono">{с.code} <Карандаш onClick={() => setПравка(с.code)} /></td>
              <td>{String(с.doc.name ?? '')}</td>
              <td>{String(с.doc.qos_class ?? '')}</td>
              <td>{нужды.filter((n) => (n.covered_by ?? []).includes(с.id)).length}</td>
            </tr>
          ))}
          {сервисы.length === 0 && (
            <tr><td colSpan={4} className="v2-empty">Сервисов пока нет.</td></tr>
          )}
        </tbody>
      </table>

      {безСервиса.length > 0 && (
        <div className="v2-warn">
          Нужд без сервиса: {безСервиса.length} — пока они есть, сцена 6 не закроется.
        </div>
      )}
    </div>
  )
}
