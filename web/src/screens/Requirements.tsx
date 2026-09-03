// Т-1 (О-12): реестр требований по эталону круга 4 — тулбар тремя строками,
// группировка со схлопыванием, дерево без дублей, раскрытие строки, карточка
// на всю рабочую область. Бокового инспектора требований больше нет (решение
// круга 2) — прежние переходы «к объекту» ведут в карточку.
//
// Клиент не досчитывает семантику: строки приходят из /views/requirement-tree
// со всем для отрисовки (носитель, родитель, вид, флаги помет); группировка и
// сортировка — представление тех же строк (requirementsView.ts, с тестами).
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import { requestObject, screenOfObject, takeObject } from '../api/intent'
import type { RequirementCard, RequirementRow, RequirementTreeView, SavedViewDoc } from '../api/types'
import { ObjectEditor } from '../ui/ObjectEditor'
import { RefPicker } from '../ui/RefPicker'
import { Select } from '../ui/Select'
import { useSession } from '../ui/session'
import { Tooltip } from '../ui/Tooltip'
import {
  buildItems, COLUMN_LABELS, defaultColumns, flatRows, gapCounters, GAP_LABELS,
  PROJECT_GROUP, visibleColumns,
  type ColumnState, type GapKey, type GroupKey, type RegistryItem, type SortState,
} from './requirementsView'

const GROUPINGS: Array<{ key: GroupKey; title: string }> = [
  { key: 'carrier', title: 'по носителю' },
  { key: 'level', title: 'по уровню' },
  { key: 'status', title: 'по статусу' },
  { key: 'owner', title: 'по владельцу' },
]

/* Ширины вспомогательных колонок: формулировка идёт без ширины и забирает
 * остаток — при table-layout: fixed это и есть бюджет её читаемости. */
const COLUMN_WIDTHS: Record<string, number> = {
  id: 96, kind: 92, mop: 132, status: 128, carrier: 110, verification: 30,
  parent: 90, category: 116, level: 100, version: 58, owner: 130, origin: 118,
}

type Mode =
  | { kind: 'registry' }
  | { kind: 'card'; id: string }
  | { kind: 'create'; template?: Record<string, unknown> }
  | { kind: 'edit'; id: string }

export function Requirements({ onGo }: { onGo?: (screen: string) => void }) {
  const { label, author } = useSession()
  const [tree, setTree] = useState<RequirementTreeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>({ kind: 'registry' })

  // состояние вида — ОДИН источник: таблица, конфигуратор и «сохранить» читают его
  const [columns, setColumns] = useState<ColumnState[]>(defaultColumns)
  const [sort, setSort] = useState<SortState | null>(null)
  const [form, setForm] = useState<'tree' | 'flat'>('tree')
  const [grouping, setGrouping] = useState<GroupKey | null>('carrier')
  const [gap, setGap] = useState<GapKey | null>(null)
  const [search, setSearch] = useState('')
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set())

  const [views, setViews] = useState<SavedViewDoc[]>([])
  const [activeView, setActiveView] = useState<string | null>(null)
  const [saveOpen, setSaveOpen] = useState(false)
  const [cfgOpen, setCfgOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const [selection, setSelection] = useState<ReadonlySet<string>>(new Set())
  const [massBusy, setMassBusy] = useState(false)

  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [activeId, setActiveId] = useState<string | null>(null)
  // ADR-045: два дерева слева — по иерархии и по документам-основаниям;
  // выбор узла сужает список, счётчики узлов считает клиент по флагам сервера
  const [treeMode, setTreeMode] = useState<'hierarchy' | 'documents'>('hierarchy')
  const [treeFocus, setTreeFocus] = useState<{ key: string; ids: ReadonlySet<string> } | null>(null)
  const [treeOpen, setTreeOpen] = useState<ReadonlySet<string>>(new Set())
  const dragKey = useRef<string | null>(null)

  const reload = useCallback(
    () => api.requirementTree().then((next) => { setTree(next); setError(null) }).catch((e) => setError(String(e))),
    [],
  )
  const reloadViews = useCallback(
    () => api.reqViews().then((r) => setViews(r.views)).catch(() => setViews([])),
    [],
  )
  useEffect(() => { void reload(); void reloadViews() }, [reload, reloadViews])

  // Переход «к объекту» с другого экрана (готовность, документы) — в карточку
  useEffect(() => {
    if (!tree) return
    const wanted = takeObject()
    if (!wanted) return
    if (tree.rows.some((r) => r.id === wanted)) setMode({ kind: 'card', id: wanted })
    else requestObject(wanted)
  }, [tree])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!tree) return <div className="empty">Загрузка…</div>

  const scopedRows = treeFocus ? tree.rows.filter((r) => treeFocus.ids.has(r.id)) : tree.rows
  const items = buildItems(scopedRows, { form, grouping, collapsed, gap, search, sort })
  const seq = flatRows(items)
  const counters = gapCounters(tree.rows)
  const subtreeIds = (id: string): Set<string> => {
    const out = new Set<string>([id])
    const walk = (x: string) => (tree.children[x] ?? []).forEach((k) => { out.add(k); walk(k) })
    walk(id)
    return out
  }
  const toggleTreeNode = (key: string) =>
    setTreeOpen((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  const focusTree = (key: string, ids: Set<string>) =>
    setTreeFocus((cur) => (cur?.key === key ? null : { key, ids }))
  const nodeCounters = (ids: ReadonlySet<string>) => {
    const rs = tree.rows.filter((r) => ids.has(r.id))
    return { n: rs.length, noMop: rs.filter((r) => r.kind === 'text').length, tbd: rs.filter((r) => r.hasTbd).length }
  }
  const counterText = (c: { n: number; noMop: number; tbd: number }) =>
    `${c.n}${c.noMop ? ` · ${c.noMop} без показателя` : ''}${c.tbd ? ` · ${c.tbd} TBD` : ''}`
  const rowsById = new Map(tree.rows.map((r) => [r.id, r]))
  const hierarchyNode = (id: string, depth: number): JSX.Element | null => {
    const r = rowsById.get(id)
    if (!r) return null
    const kids = tree.children[id] ?? []
    const ids = subtreeIds(id)
    const open = treeOpen.has(id) || depth === 0
    return (
      <div key={id}>
        <div
          className={`rr-tnode${treeFocus?.key === id ? ' on' : ''}`}
          style={{ paddingLeft: 6 + depth * 12 }}
          tabIndex={0}
          role="treeitem"
          aria-expanded={kids.length > 0 ? open : undefined}
          onClick={() => focusTree(id, ids)}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); focusTree(id, ids) } }}
        >
          {kids.length > 0 && (
            <button
              type="button"
              className="rr-tchev"
              title={open ? 'свернуть' : 'раскрыть'}
              onClick={(e) => { e.stopPropagation(); toggleTreeNode(id) }}
            >
              {open ? '▾' : '▸'}
            </button>
          )}
          <span className="mono">{id}</span> <span className="rr-ttitle">{r.title ?? r.statement}</span>
          <span className="rr-tcnt">{counterText(nodeCounters(ids))}</span>
        </div>
        {open && kids.map((k) => hierarchyNode(k, depth + 1))}
      </div>
    )
  }
  const renderCard = (id: string) => (
    <CardView
      id={id}
      seq={seq}
      rows={tree.rows}
      childrenMap={tree.children}
      systemRoot={tree.systemRoot}
      compositionRoots={tree.compositionRoots}
      inline
      onBack={() => setExpandedId(null)}
      onOpen={(x) => { setActiveId(x); setExpandedId(x) }}
      onEdit={() => setMode({ kind: 'edit', id })}
      onCreate={(template) => setMode({ kind: 'create', template })}
      onGo={onGo}
      onChanged={() => void reload()}
    />
  )

  const applyView = (v: SavedViewDoc) => {
    setColumns(v.columns.length ? v.columns.map((c) => ({ ...c })) : defaultColumns())
    setSort(v.sort ?? null)
    setForm(v.form)
    setGrouping((v.grouping as GroupKey | undefined) ?? null)
    setGap((v.filters?.gap as GapKey | undefined) ?? null)
    setSearch(v.filters?.search ?? '')
    setActiveView(v.id ?? null)
  }

  const currentViewDoc = (name: string, scope: 'personal' | 'project'): SavedViewDoc => ({
    name,
    section: 'requirements',
    scope,
    columns: columns.map((c) => ({ key: c.key, on: c.on })),
    sort: sort ?? undefined,
    filters: gap || search ? { gap: gap ?? undefined, search: search || undefined } : undefined,
    grouping: grouping ?? undefined,
    form,
  })

  const headerSort = (key: string) => {
    setSort((s) => (s && s.key === key ? { key, dir: s.dir === 'desc' ? 'asc' : 'desc' } : { key, dir: 'desc' }))
  }

  const toggleGroup = (key: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  const move = (delta: 1 | -1) => {
    if (seq.length === 0) return
    const at = activeId ? seq.findIndex((r) => r.id === activeId) : -1
    let idx = at < 0 ? 0 : at + delta
    if (idx < 0) idx = 0
    if (idx > seq.length - 1) idx = seq.length - 1
    const next = seq[idx]
    setActiveId(next.id)
    if (expandedId) setExpandedId(next.id)
  }

  const onKeys = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1) }
    else if (e.key === 'Escape') setExpandedId(null)
    else if (e.key === 'Enter' && activeId) setExpandedId(activeId)
  }

  if (mode.kind === 'create' || mode.kind === 'edit') {
    return (
      <div className="pane rr-pane">
        <div className="rr-chead">
          <button type="button" className="rr-back" onClick={() => setMode(
            mode.kind === 'edit' ? { kind: 'card', id: mode.id } : { kind: 'registry' },
          )}>
            ← {mode.kind === 'edit' ? 'Карточка' : 'Реестр требований'}
          </button>
          <span className="secondary">{mode.kind === 'edit' ? `правка ${mode.id}` : 'новое требование'}</span>
        </div>
        <div className="workarea" style={{ padding: '10px 16px', maxWidth: 720 }}>
          <ObjectEditor
            kind="requirement"
            schemaName="core/requirement"
            title="требование"
            template={mode.kind === 'create' ? mode.template ?? null : null}
            id={mode.kind === 'edit' ? mode.id : null}
            onSaved={(id) => { setMode({ kind: 'card', id }); void reload() }}
            onCancelled={() => { setMode({ kind: 'registry' }); void reload() }}
          />
        </div>
      </div>
    )
  }

  if (mode.kind === 'card') {
    return (
      <CardView
        id={mode.id}
        seq={seq}
        rows={tree.rows}
        childrenMap={tree.children}
        systemRoot={tree.systemRoot}
        compositionRoots={tree.compositionRoots}
        onBack={() => setMode({ kind: 'registry' })}
        onOpen={(id) => setMode({ kind: 'card', id })}
        onEdit={() => setMode({ kind: 'edit', id: mode.id })}
        onCreate={(template) => setMode({ kind: 'create', template })}
        onGo={onGo}
        onChanged={() => void reload()}
      />
    )
  }

  const visible = visibleColumns(columns)

  const gapChip = (g: GapKey) => (
    <button
      key={g}
      type="button"
      className={`rr-g${gap === g ? ' on' : ''}${g === 'no_carrier' || g === 'no_need' || g === 'conflict' ? ' bad' : ''}${g === 'recalc' || g === 'changed' || g === 'no_acceptance' || g === 'no_title' ? ' warnc' : ''}`}
      onClick={() => setGap((cur) => (cur === g ? null : g))}
    >
      {GAP_LABELS[g]} · <b>{counters[g]}</b>
    </button>
  )

  return (
    <div className="pane rr-pane">
      {/* строка 1: раздел и главное действие */}
      <div className="rr-tbar">
        <h1 className="rr-h1">Требования</h1>
        <span className="tabs" style={{ display: 'inline-flex' }}>
          <button type="button" className="tab" aria-selected>Реестр</button>
          <button type="button" className="tab" onClick={() => onGo?.('matrix')}>Матрицы</button>
        </span>
        <span style={{ flex: 1 }} />
        <button type="button" className="rr-btn rr-btn--pri" onClick={() => setMode({ kind: 'create' })}>
          Добавить требование
        </button>
        <span style={{ position: 'relative' }}>
          <button type="button" className="rr-btn" onClick={() => setMenuOpen((v) => !v)}>⋯</button>
          {menuOpen && (
            <DotsMenu
              onClose={() => setMenuOpen(false)}
              onImported={(report) => { setNotice(report); void reload() }}
            />
          )}
        </span>
      </div>

      {/* строка 2: форма данных — дерево/плоско, группировка, виды, поиск */}
      <div className="rr-tbar rr-tbar--form">
        <span className="rr-cut">
          <button type="button" className={form === 'tree' ? 'on' : ''} onClick={() => setForm('tree')}>Дерево</button>
          <button type="button" className={form === 'flat' ? 'on' : ''} onClick={() => setForm('flat')}>Плоско</button>
        </span>
        <Select
          value={grouping ?? ''}
          prefix="Группировка: "
          options={[{ key: '', title: 'нет' },
            ...GROUPINGS.map((g) => ({ key: g.key, title: g.title }))]}
          onChange={(v) => setGrouping((v || null) as GroupKey | null)}
        />
        <span className="rr-views">
          {views.map((v) => (
            <button
              key={v.id}
              type="button"
              className={`rr-v${activeView === v.id ? ' on' : ''}`}
              title={v.scope === 'project' ? 'проектный вид' : 'личный вид'}
              onClick={() => applyView(v)}
            >
              {v.name}
            </button>
          ))}
          <span style={{ position: 'relative' }}>
            <button type="button" className="rr-v rr-v--add" onClick={() => setSaveOpen((v) => !v)}>+ сохранить</button>
            {saveOpen && (
              <SaveViewForm
                onClose={() => setSaveOpen(false)}
                onSave={(name, scope) => {
                  api.saveReqView(currentViewDoc(name, scope), author || 'инженер')
                    .then((r) => { setActiveView(r.id); setSaveOpen(false); void reloadViews() })
                    .catch((e) => setNotice(String(e)))
                }}
              />
            )}
          </span>
        </span>
        <span style={{ flex: 1 }} />
        <input
          className="rr-search"
          placeholder="Поиск по формулировке и ID"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <span style={{ position: 'relative' }}>
          <button type="button" className="rr-btn" onClick={() => setCfgOpen((v) => !v)}>⚙ Вид</button>
          {cfgOpen && (
            <ViewConfig
              columns={columns}
              sort={sort}
              activeView={views.find((v) => v.id === activeView) ?? null}
              onToggle={(key) => setColumns((cols) => cols.map((c) => (c.key === key ? { ...c, on: !c.on } : c)))}
              onReorder={(from, to) => setColumns((cols) => {
                const next = [...cols]
                const i = next.findIndex((c) => c.key === from)
                const j = next.findIndex((c) => c.key === to)
                if (i < 0 || j < 0) return cols
                const [item] = next.splice(i, 1)
                next.splice(j, 0, item)
                return next
              })}
              dragKey={dragKey}
              onSaveAs={() => { setCfgOpen(false); setSaveOpen(true) }}
              onMakeProject={(v) => {
                edit.update(v.id!, { scope: 'project' }, v.version ?? '1', author || 'инженер')
                  .then(() => void reloadViews())
                  .catch((e) => setNotice(String(e)))
              }}
              onClose={() => setCfgOpen(false)}
            />
          )}
        </span>
      </div>

      {/* строка 3: счётчики-фильтры разрывов — стратифицированы по уровням;
          «Нужда не покрыта» — не фильтр строк, а переход к перечню нужд */}
      <div className="rr-gaps">
        <button type="button" className={`rr-g${gap === null ? ' on' : ''}`} onClick={() => setGap(null)}>
          Все · <b>{tree.rows.length}</b>
        </button>
        {(['no_carrier'] as GapKey[]).map((g) => gapChip(g))}
        <button
          type="button"
          className="rr-g bad"
          title="нужды без единого требования — открыть перечень нужд"
          onClick={() => onGo?.('needs')}
        >
          Нужда не покрыта · <b>{tree.needsUncovered.length}</b>
        </button>
        {(['no_need', 'no_verification', 'no_acceptance', 'no_title', 'conflict', 'recalc', 'changed'] as GapKey[]).map((g) => gapChip(g))}
      </div>

      {notice && <div className="warn" style={{ padding: '4px 14px' }}>{notice}</div>}

      {selection.size > 0 && (
        <div className="rr-selbar">
          <b>Выбрано: {selection.size}</b>
          <span style={{ flex: 1 }} />
          <span>Базировать <b>{selection.size} требований</b>?</span>
          <button
            type="button"
            className="rr-btn rr-btn--pri"
            disabled={!author || massBusy}
            title={author ? '' : 'представьтесь в шапке'}
            onClick={() => {
              setMassBusy(true)
              api.promoteBatch([...selection], 'Baseline', author)
                .then((r) => {
                  setNotice(
                    `базировано ${r.promoted.length}; отказов ${r.failed.length}` +
                    (r.failed.length
                      ? ` — ${r.failed.slice(0, 3).map((f) => `${f.id}: ${f.reason}`).join('; ')}${r.failed.length > 3 ? '…' : ''}`
                      : ''),
                  )
                  setSelection(new Set())
                  void reload()
                })
                .catch((e) => setNotice(String(e)))
                .finally(() => setMassBusy(false))
            }}
          >
            {massBusy ? 'Базирование…' : 'Базировать'}
          </button>
          <button type="button" className="rr-btn" onClick={() => setSelection(new Set())}>Отмена</button>
        </div>
      )}

      <div className="rr-body">
      <aside className="rr-trees" aria-label="Деревья требований">
        <div className="rr-thead">
          <span className="rr-xk">Дерево</span>
          <span className="tabs" style={{ display: 'inline-flex' }}>
            <button type="button" className={`tab${treeMode === 'hierarchy' ? ' on' : ''}`} aria-selected={treeMode === 'hierarchy'} onClick={() => setTreeMode('hierarchy')}>по иерархии</button>
            <button type="button" className={`tab${treeMode === 'documents' ? ' on' : ''}`} aria-selected={treeMode === 'documents'} onClick={() => setTreeMode('documents')}>по документам</button>
          </span>
        </div>
        {treeFocus && (
          <button type="button" className="rr-assign" onClick={() => setTreeFocus(null)}>× снять выбор · показаны {scopedRows.length} из {tree.rows.length}</button>
        )}
        {treeMode === 'hierarchy' && (
          tree.roots.length === 0
            ? <div className="empty">Дерева нет: требования появятся здесь по связям decompose.</div>
            : <div role="tree">{tree.roots.map((id) => hierarchyNode(id, 0))}</div>
        )}
        {treeMode === 'documents' && (
          (tree.documents ?? []).length === 0
            ? <div className="empty">Документов-оснований нет: заполните у требования источник (документ и якорь блока).</div>
            : <div role="tree">
                {(tree.documents ?? []).map((d) => {
                  const docIds = new Set(d.sections.flatMap((sct) => sct.ids))
                  const open = treeOpen.has(`doc:${d.doc}`)
                  return (
                    <div key={d.doc}>
                      <div
                        className={`rr-tnode${treeFocus?.key === `doc:${d.doc}` ? ' on' : ''}`}
                        tabIndex={0}
                        role="treeitem"
                        aria-expanded={open}
                        onClick={() => focusTree(`doc:${d.doc}`, docIds)}
                        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); focusTree(`doc:${d.doc}`, docIds) } }}
                      >
                        <button type="button" className="rr-tchev" title={open ? 'свернуть' : 'раскрыть'} onClick={(e) => { e.stopPropagation(); toggleTreeNode(`doc:${d.doc}`) }}>{open ? '▾' : '▸'}</button>
                        <span className="mono">{d.doc}</span> <span className="rr-ttitle">{d.name}</span>
                        <span className="rr-tcnt">{counterText(nodeCounters(docIds))}</span>
                      </div>
                      {open && d.sections.map((sct) => {
                        const key = `doc:${d.doc}:${sct.anchor}`
                        const ids = new Set(sct.ids)
                        return (
                          <div
                            key={key}
                            className={`rr-tnode${treeFocus?.key === key ? ' on' : ''}`}
                            style={{ paddingLeft: 22 }}
                            tabIndex={0}
                            role="treeitem"
                            onClick={() => focusTree(key, ids)}
                            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); focusTree(key, ids) } }}
                          >
                            <span className="rr-ttitle">{sct.anchor}</span>
                            <span className="rr-tcnt">{counterText(nodeCounters(ids))}</span>
                          </div>
                        )
                      })}
                    </div>
                  )
                })}
              </div>
        )}
      </aside>
      <div className="workarea" style={{ overflow: 'auto' }} tabIndex={0} onKeyDown={onKeys}>
        {tree.rows.length === 0 && (
          <div className="empty">
            Требований пока нет. Требование не бывает сиротой: укажите в traces_up
            нужду или сервис, из которых оно следует.
          </div>
        )}
        {tree.rows.length > 0 && (
          <table>
            <thead>
              <tr>
                <th style={{ width: 26 }} />
                {visible.map((c) => (
                  <th
                    key={c.key}
                    style={{
                      ...(COLUMN_WIDTHS[c.key] ? { width: COLUMN_WIDTHS[c.key] } : {}),
                      ...(c.key === 'mop' ? { textAlign: 'right' as const } : {}),
                      ...(c.key === 'verification' ? { textAlign: 'center' as const } : {}),
                    }}
                    onClick={() => headerSort(c.key)}
                    title="клик — сортировка"
                  >
                    {c.key === 'verification' ? 'В' : c.key === 'kind' ? 'Вид' : c.key === 'carrier' ? 'Носитель' : COLUMN_LABELS[c.key]}
                    {sort?.key === c.key && (sort.dir === 'desc' ? ' ↓' : ' ↑')}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {items.map((item) =>
                item.type === 'group' ? (
                  <tr key={`g:${item.key}`} className="rr-grp" onClick={() => toggleGroup(item.key)}>
                    <td colSpan={visible.length + 1}>
                      <span className="rr-chev">{item.collapsed ? '▸' : '▾'}</span>
                      {item.key !== item.label && !item.key.startsWith('__')
                        ? <><span className="mono" style={{ textTransform: 'none' }}>{item.key}</span> {item.label.slice(item.key.length + 1)}</>
                        : item.label}
                      <span className="rr-cnt"> · {item.count}{item.key === PROJECT_GROUP && !tree.systemRoot ? ' · носитель придёт с корнем' : ''}</span>
                    </td>
                  </tr>
                ) : (
                  <RegistryRow
                    key={item.row.id}
                    item={item}
                    visible={visible}
                    label={label}
                    selected={selection.has(item.row.id)}
                    active={activeId === item.row.id}
                    expanded={expandedId === item.row.id}
                    systemRoot={tree.systemRoot}
                    onToggleSelect={(id) =>
                      setSelection((prev) => {
                        const next = new Set(prev)
                        if (next.has(id)) next.delete(id)
                        else next.add(id)
                        return next
                      })}
                    onActivate={(id) => { setActiveId(id); setExpandedId((cur) => (cur === id ? null : id)) }}
                    onOpenCard={(id) => setMode({ kind: 'card', id })}
                    renderCard={renderCard}
                  />
                ),
              )}
            </tbody>
          </table>
        )}
      </div>
      </div>
    </div>
  )
}

/** Строка реестра + её раскрытие (суть без ухода с экрана). */
function RegistryRow({
  item, visible, label, selected, active, expanded, systemRoot,
  onToggleSelect, onActivate, onOpenCard, renderCard,
}: {
  /** ADR-045: карточка раскрывается ВНИЗ, под строкой, во всю ширину списка. */
  renderCard: (id: string) => JSX.Element
  item: Extract<RegistryItem, { type: 'row' }>
  visible: ColumnState[]
  label: (group: string, code: string | null | undefined) => string
  selected: boolean
  active: boolean
  expanded: boolean
  systemRoot: { id: string; name: string | null } | null
  onToggleSelect: (id: string) => void
  onActivate: (id: string) => void
  onOpenCard: (id: string) => void
}) {
  const r = item.row
  const cell = (key: string) => {
    switch (key) {
      case 'id':
        return (
          <td key={key} className="mono id" style={{ paddingLeft: 8 + item.depth * 16 }}>
            {item.depth > 0 && <span className="secondary">└ </span>}{r.id}
          </td>
        )
      case 'statement':
        return <td key={key} className="rr-st" title={r.statement}>{r.statement}</td>
      case 'kind':
        return <td key={key} className="secondary">{r.kind === 'numeric' ? 'Числовое' : 'Текстовое'}</td>
      case 'mop':
        return <td key={key} className="mono" style={{ textAlign: 'right' }}>{r.condition?.rendered ?? ''}</td>
      case 'status':
        return (
          <td key={key}>
            <span className={`dot status-${r.status}`} title={label('lifecycle', r.status)} />{label('lifecycle', r.status)}
            {r.recalcAfterBaseline && <span className="rr-mk rr-mk--recalc" title="Показатель пересчитан после базирования" />}
            {r.changedAfterApproval && <span className="rr-mk rr-mk--chg" title="Изменено после утверждения" />}
          </td>
        )
      case 'carrier':
        return (
          <td key={key}>
            {r.allocatedTo.length > 0
              ? <span className="mono secondary" title={r.carrierName ?? undefined}>{r.allocatedTo[0]}</span>
              : r.level === 'project'
                ? (systemRoot
                  ? <span className="mono secondary" title="распределено на корень системы автоматически">{systemRoot.id}</span>
                  : <span className="secondary" title="Распределится на корень системы автоматически">—</span>)
                : (
                  <button
                    type="button" className="rr-assign"
                    title="системное требование без носителя — распределить"
                    onClick={(e) => { e.stopPropagation(); onOpenCard(r.id) }}
                  >
                    распределить
                  </button>
                )}
          </td>
        )
      case 'verification':
        return (
          <td key={key} style={{ textAlign: 'center' }} title={r.verificationState}>
            {r.verificationState === 'верифицировано' ? <span style={{ color: 'var(--ok, #1a7f37)' }}>✓</span> : <span className="secondary">—</span>}
          </td>
        )
      case 'parent':
        return <td key={key} className="mono secondary">{r.parentId ?? ''}</td>
      case 'category':
        return <td key={key} className="secondary">{r.category ? label('requirement_category', r.category) : ''}</td>
      case 'level':
        return <td key={key} className="secondary">{r.level ? label('requirement_level', r.level) : ''}</td>
      case 'version':
        return <td key={key} className="mono secondary">{r.version}</td>
      case 'owner':
        return <td key={key} className="secondary">{r.owner ?? ''}</td>
      case 'origin':
        return <td key={key} className="secondary">{r.origin ? label('provenance_source', r.origin) : ''}</td>
      default:
        return <td key={key} />
    }
  }
  return (
    <>
      <tr
        tabIndex={0}
        aria-selected={active || expanded}
        onClick={() => onActivate(r.id)}
      >
        <td className="rr-chk" onClick={(e) => e.stopPropagation()}>
          <input type="checkbox" checked={selected} onChange={() => onToggleSelect(r.id)} />
        </td>
        {visible.map((c) => cell(c.key))}
      </tr>
      {expanded && (
        <tr className="rr-expand">
          <td colSpan={visible.length + 1} style={{ padding: 0 }}>
            {renderCard(r.id)}
          </td>
        </tr>
      )}
    </>
  )
}

/** Меню «⋯»: вторичные действия реестра — выгрузки и загрузка пачкой. */
function DotsMenu({ onClose, onImported }: { onClose: () => void; onImported: (report: string) => void }) {
  const [reqifIssues, setReqifIssues] = useState<string[]>([])
  useEffect(() => {
    api.reqifCheck()
      .then((c) => setReqifIssues([...c.mapping_issues, ...c.flattened.map((f) => `${f}: составное значение свёрнуто в строку`)]))
      .catch(() => setReqifIssues([]))
  }, [])
  return (
    <div className="rr-cfg" style={{ width: 240 }}>
      <h4>Выгрузка и обмен <button type="button" className="rr-assign" onClick={onClose}>закрыть</button></h4>
      <div className="rr-col"><a className="rr-assign" href={api.exportUrls.reqif} download>Выгрузить ReqIF</a></div>
      <div className="rr-col"><a className="rr-assign" href={api.exportUrls.csv} download>Выгрузить CSV</a></div>
      <div className="rr-col"><a className="rr-assign" href={api.exportUrls.exchangeJson} download>Выгрузить JSON (обмен)</a></div>
      <div className="rr-col"><a className="rr-assign" href={api.exportUrls.sdoc} download title="StrictDoc-канал: документ по грамматике Орбиты (ADR-049)">Выгрузить .sdoc (StrictDoc)</a></div>
      <div className="rr-col"><a className="rr-assign" href={api.exportUrls.sdocReqif} download title="ReqIF, собранный StrictDoc из .sdoc">Выгрузить ReqIF (StrictDoc)</a></div>
      <div className="rr-col">
        <button
          type="button"
          className="rr-assign"
          title="снимок базирования: .sgra и .sdoc в каталоге файлов проекта — диф двух базирований читается diff'ом"
          onClick={() => {
            api.sdocBaseline()
              .then((r) => onImported(`снимок базирования записан: ${r.dir}`))
              .catch((e) => onImported(String(e)))
          }}
        >
          Снимок базирования .sdoc
        </button>
      </div>
      <div className="rr-col">
        <label className="rr-assign" style={{ cursor: 'pointer' }} title="StrictDoc-канал (ADR-049): документ разбирает StrictDoc, назад — кандидаты с чужими полями; в модель импорт не пишет">
          Загрузить .sdoc…
          <input
            type="file" accept=".sdoc" style={{ display: 'none' }}
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) {
                void f.text().then((sdoc) => api.importSdoc(sdoc)).then((parsed) =>
                  onImported(`разобрано кандидатов из .sdoc: ${parsed.count}`),
                ).catch((err) => onImported(String(err)))
              }
              e.target.value = ''
              onClose()
            }}
          />
        </label>
      </div>
      <div className="rr-col">
        <label className="rr-assign" style={{ cursor: 'pointer' }}>
          Загрузить ReqIF…
          <input
            type="file" accept=".reqif,.xml" style={{ display: 'none' }}
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) {
                void f.text().then((xml) => api.importReqif(xml)).then((parsed) =>
                  onImported(`разобрано черновиков: ${parsed.drafts.length}` +
                    (parsed.source_title ? ` из «${parsed.source_title}»` : '')),
                ).catch((err) => onImported(String(err)))
              }
              e.target.value = ''
              onClose()
            }}
          />
        </label>
      </div>
      {reqifIssues.length > 0 && (
        <div className="secondary" style={{ fontSize: 11, marginTop: 6 }}>
          Отображение ReqIF с замечаниями: {reqifIssues.join('; ')}
        </div>
      )}
    </div>
  )
}

/** Форма «+ сохранить»: имя и область — личный вид или проектный. */
function SaveViewForm({ onClose, onSave }: {
  onClose: () => void
  onSave: (name: string, scope: 'personal' | 'project') => void
}) {
  const [name, setName] = useState('')
  const [scope, setScope] = useState<'personal' | 'project'>('personal')
  return (
    <div className="rr-cfg" style={{ width: 230, left: 0, right: 'auto' }}>
      <h4>Сохранить вид <button type="button" className="rr-assign" onClick={onClose}>закрыть</button></h4>
      <input
        className="rr-search" style={{ width: '100%' }} placeholder="Название вида"
        value={name} autoFocus onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' && name.trim()) onSave(name.trim(), scope) }}
      />
      <div className="rr-col" style={{ marginTop: 6 }}>
        <label><input type="radio" checked={scope === 'personal'} onChange={() => setScope('personal')} /> личный</label>
        <label><input type="radio" checked={scope === 'project'} onChange={() => setScope('project')} /> проектный</label>
      </div>
      <div style={{ marginTop: 8 }}>
        <button title="дайте имя — безымянный вид реестра не сохраняется" type="button" className="rr-btn rr-btn--pri" disabled={!name.trim()} onClick={() => onSave(name.trim(), scope)}>
          Сохранить
        </button>
      </div>
    </div>
  )
}

/** Конфигуратор вида: состав и порядок колонок; читает ТОТ ЖЕ массив, что
 * и таблица, — рассинхрон невозможен по построению. */
function ViewConfig({
  columns, sort, activeView, onToggle, onReorder, dragKey, onSaveAs, onMakeProject, onClose,
}: {
  columns: ColumnState[]
  sort: SortState | null
  activeView: SavedViewDoc | null
  onToggle: (key: string) => void
  onReorder: (from: string, to: string) => void
  dragKey: React.MutableRefObject<string | null>
  onSaveAs: () => void
  onMakeProject: (v: SavedViewDoc) => void
  onClose: () => void
}) {
  return (
    <div className="rr-cfg">
      <h4>
        Вид {activeView ? `«${activeView.name}»` : 'таблицы'}
        <span>
          <button type="button" className="rr-assign" onClick={onSaveAs}>сохранить как…</button>{' '}
          <button type="button" className="rr-assign" onClick={onClose}>закрыть</button>
        </span>
      </h4>
      <div className="rr-sec">Колонки — порядок перетаскиванием</div>
      {columns.map((c) => (
        <div
          key={c.key}
          className="rr-col"
          draggable
          onDragStart={() => { dragKey.current = c.key }}
          onDragOver={(e) => e.preventDefault()}
          onDrop={() => { if (dragKey.current && dragKey.current !== c.key) onReorder(dragKey.current, c.key) }}
        >
          <span className="rr-grip">⠿</span>
          <input type="checkbox" checked={c.on} onChange={() => onToggle(c.key)} />
          {COLUMN_LABELS[c.key]}
        </div>
      ))}
      <div className="rr-sec">Сортировка</div>
      <div className="rr-col secondary">
        {sort ? `${COLUMN_LABELS[sort.key] ?? sort.key} · по ${sort.dir === 'desc' ? 'убыванию' : 'возрастанию'}` : 'нет'} — клик по заголовку меняет
      </div>
      <div className="rr-sec">Область вида</div>
      <div className="rr-col secondary">
        {activeView
          ? activeView.scope === 'project'
            ? 'проектный'
            : <>личный · <button type="button" className="rr-assign" onClick={() => onMakeProject(activeView)}>сделать проектным</button></>
          : 'вид не сохранён'}
      </div>
    </div>
  )
}

/** Карточка требования — вся рабочая область, листание по текущей выборке. */
function CardView({
  id, seq, rows, childrenMap, systemRoot, compositionRoots, onBack, onOpen, onEdit, onCreate, onGo, onChanged, inline = false,
}: {
  /** Встроена под строкой реестра (ADR-045): без шапки-возврата в реестр. */
  inline?: boolean
  id: string
  seq: RequirementRow[]
  rows: RequirementRow[]
  childrenMap: Record<string, string[]>
  systemRoot: { id: string; name: string | null } | null
  compositionRoots: number
  onBack: () => void
  onOpen: (id: string) => void
  onEdit: () => void
  onCreate: (template?: Record<string, unknown>) => void
  onGo?: (screen: string) => void
  onChanged: () => void
}) {
  const { label, author } = useSession()
  const [card, setCard] = useState<RequirementCard | null>(null)
  const [history, setHistory] = useState<Array<{ version: string; status: string; author: string; valid_from: string; current: boolean }>>([])
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    api.requirementCard(id).then(setCard).catch((e) => setFailure(String(e)))
    edit.history(id).then((h) => setHistory(h as typeof history)).catch(() => setHistory([]))
  }, [id])

  if (failure) return <div className="empty">Ошибка обращения к API: {failure}</div>
  if (!card) return <div className="empty">Загрузка…</div>

  const r = card.row
  const at = seq.findIndex((x) => x.id === id)
  const kids = childrenMap[id] ?? []
  // предложения службы к этому требованию: порождённые ИИ черновики-дети
  const proposals = rows.filter((x) => x.origin === 'ai_proposed' && x.status === 'Draft' && x.parentId === id)

  const jump = (target: string) => {
    const scr = screenOfObject(target)
    if (scr) { requestObject(target); onGo?.(scr) }
  }

  return (
    <div className="pane rr-pane">
      <div className="rr-chead">
        <button type="button" className="rr-back" onClick={onBack}>{inline ? '▴ Свернуть карточку' : '← Реестр требований'}</button>
        {onGo && (
          <button type="button" className="rr-btn" title="кого заденет изменение — граф трассировки с фокусом на этом требовании" onClick={() => { requestObject(id); onGo('trace') }}>что заденет →</button>
        )}
        <span className="mono secondary">{r.id}</span>
        <span className="chip"><span className={`dot status-${r.status}`} title={label('lifecycle', r.status)} />{label('lifecycle', r.status)} · v{r.version}</span>
        {(r.lint ?? []).map((n) => (
          <Tooltip key={n.id} text={`${n.text} · правило ${n.id} (NASA SEH App. C)`}>
            <span className="chip rr-warn-chip">{n.id}</span>
          </Tooltip>
        ))}
        {r.recalcAfterBaseline && <span className="chip rr-warn-chip" title="Показатель пересчитан после базирования">пересчитан</span>}
        {r.changedAfterApproval && <span className="chip rr-warn-chip" title="Изменено после утверждения">изменено</span>}
        <span style={{ flex: 1 }} />
        <span className="secondary">{at >= 0 ? `${at + 1} из ${seq.length}` : `вне текущей выборки · всего ${seq.length}`}</span>
        <button title="это первый элемент — выше двигать некуда" type="button" className="rr-btn" disabled={at <= 0} onClick={() => onOpen(seq[at - 1].id)}>↑ пред.</button>
        <button title="это последний элемент — ниже двигать некуда" type="button" className="rr-btn" disabled={at < 0 || at >= seq.length - 1} onClick={() => onOpen(seq[at + 1].id)}>след. ↓</button>
        <button type="button" className="rr-btn" onClick={onEdit}>Изменить</button>
      </div>
      <div className="workarea" style={{ overflow: 'auto' }}>
        {r.noCarrierGap && (
          <CarrierBand
            id={id}
            version={r.version}
            author={author || 'инженер'}
            onDone={() => { onChanged(); api.requirementCard(id).then(setCard).catch(() => undefined) }}
            onFail={(e) => setFailure(e)}
          />
        )}
        <div className="rr-ctitle">
          <h2>{r.statement}</h2>
          <div className="rr-cstate">
            {r.title ? <span className="chip"><b>{r.title}</b></span> : <span className="chip amber" title="краткое имя не записано — помета к базированию; автозаголовок не выдумывается">△ без заголовка</span>}
            {r.priority && <span className="chip">приоритет: {label('requirement_priority', r.priority)}</span>}
            {r.level && <span className="chip">{label('requirement_level', r.level)}</span>}
            <span className="chip">{r.kind === 'numeric' ? 'Числовое' : 'Текстовое'}</span>
            {r.category && <span className="chip">{label('requirement_category', r.category)}</span>}
            {r.origin && <span className="chip">Происхождение: {label('provenance_source', r.origin)}</span>}
          </div>
        </div>
        <div className="rr-cbody">
          <div className="card">
            <div className="rr-xk">Показатель</div>
            {r.condition
              ? (
                <>
                  <div className="rr-mopv mono">{r.condition.rendered}</div>
                  {r.condition.name && <div className="secondary" style={{ fontSize: 12 }}>{r.condition.name}</div>}
                </>
              )
              : <div className="secondary">текстовое требование — показателя нет</div>}
          </div>
          <div className="card">
            <div className="rr-xk">{r.level === 'project' ? 'Источник — нужда' : 'Трассировка'} <button type="button" className="rr-assign" onClick={() => onGo?.('matrix')}>в матрицу →</button></div>
            {r.level === 'project' && (
              <div className="secondary" style={{ fontSize: 11, marginBottom: 4 }}>
                Якорь требования уровня проекта — трассировка на нужду; без неё — разрыв.
              </div>
            )}
            {(r.parentId ? [r.parentId] : []).concat(card.sources).map((s) => (
              <div key={s} className="rr-tnode">
                <button type="button" className="rr-assign mono" onClick={() => jump(s)}>{s}</button>
              </div>
            ))}
            <div className="secondary" style={{ fontSize: 11 }}>↓ вниз</div>
            {kids.length === 0
              ? (
                <div className="rr-tnode secondary">
                  {/* автоподстановка (РЕШЕНИЯ-Т1 §2.3): родитель — текущее,
                      уровень наследуется, носитель пуст — ребёнок может уйти
                      на другой элемент; отмена создания следа не оставляет */}
                  детей нет —{' '}
                  <button
                    type="button" className="rr-assign"
                    onClick={() => onCreate({
                      derives_from: [id],
                      ...(r.level ? { level: r.level } : {}),
                    })}
                  >
                    декомпозировать
                  </button>
                </div>
              )
              : kids.map((k) => (
                <div key={k} className="rr-tnode">
                  <button type="button" className="rr-assign mono" onClick={() => onOpen(k)}>{k}</button>
                </div>
              ))}
          </div>
          <div className="card">
            <div className="rr-xk">Обоснование</div>
            <div style={{ fontSize: 12.5, lineHeight: '18px' }}>
              {r.rationale ?? <span className="secondary">не записано</span>}
            </div>
          </div>
          {/* ADR-045: полная структура требования — поле в поле */}
          <div className="card">
            <div className="rr-xk">Критерий приёмки</div>
            <div style={{ fontSize: 12.5, lineHeight: '18px' }}>
              {r.acceptanceCriteria ?? <span className="amber">△ нет критерия приёмки — помета к базированию</span>}
            </div>
          </div>
          <div className="card">
            <div className="rr-xk">Источник · документ и якорь</div>
            <div style={{ fontSize: 12.5, lineHeight: '18px' }}>
              {r.sourceDoc
                ? <>
                    <span className="mono">{r.sourceDoc.doc}</span>{r.sourceDoc.name ? ` ${r.sourceDoc.name}` : ''}
                    {r.sourceDoc.anchor && <> · якорь <span className="mono">{r.sourceDoc.anchor}</span></>}
                    {onGo && <button type="button" className="rr-assign" style={{ marginLeft: 8 }} onClick={() => onGo('docparse')}>к месту в разборе →</button>}
                  </>
                : <span className="secondary">документ-основание не записан</span>}
            </div>
            {r.normativeBasis && (
              <div style={{ fontSize: 12.5, lineHeight: '18px', marginTop: 4 }}>
                Норматив: <span className="mono">{r.normativeBasis.ref}</span>{r.normativeBasis.name ? ` ${r.normativeBasis.name}` : ''}{r.normativeBasis.clause ? `, п. ${r.normativeBasis.clause}` : ''}
              </div>
            )}
            {(r.tags?.length ?? 0) > 0 && (
              <div style={{ marginTop: 4 }}>{r.tags!.map((t) => <span key={t} className="chip">{t}</span>)}</div>
            )}
          </div>
          {(r.relations?.length ?? 0) > 0 && (
            <div className="card">
              <div className="rr-xk">Связи с обоснованием</div>
              {r.relations!.map((rel) => (
                <div key={`${rel.kind}:${rel.ref}`} style={{ fontSize: 12.5, lineHeight: '18px' }}>
                  <span className="chip">{label('requirement_relation_kind', rel.kind)}</span>{' '}
                  <button type="button" className="rr-assign mono" onClick={() => onOpen(rel.ref)}>{rel.ref}</button>
                  <span className="secondary"> — {rel.rationale}</span>
                  {rel.kind === 'conflicts_with' && (
                    rel.resolution
                      ? <div className="secondary">разрешено: {rel.resolution}</div>
                      : <div className="bad">противоречие не разрешено — разрыв готовности к базированию</div>
                  )}
                </div>
              ))}
            </div>
          )}
          {r.comment && (
            <div className="card">
              <div className="rr-xk">Комментарий · не печатается</div>
              <div style={{ fontSize: 12.5, lineHeight: '18px' }}>{r.comment}</div>
            </div>
          )}
          <div className="card">
            <div className="rr-xk">{r.level === 'project' ? 'Распределение · механика' : 'Распределение'}</div>
            {r.level === 'project' && card.allocatedTo.length === 0 && (
              <>
                <div style={{ fontSize: 12.5, margin: '2px 0 6px' }}>
                  {systemRoot
                    ? <>Носитель — корень системы: <span className="mono">{systemRoot.id}</span> {systemRoot.name}. Распределение выполняется автоматически.</>
                    : compositionRoots > 1
                      ? <>Носитель — корень системы; в дереве состава несколько корней, корень не определён.</>
                      : <>Носитель — корень системы; корня в дереве пока нет. При его появлении распределение выполнится <b>автоматически</b> и запишется в журнал.</>}
                </div>
                {!systemRoot && compositionRoots === 0 && (
                  <button
                    type="button" className="rr-btn"
                    onClick={() => {
                      edit.create('component', { name: 'Система', kind: 'system' }, author || 'инженер')
                        .then((cm) => edit.create('component_usage', { definition_ref: cm.id, quantity: 1 }, author || 'инженер'))
                        .then(onChanged)
                        .catch((e) => setFailure(String(e)))
                    }}
                  >
                    Создать корень системы сейчас
                  </button>
                )}

              </>
            )}
            {r.level !== 'project' && card.allocatedTo.length === 0
              ? (
                <div className="rr-tnode secondary">
                  не распределено — <button type="button" className="rr-assign" onClick={onEdit}>распределить</button>
                </div>
              )
              : card.allocatedTo.map((a) => (
                <div key={a} className="rr-tnode">
                  <button type="button" className="rr-assign mono" onClick={() => jump(a)}>{a}</button>
                  {r.allocatedTo[0] === a && r.carrierName && <span>{r.carrierName}</span>}
                  <button type="button" className="rr-assign" style={{ marginLeft: 'auto' }} onClick={() => jump(a)}>к носителю →</button>
                </div>
              ))}
          </div>
          <div className="card">
            <div className="rr-xk">Верификация</div>
            {card.successCriterion && <div className="secondary mono" style={{ fontSize: 11.5 }}>{card.successCriterion}</div>}
            {card.events.length === 0 && <div className="amber">△ события верификации не запланированы</div>}
            {card.events.map((e) => (
              <div key={e.id} className="rr-tnode">
                <span>{label('verification_method', e.method ?? '')} · {e.kind} · {e.phase}{e.closes ? ' · закрывающее' : ''}</span>
                <span className="chip">{e.status}</span>
                {e.evidenceStale && <span className="warn chip">свидетельство устарело</span>}
              </div>
            ))}
          </div>
          <div className="card">
            <div className="rr-xk">История</div>
            {history.map((h) => (
              <div key={h.version} className="rr-hist">
                <span className="mono">v{h.version}</span>
                <span className="secondary">{label('lifecycle', h.status)} · {h.author} · {h.valid_from?.slice(0, 10)}</span>
                {h.current && history.length > 1 && (
                  <button
                    type="button" className="rr-assign" style={{ marginLeft: 'auto' }}
                    onClick={() => {
                      edit.undo(id, author || 'инженер')
                        .then(() => { onChanged(); api.requirementCard(id).then(setCard).catch(() => undefined); edit.history(id).then((hh) => setHistory(hh as typeof history)).catch(() => undefined) })
                        .catch((e) => setFailure(String(e)))
                    }}
                  >
                    откатить
                  </button>
                )}
              </div>
            ))}
            {history.length === 0 && <div className="secondary">история недоступна</div>}
          </div>
          <div className="card rr-wide">
            <div className="rr-xk">Предложения ИИ · {proposals.length}</div>
            {proposals.length === 0 && <div className="secondary">предложений к этому требованию нет</div>}
            {proposals.map((p) => (
              <div key={p.id} className="rr-tnode">
                <span className="mono">{p.id}</span>
                <span className="rr-st" title={p.statement}>{p.statement}</span>
                <span style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                  <button
                    type="button" className="rr-btn"
                    onClick={() => edit.promote(p.id, 'Preliminary').then(onChanged).catch((e) => setFailure(String(e)))}
                  >
                    Принять
                  </button>
                  <button
                    type="button" className="rr-btn"
                    onClick={() => edit.cancel(p.id, author || 'инженер').then(onChanged).catch((e) => setFailure(String(e)))}
                  >
                    Отклонить
                  </button>
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

/** Состояние 5б: системное требование без носителя — настоящий разрыв.
 * Распределение прямо из полосы: селектор элементов и интерфейсов проекта. */
function CarrierBand({ id, version, author, onDone, onFail }: {
  id: string
  version: string
  author: string
  onDone: () => void
  onFail: (reason: string) => void
}) {
  const [options, setOptions] = useState<Array<{ id: string; title: string; iface: boolean }>>([])
  const [picked, setPicked] = useState('')
  useEffect(() => {
    Promise.all([edit.list('component'), edit.list('interface')])
      .then(([cms, ifs]) => setOptions([
        ...cms.map((c) => ({ id: c.id, title: c.title ?? '', iface: false })),
        ...ifs.map((c) => ({ id: c.id, title: c.title ?? '', iface: true })),
      ]))
      .catch(() => setOptions([]))
  }, [])
  return (
    <div className="rr-band">
      <b>Требование системного уровня не распределено.</b>
      <span>Системное требование обязано иметь носителя — элемент или интерфейс.</span>
      <span style={{ flex: 1 }} />
      {/* МВП-П1 §2.2: распределение — через пикер (поиск по ID и имени) */}
      <RefPicker
        value={picked}
        placeholder="выбрать носителя…"
        width={250}
        options={options.map((o) => ({ id: o.id, title: o.title }))}
        onChange={setPicked}
      />
      <button title="выберите требование в списке — действие идёт по выбранному"
        type="button" className="rr-btn rr-btn--pri" disabled={!picked}
        onClick={() => {
          const target = options.find((o) => o.id === picked)!
          const entry = target.iface
            ? { interface: target.id, kind: 'full' }
            : { component: target.id, kind: 'full' }
          edit.update(id, { allocated_to: [entry] }, version, author)
            .then(onDone)
            .catch((e) => onFail(String(e)))
        }}
      >
        Распределить
      </button>
    </div>
  )
}
