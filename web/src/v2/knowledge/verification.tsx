// Эксперт-вкладка «Верификация» поля знаний (шип 5 §3.4): документ против
// поля — тезисы без оснований, числа против фактов, противоречия и
// устаревшее. Сама проверка — та же, что у документа в разделе «Документы»
// (`FieldCheck`), «как есть»: вторую реализацию не заводим.
import { useEffect, useState } from 'react'
import { api, type DocView } from '../api'
import { FieldCheck } from '../documents'

export function ВерификацияПоля({ project }: { project: string }) {
  const [документы, setДокументы] = useState<DocView[] | null>(null)
  const [код, setКод] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  const [куда, setКуда] = useState<string | null>(null)
  useEffect(() => {
    api.documents(project)
      .then((r) => { setДокументы(r.items); setКод((было) => было || r.items[0]?.code || '') })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  if (отказ) return <div className="v2-locked">{отказ}</div>
  if (!документы) return <div className="v2-empty">Читаю документы проекта…</div>
  if (документы.length === 0) return <div className="v2-empty">Документов проекта нет — проверять нечего.</div>
  const вид = документы.find((д) => д.code === код) ?? null
  return (
    <div data-why="работа" aria-label="верификация документа против поля">
      <div className="v2-chips">
        <label className="v2-inline">документ{' '}
          <select value={код} onChange={(e) => { setКод(e.target.value); setКуда(null) }} aria-label="документ для проверки против поля">
            {документы.map((д) => <option key={д.code} value={д.code}>{д.code} · {д.title}</option>)}
          </select>
        </label>
      </div>
      {куда && <div className="v2-note-line">элемент {куда} — раздел «Документы», документ {код}: там правится изложение</div>}
      {вид && <FieldCheck key={вид.code} project={project} code={вид.code} вид={вид} onJump={setКуда} />}
    </div>
  )
}
