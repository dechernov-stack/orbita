// Клиент API v2. Все решения принимает сервер: здесь только вызовы и типы.
// Ни одного вычисления состояния — состояние сцен считает движок.

export type SceneState = 'locked' | 'open' | 'done'

export interface Step {
  title: string
  place: string
  hint: string
  done: boolean
}

/** Условие сцены: заголовок для человека, состояние и причина отказа. */
export interface Condition {
  title: string
  check: string
  passed: boolean
  why: string | null
  /** Блокирующее держит точку; остальное — помета. */
  blocking?: boolean
}

/** Выход мероприятия: артефакт со счётчиком из данных. */
export interface ActivityOutput {
  what: string
  kind: string
  min: number
  count: number
  /** Сцена, где артефакт рождается у нас, если это не текущая. */
  produced_in: string | null
  satisfied: boolean
}

export type ActivityState = 'not_started' | 'available' | 'in_progress' | 'done' | 'blocked'

/** Мероприятие — единица работы: цель, входы, выходы, поверхность. */
export interface Activity {
  code: string
  name: string
  goal: string
  role: string
  track: string
  method_group: string
  surface: string
  state: ActivityState
  inputs: string[]
  outputs: ActivityOutput[]
  blocked_by: string[]
  /** Что откроет эта работа: коды мероприятий и сцены — считает сервер. */
  opens: string[]
}

/** Дорожка схемы фазы: сцены либо мероприятия метода. */
export interface Lane {
  key: string
  title: string
  of: string
  activities: Activity[]
}

export interface Scene {
  key: string
  title: string
  order: number
  role: string
  question: string
  state: SceneState
  /** Чего не хватает — словами, с именами объектов. */
  blockers: string[]
  steps: Step[]
  /** Условия входа и выхода целиком: панель показывает и ✓, и ☐. */
  entry: Condition[]
  exit: Condition[]
  /** Шип G: связи сцен с обоснованием (FS · SS · FF · INPUT); экземпляр сцены на узел состава. */
  depends?: { on: string; type: string; why: string }[]
  instance_of?: string | null
  node?: string | null
  /** Что сцена даёт — для нити потока. */
  output: string
  /** Кто ждёт эту сцену: сцены и точки. */
  awaited_by: string[]
  /** Входные потоки процесса ЖЦ — данными полки. */
  input_flows: string[]
  /** Окно плана работ фазы; нет — «план не задан». */
  window?: { start: string; end: string }
  /** Мероприятия сцены: единицы работы метода. */
  activities: Activity[]
}

/** Замечание обзора (RFA/RID) с возвратом в сцену — событие движка. */
export interface Finding {
  code: string
  text: string
  scene: string
  gate: string
  status: string
  author: string
  kind: string
  question: string | null
  closed_by: string | null
}

/** Контрольная позиция экспертизы: код зрелости и наша проверка. */
export interface Position {
  artifact: string
  maturity: string
  our_ref: string | null
  check: string | null
  /** null — не сопоставлена, не проверяется. */
  passed: boolean | null
  why: string | null
  blocking: boolean
  /** Коды зрелости по всем точкам — у строки матрицы комплекта. */
  codes?: Record<string, string>
}

export interface Expertise {
  goal: string
  source: string | null
  questions: string[]
  results: string[]
  main_outcome: string[]
  positions: Position[]
}

export interface Decision {
  by: string
  at: string
  outcome: string
  note: string | null
}

export interface Gate {
  key: string
  title: string
  order: number
  planned_date: string | null
  passed: boolean
  blocking: string[]
  /** Роль, которая решает по точке. */
  role: string
  opens_phase: string | null
  checklist_of: string | null
  legend_note: string | null
  criteria: Condition[]
  expertise?: Expertise
  findings: Finding[]
  decision?: Decision
  matrix: Position[]
}

export interface PointsView {
  project: string
  phase: string
  current_scene: string | null
  items: Gate[]
}

export interface Phase {
  project: string
  standard: string
  phase: string
  current_scene: string | null
  scenes: Scene[]
  gates: Gate[]
  /** Дорожки схемы фазы: проектирование · моделирование · управление. */
  lanes: Lane[]
}

/** Задание — адресованный разрыв сцены, а не отдельная сущность. */
export interface TaskRow {
  scene: string
  scene_title: string
  role: string
  what: string
  /** Сцена ещё закрыта: работать нельзя, ждём предыдущую. */
  waiting: boolean
}

/** Факт — атом знания с якорем и меткой достоверности. */
export interface FactRow {
  id: string
  subject: string
  predicate: string
  value: string
  unit: string | null
  anchor: string | null
  /** И — наш документ · В — внешний, проверенный на дату · П — допущение. */
  mark: 'И' | 'В' | 'П'
  material: string
  /** Заведён инженером руками, а не разбором источника. */
  manual?: boolean
}

export interface PlanAction {
  kind: string
  title: string
  /** Что появится в модели — словами, до нажатия. */
  effect: string
}

export interface IntakeResult {
  task: string
  material: string
  intent: string
  note: string
  facts: FactRow[]
  plan: PlanAction[]
}

export interface CoverageNeed {
  code: string
  statement: string
  owner: string | null
  covered: boolean
  gap: string | null
  goals: string[]
  services: string[]
}

export interface CoverageMatrix {
  total: number
  covered: number
  summary: string
  needs: CoverageNeed[]
  stakeholders_without_needs: string[]
}

/** Помета линта: правило, что не так и почему это важно. */
export interface LintNote {
  rule: string
  what: string
  why: string
}

/** Строка реестра требований: шесть колонок наружу, остальное — в карточке. */
export interface RequirementRow {
  id: string
  code: string
  level: string
  title: string
  statement: string
  category: string
  ears: string
  carrier: string | null
  carrier_kind: string | null
  measure: string | null
  verification_method: string | null
  status: string
  version: number
  template_ref: string | null
  applicability: string | null
  /** Версия выше зафиксированной снимком — «изменено после утверждения». */
  after_baseline_changed: boolean
  sources: string[]
  notes: LintNote[]
}

/** Помеха базированию: объект, правило и что именно не так. */
export interface Blocker {
  code: string
  rule: string
  what: string
}

export interface BaselineItem {
  ref: string
  code: string
  kind: string
  version: number
  firmness: 'firm' | 'conditional'
  conditional_on: string | null
  why: string | null
}

export interface BaselineRow {
  name: string
  kind: string
  gate: string
  by: string
  at: string
  firm: number
  items: BaselineItem[]
  /**
   * Автоотчёт верификации после базирования: документ зафиксирован — самое
   * время спросить, сходится ли он с полем. Нет вовсе — поле знаний v2 на
   * проекте выключено, и базирование не приросло ни одной записью.
   */
  verification?: VerificationReport
}

export interface SuspectRow {
  link: string
  type: string
  from: string
  to: string
  why: string
}

export interface ComponentRow {
  id: string
  code: string
  name: string
  level: number
  nature: 'node' | 'behaviour'
  kind: string
  parent: string | null
  external: boolean
  version: number
}

export interface FacetLine {
  what: string
  ref: string | null
}

/** Грань карточки компонента; пустая говорит, чего ждут и к какой точке. */
export interface Facet {
  key: string
  title: string
  required_to: string | null
  expected: string | null
  lines: FacetLine[]
}

export interface ComponentCard {
  code: string
  name: string
  nature: string
  level: number
  gate: string
  facets: Facet[]
  gaps: { facet: string; gate: string; what: string }[]
}

export interface ArchLayer {
  key: string
  title: string
  lines: FacetLine[]
}

/** Базовый вариант построения: выбор с обоснованием и отклонёнными. */
export interface ConceptRow {
  code: string
  variant: string
  rationale: string
  decided_by: string
  at: string
  rejected: { variant: string; reason: string }[]
}

export interface TechnologyRow {
  code: string
  name: string
  component: string | null
  trl_current: number
  trl_required: number
  required_by: string
  fallback: string | null
}

export interface MaturationRow {
  technology: string
  component: string | null
  trl_current: number
  trl_required: number
  required_by: string
  package: string | null
  milestone: string | null
  fallback: string | null
  words: string
}

export interface RiskRow {
  code: string
  statement: string
  category: string
  probability: number
  impact: number
  level: number
  strategy: string
  owner: string
  due_point: string
}

export interface OdaRow {
  code: string
  variant: string
  lifetime_years: number
  dv_deorbit: string
  compliant: boolean
  norm: string
}

/** Запись модели: чем считаем, на чём и когда считали в последний раз. */
export interface ModelRow {
  code: string
  name: string
  question: string
  required_to: string
  tool: string
  verification: string
  interface: string
  inputs: string[]
  /** Чего модели не хватает, чтобы считать: разрыв, а не ноль. */
  gaps: string[]
  last_run?: {
    code: string
    at: string
    by: string
    /** Считано прокси-моделью: число правдоподобно, но не измерено. */
    proxy: boolean
    /** Входы, изменившиеся ПОСЛЕ прогона: результат устарел. */
    stale_inputs: string[]
  }
}

/** Строка свёртки: вклад узла с его зрелостью и резервом класса. */
export interface BudgetLine {
  component: string
  value: number
  unit: string
  maturity: string
  class_reserve: number
  origin: string
}

/**
 * Свёртка величины к точке: сумма, сумма с резервом класса и она же с
 * системным запасом. Два резерва — не украшение: резерв класса ставится
 * ПО ЗРЕЛОСТИ каждой строки, системный запас — один на свёртку.
 */
/**
 * Сверка свёртки с числовой границей ограничения проекта (рамка Р).
 *
 * Решает `actual` — сумма С СИСТЕМНЫМ РЕЗЕРВОМ ступени: резерв и есть
 * ожидаемый рост, и рамка обязана выдерживать его сейчас. Число по
 * классам печатается тоже — по нему видно, упирается ли изделие в рамку
 * уже сегодня.
 */
export interface FrameCheck {
  constraint: string
  statement: string
  op: string
  limit: number
  unit: string
  with_class_reserve: number
  within_by_class: boolean
  actual: number
  within: boolean
  /** Насколько превышена рамка; 0 — превышения нет. */
  excess: number
  gate: string
  system_margin_percent: number
  /** Обязательный вывод: «с системным 20 % — 122.8 кг, превышение 22.8 кг». */
  words: string
}

export interface Budget {
  kind: string
  gate: string
  sum: number
  unit: string
  with_class_reserve: number
  system_margin_percent: number
  with_system_margin: number
  note: string
  lines: BudgetLine[]
  frame: FrameCheck[]
}

/** Показатель варианта: значение против порога, без балла. */
export interface VariantMetric {
  key: string
  title: string
  value: number
  unit: string
  threshold: string
  worse_if: string
  passed: boolean
  group: string
}

export interface VariantRow {
  code: string
  name: string
  /** Чем отклонён: пустое — вариант в рассмотрении. */
  rejected_by: string
  /** На фронте Парето: никакой другой вариант не лучше по всем показателям. */
  pareto: boolean
  metrics: VariantMetric[]
}

export interface ImpactNode {
  id: string
  code: string
  kind: string
  title: string
  depth: number
}

export interface ImpactEdge {
  from: string
  to: string
  type: string
  why: string
}

export interface ImpactGraph {
  root: string
  summary: string
  nodes: ImpactNode[]
  edges: ImpactEdge[]
}

/** Строка окна взятия WBS: пакет полки и что с ним. */
export interface WbsOffer {
  code: string
  name: string
  cross_cutting: boolean
  nodes: string[]
  missing_nodes: string[]
  recommended: boolean
  taken: boolean
  why: string
}

export interface WbsRow {
  code: string
  name: string
  parent: string | null
  cross_cutting: boolean
  pbs_refs: string[]
  estimate?: { min: number; max: number; unit: string; method: string; assumptions: string; date: string }
  gaps: string[]
}

export interface ParameterRow {
  key: string
  name: string
  target: string
  origin: string
  maturity_class: string
  reserve_percent: number
  uncertainty: number
  required_to: string
  version: number
  measure: unknown
}

export interface EntityRow {
  id: string
  code: string
  status: string
  doc: Record<string, unknown>
  owned_by?: string[]
  covered_by?: string[]
}

/** Тело отказа сервера: причина словами, что делать и что не закрыто. */
export interface RefusalBody {
  error?: string
  what_to_do?: string
  /** Имя незакрытой обязательной связи: «owns→stakeholder». */
  missing?: string
  proposals?: string[]
  run?: string
}

/**
 * Отказ сервера как есть. `message` прежний — причина словами; рядом едут код
 * ответа и тело, чтобы экран показал «что делать» и имя незакрытой связи, не
 * выдумывая их сам.
 */
export class ServerRefusal extends Error {
  readonly status: number
  readonly body: RefusalBody

  constructor(message: string, status: number, body: RefusalBody = {}) {
    super(message)
    this.name = 'ServerRefusal'
    this.status = status
    this.body = body
  }
}

async function вызов<T>(путь: string, настройки?: RequestInit, повтор = true): Promise<T> {
  const ответ = await fetch(`/api/v2${путь}`, {
    headers: { 'Content-Type': 'application/json' },
    ...настройки,
  })
  const текст = await ответ.text()
  // Сервер потерял и восстановил соединение с базой посреди запроса с
  // телом (503 с `retry`): сам он тело повторить не вправе — тело есть
  // только здесь. Один повтор, дальше — отказ словами сервера.
  if (ответ.status === 503 && повтор && /"retry"\s*:\s*true/.test(текст)) {
    return вызов<T>(путь, настройки, false)
  }
  if (!ответ.ok) {
    // Отказ сервера — не «что-то пошло не так»: причина приходит словами
    // и показывается инженеру как есть.
    let причина = текст
    let тело: RefusalBody = {}
    try {
      // Тело JSON, но не объект («null», строка, число), — это не отказ
      // словами: такой ответ показывается как есть, а `body` остаётся пустым.
      const разобрано: unknown = JSON.parse(текст)
      if (разобрано !== null && typeof разобрано === 'object') тело = разобрано as RefusalBody
      причина = тело.error ?? текст
    } catch {
      /* тело не JSON — покажем как есть */
    }
    // Рядом с причиной сервер кладёт «что делать» и имя незакрытой
    // обязательной связи (422 сверки: missing=owns→stakeholder). Экран обязан
    // показать их словами сервера — поэтому тело отказа доезжает целиком, а
    // не одной строкой.
    throw new ServerRefusal(причина, ответ.status, тело)
  }
  return текст ? (JSON.parse(текст) as T) : ({} as T)
}

/** Строка портфеля: проект, который можно открыть заново. */
export interface ProjectRow {
  code: string
  name: string
  standard: string
  lead: string
  phase: string
}

/** Элемент раздела документа: запрос уже посчитан сервером. */
export interface DocElement {
  code: string
  kind: 'query' | 'statement' | 'entity_ref' | 'fact_ref' | 'table' | 'figure'
  title: string
  min_rows: number
  satisfied: boolean
  text?: string
  columns: string[]
  rows: string[][]
  supports: string[]
  waiting_scenes: string[]
  notes: string[]
}

export interface DocSection {
  no: string
  title: string
  complete: boolean
  scenes: string[]
  waiting: string[]
  elements: DocElement[]
}

export interface DocView {
  code: string
  title: string
  standard: string
  complete: number
  total: number
  sections: DocSection[]
}

/** Куда попадает работа мероприятия: строка «в документ». */
export interface DocHint {
  document: string
  section: string
  section_title: string
  elements: number
  filled: number
}

/** Строка поля знаний: факт с якорем, меткой и решением человека. */
export interface FactRow {
  id: string
  kind?: string
  subject: string
  predicate: string
  value: string
  unit: string | null
  anchor: string | null
  mark: 'И' | 'В' | 'П'
  material: string
  topic?: string | null
  disposition?: string
  /** Ключ анкеты узла — факт-параметр из даташита. */
  param_key?: string | null
  /** Факты с тем же утверждением и иным значением: показаны оба. */
  conflicts?: string[]
  /** Допущение: владелец · точка подтверждения · способ проверки · цена ошибки. */
  assumption?: { owner: string; confirm_by: string; validation: string; impact_if_wrong: string } | null
  /** Источник получил новую версию, и блок факта изменился. */
  source_updated?: string | null
  manual?: boolean
  /**
   * Ранг доверия: наследуется от материала, у руки эксперта — expert. Пусто —
   * факт заведён до перестройки: выдуманный ранг хуже отсутствующего.
   */
  authority?: Authority | null
  /** Свидетельство: claimed · corroborated · measured · assumed; поднимает сверка. */
  evidence?: string | null
  /** Источник союзом: документ с якорем ЛИБО эксперт с учёткой, ролью и датой. */
  source?: FactSourceView | null
  /** Связи с другими фактами обоими концами: противоречие живёт между документами. */
  links?: FactLinkView[]
}

/** Оценка ТЗ против нужд проекта: строка на требование ТЗ. */
export interface AtomizeJob {
  job: string
  status: 'running' | 'done' | 'failed'
  material: string
  elapsed_seconds: number
  task?: string
  note?: string
  accepted: number
  refused: number
  refusals: string[]
  error?: string
}

export interface TorAssessment {
  lines: { fact: string; requirement: string; needs: string[]; verdict: string; note?: string }[]
  /** От нужды: вердикт и дыра словами (ответ владельца 09.09 п. 1). */
  needs?: { need: string; verdict: string; gap: string; requirements: string[] }[]
  /** Дыры ТЗ одним списком — их видно первыми. */
  gaps?: string[]
  uncovered_needs: string[]
  orphan_requirements: string[]
}

/** Внешняя модель (ADR-048): элементы Capella либо fixture с баннером. */
export interface ExternalModelView {
  source: string
  model_id: string
  banner: string | null
  elements: { uuid: string; type: string; layer: string; name: string; parent_uuid: string | null }[]
  mapping: { uuid: string; component: string; name: string }[]
}

export interface MaterialRow {
  code: string
  name: string
  /** Режим разбора (mission_memo · tor · datasheet …), не доверие. */
  kind: string
  chars: number
  supersedes: string | null
  created_at?: string
  /** Ранг доверия — отдельное поле рядом с типом: доверие называет человек. */
  authority?: Authority | null
  /** Доли блоков (Д2а): ставит разбор, не инженер. */
  profile?: ContentProfile | null
}

/** Предложение сцены из поля знаний: что появится, если принять. */
export interface Suggestion {
  index: number
  target_kind: string
  title: string
  preview: string
  facts: string[]
  payload: Record<string, string>
}

export interface SceneSuggestions {
  scene: string
  task: string | null
  summary: string
  indices: number[]
  actions: Suggestion[]
}

// --- Знания v2: ранг доверия, синтез, сверка, исследование, верификация ---
//
// Клиент здесь ничего не решает и ничего не считает: счётчики групп дифа,
// доля знаний, вердикты находок и «поле изменилось: N» приходят с сервера
// готовыми (tools/validate_web_no_math.py). Имена полей — те, которые
// действительно кладут SynthesisRoutes · ReconcileRoutes · ResearchRoutes ·
// VerifyRoutes · KindJson; выдуманных полей в этом блоке нет.

/** Ранг доверия источника: обязательный · экспертный · справочный · сомнительный. */
export type Authority = 'mandatory' | 'expert' | 'reference' | 'doubtful'

/** Метка достоверности: И — наш документ · В — внешний · П — допущение. */
export type SourceMark = 'И' | 'В' | 'П'

/**
 * Источник факта — ровно одна ветка союза (истина схем `fact.source`):
 * документ с якорем либо эксперт с учёткой, ролью и датой.
 */
export type FactSourceView =
  | { material: string; anchor: string }
  | { account: string; role: string; at: string }

/** Связь факта с фактом обоими концами; без причины связь случайна. */
export interface FactLinkView {
  type: string
  from: string
  to: string
  rationale: string
}

/** Профиль содержимого материала (Д2а): доли блоков ставит разбор, не инженер. */
export interface ContentProfile {
  statement: number
  params: number
  norms: number
  assessments: number
}

/** Вердикт предложения — группа дифа «поле → постановка». */
export type SynthesisVerdict = 'new' | 'augment' | 'contradict' | 'confirm'

/** Основание предложения: факт, его ранг и место в каноне либо эксперт. */
export interface ProposalBasis {
  fact: string
  material?: string
  anchor?: string
  authority?: Authority
  /** Ранг словами — ими он и называется человеку. */
  authority_word?: string
  mark?: SourceMark
  account?: string
  role?: string
  at?: string
}

/** Предложение постановки: что синтез предлагает сделать с полем. */
export interface FormationProposal {
  /** Код карточки: им предложение отмечается в дифе и уходит обратно в accept. */
  proposal: string
  concept: string
  verdict: SynthesisVerdict
  source_mark: SourceMark
  /** Принятое понятие, о котором вердикт; пусто только у «новое». */
  target_ref?: string
  /** ЧЕМ именно отличается от принятого — поле, а не «похоже». */
  diff_field?: string
  confidence?: number
  /** Подсказка рангов в противоречии словами; победителя не выбирает. */
  rank_hint?: string
  payload: Record<string, string>
  /** Незакрытые обязательные связи: с ними предложение сущностью не станет. */
  missing: string[]
  basis: ProposalBasis[]
}

/** Диф четырьмя группами: предложение лежит только в своей. */
export interface SynthesisDiff {
  new: FormationProposal[]
  augment: FormationProposal[]
  contradict: FormationProposal[]
  confirm: FormationProposal[]
}

export type SynthesisStatus = 'queued' | 'running' | 'done' | 'error'

/** Запуск синтеза: чем вызван, по какому срезу и что вышло. */
export interface SynthesisRun {
  id: string
  trigger: string
  status: SynthesisStatus
  slice_fingerprint: string
  /** Сколько элементов поля вошло в срез; «срез урезан: N из M» — в note. */
  slice_size: number
  ontology_version: string
  /** Ответ взят из журнала по отпечатку среза — живого вызова не было. */
  cached: boolean
  note: string
  error: string
  /** Счётчики групп считает сервер. */
  counts: Record<SynthesisVerdict, number>
  /** В списке запусков дифа нет: он раскрывается по одному запуску. */
  diff?: SynthesisDiff
}

/** Синтеза на проекте ещё не было: диф пуст и объяснён словами. */
export interface NoSynthesis {
  run: string
  note: string
}

/** Ответ дифа: запуск либо объяснение, почему его нет (различать по `id`). */
export type SynthesisDiffView = SynthesisRun | NoSynthesis

/** «Поле изменилось: N» — считает сервер. */
export interface FieldDrift {
  changed: number
  /** Запуск, с которым сравнивается поле; пусто — синтеза ещё не было. */
  since: string
  fingerprint: string
}

/** Итог принятия предложений: заведённое и то, что ждёт решения человека. */
export interface SynthesisAccepted {
  /** Запуск сверки, через который прошло принятие. */
  run: string
  /** Запуск синтеза, из которого взяты предложения. */
  from: string
  created: string[]
  links: string[]
  facts: string[]
  /** Узнанное принятое: слить · уточнить · оспорить решает человек в сверке. */
  pending: { proposal: string; verdict: string }[]
  accepted: number
  note: string
}

/** Понятие онтологии формирования: им экран объясняет, откуда взялось предложение. */
export interface FormationConcept {
  code: string
  note: string
  fields: Record<string, string>
  must_link: string[]
  conflict_on: string[]
  identity: { key: string[]; semantic: string; threshold: number }
}

export interface FormationOntology {
  version: string
  concepts: FormationConcept[]
}

/** Четыре вопроса сверки — больше служба задавать не вправе. */
export type ReconcileQuestion = 'duplicate' | 'contradiction' | 'connection' | 'gap'

/** Вердикт кандидата — та же четвёрка групп, что и у дифа синтеза. */
export type ReconcileVerdict = SynthesisVerdict

/** Что человек может сделать с находкой. Одно действие на карточку. */
export type ReconcileAction =
  | 'accept_new'
  | 'merge_into'
  | 'refine'
  | 'generalize'
  | 'link_basis'
  | 'mark_contested'
  | 'fix_input'
  | 'dismiss'

/** Отличие словами: находку без него сервер отбрасывает как брак. */
export interface FieldDifference {
  /** Как соотносятся значения словами: совпало · расходится · не сравнимо. */
  comparison: string
  field: string
  /** Значение кандидата — его собственными словами. */
  mine: string
  /** Значение принятого — его собственными словами. */
  theirs: string
  reason: string
}

/** Находка сверки: что нашли, чем сравнивали и что предлагается сделать. */
export interface ReconcileFinding {
  question: ReconcileQuestion
  question_word: string
  verdict: ReconcileVerdict
  target: string | null
  /** Чем нашли, словами: «по ключу» (без токенов) либо «по смыслу». */
  match: string
  confidence: number
  /** Пока не закрыто — сущности не будет. */
  blocking: boolean
  /** Имя незакрытой обязательной связи («owns→stakeholder»). */
  missing: string | null
  compared_fields: string[]
  basis: string[]
  /** Предложения, а не план: выбирает человек. */
  offers: ReconcileAction[]
  difference?: FieldDifference
}

/** Кандидат после сверки: его факт, его находки и то, что мешает принять. */
export interface ReconcileItem {
  local_id: string
  concept: string
  /** Кандидат-факт: ручной ввод — источник, равный документу по механике. */
  candidate_fact: string
  authority: Authority
  source: FactSourceView
  verdict: ReconcileVerdict
  verdict_word: string
  /** Действие, уже применённое человеком; пусто — решение не принято. */
  decided: ReconcileAction | null
  note: string
  blocking: string[]
  findings: ReconcileFinding[]
}

/** Запуск сверки. Живёт видом `synthesis_run`: механизм один на руку и синтез. */
export interface ReconcileRun {
  run: string
  status: string
  slice_fingerprint: string
  ontology_version: string
  /** Был ли живой вызов службы: по нему мера сверяется с журналом ИИ. */
  ai_called: boolean
  open: boolean
  note: string
  items: ReconcileItem[]
}

/** Кандидат на сверку: понятие онтологии плюс то, что внесли. */
export interface ReconcileCandidate {
  /** Местный номер строки ввода («c1») — им находка вернётся в ту же строку. */
  local_id: string
  concept: string
  payload: Record<string, unknown>
  origin?: 'manual' | 'synthesis'
}

/** Решение по находке — единственный путь изменения модели. */
export interface ReconcileDecision {
  local_id: string
  /** Номер находки в карточке кандидата: решение относится к находке. */
  finding: number
  action: ReconcileAction
  target?: string
  /** Почему так решили — без причины сервер решение не ставит. */
  reason: string
  author: string
}

/** Что изменилось в модели после решения человека. */
export interface ReconcileApplied {
  created: string[]
  updated: string[]
  links: string[]
  facts: string[]
  note: string
  /** Доля знаний в процентах — считает сервер. */
  coverage: number
}

export type ResearchStatus = 'formulated' | 'launched' | 'received' | 'parsed'

/** Что принято из результата по пяти классам — считает сервер. */
export interface ResearchAccepted {
  stakeholders: number
  programs: number
  norms: number
  applications: number
  analogs: number
}

/**
 * Место входа исследования. Ровно одно из двух: сцена либо точка — обе
 * названные или ни одной сервер отбивает словами, и выбирать за него нечего.
 */
export interface ResearchPlace {
  scene?: string
  gate?: string
}

/** Задача исследования полноты: вопросы, промпт, результат, цикл. */
export interface ResearchTask {
  id: string
  /** Место словами: «сцена 3» · «точка MCR». */
  place: string
  trigger: ResearchPlace
  questions: string[]
  slice_fingerprint: string
  prompt_version: string
  iteration: number
  status: ResearchStatus
  /** Материал результата; ранг doubtful до подтверждения источников человеком. */
  result_material: string
  note: string
  accepted: ResearchAccepted
  accepted_total: number
  /** Классы, по которым не принято ничего, — словами; ими идёт следующий цикл. */
  open: string[]
}

export type VerificationIssue = 'no_basis' | 'number_vs_fact' | 'contradiction' | 'stale'

/** Находка верификации: место в документе, род расхождения и объяснение. */
export interface VerificationItem {
  /** Номер находки в отчёте — им она переносится в замечание обзора. */
  n: number
  /** Адрес места (`mid`), а не документ вообще. */
  element: string
  issue: VerificationIssue
  issue_word: string
  /** Словами, с ОБОИМИ значениями и их рангами. */
  detail: string
  /** Предложение, а не действие: кнопки «исправить» у находки нет. */
  proposal: string
}

/** Отчёт верификации: снимок расхождений документа с полем на момент `at`. */
export interface VerificationReport {
  code: string
  document: string
  baseline: string
  baseline_name: string
  at: string
  status: 'open' | 'closed'
  open: boolean
  /** Сводка словами: «проверять нечего» либо счёт находок по родам. */
  summary: string
  total: number
  by_issue: Partial<Record<VerificationIssue, number>>
  items: VerificationItem[]
}

/** Тело материала при загрузке: текст, ссылка либо файл — и ранг доверия. */
export interface MaterialBody {
  name?: string
  /** Режим разбора (mission_memo · tor · datasheet …), не доверие. */
  kind?: string
  text?: string
  url?: string
  filename?: string
  file_base64?: string
  supersedes?: string | null
  author?: string
  /**
   * Ранг доверия источника — его называет человек при загрузке. Пусто:
   * на проекте поля знаний v2 ядро отвечает отказом «ранг доверия материала
   * обязателен», на проекте прохода выводит ранг по прежнему типу входного.
   */
  authority?: Authority
}

/** Тело ручного факта: кандидат-факт эксперта — учётка · роль · дата. */
export interface FactBody {
  subject?: string
  predicate?: string
  value?: string
  unit?: string | null
  kind?: string
  topic?: string | null
  material?: string | null
  author?: string
  mark?: SourceMark
  /** Роль автора в проекте: у руки эксперта якоря нет, есть роль. */
  role?: string
  /** Умолчание ставит приём знаний (`expert`), а не экран. */
  authority?: Authority
  /** Ворота поля знаний v2: ввод идёт через сверку — «SR-7#c1». */
  reconcile?: string
  /** Решение сверки, с которым ввод сохраняется. */
  decision?: string
}

export const api = {
  /** Документы проекта с полнотой к ступени. */
  documents: (project: string) =>
    вызов<{ items: DocView[] }>(`/documents?project=${encodeURIComponent(project)}`),

  document: (project: string, code: string) =>
    вызов<DocView>(`/documents/${code}?project=${encodeURIComponent(project)}`),

  ensureDocument: (project: string, template: string, author: string) =>
    вызов<DocView>(`/documents?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ template, author }) }),

  addStatement: (project: string, code: string, тело: Record<string, unknown>) =>
    вызов<DocSection>(`/documents/${code}/statement?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  docHints: (project: string, scene: string) =>
    вызов<{ items: DocHint[] }>(
      `/documents/hints?project=${encodeURIComponent(project)}&scene=${encodeURIComponent(scene)}`),

  /** Внешняя модель (ADR-048, шип G): только чтение. */
  externalModel: (project: string) =>
    вызов<ExternalModelView>(`/external-model?project=${encodeURIComponent(project)}`),

  /** ADR-066: владелец системы выступает от имени роли; пусто — своя. */
  actAs: async (role: string | null) => {
    const r = await fetch('/api/auth/act-as', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ role: role ?? '' }) })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error ?? `HTTP ${r.status}`)
    return r.json() as Promise<{ acting_role: string | null; author: string }>
  },
  /** Вход через Telegram (ADR-065): пути /api/auth/* — вне /api/v2, поэтому fetch напрямую. */
  authStart: async () => {
    const r = await fetch('/api/auth/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error ?? `HTTP ${r.status}`)
    return r.json() as Promise<{ token: string; deep_link: string }>
  },
  authStatus: async (token: string) => {
    const r = await fetch(`/api/auth/status?token=${encodeURIComponent(token)}`)
    if (!r.ok) throw new Error((await r.json().catch(() => ({})))?.error ?? `HTTP ${r.status}`)
    return r.json() as Promise<{ status: string; login?: string; display_name?: string }>
  },

  /** Печать — файлом с сервера: ссылка, а не сборка PDF в браузере. */
  printUrl: (project: string, code: string) =>
    `/api/v2/documents/${code}/print?project=${encodeURIComponent(project)}`,

  /** Обмен (шип F): .sdoc и ReqIF собирает служба StrictDoc, знания и пакет точки — сервер. */
  sdocUrl: (project: string, grammar = false) =>
    `/api/v2/export/sdoc?project=${encodeURIComponent(project)}${grammar ? '&grammar=1' : ''}`,
  reqifUrl: (project: string) => `/api/v2/export/sdoc/reqif?project=${encodeURIComponent(project)}`,
  knowledgeZipUrl: (project: string) => `/api/v2/export/knowledge.zip?project=${encodeURIComponent(project)}`,
  pointPackageUrl: (project: string, key: string) =>
    `/api/v2/points/${encodeURIComponent(key)}/package.zip?project=${encodeURIComponent(project)}`,
  knowledgeExport: (project: string) =>
    вызов<{ fingerprint: string; parts: { key: string; file: string; title: string; size_kb: number }[] }>(
      `/export/knowledge?project=${encodeURIComponent(project)}`),

  /** Темы поля знаний: предмет фактов до разрешения в сущность. */
  topics: (project: string) =>
    вызов<{ items: { id: string; label: string; scene: string | null; facts: number; resolved_to?: string | null }[] }>(
      `/topics?project=${encodeURIComponent(project)}`),

  disposeFact: (project: string, fact: string, disposition: string, reason: string, author: string,
    assumption?: { owner: string; confirm_by: string; validation: string; impact_if_wrong: string }) =>
    вызов<FactRow>(`/facts/${fact}/disposition?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ disposition, reason, author, assumption }) }),

  /** Тема разрешается в сущность проекта: к точке принятые факты обязаны найти адрес. */
  resolveTopic: (project: string, topic: string, entity: string, author: string) =>
    вызов<{ id: string; label: string; resolved_to: string | null; facts: number }>(
      `/topics/${encodeURIComponent(topic)}/resolve?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ entity, author }) }),

  materials: (project: string) =>
    вызов<{ items: MaterialRow[] }>(`/materials?project=${encodeURIComponent(project)}`),

  /** Предложения сцены: она начинается не с пустой формы. */
  suggestions: (project: string, scene: string) =>
    вызов<SceneSuggestions>(
      `/knowledge/suggestions?project=${encodeURIComponent(project)}&scene=${encodeURIComponent(scene)}`),

  acceptPlan: (project: string, task: string, chosen: number[], author: string) =>
    вызов<{ created: number; codes: string[]; coverage: number }>(
      `/intake/${task}/accept?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ chosen, author }) }),

  knowledgeCoverage: (project: string) =>
    вызов<{ total: number; from_facts: number; from_manual_facts: number; manual: number; share_percent: number }>(
      `/knowledge/coverage?project=${encodeURIComponent(project)}`),

  /** Полка v2 одного вида: записи с документом (экран «Библиотека»). */
  shelves: (kind: string) =>
    вызов<{ items: { code: string; kind: string; doc: Record<string, unknown> }[] }>(`/shelves?kind=${encodeURIComponent(kind)}`),

  /** Живой разбор материала: факты, темы и задание с планом одним ответом. */
  atomize: (project: string, material: string, intent: string, author: string) =>
    вызов<{ task: string; note: string; accepted: number; refused: number; refusals: string[] }>(
      `/intake/atomize?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ material, intent, author }) }),

  /** Разбор фоновой задачей (ADR-069): ответ сразу — задание со статусом; готовое из журнала — сразу done. */
  atomizeJob: (project: string, material: string, intent: string, author: string) =>
    вызов<AtomizeJob>(
      `/intake/atomize?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ material, intent, author, background: true }) }),

  /** Опрос фонового разбора: готовый ответ применяется при этом обращении. */
  atomizeJobStatus: (project: string, job: string) =>
    вызов<AtomizeJob>(`/intake/jobs/${encodeURIComponent(job)}?project=${encodeURIComponent(project)}`),

  /** План задания — предпросмотр до нажатия. */
  taskPlan: (project: string, task: string) =>
    вызов<{ task: string; note: string; actions: {
      index: number; target_kind: string; scene: string; title: string; preview: string; facts: string[]
    }[]; assessment?: TorAssessment }>(`/intake/${encodeURIComponent(task)}?project=${encodeURIComponent(project)}`),

  addTopic: (project: string, label: string, author: string) =>
    вызов<{ id: string; label: string; facts: number }>(`/topics?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ label, author }) }),

  /**
   * Факт руками — кандидат-факт ЭКСПЕРТА: источник у него не документ, а
   * человек (учётка · роль · дата). На проекте поля знаний v2 ввод идёт через
   * сверку: без `reconcile` сервер отвечает 409 «ввод не сверен» и называет
   * адрес сверки.
   */
  addFact: (project: string, тело: FactBody) =>
    вызов<FactRow>(`/facts?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  phase: (project: string) => вызов<Phase>(`/phase?project=${encodeURIComponent(project)}`),

  projects: () => вызов<{ items: ProjectRow[] }>('/projects'),

  plan: (project: string) =>
    вызов<{ planned: boolean; note?: string; gate_dates?: { gate: string; date: string }[]; scene_windows?: { scene: string; start: string; end: string }[] }>(
      `/plan?project=${encodeURIComponent(project)}`),

  setPlan: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string; version: number }>(`/plan?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  glossary: (q?: string) =>
    вызов<{ items: { term_nasa: string; ru_equivalent: string; en_full: string; romanov: string }[] }>(
      `/glossary${q ? `?q=${encodeURIComponent(q)}` : ''}`),

  openProject: (тело: Record<string, unknown>) =>
    вызов<Phase>('/projects', { method: 'POST', body: JSON.stringify(тело) }),

  intent: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; status: string; phase: Phase }>(
      `/intent?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  /** З-03: правка принятой сущности на месте — новая версия, провенанс «правка инженера»; пустое значение снимает поле. */
  patchEntity: (project: string, code: string, fields: Record<string, unknown>, author = 'инженер', reason?: string) =>
    вызов<{ id: string; code: string; version: number; changed: number }>(
      `/entities/${encodeURIComponent(code)}?project=${encodeURIComponent(project)}`,
      { method: 'PATCH', body: JSON.stringify({ fields, author, reason }) }),

  entities: (project: string, kind: string) =>
    вызов<{ items: EntityRow[] }>(
      `/entities?project=${encodeURIComponent(project)}&kind=${encodeURIComponent(kind)}`,
    ),

  addStakeholder: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/stakeholders?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addNeed: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/needs?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addConstraint: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/constraints?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addService: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/services?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addGoal: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/goals?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  /**
   * Положить материал. Ранг доверия идёт с формы полем `authority`; в ответе
   * тот ранг, который ПОСТАВИЛО ядро, — форма показывает не то, что отправила,
   * а то, с чем материал теперь живёт.
   */
  putMaterial: (project: string, тело: MaterialBody) =>
    вызов<{
      code: string; chars: number; from_url: boolean
      authority?: Authority; authority_note?: string
      extracted_from?: string; snapshot_renderer?: string; snapshot_date?: string
      supersedes?: string
    }>(`/materials?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  intake: (project: string, material: string, intent: string) =>
    вызов<IntakeResult>(`/intake?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ material, intent }) }),

  facts: (project: string) => вызов<{ items: FactRow[] }>(`/facts?project=${encodeURIComponent(project)}`),

  coverage: (project: string) => вызов<CoverageMatrix>(`/coverage?project=${encodeURIComponent(project)}`),

  myTasks: (project: string, role?: string) =>
    вызов<{ project: string; items: TaskRow[]; note: string }>(
      `/my-tasks?project=${encodeURIComponent(project)}${role ? `&role=${encodeURIComponent(role)}` : ''}`,
    ),

  // --- волна 3: требования, базирование, архитектура ---

  requirements: (project: string) =>
    вызов<{ items: RequirementRow[] }>(`/requirements?project=${encodeURIComponent(project)}`),

  addRequirement: (project: string, тело: Record<string, unknown>) =>
    вызов<RequirementRow>(`/requirements?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  lint: (statement: string, ears: string) =>
    вызов<{ notes: LintNote[]; clean: boolean }>('/requirements/lint',
      { method: 'POST', body: JSON.stringify({ statement, ears }) }),

  baselines: (project: string) =>
    вызов<{ items: BaselineRow[] }>(`/baselines?project=${encodeURIComponent(project)}`),

  baselineBlockers: (project: string, kind: string, gate: string) =>
    вызов<{ items: Blocker[]; note: string }>(
      `/baselines/blockers?project=${encodeURIComponent(project)}&kind=${kind}&gate=${encodeURIComponent(gate)}`,
    ),

  baseline: (project: string, тело: Record<string, unknown>) =>
    вызов<BaselineRow>(`/baselines?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  suspects: (project: string) =>
    вызов<{ items: SuspectRow[] }>(`/suspects?project=${encodeURIComponent(project)}`),

  confirmSuspect: (project: string, link: string, author: string) =>
    вызов<{ confirmed: string }>(
      `/suspects/${encodeURIComponent(link)}/confirm?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) },
    ),

  components: (project: string) =>
    вызов<{ items: ComponentRow[] }>(`/components?project=${encodeURIComponent(project)}`),

  addComponent: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string; id: string }>(`/components?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  componentCard: (project: string, code: string, gate: string) =>
    вызов<ComponentCard>(
      `/components/${encodeURIComponent(code)}?project=${encodeURIComponent(project)}&gate=${gate}`,
    ),

  deploy: (project: string, тело: Record<string, unknown>) =>
    вызов<{ behaviour: string; node: string }>(`/components/deploy?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  technologies: (project: string) =>
    вызов<{ items: TechnologyRow[] }>(`/technologies?project=${encodeURIComponent(project)}`),

  addTechnology: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string }>(`/technologies?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  maturation: (project: string) =>
    вызов<{ items: MaturationRow[] }>(`/maturation?project=${encodeURIComponent(project)}`),

  risks: (project: string) =>
    вызов<{ items: RiskRow[] }>(`/risks?project=${encodeURIComponent(project)}`),

  addRisk: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string }>(`/risks?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  oda: (project: string) =>
    вызов<{ items: OdaRow[] }>(`/oda?project=${encodeURIComponent(project)}`),

  addOda: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string }>(`/oda?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  wbs: (project: string) =>
    вызов<{ items: WbsRow[] }>(`/wbs?project=${encodeURIComponent(project)}`),

  wbsOffer: (project: string) =>
    вызов<{ items: WbsOffer[]; total: number; recommended: number; taken: number }>(
      `/wbs/offer?project=${encodeURIComponent(project)}`),

  /** Пусто в codes — рекомендованный набор: пакеты с парой плюс сквозные. */
  takeWbs: (project: string, codes: string[] = []) =>
    вызов<{ taken: number; already: number; total: number }>(
      `/wbs/take?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ codes, author: 'стенд' }) }),

  estimate: (project: string, тело: Record<string, unknown>) =>
    вызов<{ min: number; max: number }>(`/wbs/estimate?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  concept: (project: string) =>
    вызов<{ items: ConceptRow[] }>(`/concept?project=${encodeURIComponent(project)}`),

  setConcept: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string }>(`/concept?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  architecture: (project: string) =>
    вызов<{ layers: ArchLayer[] }>(`/architecture?project=${encodeURIComponent(project)}`),

  parameters: (project: string, component?: string) =>
    вызов<{ items: ParameterRow[] }>(
      `/parameters?project=${encodeURIComponent(project)}` +
      (component ? `&component=${encodeURIComponent(component)}` : ''),
    ),

  addParameter: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string; id: string }>(`/parameters?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  models: (project: string) =>
    вызов<{ items: ModelRow[] }>(`/models?project=${encodeURIComponent(project)}`),

  takeModels: (project: string, author: string) =>
    вызов<{ taken: number }>(`/models/take?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) }),

  runModel: (project: string, model: string, outputs: Record<string, string>, author: string) =>
    вызов<{ code: string; at: string; proxy: boolean }>(
      `/models/${encodeURIComponent(model)}/run?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ outputs, author }) }),

  budget: (project: string, kind: string, gate: string) =>
    вызов<Budget>(`/budget?project=${encodeURIComponent(project)}` +
      `&kind=${encodeURIComponent(kind)}&gate=${encodeURIComponent(gate)}`),

  variants: (project: string) =>
    вызов<{ note: string; items: VariantRow[] }>(`/variants?project=${encodeURIComponent(project)}`),

  impact: (project: string, code: string, depth = 2) =>
    вызов<ImpactGraph>(`/impact?project=${encodeURIComponent(project)}` +
      `&code=${encodeURIComponent(code)}&depth=${depth}`),

  passGate: (project: string, gate: string, author: string) =>
    вызов<Phase>(`/gates/${encodeURIComponent(gate)}/pass?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) }),

  /** Точки фазы: критерии, экспертиза, замечания, решение, матрица. */
  points: (project: string) => вызов<PointsView>(`/points?project=${encodeURIComponent(project)}`),
  addFinding: (project: string, gate: string, тело: { text: string; returns_to_scene: string; question?: string; kind?: string }) =>
    вызов<Finding>(`/points/${encodeURIComponent(gate)}/findings?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),
  closeFinding: (project: string, code: string, note: string) =>
    вызов<Finding>(`/findings/${encodeURIComponent(code)}/close?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ note }) }),
  /** Решение по точке: approve · return · defer — роль и блокирующие проверяет сервер. */
  decide: (project: string, gate: string, outcome: string, note: string) =>
    вызов<Phase & { point: Gate }>(`/points/${encodeURIComponent(gate)}/decide?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ outcome, note }) }),

  // --- Знания v2: синтез · сверка · исследование · верификация -------------
  //
  // Все адреса под флагом проекта `knowledge_v2`: на проекте прохода ПМИ-5
  // сервер отвечает 409 и словами «что делать» — экран показывает их как есть
  // (текст отказа доезжает в ServerRefusal.body.what_to_do).

  /**
   * Сформировать постановку из поля. Сервер отвечает ЗАДАНИЕМ (202 queued |
   * running), экран опрашивает synthesisState — как atomizeJob (ADR-069).
   * Готовый ответ из журнала по тому же отпечатку среза приходит сразу
   * `done` с `cached: true`, и второго живого вызова не случается.
   */
  synthesize: (project: string, trigger = 'manual', author = 'инженер') =>
    вызов<SynthesisRun>(`/synthesis/runs?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ trigger, author }) }),

  /** Опрос запуска: готовый ответ службы применяется при этом обращении. */
  synthesisState: (project: string, run: string) =>
    вызов<SynthesisRun>(
      `/synthesis/runs/${encodeURIComponent(run)}?project=${encodeURIComponent(project)}`),

  /** Журнал запусков без предложений: диф раскрывается по одному запуску. */
  synthesisRuns: (project: string) =>
    вызов<{ items: SynthesisRun[] }>(`/synthesis/runs?project=${encodeURIComponent(project)}`),

  /** Диф последнего доведённого запуска — то, что показывает вкладка постановки. */
  synthesisDiff: (project: string) =>
    вызов<SynthesisDiffView>(`/synthesis/diff?project=${encodeURIComponent(project)}`),

  /** «Поле изменилось: N» — число считает сервер, экран его показывает. */
  synthesisPending: (project: string) =>
    вызов<FieldDrift>(`/synthesis/pending?project=${encodeURIComponent(project)}`),

  /**
   * Принять отмеченные предложения — через сверку, теми же четырьмя
   * вопросами, что и ручной ввод. Заводится только названное «новым»:
   * узнанное принятое остаётся в `pending` и решается человеком.
   * Незакрытая обязательная связь — 422 с именем связи в `body.missing`.
   */
  acceptSynthesis: (
    project: string,
    run: string,
    chosen: string[],
    author: string,
    reason?: string,
    role?: string,
  ) =>
    вызов<SynthesisAccepted>(
      `/synthesis/runs/${encodeURIComponent(run)}/accept?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ chosen, author, reason, role }) }),

  /** Истина онтологии наружу: по ней экран объясняет, откуда взялось понятие. */
  formationOntology: (project: string) =>
    вызов<FormationOntology>(`/ontology/formation?project=${encodeURIComponent(project)}`),

  /**
   * Сверить ввод. Модель НЕ меняется: заводятся только кандидаты-факты и
   * запись запуска. Батч идёт ОДНИМ запросом — десять строк ввода дают один
   * запуск и одну запись в журнале ИИ.
   *
   * @param semantic звать ли вторую ступень (смысл) там, где ключ не совпал
   */
  reconcile: (
    project: string,
    candidates: ReconcileCandidate[],
    author: string,
    role: string,
    semantic = false,
  ) =>
    вызов<ReconcileRun>(`/reconcile?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ candidates, author, role, semantic }) }),

  /** Решение человека по находке — единственный путь изменения модели. */
  reconcileApply: (project: string, run: string, решение: ReconcileDecision) =>
    вызов<ReconcileApplied>(
      `/reconcile/${encodeURIComponent(run)}/apply?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(решение) }),

  /** Запуски с нерешёнными кандидатами: что ждёт человека. */
  reconcileOpen: (project: string) =>
    вызов<{ items: ReconcileRun[]; count: number }>(
      `/reconcile?project=${encodeURIComponent(project)}&status=open`),

  /** Повторное чтение запуска: идемпотентно, живого вызова не делает. */
  reconcileRun: (project: string, run: string) =>
    вызов<ReconcileRun>(
      `/reconcile/${encodeURIComponent(run)}?project=${encodeURIComponent(project)}`),

  /** Задачи исследования полноты проекта. */
  research: (project: string) =>
    вызов<{ items: ResearchTask[] }>(`/research?project=${encodeURIComponent(project)}`),

  researchTask: (project: string, task: string) =>
    вызов<ResearchTask>(
      `/research/${encodeURIComponent(task)}?project=${encodeURIComponent(project)}`),

  /**
   * Сформулировать вопросы для места входа. Пустые `questions` — сервер
   * соберёт их по пяти классам из среза поля; снять и дописать может
   * человек, придумать шестой класс не может.
   */
  formulateResearch: (
    project: string,
    place: ResearchPlace,
    author: string,
    questions: string[] = [],
  ) =>
    вызов<ResearchTask>(`/research?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ ...place, author, questions }) }),

  /** Промпт — файлом с сервера: ссылка, а не сборка .md в браузере. */
  researchPromptUrl: (project: string, task: string) =>
    `/api/v2/research/${encodeURIComponent(task)}/prompt.md?project=${encodeURIComponent(project)}`,

  /** Запущено вне продукта: задача ждёт результата. Вызова модели здесь нет. */
  launchResearch: (project: string, task: string, author: string, note = '') =>
    вызов<ResearchTask>(
      `/research/${encodeURIComponent(task)}/launch?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author, note }) }),

  /** Принять результат внешнего контура материалом; ранг ему ставит сервер. */
  researchResult: (project: string, task: string, name: string, text: string, author: string) =>
    вызов<ResearchTask>(
      `/research/${encodeURIComponent(task)}/result?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ name, text, author }) }),

  /**
   * Разобрать результат исследования.
   *
   * Отдельного разбора у исследования нет: на `/v2/research/{RT}/parse`
   * сервер отвечает отказом и называет ОБЩИЙ путь загрузки — им материал и
   * разбирается, фоновой задачей (ADR-069, опрос через atomizeJobStatus).
   * Статус «разобрано» задача исследования выводит по фактам своего
   * материала сама — второго нажатия не нужно.
   */
  parseResearch: (
    project: string,
    material: string,
    intent = 'разбери по сущностям',
    author = 'инженер',
  ) =>
    вызов<AtomizeJob>(`/intake/atomize?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ material, intent, author, background: true }) }),

  /**
   * Подтвердить источники названных фактов результата. До подтверждения
   * материал сомнителен, а факты не приняты: поднимает их сервер.
   */
  confirmSources: (project: string, task: string, facts: string[], author: string) =>
    вызов<ResearchTask>(
      `/research/${encodeURIComponent(task)}/sources?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ facts, author }) }),

  /** Следующий цикл по тому же месту: вопросы по классам, где пусто. */
  nextResearch: (project: string, task: string, author: string, questions: string[] = []) =>
    вызов<ResearchTask>(
      `/research/${encodeURIComponent(task)}/next?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author, questions }) }),

  /**
   * Проверить документ против поля. Не правит НИЧЕГО: версии документа,
   * версии сущностей и диспозиции фактов остаются прежними, а каждая
   * проверка заводит свой отчёт.
   */
  verifyDocument: (project: string, document: string, author: string, baseline?: string) =>
    вызов<VerificationReport>(
      `/documents/${encodeURIComponent(document)}/verify?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author, baseline }) }),

  /** Отчёты документа: история расхождения, а не последний снимок. */
  verification: (project: string, document: string) =>
    вызов<{ document: string; items: VerificationReport[] }>(
      `/documents/${encodeURIComponent(document)}/verification?project=${encodeURIComponent(project)}`),

  verificationReport: (project: string, code: string) =>
    вызов<VerificationReport>(
      `/verification/${encodeURIComponent(code)}?project=${encodeURIComponent(project)}`),

  /** Закрытие — решение человека, а не срок давности: причина обязательна. */
  closeVerification: (project: string, code: string, author: string, reason: string) =>
    вызов<VerificationReport>(
      `/verification/${encodeURIComponent(code)}/close?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author, reason }) }),

  /**
   * Находка → замечание обзора: возврат идёт в сцену РАЗДЕЛА, где находка
   * сидит. Сцену, точку и род замечания сервер выводит сам, если их не
   * назвали.
   */
  verificationFinding: (
    project: string,
    code: string,
    n: number,
    тело: { author: string; returns_to_scene?: string; gate?: string; kind?: string },
  ) =>
    вызов<Finding & { verification: string; item: number }>(
      `/verification/${encodeURIComponent(code)}/items/${n}/finding` +
      `?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  /**
   * Слить тему в другую: ИИ предлагает тождество связью `same_as`, сливает
   * человек — поэтому автор обязателен. В ответе ГОЛОВА цепочки: адрес, по
   * которому теперь читаются факты обеих.
   */
  mergeTopic: (project: string, topic: string, into: string, author: string, reason = '') =>
    вызов<{
      id: string; label: string; scene: string | null; resolved_to: string | null; facts: number
      merged: string; merged_into: string
    }>(
      `/topics/${encodeURIComponent(topic)}/merge?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ into, author, reason }) }),
}
