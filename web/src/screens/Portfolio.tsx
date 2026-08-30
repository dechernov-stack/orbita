// О-9 «Портфель» (БРИФ-ПОРТФЕЛЬ, эталон reference-portfolio, круг 1):
// входная дверь — СТРОКИ, не плитки; сортировка по последней активности
// («что нового» читается сверху); в строке всё для решения «куда идти»:
// фаза · ближайшая точка со счётчиком · возврат красным маркером · путь
// начала чипом · последняя активность. Вся строка — цель клика. До 20
// проектов — без поиска, фильтров и группировок (бриф §5, §8).
//
// Данные приходят ОДНИМ запросом /views/portfolio, собранным и
// отсортированным сервером; клиент рисует.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { selectProject } from '../api/project'

type Row = Awaited<ReturnType<typeof api.portfolio>>['projects'][number]

const PHASE_LABEL: Record<string, string> = { pre_phase_a: 'Pre-Phase A', phase_a: 'Phase A' }
const DAY = 86400000

/** Относительное время — подпись вёрстки: «вчера», «3 дня назад», дата. */
function relTime(iso: string): string {
  const at = new Date(iso).getTime()
  const diff = Date.now() - at
  if (diff < DAY) return 'сегодня'
  if (diff < 2 * DAY) return 'вчера'
  for (let n = 2; n <= 6; n++) if (diff < (n + 1) * DAY) return `${n} дн. назад`
  if (diff < 14 * DAY) return 'неделю назад'
  for (let n = 2; n <= 4; n++) if (diff < (n + 1) * 7 * DAY) return `${n} нед. назад`
  const d = new Date(at)
  const dd = `${d.getDate()}`.padStart(2, '0')
  const mm = `${d.getMonth() + 1}`.padStart(2, '0')
  return `${dd}.${mm}.${d.getFullYear()}`
}

function projectsWord(n: number): string {
  const d10 = n % 10
  const d100 = n % 100
  if (d10 === 1 && d100 !== 11) return 'проект'
  if (d10 >= 2 && d10 <= 4 && (d100 < 12 || d100 > 14)) return 'проекта'
  return 'проектов'
}

export function Portfolio({ onOpen, onNew, onLoadFile, onStart }: {
  onOpen: () => void
  onNew: () => void
  onLoadFile: () => void
  onStart: () => void
}) {
  const [rows, setRows] = useState<Row[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.portfolio()
      .then((r) => setRows(r.projects))
      .catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (rows == null) return <div className="empty">Загрузка портфеля…</div>

  // Первая установка — приветствие, не пустая таблица (бриф §7, эталон S2)
  if (rows.length === 0) {
    return (
      <div className="pf-welcome">
        <span className="o2" />
        <h2>Орбита</h2>
        <p>
          Система ведёт космический проект через стадию Формулирования — от постановки
          задачи до решения KDP-B: требования, состав, документы, контрольные точки и
          служба ИИ под ограничениями инженера. Начните с первого проекта — он создастся
          пустым, и система подскажет первые шаги.
        </p>
        <div className="row">
          <button className="btn btn--primary" onClick={onNew}>Создать первый проект</button>
          <button className="np-linkish" onClick={onLoadFile}>или загрузить проект из файла</button>
        </div>
      </div>
    )
  }

  const open = (r: Row) => { selectProject(r.id); onOpen() }

  return (
    <>
      <div className="toolbar">
        <h2>Портфель</h2>
        <span className="secondary">{rows.length} {projectsWord(rows.length)}</span>
        <div className="grow" />
        <button className="np-linkish" onClick={onLoadFile}>загрузить из файла</button>
        <button className="btn btn--primary" onClick={onNew}>Создать проект</button>
      </div>
      <div className="workarea">
        {rows.map((r) => (
          <button key={r.id} className="pf-row" onClick={() => open(r)}>
            <span className="pf-nm">
              <span className="nm">{r.name}</span>
              <span className="own" style={{ display: 'block' }}>руководитель: {r.owner}</span>
            </span>
            <span className="chip">{PHASE_LABEL[r.phase] ?? r.phase}</span>
            <span className="pf-gate">
              {r.gate ? (
                <>
                  <span className="k">ближайшая</span>
                  <b>{r.gate.label}</b>
                  {r.return
                    ? <span className="pf-ret">возврат: {r.return.reason}</span>
                    : r.gate.open_count !== undefined && (
                      <span className={`pf-cnt${r.gate.open_count === 0 ? ' zero' : ''}`}>
                        {r.gate.open_count === 0 ? 'готово' : `не закрыто · ${r.gate.open_count}`}
                      </span>
                    )}
                </>
              ) : (
                <span className="secondary">все точки пройдены</span>
              )}
            </span>
            {r.start_path?.status === 'in_progress' ? (
              <span
                className="pf-start"
                role="button"
                title="продолжить путь начала проекта"
                onClick={(e) => { e.stopPropagation(); selectProject(r.id); onStart() }}
              >
                начало: шаг {r.start_path.step} из 4 · продолжить
              </span>
            ) : r.start_path?.status === 'done' ? (
              <span className="pf-start done">начало пройдено</span>
            ) : null}
            {r.last_activity && (
              r.last_activity.service
                ? (
                  // без единой содержательной правки — тихая строка, без
                  // имени учётки (круг 2 портфеля §1.2)
                  <span className="pf-act">служебное обновление · {relTime(r.last_activity.at)}</span>
                )
                : (
                  <span className="pf-act">
                    <b>{relTime(r.last_activity.at)}</b> · {r.last_activity.author}
                    <br />
                    {r.last_activity.what}
                  </span>
                )
            )}
          </button>
        ))}
      </div>
    </>
  )
}
