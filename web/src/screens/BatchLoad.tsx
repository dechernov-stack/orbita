// Загрузка пачкой (блок D, дизайн: экран importb): файл → отчёт проверки до
// записи → результат. До подтверждения сервером в реестр ничего не пишется —
// «всё или ничего» держит транзакция импорта (ADR-024). Выгрузка — тем же
// форматом, выгруженное грузится обратно.
import { useState } from 'react'
import { api, asBatchReport, type BatchReport } from '../api/client'
import { currentProject, withProject } from '../api/project'
import { useSession } from '../ui/session'

export function BatchLoad() {
  const { author } = useSession()
  const [payload, setPayload] = useState('')
  const [fileName, setFileName] = useState<string | null>(null)
  const [report, setReport] = useState<BatchReport | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const pick = (file: File | null) => {
    if (!file) return
    setFileName(file.name)
    setReport(null)
    file.text().then(setPayload)
  }

  const run = async () => {
    setBusy(true)
    setFailure(null)
    setReport(null)
    try {
      const parsed = JSON.parse(payload) as unknown
      if (Array.isArray(parsed)) {
        throw new Error(
          'Это ответ канала ИИ (массив предложений), а не пачка. Вносите его на экране ' +
            '«Служба ИИ»: соберите промпт, вставьте файл в поле ответа контура и примите ' +
            'пачкой — так материал пройдёт фильтр и попадёт в журнал вызовов.',
        )
      }
      const body = parsed as Record<string, unknown>
      if (!Array.isArray(body.objects)) {
        throw new Error('В файле нет поля objects: пачка — это {"objects": [ … ]}.')
      }
      if (!body.author && !author) {
        throw new Error('Представьтесь в шапке: правка без автора не принимается (TZ-COM-005).')
      }
      if (!body.author) body.author = author
      setReport(await api.importObjects(body))
    } catch (e) {
      // 422 несёт тот же BatchReport построчно, что и успех (written: 0,
      // problems) — общий post() на отказе его теряет; достаём назад, иначе
      // инженер видит сырую строку вместо построчной таблицы замечаний.
      const parsed = asBatchReport(e)
      if (parsed) setReport(parsed)
      else setFailure(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="toolbar">
        <h2>Загрузка пачкой</h2>
        <div className="grow" />
        <a className="btn" href={withProject(api.exportObjectsUrl())} download="orbita-export.json">
          Выгрузить проект
        </a>
        <button title="вставьте пакет объектов в поле выше — кнопка оживёт" className="btn btn--primary" disabled={!payload.trim() || busy} onClick={run}>
          {busy ? 'Проверка…' : 'Проверить и загрузить'}
        </button>
      </div>
      <div className="workarea" style={{ padding: 14, maxWidth: 860 }}>
        <div className="field">
          <label>Файл пачки (формат /export/objects; порядок объектов не важен — его разрешает сервер)</label>
          <input type="file" accept="application/json,.json" onChange={(e) => pick(e.target.files?.[0] ?? null)} />
          {fileName && <span className="secondary"> {fileName}</span>}
        </div>
        <div className="field">
          <label>Или вставьте JSON пачки</label>
          <textarea rows={8} style={{ width: '100%' }} value={payload}
            onChange={(e) => { setPayload(e.target.value); setReport(null) }}
            placeholder={'{"objects": [ { "id": "ND-0001", … }, … ]}'} />
        </div>
        {!currentProject() && (
          <p className="hint secondary">
            Проект не выбран: пачка обязана нести объект проекта, иначе сервер откажет.
          </p>
        )}
        {failure && <div className="notice notice--blocked">Пачка не разобрана: {failure}</div>}
        {report && report.problems.length === 0 && (
          <div className="notice">Записано объектов: <b className="mono">{report.written}</b></div>
        )}
        {report && report.problems.length > 0 && (
          <div className="card">
            <h3>Пачка отклонена целиком — {report.problems.length} замечаний, ничего не записано</h3>
            <div>
              <table>
                <thead>
                  <tr>
                    <th style={{ width: 60 }}>Строка</th>
                    <th style={{ width: 90 }}>Объект</th>
                    <th style={{ width: 140 }}>Поле</th>
                    <th style={{ width: 120 }}>Правило</th>
                    <th>Что не так</th>
                  </tr>
                </thead>
                <tbody>
                  {report.problems.map((p, i) => (
                    <tr key={i}>
                      <td className="num">{p.index}</td>
                      <td className="id">{p.id ?? '—'}</td>
                      <td className="mono">{p.path ?? ''}</td>
                      <td className="mono">{p.rule ?? ''}</td>
                      <td className="wrap">{p.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </>
  )
}
