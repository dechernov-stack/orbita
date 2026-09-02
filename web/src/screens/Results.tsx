// Библиотека → «Результаты» (ШАБЛОН-SEMP, механика п. 8; задача «три
// пакета», шип 2.3): третий отдел рядом с Материалами и Полками — вход →
// работа → результат. Карточка выпуска: документ · версия · снимок печати
// (docx/PDF) · авторство: авторы текста из истории правок разделов +
// выпустивший + дата. Служба — происхождением черновиков, автором не бывает
// никогда: список авторов собирает сервер сторожем ServiceAuthors.
// Результат прошлого проекта — кандидат на «обобщить в образец»: нитка на
// полку шаблонов (вид template_extraction службы).
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ResultsView } from '../api/types'

function shortDate(iso: string): string {
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})/)
  return m ? `${m[3]}.${m[2]}.${m[1]}` : iso
}

export function Results({ onGo }: { onGo?: (screen: string, kind?: string, target?: string) => void }) {
  const [view, setView] = useState<ResultsView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.results().then(setView).catch((e) => setError(String(e)))
  }, [])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>
  if (view.cards.length === 0) {
    return (
      <div className="empty">
        {view.empty_why ?? 'Выпусков ещё нет.'} Результат появляется выпуском документа на экране
        «Документы»: слепок фиксируется, печать — docx и PDF, авторы — из истории текста.
      </div>
    )
  }

  return (
    <div className="results">
      <p className="secondary" style={{ marginTop: 0 }}>
        Выпуски документов проекта: документ · версия · снимок печати · кто писал и кто выпустил.
        Служба автором не бывает — только происхождением черновиков.
      </p>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 10 }}>
        {view.cards.map((c) => (
          <div key={c.issue} className="card" style={{ margin: 0 }}>
            <h3 style={{ marginTop: 0 }}>{c.title}</h3>
            <div className="secondary">
              <span className="mono">{c.issue}</span> · версия {c.version} · {shortDate(c.issued_at)}
              {c.stale && <span className="warn"> · модель ушла вперёд</span>}
              {c.gaps > 0 && <span className="warn"> · разрывов {c.gaps}</span>}
            </div>
            <div style={{ marginTop: 6 }}>
              <b>выпустил:</b> {c.issued_by}
            </div>
            <div>
              <b>авторы текста:</b>{' '}
              {c.authors.length === 0
                ? <span className="secondary">связных разделов в выпуске нет — документ собран из данных</span>
                : c.authors.map((a) => `${a.name} (§${a.sections.join(', §')})`).join('; ')}
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <a className="tab" href={api.printUrl(c.template, 'docx', c.issue)} title="снимок печати выпуска — Word">docx</a>
              <a className="tab" href={api.printUrl(c.template, 'pdf', c.issue)} title="снимок печати выпуска — PDF">PDF</a>
              {onGo && (
                <button type="button" className="tab" onClick={() => onGo('docs', undefined, c.template)}
                  title="открыть документ на экране «Документы»">
                  документ →
                </button>
              )}
              {onGo && (
                <button type="button" className="tab"
                  onClick={() => onGo('aiservice', 'template_extraction', `${c.template}#issue:${c.issue}`)}
                  title="обобщить выпуск в образец на полку шаблонов: служба предложит структуру, инженер примет">
                  обобщить в образец →
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
