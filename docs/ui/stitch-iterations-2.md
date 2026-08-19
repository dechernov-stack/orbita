# Итерации экрана требований, часть 2

Что сработало в первом заходе: колонка «Условие» с оператором, «Свёртка» с полосой
бюджета и превышением, V&V с методом и началом подхода, панель с блоками
«Как проверяется / Чем / Критерий успеха» и меткой «устарело» у свидетельства.

Что не сработало: дерево не читается как дерево, нет вида по компоненту,
экран пустой на 80%, правая панель осталась от прежней версии.

Правки вносятся по одной.

---

## Итерация 5 — дерево должно выглядеть деревом

```
The hierarchy in the requirements table is not readable: child rows sit at almost the
same horizontal position as their parents, so RQ-0101 does not look subordinate to RQ-0100.

Make the tree structure explicit:
- indent each level by 28px, not by a few pixels
- draw thin vertical guide lines at each indentation level, in the border color, running
  from the parent row down to its last child, with a short horizontal stub into each child row
- give parent rows slightly heavier weight: the ID and the requirement text in medium weight,
  child rows in regular
- put the expand chevron immediately before the ID at the row's own indentation level
- when a parent row is selected, tint the background of its whole subtree very lightly

Keep all existing columns and their content unchanged.
```

## Итерация 6 — экран не должен быть пустым

```
The table shows only five rows on a tall screen, leaving most of the area empty. Fill it
with realistic content: render 18 requirement rows across three top-level groups, each
group with two to four children, and one group nested three levels deep.

Use plausible Russian content for a satellite IoT constellation, for example:
  Масса КА, Энергобаланс витка, Время реакции контура C', Доступность сервиса,
  Запас радиолинии, Объём бортового буфера, Срок баллистического существования.

Vary the data so the screen reads as real work: some rows without a condition yet
(показывать «— TBD» in secondary text), two rows with an amber warning in the V&V column,
one subtree where the budget bar is red, several different статусы.
```

## Итерация 7 — дерево компонентов слева

```
Add a second navigation panel between the lifecycle rail and the requirements table,
240px wide, titled "Состав системы", with a collapse control.

It holds the component tree:
  КС «Альфа-Центавра»
    Космический сегмент
      Платформа
      Полезная нагрузка
      Интерфейс: Платформа ↔ ПН        (rendered with a link glyph, not a box glyph)
    Наземный сегмент
      Станция приёма
      Центр управления
    Пользовательский сегмент
      Терминал A'
      Терминал C'

Each row shows the component name and, right-aligned in secondary monospace, the number of
requirements allocated to it, e.g. "12". Interface rows show the count for the interface itself.

Selecting a component filters the requirements table to the specification of that component:
the header above the table changes to "Спецификация: Платформа · 12 требований" and a small
chip "Сбросить фильтр" appears next to it. With nothing selected the header reads
"Все требования · 214".
```

## Итерация 8 — привести правую панель в соответствие

```
The right inspector still shows content from an earlier version: an English requirement
about primary structure dry mass of 1250 kg, unrelated to the selected row.

Replace its content so it corresponds to the selected requirement RQ-0100 "Масса КА":
header "RQ-0100", status pill "Baseline", version "v3".
Keep the existing sections and their layout, but change the verification block to show
two verification events stacked as a small timeline, not one method:

  ✓ Предварительный расчёт · Анализ · Phase A · уровень: система
    Суммирование масс подсистем по MEL с резервами по зрелости
    Свидетельство: EV-0014 · расчётное · заменено
  ○ Квалификация · Испытание · Phase D · уровень: система · закрывающее
    Взвешивание собранного аппарата после интеграции
    Свидетельство: ожидается

Above the timeline, one line of state in secondary text:
"Предварительно подтверждено — закрывающее событие не выполнено".

Add a section "Декомпозиция" listing RQ-0101 (распределённое) and RQ-0102 (распределённое),
each with its condition, and one row marked "производное — в бюджет не входит".
```

---

## Экран 11. Спецификация элемента

Отдельный экран для работы «от компонента», а не «от требования».

```
[paste block B]

Screen: the specification of a single system component — the view an engineer uses when
working on one element rather than browsing all requirements.

Header: breadcrumb "КС · Космический сегмент · Платформа", then the component name as a
title with a status pill, and three ghost buttons "Спецификация", "Интерфейсы", "История".

Left column, 60%: the requirements allocated to this component, as a table with columns
"ID", "Требование", "Условие", "Источник" (the parent requirement ID as a chip, showing
where it came from), "V&V" (state word plus a small progress of events, e.g. "1 из 2"),
"Статус". About 12 rows. Two rows show the source chip with a dashed border and the caption
"производное" — meaning they arose from a design decision, not from a parent budget.

Right column, 40%, three stacked cards:

Card "Бюджеты": three rows, each a label, a horizontal stacked bar and a monospace figure —
  Масса        41.2 / 60 кг · остаток 18.8
  Мощность     78 / 95 Вт · остаток 17
  Объём буфера 1.4 / 2.0 ГБ · остаток 0.6
Each bar is segmented by the child components contributing to it, with a tiny legend below.

Card "Интерфейсы": two rows, each "Платформа ↔ Полезная нагрузка" with a link glyph,
the number of interface requirements, and the second owner as a chip. One row carries an
amber dot and the caption "требования согласованы только одной стороной".

Card "Верификация": a small horizontal stacked bar of states with a count on each segment —
"верифицировано 4 · предварительно подтверждено 5 · запланировано 2 · не запланировано 1",
using the status colors. Below it, a single line: "Ближайшее закрывающее событие: Phase D".
```

## Экран 12. Система в целом

```
[paste block B]

Screen: the system-level overview — the state of the whole requirement set, used before a
review rather than during daily work.

Top strip: four compact tiles with a large monospace number and a small label —
"Требований 214", "Базировано 168", "Незакрытых TBD 12", "Разрывов трассировки 3".
The last two carry amber and red accents.

Section "Бюджеты системы", full width: a horizontal list of four budget cards, each with a
title, a stacked bar segmented by top-level component, and a monospace figure —
  Масса 78.4 / 100 кг · остаток 21.6
  Энергия витка 62 / 69 Вт·ч · остаток 7
  Время реакции C' 105 / 120 с · остаток 15
  Бюджет ΔV 41 / 45 м/с · остаток 4
One of them is over budget: red bar, figure reads "превышение 8".

Section "Состояние верификации": a horizontal stacked bar across the full width, segmented
and labelled "верифицировано 46", "предварительно подтверждено 92", "запланировано 61",
"не запланировано 15", using status colors. Below it, three small links:
"без закрывающего события · 23", "свидетельство устарело · 7",
"свидетельство от прежней конфигурации · 4".

Section "Валидация": a separate narrower bar, labelled "ожиданий 34 · подтверждено на
моделях 21 · не запланировано 13", with the caption "против сценариев ConOps".

Section "Проблемы", two columns of compact lists with counts and links:
left "Целостность" — "требования без источника · 3", "элементы без требований · 5",
"потомок вне области родителя · 2", "интерфейс согласован односторонне · 1";
right "Качество" — "без оператора условия · 0", "без описания проверки · 9",
"формулировка противоречит оператору · 2", "производное включено в бюджет · 1".

Keep the whole screen on one page without scrolling at 1440x900.
```
