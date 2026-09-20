// Сцена 9 — «Режимы и операционные сценарии»: поверхность работы.
//
// До 20.09 поверхности у сцены 9 не было вовсе: условие говорило «машины
// режимов нет — назовите режимы аппарата в сцене 9», а называть их было негде
// (маршруты `/v2/modes` и `/v2/scenarios` на сервере были, экрана — нет).
// Владелец упёрся в это сразу после сцены 8.
//
// Устройство: две карточки по двум выходам сцены — машина режимов (ConOps §4)
// и операционные сценарии (ConOps §5). Поля — из истины схем: у состояния код,
// имя и род; у перехода — «откуда», «куда» и ПРИЧИНА (событие · обмен ·
// таймер); у шага сценария — участник и что происходит. Ничего не заводится
// само: и машину, и сценарий записывает человек.
import { useCallback, useEffect, useState } from 'react'
import { api, type ComponentRow, type ModeMachineRow, type ScenarioRow } from './api'

/**
 * Род состояния — вложенный перечень истины
 * (`state_machine.states[].kind: enum[mode,state]`). Генератор вложенные
 * перечни наружу не отдаёт (даёт только перечни полей самого вида), поэтому
 * здесь стоят те же два значения с русскими именами — вопрос владельцу
 * записан в NEXT.md.
 */
const РОД: { код: string; имя: string }[] = [
  { код: 'mode', имя: 'режим' },
  { код: 'state', имя: 'состояние' },
]

/** Причина перехода — тоже вложенный перечень истины (`trigger`). */
const ПРИЧИНА: { код: string; имя: string }[] = [
  { код: 'event', имя: 'событие' },
  { код: 'exchange', имя: 'обмен' },
  { код: 'timer', имя: 'таймер' },
]

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
    { code: '', name: '', kind: 'mode' },
    { code: '', name: '', kind: 'mode' },
  ])
  const [начальное, setНачальное] = useState('')
  const [переходы, setПереходы] = useState<Переход[]>([])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)

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
      states: годные.map((с) => ({ code: с.code.trim(), name: с.name.trim(), kind: с.kind })),
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
            <span className="v2-measure">
              <input name={`режим${i}.code`} autoComplete="off" value={с.code} placeholder="код (SAFE)"
                aria-label={`код режима ${i + 1}`}
                onChange={(e) => setСостояния(состояния.map((э, j) => j === i ? { ...э, code: e.target.value } : э))} />
              <input name={`режим${i}.name`} autoComplete="off" value={с.name} placeholder="имя (безопасный режим)"
                aria-label={`имя режима ${i + 1}`}
                onChange={(e) => setСостояния(состояния.map((э, j) => j === i ? { ...э, name: e.target.value } : э))} />
              <select name={`режим${i}.kind`} value={с.kind} aria-label={`род режима ${i + 1}`}
                onChange={(e) => setСостояния(состояния.map((э, j) => j === i ? { ...э, kind: e.target.value } : э))}>
                {РОД.map((р) => <option key={р.код} value={р.код}>{р.имя}</option>)}
              </select>
            </span>
          </span>
        ))}
        <div className="v2-form__actions">
          <button type="button" className="v2-chip"
            onClick={() => setСостояния([...состояния, { code: '', name: '', kind: 'mode' }])}>
            ещё режим
          </button>
          <button type="button" className="v2-chip" disabled={годные.length < 2}
            title={годные.length < 2 ? 'сначала назовите хотя бы два режима' : 'переход между названными режимами'}
            onClick={() => setПереходы([...переходы, {
              from: годные[0].code, to: годные[1].code, вид: 'event', чем: '',
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
            <span className="v2-measure">
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
                {ПРИЧИНА.map((р) => <option key={р.код} value={р.код}>{р.имя}</option>)}
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

function Сценарии({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [сценарии, setСценарии] = useState<ScenarioRow[]>([])
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [имя, setИмя] = useState('')
  const [шаги, setШаги] = useState<{ actor: string; what: string }[]>([{ actor: '', what: '' }])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    api.scenarios(project).then((р) => setСценарии(р.items)).catch(() => undefined)
    api.components(project).then((р) => setУзлы(р.items)).catch(() => undefined)
  }, [project])
  useEffect(перечитать, [перечитать])

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

      <div className="v2-form">
        <label>Имя сценария
          <input name="сценарий.name" autoComplete="off" value={имя}
            placeholder="доставка сообщения класса B′ вне наземного покрытия"
            onChange={(e) => setИмя(e.target.value)} />
        </label>
        {шаги.map((ш, i) => (
          <span key={i} className="v2-field">
            <span className="v2-field__cap">Шаг {i + 1}</span>
            <span className="v2-measure">
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
