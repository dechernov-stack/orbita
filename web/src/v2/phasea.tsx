// Phase A — поверхности через контекст (ПЛАН-ДОРАБОТОК-ПОСЛЕ-KDP-A §3, шип 3).
//
// Не девять экранов, а привязки «сцена → поверхность с отбором»: сцена
// A-ряда открывает СУЩЕСТВУЮЩИЙ реестр, отфильтрованный своим контекстом —
// узлом экземпляра (A4), уровнем требований (A5), шаблоном документа
// (A2 · A3 · A9 · A11), точкой (карточка узла считает ступень к точке сцены).
// Условия выхода сцены живут в рейке мероприятия («Условия», «к месту»);
// здесь — только то, с чем работают.
//
// Сторож `tools/validate_scene_surfaces.py` читает ветки `ключ === 'A…'`
// из этого файла: сцена с выходом без ветки — отказ сборки, а закрытый долг
// обязан быть снят из его списка (храповик).
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  api, type BudgetRow, type ComponentCard, type ComponentRow, type InterfaceRow, type KindSpec,
  type Phase, type Scene,
} from './api'
import { ArchitectureScreen } from './architecture'
import { Concept } from './concept'
import { DocumentBody } from './documents'
import { SceneModes } from './modes'
import { PhasePlan } from './plan'
import { Costs, Debris, Technologies } from './programmatics'
import { Requirements } from './requirements'
import { useАвтор } from './research'
import { ОтветственныеСцен } from './responsibles'
import { RiskRegistry } from './risks'

export function PhaseASurface({ project, phase, scene, onChanged, onScene }: {
  project: string
  phase: Phase
  scene: Scene
  onChanged: () => void
  /** Переход на соседнюю сцену фазы — из поверхности, где та названа. */
  onScene: (ключ: string) => void
}) {
  // Экземпляр сцены (A4:EL-SC) привязывается по сцене шаблона.
  const ключ = scene.instance_of ?? scene.key
  const сцены = useMemo(() => phase.scenes.map((с) => ({ key: с.key, title: с.title })), [phase.scenes])
  // Отбор реестра — один объект на узел: новый объект каждый рендер сбрасывал бы чипы реестра.
  const отборУзла = useMemo(() => ({ носитель: scene.node ?? undefined }), [scene.node])
  const отборСистемы = useMemo(() => ({ уровень: 'system' }), [])

  if (ключ === 'A1') {
    return (
      <>
        <PhasePlan phase={phase} project={project} onChanged={onChanged} />
        <ОтветственныеСцен project={project} onChanged={onChanged} />
        <div className="v2-panel" data-why="следующий-клик">
          <h3>Реестры к развёртыванию</h3>
          <div className="v2-empty__why">
            Мероприятие 1.P обновляет риски и технологии к новой фазе: их реестры — на своих сценах.
          </div>
          <div className="v2-inline">
            <button type="button" className="v2-link" title="сцена A8: реестр рисков — владелец и срок-точка у каждого"
              onClick={() => onScene('A8')}>риски — сцена A8</button>
            <button type="button" className="v2-link" title="сцена A7: технологии — разрыв TRL, пакет созревания и веха"
              onClick={() => onScene('A7')}>технологии — сцена A7</button>
          </div>
        </div>
      </>
    )
  }
  if (ключ === 'A2') return <ДокументФазы project={project} template="semp" имя="SEMP — план управления системной инженерией" />
  if (ключ === 'A3') {
    return (
      <>
        <ДокументФазы project={project} template="conops" имя="ConOps — концепция применения" />
        <SceneModes project={project} onChanged={onChanged} />
      </>
    )
  }
  if (ключ === 'A4') {
    if (!scene.node) {
      return (
        <div className="v2-panel" data-why="почему-нельзя">
          <h3>Аванпроект элемента</h3>
          <div className="v2-empty">
            Узлов вида «элемент» в составе нет — аванпроекту не на что развернуться.
            <span className="v2-empty__why">
              Сцена разворачивается в экземпляр на каждый элемент состава: заведите сегменты и элементы в разделе «Концепция».
            </span>
          </div>
        </div>
      )
    }
    return (
      <>
        <КарточкаУзла project={project} node={scene.node} gate={scene.gate ?? null} />
        <Requirements project={project} отбор={отборУзла} />
        <Стыки project={project} node={scene.node} заголовок={`Стыки узла ${scene.node}`} />
      </>
    )
  }
  if (ключ === 'A5') {
    return (
      <>
        <ArchitectureScreen project={project} />
        <Requirements project={project} отбор={отборСистемы} />
        <Стыки project={project} заголовок="Стыки системы" />
        <Бюджеты project={project} />
      </>
    )
  }
  // A6 — та же Концепция: вкладки «Состав · Варианты · Базовая · Модели» у
  // неё свои (шип 5 §7), второй строки вкладок над ней нет.
  if (ключ === 'A6') return <Concept project={project} />
  if (ключ === 'A7') return <Technologies project={project} />
  if (ключ === 'A8') return <RiskRegistry project={project} сцены={сцены} />
  if (ключ === 'A9') {
    return (
      <>
        <Debris project={project} />
        <ДокументФазы project={project} template="sma" имя="План обеспечения — надёжность, безопасность, ЭМС" />
      </>
    )
  }
  if (ключ === 'A10') return <Costs project={project} />
  if (ключ === 'A11') {
    return (
      <>
        <ДокументФазы project={project} template="projectplan" имя="Project Plan — Д10" />
        <ДокументФазы project={project} template="fa" имя="Formulation Agreement — обновление к Phase B" />
        <PhasePlan phase={phase} project={project} onChanged={onChanged} />
      </>
    )
  }
  return null
}

/**
 * Документ сцены — по ШАБЛОНУ, а не по угаданному коду: список документов
 * называет шаблон каждой строки. Документа нет — сказано словами и предложено
 * завести с полки.
 */
function ДокументФазы({ project, template, имя }: { project: string; template: string; имя: string }) {
  /** undefined — читаю; null — документа по шаблону в проекте нет. */
  const [код, setКод] = useState<string | null | undefined>(undefined)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    api.documents(project)
      .then((r) => setКод(r.items.find((д) => д.template === template)?.code ?? null))
      .catch((e) => { setОтказ(String(e.message ?? e)); setКод(null) })
  }, [project, template])
  useEffect(перечитать, [перечитать])

  if (код === undefined) return <div className="v2-panel" data-why="работа"><div className="v2-empty">Читаю документы…</div></div>
  if (код === null) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>{имя}</h3>
        {отказ && <div className="v2-locked">{отказ}</div>}
        <div className="v2-empty">
          Документа по этому шаблону в проекте нет.
          <span className="v2-empty__why">
            Документы фазы заводятся по её шаблону при первом чтении списка; этого в нём нет — завести можно отсюда, если шаблон лежит на полке.
          </span>
        </div>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" disabled={занято}
            title={занято ? 'документ заводится' : `завести документ по шаблону с полки от имени «${автор || 'инженер'}»`}
            onClick={() => {
              setЗанято(true); setОтказ(null)
              api.ensureDocument(project, template, автор || 'инженер')
                .then(перечитать)
                .catch((e) => setОтказ(String(e.message ?? e)))
                .finally(() => setЗанято(false))
            }}>
            {занято ? 'Завожу…' : 'Завести документ'}
          </button>
        </div>
      </div>
    )
  }
  return <DocumentBody project={project} code={код} />
}

/** Карточка узла экземпляра гранями по ступени к точке сцены (SDR): считает сервер. */
function КарточкаУзла({ project, node, gate }: { project: string; node: string; gate: string | null }) {
  const [карточка, setКарточка] = useState<ComponentCard | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    if (!gate) return
    api.componentCard(project, node, gate).then(setКарточка).catch((e) => setОтказ(String(e.message ?? e)))
  }, [project, node, gate])

  if (!gate) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <h3>Карточка узла {node}</h3>
        <div className="v2-empty">
          Точка сцены шаблоном не названа.
          <span className="v2-empty__why">Ступень зрелости считается к точке; без неё карточке не к чему считать.</span>
        </div>
      </div>
    )
  }
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><h3>Карточка узла {node}</h3><div className="v2-locked">{отказ}</div></div>
  if (!карточка) return <div className="v2-panel" data-why="работа"><h3>Карточка узла {node}</h3><div className="v2-empty">Читаю карточку…</div></div>

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Карточка узла {карточка.code} · {карточка.name}
        <span className="v2-cnt">ступень к {карточка.gate} · граней {карточка.facets.length}</span>
      </h3>
      <div className="v2-facets">
        {карточка.facets.map((г) => (
          <div key={г.key} className="v2-facet">
            <div className="v2-facet__title">{г.title}</div>
            <div className="v2-facet__body">
              {г.lines.length === 0
                ? <div className="v2-dim">{г.expected ?? 'пока пусто'}{г.required_to ? ` · к ${г.required_to}` : ''}</div>
                : <ul className="v2-why">{г.lines.map((с, i) => <li key={i}>{с.what}</li>)}</ul>}
            </div>
          </div>
        ))}
      </div>
      {карточка.gaps.length > 0 ? (
        <div className="v2-empty__why">
          не закрыто к точке: {карточка.gaps.map((р) => `${р.what} (${р.gate})`).join('; ')}
        </div>
      ) : (
        <div className="v2-empty__why">к точке {карточка.gate} грани закрыты — ступень «архитектура» узла взята</div>
      )}
    </div>
  )
}

/** Значение селектора стороны b «внешняя система»: не код узла, а выбор формы. */
const ВНЕШНЯЯ = '__external'

/**
 * Стыки: у сцены A4 — только своего узла (сторона a или b), у A5 — все.
 * Новый стык заводится тут же: обе стороны — узлы состава, тип и направление —
 * словами истины.
 */
function Стыки({ project, node, заголовок }: { project: string; node?: string | null; заголовок: string }) {
  const [стыки, setСтыки] = useState<InterfaceRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [стороны, setСтороны] = useState<{ code: string; name: string }[]>([])
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [новый, setНовый] = useState({ name: '', type: '', a: node ?? '', b: '', direction: '' })
  /** Сторона b — внешняя система (истина 24.09): имя и владелец-сторона; узлом она не притворяется. */
  const [внешняя, setВнешняя] = useState({ name: '', owner: '' })
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    api.interfaces(project).then((r) => setСтыки(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  useEffect(перечитать, [перечитать])
  useEffect(() => {
    api.components(project).then((r) => setУзлы(r.items.filter((у) => у.nature === 'node'))).catch(() => setУзлы([]))
    api.entities(project, 'stakeholder')
      .then((r) => setСтороны(r.items.map((з) => ({ code: з.code, name: String(з.doc.name ?? з.code) }))))
      .catch(() => setСтороны([]))
    api.kind('interface').then(setВид).catch(() => setВид(null))
  }, [project])

  const свои = node ? стыки.filter((с) => с.a === node || с.b === node) : стыки
  const слово = (поле: string, значение: string) => вид?.enum_labels?.[поле]?.[значение] ?? значение
  const внешняяСторона = новый.b === ВНЕШНЯЯ
  const помеха = !новый.name.trim() ? 'имя стыка не названо'
    : !новый.type ? 'тип стыка не выбран'
      : !новый.a || !новый.b ? 'у стыка две стороны — выберите обе'
        : внешняяСторона && !внешняя.name.trim() ? 'у внешней системы нет имени'
          : новый.a === новый.b ? 'стороны стыка — разные узлы' : null

  const записать = () => {
    if (помеха) return
    setЗанято(true); setОтказ(null)
    const тело = внешняяСторона
      ? { ...новый, b: { name: внешняя.name.trim(), ...(внешняя.owner ? { owner: внешняя.owner } : {}) } }
      : новый
    api.addInterface(project, { ...тело, author: автор || 'инженер' })
      .then(() => { setНовый({ name: '', type: '', a: node ?? '', b: '', direction: '' }); setВнешняя({ name: '', owner: '' }); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        {заголовок}
        <span className="v2-cnt">{свои.length}{node && стыки.length !== свои.length ? ` · всего в проекте ${стыки.length}` : ''}</span>
      </h3>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {свои.length === 0 ? (
        <div className="v2-empty">
          Стыков нет.
          <span className="v2-empty__why">
            {node
              ? 'Экземпляр держится стыками своего узла с параметрами ступени: заведите стык, где этот узел — одна из сторон.'
              : 'Архитектура держится стыками между узлами: заведите их с двумя сторонами из состава.'}
          </span>
        </div>
      ) : (
        <table className="v2-table">
          <thead><tr><th>Код</th><th>Стык</th><th>Тип</th><th>Стороны</th><th>Направление</th></tr></thead>
          <tbody>
            {свои.map((с) => (
              <tr key={с.code}>
                <td className="v2-mono">{с.code}</td>
                <td>{с.name}</td>
                <td>{слово('type', с.type)}</td>
                <td>{с.a} — {с.b}{с.b_external && <span className="v2-dim"> · внешняя система{с.b_owner ? `, владелец ${с.b_owner}` : ''}</span>}</td>
                <td>{с.direction ? слово('direction', с.direction) : <span className="v2-dim">не задано</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="v2-form">
        <span className="v2-field__cap">Новый стык</span>
        <label>Имя
          <input aria-label="имя стыка" autoComplete="off" value={новый.name} placeholder="КА — НКУ (радиолиния)"
            onChange={(e) => setНовый({ ...новый, name: e.target.value })} />
        </label>
        <label>Тип
          <select aria-label="тип стыка" value={новый.type} onChange={(e) => setНовый({ ...новый, type: e.target.value })}>
            <option value="">— тип по истине —</option>
            {(вид?.enums?.type ?? []).map((т) => <option key={т} value={т}>{слово('type', т)}</option>)}
          </select>
        </label>
        <label>Сторона a
          <select aria-label="сторона a" value={новый.a} onChange={(e) => setНовый({ ...новый, a: e.target.value })}>
            <option value="">— узел состава —</option>
            {узлы.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
          </select>
        </label>
        <label>Сторона b
          <select aria-label="сторона b" value={новый.b} onChange={(e) => setНовый({ ...новый, b: e.target.value })}>
            <option value="">— узел состава —</option>
            {узлы.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
            <option value={ВНЕШНЯЯ}>внешняя система…</option>
          </select>
        </label>
        {внешняяСторона && (
          <>
            <label>Внешняя система
              <input aria-label="имя внешней системы" autoComplete="off" value={внешняя.name} placeholder="ГАИС «ЭРА-ГЛОНАСС»"
                onChange={(e) => setВнешняя({ ...внешняя, name: e.target.value })} />
            </label>
            <label>Владелец
              <select aria-label="владелец внешней системы" value={внешняя.owner} onChange={(e) => setВнешняя({ ...внешняя, owner: e.target.value })}>
                <option value="">— сторона проекта —</option>
                {стороны.map((с) => <option key={с.code} value={с.code}>{с.name}</option>)}
              </select>
            </label>
          </>
        )}
        <label>Направление
          <select aria-label="направление стыка" value={новый.direction} onChange={(e) => setНовый({ ...новый, direction: e.target.value })}>
            <option value="">— не задано —</option>
            {(вид?.enums?.direction ?? []).map((д) => <option key={д} value={д}>{слово('direction', д)}</option>)}
          </select>
        </label>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" disabled={Boolean(помеха) || занято}
            title={помеха ?? (занято ? 'стык записывается' : 'записать стык: стороны — узлы состава, параметры ступени добавляются карточкой узла')}
            onClick={записать}>
            {занято ? 'Записываю…' : 'Записать стык'}
          </button>
          {помеха && <span className="v2-empty__why">Нажать нельзя: {помеха}.</span>}
        </div>
      </div>
    </div>
  )
}

/** Бюджеты сцены A5: записи вида «бюджет» — корень свёртки и политика резервов; свёртку к точке считает раздел «Модели». */
function Бюджеты({ project }: { project: string }) {
  const [бюджеты, setБюджеты] = useState<BudgetRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [новый, setНовый] = useState({ kind: '', root: '', reserve_policy: '' })
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    api.budgets(project).then((r) => setБюджеты(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  useEffect(перечитать, [перечитать])
  useEffect(() => {
    api.components(project).then((r) => setУзлы(r.items.filter((у) => у.nature === 'node'))).catch(() => setУзлы([]))
    api.kind('budget').then(setВид).catch(() => setВид(null))
  }, [project])

  const слово = (значение: string) => вид?.enum_labels?.kind?.[значение] ?? значение
  const помеха = !новый.kind ? 'вид величины не выбран'
    : !новый.root ? 'корень свёртки не выбран'
      : !новый.reserve_policy.trim() ? 'политика резервов не названа' : null

  const записать = () => {
    if (помеха) return
    setЗанято(true); setОтказ(null)
    api.addBudget(project, { ...новый, author: автор || 'инженер' })
      .then(() => { setНовый({ kind: '', root: '', reserve_policy: '' }); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>Бюджеты<span className="v2-cnt">{бюджеты.length}</span></h3>
      <div className="v2-empty__why">
        Сцена ждёт бюджеты массы, мощности и линии с двумя резервами против рамок; свёртка величин к точке — в разделе «Модели».
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {бюджеты.length === 0 ? (
        <div className="v2-empty">
          Бюджетов нет.
          <span className="v2-empty__why">Бюджет — корень свёртки и политика резервов; величины узлов он читает из анкет.</span>
        </div>
      ) : (
        <table className="v2-table">
          <thead><tr><th>Код</th><th>Величина</th><th>Корень</th><th>Политика резервов</th></tr></thead>
          <tbody>
            {бюджеты.map((б) => (
              <tr key={б.code}>
                <td className="v2-mono">{б.code}</td>
                <td>{слово(б.kind)}</td>
                <td>{б.root}</td>
                <td>{б.reserve_policy || <span className="v2-dim">не названа</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="v2-form v2-form--row">
        <label className="v2-inline">величина
          <select aria-label="вид величины бюджета" value={новый.kind} onChange={(e) => setНовый({ ...новый, kind: e.target.value })}>
            <option value="">— по истине —</option>
            {(вид?.enums?.kind ?? []).map((к) => <option key={к} value={к}>{слово(к)}</option>)}
          </select>
        </label>
        <label className="v2-inline">корень
          <select aria-label="корень свёртки" value={новый.root} onChange={(e) => setНовый({ ...новый, root: e.target.value })}>
            <option value="">— узел состава —</option>
            {узлы.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
          </select>
        </label>
        <label className="v2-inline">политика резервов
          <input aria-label="политика резервов" autoComplete="off" value={новый.reserve_policy}
            placeholder="резерв класса зрелости + системный запас 20 %"
            onChange={(e) => setНовый({ ...новый, reserve_policy: e.target.value })} />
        </label>
        <button type="button" className="v2-primary" disabled={Boolean(помеха) || занято}
          title={помеха ?? (занято ? 'бюджет записывается' : 'записать бюджет: свёртка к точке появится в разделе «Модели»')}
          onClick={записать}>
          {занято ? 'Записываю…' : 'Записать бюджет'}
        </button>
      </div>
    </div>
  )
}
