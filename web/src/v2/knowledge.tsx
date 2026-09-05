// Поле знаний и загрузка с заданием (ИНТЕЛЛЕКТУАЛЬНАЯ-ЗАГРУЗКА).
//
// Экран показывает то, что система знает, и как она к этому пришла: факт
// несёт якорь и метку достоверности. План действий предъявляется ЦЕЛИКОМ и
// с предпросмотром последствий — принимают пакет, а не факты поштучно.
import { useEffect, useState } from 'react'
import { api, type FactRow, type IntakeResult } from './api'

const МЕТКА: Record<string, string> = {
  И: 'внутренний документ — наш материал',
  В: 'внешний источник, проверенный на указанную дату',
  П: 'предлагаемая цель или допущение, требующее подтверждения',
}

export function KnowledgeField({ project }: { project: string | null }) {
  const [факты, setФакты] = useState<FactRow[]>([])
  const [имя, setИмя] = useState('')
  const [текст, setТекст] = useState('')
  const [задание, setЗадание] = useState('разбери по сущностям')
  const [материал, setМатериал] = useState('')
  const [итог, setИтог] = useState<IntakeResult | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    if (!project) return
    api.facts(project).then((r) => setФакты(r.items)).catch(() => undefined)
  }, [project])

  if (!project) {
    return (
      <div className="v2-card">
        <div className="v2-empty">
          Проект не выбран.
          <span className="v2-empty__why">Знания живут в проекте: факты ссылаются на его материалы.</span>
        </div>
      </div>
    )
  }

  const положить = () => {
    setОтказ(null)
    api.putMaterial(project, { name: имя, kind: 'reference', text: текст })
      .then((r) => { setМатериал(r.code); setТекст('') })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  const собратьПлан = () => {
    setОтказ(null)
    api.intake(project, материал, задание)
      .then((r) => { setИтог(r); return api.facts(project).then((f) => setФакты(f.items)) })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <>
      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Загрузка с заданием</span>
        </div>
        {отказ && <div className="v2-locked">{отказ}</div>}
        <div className="v2-form">
          <label>Название материала
            <input value={имя} onChange={(e) => setИмя(e.target.value)} placeholder="Записка миссии" />
          </label>
          <label>Текст источника
            <textarea rows={3} value={текст} onChange={(e) => setТекст(e.target.value)}
              placeholder="вставьте текст либо приложите файл на следующем шаге" />
          </label>
          <div className="v2-form__actions">
            <button type="button" onClick={положить} disabled={!имя.trim()}
              title={имя.trim() ? 'сохранить материал в проекте' : 'дайте материалу название'}>
              Положить материал
            </button>
            {материал && <span className="v2-mono">{материал}</span>}
          </div>
          <label>Задание
            <input value={задание} onChange={(e) => setЗадание(e.target.value)}
              placeholder="разбери по сущностям · рассмотрим как базовую платформу · это норматив — заведи" />
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" onClick={собратьПлан} disabled={!материал}
              title={материал
                ? 'собрать план действий из фактов и задания'
                : 'сначала положите материал — плану нужен источник'}>
              Собрать план
            </button>
          </div>
        </div>
      </div>

      {итог && (
        <div className="v2-card">
          <div className="v2-card__head">
            <span className="v2-card__title">План действий</span>
            <span className="v2-card__count">{итог.plan.length}</span>
          </div>
          <p className="v2-empty__why">{итог.note}</p>
          {итог.plan.length > 0 && (
            <table className="v2-table">
              <thead><tr><th>Действие</th><th>Что появится</th></tr></thead>
              <tbody>
                {итог.plan.map((д) => (
                  <tr key={д.kind}><td>{д.title}</td><td>{д.effect}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Поле знаний</span>
          <span className="v2-card__count">{факты.length}</span>
        </div>
        {факты.length === 0 ? (
          <div className="v2-empty">
            Фактов пока нет.
            <span className="v2-empty__why">
              Факты появляются разбором материала: система их не выдумывает.
            </span>
          </div>
        ) : (
          <table className="v2-table">
            <thead><tr><th>Предмет</th><th>Что известно</th><th>Значение</th><th>Откуда</th></tr></thead>
            <tbody>
              {факты.map((ф) => (
                <tr key={ф.id}>
                  <td>{ф.subject}</td>
                  <td>{ф.predicate}</td>
                  <td className="v2-mono">{ф.value}{ф.unit ? ` ${ф.unit}` : ''}</td>
                  <td>
                    <span className="v2-mono">{ф.material}</span>
                    {ф.anchor && <span className="v2-mono"> [{ф.anchor}]</span>}
                    <span title={МЕТКА[ф.mark]}> · {ф.mark}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
