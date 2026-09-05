// Сцены 1–4 Pre-A (СЦЕНАРИЙ-PRE-A). Каждая сцена — один вопрос и его выход.
//
// Ни одна сцена не решает, открыта ли она: это сказал сервер. Здесь только
// формы и списки, встроенные в рамку.
import { useEffect, useState } from 'react'
import { api, type EntityRow, type Phase } from './api'

/** Сцена 1 — открыть проект. Точки заводятся сразу, с датами по умолчанию. */
export function SceneOpenProject({ onOpened }: { onOpened: (project: string) => void }) {
  const [имя, setИмя] = useState('')
  const [код, setКод] = useState('')
  const [класс, setКласс] = useState('НОО · связь и IoT')
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)

  const открыть = () => {
    setЗанято(true); setОтказ(null)
    api.openProject({ name: имя, code: код || undefined, mission_class: класс, standard: 'NASA-7120' })
      .then((фаза) => onOpened(фаза.project))
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-form">
      {отказ && <div className="v2-locked">{отказ}</div>}
      <label>Название проекта
        <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Национальная платформа IoT" />
      </label>
      <label>Код проекта
        <input value={код} onChange={(e) => setКод(e.target.value)} placeholder="PJ-0001" />
      </label>
      <label>Класс миссии
        <input value={класс} onChange={(e) => setКласс(e.target.value)} />
      </label>
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" onClick={открыть}
          disabled={занято || !имя.trim()}
          title={!имя.trim() ? 'дайте проекту название — по нему его узнают в портфеле' : 'завести проект и фазу с тремя точками'}>
          {занято ? 'Завожу…' : 'Открыть проект'}
        </button>
        <span className="v2-empty__why">Стандарт — NASA-7120: имена точек будут MCR и KDP-A.</span>
      </div>
    </div>
  )
}

/** Сцена 2 — замысел: четыре поля либо связный абзац; принять явно. */
export function SceneIntent({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [поля, setПоля] = useState({ for_whom: '', what: '', where: '', horizon: '' })
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    api.entities(project, 'intent').then((r) => {
      const первый = r.items[0]
      if (первый) {
        setПоля({
          for_whom: String(первый.doc.for_whom ?? ''),
          what: String(первый.doc.what ?? ''),
          where: String(первый.doc.where ?? ''),
          horizon: String(первый.doc.horizon ?? ''),
        })
      }
    }).catch(() => undefined)
  }, [project])

  const полон = Object.values(поля).every((v) => v.trim() !== '')

  const сохранить = (принять: boolean) => {
    setЗанято(true); setОтказ(null)
    api.intent(project, { ...поля, accepted: принять })
      .then(() => onChanged())
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-form">
      {отказ && <div className="v2-locked">{отказ}</div>}
      {([
        ['for_whom', 'Для кого', 'перевозчики опасных грузов, операторы БВС'],
        ['what', 'Что делает', 'передаёт короткие сообщения от датчиков'],
        ['where', 'Где', 'Арктика, СМП, Сибирь и Дальний Восток'],
        ['horizon', 'Горизонт', 'к 2033 году, около 150 аппаратов'],
      ] as const).map(([поле, подпись, подсказка]) => (
        <label key={поле}>{подпись}
          <textarea rows={поля[поле].length > 90 ? 3 : 1} value={поля[поле]} placeholder={подсказка}
            onChange={(e) => setПоля({ ...поля, [поле]: e.target.value })} />
        </label>
      ))}
      <div className="v2-form__actions">
        <button type="button" className="v2-primary" onClick={() => сохранить(true)}
          disabled={занято || !полон}
          title={полон
            ? 'принять замысел — сцена 3 откроется сама'
            : 'нужны все четыре поля: замысел без «где» или «горизонта» не замысел'}>
          {занято ? 'Принимаю…' : 'Принять замысел'}
        </button>
        <button type="button" onClick={() => сохранить(false)} disabled={занято}
          title="сохранить черновиком: сцена 3 останется закрытой">
          сохранить черновик
        </button>
      </div>
    </div>
  )
}

/** Сцена 3 — стейкхолдеры и их нужды: у каждой нужды есть носитель. */
export function SceneStakeholders({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [стороны, setСтороны] = useState<EntityRow[]>([])
  const [нужды, setНужды] = useState<EntityRow[]>([])
  const [имя, setИмя] = useState('')
  const [роль, setРоль] = useState('customer')
  const [нужда, setНужда] = useState('')
  const [носитель, setНоситель] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = () => {
    api.entities(project, 'stakeholder').then((r) => setСтороны(r.items)).catch(() => undefined)
    api.entities(project, 'need').then((r) => setНужды(r.items)).catch(() => undefined)
  }
  useEffect(перечитать, [project])

  const добавитьСторону = () => {
    setОтказ(null)
    api.addStakeholder(project, { name: имя, role: роль })
      .then(() => { setИмя(''); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const добавитьНужду = () => {
    setОтказ(null)
    api.addNeed(project, { statement: нужда, owner: носитель })
      .then(() => { setНужда(''); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const нуждыСтороны = (id: string) => нужды.filter((n) => (n.owned_by ?? []).includes(id))

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form v2-form--row">
        <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Минтранс России" />
        <select value={роль} onChange={(e) => setРоль(e.target.value)} title="роль стороны в проекте">
          <option value="customer">заказчик</option>
          <option value="regulator">регулятор</option>
          <option value="operator">оператор</option>
          <option value="consumer">потребитель</option>
          <option value="partner">партнёр</option>
          <option value="established">учреждаемый</option>
        </select>
        <button type="button" onClick={добавитьСторону} disabled={!имя.trim()}
          title={имя.trim() ? 'завести сторону' : 'назовите сторону'}>Добавить сторону</button>
      </div>

      <table className="v2-table">
        <thead><tr><th>Код</th><th>Сторона</th><th>Роль</th><th>Нужды</th></tr></thead>
        <tbody>
          {стороны.map((с) => (
            <tr key={с.id}>
              <td className="v2-mono">{с.code}</td>
              <td>{String(с.doc.name ?? '')}</td>
              <td>{String(с.doc.role ?? '')}</td>
              <td>
                {нуждыСтороны(с.id).length === 0
                  ? <span className="v2-warn">нужд нет — сцена не закроется</span>
                  : нуждыСтороны(с.id).map((n) => <div key={n.id}>{String(n.doc.statement ?? '')}</div>)}
              </td>
            </tr>
          ))}
          {стороны.length === 0 && (
            <tr><td colSpan={4} className="v2-empty">
              Сторон пока нет.
              <span className="v2-empty__why">Круг шире потребителей: регуляторы, операторы, учреждаемые организации.</span>
            </td></tr>
          )}
        </tbody>
      </table>

      <div className="v2-form v2-form--row">
        <input value={нужда} onChange={(e) => setНужда(e.target.value)}
          placeholder="перевозчику нужна телеметрия груза в пути" />
        <select value={носитель} onChange={(e) => setНоситель(e.target.value)} title="носитель нужды">
          <option value="">— чья нужда —</option>
          {стороны.map((с) => <option key={с.id} value={с.code}>{String(с.doc.name ?? с.code)}</option>)}
        </select>
        <button type="button" onClick={добавитьНужду} disabled={!нужда.trim() || !носитель}
          title={!носитель ? 'у нужды обязан быть носитель — иначе за неё никто не отвечает' : 'завести нужду'}>
          Добавить нужду
        </button>
      </div>
    </div>
  )
}

/** Сцена 4 — цели: показатель, год и связь с нуждами. */
export function SceneGoals({ project, onChanged }: { project: string; onChanged: () => void }) {
  const [цели, setЦели] = useState<EntityRow[]>([])
  const [нужды, setНужды] = useState<EntityRow[]>([])
  const [формулировка, setФормулировка] = useState('')
  const [год, setГод] = useState('2033')
  const [покрывает, setПокрывает] = useState<string[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)

  const перечитать = () => {
    api.entities(project, 'goal').then((r) => setЦели(r.items)).catch(() => undefined)
    api.entities(project, 'need').then((r) => setНужды(r.items)).catch(() => undefined)
  }
  useEffect(перечитать, [project])

  const добавить = () => {
    setОтказ(null)
    api.addGoal(project, { statement: формулировка, year: Number(год), covers: покрывает })
      .then(() => { setФормулировка(''); setПокрывает([]); перечитать(); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const безЦели = нужды.filter((n) => (n.covered_by ?? []).length === 0)

  return (
    <div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form">
        <label>Цель с измеримым результатом
          <input value={формулировка} onChange={(e) => setФормулировка(e.target.value)}
            placeholder="отслеживаемость 100% объектов перечня" />
        </label>
        <label>Год
          <input value={год} onChange={(e) => setГод(e.target.value)} style={{ width: 90 }} />
        </label>
        <div>
          <div className="v2-empty__why">Какие нужды закрывает:</div>
          {нужды.map((n) => (
            <label key={n.id} className="v2-check">
              <input type="checkbox" checked={покрывает.includes(n.code)}
                onChange={(e) => setПокрывает(e.target.checked
                  ? [...покрывает, n.code]
                  : покрывает.filter((x) => x !== n.code))} />
              {String(n.doc.statement ?? n.code)}
            </label>
          ))}
        </div>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" onClick={добавить}
            disabled={!формулировка.trim() || покрывает.length === 0}
            title={покрывает.length === 0
              ? 'цель, не закрывающая ни одной нужды, ни к чему не ведёт'
              : 'завести цель'}>
            Добавить цель
          </button>
        </div>
      </div>

      <table className="v2-table">
        <thead><tr><th>Код</th><th>Цель</th><th>Год</th><th>Закрывает нужд</th></tr></thead>
        <tbody>
          {цели.map((ц) => (
            <tr key={ц.id}>
              <td className="v2-mono">{ц.code}</td>
              <td>{String(ц.doc.statement ?? '')}</td>
              <td>{String(ц.doc.year ?? '')}</td>
              <td>{нужды.filter((n) => (n.covered_by ?? []).includes(ц.id)).length}</td>
            </tr>
          ))}
          {цели.length === 0 && (
            <tr><td colSpan={4} className="v2-empty">Целей пока нет.</td></tr>
          )}
        </tbody>
      </table>

      {безЦели.length > 0 && (
        <div className="v2-warn">
          Нужд без цели: {безЦели.length} — пока они есть, сцена 4 не закроется.
        </div>
      )}
    </div>
  )
}

export function Гейты({ phase, onPassed }: { phase: Phase; onPassed: () => void }) {
  const [отказ, setОтказ] = useState<string | null>(null)
  return (
    <div className="v2-card">
      <div className="v2-card__head">
        <span className="v2-card__title">Точки фазы</span>
        <span className="v2-card__count">{phase.gates.length}</span>
      </div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <table className="v2-table">
        <thead><tr><th>Точка</th><th>Дата</th><th>Состояние</th><th /></tr></thead>
        <tbody>
          {phase.gates.map((т) => (
            <tr key={т.key}>
              <td>{т.title}</td>
              <td className="v2-mono">{т.planned_date ?? '—'}</td>
              <td>
                {т.passed
                  ? <span className="v2-ok">пройдена</span>
                  : т.blocking.length === 0
                    ? <span>условия выполнены</span>
                    : <span className="v2-warn">держат: {т.blocking.join('; ')}</span>}
              </td>
              <td>
                <button type="button" disabled={т.passed || т.blocking.length > 0}
                  title={т.passed
                    ? 'точка уже зафиксирована'
                    : т.blocking.length > 0
                      ? `точку держат условия: ${т.blocking.join('; ')}`
                      : 'зафиксировать прохождение точки'}
                  onClick={() => {
                    setОтказ(null)
                    api.passGate(phase.project, т.key, 'стенд')
                      .then(() => onPassed())
                      .catch((e) => setОтказ(String(e.message ?? e)))
                  }}>
                  зафиксировать
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
