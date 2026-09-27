// Действия записи модели на сцене A6 (27.09, ответ владельца «блокеры Phase A»):
// прогон и верификация — не правка полей, а действия со своими правилами
// (снимок входов; верификация — после прогона и с основанием). Строка модели
// их называет пиктограммой; форма — под таблицей, словами истины.
//
// Выход прогона — величина с единицей справочника (истина: `outputs:
// [{key*, measure*}] ≥1`): текстом «прогон прошёл» ответ модели не считался.
import { useEffect, useState } from 'react'
import { api, type KindSpec, type ModelRow, type UnitRow } from './api'
import { useАвтор } from './research'

type Выход = { key: string; value: string; unit: string }

export function ДействиеМодели({ project, модель, вид, onDone, onClose }: {
  project: string
  модель: ModelRow
  вид: 'прогон' | 'верификация'
  onDone: () => void
  onClose: () => void
}) {
  const [автор] = useАвтор()
  const [единицы, setЕдиницы] = useState<UnitRow[]>([])
  const [спец, setСпец] = useState<KindSpec | null>(null)
  const [выходы, setВыходы] = useState<Выход[]>([{ key: '', value: '', unit: '' }])
  const [проверка, setПроверка] = useState({ status: 'verified', note: '' })
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  useEffect(() => {
    api.units().then((r) => setЕдиницы(r.items)).catch(() => setЕдиницы([]))
    api.kind('system_model').then(setСпец).catch(() => setСпец(null))
  }, [])

  const годные = выходы.filter((в) => в.key.trim() && в.value.trim() && в.unit)
  const числа = годные.every((в) => !Number.isNaN(Number(в.value.replace(',', '.'))))
  const помехаПрогона = годные.length === 0 ? 'нет ни одного выхода: имя, число и единица' : !числа ? 'значение выхода — число' : null
  const помехаПроверки = !проверка.note.trim() ? 'основание не названо: верификация без основания — не решение' : !модель.last_run ? 'модель ещё не дала ответа: сначала прогон' : null
  const сделать = (ход: Promise<unknown>) => {
    setЗанято(true); setОтказ(null)
    ход.then(onDone).catch((e) => setОтказ(String((e as Error).message ?? e))).finally(() => setЗанято(false))
  }

  return (
    <div className="v2-panel" data-why="следующий-клик" aria-label={`${вид} модели ${модель.code}`}>
      <h3>{вид === 'прогон' ? 'Прогон' : 'Верификация'} модели {модель.code} · {модель.name}</h3>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {вид === 'прогон' ? (
        <div className="v2-form">
          {выходы.map((в, i) => (
            <div key={i} className="v2-form v2-form--row">
              <label className="v2-inline">выход
                <input aria-label={`имя выхода ${i + 1}`} autoComplete="off" value={в.key} placeholder="coverage_smp"
                  onChange={(e) => setВыходы(выходы.map((х, j) => (j === i ? { ...х, key: e.target.value } : х)))} />
              </label>
              <label className="v2-inline">значение
                <input aria-label={`значение выхода ${i + 1}`} autoComplete="off" value={в.value} placeholder="87"
                  onChange={(e) => setВыходы(выходы.map((х, j) => (j === i ? { ...х, value: e.target.value } : х)))} />
              </label>
              <label className="v2-inline">единица
                <select aria-label={`единица выхода ${i + 1}`} value={в.unit}
                  onChange={(e) => setВыходы(выходы.map((х, j) => (j === i ? { ...х, unit: e.target.value } : х)))}>
                  <option value="">— единица справочника —</option>
                  {единицы.map((е) => <option key={е.code} value={е.code}>{е.label}</option>)}
                </select>
              </label>
            </div>
          ))}
          <div className="v2-form__actions">
            <button type="button" className="v2-link" title="ещё один выход модели"
              onClick={() => setВыходы([...выходы, { key: '', value: '', unit: '' }])}>+ ещё выход</button>
            <button type="button" className="v2-primary" disabled={Boolean(помехаПрогона) || занято}
              title={помехаПрогона ?? 'записать прогон: снимок входов и выходы-величины'}
              onClick={() => сделать(api.runModel(project, модель.code,
                годные.map((в) => ({ key: в.key.trim(), measure: { value: Number(в.value.replace(',', '.')), unit: в.unit } })),
                автор || 'инженер'))}>
              {занято ? 'Записываю…' : 'Записать прогон'}
            </button>
            <button type="button" className="v2-link" title="закрыть, ничего не записывая" onClick={onClose}>отменить</button>
          </div>
        </div>
      ) : (
        <div className="v2-form v2-form--row">
          <label className="v2-inline">итог
            <select aria-label="статус верификации" value={проверка.status} onChange={(e) => setПроверка({ ...проверка, status: e.target.value })}>
              {['verified', 'validated'].map((к) => <option key={к} value={к}>{спец?.enum_labels?.verification_status?.[к] ?? к}</option>)}
            </select>
          </label>
          <label className="v2-inline">основание
            <input aria-label="основание верификации" autoComplete="off" value={проверка.note} placeholder="сверено с эталоном spec/reference"
              onChange={(e) => setПроверка({ ...проверка, note: e.target.value })} />
          </label>
          <button type="button" className="v2-primary" disabled={Boolean(помехаПроверки) || занято}
            title={помехаПроверки ?? 'записать верификацию модели с основанием'}
            onClick={() => сделать(api.verifyModel(project, модель.code, проверка.status, проверка.note.trim(), автор || 'инженер'))}>
            {занято ? 'Записываю…' : 'Верифицировать'}
          </button>
          <button type="button" className="v2-link" title="закрыть, ничего не записывая" onClick={onClose}>отменить</button>
        </div>
      )}
    </div>
  )
}
