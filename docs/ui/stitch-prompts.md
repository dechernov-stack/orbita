# Промпты для Stitch

Генерация интерфейса ИС. Результат — макеты и HTML/Tailwind, которые дальше переносятся
в `web/` вручную: Stitch даёт форму и композицию, а не рабочий код продукта.

## Как пользоваться

1. **Zoom-out → zoom-in.** Сначала блок A (контекст продукта) — один раз в начале сессии.
   Затем блок B (токены) и промпт нужного экрана. Токены повторять в каждом промпте.
2. **Один экран за раз.** Не просить «сделай всю систему» — качество падает.
3. **Одна правка за итерацию.** «Увеличь плотность таблицы и убери тени» — уже две.
4. Промпты на английском (модель работает точнее), подписи интерфейса — русские,
   заданы явно в кавычках. Если кириллица рендерится плохо — в конце файла есть
   английские подписи для замены.

---

## A. Контекст продукта (вставить один раз)

```
Product context. I am designing a desktop web application for space systems engineers.
It supports the early phases of a satellite mission lifecycle (concept and requirements
definition, NASA NPR 7120.5 methodology). Engineers use it to turn stakeholder needs into
a formal set of system requirements, model a LEO IoT satellite constellation, and compare
constellation options before detailed design.

Users: lead systems engineer, orbital analyst, spacecraft designer, service analyst,
project manager. They are experts. They work all day in this tool, on large monitors,
with dense numeric data. They dislike decorative interfaces, oversized cards, empty space,
and playful illustrations. They value information density, precise alignment, and the
ability to compare numbers across rows.

This is a professional engineering tool, closer to a CAD or an EDA application than to
a consumer SaaS dashboard.
```

## B. Дизайн-токены (повторять в каждом промпте экрана)

```
Design tokens. Light theme. Background #FFFFFF, surfaces #F7F8FA, borders #E1E4E8.
Text primary #1B1F24, secondary #57606A. Single accent: deep blue #0B5FFF for actions and
selection. Status colors used sparingly, only as small dots or 2px left borders:
gray #8C959F draft, blue #0B5FFF preliminary, amber #BF8700 approved, green #1A7F37 baseline,
red #CF222E error or violated constraint.
Typography: Inter for the interface; JetBrains Mono for identifiers, numbers, units and
formulas. Base size 13px, table rows 28px tall, section headers 15px semibold.
Layout: 8px grid, sharp corners (2px radius maximum), 1px hairline borders instead of
shadows, no gradients. Tables are dense, with sticky headers and right-aligned numbers.
Canvas areas for 3D and maps use a dark surface #10141A.
```

---

## Экран 1. Оболочка мастера и шаг «Нужды»

```
[paste block B]

Screen: the main application shell plus the first wizard step.

Left rail, 220px, fixed: project name "Космическая система IoT" at the top, then a vertical
7-step wizard with step numbers, Russian labels and completion state (done / current / locked):
"Нужды", "Сервисы", "Требования", "Конфигурация", "Моделирование", "Верификация", "Пакет".
Below the steps, a compact block "Готовность к SRR" with a thin progress bar at 64%.

Top bar, 48px: breadcrumb "Ш1 · Нужды", a search field, a scenario selector showing
"SC-0004 · Walker 40/5", and a small user avatar.

Main area: a dense table of stakeholder needs. Columns: "ID" (monospace, e.g. ND-0007),
"Формулировка" (wide), "Стейкхолдер", "Роль", "Приоритет" (1 to 5 as small filled squares),
"Сервисы" (count badge), "Статус" (colored dot plus text). 12 rows visible.
Rows with zero linked services show a small red warning icon in the last column.
Above the table: a filter bar with chips "Все", "Без сервисов", "Черновики" and a primary
button "Добавить нужду".

Right panel, 360px, collapsible: details of the selected need. Sections: "Формулировка"
(editable text), "Ограничения" (bulleted list), "Трассировка вниз" (list of linked service
IDs as clickable chips), "История" (compact timeline of status changes).
```

## Экран 2. Сервисы и QoS-профили

```
[paste block B]

Screen: service catalog with quality profiles per consumer type.

Main area is a two-column split. Left, 40%: list of services as rows, each showing
service ID (monospace SV-0003), name, and three small class badges "A'", "B'", "C'"
where a badge is filled if the service has a quality profile for that consumer class
and outlined if it does not.

Right, 60%: profile editor for the selected service. A segmented control at the top with
three options: "A' односторонний", "B' с подтверждением", "C' оперативное управление".
Below it, a table of effectiveness measures for the selected class. Columns:
"Показатель", "Целевое", "Пороговое", "Ед.", "Условия". Values are monospace and
right-aligned. Example rows: "Вероятность доставки за сутки  0.90  0.80  —  все регионы",
"Вероятность реакции ≤ 120 с  0.95  0.90  —  акватории".
Under the table a subtle inline notice with an info icon: "Класс C' присутствует в карте
спроса, но профиль не задан" and a link "Добавить профиль".

Header of the right panel shows the service name, its status dot, and two ghost buttons
"Породить требования" and "История".
```

## Экран 3. Требования

Экран уже сгенерирован. Правки вносятся точечно:
- `stitch-iterations-requirements.md` — итерации 1–4: дерево, условие, свёртка, верификация;
- `stitch-iterations-2.md` — итерации 5–8 (читаемость дерева, наполнение, дерево компонентов,
  панель событий верификации) и экраны 11–12 (спецификация элемента, система в целом).
Полная перегенерация экрана не требуется.

## Экран 4. Карта спроса

```
[paste block B]

Screen: consumer demand map editor.

Left panel, 300px: list of populations. Each row shows a class badge ("A'", "B'", "C'"),
a name such as "Агромониторинг, средняя полоса", terminal count "48 000", and a small
sparkline of the daily activity profile. A primary button "Добавить популяцию" at the top,
and three layer toggles below the list: "Население", "Единичные объекты", "Сценарии".

Center: a world map on a dark canvas surface, rendered as an equal-area grid heat map.
Cell color encodes demand intensity, from dark navy through teal to warm yellow.
A thin legend in the bottom left with the scale and the unit "сообщений/сутки на ячейку".
A small overlay control in the top right of the map with three options: "Мгновенно",
"Сутки", "Период".

Right panel, 320px: parameters of the selected population, as a compact form with grouped
fields: "Класс терминала", "Численность", "Профиль активности" (24-hour bar chart, editable),
"Подвижность" (radio: "Статика" / "Маршрут"), "Регуляторный регион" (select showing "RU864").
Below the form, a read-only summary block "Вклад в спрос" with three monospace numbers:
"Доля спроса 12.4 %", "Пик 1 840 сообщ./с", "Ячеек 312".
```

## Экран 5. Модель космического аппарата

```
[paste block B]

Screen: spacecraft model with budgets.

Top: a preset selector as four small tabs "12U", "16U", "25–50 кг", "50–100 кг",
with the second one active, and to the right a monospace summary "Сухая масса 41.2 кг ·
Резерв 18 %".

Main area, three columns of grouped parameter cards with hairline borders, no shadows:
- Column 1 "Платформа": массовый бюджет as a small table of subsystems with mass, maturity
  tag and margin; power block with solar array area, efficiency, battery capacity and
  depth of discharge.
- Column 2 "Полезная нагрузка": a list of radio links, each a row with role
  ("Абонентская вверх", "Абонентская вниз", "Фидерная", "ISL"), frequency, EIRP, and a link
  margin value colored green when positive and red when negative. Under the links, a block
  "Маяк эфемерид" with period, format select ("Расписание пролётов" / "Альманах" /
  "Модель орбиты") and payload size.
- Column 3 "TPM": six technical performance measures as rows, each with name, current value
  in monospace, target, margin percentage, and a tiny sparkline trend. One row is over its
  limit and shows the value in red with a small warning icon.

Bottom bar, fixed, 44px: "Проверить согласованность" (ghost) and "Сохранить версию" (primary),
plus an inline validation summary "2 предупреждения" with an amber dot.
```

## Экран 6. Баллистика и моделирование

```
[paste block B]

Screen: constellation modeling workspace.

Left panel, 280px: scenario parameters as a compact form. Groups: "Построение"
(конструкция Walker with three small numeric fields i / T / P, высота, наклонение),
"Режим доставки" (segmented control: "S&F", "ISL в плоскости", "Полная сетка"),
"Сезон" (segmented: "Худший", "Лучший"), "Монте-Карло" (число реализаций, зерно in monospace).
A primary button "Рассчитать" at the bottom of the panel with a secondary "Сбросить".

Center, dark canvas: a 3D globe placeholder showing Earth from space with several orbital
tracks as thin glowing lines, satellite markers as small dots, ground station markers as
small triangles, and one satellite's service zone drawn as a translucent ellipse on the
surface. Keep it schematic, not photorealistic.

Under the globe, a 160px timeline strip: a horizontal 24-hour axis with three stacked lanes
labeled "Сеансы", "Тень", "ISL", where events are drawn as small bars. A vertical playhead
line with a time label "08:42:15".

Right panel, 300px: computed results as read-only monospace rows grouped under headers:
"Энергетика витка" (Вт·ч/виток худший и лучший, доля тени, допустимый duty cycle),
"Доставка" (задержка p50/p95 по классам A'/B'/C'),
"Покрытие" (средний и максимальный разрыв, кратность).
At the top of this panel a small state chip "Результат актуален" in green, and next to it
a ghost button "Сравнить варианты".
```

## Экран 7. Сравнение вариантов построения

```
[paste block B]

Screen: constellation trade study comparison. Two large panels side by side.

Left panel "Профиль KPI": a radar chart (spider chart) with six axes labeled
"Качество", "Экономика", "Надёжность", "Энергетика", "Срок развёртывания", "Соответствие нормам".
Three constellation options are overlaid as translucent polygons in three distinguishable
colors with a legend below listing them as "Walker 40/5 · 550 км", "Walker 24/3 · 700 км",
"ССО 30/3 · 600 км".

Right panel "Экономика × Качество": a scatter plot. X axis "Прокси-стоимость",
Y axis "Спрос-взвешенное качество". Points are constellation options; non-dominated points
sit on a highlighted Pareto frontier line and are drawn as filled circles, dominated points
as small hollow circles in gray. The three legend options are labeled on the chart.

Below both panels, full width: a comparison table with one column per option and rows
"Аппаратов", "Плоскостей", "Пусковых кампаний", "Срок развёртывания, сут",
"Качество A'/B'/C'", "Энергия витка, Вт·ч (худш.)", "Деградация при отказе 3 КА".
Numbers monospace and right-aligned; the best value in each row is emphasized with a
subtle light blue cell background.

Second panel below the radar: a small line chart titled "Качество по широте" with latitude
on the X axis from -90 to 90, a solid line for service quality and a filled gray area
behind it for demand weight.
```

## Экран 8. Предложение ИИ и приёмка

```
[paste block B]

Screen: reviewing an AI proposal before it is applied to the model.

A centered modal panel, 900px wide, over a dimmed background.
Header: "Предложение ИИ · Пакет PP-018 · Реестр рисков", with two small source chips
showing which models answered, and a status line "3 из 7 объектов совпали".

Body: a two-column diff. Left column "Текущее состояние", right column "Предложено".
Changed fields are highlighted: additions with a pale green background, modifications with
a pale amber background. Each proposed object is a collapsible group with a checkbox,
an object title, and inline editable fields.
One group is expanded, showing rows: "Формулировка", "Категория", "Вероятность",
"Последствия", "Стратегия", "Владелец".
Where two models disagreed, the field shows two small alternative chips side by side with
one selected, and a caption "Расхождение: выберите вариант".

Footer bar: on the left a caption "Принято 3 из 7", on the right buttons
"Отклонить" (ghost) and "Принять выбранное" (primary).
Below the footer, a small note in secondary text: "Непринятые предложения не участвуют
в расчётах и отчётах".
```

## Экран 9. Готовность к контрольной точке

```
[paste block B]

Screen: readiness report for a lifecycle review.

Top: a segmented control "SRR" / "SDR" with SRR active, a date picker "на 19.08.2026",
and a primary button "Выгрузить пакет".

Below it a row of four compact metric tiles with a large monospace number and a small label:
"Требований 214", "Базировано 168", "Незакрытых TBD 12", "Разрывов трассировки 3".
The last two tiles carry an amber and a red accent respectively.

Main area: a table of objects that have not reached the required maturity. Columns:
"Объект" (monospace ID), "Тип", "Текущий статус", "Требуемый", "Владелец", "Срок".
Current status and required status are shown as two status pills with a small arrow between
them. Rows are grouped under collapsible section headers by type:
"Требования", "Архитектура", "Верификация", "Риски".

Right panel, 320px: "Что мешает" — an ordered list of blocking issues, each with a short
title, a count, and a link, for example "Требования без метода верификации · 9",
"Устаревшие результаты моделирования · 4", "Неакцептованные предложения ИИ · 6".
```

---

## Промпты-итерации (по одной правке за раз)

```
Increase the density of the requirements tree: reduce row height to 26px, remove
alternating row backgrounds, keep only a hairline separator, and make the indentation
per tree level 16px instead of 24px.
```
```
In the right panel, move the traceability section above the verification section and
render each traceability item as a single line with the ID in monospace followed by a
truncated title in secondary text.
```
```
Replace the status pills in the table with a 2px colored left border on the row plus the
status word in secondary text. Keep pills only in the detail panel.
```
```
Make the globe canvas taller by 120px and move the timeline strip into a collapsible
drawer that overlays the bottom of the canvas.
```

## Чего от Stitch не ждать

- Рабочего 3D-глобуса: экран 6 даёт оформление вокруг канвы, сам глобус — CesiumJS в `web/`.
- Реальных графиков: радар и Парето будут статичными изображениями; в продукте это компоненты.
- Готового кода продукта: экспорт HTML/Tailwind — источник разметки и токенов, не более.
- Соответствия схемам: подписи и поля выверяются по `schemas/` вручную.
- Работающего дерева: разворачивание узлов и пересчёт свёртки — это `web/`, не макет.

## Английские подписи (если кириллица рендерится плохо)

Нужды → Needs · Сервисы → Services · Требования → Requirements · Конфигурация →
Configuration · Моделирование → Modeling · Верификация → Verification · Пакет → Package ·
Формулировка → Statement · Стейкхолдер → Stakeholder · Приоритет → Priority ·
Статус → Status · Владелец → Owner · Трассировка → Traceability · Показатель → Measure ·
Карта спроса → Demand map · Построение → Constellation · Режим доставки → Delivery mode ·
Энергетика витка → Orbit energy · Сравнить варианты → Compare options ·
Готовность → Readiness · Принять выбранное → Accept selected
