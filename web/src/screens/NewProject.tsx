// Экран «Создать проект» — конвейер экранов, №1. Вёрстка и тексты — по
// замороженному эталону docs/ui/reference2/reference-create-project.html
// (круг 1): подписной элемент — лента контрольных точек выбранной фазы прямо
// в форме, даты вех в раскрывашке, «дата не задана» — законное состояние.
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import { useSession } from '../ui/session'

/** Точки фазы: код паспорта + подписи ленты (тексты — эталон, дословно). */
const PHASE_GATES: Record<string, Array<{ gate: string; nm: string; ph: string }>> = {
  pre_phase_a: [
    { gate: 'internal_review', nm: 'Внутренний обзор', ph: 'КТ-1' },
    { gate: 'MCR', nm: 'MCR', ph: 'КТ-2 · обзор концепции' },
    { gate: 'KDP-A', nm: 'KDP-A', ph: 'КТ-3 · решение о входе в Phase A' },
  ],
  phase_a: [
    { gate: 'SRR', nm: 'SRR', ph: 'КТ-1 · обзор требований' },
    { gate: 'SDR', nm: 'SDR/MDR', ph: 'КТ-2 · обзор определения' },
    { gate: 'KDP-B', nm: 'KDP-B', ph: 'КТ-3 · решение о входе в Phase B' },
  ],
}

const PHASE_HINT: Record<string, string> = {
  pre_phase_a: 'Pre-Phase A — концептуальные исследования от постановки задачи.',
  phase_a:
    'Старт с Phase A предполагает состоявшееся решение KDP-A — входы фазы (FAD, Formulation Agreement) загружаются документами.',
}

const PHASE_AFTER: Record<string, string> = {
  pre_phase_a:
    'После создания система откроет жизненный цикл и покажет первые операции фазы: О1 — инициирование, О2 — цели и нужды.',
  phase_a:
    'После создания система откроет жизненный цикл и покажет первые операции фазы: О1 — вход в фазу, О2 — SEMP.',
}

const PHASE_LABEL: Record<string, string> = { pre_phase_a: 'Pre-Phase A', phase_a: 'Phase A' }

/** Паспорт получает полный ряд Формулирования; даты — только у точек фазы. */
const PASSPORT_GATES: Record<string, string[]> = {
  pre_phase_a: ['internal_review', 'MCR', 'KDP-A', 'SRR', 'SDR', 'KDP-B'],
  phase_a: ['SRR', 'SDR', 'KDP-B'],
}

/** дд.мм.гггг → ISO для паспорта; иное — «дата не задана». */
function isoDate(raw: string): string | null {
  const m = raw.trim().match(/^([0-3][0-9])\.([01][0-9])\.([12][0-9]{3})$/)
  return m ? `${m[3]}-${m[2]}-${m[1]}` : null
}

export function NewProject({ firstRun, onDone, onCancel, onLoadFile }: {
  firstRun: boolean
  onDone: (id: string) => void
  onCancel: () => void
  onLoadFile: () => void
}) {
  const { author } = useSession()
  const [name, setName] = useState('')
  const [phase, setPhase] = useState('pre_phase_a')
  const [dates, setDates] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  /** Синхронный страж двойного нажатия: state обновляется асинхронно,
      и два клика в одном тике оба прошли бы проверку busy. */
  const busyRef = useRef(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [existing, setExisting] = useState<StoredSummary[]>([])
  const [clashPhase, setClashPhase] = useState<Record<string, string>>({})
  const nameRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    edit.list('project').then(setExisting).catch(() => setExisting([]))
    nameRef.current?.focus()
  }, [])

  // Совпадение имени — предупреждение с идентификатором и фазой существующего
  const clash = existing.find((p) => (p.title ?? '') === name.trim() && name.trim() !== '')
  useEffect(() => {
    if (clash && clashPhase[clash.id] === undefined) {
      edit.object(clash.id)
        .then((o) => setClashPhase((m) => ({ ...m, [clash.id]: String((o.doc as { phase?: string }).phase ?? '') })))
        .catch(() => setClashPhase((m) => ({ ...m, [clash.id]: '' })))
    }
  }, [clash, clashPhase])

  const create = () => {
    // повторное нажатие во время создания второй проект не создаёт
    if (busyRef.current || !name.trim() || !author) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    const dued = new Map(
      PHASE_GATES[phase].map((g) => [g.gate, isoDate(dates[g.gate] ?? '')] as const),
    )
    const milestones = PASSPORT_GATES[phase].map((g) => {
      const due = dued.get(g)
      return due ? { gate: g, due } : { gate: g }
    })
    edit.create('project', { name: name.trim(), phase, milestones }, author)
      .then((saved) => onDone(saved.id))
      .catch((e) => {
        // введённое не теряется: форма остаётся как была. Обрыв сети и
        // 502/503/504 прокси — одно состояние «сервер недоступен» (эталон);
        // содержательный отказ сервера показывается его собственным текстом.
        const gone = e instanceof TypeError ||
          (e instanceof ApiError && [502, 503, 504].includes(e.status))
        setFailure(
          gone
            ? 'сервер недоступен. Введённое сохранено на этой странице — повторите создание, когда связь восстановится.'
            : String((e as Error).message ?? e),
        )
      })
      .finally(() => { busyRef.current = false; setBusy(false) })
  }

  const gates = PHASE_GATES[phase]

  return (
    <div className="np-main">
      <div className="np-work">
        {firstRun && (
          <div className="np-first-run">
            Орбита ведёт космический проект через стадию Формулирования — от постановки задачи
            до решения KDP-B. Начните с первого проекта: он создастся пустым, и система
            подскажет первые операции.
          </div>
        )}
        <h2>{firstRun ? 'Первый проект' : 'Новый проект'}</h2>
        <div className="np-sub">
          {firstRun
            ? 'Достаточно имени — остальное можно задать позже.'
            : 'Проект создаётся пустым. Достаточно имени — остальное можно задать позже.'}
        </div>

        <div className="np-row">
          <label className="np-label" htmlFor="np-name">Наименование</label>
          <input className="np-name" id="np-name" ref={nameRef} value={name}
            placeholder="Например: «Национальная спутниковая платформа IoT»"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') create() }} />
          {!firstRun && <div className="np-hint">Идентификатор система назначит сама.</div>}
          {clash && (
            <div className="np-warn">
              Проект с таким именем уже есть — {clash.id}
              {clashPhase[clash.id] ? `, фаза ${PHASE_LABEL[clashPhase[clash.id]] ?? clashPhase[clash.id]}` : ''}.
              Создать всё равно можно: у нового будет свой идентификатор.
            </div>
          )}
        </div>

        <div className="np-row">
          <span className="np-label">Фаза старта</span>
          <span className="np-seg">
            {Object.entries(PHASE_LABEL).map(([k, v]) => (
              <button key={k} type="button" aria-selected={phase === k} onClick={() => setPhase(k)}>
                {v}
              </button>
            ))}
          </span>
          {!firstRun && <div className="np-seg-hint">{PHASE_HINT[phase]}</div>}
        </div>

        {!firstRun && (
          <div className="np-row">
            <div className="np-gates">
              <div className="np-k">Контрольные точки фазы</div>
              <div className="np-gline">
                {gates.map((g) => (
                  <div className="np-g" key={g.gate}>
                    <div className="np-dot" />
                    <div className="np-lnk" />
                    <div className="np-nm">{g.nm}</div>
                    <div className="np-ph">{g.ph}</div>
                  </div>
                ))}
              </div>
              <details className="np-dates">
                <summary>Задать плановые даты</summary>
                <div className="np-dates-grid np-gline">
                  {gates.map((g) => (
                    <div className="np-g" key={g.gate}>
                      <div className="np-dt">
                        <input placeholder="дд.мм.гггг" value={dates[g.gate] ?? ''}
                          onChange={(e) => setDates({ ...dates, [g.gate]: e.target.value })} />
                      </div>
                    </div>
                  ))}
                </div>
                <div className="np-hint">
                  Дата не задана — законное состояние; даты правятся в паспорте проекта.
                </div>
              </details>
            </div>
          </div>
        )}

        {failure && (
          <div className="np-err"><b>Проект не создан:</b> {failure}</div>
        )}

        <div className="np-actions">
          <button className="np-btn np-pri" disabled={!name.trim() || busy || !author}
            title={author ? '' : 'представьтесь в шапке'} onClick={create}>
            Создать проект
          </button>
          {!firstRun && <button className="np-btn" onClick={onCancel}>Отмена</button>}
          <button className="np-linkish" onClick={onLoadFile}>или загрузить проект из файла</button>
        </div>
        {!firstRun && <div className="np-after">{PHASE_AFTER[phase]}</div>}
      </div>
    </div>
  )
}
