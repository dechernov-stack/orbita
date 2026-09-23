// Экран «Постановка» (шип 2, экран 11) и матрица покрытия нужд
// (МОДЕЛЬ-ДАННЫХ §5: всё вычисляется).
//
// Стороны названы таблицей: роль, сколько нужд, влияние словами и сила 1–5
// КЛИКОМ. Рядом сетка «влияние × сила»: сторона — чип с инициалами в своей
// клетке; у кого силы нет, чип стоит серым пунктиром в клетке, предложенной
// по роли (истина `power_map`, журнал ПМИ-7 З-02) — согласие не требует
// действий, перенос — клик по чипу и клик по клетке. Пустой сетки не бывает.
// Карточка стороны раскрывается вниз под её строкой: кто · влияние · нужды ·
// основания · документы.
//
// Клетка матрицы покрытия не бывает просто пустой: если нужда не покрыта,
// строка говорит, чего именно не хватает — цели, сервиса или носителя.
import { Fragment as Фрагмент, useCallback, useEffect, useState } from 'react'
import './formulation.css'
import { api, type CoverageMatrix, type CoverageNeed, type EntityRow, type FactRow, type KindSpec } from './api'
import { инициалы } from './people'
import { интересы } from './interests'

/** Влияние: колонки сетки слева направо — от «информируется» к «решает». */
const ВЛИЯНИЕ: [string, string][] = [['informed', 'информируется'], ['influences', 'влияет'], ['decides', 'решает']]
const СИЛА = [5, 4, 3, 2, 1]

/** Сила стороны: оценка человека, а без неё — предложение по роли (З-02). */
function сила(с: EntityRow): number | null {
  const своя = Number(с.doc.power)
  if (своя >= 1 && своя <= 5) return своя
  const предложено = Number((с as EntityRow & { power_proposed?: number }).power_proposed)
  return предложено >= 1 && предложено <= 5 ? предложено : null
}
function предложена(с: EntityRow): boolean {
  return !(Number(с.doc.power) >= 1) && сила(с) !== null
}
function имя(с: EntityRow): string {
  return String(с.doc.name ?? с.code)
}

/**
 * Стороны и сетка влияния — одно рабочее место (экран 11). Таблица слева,
 * сетка справа; правка идёт в оба: балл в таблице и перенос в сетке пишут
 * одно и то же поле.
 */
function Стороны({ project, стороны, нужды, факты, вид, onChanged }: {
  project: string
  стороны: EntityRow[]
  нужды: EntityRow[]
  факты: FactRow[]
  вид: KindSpec | null
  onChanged: () => void
}) {
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занята, setЗанята] = useState<string | null>(null)
  /** Сторона, выбранная для переноса: следующий клик по клетке ставит ей силу и влияние. */
  const [выбрана, setВыбрана] = useState<string | null>(null)
  /** Раскрытая карточка стороны. */
  const [раскрыта, setРаскрыта] = useState<string | null>(null)

  const поправить = (с: EntityRow, поля: Record<string, unknown>, зачем: string) => {
    setЗанята(с.code); setОтказ(null)
    api.patchEntity(project, с.code, поля, 'инженер', зачем)
      .then(() => onChanged())
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанята(null))
  }

  /** Слово перечисления — из истины вида; кода поля на экране не бывает. */
  const словом = (поле: string, значение: unknown): string => {
    const код = String(значение ?? '')
    return вид?.enum_labels?.[поле]?.[код] ?? код
  }
  const нуждыСтороны = (с: EntityRow) => нужды.filter((н) => {
    const носитель = н.doc.stakeholder
    const код = typeof носитель === 'string' ? носитель
      : носитель && typeof носитель === 'object' && 'code' in (носитель as object)
        ? String((носитель as { code?: string }).code ?? '') : ''
    return код === с.code || код === с.id || (н.owned_by ?? []).includes(с.id)
  })

  const перенести = (влияние: string, балл: number) => {
    const с = стороны.find((x) => x.code === выбрана)
    setВыбрана(null)
    if (с) поправить(с, { power: String(балл), influence: влияние }, 'перенос стороны на сетке влияния')
  }

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Стороны и их влияние</span>
        <span className="v2-card__count">
          {стороны.length}
          {стороны.some(предложена) ? ` · сила предложена ${стороны.filter(предложена).length}` : ''}
        </span>
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-stakes">
        <table className="v2-table">
          <thead>
            <tr><th>Сторона</th><th>Роль</th><th>Нужд</th><th>Влияние</th><th>Сила</th></tr>
          </thead>
          <tbody>
            {стороны.length === 0 && (
              <tr><td colSpan={5} className="v2-empty">
                Сторон пока нет.
                <span className="v2-empty__why">Они заводятся на сцене 3 — руками или чтением записки.</span>
              </td></tr>
            )}
            {стороны.map((с) => {
              const свои = нуждыСтороны(с)
              const балл = сила(с)
              return (
                <Фрагмент key={с.id}>
                  <tr className={раскрыта === с.code ? 'v2-row--open' : undefined}>
                    <td>
                      <button type="button" className="v2-link" aria-expanded={раскрыта === с.code}
                        title="карточка стороны: кто, влияние, нужды, основания, документы"
                        onClick={() => setРаскрыта(раскрыта === с.code ? null : с.code)}>
                        {имя(с)}
                      </button>
                    </td>
                    <td className="v2-dim">{словом('role', с.doc.role) || '—'}</td>
                    <td>{свои.length === 0 ? <span className="v2-warn">нет</span> : свои.length}</td>
                    <td title="влияние считает система по роли стороны">
                      {словом('influence', с.doc.influence) || <span className="v2-dim">не задано</span>}
                    </td>
                    <td>
                      <span className="v2-power" role="group" aria-label={`сила стороны ${с.code}`}>
                        {[1, 2, 3, 4, 5].map((н) => (
                          <button key={н} type="button"
                            className={балл === н && предложена(с) ? 'v2-power--proposed' : undefined}
                            aria-pressed={балл === н}
                            disabled={занята === с.code}
                            title={занята === с.code
                              ? 'запись идёт — балл поставится, как ответит сервер'
                              : предложена(с) && балл === н
                                ? `предложено по роли: ${н} из 5 — нажмите, чтобы подтвердить`
                                : `сила ${н} из 5`}
                            onClick={() => поправить(с, { power: String(н) }, 'сила стороны')}>
                            {н}
                          </button>
                        ))}
                      </span>
                    </td>
                  </tr>
                  {раскрыта === с.code && (
                    <tr className="v2-card-row">
                      <td colSpan={5}>
                        <КарточкаСтороны с={с} нужды={свои} факты={факты} словом={словом} />
                      </td>
                    </tr>
                  )}
                </Фрагмент>
              )
            })}
          </tbody>
        </table>

        <div>
          <div className="v2-h4">Влияние × сила</div>
          <table className="v2-grid">
            <thead>
              <tr><th className="v2-grid__n">сила</th>{ВЛИЯНИЕ.map(([к, слово]) => <th key={к}>{слово}</th>)}</tr>
            </thead>
            <tbody>
              {СИЛА.map((балл) => (
                <tr key={балл}>
                  <td className="v2-grid__n">{балл}</td>
                  {ВЛИЯНИЕ.map(([к]) => (
                    <td key={к} className={выбрана ? 'v2-grid__aim' : undefined}
                      role={выбрана ? 'button' : undefined}
                      aria-label={выбрана ? `перенести ${выбрана}: сила ${балл}, ${к}` : undefined}
                      onClick={() => выбрана && перенести(к, балл)}>
                      {стороны.filter((с) => String(с.doc.influence ?? '') === к && сила(с) === балл).map((с) => (
                        <button key={с.id} type="button"
                          className={[
                            'v2-stake-chip',
                            предложена(с) ? 'v2-stake-chip--proposed' : '',
                            с.doc.attitude === 'supports' ? 'v2-stake-chip--supports' : '',
                            с.doc.attitude === 'resists' ? 'v2-stake-chip--resists' : '',
                          ].filter(Boolean).join(' ')}
                          aria-pressed={выбрана === с.code}
                          title={`${имя(с)} · ${словом('role', с.doc.role)}${с.doc.attitude ? ` · ${словом('attitude', с.doc.attitude)}` : ''}`
                            + (предложена(с) ? ' · сила предложена по роли' : '')
                            + ' — нажмите, чтобы перенести'}
                          onClick={(e) => { e.stopPropagation(); setВыбрана(выбрана === с.code ? null : с.code) }}>
                          {инициалы(имя(с))}{предложена(с) ? ' ?' : ''}
                        </button>
                      ))}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <div className="v2-empty__why">
            {выбрана
              ? `выбрана ${выбрана}: кликните по клетке — сторона переедет туда, сила и влияние запишутся`
              : 'сила предложена по роли — подтвердите баллом в таблице или перенесите чип кликом'}
          </div>
          {стороны.some((с) => сила(с) === null) && (
            <div className="v2-empty__why">
              вне сетки: {стороны.filter((с) => сила(с) === null).map(имя).join(', ')} — у роли карты силы нет,
              поставьте балл в таблице
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Карточка стороны: кто она, чем влияет, чего хочет и на чём это держится.
 * Основания — факты поля знаний об этой стороне (их субъект — её имя),
 * документы — материалы этих фактов. Нет фактов — так и сказано.
 */
function КарточкаСтороны({ с, нужды, факты, словом }: {
  с: EntityRow
  нужды: EntityRow[]
  факты: FactRow[]
  словом: (поле: string, значение: unknown) => string
}) {
  const название = имя(с)
  const свои = факты.filter((ф) => ф.subject.trim().toLowerCase() === название.trim().toLowerCase())
  const документы = Array.from(new Set(свои.map((ф) => ф.material).filter(Boolean)))
  return (
    <div className="v2-facets">
      <div className="v2-facet">
        <div className="v2-facet__title">Кто</div>
        <div className="v2-facet__body">
          <div><span className="v2-mono">{с.code}</span> {название}</div>
          <div className="v2-dim">{словом('role', с.doc.role) || 'роль не названа'}</div>
          {интересы(с.doc.interest).length > 0
            ? <div>интересы: {интересы(с.doc.interest).map((и) => и.statement).join(' · ')}</div>
            : <div className="v2-dim">интересы не записаны</div>}
          {с.doc.attitude ? <div>отношение: {словом('attitude', с.doc.attitude)}</div> : null}
        </div>
      </div>
      <div className="v2-facet">
        <div className="v2-facet__title">Влияние</div>
        <div className="v2-facet__body">
          <div>{словом('influence', с.doc.influence) || 'не задано'}</div>
          <div className="v2-dim">считает система по роли; правится в таблице</div>
          <div>сила: {сила(с) ?? '—'}{предложена(с) ? ' (предложена по роли)' : ''}</div>
        </div>
      </div>
      <div className="v2-facet">
        <div className="v2-facet__title">Нужды</div>
        <div className="v2-facet__body">
          {нужды.length === 0
            ? <div className="v2-warn">нужд нет — пока так, сцена 3 не закроется</div>
            : <ul className="v2-why">{нужды.map((н) => <li key={н.id}>{String(н.doc.statement ?? н.code)}</li>)}</ul>}
        </div>
      </div>
      <div className="v2-facet">
        <div className="v2-facet__title">Основания и документы</div>
        <div className="v2-facet__body">
          {свои.length === 0
            ? <div className="v2-dim">фактов об этой стороне в поле знаний нет</div>
            : (
              <ul className="v2-why">
                {свои.slice(0, 6).map((ф) => (
                  <li key={ф.id} title={ф.anchor ? `якорь ${ф.anchor}` : 'якоря нет'}>
                    {ф.predicate}: {ф.value}{ф.unit ? ` ${ф.unit}` : ''}
                    <span className="v2-dim"> · {ф.mark}{ф.anchor ? ` · ${ф.anchor}` : ''}</span>
                  </li>
                ))}
              </ul>
            )}
          <div className="v2-dim">
            документы: {документы.length === 0 ? 'нет' : документы.join(', ')}
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Куда идти, чтобы разрыв закрылся. Строка матрицы называет не только «чего
 * нет», но и место работы: носитель — сцена 3, цель — 4, сервис — 6, а разом
 * по всем нуждам — «Раздача нужд» в поле знаний (владелец 18.09: «нельзя
 * ничего отредактировать»).
 */
function кудаИдти(н: CoverageNeed): string {
  if (!н.owner) return 'закрывается на сцене 3: назначьте носителя нужде'
  if (н.goals.length === 0 && н.services.length === 0) {
    return 'закрывается на сценах 4 и 6 — или разом: «Поле знаний» → «Постановка из поля» → «Раздать нужды по целям и сервисам»'
  }
  if (н.goals.length === 0) return 'закрывается на сцене 4: свяжите нужду с целью — или раздачей нужд в поле знаний'
  return 'закрывается на сцене 6: свяжите нужду с сервисом — или раздачей нужд в поле знаний'
}

export function Coverage({ project }: { project: string | null }) {
  const [стороны, setСтороны] = useState<EntityRow[]>([])
  const [нужды, setНужды] = useState<EntityRow[]>([])
  const [факты, setФакты] = useState<FactRow[]>([])
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [матрица, setМатрица] = useState<CoverageMatrix | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    if (!project) return
    api.coverage(project).then(setМатрица).catch((e) => setОтказ(String(e.message ?? e)))
    api.entities(project, 'stakeholder').then((r) => setСтороны(r.items)).catch(() => undefined)
    api.entities(project, 'need').then((r) => setНужды(r.items)).catch(() => setНужды([]))
    // Основания карточки стороны — факты поля знаний; их нет на прежних проектах.
    api.facts(project).then((r) => setФакты(r.items)).catch(() => setФакты([]))
  }, [project])

  useEffect(перечитать, [перечитать])
  // Слова перечислений вида — из истины схем: кода роли на экране не бывает.
  useEffect(() => { api.kind('stakeholder').then(setВид).catch(() => setВид(null)) }, [])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">
          Проект не выбран.
          <span className="v2-empty__why">Матрица считается по связям проекта.</span>
        </div>
      </div>
    )
  }
  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!матрица) return <div className="v2-card"><div className="v2-empty">Считаю покрытие…</div></div>

  return (
    <>
      <Стороны project={project} стороны={стороны} нужды={нужды} факты={факты} вид={вид} onChanged={перечитать} />
      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Покрытие нужд</span>
          <span className="v2-card__count">{матрица.covered} из {матрица.total}</span>
        </div>
        <p className="v2-empty__why">{матрица.summary}</p>
        {матрица.needs.length > 0 && (
          <table className="v2-table">
            <thead>
              <tr><th>Нужда</th><th>Чья</th><th>Цели</th><th>Сервисы</th><th>Состояние</th></tr>
            </thead>
            <tbody>
              {матрица.needs.map((н) => (
                <tr key={н.code}>
                  <td>
                    <span className="v2-mono">{н.code}</span> {н.statement}
                  </td>
                  <td>{н.owner ?? <span className="v2-warn">ничья</span>}</td>
                  <td>{н.goals.length === 0 ? '—' : н.goals.join('; ')}</td>
                  <td>
                    {н.services.length === 0
                      ? (н.expected === 'service' ? '—' : <span className="v2-dim" title="истина: сервиса ждёт не всякая нужда">не ждёт</span>)
                      : н.services.join('; ')}
                  </td>
                  <td>
                    {н.covered
                      ? <span className="v2-ok">покрыта</span>
                      : <span className="v2-warn">{н.gap}</span>}
                    {н.note && <div className="v2-dim">{н.note}</div>}
                    {!н.covered && (
                      <div className="v2-dim" data-why="следующий-клик">{кудаИдти(н)}</div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {матрица.stakeholders_without_needs.length > 0 && (
        <div className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">Стороны без нужд</span>
            <span className="v2-card__count">{матрица.stakeholders_without_needs.length}</span>
          </div>
          <p className="v2-empty__why">
            Эти стороны названы, но чего они хотят — не записано. Пока так, сцена 3 не закроется.
          </p>
          <ul className="v2-why">
            {матрица.stakeholders_without_needs.map((с) => <li key={с}>{с}</li>)}
          </ul>
        </div>
      )}
    </>
  )
}
