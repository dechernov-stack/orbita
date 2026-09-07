// Экран «Поле знаний» (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ §3, минимум по ДИЗАЙН-ПРИНЦИПЫ-V2).
//
// Поле знаний — не список фактов, а рабочая поверхность: ТЕМЫ (о чём
// накопились утверждения), ДИСПОЗИЦИИ (что с ними решено) и ИСТОЧНИКИ
// (откуда). Всё остальное — за фильтром, а не на экране одновременно.
//
// Диспозицию ставит ЧЕЛОВЕК и обязан объяснить: кнопка без причины
// отказывает на сервере, поэтому причина спрашивается здесь.
import { useEffect, useState } from 'react'
import { api, type FactRow } from './api'

/** Диспозиции по-русски: служебное имя инженеру ничего не говорит. */
const РЕШЕНИЕ: Record<string, string> = {
  free: 'свободен',
  noted: 'рассмотрен',
  assumed: 'принят допущением',
  adopted: 'принят',
  rejected: 'отклонён',
  contested: 'оспорен',
  superseded: 'вытеснен',
}

const МЕТКА: Record<string, string> = {
  И: 'наш документ',
  В: 'внешний источник',
  П: 'допущение',
}

type Фильтр = 'все' | 'свободные' | 'допущения' | 'спорные'

export function KnowledgeField({ project }: { project: string | null }) {
  const [факты, setФакты] = useState<FactRow[] | null>(null)
  const [темы, setТемы] = useState<{ id: string; label: string; facts: number }[]>([])
  const [покрытие, setПокрытие] = useState<{ total: number; from_facts: number; share_percent: number } | null>(null)
  const [тема, setТема] = useState<string | null>(null)
  const [фильтр, setФильтр] = useState<Фильтр>('все')
  const [отказ, setОтказ] = useState<string | null>(null)
  // Решение без причины не ставится (сервер откажет), поэтому причина
  // спрашивается СТРОКОЙ В ТАБЛИЦЕ: нативных диалогов в продукте нет.
  // Хуки — до любых возвратов: порядок хуков стережёт CI.
  const [решаем, setРешаем] = useState<{ факт: string; решение: string } | null>(null)
  const [причина, setПричина] = useState('')

  const перечитать = () => {
    if (!project) return
    api.facts(project).then((r) => setФакты(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.topics(project).then((r) => setТемы(r.items)).catch(() => setТемы([]))
    api.knowledgeCoverage(project).then(setПокрытие).catch(() => setПокрытие(null))
  }

  useEffect(перечитать, [project])

  if (!project) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>

  const все = факты ?? []
  const видно = все
    .filter((ф) => (тема ? ф.topic === тема : true))
    .filter((ф) => {
      if (фильтр === 'свободные') return ф.disposition === 'free'
      if (фильтр === 'допущения') return ф.mark === 'П' || ф.disposition === 'assumed'
      if (фильтр === 'спорные') return ф.disposition === 'contested'
      return true
    })

  const записать = () => {
    if (!решаем || !причина.trim()) return
    api.disposeFact(project, решаем.факт, решаем.решение, причина.trim(), 'инженер')
      .then(() => { setРешаем(null); setПричина(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Поле знаний
        <span className="v2-cnt">
          {факты === null ? 'читаю…' : `${все.length} фактов · ${темы.length} тем`}
          {покрытие && ` · из знаний ${покрытие.share_percent}% сущностей`}
        </span>
      </h3>

      <div className="v2-kf__bar">
        <span className="v2-dim">темы:</span>
        <button type="button" className={тема === null ? 'v2-chip v2-chip--on' : 'v2-chip'}
          title="все темы поля знаний" onClick={() => setТема(null)}>все</button>
        {темы.filter((т) => т.facts > 0).map((т) => (
          <button key={т.id} className={тема === т.id ? 'v2-chip v2-chip--on' : 'v2-chip'}
            type="button" title={`факты темы: ${т.facts}`} onClick={() => setТема(т.id)}>
            {т.label} <b>{т.facts}</b>
          </button>
        ))}
      </div>

      <div className="v2-kf__bar">
        <span className="v2-dim">показать:</span>
        {([
          ['все', 'все факты'],
          ['свободные', 'не рассмотрены'],
          ['допущения', 'допущения к точке'],
          ['спорные', 'противоречия'],
        ] as [Фильтр, string][]).map(([ключ, имя]) => (
          <button key={ключ} type="button"
            className={фильтр === ключ ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title={ключ === 'допущения'
              ? 'допущения [П] и принятые допущением — их подтверждают к ближайшей точке'
              : ключ === 'свободные' ? 'факты, по которым решения ещё нет' : имя}
            onClick={() => setФильтр(ключ)}>
            {имя}
          </button>
        ))}
      </div>

      {факты !== null && видно.length === 0 && (
        <div className="v2-empty">
          Фактов по этому отбору нет.
          <span className="v2-empty__why">
            Поле знаний наполняется разбором материала: «Загрузка с заданием» в разделе материалов.
          </span>
        </div>
      )}

      {видно.length > 0 && (
        <table className="v2-tab2">
          <thead>
            <tr>
              <th>Утверждение</th><th>Значение</th><th>Метка</th>
              <th>Якорь</th><th>Решение</th><th />
            </tr>
          </thead>
          <tbody>
            {видно.map((ф) => (
              <tr key={ф.id}>
                <td>{ф.subject ? `${ф.subject}: ` : ''}{ф.predicate}</td>
                <td>{ф.value}{ф.unit ? ` ${ф.unit}` : ''}</td>
                <td title={МЕТКА[ф.mark] ?? ф.mark}>{МЕТКА[ф.mark] ?? ф.mark}</td>
                <td className="v2-mono">{ф.anchor ?? '—'}</td>
                <td className={ф.disposition === 'adopted' ? 'v2-ok' : ''}>
                  {РЕШЕНИЕ[ф.disposition ?? 'free'] ?? ф.disposition}
                </td>
                <td>
                  {ф.disposition === 'free' && решаем?.факт !== ф.id && (
                    <>
                      <button type="button" className="v2-link"
                        title="принять факт: он станет основанием сущности"
                        onClick={() => { setРешаем({ факт: ф.id, решение: 'adopted' }); setПричина('') }}>
                        принять
                      </button>
                      {' · '}
                      <button type="button" className="v2-link"
                        title="рассмотрен и не взят — это тоже решение, факт не исчезает"
                        onClick={() => { setРешаем({ факт: ф.id, решение: 'noted' }); setПричина('') }}>
                        отложить
                      </button>
                    </>
                  )}
                  {решаем?.факт === ф.id && (
                    <span className="v2-kf__why">
                      <input value={причина} autoFocus
                        onChange={(e) => setПричина(e.target.value)}
                        onKeyDown={(e) => { if (e.key === 'Enter') записать() }}
                        placeholder={`почему «${РЕШЕНИЕ[решаем.решение]}»`} />
                      <button type="button" disabled={!причина.trim()}
                        title={причина.trim() ? 'записать решение' : 'решение без причины не ставится'}
                        onClick={записать}>Записать</button>
                      <button type="button" className="v2-link" title="не менять решение"
                        onClick={() => { setРешаем(null); setПричина('') }}>отмена</button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
