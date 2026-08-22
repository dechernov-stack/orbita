// Документы БП-PA из модели (TZ-OUT-001, шаг 16 §2.4).
//
// Документ — чистая функция модели: ручное дополнение текста не сохраняется,
// правка вносится в модель. Пустой раздел не выбрасывается — он остаётся на
// месте, а рядом стоит разрыв со словами регламента о том, что там должно
// быть: документ, из которого молча исчезли разделы, выглядит полным.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useSession } from '../ui/session'
import type { DocumentIssuesView, GeneratedDocumentView } from '../api/types'

export function Documents() {
  const [templates, setTemplates] = useState<Array<{ code: string; title: string; source: string }>>([])
  const [code, setCode] = useState('')
  const [doc, setDoc] = useState<GeneratedDocumentView | null>(null)
  const [issues, setIssues] = useState<DocumentIssuesView | null>(null)
  const [issueReport, setIssueReport] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { author } = useSession()

  const loadIssues = (c: string) => {
    api.documentIssues(c).then(setIssues).catch(() => setIssues(null))
  }

  useEffect(() => {
    api
      .documentTemplates()
      .then((rows) => {
        setTemplates(rows)
        if (rows.length > 0) setCode((cur) => cur || rows[0].code)
      })
      .catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!code) return
    setDoc(null)
    setIssueReport(null)
    api.document(code).then(setDoc).catch((e) => setError(String(e)))
    loadIssues(code)
  }, [code])

  // Выпуск (Шаг 17 C5): дата — аргумент; здесь её даёт действие инженера,
  // а слепок и объект выпуска делает сервер
  const issue = () => {
    setIssueReport(null)
    api
      .issueDocument(code, new Date().toISOString(), author)
      .then(() => {
        setIssueReport('выпущено')
        loadIssues(code)
      })
      .catch((e) => setIssueReport(String(e).slice(0, 200)))
  }

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  return (
    <div className="pane" style={{ gridArea: 'main', overflow: 'auto', padding: 16 }}>
      <div className="tabs" style={{ marginBottom: 8 }}>
        {templates.map((t) => (
          <button key={t.code} className="tab" aria-selected={t.code === code} onClick={() => setCode(t.code)}>
            {t.title}
          </button>
        ))}
      </div>

      {!doc ? (
        <div className="secondary">Сборка документа…</div>
      ) : (
        <>
          <h2 style={{ fontSize: 15, margin: '4px 0' }}>
            {doc.body.title} <span className="secondary">· {doc.body.source}</span>
          </h2>
          <p className="secondary" style={{ marginTop: 0 }}>
            Слепок содержимого <span className="mono">{doc.digest.slice(0, 16)}</span> — тот же
            вход даёт тот же документ. Правка вносится в модель, не в текст.
          </p>
          <div className="field">
            <button type="button" className="tab tab--primary" disabled={!author} onClick={issue}
              title={author ? 'зафиксировать слепок текущей генерации' : 'представьтесь в шапке'}>
              Выпустить
            </button>
            {issueReport && <span className="secondary"> {issueReport}</span>}
          </div>
          {issues && issues.issues.length > 0 && (
            <table style={{ marginBottom: 8 }}>
              <thead>
                <tr>
                  <th style={{ width: 90 }}>Выпуск</th>
                  <th style={{ width: 180 }}>Дата</th>
                  <th style={{ width: 100 }}>Статус</th>
                  <th style={{ width: 90 }}>Разрывов</th>
                  <th>Слепок</th>
                </tr>
              </thead>
              <tbody>
                {issues.issues.map((i) => (
                  <tr key={i.id}>
                    <td className="mono">{i.id}</td>
                    <td className="mono">{i.issued_at}</td>
                    <td>{i.status}</td>
                    <td className="num">{i.gaps}</td>
                    <td className="mono">
                      {i.digest.slice(0, 12)}
                      {/* расхождение слепков — факт, а не ощущение */}
                      {i.stale && <span className="warn"> устарел: модель ушла вперёд</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {doc.gaps.length > 0 && (
            <div className="warn" style={{ padding: 8 }}>
              Разрывы ({doc.gaps.length}):{' '}
              {doc.gaps.map((g) => `§${g.section} — ${g.what}: ${g.expected}`).join('; ')}
            </div>
          )}
          {doc.body.sections.map((s) => (
            <div key={s.number} style={{ marginTop: 12 }}>
              <h3 style={{ fontSize: 13, marginBottom: 4 }}>
                {s.number}. {s.title}
              </h3>
              {s.items.length === 0 ? (
                <div className="empty" style={{ padding: 8 }}>
                  Раздел пуст. Регламент ожидает: {s.expects}
                </div>
              ) : (
                <table>
                  <tbody>
                    {s.items.map((item, i) => (
                      <tr key={i}>
                        <td className="mono" style={{ width: 100 }}>
                          {String(item.id ?? item.ref ?? i + 1)}
                        </td>
                        <td className="wrap">
                          {String(item.text ?? item.statement ?? item.title ?? JSON.stringify(item))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ))}
        </>
      )}
    </div>
  )
}
