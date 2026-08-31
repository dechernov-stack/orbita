// Формы ответов API ядра (core/out/ScreenViews.kt).
//
// Это ЗЕРКАЛО серверных структур, а не отдельная модель: клиент ничего не
// достраивает и не пересчитывает. Если поля здесь не хватает — его добавляют
// на сервере, а не вычисляют на месте (STEP-6 §3.2, ловушка 5).

/** Условие требования: оператор, число, код единицы и готовая строка. */
export interface ConditionView {
  name: string | null
  operator: string | null
  value: number | null
  valueMax: number | null
  tolerance: number | null
  /** Код СИ — то, что хранится в модели. */
  unit: string | null
  /** Подпись для отображения; подставлена сервером. */
  unitLabel: string | null
  /** Готовая строка условия, напр. «≤ 60 кг». */
  rendered: string | null
}

export interface BudgetSegment {
  label: string
  value: number
  reserve: boolean
}

/** Полоса бюджета, посчитанная сервером: клиент её только рисует. */
export interface BudgetBar {
  segments: BudgetSegment[]
  used: number
  limit: number
  remaining: number
  overrun: boolean
  overrunValue: number | null
  /** Доля заполнения в процентах — посчитана сервером, клиент её не делит. */
  fillPercent: number
}

export interface RequirementRow {
  id: string
  depth: number
  hasChildren: boolean
  statement: string
  category: string | null
  level: string | null
  status: string
  condition: ConditionView | null
  budget: BudgetBar | null
  budgetOverrun: boolean
  verificationState: string
  method: string | null
  approach: string | null
  planIssues: string[]
  /** Откуда следует (traces_up) — «родители» смысла: нужды, сервисы, цели. */
  sources: string[]
  /** На что распределено: элементы и интерфейсы. */
  allocatedTo: string[]
  /** Т-1: вид требования — до Г5 вычисляется сервером (numeric при mop). */
  kind: 'numeric' | 'text'
  rationale: string | null
  version: string
  owner: string | null
  origin: string | null
  /** Родитель-требование (derive); null у корней. */
  parentId: string | null
  /** Имя первого носителя — строка рисуется без догрузок. */
  carrierName: string | null
  /** Пометы с сервера — клиент их семантику не вычисляет. */
  recalcAfterBaseline: boolean
  changedAfterApproval: boolean
  /** Разрывы стратифицированы по уровням: сирота — только системное. */
  noCarrierGap: boolean
  noNeedGap: boolean
}

/** Сохранённый вид реестра (Т-1): серверный объект, не localStorage. */
export interface SavedViewDoc {
  id?: string
  name: string
  section: 'requirements'
  scope: 'personal' | 'project'
  owner_login?: string
  columns: Array<{ key: string; on: boolean }>
  sort?: { key: string; dir: 'asc' | 'desc' }
  filters?: { gap?: string; search?: string }
  grouping?: 'carrier' | 'level' | 'status' | 'owner'
  form: 'tree' | 'flat'
  version?: string
}

export interface RequirementTreeView {
  roots: string[]
  children: Record<string, string[]>
  rows: RequirementRow[]
  /** Нужды без единого требования — «нужда не покрыта», счётчик реестра. */
  needsUncovered: string[]
  /** Корень системы — носитель проектных требований; null — не определён. */
  systemRoot: { id: string; name: string | null } | null
  compositionRoots: number
}

export interface EventView {
  id: string
  method: string | null
  kind: string | null
  phase: string | null
  level: string | null
  closes: boolean
  status: string | null
  approach: string | null
  means: string | null
  evidenceRef: string | null
  evidenceStale: boolean
  issues: string[]
}

export interface RequirementCard {
  row: RequirementRow
  successCriterion: string | null
  sources: string[]
  allocatedTo: string[]
  events: EventView[]
}

export interface SpecificationRow {
  id: string
  statement: string
  condition: ConditionView | null
  source: string | null
  derivationKind: string | null
  verificationState: string
  eventsDone: number
  eventsTotal: number
  status: string
}

export interface ComponentSpecification {
  componentId: string
  rows: SpecificationRow[]
  budgets: Record<string, BudgetBar>
}

/** Подписи единиц: подстановка на стороне представления, коды СИ в модели. */
export type UnitLabels = Record<string, string>

// ---------- экран 7: сравнение вариантов ----------

export interface RadarOption {
  name: string
  values: Record<string, number>
}

export interface RadarSeriesEntry {
  name: string
  values: number[]
}

/** Диаграмма несёт состав набора, по которому нормирована: без него её сравнят с чужой. */
export interface RadarChart {
  axes: string[]
  series: RadarSeriesEntry[]
  normalizedOver: string[]
}

export interface ComparisonView {
  options: RadarOption[]
  radar: RadarChart
  paretoFront: string[]
  axes: string[]
  /** Оси, фактически имеющиеся во всех вариантах, — из них выбирают (§3.5). */
  availableAxes: string[]
  /** Подписи показателей из реестра направлений; без подписи — ключ. */
  axisLabels: Record<string, string>
}

// ---------- экран 12: система в целом ----------

export interface RiskCell {
  probability: number
  impact: number
  /** Критичность клетки посчитана сервером: раскраска — следствие правила. */
  criticality: string
  risks: string[]
}

export interface RegisterSummary {
  total: number
  active: number
  distribution: Record<string, number>
  escalate: string[]
  closedRetained: string[]
}

export interface SystemOverview {
  requirements: number
  components: number
  verification: Record<string, number>
  budgets: Record<string, BudgetBar>
  budgetsOverrun: string[]
  riskSummary: RegisterSummary
  riskMatrix: RiskCell[]
  problems: string[]
}

// ---------- экраны мастера (шаг 9) ----------

export interface NeedRow {
  id: string
  statement: string
  stakeholder: string
  role: string
  priority: number
  /** Пустой список — нужда не порождает сервисов: разрыв трассировки. */
  services: string[]
  status: string
}

export interface MoeRow {
  id: string
  name: string
  target: number | null
  unit: string | null
}

export interface QosProfileRow {
  consumerClass: string
  moe: MoeRow[]
}

export interface ServiceRow {
  id: string
  name: string
  needs: string[]
  profiles: QosProfileRow[]
  /** Классы, присутствующие в спросе, но без профиля. */
  uncoveredClasses: string[]
  requirements: string[]
  status: string
}

export interface GateGap {
  id: string
  actual: string
  required: string
}

export interface ReadinessView {
  gate: string
  gaps: GateGap[]
  ready: boolean
  readyObjects: number
  totalObjects: number
}

export interface WizardStep {
  number: number
  title: string
  objects: number
  issues: string[]
  complete: boolean
}

export interface RiskRegisterView {
  summary: RegisterSummary
  risks: Array<Record<string, unknown>>
  matrix: RiskCell[]
}

// ---------- экран 4: карта спроса ----------

/** Ячейка карты. `intensity` — доля от максимума, посчитанная сервером. */
export interface DemandCellView {
  halfLatDeg: number
  halfLonDeg: number
  id: string
  latDeg: number
  lonDeg: number
  areaKm2: number
  msgsPerDay: number
  /** Спрос по классам раздельно: классы не усредняются (Р9). */
  byClass: Record<string, number>
  weight: number
  intensity: number
}

export interface LatitudeBandView {
  bandDeg: number
  weight: number
}

export interface PopulationContribution {
  id: string
  consumerClass: string
  terminals: number
  msgsPerDay: number
  share: number
  cells: number
}

export interface ReferenceScenarioRow {
  id: string
  name: string
  consumerClass: string
  geography: string
  terminals: number
  msgsPerTerminalDay: number
  mobilityModel: string
}

/** Пик спроса: худшее сочетание часа и месяца, а не среднее. */
export interface DemandPeak {
  hour: number
  month: number
  msgsPerS: number
  profiled: boolean
}

export interface DemandMapView {
  version: string
  cells: DemandCellView[]
  totalMsgsPerDay: number
  byClass: Record<string, number>
  terminalsByClass: Record<string, number>
  peak: DemandPeak
  latitudeProfile: LatitudeBandView[]
  contributions: PopulationContribution[]
  layers: string[]
  issues: string[]
}

/** Слои карты, как их задаёт экран (тело запроса). */
export interface DemandLayersRequest {
  population: Array<{
    id: string
    lat: number
    lon?: number
    pop_density_per_km2: number
    terminals_per_capita: number
    msgs_per_terminal_day: number
    consumer_class: string
  }>
  point_objects: Array<{
    cell_id: string
    lat: number
    lon?: number
    terminals: number
    msgs_per_terminal_day: number
    consumer_class: string
  }>
  scenario_ids: string[]
}

// ---------- экран 5: модель космического аппарата ----------

export interface PresetRow {
  id: string
  name: string
  dryMassKg: number
  saAreaM2: number
  batteryWh: number
  busPowerW: number
  payloadPowerW: number
  designLifeYears: number
}

export interface MassRow {
  name: string
  massKg: number
  maturity: string
  marginPct: number
  withMarginKg: number
}

export interface MassBudgetView {
  items: MassRow[]
  nominalKg: number
  systemMarginPct: number
  dryMassKg: number
  wetMassKg: number
  deltaVMs: number
  /** Р2/ADR-002: диапазон 12U–100 кг. */
  withinPlatformRange: boolean
}

/** Баланс считается при заявленной скважности ПН, а не при допустимой. */
export interface PowerView {
  altKm: number
  worstBetaDeg: number
  generatedWh: number
  consumedWh: number
  balanceWh: number
  beaconWh: number
  plannedPayloadDuty: number
  allowedPayloadDuty: number
  batteryDod: number
  batteryMaxDod: number
  balanceOk: boolean
  dutyOk: boolean
  dodOk: boolean
}

export interface LinkRow {
  id: string
  role: string
  bandHz: number
  eirpDbw: number
  bitrateBps: number
  requiredMarginDb: number
  marginAtZenithDb: number
  marginAtMinElevDb: number
  serviceElevationDeg: number | null
  limitingFactor: string
  closes: boolean
}

export interface BeaconView {
  format: string
  periodS: number
  payloadBytes: number
  downlinkLoad: number
  energyWhPerOrbit: number
}

export interface TpmRow {
  name: string
  current: number
  unit: string
  target: number
  marginPct: number
  requiredMarginPct: number
  breached: boolean
  lowerIsBetter: boolean
}

export interface SpacecraftView {
  id: string
  preset: string | null
  mass: MassBudgetView
  power: PowerView
  links: LinkRow[]
  beacon: BeaconView | null
  tpm: TpmRow[]
  issues: string[]
}

// ---------- экран 8: предложение ИИ ----------

export interface PromptPackage {
  id: string
  kind: string
  context: unknown
  task: string
  response_schema: unknown
}

export interface DiffEntryView {
  op: 'add' | 'change' | 'keep' | 'same'
  from?: unknown
  to?: unknown
  value?: unknown
}

export interface ScreenedProposal {
  item: Record<string, unknown>
  diff: Record<string, DiffEntryView>
}

export interface AnswerReport {
  package_id: string
  proposed: number
  malformed: Array<{ item?: unknown; errors: string[] }>
  shown: ScreenedProposal[]
  rework: { proposed: number; rejected: number; rework: Array<{ item: unknown; issues: string[] }> }
  by_rule: Record<string, number>
}

/**
 * Карта покрытия (шаг 16 §2.2). Все значения и класс каждой ячейки посчитаны
 * сервером; клиент красит по классу и подписывает числа — своих порогов,
 * средних и нормировок в клиенте нет (ловушка 2).
 */
export interface CoverageCell {
  /** §5: ёмкостная мера — проходо-минуты (все сервисные пролёты, без слияния). */
  pass_minutes: number
  /** §6: запас — проходо-минуты на сообщение спроса; нет спроса — поля нет. */
  margin_min_per_msg?: number
  demand_by_class?: Record<string, number>
  half_lat_deg: number
  half_lon_deg: number
  cell_id: string
  lat_deg: number
  lon_deg: number
  availability_mean: number
  availability_worst: number
  /** Среднесуточная, взвешенная профилем активности ячейки (ловушка 3). */
  availability_weighted?: number
  class: 'ok' | 'degraded' | 'gap'
  access_windows: number
  mean_gap_s?: number
  max_gap_s?: number
  revisit_s?: number
}

export interface CoverageView {
  scenario_ref: string
  horizon: 'orbit' | 'day' | 'run'
  horizons: { orbit_s: number; day_s: number; run_s: number }
  cells: CoverageCell[]
  /** §5 МВП-М1: числовая шкала и баланс — статистика карты от сервера. */
  map_stats: {
    pass_minutes_min: number
    pass_minutes_max: number
    pass_minutes_total: number
    cells_out_of_view: number
    /** §6 МВП-М3: слой «запас» — обслуживаемо/спрос. */
    margin_min: number
    margin_max: number
    demand_max_by_class?: Record<string, number>
    demand_total_by_class?: Record<string, number>
  }
  constellation: {
    total_sats: number
    subgroups: Array<{
      name: string; kind: string; planes: number; per_plane: number
      altitude_km: number; inclination_deg: number; sats: number
    }>
  }
}

/** МВП-М2: строка сравнения — метрики группами; Г — прокси с пометкой. */
export interface CompareVariantRow {
  variant: string
  name: string
  total_sats: number
  subgroups: Array<{ name: string; kind: string; planes: number; per_plane: number
    altitude_km: number; inclination_deg: number }>
  service: Record<string, {
    coverage_share: number; max_gap_s: number; revisit_p75_s?: number
    mean_response_s: number; latency_s: number; capacity_margin_min_per_msg?: number
  }>
  logistics: { launch_batches: number; deployment_days: number; cost_proxy: number }
  resilience: { degradation_dmax_gap_s: number; station_keeping_dv_mps_year: number; disposal: string }
  orbit_proxy: {
    proxy: boolean
    power_regime: Array<{ name: string; beta_min_deg: number; beta_max_deg: number; worst_shadow_share: number }>
    radiation_class: string; radiation_note: string
    stations_for_latency: number; stations_names: string
    median_pass_s?: number; doppler_max_hz: number
  }
}

export interface ConstellationCompareView {
  scenario_ref: string
  computed_at: string
  working_variant: string
  variants: CompareVariantRow[]
  excluded: Array<{ variant: string; name: string; threshold: string; metric: string; value: number; limit: number }>
  axes: string[]
  pareto: string[]
}

/** §6: наземные трассы подгрупп — каждая своим цветом (индекс от сервера). */
export interface GroundTracksView {
  scenario_ref: string
  duration_s: number
  subgroups: Array<{
    name: string; kind: string; color_index: number
    tracks: Array<{ sat: string; points: Array<[number, number]> }>
  }>
}

/** §6: географические маски зон — точки с радиусами. */
export interface GeoMasksView {
  rx_radius_km: number
  downlink_radius_km: number
  rx: Array<[number, number]>
  downlink: Array<[number, number]>
}

/** Строка расписания пролётов (шаг 16 §2.3): времена в UTC посчитаны сервером. */
export interface PassRow {
  spacecraft_ref: string
  target_ref: string
  start_utc: string
  end_utc: string
  duration_s: number
  in_service_zone: boolean
}

/**
 * Глобус от модели проекта: CZML собран сервером из хранимых объектов по
 * ссылкам сценария — группировка, станции, ячейки спроса, зоны обслуживания.
 */
export interface GlobeView {
  scenario_ref: string
  epoch: string
  duration_s: number
  czml: unknown[]
  passes: PassRow[]
  /** Полное число окон; расписание обрезано до первых по времени. */
  passes_total: number
  passes_truncated: boolean
}

/** Матрица трассировки (TZ-OUT-004): строка на требование, разрывы отдельно. */
export interface TraceMatrixView {
  rows: Array<{
    requirement: string
    needs: string[]
    services: Array<{ id: string; consumer_class: string }>
    elements: string[]
    method: string | null
  }>
  gaps: Array<{ requirement: string; missing: string }>
}

/**
 * Матрица верификации (шаг 16 §2.4): строка на пару «требование × событие»,
 * разрывы — отдельным списком, а не пустой ячейкой; рядом непокрытые (TZ-REQ-008).
 */
export interface VerificationMatrixFlatView {
  rows: Array<{
    requirement: string
    event: string
    method: string | null
    level: string | null
    closes: boolean
    approach: string
    status: string | null
    evidence_ref: string | null
    evidence_stale: boolean
  }>
  gaps: Array<{ requirement: string; event: string | null; reason: string }>
  unverified: string[]
}

export interface ValidationRow {
  validation: string
  target: string
  conops_ref: string | null
  product_kind: string | null
  method: string | null
  phase: string | null
  status: string | null
  evidence_ref: string | null
}

/** Устаревший результат: входы изменились после расчёта (TZ-MOD-007). */
export interface StaleResultRow {
  pk: number
  scenario_id: string
  kind: string
}

/** Зрелость пакета к контрольной точке (TZ-OUT-003). */
export interface MaturityView {
  gate: string
  at?: string
  ready: boolean
  blocking: string[]
  gaps_by_type: Record<string, Array<{ id: string; actual: string; required: string; owner: string | null }>>
  open_tbd: Array<{ id: string; owner: string | null }>
  trace_breaks: string[]
  unverified: string[]
}

/** Неакцептованное предложение ИИ (TZ-AI-004). */
export interface UnacceptedAiRow {
  object_id: string
  name: string
  prompt_package_id: string
}

/** Циклограмма из географических масок (TZ-KA-009): сгенерированные доли рядом с ручными. */
export interface MaskScheduleView {
  mask_version: string
  rx_cells: number
  downlink_cells: number
  generated_orbit_fractions: Record<string, number>
  model_orbit_fractions?: Record<string, number>
}

/** Режим канала адаптера (TZ-NET-001): параметры отдаёт только адаптер. */
export interface ProtocolAdapterView {
  id: string
  name: string
  phy: {
    modulation: string
    modes: Array<{
      mode_id: string
      bitrate_bps: number
      required_ebn0_db: number
      time_on_air_ms_per_byte: number
      doppler_tolerance_hz: number
    }>
  }
  mac: Record<string, unknown>
  calibration?: Record<string, unknown>
}

/** Узкие места из сохранённого прогона (TZ-OUT-002): «не считали» ≠ «пусто». */
export interface BottlenecksReport {
  name: string
  executed: boolean
  entries: Array<{ scenario_ref: string; location: string; utilization: number }>
}

/** Документ БП-PA из модели (TZ-OUT-001): разделы регламента + разрывы. */
export interface GeneratedDocumentView {
  body: {
    template: string
    title: string
    source: string
    sections: Array<{
      number: number
      title: string
      expects: string
      items: Array<Record<string, unknown>>
    }>
  }
  digest: string
  gaps: Array<{ section: number; what: string; expected: string }>
}

/** Рекомендательное размещение станций (шаг 12.1): подбор поверх ручных. */
export interface GroundSuggestView {
  suggested: Array<{
    id: string
    name: string
    lat_deg: number
    lon_deg: number
    placement: string
    gain: number
  }>
  coverage_before: number
  coverage_after: number
}

/** Контрольные точки (Шаг 17 C4): из проекта, без него — из реестра ворот. */
export interface GatesView {
  source: 'project' | 'registry'
  project_ref?: string
  project_name?: string
  phase?: string
  gates: Array<{ gate: string; due?: string | null; held?: boolean }>
}

/** Выпуски документа со сверкой слепков (Шаг 17 C5). */
export interface DocumentIssuesView {
  template: string
  current_digest: string
  issues: Array<{
    id: string
    digest: string
    issued_at: string
    status: string
    gaps: number
    stale: boolean
  }>
}

/** Д1: карта разбора документа — координаты и находки, текста не несёт. */
export interface DocumentParseMap {
  parser_version: number
  fingerprint: string
  source_document: string
  source_file: string
  canonical_text: string
  structure: Array<{
    anchor: string
    type: 'title' | 'section' | 'para' | 'table'
    title?: string
    level?: number
    blocks?: string[]
    /** таблицы: строк без шапки, ключевая колонка (адрес строки t1#15), колонки */
    rows?: number
    row_key?: string
    cols?: string[]
    section?: string
  }>
  numbers: Array<{
    block: string
    unit: string
    value: number | { min: number; max: number }
    canonical?: { unit: string; value: number | { min: number; max: number } }
    converted_from?: string
  }>
  terms: Array<{ term: string; blocks: string[] }>
  normative_candidates: Array<{ mention: string; block: string }>
  summary: {
    blocks: number
    sections: number
    tables: number
    numbers: number
    terms: number
    normative_candidates: number
    source_chars: number
    canon_chars: number
  }
}

/** Д2: урожай смыслового разбора и адреса его раскладки. */
export interface DocumentHarvestView {
  kind: string
  source_document: string
  parser?: string
  note?: string
  schema_note?: string
  items: Array<{
    class: string
    block?: string | string[]
    anchor?: string
    name?: string
    statement?: string
    role?: string
    establishes?: boolean
    need_ref?: boolean
    priority?: boolean
    /** Ф-08.3: метка достоверности — [И] внутренний документ, [В] внешний
     *  проверенный источник, [П] предлагаемая цель либо допущение. */
    source_mark?: 'И' | 'В' | 'П' 
    scale?: string
    schema_note?: string
    scores?: Record<string, number>
    horizon?: number
    /** Готовит сервер: величина каноном строкой и координаты блоков строкой. */
    display?: string
    blocks_label?: string
  }>
  derived?: Array<{ kind: string; derived: true; note?: string; rows?: Array<Record<string, unknown>> }>
  summary: Record<string, number>
  targets: Record<string, {
    where: string
    type: string
    note?: string
    gaps: Array<{ field: string; prompt: string; options: string[] }>
  }>
}

/** Ф-06: запросы данных — анкеты характеристик, наложенные на модель. */
export interface DataRequestsView {
  missing_total: number
  requests: Array<{
    form: string
    name: string
    role: string
    note?: string
    holder?: string
    missing: number
    fields: Array<{
      key: string
      name: string
      unit?: string
      required: boolean
      filled: boolean
      value?: string
      from?: string
      hint?: string
      kind: string
      options?: string[]
    }>
  }>
}

/** Ф-07: предложение замысла из документов — четыре поля с якорями. */
export interface MissionIntentDraftView {
  kind: string
  source_document?: string
  note?: string
  intent: {
    for_whom: { text: string; anchors?: string[] }
    what: { text: string; anchors?: string[] }
    where: { text: string; anchors?: string[] }
    horizon: { text: string; anchors?: string[] }
  }
}

/** Ф-09: что нормативы полки знают — и потому могут порождать кандидатов. */
export interface NormativeReadiness {
  normatives: number
  speaking: number
  can_compose: boolean
  why: string
  sources: Array<{
    id: string
    name: string
    clauses: number
    document?: string | null
    parsed: boolean
    speaks: boolean
  }>
  documents: Array<{
    id: string
    name: string
    kind: string
    parsed: boolean
    harvested: boolean
    in_prompt: boolean
    blocks: number
  }>
}

/** Ф-09: пакет кандидатов из нормативов — у каждого обязательное основание. */
export interface NormativeCandidatesPacket {
  kind: string
  rules_version?: number
  knowledge_fingerprint?: string
  items: Array<{
    class: 'requirement' | 'constraint'
    statement: string
    category?: string
    applies_to?: string
    note?: string
    measure?: { value: number; unit: string }
    basis: { normative_ref: string; clause?: string; anchors?: string[]; quote?: string }
  }>
}

/** Ф-10: состав выгрузки знаний и её отпечаток. */
export interface KnowledgeExportView {
  fingerprint: string
  parts: Array<{ key: string; file: string; title: string; chosen: boolean; size: number; size_kb: number }>
}

/** Ф-12: сквозная цепочка постановки со счётчиками и следующим шагом. */
export interface StatementPathView {
  complete: boolean
  summary: string
  links: Array<{
    key: string
    title: string
    count: number
    done: boolean
    screen: string
    kind?: string
    invitation: string
    why: string
  }>
  next?: { key: string; title: string; screen: string; kind?: string; invitation: string; why: string }
}

/** «Работа фазы»: задача регламента со статусом, шагами и окном ленты. */
export interface PhaseWorkTask {
  id: string
  order: number
  name: string
  why: string
  status: 'waiting' | 'available' | 'in_progress' | 'done'
  waits_on?: string
  input_ready: boolean
  input_why: string
  artifact: string
  gate?: string
  output_done: boolean
  start?: string
  end?: string
  tight: boolean
  lane_offset_pct?: number
  lane_width_pct?: number
  /** Ярус зависимостей внутри интервала точки и границы доли датами. */
  tier?: number
  tiers?: number
  lane_start?: string
  lane_end?: string
  steps: Array<{
    title: string; hint?: string; screen?: string; kind?: string
    /** Шаблон документа: переход открывает место уже настроенным на него. */
    document_code?: string
    done: boolean; why: string
  }>
  gaps: string[]
}

export interface PhaseWorkView {
  /** Круг 2: шкала ленты — вехи ◆ и линия «сегодня», положения считает сервер. */
  scale?: Array<{ gate: string; date: string; at_pct: number }>
  today?: string
  today_pct?: number
  phase?: string
  empty_why?: string
  lane_from?: string
  lane_to?: string
  tasks: number
  in_progress: number
  available: number
  waiting: number
  done: number
  next?: { task: string; name: string; step?: string; screen?: string; kind?: string }
  items: PhaseWorkTask[]
}

/** Ф-13: матрица «стейкхолдер × нужды» с тройным состоянием и краями. */
export interface StakeholderCoverageView {
  stakeholders: number
  needs: number
  declared: number
  covered: number
  verified: number
  summary: string
  rows: Array<{
    id: string
    name: string
    role: string
    establishes: boolean
    interest?: string
    supplies?: Array<{ id: string; name: string; has_form: boolean }>
    needs: number
    covered: number
    verified: number
    empty_why?: string
    items: Array<{ id: string; statement: string; state: string; covered_by?: string[] }>
  }>
  without_stakeholder: Array<{ id: string; statement: string; state: string; named?: string }>
}

/** Г-01: чужие ссылки пакета и предложения замены по смыслу. */
export interface LinkMappingView {
  foreign: number
  summary: string
  links: Array<{
    ref: string
    from_project?: string | null
    text: string
    kind: string
    suggested?: { id: string; text: string; score: number }
    candidates: Array<{ id: string; text: string; score: number }>
  }>
}
