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
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { NeedRow } from '../api/types'
import { ObjectEditor } from '../ui/ObjectEditor'
import { StatusDot } from '../ui/parts'
import { useSession } from '../ui/session'

export function Needs() {
  const { label } = useSession()
  const [rows, setRows] = useState<NeedRow[] | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [onlyOrphans, setOnlyOrphans] = useState(false)

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

  return (
    <div className="split">
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
        </div>

        {rows.length === 0 && (
          <div className="empty">
            Нужд пока нет. С них начинается проект: нажмите «Добавить нужду» — форма построена
            по схеме и проверяется теми же правилами, что импорт и предложения ИИ.
          </div>
        )}

        {rows.length > 0 && (
          <table>
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
                <th style={{ width: 90 }}>ID</th>
                <th>Формулировка</th>
                <th style={{ width: 140 }}>Стейкхолдер</th>
                <th style={{ width: 120 }}>Роль</th>
                <th style={{ width: 70 }}>Приоритет</th>
                <th style={{ width: 110 }}>Сервисы</th>
                <th style={{ width: 95 }}>Статус</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row) => (
                <tr
                  key={row.id}
                  aria-selected={row.id === selected}
                  onClick={() => {
                    setSelected(row.id)
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
              ))}
            </tbody>
          </table>
        )}
      </div>

      <aside className="pane pane--side">
        {creating || selected ? (
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
              setSelected(null)
              void reload()
            }}
          />
        ) : (
          <div className="secondary">
            Выберите нужду для правки или добавьте новую.
          </div>
        )}
      </aside>
    </div>
  )
}
