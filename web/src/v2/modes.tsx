// Сцена 9 — «Режимы и операционные сценарии»: поверхность работы.
//
// До 20.09 поверхности у сцены 9 не было вовсе: условие говорило «машины
// режимов нет — назовите режимы аппарата в сцене 9», а называть их было негде
// (маршруты `/v2/modes` и `/v2/scenarios` на сервере были, экрана — нет).
// Владелец упёрся в это сразу после сцены 8.
//
// Устройство: две карточки по двум выходам сцены — машина режимов (ConOps §4)
// и операционные сценарии (ConOps §5). Поля — из истины схем: у состояния код,
// имя и род; у перехода — «откуда», «куда» и причина (обмен · событие ·
// таймер) значениями истины; у шага сценария — участник и что происходит. Ничего не заводится
// само: и машину, и сценарий записывает человек.
import { useCallback, useEffect, useState } from 'react'
import {
  api, type ComponentRow, type KindSpec, type ModeMachineRow, type ProposedScenario, type ScenarioProposal, type ScenarioRow,
} from './api'
import { useАвтор } from './research'

/**
 * Перечисления сцены 9 приходят С СЕРВЕРА вложенными путями истины:
 * `states.kind` (режим · состояние) и `transitions.trigger` (обмен · событие ·
 * таймер). Своей копии у экрана нет — правило владельца 20.09
 * (`field_rules.nested_enums`): «копия перечисления на экране — сторож».
 */
function useВидМашины(): (путь: string) => { код: string; имя: string }[] {
  const [вид, setВид] = useState<KindSpec | null>(null)
  useEffect(() => { api.kind('state_machine').then(setВид).catch(() => undefined) }, [])
  return useCallback((путь: string) => {
    const метки = вид?.enum_labels[путь]
    if (метки) return Object.entries(метки).map(([код, имя]) => ({ код, имя }))
    return (вид?.enums[путь] ?? []).map((код) => ({ код, имя: код }))
  }, [вид])
}

type Состояние = { code: string; name: string; kind: string }
type Переход = { from: string; to: string; вид: string; чем: string }

export function SceneModes({ project, onChanged }: { project: string; onChanged: () => void }) {
  return (
    <>
      <Режимы project={project} onChanged={onChanged} />
      <Сценарии project={project} onChanged={onChanged} />
    </>
  )
}

function Режимы({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [машины, setМашины] = useState<ModeMachineRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [владелец, setВладелец] = useState('')
  const [состояния, setСостояния] = useState<Состояние[]>([
    { code: '', name: '', kind: '' },
    { code: '', name: '', kind: '' },
  ])
  const [начальное, setНачальное] = useState('')
  const [переходы, setПереходы] = useState<Переход[]>([])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const значения = useВидМашины()
  const роды = значения('states.kind')
  const причины = значения('transitions.trigger')

  const перечитать = useCallback(() => {
    api.modes(project).then((р) => setМашины(р.items)).catch(() => undefined)
    api.components(project).then((р) => setУзлы(р.items)).catch(() => undefined)
  }, [project])
  useEffect(перечитать, [перечитать])

  const годные = состояния.filter((с) => с.code.trim() && с.name.trim())
  const можно = владелец !== '' && годные.length >= 2
    && переходы.every((п) => п.from && п.to && п.чем.trim())

  const записать = () => {
    setЗанято(true); setОтказ(null); setИтог(null)
    api.saveModes(project, {
      owner: владелец,
      states: годные.map((с) => ({ code: с.code.trim(), name: с.name.trim(), kind: с.kind || роды[0]?.код })),
      initial: начальное || годные[0]?.code,
      transitions: переходы.map((п) => ({ from: п.from, to: п.to, trigger: { [п.вид]: п.чем.trim() } })),
      author: 'инженер',
    })
      .then((р) => { setИтог(`машина режимов ${р.code} записана`); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Режимы аппарата</span>
        <span className="v2-card__count">{машины.length}</span>
      </div>
      <span className="v2-empty__why">
        Режим — состояние, в котором система ведёт себя ИНАЧЕ; одно состояние режимом
        не считается. Переход называет причину: событие, обмен или таймер. Отсюда
        наполняется ConOps §4.
      </span>

      {машины.map((м) => (
        <div key={м.code} className="v2-note">
          <span className="v2-note__rule">{м.code}</span>
          <span>
            узел {м.owner} · начальное: {м.initial || '—'} ·
            режимы: {м.states.map((с) => `${с.code} (${с.name})`).join(', ')}
            {м.transitions.length > 0 && ` · переходов ${м.transitions.length}`}
          </span>
        </div>
      ))}

      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-empty__why">{итог}</div>}

      <div className="v2-form">
        <label>Носитель режимов
          <select name="режимы.owner" value={владелец} onChange={(e) => setВладелец(e.target.value)}>
            <option value="">— узел состава, чьи это режимы —</option>
            {узлы.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
          </select>
        </label>

        {состояния.map((с, i) => (
          <span key={i} className="v2-field">
            <span className="v2-field__cap">Режим {i + 1}</span>
            <span className="v2-row3">
              <input name={`режим${i}.code`} autoComplete="off" value={с.code} placeholder="код (SAFE)"
                aria-label={`код режима ${i + 1}`}
                onChange={(e) => setСостояния(состояния.map((э, j) => j === i ? { ...э, code: e.target.value } : э))} />
              <input name={`режим${i}.name`} autoComplete="off" value={с.name} placeholder="имя (безопасный режим)"
                aria-label={`имя режима ${i + 1}`}
                onChange={(e) => setСостояния(состояния.map((э, j) => j === i ? { ...э, name: e.target.value } : э))} />
              <select name={`режим${i}.kind`} value={с.kind} aria-label={`род режима ${i + 1}`}
                onChange={(e) => setСостояния(состояния.map((э, j) => j === i ? { ...э, kind: e.target.value } : э))}>
                {роды.map((р) => <option key={р.код} value={р.код}>{р.имя}</option>)}
              </select>
            </span>
          </span>
        ))}
        <div className="v2-form__actions">
          <button type="button" className="v2-chip"
            onClick={() => setСостояния([...состояния, { code: '', name: '', kind: '' }])}>
            ещё режим
          </button>
          <button type="button" className="v2-chip" disabled={годные.length < 2}
            title={годные.length < 2 ? 'сначала назовите хотя бы два режима' : 'переход между названными режимами'}
            onClick={() => setПереходы([...переходы, {
              from: годные[0].code, to: годные[1].code, вид: причины[0]?.код ?? '', чем: '',
            }])}>
            ещё переход
          </button>
        </div>

        {годные.length >= 2 && (
          <label>Начальный режим
            <select name="режимы.initial" value={начальное || годные[0].code}
              onChange={(e) => setНачальное(e.target.value)}>
              {годные.map((с) => <option key={с.code} value={с.code}>{с.code} · {с.name}</option>)}
            </select>
          </label>
        )}

        {переходы.map((п, i) => (
          <span key={i} className="v2-field">
            <span className="v2-field__cap">Переход {i + 1}</span>
            <span className="v2-row3">
              <select name={`переход${i}.from`} value={п.from} aria-label={`переход ${i + 1}: откуда`}
                onChange={(e) => setПереходы(переходы.map((э, j) => j === i ? { ...э, from: e.target.value } : э))}>
                {годные.map((с) => <option key={с.code} value={с.code}>{с.code}</option>)}
              </select>
              <select name={`переход${i}.to`} value={п.to} aria-label={`переход ${i + 1}: куда`}
                onChange={(e) => setПереходы(переходы.map((э, j) => j === i ? { ...э, to: e.target.value } : э))}>
                {годные.map((с) => <option key={с.code} value={с.code}>{с.code}</option>)}
              </select>
              <select name={`переход${i}.вид`} value={п.вид} aria-label={`переход ${i + 1}: чем вызван`}
                onChange={(e) => setПереходы(переходы.map((э, j) => j === i ? { ...э, вид: e.target.value } : э))}>
                {причины.map((р) => <option key={р.код} value={р.код}>{р.имя}</option>)}
              </select>
            </span>
            <input name={`переход${i}.чем`} autoComplete="off" value={п.чем}
              placeholder="чем вызван: «FDIR уровень ≥ 2», «сеанс связи», «через 30 мин»"
              aria-label={`переход ${i + 1}: причина словами`}
              onChange={(e) => setПереходы(переходы.map((э, j) => j === i ? { ...э, чем: e.target.value } : э))} />
          </span>
        ))}

        <div className="v2-form__actions">
          <button type="button" className="v2-primary" disabled={занято || !можно}
            title={!владелец
              ? 'выберите узел: режимы принадлежат носителю'
              : годные.length < 2
                ? 'режимов меньше двух: одно состояние — не режим, а постоянное поведение'
                : переходы.some((п) => !п.чем.trim())
                  ? 'у перехода нет причины: режим не меняется сам по себе'
                  : 'записать машину режимов узла'}
            onClick={записать}>
            {занято ? 'Записываю…' : 'Записать режимы'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** Режим сценария словами — из ответа сервера, копии перечня в коде нет. */
const РЕЖИМ_СЦЕНАРИЯ: Record<string, string> = { nominal: 'штатный', off_nominal: 'нештатный', alarm: 'тревога' }
const ВИД_УЧАСТНИКА: Record<string, string> = { node: 'узел', side: 'сторона', external: 'внешняя система', unresolved: 'не найден' }

function Сценарии({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [сценарии, setСценарии] = useState<ScenarioRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [имя, setИмя] = useState('')
  const [шаги, setШаги] = useState<{ actor: string; what: string }[]>([{ actor: '', what: '' }])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  /**
   * Предложение из сервисов и цепочек Arcadia (шип 1, п. 1.7): один вызов,
   * приём галками, откат. Правило поставки СЦЕНАРИИ-PRE-A-СЦЕНА-9: «сценарии
   * предлагаются … кнопкой, а не руками».
   */
  const [предложение, setПредложение] = useState<ScenarioProposal | null>(null)
  const [отмечено, setОтмечено] = useState<string[]>([])
  const [занятоПредложением, setЗанятоПредложением] = useState(false)
  const [автор] = useАвтор()

  const перечитать = useCallback(() => {
    api.scenarios(project).then((р) => setСценарии(р.items)).catch(() => undefined)
    api.components(project).then((р) => setУзлы(р.items)).catch(() => undefined)
    api.scenarioProposal(project)
      .then((п) => setПредложение(п.run ? (п as ScenarioProposal) : null))
      .catch(() => setПредложение(null))
  }, [project])
  useEffect(перечитать, [перечитать])

  const предложить = () => {
    setЗанятоПредложением(true); setОтказ(null); setИтог(null)
    api.proposeScenarios(project, автор || 'инженер')
      .then((п) => { setПредложение(п); setОтмечено(п.scenarios.filter((с) => !с.exists && !с.accepted).map((с) => с.id)) })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанятоПредложением(false))
  }
  const принять = () => {
    if (!предложение || отмечено.length === 0) return
    setЗанятоПредложением(true); setОтказ(null)
    api.acceptScenarioProposal(project, предложение.run, отмечено, автор || 'инженер')
      .then((о) => { setИтог(о.note + (о.created.length ? `: ${о.created.join(', ')}` : '')); setОтмечено([]); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанятоПредложением(false))
  }
  const отменить = () => {
    if (!предложение) return
    setЗанятоПредложением(true); setОтказ(null)
    api.undoScenarioProposal(project, предложение.run, автор || 'инженер')
      .then((о) => { setИтог(о.note); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанятоПредложением(false))
  }
  const кПриёму = (с: ProposedScenario) => !с.exists && !с.accepted

  const годные = шаги.filter((ш) => ш.actor.trim() && ш.what.trim())
  const записать = () => {
    setЗанято(true); setОтказ(null); setИтог(null)
    api.addScenario(project, { name: имя.trim(), steps: годные, author: 'инженер' })
      .then((р) => {
        setИтог(`сценарий ${р.code} записан`); setИмя(''); setШаги([{ actor: '', what: '' }])
        перечитать(); onChanged()
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">Операционные сценарии</span>
        <span className="v2-card__count">{сценарии.length}</span>
      </div>
      <span className="v2-empty__why">
        Сценарий — путь по составу: кто участвует и что происходит по шагам. Шаг без
        участника не проверить. Отсюда наполняется ConOps §5; выход сцены — не меньше двух.
      </span>

      {сценарии.map((с) => (
        <div key={с.code} className="v2-note">
          <span className="v2-note__rule">{с.code}</span>
          <span>
            {с.name} · шагов {с.steps.length}
            {с.steps.length > 0 && `: ${с.steps.map((ш) => `${ш.actor} — ${ш.what}`).join(' → ')}`}
          </span>
        </div>
      ))}

      {отказ && <div className="v2-locked">{отказ}</div>}
      {итог && <div className="v2-empty__why">{итог}</div>}

      <div className="v2-form__actions" data-why="следующий-клик">
        <button type="button" className="v2-chip" disabled={занятоПредложением} onClick={предложить}
          title="один вызов: сервисы проекта и цепочки полки Arcadia → сценарии с шагами «участник — что происходит»; ничего не заводится без вашей галки">
          {занятоПредложением ? 'Предлагаю…' : 'Предложить из сервисов'}
        </button>
        {предложение && предложение.scenarios.some((с) => с.accepted) && (
          <button type="button" className="v2-link" disabled={занятоПредложением} onClick={отменить}
            title="снять заведённые предложением сценарии; записанные руками целы">
            Отменить предложение {предложение.run}
          </button>
        )}
      </div>
      {предложение && (
        <div className="v2-card__body" data-why="работа">
          <div className="v2-dim">{предложение.note}{предложение.cached ? ' · ответ из журнала' : ''}</div>
          {предложение.scenarios.map((с) => (
            <label key={с.id} className="v2-check" title={с.reason}>
              <input type="checkbox" aria-label={`отметить сценарий ${с.id}`} disabled={!кПриёму(с)}
                checked={отмечено.includes(с.id)}
                onChange={(e) => setОтмечено(e.target.checked ? [...отмечено, с.id] : отмечено.filter((и) => и !== с.id))} />
              <span>
                <b>{с.name}</b> · {РЕЖИМ_СЦЕНАРИЯ[с.mode] ?? с.mode}
                {с.services.length > 0 && ` · сервисы: ${с.services.join(', ')}`}
                {с.accepted && <span className="v2-ok"> · принят</span>}
                {с.exists && <span className="v2-dim"> · уже есть</span>}
                {с.unresolved.length > 0 && (
                  <span className="v2-warn"> · участники без записи: {с.unresolved.join(', ')} — назовите их узлом или стороной</span>
                )}
                <div className="v2-dim">
                  {с.steps.map((ш, i) => (
                    <span key={i}>
                      {i > 0 && ' → '}
                      <span title={ВИД_УЧАСТНИКА[ш.kind] ?? ш.kind}>{ш.participant}</span> — {ш.what}
                    </span>
                  ))}
                </div>
              </span>
            </label>
          ))}
          {предложение.refused.length > 0 && (
            <div className="v2-dim">отбито: {предложение.refused.join('; ')}</div>
          )}
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={занятоПредложением || отмечено.length === 0} onClick={принять}
              title={отмечено.length === 0 ? 'отметьте сценарии: само предложение ничего не заводит' : 'завести отмеченные сценарии цепочками сцены 9'}>
              Принять отмеченные ({отмечено.length})
            </button>
          </div>
        </div>
      )}

      <div className="v2-form">
        <label>Имя сценария
          <input name="сценарий.name" autoComplete="off" value={имя}
            placeholder="доставка сообщения класса B′ вне наземного покрытия"
            onChange={(e) => setИмя(e.target.value)} />
        </label>
        {шаги.map((ш, i) => (
          <span key={i} className="v2-field">
            <span className="v2-field__cap">Шаг {i + 1}</span>
            <span className="v2-row2">
              <select name={`шаг${i}.actor`} value={ш.actor} aria-label={`участник шага ${i + 1}`}
                onChange={(e) => setШаги(шаги.map((э, j) => j === i ? { ...э, actor: e.target.value } : э))}>
                <option value="">— участник —</option>
                {узлы.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
              </select>
              <input name={`шаг${i}.what`} autoComplete="off" value={ш.what} placeholder="что происходит"
                aria-label={`что происходит на шаге ${i + 1}`}
                onChange={(e) => setШаги(шаги.map((э, j) => j === i ? { ...э, what: e.target.value } : э))} />
            </span>
          </span>
        ))}
        <div className="v2-form__actions">
          <button type="button" className="v2-chip" onClick={() => setШаги([...шаги, { actor: '', what: '' }])}>
            ещё шаг
          </button>
          <button type="button" className="v2-primary"
            disabled={занято || !имя.trim() || годные.length === 0}
            title={!имя.trim()
              ? 'у сценария нет имени'
              : годные.length === 0
                ? 'сценарий без шагов — это название, а не сценарий'
                : 'записать сценарий'}
            onClick={записать}>
            {занято ? 'Записываю…' : 'Записать сценарий'}
          </button>
        </div>
      </div>
    </div>
  )
}
