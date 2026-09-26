// Вкладка «Факты» поля знаний (шип 5 §3.2) — полосы групп по предмету.
//
// Владелец (КТ2): «объединение фактов по группам — стало только хуже: колонка
// «Утверждение», а написано там всё подряд». Группа теперь — своя полоса над
// своей таблицей (§1.2): раскрыватель, предмет жирно, счётчики словами,
// действия группы пиктограммами. Внутри группы колонки БЕЗ предмета:
// утверждение · значение · метка · ранг · откуда (документ · якорь ссылкой,
// раскрывает цитату) · решение · действия. Чипы: документ · метка ·
// диспозиция · нерешённые · противоречия · ручной ввод; поиск по
// утверждению. Массово: принять · отклонить · в допущения · учесть. Ручной
// ввод — полосой «Ручной ввод» с формой, а не отдельной карточкой.
import { Fragment, useMemo, useState, type ReactNode } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type FactRow, type Gate } from '../api'
import { ОкноДопущения } from '../registry/assumptions'
import { Полоса, РаскрытьВсе, useПолосы } from '../ui/band'
import { Чипы, найдено, отобрать, type ЧипОтбора } from '../ui/chips'
import { ИконКнопка } from '../ui/iconbutton'
import { Отметка, ПанельМассово, useМассово, type МассовоеДействие } from '../ui/mass'
import { СЛОВО_РАНГА, СЛОВО_РЕШЕНИЯ } from '../words'

/** Метка происхождения словом: И — наш документ · В — внешний · П — допущение. */
export const МЕТКА_ФАКТА: Record<string, string> = { И: 'наш документ', В: 'внешний источник', П: 'допущение' }

/** Предмет факта для полосы: субъект утверждения; без субъекта — материал или ручной ввод. */
export function предметФакта(ф: { subject?: string; material?: string; manual?: boolean }): string {
  const с = (ф.subject ?? '').trim()
  if (с) return с
  return ф.manual ? 'ручной ввод' : (ф.material ?? '—')
}

type ФактСпора = { code?: string; id?: string; disposition?: string; conflicts?: string[] }

/**
 * Спор факта, который — ЕГО долг (ответ владельца 26.09): противоречие к
 * принятому факту — долг другого факта. У принятого спор с непринятым не
 * показывается равноправным; у непринятого — весь его спор. Оба приняты —
 * спор у обоих: принятое не согласовано, и это держит точку.
 */
export function долгСпора<T extends ФактСпора>(ф: T, поКоду: Map<string, T>): string[] {
  const спор = ф.conflicts ?? []
  if ((ф.disposition ?? 'free') !== 'adopted') return спор
  return спор.filter((код) => поКоду.get(код)?.disposition === 'adopted')
}

/** Кто оспаривает принятый факт: непринятые — их долг, здесь они только названы. */
export function оспаривают<T extends ФактСпора>(ф: T, поКоду: Map<string, T>): string[] {
  if ((ф.disposition ?? 'free') !== 'adopted') return []
  return (ф.conflicts ?? []).filter((код) => поКоду.get(код)?.disposition !== 'adopted')
}

/**
 * «Подтверждено N документами»: другие документы, где то же утверждение с тем
 * же значением, — по связям `same_as` и `supports` (их ставит сверка по правилу
 * 26.09). Свой документ подтверждением не считается.
 */
export function подтверждающие(ф: FactRow, поКоду: Map<string, FactRow>): string[] {
  const свой = ф.code || ф.id
  const документы = new Set<string>()
  ;(ф.links ?? []).filter((с) => с.type === 'same_as' || с.type === 'supports').forEach((с) => {
    const другой = поКоду.get(с.from === свой ? с.to : с.from)
    if (другой && другой.material && другой.material !== ф.material) документы.add(другой.material)
  })
  return [...документы]
}

/** Факты по коду: спор и подтверждение смотрят на другой конец связи. */
export function фактыПоКоду<T extends ФактСпора>(факты: T[]): Map<string, T> {
  return new Map(факты.map((ф) => [ф.code || ф.id || '', ф]))
}

/** Группы фактов по предмету — в порядке появления, с числом нерешённых и противоречий (долгов спора). */
export function группыФактов<T extends { subject?: string; material?: string; manual?: boolean; disposition?: string; conflicts?: string[]; code?: string; id?: string }>(
  факты: T[],
): { предмет: string; факты: T[]; нерешено: number; противоречий: number }[] {
  const поКоду = фактыПоКоду(факты)
  const по = new Map<string, T[]>()
  факты.forEach((ф) => {
    const п = предметФакта(ф)
    const с = по.get(п)
    if (с) с.push(ф); else по.set(п, [ф])
  })
  return [...по.entries()].map(([предмет, список]) => ({
    предмет,
    факты: список,
    нерешено: список.filter((ф) => (ф.disposition ?? 'free') === 'free').length,
    противоречий: список.filter((ф) => долгСпора(ф, поКоду).length > 0).length,
  }))
}

/**
 * Нужен ли повод к решению. Правило то же, что на сервере: сервер откажет и
 * без нас, но спрашивать текст там, где он не нужен, — налог.
 */
export function нуженПовод(было: string, стало: string): boolean {
  if (стало === 'rejected') return true
  if (было === 'contested') return true
  return было !== 'free' && было !== стало
}

/**
 * Бесспорный факт: не решён, без противоречий и не сомнительного ранга. Такие
 * принимаются пакетом — «принять решённые» полосы; сомнительный ранг пакетом
 * не принимается тем же правилом, каким сервер запирает пакетный приём плана.
 */
export function бесспорный(ф: FactRow): boolean {
  return (ф.disposition ?? 'free') === 'free' && (ф.conflicts?.length ?? 0) === 0 && ф.rank !== 'doubtful'
}

export interface ТемаПоля { id: string; label: string; scene?: string | null; facts: number; resolved_to?: string | null }

const ключФакта = (ф: FactRow) => ф.code || ф.id

/** Итог пакета словами: сколько прошло, сколько отказало и первая причина. */
type Итог = { что: string; прошло: number; отказов: number; пропущено: number; причина: string | null }

export function ВкладкаФакты({
  project, факты, темы, знанияV2, документы, точки, автор, ручнойВвод, ручнойОткрыт = false, onChanged, onGoScene, темаПанель,
}: {
  project: string
  факты: FactRow[]
  темы: ТемаПоля[]
  /** Поле знаний v2: ранг доверия и отбор «из исследований». */
  знанияV2: boolean
  /** Имена документов по коду: чип документа — словом, а не кодом. */
  документы: Record<string, string>
  /** Точки фазы: срок подтверждения допущения. */
  точки?: Gate[]
  автор: string
  /** Форма ручного ввода — в полосе «Ручной ввод». */
  ручнойВвод: ReactNode
  /** Сюда пришли из отчёта «Темы без разрешения»: полоса ручного ввода раскрыта сразу. */
  ручнойОткрыт?: boolean
  onChanged: () => void
  onGoScene?: (сцена: string, зачем?: string) => void
  /** Строка темы (разрешить в сущность, слить) — когда отбор сужен до одной темы. */
  темаПанель?: (тема: string) => ReactNode
}) {
  const [включены, setВключены] = useState<Set<string>>(new Set())
  const [игла, setИгла] = useState('')
  const [подсвечен, setПодсвечен] = useState<string | null>(null)
  const [цитата, setЦитата] = useState<string | null>(null)
  const [окно, setОкно] = useState<string[] | null>(null)
  const [идёт, setИдёт] = useState<string | null>(null)
  const [итог, setИтог] = useState<Итог | null>(null)
  const [ручнойРаскрыт, setРучнойРаскрыт] = useState(ручнойОткрыт)
  const [ask, спросить, закрытьВопрос] = useConfirm()

  const материалы = useMemo(() => Array.from(new Set(факты.filter((ф) => !ф.manual).map((ф) => ф.material).filter(Boolean))), [факты])
  /** Факты по коду: спор к принятому — долг другого факта, подтверждение — другой документ. */
  const поКоду = useMemo(() => фактыПоКоду(факты), [факты])
  const решения = useMemo(() => Array.from(new Set(факты.map((ф) => ф.disposition || 'free'))).filter((д) => д !== 'free'), [факты])
  const чипы = useMemo<ЧипОтбора<FactRow>[]>(() => [
    ...материалы.map((м) => ({ key: `док-${м}`, word: документы[м] ?? м, group: 'документ', test: (ф: FactRow) => !ф.manual && ф.material === м, hint: `факты документа ${м}` })),
    ...(['И', 'В', 'П'] as const).map((б) => ({ key: `метка-${б}`, word: `${б} · ${МЕТКА_ФАКТА[б]}`, group: 'метка', test: (ф: FactRow) => ф.mark === б })),
    { key: 'нерешённые', word: 'нерешённые', group: 'решение', test: (ф) => (ф.disposition ?? 'free') === 'free', hint: 'факты, по которым решения ещё нет' },
    ...решения.map((д) => ({ key: `решение-${д}`, word: СЛОВО_РЕШЕНИЯ[д] ?? д, group: 'решение', test: (ф: FactRow) => ф.disposition === д })),
    { key: 'противоречия', word: 'противоречия', group: 'противоречия', test: (ф) => долгСпора(ф, поКоду).length > 0, hint: 'однозначное утверждение с иным значением в другом документе: показаны оба, решает человек' },
    { key: 'ручной', word: 'ручной ввод', group: 'ввод', test: (ф) => ф.manual === true, hint: 'факты без документа-источника: эксперт, роль и дата' },
    ...(знанияV2 ? [{ key: 'исследования', word: 'из исследований', group: 'ранг', test: (ф: FactRow) => ф.rank === 'doubtful', hint: 'принесённое внешним контуром: ранг сомнительный, пока человек не подтвердил источники' }] : []),
    ...темы.filter((т) => т.facts > 0).map((т) => ({ key: `тема-${т.id}`, word: т.label, group: 'тема', test: (ф: FactRow) => ф.topic === т.id, hint: `факты темы: ${т.facts}` })),
  ], [материалы, решения, документы, знанияV2, темы, поКоду])

  const видно = useMemo(() => {
    const поЧипам = отобрать(факты, чипы, включены)
    return игла.trim() ? поЧипам.filter((ф) => найдено(`${ф.subject} ${ф.predicate} ${ф.value}`, игла)) : поЧипам
  }, [факты, чипы, включены, игла])
  const группы = группыФактов(видно)
  const предметные = группы.filter((г) => г.предмет !== 'ручной ввод')
  const ручныеБезПредмета = группы.find((г) => г.предмет === 'ручной ввод')?.факты ?? []
  const полосы = useПолосы(предметные.map((г) => г.предмет))
  const ключи = useMemo(
    () => [...предметные.flatMap((г) => (полосы.открыта(г.предмет) ? г.факты.map(ключФакта) : [])),
      ...(ручнойРаскрыт ? ручныеБезПредмета.map(ключФакта) : [])],
    [предметные, полосы, ручнойРаскрыт, ручныеБезПредмета],
  )
  const поКлючу = useMemo(() => new Map(факты.map((ф) => [ключФакта(ф), ф])), [факты])
  const выбрать = (коды: string[]) => коды.map((к) => поКлючу.get(к)).filter((ф): ф is FactRow => Boolean(ф))

  /** Решения подряд — по одному вызову на факт: отказ одного не отменяет принятого. */
  const подряд = async (что: string, список: FactRow[], решение: string, причина: string, пропущено = 0) => {
    setИтог(null)
    let прошло = 0
    let отказов = 0
    let первая: string | null = null
    for (const ф of список) {
      setИдёт(`${что}: ${прошло + отказов} из ${список.length}`)
      try {
        await api.disposeFact(project, ф.id, решение, причина, автор)
        прошло += 1
      } catch (e) {
        отказов += 1
        if (первая === null) первая = `${ключФакта(ф)} — ${String((e as Error).message ?? e)}`
      }
    }
    setИдёт(null)
    setИтог({ что, прошло, отказов, пропущено, причина: первая })
    onChanged()
  }

  const принять = (коды: string[]) => {
    const все = выбрать(коды)
    const можно = все.filter((ф) => ф.disposition !== 'adopted' && !нуженПовод(ф.disposition ?? 'free', 'adopted') && ф.rank !== 'doubtful')
    void подряд('принято', можно, 'adopted', '', все.length - можно.length)
  }
  const отклонить = (коды: string[]) => спросить({
    question: `Отклонить ${коды.length === 1 ? `факт ${коды[0]}` : `факты (${коды.length})`}: факт не исчезает, решение видно в истории.`,
    ok: 'Отклонить',
    input: { label: 'почему не берём', required: true },
    onOk: (причина) => { void подряд('отклонено', выбрать(коды), 'rejected', причина) },
  })
  const учесть = (коды: string[]) => {
    const все = выбрать(коды)
    const сПоводом = все.some((ф) => нуженПовод(ф.disposition ?? 'free', 'noted'))
    if (!сПоводом) { void подряд('учтено', все, 'noted', ''); return }
    спросить({
      question: `Учесть ${коды.length === 1 ? коды[0] : `факты (${коды.length})`}: среди них есть решённые — нужен повод.`,
      ok: 'Учесть',
      input: { label: 'что изменилось с прошлого решения', required: true },
      onOk: (причина) => { void подряд('учтено', все, 'noted', причина) },
    })
  }

  const кФакту = (код: string) => {
    const ф = факты.find((х) => ключФакта(х) === код)
    if (ф && !полосы.открыта(предметФакта(ф))) полосы.переключить(предметФакта(ф))
    setПодсвечен(код)
    window.setTimeout(() => document.getElementById(`v2-fact-${код}`)?.scrollIntoView({ block: 'center' }), 50)
  }

  const массово: МассовоеДействие[] = [
    { key: 'принять', икон: 'принять', слово: 'принять', клавиша: 'A', run: принять },
    { key: 'отклонить', икон: 'отклонить', слово: 'отклонить', клавиша: 'R', run: отклонить },
    { key: 'допущения', икон: 'отложить', слово: 'в допущения', run: (коды) => setОкно(коды) },
    { key: 'учесть', икон: 'вернуть', слово: 'учесть', клавиша: 'L', run: учесть },
  ]
  const м = useМассово(ключи, { действия: массово })
  const переключитьЧип = (к: string) => setВключены((было) => {
    const стало = new Set(было)
    if (стало.has(к)) стало.delete(к); else стало.add(к)
    return стало
  })
  const однаТема = [...включены].filter((к) => к.startsWith('тема-'))
  const колонок = (знанияV2 ? 8 : 7)

  const строка = (ф: FactRow) => {
    const к = ключФакта(ф)
    const было = ф.disposition ?? 'free'
    const тема = темы.find((т) => т.id === ф.topic)
    const открытаЦитата = цитата === к
    return (
      <Fragment key={к}>
        <tr id={`v2-fact-${к}`}
          className={[ключи[м.курсор] === к ? 'v2-row--cursor' : '', подсвечен === к ? 'v2-row--cur' : ''].filter(Boolean).join(' ') || undefined}
          onClick={() => м.setКурсор(ключи.indexOf(к))}>
          <td className="v2-reg__mark"><Отметка ключ={к} выбрана={м.выбраны.has(к)} onToggle={м.отметить} /></td>
          <td>{ф.predicate}{ф.param_key && <span className="v2-dim"> · анкета: {ф.param_key}</span>}</td>
          <td>{ф.value}{ф.unit ? ` ${ф.unit}` : ''}</td>
          <td title={МЕТКА_ФАКТА[ф.mark] ?? ф.mark}>{ф.mark}</td>
          {знанияV2 && (
            <td className="v2-dim" title={ф.rank ? 'ранг доверия наследуется от источника; у руки эксперта — экспертный' : 'ранга нет: факт заведён до перестройки поля'}>
              {ф.rank ? СЛОВО_РАНГА[ф.rank] ?? ф.rank : '—'}
            </td>
          )}
          <td className="v2-nowrap">
            {ф.manual
              ? <span className="v2-dim" title="без документа-источника: эксперт, роль и дата">ручной ввод</span>
              : (
                <button type="button" className="v2-link" aria-expanded={открытаЦитата}
                  title={ф.quote ? 'раскрыть цитату блока документа' : 'цитаты у факта нет — только документ и якорь'}
                  onClick={(e) => { e.stopPropagation(); setЦитата(открытаЦитата ? null : к) }}>
                  {документы[ф.material] ?? ф.material}{ф.anchor ? ` · ${ф.anchor}` : ''}
                </button>
              )}
            {(() => {
              const другие = подтверждающие(ф, поКоду)
              return другие.length > 0 && (
                <span className="v2-dim" title={`то же значение: ${другие.map((м) => документы[м] ?? м).join(' · ')}`}>
                  {' '}· подтверждено {другие.length}
                </span>
              )
            })()}
          </td>
          <td>
            <span className={было === 'adopted' ? 'v2-ok' : было === 'free' ? 'v2-warn' : undefined}>{СЛОВО_РЕШЕНИЯ[было] ?? было}</span>
            {(() => {
              // Противоречие к принятому — долг другого факта (ответ владельца 26.09).
              const долг = долгСпора(ф, поКоду)
              const спорят = оспаривают(ф, поКоду)
              const ссылки = (коды: string[]) => (
                <>
                  {коды.slice(0, 3).map((код, i, три) => (
                    <span key={код} className="v2-nowrap">
                      <button type="button" className="v2-link" onClick={(e) => { e.stopPropagation(); кФакту(код) }}
                        title={`перейти к факту ${код}: его полоса раскроется, строка подсветится`}>{код}</button>
                      {i < три.length - 1 ? ', ' : ''}
                    </span>
                  ))}
                  {коды.length > 3 && <span className="v2-dim" title={коды.slice(3).join(', ')}> и ещё {коды.length - 3}</span>}
                </>
              )
              const сПринятым = долг.filter((код) => поКоду.get(код)?.disposition === 'adopted')
              return (
                <>
                  {долг.length > 0 && (
                    <span className="v2-bad" title="противоречие: однозначное утверждение с иным значением в другом документе; победителя ИИ не выбирает — решает человек">
                      {' '}· противоречит {сПринятым.length === долг.length && (ф.disposition ?? 'free') !== 'adopted' ? 'принятому ' : ''}{ссылки(долг)}
                    </span>
                  )}
                  {спорят.length > 0 && (
                    <span className="v2-dim" title="с принятым фактом спорит непринятый: решение — за тем фактом, это его долг">
                      {' '}· оспаривают {ссылки(спорят)}
                    </span>
                  )}
                </>
              )
            })()}
            {ф.source_updated && <span className="v2-warn" title={ф.source_updated}> · источник обновлён</span>}
            {ф.assumption && (
              <div className="v2-dim" title={`проверка: ${ф.assumption.validation || '—'}; если неверно: ${ф.assumption.impact_if_wrong || '—'}`}>
                допущение · {ф.assumption.owner} · к {ф.assumption.confirm_by}
              </div>
            )}
          </td>
          <td className="v2-acts">
            {было !== 'adopted' && (
              <ИконКнопка икон="принять" слово="принять факт" disabled={нуженПовод(было, 'adopted') || ф.rank === 'doubtful'}
                onClick={() => (нуженПовод(было, 'adopted')
                  ? спросить({ question: `Принять ${к}: прежнее решение меняется — нужен повод.`, ok: 'Принять', input: { label: 'что изменилось с прошлого решения', required: true }, onOk: (п) => { void подряд('принято', [ф], 'adopted', п) } })
                  : void подряд('принято', [ф], 'adopted', ''))} />
            )}
            {было !== 'rejected' && <ИконКнопка икон="отклонить" слово="отклонить с причиной" onClick={() => отклонить([к])} />}
            {было !== 'assumed' && было !== 'adopted' && <ИконКнопка икон="отложить" слово="в допущения" onClick={() => setОкно([к])} />}
            {тема?.scene && onGoScene && <ИконКнопка икон="к-месту" слово={`к месту: сцена ${тема.scene}`} onClick={() => onGoScene(тема.scene!, `факт ${к}`)} />}
          </td>
        </tr>
        {открытаЦитата && (
          <tr className="v2-card-row">
            <td colSpan={колонок}>
              <div className="v2-quote">
                {ф.quote ? `«${ф.quote}»` : 'цитаты у факта нет: разбор этого документа её не клал'}
                <span className="v2-dim"> — {документы[ф.material] ?? ф.material}{ф.anchor ? `, ${ф.anchor}` : ''}</span>
              </div>
            </td>
          </tr>
        )}
      </Fragment>
    )
  }

  const таблица = (список: FactRow[]) => (
    <div className="v2-reg__table" tabIndex={0} onKeyDown={м.клавиша} aria-label="факты группы: ↑ ↓ строка, Space отметить">
      <table className="v2-table v2-facts">
        <thead>
          <tr>
            <th className="v2-reg__mark" />
            <th>Утверждение</th><th>Значение</th><th title="И — наш документ · В — внешний источник · П — допущение">Метка</th>
            {знанияV2 && <th>Ранг</th>}
            <th>Откуда</th><th>Решение</th><th className="v2-acts" aria-label="действия строки" />
          </tr>
        </thead>
        <tbody>{список.map(строка)}</tbody>
      </table>
    </div>
  )

  return (
    <div className="v2-facts-tab" aria-label="факты поля знаний">
      <Чипы строки={факты} чипы={чипы} включены={включены} onToggle={переключитьЧип} label="отбор фактов">
        <input type="search" value={игла} placeholder="найти утверждение" aria-label="найти утверждение"
          onChange={(e) => setИгла(e.target.value)} />
      </Чипы>
      {однаТема.length === 1 && темаПанель?.(однаТема[0].replace(/^тема-/, ''))}
      {окно && (
        <ОкноДопущения project={project} факты={выбрать(окно).map((ф) => ф.id)} автор={автор}
          точки={точки && точки.length > 0 ? точки.map((т) => [т.key, т.title]) : [['internal_review', 'внутренний обзор'], ['MCR', 'MCR'], ['KDP-A', 'KDP-A']]}
          onDone={(т) => { setИтог({ что: т, прошло: 0, отказов: 0, пропущено: 0, причина: null }); setОкно(null); onChanged() }}
          onCancel={() => setОкно(null)} />
      )}
      {идёт && <div className="v2-note-line">{идёт}</div>}
      {итог && (
        <div className="v2-note-line" data-why="работа">
          {итог.прошло > 0 || итог.отказов > 0 ? `${итог.что}: ${итог.прошло}` : итог.что}
          {итог.отказов > 0 && ` · отказал сервер: ${итог.отказов}`}
          {итог.пропущено > 0 && ` · пропущено: ${итог.пропущено} — решённые с поводом и сомнительного ранга решаются в строке`}
          {итог.причина && <span className="v2-locked"> первый отказ — {итог.причина}</span>}
        </div>
      )}
      {видно.length === 0 && (
        <div className="v2-empty">
          {факты.length === 0 ? 'Фактов пока нет.' : 'Под отбор ничего не попало — снимите чип или поиск.'}
          <span className="v2-empty__why">Поле наполняется разбором документа — «Загрузить источник» — либо ручным вводом ниже.</span>
        </div>
      )}
      {предметные.length > 1 && (
        <div className="v2-bands__all"><РаскрытьВсе все={полосы.все} число={полосы.число} onClick={полосы.всеРазом} /></div>
      )}
      {предметные.map((г) => {
        const бесспорные = г.факты.filter(бесспорный)
        return (
          <Полоса key={г.предмет} open={полосы.открыта(г.предмет)} onToggle={() => полосы.переключить(г.предмет)}
            subject={г.предмет}
            counts={`фактов ${г.факты.length} · нерешённых ${г.нерешено}${г.противоречий > 0 ? ` · противоречий ${г.противоречий}` : ''}`}
            actions={<ИконКнопка икон="принять" слово={`принять бесспорные: ${бесспорные.length}`} disabled={бесспорные.length === 0 || идёт !== null}
              onClick={() => { void подряд('принято', бесспорные, 'adopted', '') }} />}>
            {таблица(г.факты)}
          </Полоса>
        )
      })}
      <Полоса open={ручнойРаскрыт} onToggle={() => setРучнойРаскрыт(!ручнойРаскрыт)} subject="Ручной ввод"
        counts={`фактов без документа ${факты.filter((ф) => ф.manual).length} · сверка при сохранении`}>
        {ручнойВвод}
        {ручныеБезПредмета.length > 0 && таблица(ручныеБезПредмета)}
      </Полоса>
      {факты.length > 0 && (
        <ПанельМассово выбранные={м.выбранные} действия={массово}
          наборы={[{ key: 'бесспорные', word: 'все бесспорные в раскрытых', keys: ключи.filter((к) => { const ф = поКлючу.get(к); return ф ? бесспорный(ф) : false }) }]}
          onНабор={(н) => м.выбрать(н.keys)} onСнять={м.снять} />
      )}
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}
