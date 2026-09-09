// Экран «Точки» (шип D, сцены 15–18): готовность по экспертизе, замечания
// с возвратом в сцену, решение точки, матрица зрелости комплекта.
//
// Минимум по `ДИЗАЙН-ПРИНЦИПЫ-V2`: список точек, карточка вниз. Ни одной
// спрятанной кнопки: роль и блокирующие проверяет сервер и отвечает
// словами — они и показываются, как есть.
import { useEffect, useState } from 'react'
import { api, type Condition, type Finding, type Gate, type Phase, type Position, type PointsView } from './api'

const ИСХОД: Record<string, string> = {
  approve: 'зафиксирована',
  return: 'возвращена с замечаниями',
  defer: 'отложена',
}
const ВИД_ЗАМЕЧАНИЯ: Record<string, string> = { finding: 'замечание', rfa: 'RFA', rid: 'RID' }
const РОЛЬ: Record<string, string> = {
  lead: 'руководитель проекта',
  lead_se: 'ведущий системный инженер',
  specialist: 'инженер',
  da_review: 'DA',
}

export function Points({ project, phase, onChanged }: {
  project: string | null
  phase: Phase | null
  onChanged: () => void
}) {
  const [вид, setВид] = useState<PointsView | null>(null)
  const [открыта, setОткрыта] = useState<string | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = () => {
    if (!project) return
    api.points(project).then(setВид).catch((e) => setОтказ(String(e.message ?? e)))
  }
  useEffect(перечитать, [project])

  if (!project) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Проект не выбран.</div></div>
  if (отказ) return <div className="v2-panel" data-why="почему-нельзя"><div className="v2-locked">{отказ}</div></div>
  if (!вид) return <div className="v2-panel" data-why="следующий-клик"><div className="v2-empty">Точки читаются…</div></div>

  const пройдено = вид.items.filter((т) => т.passed).length
  const точка = вид.items.find((т) => т.key === открыта) ?? null
  return (
    <>
      <div className="v2-panel" data-why="следующий-клик">
        <h3>
          Точки фазы
          <span className="v2-cnt">{пройдено} из {вид.items.length} пройдены · {вид.phase}</span>
        </h3>
        <div className="v2-list">
          {вид.items.map((т) => (
            <button key={т.key} type="button"
              className={т.key === открыта ? 'v2-row v2-row--cur' : 'v2-row'}
              title={т.passed ? 'пройдена — решение записано' : т.blocking.length > 0 ? `держится: ${т.blocking.join('; ')}` : 'условия выполнены — можно решать'}
              onClick={() => setОткрыта(т.key === открыта ? null : т.key)}>
              <span className="v2-row__n">◆</span>
              <span className="v2-row__t">{т.title}</span>
              <span className="v2-st">
                {т.planned_date && <>{датаКратко(т.planned_date)} · </>}
                {т.passed
                  ? <span className="v2-ok">пройдена</span>
                  : т.blocking.length > 0
                    ? <span className="v2-bad">{т.blocking.length} блокирующих</span>
                    : <span className="v2-ok">условия выполнены</span>}
                {' · решает '}{РОЛЬ[т.role] ?? т.role}
              </span>
            </button>
          ))}
        </div>
      </div>
      {точка && phase && (
        <PointCard project={project} точка={точка} все={вид.items} phase={phase}
          onChanged={() => { перечитать(); onChanged() }} />
      )}
    </>
  )
}

function PointCard({ project, точка, все, phase, onChanged }: {
  project: string
  точка: Gate
  все: Gate[]
  phase: Phase
  onChanged: () => void
}) {
  const [ответ, setОтвет] = useState<string | null>(null)
  const [форма, setФорма] = useState<{ text: string; scene: string; question?: string; kind: string } | null>(null)
  const [помета, setПомета] = useState('')
  const [толькоОткрытые, setТолькоОткрытые] = useState(true)
  const [занято, setЗанято] = useState(false)

  const чекЛист = точка.checklist_of ? все.find((т) => т.key === точка.checklist_of) ?? null : null
  const экспертиза = точка.expertise ?? null
  const вопросы = (экспертиза?.questions.length ? экспертиза.questions : чекЛист?.expertise?.questions) ?? []
  const открытые = точка.findings.filter((з) => з.status === 'open')

  const действие = async (что: () => Promise<unknown>) => {
    setЗанято(true); setОтвет(null)
    try { await что(); setФорма(null); onChanged() } catch (e) { setОтвет(String((e as Error).message ?? e)) } finally { setЗанято(false) }
  }
  const завести = () => форма && действие(() => api.addFinding(project, точка.key, {
    text: форма.text, returns_to_scene: форма.scene, question: форма.question, kind: форма.kind,
  }))
  const решить = (исход: string) => действие(() => api.decide(project, точка.key, исход, помета))
  const новоеЗамечание = (вопрос?: string) => setФорма({
    text: вопрос ? `Нет: ${вопрос}` : '', scene: phase.current_scene ?? phase.scenes[0]?.key ?? '1', question: вопрос,
    kind: точка.key === 'MCR' ? 'rfa' : 'finding',
  })

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        ◆ {точка.title}
        <span className="v2-cnt">
          {точка.planned_date ? датаКратко(точка.planned_date) + ' · ' : ''}
          {точка.decision
            ? `${ИСХОД[точка.decision.outcome] ?? точка.decision.outcome} · ${точка.decision.by} · ${датаКратко(точка.decision.at.slice(0, 10))}`
            : `решает ${РОЛЬ[точка.role] ?? точка.role}`}
        </span>
      </h3>
      {точка.decision?.note && <div className="v2-note-line">{точка.decision.note}</div>}
      <div className="v2-note-line">
        <a className="v2-link" href={api.pointPackageUrl(project, точка.key)} target="_blank" rel="noreferrer"
          title="один архив: точка JSON, печать документов фазы, .sdoc с грамматикой, пакет знаний с отпечатком, манифест">
          пакет точки (zip)
        </a>
      </div>
      {точка.legend_note && экспертиза?.positions.length ? <div className="v2-note-line">{точка.legend_note}</div> : null}

      <h4 className="v2-h4">Готовность</h4>
      <ul className="v2-checks">
        {точка.criteria.map((у) => <Условие key={у.check} у={у} />)}
        {экспертиза?.positions.map((п) => <Позиция key={п.artifact} п={п} />)}
      </ul>

      {чекЛист && (
        <>
          <h4 className="v2-h4">Чек-лист по критериям {чекЛист.title}</h4>
          <ul className="v2-checks">
            {чекЛист.criteria.map((у) => <Условие key={у.check} у={у} />)}
            {чекЛист.expertise?.positions.map((п) => <Позиция key={п.artifact} п={п} />)}
          </ul>
        </>
      )}

      {(экспертиза || вопросы.length > 0) && (
        <>
          <h4 className="v2-h4">Экспертиза{экспертиза?.source ? ` · ${экспертиза.source}` : ''}</h4>
          {экспертиза?.goal && <div className="v2-act__goal">{экспертиза.goal}</div>}
          <ul className="v2-checks">
            {вопросы.map((в) => {
              const нет = точка.findings.find((з) => з.question === в && з.status === 'open')
              return (
                <li key={в} className={нет ? 'v2-check v2-check--no' : 'v2-check'}>
                  <span>{нет ? '☐' : '☑'}</span>
                  <span className="v2-check__t">{в}</span>
                  {нет
                    ? <span className="v2-cnt"> — {нет.code} → сцена {нет.scene}</span>
                    : <button type="button" className="v2-link" onClick={() => новоеЗамечание(в)}>нет — замечание</button>}
                </li>
              )
            })}
          </ul>
          {экспертиза && экспертиза.results.length > 0 && (
            <div className="v2-act__io">
              <span>результаты: {экспертиза.results.join(' · ')}</span>
              {экспертиза.main_outcome.length > 0 && <span>главный итог: {экспертиза.main_outcome.join(' · ')}</span>}
            </div>
          )}
        </>
      )}

      {точка.matrix.length > 0 && (
        <>
          <h4 className="v2-h4">Матрица зрелости комплекта Д1–Д9</h4>
          <div className="v2-scroll">
            <table className="v2-table">
              <thead>
                <tr>
                  <th>артефакт</th>
                  {все.map((т) => <th key={т.key}>{т.key}</th>)}
                  <th>к этой точке</th>
                </tr>
              </thead>
              <tbody>
                {точка.matrix.map((р) => (
                  <tr key={р.artifact}>
                    <td>{р.artifact}</td>
                    {все.map((т) => <td key={т.key} className="v2-mono">{р.codes?.[т.key] ?? '—'}</td>)}
                    <td>
                      {р.passed === null
                        ? <span className="v2-cnt">{р.why}</span>
                        : р.passed
                          ? <span className="v2-ok">✓ {р.why}</span>
                          : <span className={р.blocking ? 'v2-bad' : 'v2-cnt'}>☐ {р.why}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <h4 className="v2-h4">
        Замечания
        <span className="v2-cnt"> {открытые.length} открытых из {точка.findings.length}</span>
        {' '}
        <button type="button" className={толькоОткрытые ? 'v2-chip v2-chip--on' : 'v2-chip'}
          onClick={() => setТолькоОткрытые(!толькоОткрытые)}>{толькоОткрытые ? 'открытые' : 'все'}</button>
        {' '}
        <button type="button" className="v2-chip" onClick={() => новоеЗамечание()}>+ замечание</button>
      </h4>
      {форма && (
        <div className="v2-form" data-why="работа">
          <label>текст замечания
            <textarea rows={2} value={форма.text} onChange={(e) => setФорма({ ...форма, text: e.target.value })} />
          </label>
          <label>возврат в сцену
            <select value={форма.scene} onChange={(e) => setФорма({ ...форма, scene: e.target.value })}>
              {phase.scenes.map((с) => <option key={с.key} value={с.key}>{с.key} · {с.title}</option>)}
            </select>
          </label>
          {точка.key === 'MCR' && (
            <label>вид
              <select value={форма.kind} onChange={(e) => setФорма({ ...форма, kind: e.target.value })}>
                <option value="rfa">RFA — запрос действия</option>
                <option value="rid">RID — расхождение обзора</option>
              </select>
            </label>
          )}
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занято || !форма.text.trim()}
              title={!форма.text.trim() ? 'замечание без текста не заводится' : 'завести замечание: сцена возврата снова в работе'}
              onClick={завести}>Завести</button>
            <button type="button" className="v2-link" onClick={() => setФорма(null)}>отмена</button>
          </div>
        </div>
      )}
      <ul className="v2-checks">
        {точка.findings.filter((з) => !толькоОткрытые || з.status === 'open').map((з) => (
          <Замечание key={з.code} з={з} занято={занято}
            onClose={() => действие(() => api.closeFinding(project, з.code, ''))} />
        ))}
        {точка.findings.length === 0 && <li className="v2-empty">замечаний нет</li>}
      </ul>

      {!точка.passed && (
        <div className="v2-form v2-form--row" data-why="следующий-клик">
          <input value={помета} placeholder="помета к решению (не обязательно)" onChange={(e) => setПомета(e.target.value)} />
          <button type="button" className="v2-primary" disabled={занято}
            title={точка.opens_phase ? `решение открывает ${точка.opens_phase}` : 'зафиксировать точку: блокирующие и роль проверит сервер'}
            onClick={() => решить('approve')}>
            {точка.opens_phase ? `Решение: переход в ${точка.opens_phase}` : 'Зафиксировать'}
          </button>
          <button type="button" className="v2-link" disabled={занято}
            title="вернуть точку с открытыми замечаниями" onClick={() => решить('return')}>вернуть с замечаниями</button>
        </div>
      )}
      {ответ && <div className="v2-locked" data-why="почему-нельзя">{ответ}</div>}
    </div>
  )
}

function Условие({ у }: { у: Condition }) {
  return (
    <li className={у.passed ? 'v2-check' : у.blocking === false ? 'v2-check v2-check--note' : 'v2-check v2-check--no'}>
      <span>{у.passed ? '✓' : '☐'}</span>
      <span className="v2-check__t">{у.title}</span>
      {!у.passed && у.why && <span className="v2-cnt"> — {у.why}</span>}
    </li>
  )
}

function Позиция({ п }: { п: Position }) {
  const класс = п.passed === null ? 'v2-check v2-check--note' : п.passed ? 'v2-check' : п.blocking ? 'v2-check v2-check--no' : 'v2-check v2-check--note'
  return (
    <li className={класс}>
      <span>{п.passed === null ? '·' : п.passed ? '✓' : '☐'}</span>
      <span className="v2-check__t"><span className="v2-mono">{п.maturity}</span> {п.artifact}</span>
      {п.why && п.passed !== true && <span className="v2-cnt"> — {п.why}</span>}
      {п.passed === true && п.our_ref?.startsWith('kind:') && <span className="v2-cnt" title={п.why ?? ''}> — записи есть</span>}
    </li>
  )
}

function Замечание({ з, занято, onClose }: { з: Finding; занято: boolean; onClose: () => void }) {
  return (
    <li className={з.status === 'open' ? 'v2-check v2-check--no' : 'v2-check'}>
      <span>{з.status === 'open' ? '☐' : '✓'}</span>
      <span className="v2-check__t">
        <span className="v2-row__n">{з.code}</span> {з.text}
        <span className="v2-cnt"> — {ВИД_ЗАМЕЧАНИЯ[з.kind] ?? з.kind} · сцена {з.scene} · {з.author}{з.closed_by ? ` · закрыто: ${з.closed_by}` : ''}</span>
      </span>
      {з.status === 'open' && (
        <button type="button" className="v2-link" disabled={занято}
          title="закрыть замечание — сцена возврата отпускается" onClick={onClose}>закрыть</button>
      )}
    </li>
  )
}

function датаКратко(дата: string): string {
  const [г, м, д] = дата.split('-')
  return д && м ? `${д}.${м}${г ? '.' + г.slice(2) : ''}` : дата
}
