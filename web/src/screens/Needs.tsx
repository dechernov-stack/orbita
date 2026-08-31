// Экран 1 — нужды стейкхолдеров (Ш1 мастера) и ввод их руками (шаг 15).
//
// Разрыв трассировки (нужда без сервисов) определён сервером: клиент только
// показывает признак, а не решает, что считать разрывом.
//
// Композиция — из макета `docs/ui/stitch/stakeholder-map-needs`: список слева,
// панель определения нужды справа, действие «добавить» на виду. Четыре дефекта
// прежнего экрана (шаг 15 §2) исправлены здесь и в `tokens.css`:
//   1) формулировка больше не обрезается при свободном месте справа;
//   2) роль выводится подписью, а не кодом `operator`;
//   3) правая панель не перекрывает колонку таблицы;
//   4) панель не повторяет поля строки, а даёт работать с объектом.
import React, { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { NeedRow } from '../api/types'
import { STATUS_MEANING } from '../ui/maturity'
import { ObjectEditor } from '../ui/ObjectEditor'
import { StatusDot } from '../ui/parts'
import { SortTh, useSort } from '../ui/sort'
import { useSession } from '../ui/session'

export function Needs() {
  const { label, author } = useSession()
  const [rows, setRows] = useState<NeedRow[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [onlyOrphans, setOnlyOrphans] = useState(false)
  const [massStatus, setMassStatus] = useState('Preliminary')
  const [massReport, setMassReport] = useState<string | null>(null)
  const [massBusy, setMassBusy] = useState(false)

  const reload = useCallback(
    () =>
      api
        .needs()
        .then((next) => {
          setRows(next)
          setError(null)
        })
        .catch((e) => setError(String(e))),
    [],
  )

  useEffect(() => {
    void reload()
  }, [reload])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!rows) return <div className="empty">Загрузка…</div>

  const shown = onlyOrphans ? rows.filter((r) => r.services.length === 0) : rows

  // Сортировка заголовком (§2.4): клиентская, по загруженному
  const { sorted, sort, toggle } = useSort(shown, {
    id: (r) => r.id,
    statement: (r) => r.statement,
    stakeholder: (r) => r.stakeholder,
    role: (r) => r.role,
    priority: (r) => r.priority,
    services: (r) => r.services.length,
    status: (r) => r.status,
  })

  /** Редактор нужды — один узел: раскрывается ВНИЗ, как в прочих реестрах. */
  const editor = (
    <ObjectEditor
      kind="need"
      schemaName="core/need"
      title="нужда стейкхолдера"
      id={creating ? null : selected}
      onSaved={(id) => {
        setCreating(false)
        setSelected(id)
        void reload()
      }}
      onCancelled={() => {
        setCreating(false)
        setSelected(null)
        void reload()
      }}
    />
  )

  return (
    <div className="registry">
      <div className="pane">
        <div className="pane__tools">
          <button
            type="button"
            className="tab tab--primary"
            onClick={() => {
              setCreating(true)
              setSelected(null)
            }}
          >
            + Добавить нужду
          </button>
          <button
            type="button"
            className="tab"
            aria-selected={onlyOrphans}
            onClick={() => setOnlyOrphans((v) => !v)}
          >
            Без сервисов
          </button>
          {/* Массовый перевод зрелости — как в шапке реестров (§3.2): прежде
              экран позволял переводить только по одной нужде через инспектор
              (находка живой сессии, п. 5). */}
          {rows.length > 0 && (
            <>
              <span className="secondary" style={{ marginLeft: 'auto' }}>статус:</span>
              <select
                value={massStatus}
                onChange={(e) => setMassStatus(e.target.value)}
                title={STATUS_MEANING[massStatus]}
              >
                {['Preliminary', 'Approved', 'Baseline'].map((st) => (
                  <option key={st} value={st} title={STATUS_MEANING[st]}>
                    {label('lifecycle', st)}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="tab"
                disabled={!author || massBusy}
                title={author ? STATUS_MEANING[massStatus] : 'представьтесь в шапке'}
                onClick={() => {
                  const ids = shown
                    .filter((r) => r.status !== massStatus && r.status !== 'Cancelled')
                    .map((r) => r.id)
                  if (ids.length === 0) {
                    setMassReport('переводить нечего')
                    return
                  }
                  setMassBusy(true)
                  api
                    .promoteBatch(ids, massStatus, author)
                    .then((r) => {
                      setMassReport(
                        `переведено ${r.promoted.length}; отказов ${r.failed.length}` +
                          (r.failed.length
                            ? ` — ${r.failed.slice(0, 3).map((f) => `${f.id}: ${f.reason}`).join('; ')}`
                            : ''),
                      )
                      void reload()
                    })
                    .catch((e) => setMassReport(String(e)))
                    .finally(() => setMassBusy(false))
                }}
              >
                {massBusy ? 'Перевод…' : 'Перевести все видимые'}
              </button>
              {massReport && <span className="secondary">{massReport}</span>}
            </>
          )}
        </div>

        {/* создание — формой на месте, над таблицей: тот же приём, что в
            реестрах с раскрытием вниз, никакой боковой панели */}
        {creating && (
          <div className="rr-expand" style={{ display: 'block', padding: '10px 14px 12px', maxWidth: 760 }}>
            {editor}
          </div>
        )}

        {rows.length === 0 && (
          <div className="empty">
            Нужд пока нет. С них начинается проект: нажмите «Добавить нужду» — форма построена
            по схеме и проверяется теми же правилами, что импорт и предложения ИИ.
          </div>
        )}

        {rows.length > 0 && (
          <div style={{ overflowX: 'auto' }}>
          {/* min-width: при узкой панели фиксированные колонки съедали всю
              ширину, и формулировка сжималась в столбик по букве на строку
              (находка живой сессии); прокрутка честнее нечитаемой колонки. */}
          <table style={{ minWidth: 720 }}>
            <thead>
              {/* Формулировка — главное содержимое строки: она БЕЗ ширины
                  и переносится по словам, забирая остаток. При table-layout:
                  fixed остаток и есть всё, что не заняли колонки с шириной
                  (измерено: процент такой колонке не помогает — она всё равно
                  получает остаток), поэтому вспомогательные колонки урезаны
                  до необходимого: их сумма — бюджет, который нельзя проедать.
                  Прежде формулировка имела фиксированные 320 px и обрезалась
                  многоточием при свободном месте справа (шаг 15 §2, дефект 1). */}
              <tr>
                <SortTh label="ID" sortKey="id" sort={sort} onToggle={toggle} width={90} />
                <SortTh label="Формулировка" sortKey="statement" sort={sort} onToggle={toggle} />
                <SortTh label="Стейкхолдер" sortKey="stakeholder" sort={sort} onToggle={toggle} width={140} />
                <SortTh label="Роль" sortKey="role" sort={sort} onToggle={toggle} width={120} />
                <SortTh label="Приоритет" sortKey="priority" sort={sort} onToggle={toggle} width={70} />
                <SortTh label="Сервисы" sortKey="services" sort={sort} onToggle={toggle} width={110} />
                <SortTh label="Статус" sortKey="status" sort={sort} onToggle={toggle} width={95} />
              </tr>
            </thead>
            <tbody>
              {sorted.map((row) => (
                <React.Fragment key={row.id}>
                <tr
                  aria-selected={row.id === selected}
                  onClick={() => {
                    // второй щелчок по строке закрывает правку — как в реестрах
                    setSelected(selected === row.id ? null : row.id)
                    setCreating(false)
                  }}
                >
                  <td>
                    <span className="id">{row.id}</span>
                  </td>
                  <td className="wrap">{row.statement}</td>
                  <td className="wrap">{row.stakeholder}</td>
                  <td className="secondary">{label('stakeholder_role', row.role)}</td>
                  <td className="num">{row.priority}</td>
                  <td>
                    {row.services.length === 0 ? (
                      <span className="warn">△ нет сервисов</span>
                    ) : (
                      row.services.map((s) => (
                        <span key={s} className="chip">
                          {s}
                        </span>
                      ))
                    )}
                  </td>
                  <td>
                    <StatusDot status={row.status} />
                    <span className="secondary">{label('lifecycle', row.status)}</span>
                  </td>
                </tr>
                {/* правка раскрывается вниз — как в реестрах требований и целей */}
                {selected === row.id && !creating && (
                  <tr className="rr-expand">
                    <td colSpan={7}>
                      <div style={{ maxWidth: 760 }}>{editor}</div>
                    </td>
                  </tr>
                )}
                </React.Fragment>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </div>


    </div>
  )
}
