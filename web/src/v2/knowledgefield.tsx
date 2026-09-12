// Экран «Поле знаний» (ЗАДАНИЕ-ПОЛЕ-ЗНАНИЙ §3; замечания прохода 08.09).
//
// Поле знаний — не список фактов, а рабочая поверхность: ТЕМЫ (о чём
// накопились утверждения), ДИСПОЗИЦИИ (что с ними решено) и ИСТОЧНИКИ
// (откуда). И оно же — дом для ВХОДА: источник загружается здесь, разбор
// кладёт факты и темы на глазах, план действий ложится на акцепт тут же.
//
// Три правила прохода 08.09:
//   1. Обоснование — только там, где оно несёт смысл: отклонение, спор,
//      смена уже принятого. Согласие и «рассмотрен» — одним кликом.
//      Обоснование не должно быть налогом на согласие.
//   2. Загрузка во вкладке: текст, файл или ссылка + задание → разбор.
//   3. Ручные темы и факты — полноправные; ручное видно отдельно.
import { useCallback, useEffect, useState } from 'react'
import { api, type TorAssessment, type FactRow } from './api'

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

const ВИДЫ_ФАКТА: [string, string][] = [
  ['framing', 'рамка'],
  ['quantity', 'величина'],
  ['capability', 'способность'],
  ['obligation', 'обязательство'],
  ['event', 'событие'],
  ['assessment', 'оценка'],
  ['relation', 'связь'],
  ['assumption', 'допущение'],
]

type Фильтр = 'все' | 'свободные' | 'допущения' | 'спорные' | 'ручные'

/**
 * Нужен ли повод к решению. Правило то же, что на сервере: сервер
 * откажет и без нас, но спрашивать текст там, где он не нужен, — налог.
 */
function нуженПовод(было: string, стало: string): boolean {
  if (стало === 'rejected') return true
  if (было === 'contested') return true
  return было !== 'free' && было !== стало
}

type Тема = { id: string; label: string; facts: number; resolved_to?: string | null }
type Покрытие = { total: number; from_facts: number; from_manual_facts: number; manual: number; share_percent: number }
type Действие = { index: number; target_kind: string; scene: string; title: string; preview: string; facts: string[] }

export function KnowledgeField({ project }: { project: string | null }) {
  const [факты, setФакты] = useState<FactRow[] | null>(null)
  const [темы, setТемы] = useState<Тема[]>([])
  const [покрытие, setПокрытие] = useState<Покрытие | null>(null)
  const [тема, setТема] = useState<string | null>(null)
  const [фильтр, setФильтр] = useState<Фильтр>('все')
  const [отказ, setОтказ] = useState<string | null>(null)
  // Хуки — до любых возвратов: порядок хуков стережёт CI.
  const [решаем, setРешаем] = useState<{ факт: string; решение: string } | null>(null)
  const [причина, setПричина] = useState('')
  const [вход, setВход] = useState(false)
  const [рукой, setРукой] = useState(false)
  const [план, setПлан] = useState<{ task: string; note: string; actions: Действие[]; assessment?: TorAssessment } | null>(null)
  const [выбраны, setВыбраны] = useState<number[]>([])
  const [занято, setЗанято] = useState(false)
  // Допущение ставится с владельцем, точкой и способом проверки — иначе к
  // точке его никто не подтвердит (истина схем: assumption при assumed).
  const [допущение, setДопущение] = useState<{ факт: string; owner: string; confirm_by: string; validation: string; impact: string } | null>(null)
  const [адресТемы, setАдресТемы] = useState('')

  const перечитать = useCallback(() => {
    if (!project) return
    api.facts(project).then((r) => setФакты(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.topics(project).then((r) => setТемы(r.items)).catch(() => setТемы([]))
    api.knowledgeCoverage(project).then(setПокрытие).catch(() => setПокрытие(null))
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  }
  if (отказ) {
    return (
      <div className="v2-panel" data-why="почему-нельзя">
        <div className="v2-locked">{отказ}</div>
        <button type="button" className="v2-link" onClick={() => { setОтказ(null); перечитать() }}>ещё раз</button>
      </div>
    )
  }

  const все = факты ?? []
  const видно = все
    .filter((ф) => (тема ? ф.topic === тема : true))
    .filter((ф) => {
      if (фильтр === 'свободные') return (ф.disposition ?? 'free') === 'free'
      if (фильтр === 'допущения') return ф.mark === 'П' || ф.disposition === 'assumed'
      if (фильтр === 'спорные') return ф.disposition === 'contested'
      if (фильтр === 'ручные') return ф.manual === true
      return true
    })
  const ручных = все.filter((ф) => ф.manual).length

  const решить = (ф: FactRow, решение: string) => {
    const было = ф.disposition ?? 'free'
    if (нуженПовод(было, решение)) {
      // Повод спрашивается СТРОКОЙ В ТАБЛИЦЕ, нормальным многострочным
      // полем — и только здесь. Нативных диалогов в продукте нет.
      setРешаем({ факт: ф.id, решение }); setПричина('')
      return
    }
    api.disposeFact(project, ф.id, решение, '', 'инженер')
      .then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))
  }

  const записать = () => {
    if (!решаем || !причина.trim()) return
    api.disposeFact(project, решаем.факт, решаем.решение, причина.trim(), 'инженер')
      .then(() => { setРешаем(null); setПричина(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const записатьДопущение = () => {
    if (!допущение || !допущение.owner.trim() || !допущение.validation.trim()) return
    api.disposeFact(project, допущение.факт, 'assumed', 'принято допущением до подтверждения', 'инженер', {
      owner: допущение.owner.trim(), confirm_by: допущение.confirm_by,
      validation: допущение.validation.trim(), impact_if_wrong: допущение.impact.trim(),
    })
      .then(() => { setДопущение(null); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const разрешитьТему = () => {
    if (!тема || !адресТемы.trim()) return
    api.resolveTopic(project, тема, адресТемы.trim(), 'инженер')
      .then(() => { setАдресТемы(''); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const принятьПлан = () => {
    if (!план || выбраны.length === 0) return
    setЗанято(true)
    api.acceptPlan(project, план.task, выбраны, 'инженер')
      .then(() => { setПлан(null); setВыбраны([]); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Поле знаний
        <span className="v2-cnt">
          {факты === null ? 'читаю…' : `${все.length} фактов · ${темы.length} тем`}
          {ручных > 0 && ` · руками ${ручных}`}
          {покрытие && ` · из источников ${покрытие.share_percent}% сущностей`}
          {покрытие && покрытие.from_manual_facts > 0 && ` · из ручных ${покрытие.from_manual_facts}`}
        </span>
        <span className="v2-head__spacer" />
        <button type="button" className={вход ? 'v2-chip v2-chip--on' : 'v2-chip'}
          title="текст, файл или ссылка + задание → разбор → факты и темы ложатся сюда"
          onClick={() => { setВход(!вход); setРукой(false) }}>
          Загрузить источник
        </button>
        <button type="button" className={рукой ? 'v2-chip v2-chip--on' : 'v2-chip'}
          title="завести тему или факт руками: метка [И], источник — инженер и дата"
          onClick={() => { setРукой(!рукой); setВход(false) }}>
          Руками
        </button>
        <a className="v2-chip" href={api.knowledgeZipUrl(project ?? '')} target="_blank" rel="noreferrer"
          title="пакет знаний внешнему контуру: MD-файлы с отпечатком в шапке — факты принятые · допущенные · замеченные с якорями">
          Выгрузка знаний
        </a>
      </h3>

      {вход && (
        <Source project={project}
          onParsed={(итог) => {
            перечитать()
            if (итог.task) {
              api.taskPlan(project, итог.task)
                .then((п) => { setПлан(п); setВыбраны(п.actions.map((д) => д.index)) })
                .catch(() => setПлан(null))
            }
          }}
          onError={setОтказ} />
      )}

      {рукой && <Manual project={project} темы={темы} onDone={перечитать} onError={setОтказ} />}

      {план && (
        <div className="v2-kf__src" data-why="следующий-клик">
          <div className="v2-empty__why">
            План из разбора: {план.actions.length} действий. {план.note}
            {' '}Снятое действие остаётся рассмотренным — факт не исчезает.
          </div>
          {план.assessment && (
            <div className="v2-scroll">
              {(план.assessment.gaps?.length ?? 0) > 0 && (
                <div className="v2-empty__why" data-why="почему-нельзя">
                  Дыры ТЗ против нужд — {план.assessment.gaps!.length}:
                  <ul className="v2-list">
                    {план.assessment.gaps!.map((д) => <li key={д}>{д}</li>)}
                  </ul>
                </div>
              )}
              {(план.assessment.needs?.length ?? 0) > 0 && (
                <table className="v2-tab2">
                  <thead><tr><th>Нужда</th><th>Вердикт</th><th>Чего в ТЗ нет</th><th>Требования ТЗ</th></tr></thead>
                  <tbody>
                    {план.assessment.needs!.map((н) => (
                      <tr key={н.need}>
                        <td className="v2-mono">{н.need}</td>
                        <td className={н.verdict === 'uncovered' ? 'v2-bad' : н.verdict === 'partial' ? 'v2-warn' : 'v2-ok'}>
                          {н.verdict === 'covered' ? 'покрыта' : н.verdict === 'partial' ? 'частично' : 'не покрыта'}
                        </td>
                        <td>{н.gap || '—'}</td>
                        <td className="v2-mono">{н.requirements.join(', ') || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <table className="v2-tab2">
                <thead><tr><th>Требование ТЗ</th><th>Покрывает нужды</th><th>Вердикт</th><th>Почему</th></tr></thead>
                <tbody>
                  {план.assessment.lines.map((л) => (
                    <tr key={л.fact}>
                      <td><span className="v2-mono">{л.fact}</span> {л.requirement}</td>
                      <td className="v2-mono">{л.needs.join(', ') || '—'}</td>
                      <td className={л.verdict === 'none' ? 'v2-bad' : л.verdict === 'partial' ? 'v2-warn' : 'v2-ok'}>
                        {л.verdict === 'covers' ? 'покрывает' : л.verdict === 'partial' ? 'частично' : 'без нужды'}
                      </td>
                      <td>{л.note || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="v2-note-line">
                непокрытых нужд: {план.assessment.uncovered_needs.length}
                {план.assessment.uncovered_needs.length > 0 && ` (${план.assessment.uncovered_needs.join(', ')}) — RFA заказчику в плане`}
                {' · '}требований без нужды: {план.assessment.orphan_requirements.length}
              </div>
            </div>
          )}
          <table className="v2-tab2">
            <thead><tr><th /><th>Действие</th><th>Что появится</th><th>Сцена</th></tr></thead>
            <tbody>
              {план.actions.map((д) => (
                <tr key={д.index}>
                  <td>
                    <input type="checkbox" checked={выбраны.includes(д.index)}
                      onChange={(e) => setВыбраны(e.target.checked
                        ? [...выбраны, д.index] : выбраны.filter((i) => i !== д.index))} />
                  </td>
                  <td>{д.title}</td>
                  <td>{д.preview}</td>
                  <td className="v2-mono">{д.scene}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занято || выбраны.length === 0}
              title="выполнить выбранные действия: сущности получат нить к своим фактам"
              onClick={принятьПлан}>
              {занято ? 'Принимаю…' : `Принять ${выбраны.length} из ${план.actions.length}`}
            </button>
            <button type="button" className="v2-link" onClick={() => setПлан(null)}>позже</button>
          </div>
        </div>
      )}

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

      {тема && (() => {
        const т = темы.find((x) => x.id === тема)
        if (!т) return null
        return (
          <div className="v2-kf__bar" data-why="работа">
            <span className="v2-dim">тема «{т.label}»:</span>
            {т.resolved_to
              ? <span>разрешена в <span className="v2-mono">{т.resolved_to}</span></span>
              : (
                <>
                  <input value={адресТемы} placeholder="код сущности проекта (узел, сторона, требование…)"
                    onChange={(e) => setАдресТемы(e.target.value)} />
                  <button type="button" className="v2-link" disabled={!адресТемы.trim()}
                    title="к точке принятые факты темы обязаны найти адрес — сущность проекта"
                    onClick={разрешитьТему}>разрешить в сущность</button>
                </>
              )}
          </div>
        )
      })()}

      <div className="v2-kf__bar">
        <span className="v2-dim">показать:</span>
        {([
          ['все', 'все факты'],
          ['свободные', 'не рассмотрены'],
          ['допущения', 'допущения к точке'],
          ['спорные', 'противоречия'],
          ['ручные', 'заведены руками'],
        ] as [Фильтр, string][]).map(([ключ, имя]) => (
          <button key={ключ} type="button"
            className={фильтр === ключ ? 'v2-chip v2-chip--on' : 'v2-chip'}
            title={ключ === 'допущения'
              ? 'допущения [П] и принятые допущением — их подтверждают к ближайшей точке'
              : ключ === 'свободные' ? 'факты, по которым решения ещё нет'
                : ключ === 'ручные' ? 'факты без источника-документа: инженер и дата' : имя}
            onClick={() => setФильтр(ключ)}>
            {имя}
          </button>
        ))}
      </div>

      {факты !== null && видно.length === 0 && (
        <div className="v2-empty">
          Фактов по этому отбору нет.
          <span className="v2-empty__why">
            Поле наполняется разбором источника — «Загрузить источник» — либо руками.
          </span>
        </div>
      )}

      {видно.length > 0 && (
        <table className="v2-tab2">
          <thead>
            <tr>
              <th>Утверждение</th><th>Значение</th><th>Метка</th>
              <th>Откуда</th><th>Решение</th><th />
            </tr>
          </thead>
          <tbody>
            {видно.map((ф) => {
              const было = ф.disposition ?? 'free'
              return (
                <tr key={ф.id}>
                  <td>{ф.subject ? `${ф.subject}: ` : ''}{ф.predicate}</td>
                  <td>{ф.value}{ф.unit ? ` ${ф.unit}` : ''}</td>
                  <td title={МЕТКА[ф.mark] ?? ф.mark}>{МЕТКА[ф.mark] ?? ф.mark}</td>
                  <td className="v2-mono" title={ф.material}>
                    {ф.manual ? <span className="v2-kf__manual">{ф.material}</span> : (ф.anchor ?? '—')}
                    {ф.param_key && <span className="v2-dim"> · анкета: {ф.param_key}</span>}
                    {ф.conflicts && ф.conflicts.length > 0 && (
                      <span className="v2-bad" title="то же утверждение с иным значением: показаны оба, ИИ не выбирает"> · против {ф.conflicts.join(', ')}</span>
                    )}
                    {ф.source_updated && <span className="v2-warn" title={ф.source_updated}> · источник обновлён</span>}
                    {ф.assumption && (
                      <span className="v2-dim" title={`проверка: ${ф.assumption.validation}; если неверно: ${ф.assumption.impact_if_wrong}`}>
                        {' '}· допущение · {ф.assumption.owner} · к {ф.assumption.confirm_by}
                      </span>
                    )}
                  </td>
                  <td className={было === 'adopted' ? 'v2-ok' : было === 'rejected' ? 'v2-warn' : ''}>
                    {РЕШЕНИЕ[было] ?? было}
                  </td>
                  <td>
                    {решаем?.факт !== ф.id && (
                      <>
                        {было !== 'adopted' && (
                          <button type="button" className="v2-link"
                            title="принять факт: он станет основанием сущности — одним кликом"
                            onClick={() => решить(ф, 'adopted')}>принять</button>
                        )}
                        {было !== 'noted' && (
                          <>
                            {было !== 'adopted' && ' · '}
                            <button type="button" className="v2-link"
                              title="рассмотрен и не взят — это тоже решение, факт не исчезает"
                              onClick={() => решить(ф, 'noted')}>отложить</button>
                          </>
                        )}
                        {было !== 'rejected' && (
                          <>
                            {' · '}
                            <button type="button" className="v2-link"
                              title="отклонить — с причиной: «нет» без объяснения через год читается как забывчивость"
                              onClick={() => решить(ф, 'rejected')}>отклонить</button>
                          </>
                        )}
                        {было !== 'assumed' && было !== 'adopted' && (
                          <>
                            {' · '}
                            <button type="button" className="v2-link"
                              title="принять допущением: владелец, точка подтверждения и способ проверки обязательны — к точке допущение держит её"
                              onClick={() => setДопущение({ факт: ф.id, owner: '', confirm_by: 'MCR', validation: '', impact: '' })}>допущение</button>
                          </>
                        )}
                      </>
                    )}
                    {допущение?.факт === ф.id && (
                      <div className="v2-kf__why" data-why="работа">
                        <input value={допущение.owner} placeholder="владелец допущения"
                          onChange={(e) => setДопущение({ ...допущение, owner: e.target.value })} />
                        <select value={допущение.confirm_by} onChange={(e) => setДопущение({ ...допущение, confirm_by: e.target.value })}>
                          <option value="internal_review">к внутреннему обзору</option>
                          <option value="MCR">к MCR</option>
                          <option value="KDP-A">к KDP-A</option>
                        </select>
                        <input value={допущение.validation} placeholder="чем подтвердить (замер, расчёт, запрос)"
                          onChange={(e) => setДопущение({ ...допущение, validation: e.target.value })} />
                        <input value={допущение.impact} placeholder="что будет, если неверно"
                          onChange={(e) => setДопущение({ ...допущение, impact: e.target.value })} />
                        <div className="v2-form__actions">
                          <button type="button" className="v2-primary" disabled={!допущение.owner.trim() || !допущение.validation.trim()}
                            title="поставить допущение: до подтверждения точка держится им" onClick={записатьДопущение}>Допустить</button>
                          <button type="button" className="v2-link" onClick={() => setДопущение(null)}>отмена</button>
                        </div>
                      </div>
                    )}
                    {решаем?.факт === ф.id && (
                      <span className="v2-kf__why">
                        <textarea value={причина} autoFocus rows={2}
                          onChange={(e) => setПричина(e.target.value)}
                          placeholder={
                            решаем.решение === 'rejected' ? 'почему не берём'
                              : было === 'contested' ? 'какой факт победил и почему'
                                : 'что изменилось с прошлого решения'
                          } />
                        <button type="button" disabled={!причина.trim()}
                          title={причина.trim() ? 'записать решение' : 'здесь повод обязателен'}
                          onClick={записать}>Записать</button>
                        <button type="button" className="v2-link" title="не менять решение"
                          onClick={() => { setРешаем(null); setПричина('') }}>отмена</button>
                      </span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}

/** Вход в поле: текст, файл или ссылка + задание → разбор. */
function Source({ project, onParsed, onError }: {
  project: string
  onParsed: (итог: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => void
  onError: (e: string) => void
}) {
  const [имя, setИмя] = useState('')
  const [текст, setТекст] = useState('')
  const [ссылка, setСсылка] = useState('')
  const [вид, setВид] = useState('mission_memo')
  const [задание, setЗадание] = useState('разбери по сущностям')
  const [занято, setЗанято] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)
  const [прежние, setПрежние] = useState<{ code: string; name: string }[]>([])
  const [прежний, setПрежний] = useState('')

  useEffect(() => {
    api.materials(project).then((r) => setПрежние(r.items)).catch(() => setПрежние([]))
  }, [project])

  // Задание — по типу входного (каталог заданий, ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА §3);
  // строка остаётся редактируемой: узел или намерение инженер уточняет сам.
  const заданиеПоТипу: Record<string, string> = {
    mission_memo: 'разбери по сущностям',
    tor: 'это ТЗ — оцени против нужд',
    datasheet: 'обнови параметры ‹код узла›',
    normative: 'это норматив — заведи',
    analysis: 'разбери по сущностям',
    reference: 'просто в контекст',
  }
  const сменитьТип = (т: string) => { setВид(т); setЗадание(заданиеПоТипу[т] ?? 'разбери по сущностям') }

  // Текстовый файл читается В БРАУЗЕРЕ и уходит текстом; двоичный (docx ·
  // pdf · xlsx · pptx) уходит base64, текст извлекает сервер тем же
  // извлекателем, что у документов v1 (замечание ПМИ-5, 12.09).
  const [двоичный, setДвоичный] = useState<{ name: string; base64: string } | null>(null)
  const файл = (f: File | null) => {
    if (!f) { setДвоичный(null); return }
    if (!имя.trim()) setИмя(f.name.replace(/\.[^.]+$/, ''))
    const текстовый = /\.(txt|md|csv|markdown)$/i.test(f.name) || f.type.startsWith('text/')
    if (текстовый) {
      setДвоичный(null)
      f.text().then(setТекст).catch(() => onError('файл не прочитался: приложите текстовый'))
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const url = String(reader.result ?? '')
      setДвоичный({ name: f.name, base64: url.substring(url.indexOf(',') + 1) })
      setТекст('')
    }
    reader.onerror = () => onError('файл не прочитался')
    reader.readAsDataURL(f)
  }

  // Разбор — фоновой задачей (ADR-069): стенд отвечает, пока модель думает;
  // статус опрашивается раз в три секунды, готовый ответ применяется при опросе.
  const разобрать = () => {
    setЗанято(true); setИтог(null)
    const готово = (р: { task: string; note: string; accepted: number; refused: number; refusals: string[] }) => {
      setИтог(`${р.note}${р.refusals.length ? ` · отклонено: ${р.refusals.slice(0, 3).join('; ')}` : ''}`)
      onParsed(р)
      setЗанято(false)
    }
    const опрос = (job: string) => {
      api.atomizeJobStatus(project, job).then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job}: ${з.elapsed_seconds} с — стенд отвечает, страницу можно не держать`); window.setTimeout(() => опрос(job), 3000) }
      }).catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
    }
    api.putMaterial(project, {
      name: имя, kind: вид, text: текст, url: ссылка, author: 'инженер', supersedes: прежний || undefined,
      ...(двоичный ? { filename: двоичный.name, file_base64: двоичный.base64 } : {}),
    })
      .then((м) => api.atomizeJob(project, м.code, задание, 'инженер'))
      .then((з) => {
        if (з.status === 'done') готово({ task: з.task ?? '', note: з.note ?? '', accepted: з.accepted, refused: з.refused, refusals: з.refusals })
        else if (з.status === 'failed') { onError(з.error ?? 'разбор не удался'); setЗанято(false) }
        else { setИтог(`разбор идёт фоновой задачей ${з.job} — стенд отвечает`); window.setTimeout(() => опрос(з.job), 3000) }
      })
      .catch((e) => { onError(String(e.message ?? e)); setЗанято(false) })
  }

  const есть = текст.trim().length > 0 || ссылка.trim().length > 0 || двоичный !== null

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <label>название
          <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Записка миссии" />
        </label>
        <label>тип
          <select value={вид} onChange={(e) => сменитьТип(e.target.value)}>
            <option value="mission_memo">записка миссии</option>
            <option value="tor">техническое задание</option>
            <option value="normative">норматив</option>
            <option value="datasheet">даташит</option>
            <option value="analysis">анализ</option>
            <option value="reference">справочный</option>
          </select>
        </label>
      </div>
      <label>текст
        <textarea rows={4} value={текст} onChange={(e) => setТекст(e.target.value)}
          placeholder="вставьте текст — либо приложите файл или дайте ссылку ниже" />
      </label>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr' }}>
        <label>файл
          <input type="file" accept=".txt,.md,.csv,.docx,.pdf,.xlsx,.pptx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.presentationml.presentation"
            title="txt · md · csv читаются в браузере; docx · pdf · xlsx · pptx — текст извлекает сервер"
            onChange={(e) => файл(e.target.files?.[0] ?? null)} />
          {двоичный && <span className="v2-muted"> {двоичный.name}: текст извлечёт сервер</span>}
        </label>
        <label>ссылка
          <input value={ссылка} onChange={(e) => setСсылка(e.target.value)} placeholder="https://…" />
        </label>
      </div>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr 1fr' }}>
        <label>задание
          <input value={задание} onChange={(e) => setЗадание(e.target.value)}
            placeholder="разбери по сущностям · это норматив — заведи · сравни с нашим" />
        </label>
        <label>новая версия материала
          <select value={прежний} onChange={(e) => setПрежний(e.target.value)}
            title="прежняя версия того же входного: изменённые блоки пометят свои факты и сущности «источник обновлён»">
            <option value="">— нет, новый источник —</option>
            {прежние.map((м) => <option key={м.code} value={м.code}>{м.code} · {м.name}</option>)}
          </select>
        </label>
      </div>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" disabled={занято || !есть || !имя.trim()}
          title={!имя.trim() ? 'дайте источнику название'
            : !есть ? 'нужен текст, файл или ссылка'
              : 'положить материал и разобрать: факты и темы лягут в поле'}
          onClick={разобрать}>
          {занято ? 'Разбираю…' : 'Разобрать'}
        </button>
        {итог && <span className="v2-dim">{итог}</span>}
      </div>
    </div>
  )
}

/** Тема или факт руками: полноправно, но видно как ручное. */
function Manual({ project, темы, onDone, onError }: {
  project: string
  темы: Тема[]
  onDone: () => void
  onError: (e: string) => void
}) {
  const [метка, setМетка] = useState('')
  const [предмет, setПредмет] = useState('')
  const [утверждение, setУтверждение] = useState('')
  const [значение, setЗначение] = useState('')
  const [единица, setЕдиница] = useState('')
  const [вид, setВид] = useState('framing')
  const [темаФакта, setТемаФакта] = useState('')

  const завестиТему = () => {
    api.addTopic(project, метка.trim(), 'инженер')
      .then(() => { setМетка(''); onDone() }).catch((e) => onError(String(e.message ?? e)))
  }
  const завестиФакт = () => {
    api.addFact(project, {
      subject: предмет, predicate: утверждение, value: значение, unit: единица,
      kind: вид, topic: темаФакта, author: 'инженер',
    })
      .then(() => { setУтверждение(''); setЗначение(''); onDone() })
      .catch((e) => onError(String(e.message ?? e)))
  }
  const величина = вид === 'quantity'

  return (
    <div className="v2-kf__src" data-why="работа">
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
        <label>новая тема
          <input value={метка} onChange={(e) => setМетка(e.target.value)} placeholder="Опыт ЛИ Гонец-Д1М" />
        </label>
        <button type="button" disabled={!метка.trim()} onClick={завестиТему}
          title="тема руками: предмет, о котором знания копятся">Завести тему</button>
      </div>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '1fr 2fr 1fr 80px 1fr' }}>
        <label>предмет
          <input value={предмет} onChange={(e) => setПредмет(e.target.value)} placeholder="Гонец-Д1М" />
        </label>
        <label>утверждение
          <input value={утверждение} onChange={(e) => setУтверждение(e.target.value)}
            placeholder="срок активного существования по ЛИ" />
        </label>
        <label>значение
          <input value={значение} onChange={(e) => setЗначение(e.target.value)} placeholder="7" />
        </label>
        <label>единица
          <input value={единица} onChange={(e) => setЕдиница(e.target.value)} placeholder={величина ? 'лет' : '—'}
            title={величина ? 'величина без единицы — не факт' : 'у текста единицы нет'} />
        </label>
        <label>вид
          <select value={вид} onChange={(e) => setВид(e.target.value)}>
            {ВИДЫ_ФАКТА.map(([к, и]) => <option key={к} value={к}>{и}</option>)}
          </select>
        </label>
      </div>
      <div className="v2-kf__row" style={{ gridTemplateColumns: '2fr auto' }}>
        <label>тема факта
          <select value={темаФакта} onChange={(e) => setТемаФакта(e.target.value)}>
            <option value="">— без темы —</option>
            {темы.map((т) => <option key={т.id} value={т.label}>{т.label}</option>)}
          </select>
        </label>
        <button type="button" className="v2-primary"
          disabled={!утверждение.trim() || !значение.trim() || (величина && !единица.trim())}
          title="факт руками: метка [И], источник — инженер и дата; полноправный, но виден как ручной"
          onClick={завестиФакт}>Завести факт</button>
      </div>
      <div className="v2-empty__why">
        Ручной факт полноправен — диспозиции, связи, промпт. Доля знаний из источников
        считается без него: ручное видно отдельно.
      </div>
    </div>
  )
}
