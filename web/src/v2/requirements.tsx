// Экран требований (ТЗ §4.3, сцена 8): ШЕСТЬ КОЛОНОК и карточка вниз.
//
// Правило экрана: в таблице живёт только то, по чему ищут глазами — код,
// заголовок, формулировка, показатель, носитель, статус. Всё остальное
// (источники, метод, применимость, пометы линта) открывается карточкой под
// строкой, а не отдельным окном: контекст строки не теряется.
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ReactFlow, Background, Controls, MarkerType, type Node, type Edge,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from '@dagrejs/dagre'
import {
  api, type BaselineRow, type Blocker, type FormationProposal, type ImpactGraph, type KindSpec,
  type LintNote, type RequirementRow, type SuspectRow, type UnitRow,
} from './api'

/**
 * Формы шаблонов EARS: подсказка ввода, не перечень значений.
 *
 * Значения перечисления и их русские имена приходят истиной (`enum_labels`,
 * 19.09) — здесь только форма фразы, которую ждёт линт. Ключи — коды истины:
 * прежнее «always» не совпадало ни с чем («ubiquitous» в записи), и карточка
 * показывала код вместо слова.
 */
const ФОРМЫ: Record<string, string> = {
  ubiquitous: '‹Носитель› должен ‹действие› ‹объект› [‹показатель›].',
  event: 'Когда ‹событие›, ‹носитель› должен ‹действие› [в течение ‹T›].',
  state: 'Пока ‹состояние›, ‹носитель› должен ‹действие›.',
  unwanted: 'Если ‹условие›, то ‹носитель› должен ‹парирование›.',
  optional: 'Где предусмотрен ‹элемент›, ‹носитель› должен ….',
}

/** Природа носителя по уровню — правило носителя (истина уровней сцены 8). */
const НОСИТЕЛЬ: Record<string, { слова: string; виды: string[] }> = {
  project: { слова: 'узел состава', виды: ['component'] },
  scenario: { слова: 'цепочка или сценарий', виды: ['scenario', 'chain'] },
  system: { слова: 'узел состава', виды: ['component'] },
  subsystem: { слова: 'узел состава', виды: ['component'] },
  interface: { слова: 'стык', виды: ['interface'] },
}

/**
 * Истина схем о виде требования — один запрос на экран.
 *
 * Русские имена полей и значений экран не придумывает: `label` и `enum_labels`
 * приходят с сервера (истина 19.09). Пока ответа нет — показывается код, но
 * ненадолго: запрос уходит при открытии экрана.
 */
function useВидТребования(): {
  вид: KindSpec | null
  имя: (поле: string) => string
  метка: (поле: string, значение: string | null | undefined) => string
  значения: (поле: string) => { код: string; имя: string }[]
  стадия: (поле: string) => string
  примечание: (поле: string) => string
  единицы: UnitRow[]
  почемуБезЕдиниц: string
} {
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [единицы, setЕдиницы] = useState<UnitRow[]>([])
  const [почемуБезЕдиниц, setПочему] = useState('')
  useEffect(() => { api.kind('requirement').then(setВид).catch(() => undefined) }, [])
  // Единицы — из справочника (полка LIB). Владелец 19.09: «единиц измерения
  // нет — блок»: единица набиралась руками, и величина не собиралась вовсе.
  useEffect(() => {
    api.units()
      .then((r) => { setЕдиницы(r.items); setПочему(r.why ?? '') })
      .catch(() => setПочему('справочник единиц не отвечает'))
  }, [])
  const имя = useCallback((поле: string) => вид?.labels[поле] ?? поле, [вид])
  const метка = useCallback((поле: string, значение: string | null | undefined) => {
    if (!значение) return ''
    return вид?.enum_labels[поле]?.[значение] ?? значение
  }, [вид])
  const значения = useCallback((поле: string) => {
    const метки = вид?.enum_labels[поле]
    if (метки) return Object.entries(метки).map(([код, имя]) => ({ код, имя }))
    return (вид?.enums[поле] ?? []).map((код) => ({ код, имя: код }))
  }, [вид])
  const стадия = useCallback((поле: string) => вид?.required_at[поле] ?? '', [вид])
  const примечание = useCallback((поле: string) => вид?.notes[поле] ?? '', [вид])
  return { вид, имя, метка, значения, стадия, примечание, единицы, почемуБезЕдиниц }
}

/**
 * Предложения требований из постановки — прямо на сцене 8.
 *
 * Маршрут владельца: «Сцена 8: принять 12 требований, распределить по
 * природе». До 18.09 предложения лежали на другом экране (поле знаний), и на
 * сцене 8 было пусто: «ничего в требованиях верхнего уровня не изменилось».
 * Приём тот же самый — те же ворота сверки и та же обратимость пакета.
 */
function ПредложенияТребований({ project, onAccepted }: { project: string; onAccepted: () => void }) {
  const [запуск, setЗапуск] = useState<string>('')
  const [строки, setСтроки] = useState<FormationProposal[]>([])
  const [отмечены, setОтмечены] = useState<string[]>([])
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    api.synthesisDiff(project)
      .then((д) => {
        if (!('id' in д) || !д.diff) { setСтроки([]); return }
        const свои = (['new', 'augment', 'contradict', 'confirm'] as const)
          .flatMap((к) => д.diff?.[к] ?? [])
          .filter((п) => п.concept === 'requirement')
        setЗапуск(д.id)
        setСтроки(свои)
        setОтмечены(свои.filter((п) => п.missing.length === 0 && п.verdict === 'new').map((п) => п.proposal))
      })
      .catch(() => setСтроки([]))
  }, [project])
  useEffect(перечитать, [перечитать])

  if (строки.length === 0) return null

  const принять = () => {
    if (отмечены.length === 0) return
    setЗанято(true); setОтказ(null)
    api.acceptSynthesis(project, запуск, отмечены, 'инженер', 'сцена 8: требования из постановки')
      .then((и) => {
        setИтог(`заведено ${и.created.length}${и.pending.length > 0 ? `, ждёт решения ${и.pending.length}` : ''}`)
        onAccepted(); перечитать()
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Предложения требований из постановки</span>
        <span className="v2-card__count">{строки.length}</span>
      </div>
      <div className="v2-empty__why">
        Требования уровня проекта образуются здесь, решением инженера: приём заводит их
        черновиком, а природу и носителя вы назначаете в карточке. Приём обратим пакетом.
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      <table className="v2-table">
        <thead><tr><th /><th>Заголовок</th><th>Формулировка</th><th>Основания</th></tr></thead>
        <tbody>
          {строки.map((п) => (
            <tr key={п.proposal}>
              <td>
                <input type="checkbox" checked={отмечены.includes(п.proposal)}
                  aria-label={`отметить ${п.proposal}`} autoComplete="off"
                  onChange={(e) => setОтмечены(e.target.checked
                    ? [...отмечены, п.proposal]
                    : отмечены.filter((к) => к !== п.proposal))} />
              </td>
              <td>{п.payload.title ?? '—'}</td>
              <td>{п.payload.statement ?? ''}</td>
              <td className="v2-dim">{п.basis.map((о) => `${о.material ?? о.fact}${о.anchor ? ` · ${о.anchor}` : ''}`).join(' · ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={занято || отмечены.length === 0}
          title={отмечены.length === 0 ? 'отметьте предложения — сами они ничего не заводят' : `принять ${отмечены.length} черновиками`}
          onClick={принять}>
          {занято ? 'Принимаю…' : `Принять в черновик (${отмечены.length})`}
        </button>
      </div>
    </div>
  )
}

export function Requirements({ project }: { project: string | null }) {
  const [строки, setСтроки] = useState<RequirementRow[]>([])
  const [открыта, setОткрыта] = useState<string | null>(null)
  const [дерево, setДерево] = useState<'carrier' | 'source'>('carrier')
  const [подозрения, setПодозрения] = useState<SuspectRow[]>([])
  const [снимки, setСнимки] = useState<BaselineRow[]>([])
  const [помехи, setПомехи] = useState<Blocker[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  // Русские имена полей и значений — истиной схем, один запрос на экран.
  const схема = useВидТребования()

  const перечитать = useCallback(() => {
    if (!project) return
    api.requirements(project).then((r) => setСтроки(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.suspects(project).then((r) => setПодозрения(r.items)).catch(() => undefined)
    api.baselines(project).then((r) => setСнимки(r.items)).catch(() => undefined)
    api.baselineBlockers(project, 'functional', 'SRR').then((r) => setПомехи(r.items)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">Сначала откройте проект — требования живут в проекте.</div>
      </div>
    )
  }

  return (
    <>
      {отказ && <div className="v2-card"><div className="v2-locked">{отказ}</div></div>}

      <ПредложенияТребований project={project} onAccepted={перечитать} />

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Требования</span>
          <span className="v2-card__count">{строки.length}</span>
          <span className="v2-head__spacer" />
          <button type="button" className="v2-chip" aria-pressed={дерево === 'carrier'}
            title="дерево по носителю: кто несёт требование"
            onClick={() => setДерево('carrier')}>по носителю</button>
          <button type="button" className="v2-chip" aria-pressed={дерево === 'source'}
            title="дерево по источнику: откуда требование выведено"
            onClick={() => setДерево('source')}>по источнику</button>
          <a className="v2-chip" href={api.sdocUrl(project ?? '')} target="_blank" rel="noreferrer"
            title="StrictDoc: нужды · сервисы · требования с показателем по грамматике Орбиты (служба профиля strictdoc)">.sdoc</a>
          <a className="v2-chip" href={api.reqifUrl(project ?? '')} target="_blank" rel="noreferrer"
            title="ReqIF штатным экспортом StrictDoc из того же .sdoc">ReqIF</a>
        </div>

        {строки.length === 0 ? (
          <div className="v2-empty">
            Требований пока нет.
            <span className="v2-empty__why">
              Требование выводится из целей, нужд и ограничений — источник обязателен,
              а носитель определяет, кто за него отвечает.
            </span>
          </div>
        ) : (
          <table className="v2-table v2-table--req">
            <thead>
              <tr>
                <th>Код</th><th>Заголовок</th><th>Формулировка</th>
                <th>Показатель</th><th>Носитель</th><th>Статус</th>
              </tr>
            </thead>
            <tbody>
              {строки.map((т) => (
                <TableRow key={т.code} т={т} project={project} onChanged={перечитать} схема={схема}
                  открыта={открыта === т.code}
                  onToggle={() => setОткрыта(открыта === т.code ? null : т.code)} />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {открыта && <Impact код={открыта} project={project} />}

      <Деревья строки={строки} вид={дерево} />

      <Подозрения project={project} строки={подозрения} onConfirmed={перечитать} />

      <Базирование project={project} снимки={снимки} помехи={помехи} onDone={перечитать} />

      <Форма project={project} onAdded={перечитать} схема={схема} />
    </>
  )
}

/** Строка таблицы плюс карточка ВНИЗ: контекст строки не теряется. */
/** З-03: правка требования на месте — заголовок и формулировка новой версией; связи после базирования станут подозрительными и потребуют подтверждения. */
/**
 * Правка требования в карточке: заголовок, формулировка и поля СВОЕЙ стадии.
 *
 * Журнал ПМИ-7, З-10: «в карточке нет пикера носителя — а распределение по
 * природе и есть работа сцены 8». Поля собираются не списком в коде, а по
 * истине: всё, что она требует к базированию или к точке (`required_at`:
 * baseline · SRR), показывается здесь — категория и метод селектами русских
 * значений (`enum_labels`), показатель тройкой оператор · значение · единица,
 * носитель — пикером по природе уровня (`field_rules.widgets`).
 */
function ПравкаТребования({ project, т, onSaved, схема }: {
  project: string; т: RequirementRow; onSaved: () => void; схема: ReturnType<typeof useВидТребования>
}) {
  const [заголовок, setЗаголовок] = useState(т.title ?? '')
  const [формулировка, setФормулировка] = useState(т.statement ?? '')
  const [правки, setПравки] = useState<Record<string, unknown>>({})
  const [носители, setНосители] = useState<{ id: string; code: string; kind: string; подпись: string }[]>([])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  /**
   * Величина набрана наполовину: число есть, единицы нет.
   *
   * Прежде такая величина уходила ПУСТОЙ, сервер отвечал «изменено 0», и на
   * экране не менялось ничего — владелец 19.09: «не могу ввести показатель ни
   * в одно поле требований — он не сохраняется». Теперь это названо словами.
   */
  const [неполна, setНеполна] = useState<string | null>(null)

  // Поля стадии базирования и точки: истина называет их сама.
  const поляСтадии = useMemo(
    () => (схема.вид?.fields ?? []).filter((поле) => {
      const стадия = схема.стадия(поле)
      return стадия.startsWith('baseline') || /^[A-Z]{3}/.test(стадия)
    }),
    [схема],
  )

  // Носители по природе уровня: узел состава · стык · сценарий.
  useEffect(() => {
    const виды = НОСИТЕЛЬ[т.level]?.виды ?? ['component']
    Promise.all(виды.map((вид) => api.entities(project, вид).catch(() => ({ items: [] }))))
      .then((ответы) => setНосители(ответы.flatMap((о, i) => о.items.map((с) => ({
        id: с.id,
        code: с.code,
        kind: виды[i],
        подпись: `${с.code} · ${String(с.doc.name ?? с.doc.title ?? с.doc.statement ?? '')}`.slice(0, 60),
      })))))
      .catch(() => undefined)
  }, [project, т.level])

  const сохранить = () => {
    // Половина величины НЕ держит карточку: показатель по истине необязателен
    // (`required: false`), а держал он всю работу — час прохода владельца ушёл
    // на запертую кнопку (19.09). Неполное поле просто не уезжает, и это
    // сказано словами рядом.
    const кПравке = Object.fromEntries(Object.entries(правки).filter(([, з]) => з !== null))
    setЗанято(true); setОтказ(null); setИтог(null)
    api.patchEntity(project, т.code, { title: заголовок, statement: формулировка, ...кПравке }, 'инженер')
      .then((р) => {
        setЗанято(false); setПравки({})
        // «Изменено 0» говорится словами: молчание человек читает как «сохранилось».
        const наполовину = неполна ? ` · ${неполна} — это поле не сохранено` : ''
        setИтог((р.note?.trim()
          ? р.note
          : (р.changed > 0 ? `сохранено, версия ${р.version}` : 'ничего не изменилось — поля те же')) + наполовину)
        onSaved()
      })
      .catch((e) => { setЗанято(false); setОтказ(String(e.message ?? e)) })
  }

  const значение = (поле: string): string => {
    const правка = правки[поле]
    if (typeof правка === 'string') return правка
    if (поле === 'carrier') return ''
    return String((т as unknown as Record<string, unknown>)[поле] ?? '')
  }

  return (
    <Группа title="Правка на месте">
      <div className="v2-form" data-why="работа">
        <label>{схема.имя('title')}
          <input name={`${т.code}.title`} autoComplete="off" value={заголовок}
            onChange={(e) => setЗаголовок(e.target.value)} />
        </label>
        <label>{схема.имя('statement')}
          <textarea name={`${т.code}.statement`} value={формулировка} autoComplete="off"
            onChange={(e) => setФормулировка(e.target.value)} rows={3} />
        </label>
        {поляСтадии.map((поле) => {
          // Подпись несёт стадию и ПРИМЕЧАНИЕ истины: «обязателен для
          // performance» у показателя — иначе необязательное поле выглядит
          // долгом (владелец 19.09: «какой показатель тут можно поставить?»).
          const примечание = схема.примечание(поле)
          const подпись = `${схема.имя(поле)} · ${схема.стадия(поле)}${примечание ? ` · ${примечание}` : ''}`
          if (поле === 'carrier') {
            return (
              <label key={поле}>{подпись}
                <select name={`${т.code}.carrier`} value={String(правки.carrier ?? '')}
                  onChange={(e) => setПравки({ ...правки, carrier: e.target.value })}>
                  <option value="">
                    {т.carrier ? `оставить ${т.carrier}` : `— выберите ${НОСИТЕЛЬ[т.level]?.слова ?? 'носителя'} —`}
                  </option>
                  {носители.map((н) => <option key={н.id} value={н.id}>{н.подпись}</option>)}
                </select>
              </label>
            )
          }
          if (схема.вид?.measures.includes(поле)) {
            // Показатель нужен НЕ ВСЕМ: истина говорит «обязателен для
            // performance», и у функционального требования его ставить неоткуда
            // (владелец 19.09: «что ставить в показатели неведомо»). Поэтому
            // поле стоит там, где оно значит, а не висит вопросом всегда.
            const категория = String(правки.category ?? т.category ?? '')
            const ждёт = примечание.includes('performance')
              ? категория === 'performance'
              : true
            if (!ждёт && !т.measure && правки[поле] === undefined) {
              return (
                <span key={поле} className="v2-empty__why">
                  {схема.имя(поле)}: у категории «{схема.метка('category', категория) || 'не задана'}»
                  не требуется — {примечание || схема.стадия(поле)}.{' '}
                  <button type="button" className="v2-chip"
                    title="показатель можно поставить и здесь — истина этого не запрещает"
                    onClick={() => setПравки({ ...правки, [поле]: null })}>всё равно задать</button>
                </span>
              )
            }
            return (
              <Величина key={поле} подпись={подпись} код={т.code} было={т.measure}
                единицы={схема.единицы} почемуБезЕдиниц={схема.почемуБезЕдиниц}
                операторы={схема.вид.measure_ops}
                onChange={(в) => setПравки({ ...правки, [поле]: в })}
                onНеполна={setНеполна} />
            )
          }
          const значения = схема.значения(поле)
          if (значения.length > 0) {
            return (
              <label key={поле}>{подпись}
                <select name={`${т.code}.${поле}`} value={значение(поле)}
                  onChange={(e) => setПравки({ ...правки, [поле]: e.target.value })}>
                  <option value="">— не задано —</option>
                  {значения.map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
                </select>
              </label>
            )
          }
          return (
            <label key={поле}>{подпись}
              <input name={`${т.code}.${поле}`} autoComplete="off" value={значение(поле)}
                onChange={(e) => setПравки({ ...правки, [поле]: e.target.value })} />
            </label>
          )
        })}
        <button type="button" className="v2-primary" onClick={сохранить}
          disabled={занято || !формулировка.trim()}
          title={!формулировка.trim()
            ? 'формулировка пустой быть не может'
            : 'сохранить новой версией — провенанс «правка инженера»'}>Сохранить</button>
        {неполна && <span className="v2-empty__why">{неполна} — остальное сохранится</span>}
        {итог && <span className="v2-empty__why">{итог}</span>}
        {отказ && <span className="v2-locked">{отказ}</span>}
      </div>
    </Группа>
  )
}

/**
 * Величина: оператор · значение · ЕДИНИЦА СПРАВОЧНИКА (`field_rules.widgets`).
 *
 * Плоским полем величину брать нельзя — «объект без виджета на экране —
 * сторож». Единица ВЫБИРАЕТСЯ из справочника (полка LIB), а не набирается
 * руками: владелец 19.09 — «единиц измерения нет — блок», и число без единицы
 * уходило пустой величиной, отчего правка «не сохранялась» молча. Половина
 * величины теперь названа словами и держит кнопку.
 */
function Величина({ подпись, код, было, единицы, почемуБезЕдиниц, операторы, onChange, onНеполна }: {
  подпись: string
  код: string
  было: string | null
  единицы: UnitRow[]
  почемуБезЕдиниц: string
  операторы: Record<string, string>
  onChange: (значение: Record<string, unknown> | null) => void
  onНеполна: (словами: string | null) => void
}) {
  const прежнее = useMemo(() => {
    try { return было ? (JSON.parse(было) as Record<string, unknown>) : null } catch { return null }
  }, [было])
  const [оператор, setОператор] = useState(String(прежнее?.op ?? ''))
  const [число, setЧисло] = useState(String(прежнее?.value ?? ''))
  const [единица, setЕдиница] = useState(String(прежнее?.unit ?? ''))

  const собрать = (оп: string, зн: string, ед: string) => {
    const естьЧисло = зн.trim() !== ''
    const естьЕдиница = ед.trim() !== ''
    if (!естьЧисло && !естьЕдиница) { onНеполна(null); onChange(null); return }
    if (!естьЧисло || !естьЕдиница) {
      onНеполна(естьЕдиница
        ? `${подпись}: единица есть, а числа нет — величины без числа не бывает`
        : `${подпись}: «${зн}» без единицы — выберите единицу справочника, иначе число ничего не значит`)
      onChange(null)
      return
    }
    if (Number.isNaN(Number(зн.replace(',', '.')))) {
      onНеполна(`${подпись}: «${зн}» — не число`); onChange(null); return
    }
    onНеполна(null)
    const величина: Record<string, unknown> = { value: Number(зн.replace(',', '.')), unit: ед.trim() }
    if (оп) величина.op = оп
    onChange(величина)
  }

  // Единица записи может быть вне справочника (её дало чтение документа): её не
  // выбрасываем — показываем как есть и говорим, что она вне справочника.
  const своя = единица !== '' && !единицы.some((е) => е.code === единица)

  return (
    <label>{подпись}
      <span className="v2-measure">
        <select name={`${код}.measure.op`} value={оператор}
          onChange={(e) => { setОператор(e.target.value); собрать(e.target.value, число, единица) }}>
          {Object.entries(операторы).map(([кодОп, знак]) => (
            <option key={кодОп} value={кодОп === '=' ? '' : кодОп}>{знак}</option>
          ))}
        </select>
        <input name={`${код}.measure.value`} autoComplete="off" value={число} placeholder="значение"
          onChange={(e) => { setЧисло(e.target.value); собрать(оператор, e.target.value, единица) }} />
        <select name={`${код}.measure.unit`} value={единица}
          onChange={(e) => { setЕдиница(e.target.value); собрать(оператор, число, e.target.value) }}>
          <option value="">— единица —</option>
          {своя && <option value={единица}>{единица} · вне справочника</option>}
          {единицы.map((е) => <option key={е.code} value={е.code}>{е.label}</option>)}
        </select>
      </span>
      {единицы.length === 0 && (
        <span className="v2-locked">{почемуБезЕдиниц || 'справочник единиц пуст — выбрать единицу нечем'}</span>
      )}
    </label>
  )
}

function TableRow({ т, открыта, onToggle, project, onChanged, схема }: {
  т: RequirementRow; открыта: boolean; onToggle: () => void; project: string; onChanged: () => void
  схема: ReturnType<typeof useВидТребования>
}) {
  const показатель = т.measure ? кратко(т.measure) : '—'
  return (
    <>
      {/*
        Класс `v2-row` здесь стоять не может: это СЕТКА из трёх колонок для
        списков, и строка таблицы разваливалась на клетки по 16 px высотой в
        2705 (замерено в браузере на стенде; владелец: «в дизайне каша»).
        Подсветка открытой строки — своим классом, без раскладки.
      */}
      <tr className={открыта ? 'v2-row--open' : undefined} onClick={onToggle}>
        <td>
          {т.code}
          {т.after_baseline_changed && (
            <span className="v2-flag" title="версия выше зафиксированной снимком — изменено после утверждения">
              изменено
            </span>
          )}
        </td>
        <td>{т.title}</td>
        <td className="v2-cell--statement">
          {т.statement}
          {/*
            Помета линта НАЗЫВАЕТ себя (журнал ПМИ-7, З-13): «линт: 1» без
            текста не говорит ни правила, ни что исправить. Правило и фраза —
            в строке, причина — подсказкой; полный разбор — в карточке.
          */}
          {т.notes.length > 0 && (
            <span className="v2-flag v2-flag--warn" title={т.notes.map((n) => `${n.what}: ${n.why}`).join('\n')}>
              {т.notes[0].rule} · {т.notes[0].what}
              {т.notes.length > 1 && <span className="v2-dim"> и ещё {т.notes.length - 1}</span>}
            </span>
          )}
        </td>
        <td>{показатель}</td>
        <td>
          {т.carrier ?? <span className="v2-flag v2-flag--warn" title="без носителя — не требование">нет</span>}
          {т.carrier_kind && <span className="v2-dim"> · {т.carrier_kind}</span>}
        </td>
        <td>{схема.метка('status', т.status) || т.status}</td>
      </tr>
      {открыта && (
        <tr className="v2-card-row">
          <td colSpan={6}>
            <div className="v2-facets">
              <ПравкаТребования project={project} т={т} onSaved={onChanged} схема={схема} />
              <Группа title="Происхождение">
                <div>{схема.имя('level')}: {схема.метка('level', т.level) || '—'}</div>
                <div>
                  {схема.имя('category')}: {схема.метка('category', т.category) || (
                    <span className="v2-dim">не задана — к базированию</span>
                  )}
                </div>
                <div>источники: {т.sources.length > 0 ? т.sources.join(', ') : 'нет — требование ниоткуда не выводится'}</div>
                {т.template_ref && <div>типовое: {т.template_ref} · применимость {схема.метка('applicability', т.applicability) || '—'}</div>}
              </Группа>
              <Группа title="Проверка">
                <div>{схема.имя('ears_pattern')}: {схема.метка('ears_pattern', т.ears) || т.ears}</div>
                <div>
                  {схема.имя('verification_method')}: {схема.метка('verification_method', т.verification_method)
                    || <span className="v2-dim">не выбран</span>}
                </div>
                <div>версия: {т.version}</div>
              </Группа>
              <Группа title="Пометы линта">
                {т.notes.length === 0
                  ? <div className="v2-dim">замечаний нет</div>
                  : т.notes.map((n) => <Помета key={n.rule + n.what} note={n} />)}
              </Группа>
              <Группа title="Что заденет правка">
                <div className="v2-dim">
                  Граф связей — под таблицей: в колонке карточки он был бы нечитаем.
                </div>
              </Группа>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

/**
 * «Что заденет правка» — граф влияния СВЯЗЯМИ, а не догадкой по таблице.
 *
 * Прежде соседство считалось здесь же: общий носитель и упоминание кода в
 * источниках. Это давало ответ, похожий на правду, но видело только
 * требования и только те, что уже загружены на экран. Настоящие нити
 * лежат в реестре связей и ведут дальше — к узлам, функциям, моделям, — и
 * ходить по ним умеет сервер. Глубина спрашивается: «на два шага» и «на
 * четыре» — разные ответы, и человек выбирает, насколько далеко смотреть.
 *
 * Рисует БИБЛИОТЕКА (ADR-046): раскладку считает dagre при каждом показе,
 * координаты не хранятся ни на сервере, ни в браузере — иначе картинка
 * однажды начнёт врать про вчерашние связи.
 */
function Impact({ код, project }: { код: string; project: string }) {
  const [граф, setГраф] = useState<ImpactGraph | null>(null)
  const [глубина, setГлубина] = useState(2)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    let живо = true
    setГраф(null); setОтказ(null)
    api.impact(project, код, глубина)
      .then((г) => { if (живо) setГраф(г) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [project, код, глубина])

  const разложено = useMemo(() => (граф ? раскладка(граф) : { nodes: [], edges: [] }), [граф])

  return (
    <div className="v2-card" data-why="почему-нельзя">
      <div className="v2-card__head">
        <span className="v2-card__title">Что заденет правка {код}</span>
        <span className="v2-card__count">{граф ? граф.nodes.length - 1 : '…'}</span>
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {!отказ && !граф && <div className="v2-empty">читаю связи…</div>}
      {!отказ && граф && <ImpactCanvas граф={граф} код={код} глубина={глубина}
        setГлубина={setГлубина} разложено={разложено} />}
    </div>
  )
}

function ImpactCanvas({ граф, код, глубина, setГлубина, разложено }: {
  граф: ImpactGraph
  код: string
  глубина: number
  setГлубина: (г: number) => void
  разложено: { nodes: Node[]; edges: Edge[] }
}) {
  return (
    <>
      <div className="v2-kf__bar">
        <span className="v2-dim">на шагов:</span>
        {[1, 2, 4].map((г) => (
          <button key={г} type="button"
            className={глубина === г ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title={`пройти по связям на ${г} ${г === 1 ? 'шаг' : 'шага'} от требования`}
            onClick={() => setГлубина(г)}>{г}</button>
        ))}
      </div>
      {граф.nodes.length <= 1 ? (
        <div className="v2-dim">
          ничего: у требования нет связей — ни носителя, ни выведенных из него.
        </div>
      ) : (
        <>
          <div className="v2-imp__canvas">
            <ReactFlow
              key={`${код}:${глубина}:${граф.nodes.length}`}
              nodes={разложено.nodes}
              edges={разложено.edges}
              nodeOrigin={[0.5, 0.5]}
              fitView
              // Подгонка после готовности: раскладка dagre даёт координаты в
              // своих единицах, и без неё граф открывается не в кадре.
              onInit={(инстанс) => { setTimeout(() => инстанс.fitView(), 0) }}
              nodesDraggable={false}
              nodesConnectable={false}
              proOptions={{ hideAttribution: true }}
              minZoom={0.2}
              // Без потолка подгонка раздувает три узла во весь экран.
              maxZoom={1.2}
            >
              <Background />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
          <div className="v2-dim">
            {граф.summary || 'после правки эти связи станут подозрительными — их придётся подтвердить.'}
          </div>
        </>
      )}
    </>
  )
}

const ШИР = 168
const ВЫС = 54

/** Раскладка dagre при каждом показе: координаты нигде не живут. */
function раскладка(граф: ImpactGraph): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: 22, ranksep: 90 })
  граф.nodes.forEach((у) => g.setNode(у.id, { width: ШИР, height: ВЫС }))
  граф.edges.forEach((р) => g.setEdge(р.from, р.to))
  dagre.layout(g)
  return {
    nodes: граф.nodes.map((у) => {
      const т = g.node(у.id)
      const корень = у.depth === 0
      return {
        id: у.id,
        position: { x: т.x, y: т.y },
        // Размер задан явно, а не оставлен на обмер: пока библиотека не
        // обмерила узел, она держит его скрытым, а обмер идёт наблюдателем
        // размеров — во вкладке, которую браузер не рисует (фон, свёрнутое
        // окно), наблюдатель молчит, и граф остаётся пустым полотном.
        width: ШИР,
        height: ВЫС,
        data: {
          label: (
            <div className="v2-imp__node" title={`${у.kind}: ${у.title}`}>
              <div className="v2-imp__kind">{ВИД_УЗЛА[у.kind] ?? у.kind}</div>
              <div className="v2-mono">{у.code}</div>
            </div>
          ),
        },
        style: {
          width: ШИР,
          fontSize: 12,
          borderRadius: 6,
          border: корень ? '2px solid var(--accent)' : '1px solid var(--line)',
          background: 'var(--ground)',
        },
      } as Node
    }),
    // Подписью идёт ТИП связи, а не её обоснование: обоснование бывает
    // целым предложением («поведение исполняется на бортовой вычислительной
    // машине») и ложится поверх соседних узлов. Причина — в подсказке.
    edges: граф.edges.map((р, i) => ({
      id: `${р.from}->${р.to}:${i}`,
      source: р.from,
      target: р.to,
      label: СВЯЗЬ[р.type] ?? р.type,
      labelStyle: { fontSize: 12 },
      labelShowBg: true,
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { stroke: 'var(--line)' },
      data: { why: р.why },
    } as Edge)),
  }
}

/** Тип связи по-русски: подпись ребра короткая, причина — в подсказке. */
const СВЯЗЬ: Record<string, string> = {
  carrier: 'носитель',
  target: 'носитель анкеты',
  deployed_on: 'развёрнуто на',
  derived_from_fact: 'из факта',
  derived_from: 'выведено из',
  owns: 'владеет',
  covers: 'покрывает',
  snapshot_of: 'напечатано в',
  verifies: 'проверяет',
  allocation: 'размещено на',
  trace: 'трассировка',
  conflicts_with: 'противоречит',
}

/** Вид узла по-русски: латинское имя вида инженеру ничего не говорит. */
const ВИД_УЗЛА: Record<string, string> = {
  requirement: 'требование',
  component: 'узел',
  need: 'нужда',
  goal: 'цель',
  service: 'сервис',
  interface: 'стык',
  function: 'функция',
  model: 'модель',
  parameter: 'анкета',
  constraint: 'ограничение',
  stakeholder: 'сторона',
  technology: 'технология',
  risk: 'риск',
  document: 'документ',
  event: 'событие',
}

function Группа({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="v2-facet">
      <div className="v2-facet__title">{title}</div>
      <div className="v2-facet__body">{children}</div>
    </div>
  )
}

function Помета({ note }: { note: LintNote }) {
  return (
    <div className="v2-note">
      <span className="v2-note__rule">{note.rule}</span>
      <span>{note.what}</span>
      <span className="v2-empty__why">{note.why}</span>
    </div>
  )
}

/** Два дерева: по носителю (кто несёт) и по источнику (откуда выведено). */
function Деревья({ строки, вид }: { строки: RequirementRow[]; вид: 'carrier' | 'source' }) {
  const группы = useMemo(() => {
    const карта = new Map<string, RequirementRow[]>()
    строки.forEach((т) => {
      const ключи = вид === 'carrier'
        ? [т.carrier ?? 'без носителя']
        : (т.sources.length > 0 ? т.sources : ['без источника'])
      ключи.forEach((к) => карта.set(к, [...(карта.get(к) ?? []), т]))
    })
    return [...карта.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [строки, вид])

  if (группы.length === 0) return null

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">
          Дерево {вид === 'carrier' ? 'по носителю' : 'по источнику'}
        </span>
        <span className="v2-card__count">{группы.length}</span>
      </div>
      <ul className="v2-tree">
        {группы.map(([ключ, дети]) => (
          <li key={ключ}>
            <span className="v2-tree__node">{ключ}</span>
            <span className="v2-card__count">{дети.length}</span>
            <ul>
              {дети.map((т) => (
                <li key={т.code}><span className="v2-dim">{т.code}</span> {т.title}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Подозрения({ project, строки, onConfirmed }: {
  project: string; строки: SuspectRow[]; onConfirmed: () => void
}) {
  if (строки.length === 0) return null
  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Подозрительные связи</span>
        <span className="v2-card__count">{строки.length}</span>
      </div>
      <div className="v2-empty__why">
        Конец связи изменился после снимка. Подозрение снимает человек — это разрыв к следующей точке.
      </div>
      {строки.map((с) => (
        <div key={с.link} className="v2-note">
          <span className="v2-note__rule">{с.type}</span>
          <span>{с.from} → {с.to}: {с.why}</span>
          <button type="button" className="v2-chip"
            title="подтвердить, что связь по-прежнему верна"
            onClick={() => api.confirmSuspect(project, с.link, 'стенд').then(onConfirmed).catch(() => undefined)}>
            подтвердить
          </button>
        </div>
      ))}
    </div>
  )
}

function Базирование({ project, снимки, помехи, onDone }: {
  project: string; снимки: BaselineRow[]; помехи: Blocker[]; onDone: () => void
}) {
  const [имя, setИмя] = useState('SRR')
  const [класс, setКласс] = useState('functional')
  const [отказ, setОтказ] = useState<Blocker[] | null>(null)
  const [занято, setЗанято] = useState(false)

  const базировать = () => {
    setЗанято(true); setОтказ(null)
    api.baseline(project, { name: имя, kind: класс, gate: имя, author: 'стенд' })
      .then(() => onDone())
      .catch((e) => setОтказ([{ code: '—', rule: '—', what: String(e.message ?? e) }]))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Базирование</span>
        <span className="v2-card__count">{снимки.length}</span>
      </div>

      {снимки.map((с) => (
        <div key={с.name} className="v2-note">
          <span className="v2-note__rule">{с.name}</span>
          <span>
            {с.kind} · точка {с.gate} · {с.items.length} объектов
            {с.items.length - с.firm > 0 && ` · условных ${с.items.length - с.firm}`}
          </span>
          {с.items.filter((э) => э.firmness === 'conditional').map((э) => (
            <span key={э.ref} className="v2-empty__why">{э.code}: {э.why}</span>
          ))}
        </div>
      ))}

      {помехи.length > 0 && (
        <div className="v2-locked">
          Базировать пока нельзя — {помехи.length} помех:
          <ul>
            {помехи.slice(0, 8).map((п, i) => (
              <li key={`${п.code}-${i}`}><b>{п.code}</b> · {п.rule} — {п.what}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="v2-form__actions">
        <label>Имя снимка
          <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="SRR" />
        </label>
        <label>Класс
          <select value={класс} onChange={(e) => setКласс(e.target.value)}>
            <option value="functional">функциональная</option>
            <option value="allocated">распределённая</option>
            <option value="product">изделия</option>
          </select>
        </label>
        <button type="button" className="v2-primary" disabled={занято || помехи.length > 0}
          title={помехи.length > 0
            ? `сначала закройте помехи: ${помехи[0].what}`
            : 'снять снимок набора: версии зафиксируются, незрелые технологии войдут условными'}
          onClick={базировать}>
          {занято ? 'Снимаю…' : 'Базировать'}
        </button>
      </div>

      {отказ && <div className="v2-locked">{отказ.map((п) => п.what).join('; ')}</div>}
    </div>
  )
}

/** Форма требования: шаблон EARS в форме, линт — пометами по ходу. */
function Форма({ project, onAdded, схема }: {
  project: string; onAdded: () => void; схема: ReturnType<typeof useВидТребования>
}) {
  const [поля, setПоля] = useState({
    code: '', level: 'system', title: '', statement: '', category: 'functional',
    carrier: '', verification_method: 'test', acceptance_criteria: '',
  })
  const [шаблон, setШаблон] = useState('ubiquitous')
  // Показатель и здесь ставится ПАРОЙ с единицей справочника: в форме нового
  // требования поля величины не было вовсе — владелец 19.09 искал, где ввести
  // показатель, и «единиц измерения нет» было буквально так.
  const [показатель, setПоказатель] = useState<Record<string, unknown> | null>(null)
  const [неполна, setНеполна] = useState<string | null>(null)
  const [пометы, setПометы] = useState<LintNote[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  /** Источник обязателен: требование ниоткуда не выводится. */
  const [источники, setИсточники] = useState<{ kind: string; id: string; подпись: string }[]>([])
  const [источник, setИсточник] = useState('')

  useEffect(() => {
    const собрать = async () => {
      const пары = await Promise.all(
        (['goal', 'need', 'constraint'] as const).map(async (вид) => {
          const r = await api.entities(project, вид)
          return r.items.map((с) => ({
            kind: вид,
            id: с.id,
            подпись: `${с.code} · ${String(с.doc.statement ?? с.doc.text ?? с.doc.name ?? '')}`.slice(0, 70),
          }))
        }),
      )
      setИсточники(пары.flat())
    }
    собрать().catch(() => undefined)
  }, [project])

  useEffect(() => {
    if (!поля.statement.trim()) { setПометы([]); return }
    const таймер = setTimeout(() => {
      api.lint(поля.statement, шаблон).then((r) => setПометы(r.notes)).catch(() => undefined)
    }, 400)
    return () => clearTimeout(таймер)
  }, [поля.statement, шаблон])

  const форма = ФОРМЫ[шаблон] ?? ФОРМЫ.ubiquitous
  const уровень = НОСИТЕЛЬ[поля.level] ?? НОСИТЕЛЬ.system

  const завести = () => {
    setЗанято(true); setОтказ(null)
    const выбран = источники.find((и) => и.id === источник)
    api.addRequirement(project, {
      ...поля,
      ears_pattern: шаблон,
      ...(показатель ? { measure: показатель } : {}),
      source: выбран ? [{ kind: выбран.kind, ref: выбран.id }] : [],
    })
      .then(() => { setПоля({ ...поля, code: '', title: '', statement: '' }); onAdded() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card">
      <div className="v2-card__head"><span className="v2-card__title">Новое требование</span></div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form">
        <label>Уровень
          <select value={поля.level} onChange={(e) => setПоля({ ...поля, level: e.target.value })}>
            {схема.значения('level').map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
          </select>
        </label>
        <label>Носитель ({уровень.слова})
          <input value={поля.carrier} onChange={(e) => setПоля({ ...поля, carrier: e.target.value })}
            placeholder={поля.level === 'interface' ? 'IF-DATA-BUS' : 'OBC-CPU'} />
        </label>
        <label>Заголовок
          <input value={поля.title} onChange={(e) => setПоля({ ...поля, title: e.target.value })} />
        </label>
        <label>Шаблон формулировки
          <select value={шаблон} onChange={(e) => setШаблон(e.target.value)}>
            {схема.значения('ears_pattern').map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
          </select>
        </label>
        <label>Формулировка
          <textarea rows={2} value={поля.statement} placeholder={форма}
            autoComplete="off" onChange={(e) => setПоля({ ...поля, statement: e.target.value })} />
        </label>
        <span className="v2-empty__why">Форма: {форма}</span>
        {пометы.map((n) => <Помета key={n.rule + n.what} note={n} />)}
        <label>Источник (откуда выведено)
          <select value={источник} onChange={(e) => setИсточник(e.target.value)}>
            <option value="">— выберите цель, нужду или ограничение —</option>
            {источники.map((и) => <option key={и.id} value={и.id}>{и.подпись}</option>)}
          </select>
        </label>
        <label>{схема.имя('category')}
          <select name="новое.category" value={поля.category}
            onChange={(e) => setПоля({ ...поля, category: e.target.value })}>
            {схема.значения('category').map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
          </select>
        </label>
        <Величина подпись={`${схема.имя('measure')}${схема.примечание('measure') ? ` · ${схема.примечание('measure')}` : ''}`}
          код="новое" было={null} единицы={схема.единицы} почемуБезЕдиниц={схема.почемуБезЕдиниц}
          операторы={схема.вид?.measure_ops ?? {}}
          onChange={setПоказатель} onНеполна={setНеполна} />
        {неполна && <span className="v2-empty__why">{неполна} — показатель не запишется, остальное запишется</span>}
        <label>Критерий приёмки
          <input value={поля.acceptance_criteria}
            onChange={(e) => setПоля({ ...поля, acceptance_criteria: e.target.value })}
            placeholder="наблюдаемый результат: что считать выполнением" />
        </label>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary"
            disabled={занято || !поля.statement.trim() || !поля.carrier.trim() || !источник}
            title={!поля.carrier.trim()
              ? `укажите носителя: для этого уровня это ${уровень.слова}`
              : !источник
                ? 'выберите источник: требование ниоткуда не выводится'
                : 'завести требование; пометы линта не мешают черновику, но держат базирование'}
            onClick={завести}>
            {занято ? 'Завожу…' : 'Завести требование'}
          </button>
        </div>
      </div>
    </div>
  )
}

function кратко(measure: string): string {
  try {
    const м = JSON.parse(measure) as Record<string, unknown>
    const значение = м.value ?? `${м.min ?? ''}…${м.max ?? ''}`
    return `${м.op ?? ''} ${значение} ${м.unit ?? ''}`.trim()
  } catch {
    return measure
  }
}
