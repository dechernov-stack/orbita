# Stitch prompt 2 — Orbita v2, eight screens the owner asked for

Paste as one prompt. UI strings are Russian (use them verbatim). Same
visual direction as before ("route map on drafting paper"), but this
time the brief is **less on every screen**: one job per screen, no
side panels unless named, no metadata strings with middle dots where a
sentence works. Last round produced dense ledgers; this round must
produce work surfaces.

## Direction (unchanged)

Light paper `#F4F6F8`, white surfaces, hairline graphite `#C9D0D8`,
ink `#182130`, muted `#5C6675`. Colour only for state: cobalt `#2A4BD7`
current/action, green `#1F7A4D` done, amber `#B7791F` waiting, red
`#C43A2B` blocking. Golos Text, 14 px body, tabular numerals. Three
markers everywhere: circle = scene, diamond = gate, chamfered square =
activity. No dark theme, gradients, shadows, all-caps, coloured status
pills, decorative icons. Small line pictograms only with a label or
tooltip.

## Global shell — compact top bar

Height 40 px. Left: logo «Орбита», project selector («Национальная
система IoT ▾»), phase chip «Pre-A», nearest gate «◆ внутренний обзор
· 06.10 · блокирует 2». Right: **one user menu** avatar/initials
«ДЧ ▾» that opens: name, role («руководитель проекта»), «Выступить от
имени роли…», «Эксперт-режим» toggle, «Выйти». Nothing else in the
bar — no scene, no role text, no mode text.

Left rail 200 px: «Проекты», «Работа», «Постановка», «Поле знаний»,
«Концепция», «Требования», «Архитектура», «Модели», «Документы»,
«Точки»; separator; «Библиотека», «Обмен» (muted until expert mode).

## Screen 1 — Проекты (portfolio), two columns

Title «Проекты». Two equal columns: «Рабочие» and «Примеры». Each
project is a row-card: name, phase chip, nearest gate with date and
blocking count in red if > 0, last activity date, manager initials.
Top-right: «Новый проект» (primary). Empty column state: one line
«Примеров пока нет». No stats dashboards.

## Screen 2 — Новый проект (create)

A single form, two columns. Left: «Название», «Класс миссии» (select:
«НОО · связь и IoT», «НОО · ДЗЗ»), «Руководитель» (picker), «Дата
старта» (date). Right, titled «Точки фазы Pre-A» with defaults from
the start date, all editable: «Внутренний обзор — 06.10.2026»,
«MCR — 25.11.2026», «KDP-A — 11.12.2026», each with a date field and a
tiny hint «по умолчанию +24 / +72 / +88 дней». Bottom: «Создать
проект» (primary), «Отмена». That's the whole screen.

## Screen 3 — Работа: scene inside the body

Layout: left column 320 px — list of scenes of the phase with markers
and state text («выполнена», «в работе · 2 из 3», «закрыта · ждёт 3»),
gates as diamond rows between scenes («◆ внутренний обзор · 06.10 ·
блокирует 2»). Right, the body: **the current scene as a work
surface** — heading «3 · Стейкхолдеры и нужды», one context line
(«ведущий СИ · вход: замысел ✓, записка ✓»), then the scene's
activities as a short horizontal flow of two chamfered cards («0.1
Уточнение потребностей и задач пользователей» — current, «0.2
Уточнение допущений и ограничений» — locked, with the arrow labelled
«пользователи · потребности»), then the selected activity's surface
(a stakeholder table with an inline «+ потребность» row), and a footer
line with outputs as counters and one red «не даёт завершить: …».
No stepper in the top bar, no right rail.

## Screen 4 — Карта фазы (phase map) — laid out on a time axis

Full-width panel. **Horizontal axis = calendar weeks** (Sep 14 → Dec
11), a vertical «сегодня» line in cobalt. **Three horizontal lanes**:
«Проектирование», «Моделирование», «Планирование и управление».
Scenes are circle stations positioned at their planned windows (a
station with a short bar showing the window), names below,
**responsible person initials** in a small chip under each station
(«ИИ», «ПМ»), state by fill (done green, current cobalt outline,
locked grey). Gates are diamonds placed on their dates on the top
lane with labels «внутренний обзор · 06.10», «MCR · 25.11», «KDP-A ·
11.12», blocking count in red. Dashed branches from a design-lane
station to a modelling-lane station, labelled with the artefact
(«пользователи миссии»). Overdue window: amber bar. Nothing overlaps
because position is date-driven. Legend row at the bottom. This
replaces the previous cramped map.

## Screen 5 — Концепция: состав системы as an editable tree

Title «Состав системы · вариант В2». A **tree table**: expandable rows
(Система → Космический сегмент → КА ×36 → Платформа → БЦВМ…), columns:
«Код», «Наименование», «×N», «Масса, кг» (or key parameter), «Ступень»
(MCR ✓ / SRR 3 из 5), «Анкета» (state text). Inline editing on click
(name, ×N, parameter). Row hover shows actions as pictograms with
tooltips: «добавить дочерний», «снять», «взять из полки» (opens the
take window), «карточка». A collapsed toolbar: «Развернуть до L2 ▾»,
«Показать: масса ▾», «Взять из полки». Show ~14 rows, two expanded
levels, one row being edited, one row flagged «анкета: 2 из 5 к MCR».
No cards, no side panel.

## Screen 6 — Требования: views, sorting, filters

Title «Требования · уровень проекта». A **view switcher** at the top
left: «Таблица» (default) · «По иерархии» · «По документам» ·
«Матрица». Filter chips: «Уровень ▾», «Категория ▾», «Носитель ▾»,
«Статус ▾», «Только без носителя», and a search field. Table with
**sortable headers** (arrow on the sorted column): «Код», «Заголовок»,
«Формулировка», «Показатель», «Носитель», «Статус». Grouping toggle
«Группировать: по носителю ▾». One row expanded downward into the card
(source with anchor chip and quote, rationale, acceptance criterion,
verification method select with «TBD», EARS pattern, links with
justification, lint note as text «П16: „чувствительные" — уточнить»,
carrier picker by nature «узел · стык · цепочка»). Bulk actions on
selection: «Распределить носитель…», «Базировать выбранные». Rows to
show: RQ-P-03 (Draft, «≤ 180 мин», Система), RQ-P-06 (без носителя,
lint 1), RQ-P-09 (TBR), RQ-P-01 (Baseline).

## Screen 7 — Библиотека: shelves you can take from

Title «Библиотека». A **catalog of shelves** as rows (not cards):
«Каркас состава НОО-IoT · 135 узлов · версия 3 · взято в проект 42»,
«Стыки · 26», «Архитектура Arcadia · 55», «WBS · 54», «Шаблоны
компонентов · 31», «Модели системы · 14», «Нормативы · 11», «Процессы
Романова · 73». Each row: name, count, version, «взято: N», and a
primary action **«Взять в проект…»** which opens the take window (a
tree with checkboxes, defaults ticked, greyed rows with «взять ‹узел›»,
a preview «станут доступны: 12 стыков, 8 функций», «Взять»). Show the
take window open over the catalog.

## Screen 8 — Постановка: stakeholders grid with defaults

A compact table of stakeholders (name, role, needs count, influence
computed «решает/влияет/информируется», **power 1–5 as a clickable
cell**) and beside it the «Влияние × сила» grid where each stakeholder
appears as initials chip in its cell; unset power shows the chip grey
in a suggested cell with a «?». Clicking a cell moves the chip. One
line under the grid: «сила предложена по роли — подтвердите или
перенесите». No empty grids.

## Do not

No third column on any screen; no side drawers; no dashboards with
KPI tiles except the four small signals on the bridge (not in this
brief); no English field codes; no `[object Object]`; no tiny inputs
for long text — long text fields are full-width with auto height.
