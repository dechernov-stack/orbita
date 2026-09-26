// Реестр сторон (шип 5 §2): вкладка «Стороны» Постановки и тело сцены 3 — один
// компонент. Колонки: код · сторона · роль · влияние · сила кликом по баллу ·
// нужд. Чипы: без нужд · сила не задана · по роли. Сетка «влияние × сила» —
// переключателем справа от чипов: при ширине ≥ 1280 правее таблицы, иначе под
// ней; инженеру не показывается (третьей колонки у него нет).
//
// Сила: пять клеток, своя оценка — заливкой, предложенная по роли (истина
// `power_map`, З-02) — штрихом и «?»: согласие не требует действий, клик по
// клетке ставит свою. На сетке сторона — чип с инициалами; перенос — клик по
// чипу и клик по клетке, пишет то же поле, что и балл в таблице.
import { useContext, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { api, type EntityRow, type FactRow, type KindSpec } from '../api'
import { интересы } from '../interests'
import { инициалы } from '../people'
import type { ЧипОтбора } from '../ui/chips'
import { ПлотностьКонтекст } from '../ui/density'
import { ИконКнопка } from '../ui/iconbutton'
import type { МассовоеДействие } from '../ui/mass'
import { Карточка } from '../ui/objectcard'
import { имяЗаписи, нуждыСтороны, силаСтороны, словом, type ДанныеПостановки } from './data'
import { Реестр, type Колонка } from './registry'

/** Колонки сетки слева направо — от «информируется» к «решает»; строки — сила 5…1. */
const ВЛИЯНИЕ_СЕТКИ = ['informed', 'influences', 'decides']
const СИЛА_СЕТКИ = [5, 4, 3, 2, 1]

export interface ПропсыРеестра {
  project: string
  данные: ДанныеПостановки
  onChanged: () => void
  /** Переход «к месту» — к сцене; нет — реестр уже в теле сцены. */
  onGoScene?: (сцена: string, зачем?: string) => void
}

/** Сила 1–5 кликом по баллу: своя — заливкой, предложенная по роли — штрихом. */
export function Сила({ с, занята, onSet }: { с: EntityRow; занята: boolean; onSet: (балл: number) => void }) {
  const { балл, предложена } = силаСтороны(с)
  return (
    <span className="v2-power" role="group" aria-label={`сила стороны ${с.code}`}>
      {[1, 2, 3, 4, 5].map((н) => {
        const залита = балл !== null && н <= балл
        const класс = !залита ? 'v2-power__c' : предложена ? 'v2-power__c v2-power__c--guess' : 'v2-power__c v2-power__c--on'
        return (
          <button key={н} type="button" className={класс} aria-pressed={балл === н && !предложена} disabled={занята}
            aria-label={`сила ${н} из 5`}
            title={занята
              ? 'запись идёт — балл поставится, как ответит сервер'
              : предложена && балл === н ? `предложено по роли: ${н} из 5 — нажмите, чтобы подтвердить` : `сила ${н} из 5`}
            onClick={() => onSet(н)} />
        )
      })}
      {предложена && <span className="v2-dim" title="сила предложена по роли — подтвердите кликом по клетке">?</span>}
      {балл === null && <span className="v2-dim" title="у роли карты силы нет — поставьте балл">не задана</span>}
    </span>
  )
}

/** Сетка «влияние × сила»: сторона — чип с инициалами; перенос — чип, затем клетка. */
export function СеткаВлияния({ стороны, вид, onMove }: {
  стороны: EntityRow[]
  вид: KindSpec | undefined
  onMove: (с: EntityRow, влияние: string, балл: number) => void
}) {
  const [выбрана, setВыбрана] = useState<string | null>(null)
  const вСетке = (с: EntityRow, влияние: string, балл: number) =>
    String(с.doc.influence ?? '') === влияние && силаСтороны(с).балл === балл
  const внеСетки = стороны.filter((с) => силаСтороны(с).балл === null)
  return (
    <div aria-label="сетка влияние × сила">
      <table className="v2-grid">
        <thead>
          <tr><th className="v2-grid__n">сила</th>{ВЛИЯНИЕ_СЕТКИ.map((к) => <th key={к}>{словом(вид, 'influence', к)}</th>)}</tr>
        </thead>
        <tbody>
          {СИЛА_СЕТКИ.map((балл) => (
            <tr key={балл}>
              <td className="v2-grid__n">{балл}</td>
              {ВЛИЯНИЕ_СЕТКИ.map((к) => (
                <td key={к} className={выбрана ? 'v2-grid__aim' : undefined}
                  role={выбрана ? 'button' : undefined}
                  aria-label={выбрана ? `перенести ${выбрана}: сила ${балл}, ${словом(вид, 'influence', к)}` : undefined}
                  onClick={() => {
                    const с = стороны.find((х) => х.code === выбрана)
                    setВыбрана(null)
                    if (с) onMove(с, к, балл)
                  }}>
                  {стороны.filter((с) => вСетке(с, к, балл)).map((с) => {
                    const { предложена } = силаСтороны(с)
                    return (
                      <button key={с.id} type="button"
                        className={['v2-stake-chip', предложена ? 'v2-stake-chip--proposed' : '',
                          с.doc.attitude === 'supports' ? 'v2-stake-chip--supports' : '',
                          с.doc.attitude === 'resists' ? 'v2-stake-chip--resists' : ''].filter(Boolean).join(' ')}
                        aria-pressed={выбрана === с.code}
                        title={`${имяЗаписи(с)} · ${словом(вид, 'role', с.doc.role)}${предложена ? ' · сила предложена по роли' : ''} — нажмите, чтобы перенести`}
                        onClick={(e) => { e.stopPropagation(); setВыбрана(выбрана === с.code ? null : с.code) }}>
                        {инициалы(имяЗаписи(с))}{предложена ? ' ?' : ''}
                      </button>
                    )
                  })}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="v2-empty__why">
        {выбрана
          ? `выбрана ${выбрана}: кликните по клетке — сила и влияние запишутся`
          : 'сила предложена по роли — подтвердите баллом в таблице или перенесите чип кликом'}
      </div>
      {внеСетки.length > 0 && (
        <div className="v2-empty__why">вне сетки: {внеСетки.map(имяЗаписи).join(', ')} — у роли карты силы нет, поставьте балл</div>
      )}
    </div>
  )
}

/** Основания стороны — факты поля знаний о ней (субъект — её имя) и их документы. */
export function основанияСтороны(с: EntityRow, факты: FactRow[]): { факты: FactRow[]; документы: string[] } {
  const имя = имяЗаписи(с).trim().toLowerCase()
  const свои = факты.filter((ф) => ф.subject.trim().toLowerCase() === имя)
  return { факты: свои, документы: Array.from(new Set(свои.map((ф) => ф.material).filter(Boolean))) }
}

export function РеестрСторон({ project, данные, onChanged, onGoScene, onНуждаИзИнтереса }: ПропсыРеестра & {
  /** В сцене 3: интерес ложится в строку нужды и уходит на сверку. */
  onНуждаИзИнтереса?: (формулировка: string, сторона: string) => void
}) {
  const { стороны, нужды } = данные
  const вид = данные.виды.stakeholder
  const плотность = useContext(ПлотностьКонтекст)
  const [сетка, setСетка] = useState(false)
  const [занята, setЗанята] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрыть] = useConfirm()
  const автор = плотность.кто || 'инженер'
  const инженер = плотность.режим === 'мероприятие'

  const поправить = (коды: string[], поля: (с: EntityRow) => Record<string, unknown> | null, зачем: string) => {
    const цели = коды.map((к) => стороны.find((с) => с.code === к)).filter((с): с is EntityRow => Boolean(с))
    const правки = цели.map((с) => [с, поля(с)] as const).filter(([, п]) => п !== null)
    if (правки.length === 0) { setИтог('править нечего: у выбранных это уже так'); return }
    setОтказ(null); setЗанята(правки.length === 1 ? правки[0][0].code : '*')
    Promise.allSettled(правки.map(([с, п]) => api.patchEntity(project, с.code, п ?? {}, автор, зачем)))
      .then((итоги) => {
        const отказы = итоги.filter((и) => и.status === 'rejected').length
        setИтог(отказы === 0 ? `сохранено: ${правки.length}` : `сохранено ${правки.length - отказы}, отказов ${отказы}`)
        onChanged()
      })
      .finally(() => setЗанята(null))
  }

  const связатьСНуждой = (коды: string[]) => {
    if (нужды.length === 0) { setИтог('нужд пока нет — они заводятся на сцене 3'); return }
    спросить({
      question: `Сделать ${коды.length === 1 ? `сторону ${коды[0]}` : `стороны (${коды.length})`} носителем нужды: нужда — много носителей, прежние останутся.`,
      ok: 'Связать',
      choice: { label: 'нужда', options: нужды.map((н) => [н.code, `${н.code} · ${имяЗаписи(н)}`]) },
      onOk: (нужда) => {
        setОтказ(null)
        Promise.allSettled(коды.map((к) => api.needOwner(project, нужда, к, { author: автор })))
          .then((итоги) => {
            const отказы = итоги.filter((и) => и.status === 'rejected')
            setИтог(отказы.length === 0 ? `носителей у ${нужда}: +${коды.length}` : `связано ${коды.length - отказы.length}, отказов ${отказы.length}`)
            if (отказы.length > 0) setОтказ(String((отказы[0] as PromiseRejectedResult).reason?.message ?? ''))
            onChanged()
          })
      },
    })
  }

  const задатьОтношение = (коды: string[]) => {
    const варианты = (вид?.enums?.attitude ?? []).map((к) => [к, словом(вид, 'attitude', к)] as [string, string])
    спросить({
      question: `Отношение ${коды.length === 1 ? `стороны ${коды[0]}` : `сторон (${коды.length})`} к проекту — оценка человека.`,
      ok: 'Записать',
      choice: { label: 'отношение', options: варианты },
      onOk: (отношение) => поправить(коды, () => ({ attitude: отношение }), 'отношение стороны'),
    })
  }

  const колонки: Колонка<EntityRow>[] = [
    { key: 'код', title: 'Код', cell: (с) => с.code, className: 'v2-mono' },
    { key: 'сторона', title: 'Сторона', cell: (с) => имяЗаписи(с) },
    { key: 'роль', title: 'Роль', cell: (с) => словом(вид, 'role', с.doc.role) || <span className="v2-dim">не названа</span>, className: 'v2-dim' },
    {
      key: 'влияние', title: 'Влияние', hint: 'влияние считает система по роли; правится в карточке',
      cell: (с) => словом(вид, 'influence', с.doc.influence) || <span className="v2-dim">не задано</span>,
    },
    {
      key: 'сила', title: 'Сила', hint: 'оценка человека 1–5 кликом; штрих — предложено по роли',
      cell: (с) => <Сила с={с} занята={занята === с.code || занята === '*'}
        onSet={(балл) => поправить([с.code], () => ({ power: балл }), 'сила стороны')} />,
    },
    {
      key: 'нужд', title: 'Нужд',
      cell: (с) => {
        const n = нуждыСтороны(с, нужды, стороны).length
        return n === 0 ? <span className="v2-warn" title="нужд нет — сцена 3 этим держится">нет</span> : n
      },
    },
  ]

  const роли = Array.from(new Set(стороны.map((с) => String(с.doc.role ?? '')).filter(Boolean)))
  const чипы: ЧипОтбора<EntityRow>[] = [
    { key: 'без-нужд', word: 'без нужд', group: 'нужды', test: (с) => нуждыСтороны(с, нужды, стороны).length === 0, hint: 'стороны без нужд: пока они есть, сцена 3 не закроется' },
    { key: 'без-силы', word: 'сила не задана', group: 'сила', test: (с) => силаСтороны(с).балл === null || силаСтороны(с).предложена, hint: 'своей оценки силы нет: предложена по роли или не задана вовсе' },
    ...роли.map((р) => ({ key: `роль-${р}`, word: словом(вид, 'role', р), group: 'роль', test: (с: EntityRow) => с.doc.role === р })),
  ]

  const массово: МассовоеДействие[] = [
    {
      key: 'сила', икон: 'принять', слово: 'подтвердить силу', клавиша: 'A',
      run: (коды) => поправить(коды, (с) => {
        const { балл, предложена } = силаСтороны(с)
        return предложена && балл !== null ? { power: балл } : null
      }, 'сила подтверждена по роли'),
    },
    { key: 'нужда', икон: 'связать', слово: 'связать с нуждой', run: связатьСНуждой },
    { key: 'отношение', икон: 'править', слово: 'отношение', run: задатьОтношение },
  ]

  const хвост = (
    <>
      {!инженер && (
        <ИконКнопка икон="сетка" слово="сетка" сословом className="v2-btn" aria-pressed={сетка}
          onClick={() => setСетка(!сетка)} />
      )}
      {onGoScene && (
        <ИконКнопка икон="добавить" слово="добавить сторону" сословом className="v2-btn v2-btn--primary"
          onClick={() => onGoScene('3', 'добавить сторону: ввод идёт через сверку на сцене 3')} />
      )}
    </>
  )

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}
      <Реестр label="реестр сторон" строки={стороны} ключ={(с) => с.code} колонки={колонки} чипы={чипы}
        поиск={{ placeholder: 'найти сторону', text: (с) => имяЗаписи(с) }}
        хвост={хвост}
        действия={(с) => <ИконКнопка икон="связать" слово="связать с нуждой" onClick={() => связатьСНуждой([с.code])} />}
        массово={массово}
        наборы={(видимые) => [
          { key: 'предложена', word: 'сила предложена', keys: видимые.filter((с) => силаСтороны(с).предложена).map((с) => с.code) },
          { key: 'без-нужд', word: 'без нужд', keys: видимые.filter((с) => нуждыСтороны(с, нужды, стороны).length === 0).map((с) => с.code) },
        ]}
        пусто={<>Сторон пока нет.<span className="v2-empty__why">Они заводятся на сцене 3 — руками или чтением записки.</span></>}
        рядом={сетка && !инженер
          ? <СеткаВлияния стороны={стороны} вид={вид}
              onMove={(с, влияние, балл) => поправить([с.code], () => ({ power: балл, influence: влияние }), 'перенос стороны на сетке влияния')} />
          : undefined}
        карточка={(с, закрытьКарточку) => вид
          ? (
            <Карточка project={project} row={с} spec={вид} скрыть={['interest']} onSaved={onChanged} onClose={закрытьКарточку}
              кМесту={onGoScene ? { слово: 'к месту: сцена 3', go: () => onGoScene('3', `сторона ${с.code}`) } : undefined}
              extra={<ГраниСтороны project={project} с={с} данные={данные} автор={автор} onChanged={onChanged}
                onНужда={onНуждаИзИнтереса ?? (onGoScene ? (ф) => onGoScene('3', `нужда из интереса стороны ${с.code}: ${ф}`) : undefined)} />} />
          )
          : <div className="v2-empty">Читаю истину вида…</div>} />
      <ConfirmBox request={ask} onClose={закрыть} />
    </>
  )
}

/**
 * Грани стороны, которых у вида нет полем: интересы её словами (правятся
 * строкой через «;»), нужды, основания и документы. Интерес — не нужда
 * (истина 24.09, interest_to_need_rule): нуждой он становится решением
 * человека через сверку.
 */
function ГраниСтороны({ project, с, данные, автор, onChanged, onНужда }: {
  project: string
  с: EntityRow
  данные: ДанныеПостановки
  автор: string
  onChanged: () => void
  onНужда?: (формулировка: string, сторона: string) => void
}) {
  const [правлю, setПравлю] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const свои = интересы(с.doc.interest)
  const нуждыЕё = нуждыСтороны(с, данные.нужды, данные.стороны)
  const основания = основанияСтороны(с, данные.факты)
  const записать = (строка: string) => {
    setОтказ(null)
    api.patchEntity(project, с.code, { interest: строка }, автор, 'интересы стороны')
      .then(() => { setПравлю(false); onChanged() })
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
  }
  // Нужда правится там, где показана (владелец 16.09: «карандаша нет в
  // нуждах, только текст»): формулировка — прямо в карточке стороны.
  const записатьНужду = (код: string, формулировка: string) => {
    setОтказ(null)
    api.patchEntity(project, код, { statement: формулировка }, автор, `формулировка нужды в карточке стороны ${с.code}`)
      .then(onChanged)
      .catch((e) => setОтказ(String((e as Error).message ?? e)))
  }
  return (
    <>
      <div className="v2-facet v2-facet--wide">
        <label>Интересы</label>
        {правлю
          ? <textarea defaultValue={свои.map((и) => и.statement).join('; ')} aria-label="интересы стороны через точку с запятой"
              onBlur={(e) => записать(e.target.value)} autoFocus />
          : свои.length === 0
            ? <div className="v2-warn">интересы не записаны</div>
            : (
              <ul className="v2-why">
                {свои.map((и, i) => (
                  <li key={i}>
                    {и.statement}
                    {и.quote ? <span className="v2-dim"> — «{и.quote}»</span> : null}
                    {onНужда && <>{' '}<ИконКнопка икон="добавить" слово="нужда из интереса" onClick={() => onНужда(и.statement, с.code)} /></>}
                  </li>
                ))}
              </ul>
            )}
        {!правлю && <button type="button" className="v2-link" onClick={() => setПравлю(true)} title="править интересы одной строкой: «;» между интересами, цитаты нетронутых останутся">править</button>}
        {отказ && <div className="v2-facet__err">{отказ}</div>}
        <div className="v2-facet__hint">чего сторона хочет, её словами; нуждой становится решением через сверку</div>
      </div>
      <div className="v2-facet v2-facet--wide">
        <label>Нужды</label>
        {нуждыЕё.length === 0
          ? <div className="v2-warn">нужд нет — сцена 3 этим держится</div>
          : (
            <ul className="v2-why">
              {нуждыЕё.map((н) => (
                <li key={н.id} className="v2-facet__row">
                  <span className="v2-mono">{н.code}</span>
                  <input defaultValue={имяЗаписи(н)} aria-label={`формулировка нужды ${н.code}`}
                    onBlur={(e) => {
                      const новая = e.target.value.trim()
                      if (новая && новая !== имяЗаписи(н)) записатьНужду(н.code, новая)
                    }} />
                </li>
              ))}
            </ul>
          )}
        <div className="v2-facet__hint">формулировка правится здесь же; носители и класс — в реестре нужд</div>
      </div>
      <div className="v2-facet">
        <label>Основания</label>
        {основания.факты.length === 0
          ? <div className="v2-dim">фактов об этой стороне в поле знаний нет</div>
          : (
            <ul className="v2-why">
              {основания.факты.slice(0, 5).map((ф) => (
                <li key={ф.id} title={ф.anchor ? `якорь ${ф.anchor}` : 'якоря нет'}>
                  {ф.predicate}: {ф.value}{ф.unit ? ` ${ф.unit}` : ''}<span className="v2-dim"> · {ф.mark}{ф.anchor ? ` · ${ф.anchor}` : ''}</span>
                </li>
              ))}
              {основания.факты.length > 5 && <li className="v2-dim">и ещё {основания.факты.length - 5}</li>}
            </ul>
          )}
        <div className="v2-facet__hint">документы: {основания.документы.length === 0 ? 'нет' : основания.документы.join(', ')}</div>
      </div>
    </>
  )
}
