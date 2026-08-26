// Полки библиотеки (круг 2 стартового потока §3): раздел «Библиотека» —
// область первого ранга. Обзор А1–Г2 по ФАКТИЧЕСКОМУ наполнению: пустая
// полка честно видна пустой. Правка полок — своими экранами по эталонам;
// здесь — состав и счётчики.
import { useEffect, useState } from 'react'
import { api } from '../api/client'

interface Row {
  id: string
  title?: string
}

const SHELF_LABEL: Record<string, string> = {
  B1: 'Б1 · Типовые требования',
  B2: 'Б2 · Шаблоны компонентов',
  B5: 'Б5 · Фрагменты PBS',
  B6: 'Б6 · Фрагменты WBS',
  B7: 'Б7 · Типовые интерфейсы',
  G1: 'Г1 · Заготовки профилей',
}

export function Shelves() {
  const [normative, setNormative] = useState<Row[] | null>(null)
  const [classes, setClasses] = useState<Row[] | null>(null)
  const [stakeholders, setStakeholders] = useState<Row[] | null>(null)
  const [risks, setRisks] = useState<Row[] | null>(null)
  const [templates, setTemplates] = useState<Row[] | null>(null)
  const [fragments, setFragments] = useState<Array<{
    id: string; name: string; shelf: string; counters: Record<string, number>
  }> | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    api.libraryObjects('normative_document').then(setNormative).catch((e) => setFailure(String(e)))
    api.libraryObjects('mission_class').then(setClasses).catch(() => setClasses([]))
    api.libraryObjects('stakeholder_profile').then(setStakeholders).catch(() => setStakeholders([]))
    api.libraryObjects('typical_risk').then(setRisks).catch(() => setRisks([]))
    api.libraryObjects('document_template').then(setTemplates).catch(() => setTemplates([]))
    api.libraryShelves().then(setFragments).catch(() => setFragments([]))
  }, [])

  if (failure) return <div className="empty">Ошибка обращения к API: {failure}</div>

  const shelf = (title: string, hint: string, rows: Row[] | null, empty: string) => (
    <div className="card" style={{ marginBottom: 10 }}>
      <h3>{title} <span className="count">{rows?.length ?? '…'}</span></h3>
      <p className="secondary" style={{ margin: '2px 0 6px' }}>{hint}</p>
      {rows != null && rows.length === 0 && <div className="secondary">{empty}</div>}
      {(rows ?? []).map((r) => (
        <div key={r.id} className="sp-file" style={{ padding: '3px 0' }}>
          <span className="mono">{r.id}</span>
          <span>{r.title ?? ''}</span>
        </div>
      ))}
    </div>
  )

  return (
    <>
      <div className="toolbar">
        <h2>Полки библиотеки</h2>
        <span className="secondary">область LIB · переиспользуемое между проектами</span>
      </div>
      <div className="workarea" style={{ padding: 14, columnWidth: 420, columnGap: 14 }}>
        {shelf('А1 · Нормативная база', 'реквизиты и пункты — основания требований',
          normative, 'полка пуста')}
        {shelf('А2 · Стейкхолдеры', 'профили организаций — материал генерации О2; наполняется службой на акцепт',
          stakeholders, 'полка пуста — разбор записки миссии наполнит её (Ш2, «Разобрать»)')}
        {shelf('Б3 · Типовые риски', 'каталог рисков класса с митигациями',
          risks, 'полка пуста — разбор записки миссии наполнит её')}
        {shelf('Б4 · Классы миссий', 'ключ наборов: Ш1 выбирает класс, Ш2 предлагает его наборы',
          classes, 'полка пуста')}
        <div className="card" style={{ marginBottom: 10 }}>
          <h3>Фрагменты · Б1/Б2/Б5/Б6/Б7/Г1 <span className="count">{fragments?.length ?? '…'}</span></h3>
          <p className="secondary" style={{ margin: '2px 0 6px' }}>
            пачки с манифестом; «взять» — в Ш2 мастер-пути
          </p>
          {fragments != null && fragments.length === 0 && <div className="secondary">полка пуста</div>}
          {(fragments ?? []).map((f) => (
            <div key={f.id} className="sp-file" style={{ padding: '3px 0' }}>
              <span className="mono">{f.id}</span>
              <span>{SHELF_LABEL[f.shelf] ?? f.shelf}: {f.name}</span>
              <span className="secondary" style={{ marginLeft: 'auto' }}>
                {Object.entries(f.counters).map(([t, n]) => `${t}: ${n}`).join(' · ')}
              </span>
            </div>
          ))}
        </div>
        {shelf('В1 · Шаблоны документов', 'структуры разделов Д-комплектов; выпуск работает по ним',
          templates, 'полка пуста — залейте сид шаблонов')}
      </div>
    </>
  )
}
