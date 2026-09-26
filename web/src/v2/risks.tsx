// Реестр рисков — раздел «Риски» (шип 1, п. 1.2–1.4; шип 5 §7).
//
// Реестр живёт всю фазу, не только в сцене 11: пункт меню виден с сцены 11,
// до неё — в эксперт-режиме. Шип 5 §7: полосы по владельцу, карточка объекта
// §1.3, закрытие решением. Строение — общий реестр (`registry/registry.tsx`):
// чипы отбора значениями из данных, колонка отметок и массовые действия
// (срок-точка, владелец, закрытие решением), карточка вниз от строки.
//
// Поля вида правятся в карточке по истине (формулировка, категория,
// стратегия, меры, срок-точка). Рядом — грани риска, которых истина не
// размечает виджетом: вероятность и последствия кликом 1–5, условие ·
// событие · последствие тремя строками, владелец из учёток, связи с узлами
// и сценами, состояние — закрыть решением словами или вернуть с причиной.
// Точка называет риски, которые её держат, и ведёт сюда ссылкой: два клика
// от точки до закрытого риска.
import { useCallback, useEffect, useMemo, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { api, type ComponentRow, type EntityRow, type KindSpec, type RiskRow } from './api'
import { Реестр, type Колонка } from './registry/registry'
import { useАвтор } from './research'
import type { ЧипОтбора } from './ui/chips'
import { ИконКнопка } from './ui/iconbutton'
import type { МассовоеДействие, Набор } from './ui/mass'
import { Карточка } from './ui/objectcard'
import { Маркер } from './ui/tabs'

/**
 * Вехи проекта для срока риска.
 *
 * Истина: `risk.due_point: ref gate` — «срок — любая веха (gate.kind
 * phase|technology)». Перечень в коде («MCR · SRR · SDR · PDR») был вторым
 * списком мимо данных: у проекта свои точки и свои вехи технологий, и риск
 * держится ими. Пусто — значит вех в проекте ещё нет, и это сказано словами.
 */
export function useВехи(project: string): { код: string; подпись: string }[] {
  const [вехи, setВехи] = useState<{ код: string; подпись: string }[]>([])
  useEffect(() => {
    api.entities(project, 'gate')
      .then((р) => setВехи(р.items
        .filter((в) => в.status !== 'cancelled')
        .map((в) => ({
          код: в.code,
          подпись: `${в.code}${в.doc.name ? ` · ${String(в.doc.name)}` : ''}`
            + (в.doc.planned_date ? ` · ${String(в.doc.planned_date)}` : ''),
        }))))
      .catch(() => undefined)
  }, [project])
  return вехи
}

type Порядок = 'level' | 'due'

/**
 * Слова статуса. Истина знает статусную модель риска (open|closed), но меток
 * под ключом risk.status в enum_labels не называет — вопрос владельцу; до
 * ответа слова здесь, чтобы на экране не было кода.
 */
const СТАТУС: Record<string, string> = { open: 'открыт', closed: 'закрыт' }

/** Полоса риска — его владелец; без владельца — своя полоса, её видно первой по счёту. */
const БЕЗ_ВЛАДЕЛЬЦА = 'владелец не назначен'
export function владелецРиска(р: RiskRow): string {
  return р.owner && р.owner !== '—' ? р.owner : БЕЗ_ВЛАДЕЛЬЦА
}

/** Порядок строк: по критичности (уровень считает сервер) или по сроку-точке. */
export function порядокРисков(риски: RiskRow[], порядок: Порядок): RiskRow[] {
  return [...риски].sort((а, б) => (порядок === 'level'
    ? б.level - а.level || а.code.localeCompare(б.code)
    : (а.due_date || '9999').localeCompare(б.due_date || '9999') || б.level - а.level))
}

/** Чипы отбора — значениями из данных: состояния, точки, которые риски держат, категории. */
export function чипыРисков(риски: RiskRow[], словом: (поле: string, значение: string) => string): ЧипОтбора<RiskRow>[] {
  const точки = [...new Set(риски.flatMap((р) => р.holds))].sort()
  const категории = [...new Set(риски.map((р) => р.category).filter(Boolean))].sort()
  return [
    { key: 'открытые', word: 'открытые', group: 'состояние', test: (р) => р.status === 'open' },
    { key: 'закрытые', word: 'закрытые', group: 'состояние', test: (р) => р.status === 'closed' },
    ...точки.map((т): ЧипОтбора<RiskRow> => ({
      key: `точка:${т}`, word: `держат ${т}`, group: 'точка', test: (р) => р.holds.includes(т),
      hint: `открытые риски, чей срок не позже даты ${т}: они держат её решение`,
    })),
    { key: 'без-срока', word: 'без срока-точки', group: 'срок', test: (р) => р.status === 'open' && р.due_point === '—' },
    { key: 'ключевые', word: 'ключевые ≥ 12', group: 'уровень', test: (р) => р.level >= 12, hint: 'критичность — вероятность × последствия, считает сервер' },
    ...категории.map((к): ЧипОтбора<RiskRow> => ({ key: `категория:${к}`, word: словом('category', к), group: 'категория', test: (р) => р.category === к })),
  ]
}

type Учётка = { login: string; display_name: string }

export function RiskRegistry({ project, wantRisk, сцены }: {
  project: string
  /** Карточка, которую просили открыть (ссылка с точки). */
  wantRisk?: string | null
  /** Сцены фазы для связи риска со сценой; нет — только узлы. */
  сцены?: { key: string; title: string }[]
}) {
  const [риски, setРиски] = useState<RiskRow[]>([])
  /** Записи вида «риск» для карточки: грани правятся по истине. */
  const [записи, setЗаписи] = useState<Record<string, EntityRow>>({})
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [учётки, setУчётки] = useState<Учётка[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [порядок, setПорядок] = useState<Порядок>('level')
  const [форма, setФорма] = useState(false)
  const [занято, setЗанято] = useState(false)
  const вехи = useВехи(project)
  const [автор] = useАвтор()
  const [вопрос, спросить, закрытьВопрос] = useConfirm()

  const перечитать = useCallback(() => {
    api.risks(project).then((r) => setРиски(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.entities(project, 'risk')
      .then((r) => setЗаписи(Object.fromEntries(r.items.map((з) => [з.code, з]))))
      .catch(() => setЗаписи({}))
  }, [project])
  useEffect(перечитать, [перечитать])
  useEffect(() => {
    api.kind('risk').then(setВид).catch(() => setВид(null))
    api.components(project).then((r) => setУзлы(r.items)).catch(() => setУзлы([]))
    fetch('/api/auth/users').then((r) => (r.ok ? r.json() : { users: [] }))
      .then((d) => setУчётки(d.users ?? [])).catch(() => setУчётки([]))
  }, [project])

  /**
   * Правка риска на месте: вид «риск» правится полем, а
   * пересчёт уровня (вероятность × влияние) делает сервер — экран лишь
   * перечитывает реестр, чтобы «В×П» не разошлись с тем, что видят ворота.
   */
  const правитьРиск = (код: string, поля: Record<string, unknown>, зачем = 'оценка риска') => {
    api.patchEntity(project, код, поля, автор || 'инженер', зачем)
      .then(перечитать)
      .catch((ошибка) => setОтказ(String(ошибка.message ?? ошибка)))
  }
  /** Действие над несколькими рисками разом: один отказ — словами сервера, реестр перечитывается. */
  const разом = (коды: string[], одно: (код: string) => Promise<unknown>) => {
    setЗанято(true); setОтказ(null)
    Promise.all(коды.map(одно))
      .then(перечитать)
      .catch((ошибка) => { setОтказ(String(ошибка.message ?? ошибка)); перечитать() })
      .finally(() => setЗанято(false))
  }

  /** Русские значения перечислений — только из истины (enum_labels вида). */
  const метки = (поле: string): Record<string, string> => вид?.enum_labels?.[поле] ?? {}
  const словом = (поле: string, значение: string) => метки(поле)[значение] ?? значение

  const строки = useMemo(() => порядокРисков(риски, порядок), [риски, порядок])
  const чипы = useMemo(() => чипыРисков(риски, (п, з) => вид?.enum_labels?.[п]?.[з] ?? з), [риски, вид])
  const открытых = риски.filter((р) => р.status === 'open').length
  const критичных = риски.filter((р) => р.status === 'open' && р.level >= 12).length
  const безСрока = риски.filter((р) => р.status === 'open' && р.due_point === '—').length

  const закрытьРешением = (коды: string[]) => {
    const открытые = коды.filter((к) => риски.find((р) => р.code === к)?.status === 'open')
    if (открытые.length === 0) return
    спросить({
      question: открытые.length === 1
        ? `Закрыть риск ${открытые[0]} решением. Кем и когда — запишет сервер.`
        : `Закрыть решением рисков: ${открытые.length} (${открытые.join(', ')}). Кем и когда — запишет сервер.`,
      ok: 'Закрыть',
      input: { label: 'решение: чем снят или почему принят', placeholder: 'ECC и watchdog приняты в состав', required: true },
      onOk: (решение) => разом(открытые, (к) => api.closeRisk(project, к, решение, автор || 'инженер')),
    })
  }
  const вернуть = (код: string) => спросить({
    question: `Вернуть риск ${код} в открытые. Решение о закрытии перестанет держаться.`,
    ok: 'Вернуть',
    input: { label: 'причина: почему решение не держится', required: true },
    onOk: (причина) => разом([код], (к) => api.reopenRisk(project, к, причина, автор || 'инженер')),
  })

  /*
    Простановка разом (проход 20.09): без точки стояли ВСЕ двенадцать рисков —
    по одному это двенадцать кликов. Теперь: набор «без срока-точки», действие
    «срок-точка выбранным», веха — выбором человека (первой строкой пусто,
    без выбора «Проставить» не нажимается). Набор берёт только риски без
    точки — названные сроки не трогаются, пока их не отметили руками.
  */
  const массово: МассовоеДействие[] = [
    {
      key: 'срок', икон: 'отложить', слово: 'срок-точка выбранным', disabled: занято || вехи.length === 0,
      run: (коды) => спросить({
        question: `Срок-точка для рисков: ${коды.length}. Выбранная веха встанет каждому отмеченному.`,
        ok: 'Проставить',
        choice: { label: 'веха проекта', options: [['', '— выберите веху —'], ...вехи.map((в): [string, string] => [в.код, в.подпись])], initial: '' },
        onOk: (веха) => разом(коды, (к) => api.patchEntity(project, к, { due_point: веха }, автор || 'инженер', 'срок-точка риска: проставлено разом')),
      }),
    },
    {
      key: 'владелец', икон: 'править', слово: 'назначить владельца', disabled: занято || учётки.length === 0,
      run: (коды) => спросить({
        question: `Владелец для рисков: ${коды.length}.`,
        ok: 'Назначить',
        choice: { label: 'кто ведёт', options: [['', '— выберите учётку —'], ...учётки.map((у): [string, string] => [у.display_name, `${у.display_name} · ${у.login}`])], initial: '' },
        onOk: (имя) => разом(коды, (к) => api.patchEntity(project, к, { owner: имя }, автор || 'инженер', 'владелец риска: назначен разом')),
      }),
    },
    { key: 'закрыть', икон: 'принять', слово: 'закрыть решением', disabled: занято, run: закрытьРешением },
  ]
  const наборы = (видимые: RiskRow[]): Набор[] => [
    { key: 'без-срока', word: 'без срока-точки', keys: видимые.filter((р) => р.status === 'open' && р.due_point === '—').map((р) => р.code) },
    { key: 'без-владельца', word: 'без владельца', keys: видимые.filter((р) => владелецРиска(р) === БЕЗ_ВЛАДЕЛЬЦА).map((р) => р.code) },
    { key: 'держат', word: 'держат точку', keys: видимые.filter((р) => р.holds.length > 0).map((р) => р.code) },
  ]

  const колонки: Колонка<RiskRow>[] = [
    { key: 'code', title: 'Код', className: 'v2-mono', cell: (р) => р.code },
    {
      key: 'statement', title: 'Риск', cell: (р) => (
        <>
          {р.statement}
          {р.holds.length > 0 && <span className="v2-dim"> · держит {р.holds.join(', ')}</span>}
        </>
      ),
    },
    {
      key: 'level', title: 'В×П', className: 'v2-nowrap', hint: 'вероятность × последствия: шкала 1–5, уровень считает сервер',
      cell: (р) => (р.level > 0
        ? <span className={р.level >= 12 && р.status === 'open' ? 'v2-bad' : undefined}>{р.probability}×{р.impact} = {р.level}</span>
        : <span className="v2-dim">не оценён</span>),
    },
    { key: 'strategy', title: 'Стратегия', cell: (р) => (р.strategy && р.strategy !== '—' ? словом('strategy', р.strategy) : <span className="v2-dim">—</span>) },
    {
      key: 'due', title: 'Срок', className: 'v2-nowrap', hint: 'срок — веха проекта; точка фазы считает сроки по дате не позже своей',
      cell: (р) => (р.due_point === '—'
        ? <span className={р.status === 'open' ? 'v2-warn' : 'v2-dim'}>нет точки</span>
        : <>{р.due_point}{р.due_date ? <span className="v2-dim"> · {р.due_date}</span> : null}</>),
    },
    {
      key: 'status', title: 'Состояние', className: 'v2-nowrap', cell: (р) => (
        <>
          <Маркер health={р.status === 'closed' ? 'ok' : р.holds.length > 0 ? 'block' : р.due_point === '—' ? 'debt' : null}
            title={р.status === 'closed' ? 'закрыт решением' : р.holds.length > 0 ? `держит ${р.holds.join(', ')}` : р.due_point === '—' ? 'без срока-точки' : 'открыт'} />
          {' '}{СТАТУС[р.status] ?? р.status}
        </>
      ),
    },
  ]

  return (
    <div className="v2-panel" data-why="работа">
      {отказ && <div className="v2-locked">{отказ}</div>}
      <h3>
        Реестр рисков
        <span className="v2-cnt">
          {риски.length} · открытых {открытых} · критичность ≥ 12: {критичных} · без срока {безСрока}
        </span>
      </h3>
      <Реестр<RiskRow>
        label="реестр рисков"
        строки={строки}
        ключ={(р) => р.code}
        колонки={колонки}
        чипы={чипы}
        начальныеЧипы={wantRisk ? [] : ['открытые']}
        открыть={wantRisk}
        поиск={{ placeholder: 'найти риск', text: (р) => `${р.code} ${р.statement} ${р.measures}` }}
        хвост={(
          <>
            <label className="v2-inline">
              порядок
              <select aria-label="порядок рисков" value={порядок} onChange={(e) => setПорядок(e.target.value as Порядок)}>
                <option value="level">по критичности</option>
                <option value="due">по сроку-точке</option>
              </select>
            </label>
            <ИконКнопка икон="добавить" сословом слово={форма ? 'свернуть форму' : 'завести риск'} aria-expanded={форма}
              onClick={() => setФорма(!форма)} />
          </>
        )}
        полосы={{
          предмет: владелецРиска,
          счёт: (rr) => `рисков ${rr.length} · открытых ${rr.filter((р) => р.status === 'open').length}`
            + (rr.some((р) => р.holds.length > 0) ? ` · держат точку ${rr.filter((р) => р.holds.length > 0).length}` : ''),
        }}
        действия={(р) => (р.status === 'open'
          ? <ИконКнопка икон="принять" слово="закрыть решением" disabled={занято} onClick={() => закрытьРешением([р.code])} />
          : <ИконКнопка икон="вернуть" слово="вернуть в открытые с причиной" disabled={занято} onClick={() => вернуть(р.code)} />)}
        карточка={(р, закрыть) => (записи[р.code] && вид
          ? (
            <Карточка project={project} row={записи[р.code]} spec={вид} заголовок={р.statement}
              состояние={(
                <span className="v2-object__state">
                  <Маркер health={р.status === 'closed' ? 'ok' : р.holds.length > 0 ? 'block' : null} title={р.status === 'closed' ? 'закрыт решением' : 'открыт'} />
                  {СТАТУС[р.status] ?? р.status}{р.holds.length > 0 ? ` · держит ${р.holds.join(', ')}` : ''}
                </span>
              )}
              скрыть={['cec', 'probability', 'impact', 'owner', 'refs']}
              extra={<ГраниРиска р={р} spec={вид} правитьРиск={правитьРиск} узлы={узлы} сцены={сцены ?? []} учётки={учётки}
                project={project} занято={занято} onЗакрыть={() => закрытьРешением([р.code])} onВернуть={() => вернуть(р.code)} />}
              onSaved={перечитать} onClose={закрыть} />
          )
          : <div className="v2-empty">Карточка читается…</div>)}
        массово={массово}
        наборы={наборы}
        пусто={<>Рисков не заведено. <span className="v2-empty__why">Пустой реестр означает, что риски не искали: сцена 11 держится тремя записями.</span></>}
      />
      {(форма || риски.length === 0) && (
        <НовыйРиск project={project} вехи={вехи} учётки={учётки} автор={автор}
          onDone={() => { setФорма(false); перечитать() }} onОтказ={setОтказ} />
      )}
      <ConfirmBox request={вопрос} onClose={закрытьВопрос} />
    </div>
  )
}

/** Заведение риска: срок-точка обязательна — срок без точки не наступает. */
function НовыйРиск({ project, вехи, учётки, автор, onDone, onОтказ }: {
  project: string
  вехи: { код: string; подпись: string }[]
  учётки: Учётка[]
  автор: string
  onDone: () => void
  onОтказ: (т: string | null) => void
}) {
  const [поля, setПоля] = useState({
    statement: '', category: 'technical', probability: 3, impact: 3,
    strategy: 'mitigate', measures: '', owner: '', due_point: '',
  })
  return (
    <div className="v2-form" data-why="работа" aria-label="новый риск">
      <label>Формулировка
        <input value={поля.statement} placeholder="SEU в памяти без ECC → зависание борта"
          onChange={(e) => setПоля({ ...поля, statement: e.target.value })} />
      </label>
      <label>Меры
        <input value={поля.measures} placeholder="ECC и watchdog"
          onChange={(e) => setПоля({ ...поля, measures: e.target.value })} />
      </label>
      <label>Вероятность 1–5
        <input type="number" min={1} max={5} value={поля.probability}
          onChange={(e) => setПоля({ ...поля, probability: Number(e.target.value) })} />
      </label>
      <label>Влияние 1–5
        <input type="number" min={1} max={5} value={поля.impact}
          onChange={(e) => setПоля({ ...поля, impact: Number(e.target.value) })} />
      </label>
      <label>Владелец
        <input list={`v2-владельцы-${project}`} value={поля.owner} placeholder="кто ведёт"
          onChange={(e) => setПоля({ ...поля, owner: e.target.value })} />
      </label>
      <datalist id={`v2-владельцы-${project}`}>
        {учётки.map((у) => <option key={у.login} value={у.display_name}>{у.login}</option>)}
      </datalist>
      <label>Срок — точка
        <select value={поля.due_point} onChange={(e) => setПоля({ ...поля, due_point: e.target.value })}>
          <option value="">— веха проекта —</option>
          {вехи.map((в) => <option key={в.код} value={в.код}>{в.подпись}</option>)}
        </select>
      </label>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={!поля.statement.trim()}
          title="срок без точки не наступает — точка обязательна"
          onClick={() => api.addRisk(project, { ...поля, owner: поля.owner || автор || 'инженер', author: автор || 'инженер' })
            .then(() => { setПоля({ ...поля, statement: '', measures: '' }); onDone() })
            .catch((e) => onОтказ(String(e.message ?? e)))}>
          Завести риск
        </button>
      </div>
    </div>
  )
}

/**
 * Грани риска рядом с гранями истины: то, что истина не размечает виджетом
 * карточки. Оценка — кликом 1–5 (одна кнопка на балл, не поле ввода);
 * условие · событие · последствие — три строки; владелец — из учёток;
 * связи — узлы состава и сцены; состояние — закрыть решением словами или
 * вернуть с причиной. У каждой подписи — один контрол.
 */
export function ГраниРиска({ р, spec, правитьРиск, узлы, сцены, учётки, project, занято, onЗакрыть, onВернуть }: {
  р: RiskRow
  spec: KindSpec
  правитьРиск: (код: string, поля: Record<string, unknown>, зачем?: string) => void
  узлы: ComponentRow[]
  сцены: { key: string; title: string }[]
  учётки: Учётка[]
  project: string
  занято: boolean
  onЗакрыть: () => void
  onВернуть: () => void
}) {
  const закрыт = р.status === 'closed'
  const [cec, setCec] = useState({ condition: р.condition, event: р.event, consequence: р.consequence })
  useEffect(() => {
    setCec({ condition: р.condition, event: р.event, consequence: р.consequence })
  }, [р.code, р.version, р.condition, р.event, р.consequence])
  const сохранитьCec = () => {
    if (cec.condition === р.condition && cec.event === р.event && cec.consequence === р.consequence) return
    правитьРиск(р.code, { cec }, 'условие · событие · последствие')
  }
  const ссылки = р.refs
  const добавитьСсылку = (код: string) => {
    if (!код || ссылки.includes(код)) return
    правитьРиск(р.code, { refs: [...ссылки, код] }, 'связь риска с узлом или сценой')
  }
  const снятьСсылку = (код: string) => правитьРиск(р.code, { refs: ссылки.filter((с) => с !== код) }, 'связь риска снята')
  const подпись = (поле: string, запас: string) => spec.labels?.[поле] ?? запас
  const шкала = (поле: 'probability' | 'impact', слово: string) => (
    <div className="v2-facet">
      <div className="v2-facet__lab">{подпись(поле, слово)}</div>
      <div className="v2-facet__row" role="group" aria-label={`${слово} риска ${р.code} кликом`}>
        {[1, 2, 3, 4, 5].map((з) => (
          <button key={з} type="button" className={р[поле] === з ? 'v2-chip v2-chip--on' : 'v2-chip'}
            aria-pressed={р[поле] === з} disabled={закрыт}
            title={закрыт ? 'риск закрыт: оценка не правится — верните в открытые с причиной' : `${слово} ${з} из 5`}
            onClick={() => р[поле] !== з && правитьРиск(р.code, { [поле]: з })}>{з}</button>
        ))}
      </div>
    </div>
  )
  return (
    <>
      {шкала('probability', 'вероятность')}
      {шкала('impact', 'влияние')}
      <div className="v2-facet">
        <div className="v2-facet__lab">Критичность</div>
        <div className="v2-facet__ro">
          <b>{р.level || '—'}</b>{р.level >= 12 && <span className="v2-bad"> · ключевой (≥ 12)</span>}
        </div>
        <div className="v2-facet__hint">вероятность × последствия — пересчёт уровня делает сервер</div>
      </div>
      <div className="v2-facet v2-facet--wide">
        <div className="v2-facet__lab">{подпись('cec', 'Условие · событие · последствие')}</div>
        <div className="v2-facet__trio">
          {(['condition', 'event', 'consequence'] as const).map((к) => (
            <label key={к}>{{ condition: 'условие', event: 'событие', consequence: 'последствие' }[к]}
              <input value={cec[к]} disabled={закрыт} aria-label={`${{ condition: 'условие', event: 'событие', consequence: 'последствие' }[к]} риска ${р.code}`}
                onChange={(e) => setCec({ ...cec, [к]: e.target.value })} onBlur={сохранитьCec} />
            </label>
          ))}
        </div>
      </div>
      <div className="v2-facet">
        <label htmlFor={`v2-risk-owner-${р.code}`}>{подпись('owner', 'Владелец')}</label>
        <input id={`v2-risk-owner-${р.code}`} name={`${р.code}.owner`} defaultValue={р.owner === '—' ? '' : р.owner}
          list={`v2-владельцы-карточки-${project}`} placeholder="кто ведёт" aria-label={`владелец риска ${р.code}`} disabled={закрыт}
          onBlur={(e) => {
            const имя = e.target.value.trim()
            if (имя && имя !== р.owner) правитьРиск(р.code, { owner: имя }, 'владелец риска')
          }} />
        <datalist id={`v2-владельцы-карточки-${project}`}>
          {учётки.map((у) => <option key={у.login} value={у.display_name}>{у.login}</option>)}
        </datalist>
        <div className="v2-facet__hint">полоса реестра — по владельцу</div>
      </div>
      <div className="v2-facet v2-facet--wide">
        <div className="v2-facet__lab">{подпись('refs', 'Ссылки')} · узлы состава и сцены</div>
        <div className="v2-facet__chips">
          {ссылки.length === 0 && <span className="v2-dim">связей нет</span>}
          {ссылки.map((с) => (
            <span key={с} className="v2-chip" title="узел состава или сцена">
              {с}
              {!закрыт && <ИконКнопка икон="снять" слово={`снять связь ${с}`} onClick={() => снятьСсылку(с)} />}
            </span>
          ))}
          {!закрыт && (
            <select aria-label={`связать риск ${р.code} с узлом`} value="" onChange={(e) => добавитьСсылку(e.target.value)}>
              <option value="">+ узел</option>
              {узлы.filter((у) => !ссылки.includes(у.code)).map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
            </select>
          )}
          {!закрыт && сцены.length > 0 && (
            <select aria-label={`связать риск ${р.code} со сценой`} value="" onChange={(e) => добавитьСсылку(e.target.value)}>
              <option value="">+ сцена</option>
              {сцены.filter((с) => !ссылки.includes(`scene:${с.key}`)).map((с) => <option key={с.key} value={`scene:${с.key}`}>сцена {с.key} · {с.title}</option>)}
            </select>
          )}
        </div>
      </div>
      <div className="v2-facet v2-facet--wide">
        <div className="v2-facet__lab">Состояние · закрытие решением</div>
        {закрыт ? (
          <div className="v2-facet__ro">
            <span className="v2-ok">закрыт</span> · {р.closed_by || '—'} · {р.closed_at ? р.closed_at.slice(0, 16).replace('T', ' ') : '—'}
            <div>решение: {р.resolution || '—'}</div>
            <ИконКнопка икон="вернуть" сословом слово="вернуть в открытые с причиной" disabled={занято} onClick={onВернуть} />
          </div>
        ) : (
          <div className="v2-facet__ro">
            {р.reopen_reason && <div className="v2-dim">возвращён в открытые: {р.reopen_reason}</div>}
            <ИконКнопка икон="принять" сословом слово="закрыть решением" disabled={занято} onClick={onЗакрыть} />
            <span className="v2-facet__hint"> чем снят или почему принят — словами; кем и когда запишет сервер</span>
          </div>
        )}
      </div>
    </>
  )
}
