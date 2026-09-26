// Экран требований (ТЗ §4.3, сцена 8): ШЕСТЬ КОЛОНОК и карточка вниз.
//
// Правило экрана: в таблице живёт только то, по чему ищут глазами — код,
// заголовок, формулировка, показатель, носитель, статус. Всё остальное
// (источники, метод, применимость, пометы линта) открывается карточкой под
// строкой, а не отдельным окном: контекст строки не теряется.
import { useCallback, useContext, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { Вкладки, useВкладка, Маркер } from './ui/tabs'
import { Полоса, РаскрытьВсе, useПолосы } from './ui/band'
import { ИконКнопка } from './ui/iconbutton'
import { Отметка, ПанельМассово, useМассово, type МассовоеДействие } from './ui/mass'
import { ПлотностьКонтекст } from './ui/density'
import { долг } from './ui/objectcard'
import { api, type BaselineRow, type Blocker, type LintNote, type RequirementRow, type SuspectRow } from './api'
import { НОСИТЕЛЬ, useВидТребования, кратко } from './requirements/common'
import { ЦелиБезТребования, ПредложенияТребований } from './requirements/scene8'
import { ПравкаТребования, КарточкаТребования, Группа, Помета } from './requirements/card'
import { Impact } from './requirements/impact'
import { Деревья, МатрицаНосителей } from './requirements/trees'
import { Подозрения, Базирование } from './requirements/baseline'
import { Форма } from './requirements/form'

/** Виды реестра (экран 9 шипа 2): одни и те же строки, разный порядок чтения. */
type ВидРеестра = 'таблица' | 'иерархия' | 'документы' | 'матрица'
const ВИДЫ: [ВидРеестра, string][] = [
  ['таблица', 'Таблица'], ['иерархия', 'По иерархии'], ['документы', 'По документам'], ['матрица', 'Матрица'],
]
type Колонка = 'code' | 'title' | 'statement' | 'measure' | 'carrier' | 'status'
const КОЛОНКИ: [Колонка, string][] = [
  ['code', 'Код'], ['title', 'Заголовок'], ['statement', 'Формулировка'],
  ['measure', 'Показатель'], ['carrier', 'Носитель'], ['status', 'Статус'],
]

/**
 * Начальный отбор реестра: реестр открывается ИЗ ЗАДАЧИ уже отфильтрованным
 * («3 требования без носителя» показывают три строки, а не четырнадцать).
 * Прямой вход — без отбора.
 */
export interface ОтборТребований {
  без_носителя?: boolean
  коды?: string[]
  уровень?: string
  носитель?: string
}

export function Requirements({ project, отбор }: { project: string | null; отбор?: ОтборТребований }) {
  const [строки, setСтроки] = useState<RequirementRow[]>([])
  const [открыта, setОткрыта] = useState<string | null>(null)
  const [вид, setВид] = useВкладка<ВидРеестра>('requirements', 'таблица', ВИДЫ.map(([к]) => к))
  const [сортировка, setСортировка] = useState<{ по: Колонка; вверх: boolean }>({ по: 'code', вверх: true })
  const [группировка, setГруппировка] = useState<'нет' | 'carrier' | 'level'>('нет')
  /** Материалы проекта: по ним вид «по документам» узнаёт источник-документ. */
  const [документы, setДокументы] = useState<string[]>([])
  const [фильтры, setФильтры] = useState<{ level: string; category: string; carrier: string; status: string; безНосителя: boolean; поиск: string }>(
    { level: '', category: '', carrier: '', status: '', безНосителя: false, поиск: '' },
  )
  useEffect(() => {
    if (!отбор) return
    setФильтры((ф) => ({
      ...ф,
      безНосителя: отбор.без_носителя ?? ф.безНосителя,
      level: отбор.уровень ?? ф.level,
      carrier: отбор.носитель ?? ф.carrier,
    }))
  }, [отбор])
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
    api.entities(project, 'material').then((r) => setДокументы(r.items.map((м) => м.code))).catch(() => setДокументы([]))
  }, [project])

  useEffect(перечитать, [перечитать])

  // Отбор и порядок считает экран: это его собственные строки, не величины модели.
  const видимые = отобрать(строки, фильтры, отбор?.коды)
  const упорядочены = упорядочить(видимые, сортировка.по, сортировка.вверх)
  const группы = сгруппировать(упорядочены, группировка, схема)

  // Шип 5 §4: группы — полосами над своими таблицами, выбор строк — общим
  // массовым приёмом (↑ ↓ Space Shift Enter A · N · L), действия — пиктограммой.
  const полосы = useПолосы(группы.map((г) => г.имя ?? '*'))
  const плотность = useContext(ПлотностьКонтекст)
  const инженер = плотность.режим === 'мероприятие'
  const автор = плотность.кто || 'инженер'
  const [ask, спросить, закрытьВопрос] = useConfirm()
  const [итогМассово, setИтогМассово] = useState<string | null>(null)
  const пачкой = (коды: string[], шаг: (код: string) => Promise<unknown>, что: string) => {
    setИтогМассово(null)
    Promise.allSettled(коды.map(шаг)).then((итоги) => {
      const отказы = итоги.filter((и) => и.status === 'rejected') as PromiseRejectedResult[]
      setИтогМассово(отказы.length === 0 ? `${что}: ${коды.length}`
        : `${что}: ${коды.length - отказы.length} · отказов ${отказы.length} — ${String(отказы[0].reason?.message ?? отказы[0].reason)}`)
      перечитать()
    })
  }
  const массово: МассовоеДействие[] = [
    {
      key: 'носитель', икон: 'связать', слово: 'распределить носитель', клавиша: 'N',
      run: (коды) => {
        // Природа носителя — по уровню отмеченных (истина уровней сцены 8).
        const уровни = [...new Set(строки.filter((т) => коды.includes(т.code)).map((т) => т.level))]
        const виды = [...new Set(уровни.flatMap((у) => НОСИТЕЛЬ[у]?.виды ?? ['component']))]
        Promise.all(виды.map((в) => api.entities(project ?? '', в).then((r) => r.items).catch(() => [])))
          .then((списки) => {
            const носители = списки.flat()
            if (носители.length === 0) { setИтогМассово(`носителей нужной природы нет: ${уровни.map((у) => НОСИТЕЛЬ[у]?.слова ?? 'узел состава').join(' · ')}`); return }
            спросить({
              question: `Носитель для отмеченных (${коды.length}): природа — ${уровни.map((у) => НОСИТЕЛЬ[у]?.слова ?? 'узел состава').join(' · ')}.`,
              ok: 'Распределить',
              choice: { label: 'носитель', options: носители.map((н) => [н.code, `${н.code} · ${String(н.doc.name ?? н.doc.title ?? н.code)}`]) },
              onOk: (носитель) => пачкой(коды, (к) => api.patchEntity(project ?? '', к, { carrier: носитель }, автор, 'массовое распределение носителя'), 'распределено'),
            })
          })
      },
    },
    {
      key: 'базировать', икон: 'принять', слово: 'базировать выбранные', клавиша: 'A',
      run: (коды) => {
        setИтогМассово(null)
        api.baseline(project ?? '', { name: 'SRR', kind: 'functional', gate: 'SRR', author: автор, items: коды })
          .then((с) => { setИтогМассово(`снимок «${с.name}» (точка ${с.gate}): объектов ${с.items.length}`); перечитать() })
          .catch((e) => setИтогМассово(String(e.message ?? e)))
      },
    },
    {
      key: 'линт', икон: 'снять', слово: 'снять линт-помету с причиной', клавиша: 'L',
      run: (коды) => спросить({
        question: `Снять пометы линта у отмеченных (${коды.length}) — «принять как есть»: помета остаётся в истории, маркер гаснет.`,
        ok: 'Снять пометы',
        input: { label: 'почему так и задумано', required: true },
        onOk: (причина) => {
          const когда = new Date().toISOString().slice(0, 10)
          const цели = строки.filter((т) => коды.includes(т.code) && активныеПометы(т).length > 0)
          пачкой(цели.map((т) => т.code), (к) => {
            const т = цели.find((х) => х.code === к)!
            const принятые = [...(т.lint_acknowledged ?? []), ...активныеПометы(т).map((п) => ({ rule: п.rule, by: автор, at: когда, note: причина }))]
            return api.patchEntity(project ?? '', к, { lint_acknowledged: принятые }, автор, 'пометы линта приняты как есть')
          }, 'пометы сняты')
        },
      }),
    },
  ]
  const м = useМассово(упорядочены.map((т) => т.code), {
    onOpen: (к) => setОткрыта((было) => (было === к ? null : к)),
    действия: массово,
  })
  /** Таблица реестра: одна на весь реестр либо своя под каждой полосой группы. */
  const таблица = (дети: RequirementRow[]) => (
    <div className="v2-reg__table" tabIndex={0} onKeyDown={м.клавиша} aria-label="реестр требований: ↑ ↓ строка, Space отметить, Enter карточка">
      <table className="v2-table v2-table--req">
        <thead>
          <tr>
            <th>
              <input type="checkbox" className="v2-mark" checked={м.всеВыбраны} onChange={м.всеРазом}
                aria-label="отметить все строки на экране"
                title={м.всеВыбраны ? 'снять отметки' : 'отметить все строки, что видны по отбору'} />
            </th>
            {КОЛОНКИ.map(([к, слово]) => (
              <th key={к}>
                <button type="button" className="v2-link" aria-label={`сортировать по «${слово}»`}
                  title={сортировка.по === к
                    ? (сортировка.вверх ? 'сейчас по возрастанию — нажмите для обратного' : 'сейчас по убыванию — нажмите для обратного')
                    : `сортировать по «${слово}»`}
                  onClick={() => setСортировка({ по: к, вверх: сортировка.по === к ? !сортировка.вверх : true })}>
                  {слово}{сортировка.по === к ? (сортировка.вверх ? ' ↑' : ' ↓') : ''}
                </button>
              </th>
            ))}
            <th className="v2-acts" aria-label="действия строки" />
          </tr>
        </thead>
        <tbody>
          {дети.map((т) => (
            <TableRow key={т.code} т={т} project={project ?? ''} onChanged={перечитать} схема={схема}
              открыта={открыта === т.code} курсор={упорядочены[м.курсор]?.code === т.code} инженер={инженер}
              отмечена={м.выбраны.has(т.code)} onОтметить={м.отметить}
              onToggle={() => setОткрыта(открыта === т.code ? null : т.code)} />
          ))}
        </tbody>
      </table>
    </div>
  )

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

      <ЦелиБезТребования project={project} onChanged={перечитать} />

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Требования · уровень проекта</span>
          <span className="v2-card__count">
            {упорядочены.length === строки.length ? строки.length : `${упорядочены.length} из ${строки.length}`}
          </span>
          <span className="v2-head__spacer" />
          <a className="v2-chip" href={api.sdocUrl(project ?? '')} target="_blank" rel="noreferrer"
            title="StrictDoc: нужды · сервисы · требования с показателем по грамматике Орбиты (служба профиля strictdoc)">.sdoc</a>
          <a className="v2-chip" href={api.reqifUrl(project ?? '')} target="_blank" rel="noreferrer"
            title="ReqIF штатным экспортом StrictDoc из того же .sdoc">ReqIF</a>
        </div>

        {/* Виды реестра — вкладками (шип 5 §1.1): единственное второе меню экрана. */}
        <Вкладки label="вид реестра требований" current={вид} onChange={setВид}
          items={ВИДЫ.map(([к, слово]) => ({
            key: к, word: слово,
            hint: к === 'таблица' ? 'строки реестра с сортировкой и карточкой'
              : к === 'иерархия' ? 'требования под своими источниками: откуда выведено'
                : к === 'документы' ? 'по документам-источникам: что из чего написано'
                  : 'матрица «требование × носитель»: кто что несёт',
          }))} />
        <div className="v2-form v2-form--row" data-why="следующий-клик">
          <ЧипОтбора имя={схема.имя('level')} значение={фильтры.level} значения={значения(строки, 'level')}
            словом={(з) => схема.метка('level', з) || з} onПравка={(з) => setФильтры({ ...фильтры, level: з })} />
          <ЧипОтбора имя={схема.имя('category')} значение={фильтры.category} значения={значения(строки, 'category')}
            словом={(з) => схема.метка('category', з) || з} onПравка={(з) => setФильтры({ ...фильтры, category: з })} />
          <ЧипОтбора имя={схема.имя('carrier')} значение={фильтры.carrier} значения={значения(строки, 'carrier')}
            словом={(з) => з} onПравка={(з) => setФильтры({ ...фильтры, carrier: з })} />
          <ЧипОтбора имя={схема.имя('status')} значение={фильтры.status} значения={значения(строки, 'status')}
            словом={(з) => схема.метка('status', з) || з} onПравка={(з) => setФильтры({ ...фильтры, status: з })} />
          <label className="v2-inline" title="показать только те, которым не назначен носитель">
            <input type="checkbox" checked={фильтры.безНосителя}
              onChange={(e) => setФильтры({ ...фильтры, безНосителя: e.target.checked })} />
            только без носителя
          </label>
          <label className="v2-field">
            <span className="v2-field__cap">поиск</span>
            <input value={фильтры.поиск} placeholder="код, заголовок или формулировка"
              aria-label="поиск по требованиям"
              onChange={(e) => setФильтры({ ...фильтры, поиск: e.target.value })} />
          </label>
          <label className="v2-inline">
            группировать
            <select value={группировка} aria-label="группировка реестра"
              title="группы строк: по носителю или по уровню требования"
              onChange={(e) => setГруппировка(e.target.value as 'нет' | 'carrier' | 'level')}>
              <option value="нет">не группировать</option>
              <option value="carrier">по носителю</option>
              <option value="level">по уровню</option>
            </select>
          </label>
        </div>

        {отбор?.коды && отбор.коды.length > 0 && (
          <div className="v2-empty__why">
            реестр открыт из задачи: показаны {отбор.коды.length} названных ею требования
          </div>
        )}

        {строки.length === 0 ? (
          <div className="v2-empty">
            Требований пока нет.
            <span className="v2-empty__why">
              Требование выводится из целей, нужд и ограничений — источник обязателен,
              а носитель определяет, кто за него отвечает.
            </span>
          </div>
        ) : упорядочены.length === 0 ? (
          <div className="v2-empty">
            По отбору требований нет.
            <span className="v2-empty__why">Снимите чипы отбора или поиск — строки вернутся.</span>
          </div>
        ) : вид === 'таблица' ? (
          <>
            {итогМассово && <div className="v2-note-line">{итогМассово}</div>}
            {группировка === 'нет'
              ? таблица(упорядочены)
              : (
                <>
                  <div className="v2-bands__all"><РаскрытьВсе все={полосы.все} число={полосы.число} onClick={полосы.всеРазом} /></div>
                  {группы.map(({ имя: имяГруппы, дети }) => (
                    <Полоса key={имяГруппы ?? '*'} open={полосы.открыта(имяГруппы ?? '*')} onToggle={() => полосы.переключить(имяГруппы ?? '*')}
                      subject={имяГруппы ?? 'все'}
                      counts={`требований ${дети.length}${дети.some((т) => !т.carrier) ? ` · без носителя ${дети.filter((т) => !т.carrier).length}` : ''}${дети.some((т) => активныеПометы(т).length > 0) ? ` · с пометой линта ${дети.filter((т) => активныеПометы(т).length > 0).length}` : ''}`}>
                      {таблица(дети)}
                    </Полоса>
                  ))}
                </>
              )}
            <ПанельМассово выбранные={м.выбранные} действия={массово}
              наборы={[
                { key: 'без-носителя', word: 'все без носителя', keys: упорядочены.filter((т) => !т.carrier).map((т) => т.code) },
                { key: 'линт', word: 'все с пометой линта', keys: упорядочены.filter((т) => активныеПометы(т).length > 0).map((т) => т.code) },
              ]}
              onНабор={(н) => м.выбрать(н.keys)} onСнять={м.снять} />
          </>
        ) : вид === 'матрица' ? (
          <МатрицаНосителей строки={упорядочены} />
        ) : (
          <Деревья строки={упорядочены} вид={вид === 'иерархия' ? 'source' : 'document'} документы={документы} />
        )}
      </div>

      {открыта && <Impact код={открыта} project={project} />}

      {/* Дерево по носителю — спутник таблицы: кто что несёт одним взглядом. */}
      {вид === 'таблица' && <Деревья строки={упорядочены} вид="carrier" />}

      <Подозрения project={project} строки={подозрения} onConfirmed={перечитать} />

      <Базирование project={project} снимки={снимки} помехи={помехи} onDone={перечитать} />

      <Форма project={project} onAdded={перечитать} схема={схема} />
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </>
  )
}

/** Значения колонки, которые реально есть в реестре: отбор не предлагает пустоты. */
function значения(строки: RequirementRow[], поле: 'level' | 'category' | 'carrier' | 'status'): string[] {
  const набор = new Set<string>()
  строки.forEach((т) => {
    const з = поле === 'carrier' ? (т.carrier ?? '') : String(т[поле] ?? '')
    if (з.trim()) набор.add(з)
  })
  return [...набор].sort((a, b) => a.localeCompare(b))
}

function отобрать(строки: RequirementRow[], ф: { level: string; category: string; carrier: string; status: string; безНосителя: boolean; поиск: string }, коды?: string[]): RequirementRow[] {
  const искомое = ф.поиск.trim().toLowerCase()
  return строки.filter((т) => {
    if (коды && коды.length > 0 && !коды.includes(т.code)) return false
    if (ф.level && т.level !== ф.level) return false
    if (ф.category && т.category !== ф.category) return false
    if (ф.carrier && т.carrier !== ф.carrier) return false
    if (ф.status && т.status !== ф.status) return false
    if (ф.безНосителя && т.carrier) return false
    if (!искомое) return true
    return [т.code, т.title, т.statement].some((с) => String(с ?? '').toLowerCase().includes(искомое))
  })
}

/** Порядок строк реестра — текстовое сравнение колонки; величин модели экран не считает. */
function упорядочить(строки: RequirementRow[], по: Колонка, вверх: boolean): RequirementRow[] {
  const ключ = (т: RequirementRow): string => {
    if (по === 'carrier') return т.carrier ?? ''
    if (по === 'measure') return т.measure ?? ''
    return String(т[по] ?? '')
  }
  return [...строки].sort((a, b) => (вверх ? 1 : -1) * ключ(a).localeCompare(ключ(b), 'ru'))
}

function сгруппировать(
  строки: RequirementRow[],
  как: 'нет' | 'carrier' | 'level',
  схема: ReturnType<typeof useВидТребования>,
): { имя: string | null; дети: RequirementRow[] }[] {
  if (как === 'нет') return [{ имя: null, дети: строки }]
  const карта = new Map<string, RequirementRow[]>()
  строки.forEach((т) => {
    const ключ = как === 'carrier'
      ? (т.carrier ?? 'без носителя')
      : (схема.метка('level', т.level) || т.level || 'без уровня')
    карта.set(ключ, [...(карта.get(ключ) ?? []), т])
  })
  return [...карта.entries()].sort((a, b) => a[0].localeCompare(b[0], 'ru')).map(([имя, дети]) => ({ имя, дети }))
}

/** Чип отбора: значение словами истины, «любой» снимает отбор. */
function ЧипОтбора({ имя, значение, значения: список, словом, onПравка }: {
  имя: string
  значение: string
  значения: string[]
  словом: (з: string) => string
  onПравка: (з: string) => void
}) {
  if (список.length === 0) return null
  return (
    <label className="v2-inline" title={`отбор по полю «${имя}»`}>
      {имя}
      <select value={значение} aria-label={`отбор: ${имя}`} onChange={(e) => onПравка(e.target.value)}>
        <option value="">любой</option>
        {список.map((з) => <option key={з} value={з}>{словом(з)}</option>)}
      </select>
    </label>
  )
}

/** Пометы линта, ещё не принятые как есть (`lint_acknowledged`): только они горят маркером. */
export function активныеПометы(т: RequirementRow): LintNote[] {
  const принятые = new Set((т.lint_acknowledged ?? []).map((а) => а.rule))
  return т.notes.filter((п) => !принятые.has(п.rule))
}

/**
 * Формулировка: у СИ и РП — полным текстом; у инженера — двумя строками с
 * раскрытием (шип 5 §4): его реестр — работа по строке, не чтение.
 */
export function Формулировка({ текст, свернуть }: { текст: string; свернуть: boolean }) {
  const [раскрыта, setРаскрыта] = useState(false)
  if (!свернуть || текст.length < 140) return <>{текст}</>
  return (
    <>
      <span className={раскрыта ? undefined : 'v2-clamp2'} title={раскрыта ? undefined : текст}>{текст}</span>
      <button type="button" className="v2-link" onClick={(e) => { e.stopPropagation(); setРаскрыта(!раскрыта) }}
        title={раскрыта ? 'свернуть формулировку до двух строк' : 'показать формулировку целиком'}>
        {раскрыта ? 'свернуть' : 'целиком'}
      </button>
    </>
  )
}

function TableRow({ т, открыта, курсор, инженер, onToggle, project, onChanged, схема, отмечена, onОтметить }: {
  т: RequirementRow; открыта: boolean; onToggle: () => void; project: string; onChanged: () => void
  схема: ReturnType<typeof useВидТребования>
  /** Строка под курсором клавиатуры. */
  курсор: boolean
  /** Плотность инженера: формулировка двумя строками с раскрытием. */
  инженер: boolean
  /** Отметка для массовых действий: работа идёт по отмеченным строкам; Shift — диапазон. */
  отмечена: boolean
  onОтметить: (код: string, диапазон: boolean) => void
}) {
  const показатель = т.measure ? кратко(т.measure) : '—'
  const пометы = активныеПометы(т)
  // Долг карточки — поля, которых истина требует к ступени, а их нет: имена истины.
  const долги = схема.вид ? долг(схема.вид, {
    level: т.level, category: т.category, priority: т.priority, ears_pattern: т.ears, carrier: т.carrier,
    measure: т.measure, verification_method: т.verification_method, acceptance_criteria: т.acceptance_criteria,
    title: т.title, statement: т.statement, source: т.sources,
  }) : []
  return (
    <>
      {/*
        Класс `v2-row` здесь стоять не может: это СЕТКА из трёх колонок для
        списков, и строка таблицы разваливалась на клетки по 16 px высотой в
        2705 (замерено в браузере на стенде; владелец: «в дизайне каша»).
        Подсветка открытой строки — своим классом, без раскладки.
      */}
      <tr className={[открыта ? 'v2-row--open' : '', курсор ? 'v2-row--cursor' : ''].filter(Boolean).join(' ') || undefined} onClick={onToggle}>
        <td className="v2-reg__mark" onClick={(e) => e.stopPropagation()}>
          <Отметка ключ={т.code} выбрана={отмечена} onToggle={onОтметить} />
        </td>
        <td>
          <span className="v2-nowrap">{т.code}</span>
          {т.after_baseline_changed && (
            <span className="v2-flag" title="версия выше зафиксированной снимком — изменено после утверждения">
              изменено
            </span>
          )}
        </td>
        <td>{т.title}</td>
        <td className="v2-cell--statement">
          <Формулировка текст={т.statement} свернуть={инженер} />
          {/*
            Помета линта НАЗЫВАЕТ себя (журнал ПМИ-7, З-13): «линт: 1» без
            текста не говорит ни правила, ни что исправить. Правило и фраза —
            в строке, причина — подсказкой; полный разбор — в карточке.
            Линт рекомендует и никогда не запирает (шип 5 §4): принятая как
            есть помета маркером не горит.
          */}
          {пометы.length > 0 && (
            <span className="v2-flag v2-flag--warn" title={пометы.map((n) => `${n.what}: ${n.why}`).join('\n')}>
              {пометы[0].rule} · {пометы[0].what}
              {пометы.length > 1 && <span className="v2-dim"> и ещё {пометы.length - 1}</span>}
            </span>
          )}
        </td>
        <td>{показатель}</td>
        <td>
          {т.carrier ?? <span className="v2-flag v2-flag--warn" title="без носителя — не требование">нет</span>}
          {т.carrier_kind && <span className="v2-dim"> · {т.carrier_kind}</span>}
        </td>
        <td>{схема.метка('status', т.status) || т.status}</td>
        <td className="v2-acts" onClick={(e) => e.stopPropagation()}>
          <ИконКнопка икон="карточка" слово={открыта ? 'свернуть карточку' : 'карточка'} aria-expanded={открыта} onClick={onToggle} />
        </td>
      </tr>
      {открыта && (
        <tr className="v2-card-row">
          <td colSpan={8}>
            <div className="v2-object" aria-label={`карточка ${т.code}`}>
              <div className="v2-object__head">
                <span className="v2-mono">{т.code}</span>
                <h3>{т.title}</h3>
                <span className="v2-object__state">
                  <Маркер health={долги.length > 0 ? 'debt' : 'ok'} title={долги.length > 0 ? 'есть долг: строка внизу карточки' : 'долгов нет'} />
                  {схема.метка('status', т.status) || т.status}
                </span>
                {т.ears && <span className="v2-chip" title="шаблон EARS: форма фразы, которую ждёт линт">{схема.метка('ears_pattern', т.ears) || т.ears}</span>}
                {пометы.length > 0 && (
                  <span className="v2-object__state" title={пометы.map((n) => `${n.rule}: ${n.what} — ${n.why}`).join('\n')}>
                    <Маркер health="debt" title="линт рекомендует, не запирает" />линт {пометы.length}
                  </span>
                )}
                <span className="v2-object__meta">версия {т.version}</span>
                <span className="v2-object__acts">
                  <ИконКнопка икон="свернуть-полосу" слово="свернуть карточку" onClick={onToggle} />
                </span>
              </div>
              <div className="v2-facets">
                <ПравкаТребования project={project} т={т} onSaved={onChanged} схема={схема} />
                <КарточкаТребования project={project} т={т} схема={схема} />
                <Группа title="Пометы линта">
                  {т.notes.length === 0
                    ? <div className="v2-dim">замечаний нет</div>
                    : т.notes.map((n) => <Помета key={n.rule + n.what} note={n} />)}
                  {(т.lint_acknowledged ?? []).length > 0 && (
                    <div className="v2-dim">приняты как есть: {(т.lint_acknowledged ?? []).map((а) => `${а.rule}${а.note ? ` — ${а.note}` : ''}`).join('; ')}</div>
                  )}
                </Группа>
                <Группа title="Что заденет правка">
                  <div className="v2-dim">
                    Граф связей — под таблицей: в колонке карточки он был бы нечитаем.
                  </div>
                </Группа>
              </div>
              {долги.length > 0 && (
                <div className="v2-object__debt">
                  {долги.map((д, i) => <span key={д.стадия}>{i > 0 ? ' · ' : ''}<b>{д.стадия}:</b> {д.поля.join(' · ')}</span>)}
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  )
}
