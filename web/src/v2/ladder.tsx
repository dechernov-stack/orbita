// Строка долга карточки узла — лестница зрелости к точке (27.09, ответ владельца
// «блокеры Phase A»): не панель, а та же строка «к SDR: …» карточки узла, у
// которой каждая пустая грань — действие. Грань-запись («КЕ», «Функции»,
// «Элементы обмена») — «+ завести» той же карточкой объекта вниз; грань-связь
// («Поставщик», «Модели», «Риски») — пикер. Где запись рождается на своей
// сцене (технология — A7, бюджет — A5), грань говорит, где, словами.
//
// Лестницу считает сервер (полка TCS-9002 по роду узла); здесь ни одного
// правила зрелости — только дорога от пустой грани к записи.
import { useEffect, useState } from 'react'
import { api, type ComponentCard, type EntityRow, type InterfaceRow, type ModelRow, type RiskRow } from './api'
import { useАвтор } from './research'
import { type СвойКонтрол } from './ui/objectcard'
import { НоваяЗапись } from './ui/newrecord'

/** Грань без действия здесь: где она заводится — словами. */
const ГДЕ: Record<string, string> = {
  technologies: 'технология узла — формой технологий (сцена A7): узел — её носитель',
  budgets: 'бюджет с корнем в узле — формой «Бюджеты» (сцена A5)',
  verification: 'метод верификации — у требований узла: носитель требования — этот узел',
  requirements: 'требование на узел — формой реестра требований: носитель — этот узел',
  interfaces: 'стык узла — формой «Новый стык»: этот узел — одна из сторон',
  parameters: 'величины анкеты узла — в карточке выше',
  standards: 'нормативы — грань карточки выше: пикер полки нормативов',
}

/** Действие грани: заведение записи вида либо пикер связи. */
const ДЕЙСТВИЕ: Record<string, string> = {
  configuration: '+ завести КЕ',
  functions: '+ завести функцию',
  exchange_items: '+ завести обмен и элемент обмена',
  supplier: 'назначить поставщика',
  models: 'привязать модель',
  risks: 'связать риск',
}

/**
 * Пустые грани узла по точкам, к которым их требует лестница. Порядок точек —
 * тот, в каком их называют сами грани (идентичность — к MCR, функции — к SRR…):
 * лестница сервера, не список в коде.
 */
export function граниПоТочкам(ступень: ComponentCard): [string, { key: string; title: string }[]][] {
  const порядок = [...new Set(ступень.facets.map((г) => г.required_to).filter((т): т is string => Boolean(т)))]
  const группы = new Map<string, { key: string; title: string }[]>()
  ступень.gaps.forEach((р) => {
    const грань = ступень.facets.find((г) => г.key === р.facet)
    группы.set(р.gate, [...(группы.get(р.gate) ?? []), { key: р.facet, title: грань?.title ?? р.facet }])
  })
  const место = (т: string) => (порядок.includes(т) ? порядок.indexOf(т) : порядок.length)
  return [...группы.entries()].sort(([а], [б]) => место(а) - место(б))
}

export function ДолгЛестницы({ project, узел, ступень, onChanged }: {
  project: string
  /** Код узла состава. */
  узел: string
  ступень: ComponentCard
  onChanged: () => void
}) {
  const [открыта, setОткрыта] = useState<string | null>(null)
  const точки = граниПоТочкам(ступень)
  const готово = () => { setОткрыта(null); onChanged() }

  return (
    <>
      <div className="v2-facet__ro" aria-label={`долг лестницы узла ${узел}`}>
        {точки.map(([т, грани]) => (
          <div key={т}>
            <span className="v2-warn">к {т}:</span>{' '}
            {грани.map((г, i) => (
              <span key={г.key}>
                {i > 0 && ' · '}
                <button type="button" className="v2-link" aria-pressed={открыта === г.key}
                  title={ДЕЙСТВИЕ[г.key] ?? ГДЕ[г.key] ?? ступень.facets.find((ф) => ф.key === г.key)?.expected ?? 'где заводится — ниже'}
                  onClick={() => setОткрыта(открыта === г.key ? null : г.key)}>
                  {г.title}
                </button>
              </span>
            ))}
          </div>
        ))}
      </div>
      {открыта && (
        <div className="v2-facet v2-facet--wide" data-why="следующий-клик">
          <Действие project={project} узел={узел} грань={открыта} ступень={ступень} onDone={готово} onClose={() => setОткрыта(null)} />
        </div>
      )}
    </>
  )
}

function Действие({ project, узел, грань, ступень, onDone, onClose }: {
  project: string; узел: string; грань: string; ступень: ComponentCard; onDone: () => void; onClose: () => void
}) {
  const [автор] = useАвтор()
  if (грань === 'configuration') return <КЕУзла project={project} узел={узел} автор={автор} onDone={onDone} onClose={onClose} />
  if (грань === 'functions') {
    return (
      <НоваяЗапись project={project} kind="function" автор={автор} заготовка={{ allocated_to: [узел], layer: 'SA' }}
        заголовок={`Функция узла ${узел}`} onCreated={onDone} onClose={onClose} />
    )
  }
  if (грань === 'exchange_items') return <ОбменУзла project={project} узел={узел} автор={автор} onDone={onDone} onClose={onClose} />
  if (грань === 'supplier') return <ПоставщикУзла project={project} узел={узел} автор={автор} onDone={onDone} />
  if (грань === 'models') return <МодельУзла project={project} узел={узел} автор={автор} onDone={onDone} />
  if (грань === 'risks') return <РискУзла project={project} узел={узел} автор={автор} onDone={onDone} />
  const ожидание = ступень.facets.find((г) => г.key === грань)?.expected
  return <div className="v2-facet__hint">{ГДЕ[грань] ?? ожидание ?? 'грань заводится своей сценой'}</div>
}

/** КЕ узла: ответственный — учётка проекта (`ref account`), а не свободная строка. */
function КЕУзла({ project, узел, автор, onDone, onClose }: { project: string; узел: string; автор: string; onDone: () => void; onClose: () => void }) {
  const [учётки, setУчётки] = useState<string[]>([])
  useEffect(() => { api.projectRoles(project).then((р) => setУчётки(Object.keys(р))).catch(() => setУчётки([])) }, [project])
  const учётка: СвойКонтрол = ({ id, значение, занято, onSave }) => (
    <select id={id} value={String(значение ?? '')} disabled={занято} onChange={(e) => onSave(e.target.value || null)}>
      <option value="">— учётка проекта —</option>
      {учётки.map((у) => <option key={у} value={у}>{у}</option>)}
    </select>
  )
  return (
    <НоваяЗапись project={project} kind="configuration_item" автор={автор} заготовка={{ component: узел }}
      заголовок={`Конфигурационная единица узла ${узел}`} свои={{ responsible: учётка }}
      onCreated={onDone} onClose={onClose} />
  )
}

/** Обмен на стыке узла, затем элемент обмена на нём — той же карточкой вниз. */
function ОбменУзла({ project, узел, автор, onDone, onClose }: { project: string; узел: string; автор: string; onDone: () => void; onClose: () => void }) {
  const [стыки, setСтыки] = useState<InterfaceRow[]>([])
  const [обмен, setОбмен] = useState<string | null>(null)
  useEffect(() => {
    api.interfaces(project).then((r) => setСтыки(r.items.filter((с) => с.a === узел || с.b === узел))).catch(() => setСтыки([]))
  }, [project, узел])
  if (стыки.length === 0) return <div className="v2-facet__hint">у узла нет стыков: обмен идёт по стыку — сначала «Новый стык»</div>
  if (!обмен) {
    return (
      <НоваяЗапись project={project} kind="exchange" автор={автор} заготовка={{ interface: стыки[0].code }}
        заголовок={`Обмен на стыке узла ${узел}`} onCreated={setОбмен} onClose={onClose} />
    )
  }
  return (
    <НоваяЗапись project={project} kind="exchange_item" автор={автор} заготовка={{ exchanges: [обмен], type: 'flow' }}
      заголовок={`Элемент обмена ${обмен}`} свои={{ elements: ВводЭлементов }} onCreated={onDone} onClose={onClose} />
  )
}

/** Структура элемента обмена одной строкой: «имя: тип; имя: тип». */
const ВводЭлементов: СвойКонтрол = ({ id, значение, занято, onSave }) => {
  const было = Array.isArray(значение) ? (значение as { name?: string; data_type?: string }[]) : []
  return (
    <input id={id} disabled={занято} placeholder="payload: bytes32; seq: uint16"
      defaultValue={было.map((э) => `${э.name ?? ''}: ${э.data_type ?? ''}`).join('; ')}
      onBlur={(e) => onSave(e.target.value.split(';').map((ч) => ч.trim()).filter(Boolean).map((ч) => {
        const [имя, тип] = ч.split(':').map((х) => х.trim())
        return { name: имя, data_type: тип || 'string' }
      }))} />
  )
}

/**
 * Поставщик узла — полем узла (диф владельца 27.09-b: `component.supplier`), не
 * связью «владеет»: «владеет» — носитель нужды. Стороны с ролью «поставщик» — первыми.
 */
export function поставщикиПервыми(стороны: EntityRow[]): EntityRow[] {
  return [...стороны].sort((а, б) => Number(б.doc.role === 'supplier') - Number(а.doc.role === 'supplier'))
}

function ПоставщикУзла({ project, узел, автор, onDone }: { project: string; узел: string; автор: string; onDone: () => void }) {
  const [стороны, setСтороны] = useState<EntityRow[]>([])
  const [сторона, setСторона] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  useEffect(() => { api.entities(project, 'stakeholder').then((r) => setСтороны(поставщикиПервыми(r.items))).catch(() => setСтороны([])) }, [project])
  return (
    <div className="v2-form v2-form--row">
      <label className="v2-inline">поставщик
        <select aria-label="сторона-поставщик узла" value={сторона} onChange={(e) => setСторона(e.target.value)}>
          <option value="">— сторона проекта —</option>
          {стороны.map((с) => (
            <option key={с.code} value={с.code}>{String(с.doc.name ?? с.code)}{с.doc.role === 'supplier' ? ' · поставщик' : ''}</option>
          ))}
        </select>
      </label>
      <button type="button" className="v2-primary" disabled={!сторона}
        title={сторона ? `поставщик узла ${узел}: ${сторона}` : 'выберите сторону проекта'}
        onClick={() => api.patchEntity(project, узел, { supplier: сторона }, автор || 'инженер', 'поставщик узла')
          .then(onDone).catch((e) => setОтказ(String((e as Error).message ?? e)))}>
        Назначить поставщиком
      </button>
      {отказ && <span className="v2-bad">{отказ}</span>}
    </div>
  )
}

/** Модель, которая читает величину узла: вход модели — параметр анкеты узла. */
function МодельУзла({ project, узел, автор, onDone }: { project: string; узел: string; автор: string; onDone: () => void }) {
  const [модели, setМодели] = useState<ModelRow[]>([])
  const [ключи, setКлючи] = useState<string[]>([])
  const [выбор, setВыбор] = useState({ модель: '', ключ: '' })
  const [отказ, setОтказ] = useState<string | null>(null)
  useEffect(() => {
    api.models(project).then((r) => setМодели(r.items)).catch(() => setМодели([]))
    api.parameters(project, узел).then((r) => setКлючи(r.items.map((п) => п.key))).catch(() => setКлючи([]))
  }, [project, узел])
  const привязать = () => {
    setОтказ(null)
    api.entities(project, 'system_model')
      .then((r) => {
        const запись = r.items.find((м) => м.code === выбор.модель)
        const входы = Array.isArray(запись?.doc.inputs) ? (запись?.doc.inputs as { param_ref: string }[]) : []
        const вход = `${узел}.${выбор.ключ}`
        if (входы.some((в) => в.param_ref === вход)) return onDone()
        return api.patchEntity(project, выбор.модель, { inputs: [...входы, { param_ref: вход }] }, автор || 'инженер', `вход модели — величина ${вход}`)
          .then(onDone)
      })
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
  }
  if (ключи.length === 0) return <div className="v2-facet__hint">у узла нет величин анкеты: модели читать нечего — сначала анкета</div>
  return (
    <div className="v2-form v2-form--row">
      <label className="v2-inline">модель
        <select aria-label="модель проекта" value={выбор.модель} onChange={(e) => setВыбор({ ...выбор, модель: e.target.value })}>
          <option value="">— модель проекта —</option>
          {модели.map((м) => <option key={м.code} value={м.code}>{м.code} · {м.name}</option>)}
        </select>
      </label>
      <label className="v2-inline">читает величину
        <select aria-label="величина узла" value={выбор.ключ} onChange={(e) => setВыбор({ ...выбор, ключ: e.target.value })}>
          <option value="">— величина анкеты —</option>
          {ключи.map((к) => <option key={к} value={к}>{к}</option>)}
        </select>
      </label>
      <button type="button" className="v2-primary" disabled={!выбор.модель || !выбор.ключ}
        title={!выбор.модель || !выбор.ключ ? 'выберите модель и величину узла' : 'величина узла станет входом модели'}
        onClick={привязать}>
        Привязать
      </button>
      {отказ && <span className="v2-bad">{отказ}</span>}
    </div>
  )
}

/** Риск узла — ссылка риска на узел (та же, что пикер ссылок в карточке риска). */
function РискУзла({ project, узел, автор, onDone }: { project: string; узел: string; автор: string; onDone: () => void }) {
  const [риски, setРиски] = useState<RiskRow[]>([])
  const [риск, setРиск] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  useEffect(() => { api.risks(project).then((r) => setРиски(r.items.filter((р) => р.status !== 'closed'))).catch(() => setРиски([])) }, [project])
  const выбран = риски.find((р) => р.code === риск)
  return (
    <div className="v2-form v2-form--row">
      <label className="v2-inline">риск
        <select aria-label="открытый риск проекта" value={риск} onChange={(e) => setРиск(e.target.value)}>
          <option value="">— открытый риск —</option>
          {риски.map((р) => <option key={р.code} value={р.code}>{р.code} · {р.statement.slice(0, 60)}</option>)}
        </select>
      </label>
      <button type="button" className="v2-primary" disabled={!выбран}
        title={выбран ? `ссылка риска ${выбран.code} на узел ${узел}` : 'выберите риск; новый — формой реестра рисков'}
        onClick={() => выбран && api.patchEntity(project, выбран.code, { refs: [...new Set([...(выбран.refs ?? []), узел])] }, автор || 'инженер', 'связь риска с узлом')
          .then(onDone).catch((e) => setОтказ(String((e as Error).message ?? e)))}>
        Связать с узлом
      </button>
      {отказ && <span className="v2-bad">{отказ}</span>}
    </div>
  )
}
