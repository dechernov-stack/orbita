// Новое требование: форма по шаблону EARS и истине вида.
import { useEffect, useState } from 'react'
import { api, type LintNote } from '../api'
import { ФОРМЫ, НОСИТЕЛЬ, useВидТребования } from './common'
import { Величина, Помета } from './card'

/** Форма требования: шаблон EARS в форме, линт — пометами по ходу. */
export function Форма({ project, onAdded, схема }: {
  project: string; onAdded: () => void; схема: ReturnType<typeof useВидТребования>
}) {
  const [поля, setПоля] = useState({
    code: '', level: 'system', title: '', statement: '', category: 'functional',
    carrier: '', verification_method: 'test', acceptance_criteria: '',
  })
  const [шаблон, setШаблон] = useState('ubiquitous')
  // Показатель и здесь ставится ПАРОЙ с единицей справочника: в форме нового
  // требования поля величины не было вовсе — владелец 19.09 искал, где ввести
  // показатель, и «единиц измерения нет» было буквально так.
  const [показатель, setПоказатель] = useState<Record<string, unknown> | null>(null)
  const [неполна, setНеполна] = useState<string | null>(null)
  const [пометы, setПометы] = useState<LintNote[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  /** Источник обязателен: требование ниоткуда не выводится. */
  const [источники, setИсточники] = useState<{ kind: string; id: string; подпись: string }[]>([])
  const [источник, setИсточник] = useState('')

  useEffect(() => {
    const собрать = async () => {
      const пары = await Promise.all(
        (['goal', 'need', 'constraint'] as const).map(async (вид) => {
          const r = await api.entities(project, вид)
          return r.items.map((с) => ({
            kind: вид,
            id: с.id,
            подпись: `${с.code} · ${String(с.doc.statement ?? с.doc.text ?? с.doc.name ?? '')}`.slice(0, 70),
          }))
        }),
      )
      setИсточники(пары.flat())
    }
    собрать().catch(() => undefined)
  }, [project])

  useEffect(() => {
    if (!поля.statement.trim()) { setПометы([]); return }
    const таймер = setTimeout(() => {
      api.lint(поля.statement, шаблон).then((r) => setПометы(r.notes)).catch(() => undefined)
    }, 400)
    return () => clearTimeout(таймер)
  }, [поля.statement, шаблон])

  const форма = ФОРМЫ[шаблон] ?? ФОРМЫ.ubiquitous
  const уровень = НОСИТЕЛЬ[поля.level] ?? НОСИТЕЛЬ.system

  const завести = () => {
    setЗанято(true); setОтказ(null)
    const выбран = источники.find((и) => и.id === источник)
    api.addRequirement(project, {
      ...поля,
      ears_pattern: шаблон,
      ...(показатель ? { measure: показатель } : {}),
      source: выбран ? [{ kind: выбран.kind, ref: выбран.id }] : [],
    })
      .then(() => { setПоля({ ...поля, code: '', title: '', statement: '' }); onAdded() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card">
      <div className="v2-card__head"><span className="v2-card__title">Новое требование</span></div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      <div className="v2-form">
        <label>Уровень
          <select value={поля.level} onChange={(e) => setПоля({ ...поля, level: e.target.value })}>
            {схема.значения('level').map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
          </select>
        </label>
        <label>Носитель ({уровень.слова})
          <input value={поля.carrier} onChange={(e) => setПоля({ ...поля, carrier: e.target.value })}
            placeholder={поля.level === 'interface' ? 'IF-DATA-BUS' : 'OBC-CPU'} />
        </label>
        <label>Заголовок
          <input value={поля.title} onChange={(e) => setПоля({ ...поля, title: e.target.value })} />
        </label>
        <label>Шаблон формулировки
          <select value={шаблон} onChange={(e) => setШаблон(e.target.value)}>
            {схема.значения('ears_pattern').map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
          </select>
        </label>
        <label>Формулировка
          <textarea rows={2} value={поля.statement} placeholder={форма}
            autoComplete="off" onChange={(e) => setПоля({ ...поля, statement: e.target.value })} />
        </label>
        <span className="v2-empty__why">Форма: {форма}</span>
        {пометы.map((n) => <Помета key={n.rule + n.what} note={n} />)}
        <label>Источник (откуда выведено)
          <select value={источник} onChange={(e) => setИсточник(e.target.value)}>
            <option value="">— выберите цель, нужду или ограничение —</option>
            {источники.map((и) => <option key={и.id} value={и.id}>{и.подпись}</option>)}
          </select>
        </label>
        <label>{схема.имя('category')}
          <select name="новое.category" value={поля.category}
            onChange={(e) => setПоля({ ...поля, category: e.target.value })}>
            {схема.значения('category').map((з) => <option key={з.код} value={з.код}>{з.имя}</option>)}
          </select>
        </label>
        <Величина подпись={`${схема.имя('measure')}${схема.примечание('measure') ? ` · ${схема.примечание('measure')}` : ''}`}
          код="новое" было={null} единицы={схема.единицы} почемуБезЕдиниц={схема.почемуБезЕдиниц}
          операторы={схема.вид?.measure_ops ?? {}}
          onChange={setПоказатель} onНеполна={setНеполна} />
        {неполна && <span className="v2-empty__why">{неполна} — показатель не запишется, остальное запишется</span>}
        <label>Критерий приёмки
          <input value={поля.acceptance_criteria}
            onChange={(e) => setПоля({ ...поля, acceptance_criteria: e.target.value })}
            placeholder="наблюдаемый результат: что считать выполнением" />
        </label>
        <div className="v2-form__actions">
          <button type="button" className="v2-primary"
            disabled={занято || !поля.statement.trim() || !поля.carrier.trim() || !источник}
            title={!поля.carrier.trim()
              ? `укажите носителя: для этого уровня это ${уровень.слова}`
              : !источник
                ? 'выберите источник: требование ниоткуда не выводится'
                : 'завести требование; пометы линта не мешают черновику, но держат базирование'}
            onClick={завести}>
            {занято ? 'Завожу…' : 'Завести требование'}
          </button>
        </div>
      </div>
    </div>
  )
}
