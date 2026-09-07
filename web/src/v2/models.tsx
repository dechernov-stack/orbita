// Раздел «Модели» — волна 4: записи моделей, свёртка величин, варианты.
//
// Модель здесь — не расчёт в коде, а ЗАПИСЬ о расчёте: чем считаем, на
// каких входах, когда считали и не устарел ли ответ. Система не выдаёт
// правдоподобное число за измеренное: прогон прокси-моделью помечен, а
// вход, изменившийся после прогона, гасит результат.
//
// Свёртка держит ДВА резерва и они не смешиваются: резерв класса стоит на
// каждой строке по её зрелости, системный запас — один на всю свёртку.
// Против рамки проекта (Р2: 12U…100 кг) свёртка печатает честный перебор,
// а не подгоняет число.
import { useCallback, useEffect, useState } from 'react'
import { api, type Budget, type ModelRow, type VariantRow } from './api'

const ТОЧКИ = ['MCR', 'SRR', 'SDR', 'PDR']

/** Величины свёртки: у каждой своя рамка и своя единица. */
const ВЕЛИЧИНЫ: { key: string; title: string }[] = [
  { key: 'mass', title: 'масса' },
  { key: 'power', title: 'мощность' },
  { key: 'data', title: 'поток данных' },
]

/**
 * Средство расчёта по-русски: служебное имя инженеру ничего не говорит,
 * а сторож текста интерфейса не пускает латинские значения перечислений.
 */
const СРЕДСТВО: Record<string, string> = {
  calc: 'свой расчёт',
  proxy: 'прокси',
  build: 'не построена',
}

/** Ступень доверия к модели: считает — ещё не значит проверена. */
const ПРОВЕРКА: Record<string, string> = {
  unverified: 'не проверена',
  verified: 'проверена',
  validated: 'аттестована',
}

export function Models({ project }: { project: string | null }) {
  const [модели, setМодели] = useState<ModelRow[] | null>(null)
  const [свёртка, setСвёртка] = useState<Budget | null>(null)
  const [величина, setВеличина] = useState('mass')
  const [точка, setТочка] = useState('MCR')
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)

  const перечитать = useCallback(() => {
    if (!project) return
    api.models(project).then((r) => setМодели(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.budget(project, величина, точка).then(setСвёртка).catch(() => setСвёртка(null))
  }, [project, величина, точка])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return <div className="v2-panel" data-why="следующий-клик">
      <div className="v2-empty">Проект не выбран.</div>
    </div>
  }

  const взять = () => {
    setЗанято(true); setОтказ(null)
    api.takeModels(project, 'стенд')
      .then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  const всего = модели?.length ?? 0
  const считаны = модели?.filter((м) => м.last_run).length ?? 0
  const устарели = модели?.filter((м) => (м.last_run?.stale_inputs.length ?? 0) > 0).length ?? 0

  return (
    <>
      {отказ && <div className="v2-locked">{отказ}</div>}

      <div className="v2-panel" data-why="работа">
        <h3>
          Записи моделей
          <span className="v2-cnt">
            {модели === null ? 'читаю…' : `${всего} · считаны ${считаны}`}
            {устарели > 0 && ` · вход изменился у ${устарели}`}
          </span>
        </h3>

        {модели !== null && всего === 0 ? (
          <div className="v2-empty">
            Записей моделей в проекте нет.
            <span className="v2-empty__why">
              Набор моделей берётся с полки: он говорит, ЧЕМ отвечать на вопросы фазы.
              Пока набора нет, свёртке нечего складывать.
            </span>
            <div className="v2-form__actions">
              <button type="button" disabled={занято} onClick={взять}
                title="взять набор моделей с полки в проект">
                {занято ? 'беру…' : 'Взять набор с полки'}
              </button>
            </div>
          </div>
        ) : (
          <table className="v2-table">
            <thead>
              <tr>
                <th>Код</th><th>Модель</th><th>На какой вопрос</th>
                <th>Чем</th><th>Проверка</th><th>Входы</th><th>Последний прогон</th>
              </tr>
            </thead>
            <tbody>
              {(модели ?? []).map((м) => {
                const устарел = (м.last_run?.stale_inputs.length ?? 0) > 0
                return (
                  <tr key={м.code}>
                    <td className="v2-mono">{м.code}</td>
                    <td>
                      {м.name}
                      {м.required_to && <span className="v2-dim"> · к {м.required_to}</span>}
                    </td>
                    <td>{м.question}</td>
                    <td title={м.tool === 'proxy'
                      ? 'прокси даёт правдоподобное число вместо расчёта — оно помечается на выходе'
                      : м.tool === 'build' ? 'модель ещё не построена' : 'считает своим расчётом'}>
                      {СРЕДСТВО[м.tool] ?? м.tool}
                    </td>
                    <td title="считает — ещё не значит проверена">
                      {ПРОВЕРКА[м.verification] ?? м.verification}
                    </td>
                    <td>
                      {м.inputs.length}
                      {м.gaps.length > 0 && (
                        <span className="v2-warn" title={`не хватает: ${м.gaps.join(', ')}`}>
                          {' '}· нет {м.gaps.length}
                        </span>
                      )}
                    </td>
                    <td>
                      {!м.last_run ? (
                        <span className="v2-dim">не считана</span>
                      ) : (
                        <>
                          {м.last_run.at.slice(0, 10)}
                          {м.last_run.proxy && (
                            <span className="v2-warn" title="считано прокси-моделью: число правдоподобно, но не измерено">
                              {' '}· прокси
                            </span>
                          )}
                          {устарел && (
                            <span className="v2-warn"
                              title={`после прогона изменились входы: ${м.last_run.stale_inputs.join(', ')}`}>
                              {' '}· вход изменился
                            </span>
                          )}
                        </>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      <Rollup свёртка={свёртка} величина={величина} точка={точка}
        onВеличина={setВеличина} onТочка={setТочка} />

      <Variants project={project} />
    </>
  )
}

/** Свёртка величины: два резерва порознь и рамка проекта вслух. */
function Rollup({ свёртка, величина, точка, onВеличина, onТочка }: {
  свёртка: Budget | null
  величина: string
  точка: string
  onВеличина: (в: string) => void
  onТочка: (т: string) => void
}) {
  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Свёртка к точке
        <span className="v2-cnt">
          {свёртка ? `${свёртка.lines.length} вкладов` : 'нет данных'}
        </span>
      </h3>

      <div className="v2-kf__bar">
        <span className="v2-dim">величина:</span>
        {ВЕЛИЧИНЫ.map((в) => (
          <button key={в.key} type="button"
            className={величина === в.key ? 'v2-chip v2-chip--on' : 'v2-chip'}
            onClick={() => onВеличина(в.key)}>{в.title}</button>
        ))}
        <span className="v2-dim">к точке:</span>
        {ТОЧКИ.map((т) => (
          <button key={т} type="button"
            className={точка === т ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title={`резерв класса берётся по зрелости на ${т}`}
            onClick={() => onТочка(т)}>{т}</button>
        ))}
      </div>

      {!свёртка || свёртка.lines.length === 0 ? (
        <div className="v2-empty">
          Складывать нечего.
          <span className="v2-empty__why">
            Свёртка берёт величины с узлов дерева состава. Пока у узлов нет ни значений,
            ни прогонов моделей, сумма была бы выдумкой.
          </span>
        </div>
      ) : (
        <>
          <table className="v2-table">
            <thead>
              <tr><th>Узел</th><th>Значение</th><th>Зрелость</th><th>Резерв класса</th><th>Откуда</th></tr>
            </thead>
            <tbody>
              {свёртка.lines.map((с) => (
                <tr key={с.component}>
                  <td>{с.component}</td>
                  <td>{с.value} {с.unit}</td>
                  <td>{с.maturity}</td>
                  <td>{с.class_reserve}%</td>
                  <td className="v2-dim">{с.origin}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="v2-frag">
            <p className="v2-question">Сколько получается и остаётся ли это в рамке</p>
            <ul className="v2-rollup">
              <li>
                сумма вкладов: <b>{свёртка.sum} {свёртка.unit}</b>
              </li>
              <li title="резерв класса стоит на КАЖДОЙ строке по её зрелости">
                с резервом класса: <b>{свёртка.with_class_reserve} {свёртка.unit}</b>
              </li>
              <li title="системный запас — один на всю свёртку, поверх резервов класса">
                с системным запасом {свёртка.system_margin_percent}%:{' '}
                <b>{свёртка.with_system_margin} {свёртка.unit}</b>
              </li>
            </ul>
            {свёртка.frame.length > 0 && (
              <ul className="v2-rollup">
                {свёртка.frame.map((р) => (
                  <li key={р.constraint} className={р.within ? 'v2-ok' : 'v2-warn'}
                    title={р.statement || `рамка ${р.constraint}`}>
                    <b>{р.words}</b>
                    {!р.within && ' — свёртка не подгоняется под рамку'}
                  </li>
                ))}
              </ul>
            )}
            {свёртка.note && (
              <div className={свёртка.frame.some((р) => !р.within) ? 'v2-locked' : 'v2-empty__why'}>
                {свёртка.note}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Сравнение вариантов: показатели против порогов, БЕЗ балла.
 *
 * Свёрнутый в одно число вариант выбирает себя сам, и обоснование
 * подменяется арифметикой. Здесь видно, чем именно вариант хуже: какой
 * показатель не прошёл порог и на сколько.
 */
export function Variants({ project }: { project: string }) {
  const [строки, setСтроки] = useState<VariantRow[] | null>(null)
  const [пояснение, setПояснение] = useState('')

  useEffect(() => {
    api.variants(project)
      .then((r) => { setСтроки(r.items); setПояснение(r.note) })
      .catch(() => setСтроки([]))
  }, [project])

  const все = строки ?? []
  const показатели = все[0]?.metrics ?? []

  return (
    <div className="v2-panel" data-why="следующий-клик">
      <h3>
        Варианты построения
        <span className="v2-cnt">
          {строки === null ? 'читаю…'
            : `${все.length} · на фронте Парето ${все.filter((в) => в.pareto).length}`}
        </span>
      </h3>

      {строки !== null && все.length === 0 ? (
        <div className="v2-empty">
          Вариантов построения нет.
          <span className="v2-empty__why">
            Сравнивать нечего, пока вариант один: альтернативы заводятся в сцене 7.
          </span>
        </div>
      ) : (
        <>
          <table className="v2-table">
            <thead>
              <tr>
                <th>Вариант</th>
                {показатели.map((п) => (
                  <th key={п.key} title={`порог: ${п.threshold}, хуже если ${п.worse_if}`}>
                    {п.title}
                  </th>
                ))}
                <th>Состояние</th>
              </tr>
            </thead>
            <tbody>
              {все.map((в) => (
                <tr key={в.code}>
                  <td>
                    <span className="v2-mono">{в.code}</span> {в.name}
                  </td>
                  {в.metrics.map((м) => (
                    <td key={м.key} className={м.passed ? '' : 'v2-warn'}
                      title={м.passed ? `порог ${м.threshold} выдержан` : `порог ${м.threshold} не выдержан`}>
                      {м.value} {м.unit}
                    </td>
                  ))}
                  <td>
                    {в.rejected_by
                      ? <span className="v2-warn" title="вариант отклонён — причина остаётся с ним">
                          отклонён: {в.rejected_by}
                        </span>
                      : в.pareto
                        ? <span className="v2-ok" title="ни один другой вариант не лучше по всем показателям сразу">
                            на фронте Парето
                          </span>
                        : <span className="v2-dim" title="есть вариант, который не хуже ни по одному показателю и лучше хотя бы по одному">
                            вытеснен
                          </span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="v2-empty__why">
            {пояснение || 'Балла у варианта нет намеренно: свёрнутый в одно число вариант ' +
              'выбирает себя сам, а решение остаётся за человеком — по показателям и порогам.'}
          </div>
        </>
      )}
    </div>
  )
}
