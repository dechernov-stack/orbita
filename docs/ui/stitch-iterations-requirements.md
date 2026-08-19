# Итерации экрана требований (Stitch)

Экран уже сгенерирован. Перегенерировать его не нужно — вносим четыре правки
последовательно, **по одной за раз**, дожидаясь результата каждой.
Порядок важен: каждая следующая опирается на предыдущую.

Причина правок: модель требований дополнена (ADR-017, ADR-018), и экран должен
показывать данные, которых в нём не было — дерево декомпозиции, оператор условия,
свёртку бюджета и описание проверки.

---

## Итерация 1 — из таблицы в дерево

```
Convert the requirements table into a tree table. Requirements decompose into child
requirements, and the screen must show that hierarchy.

Indent each row by its depth in the tree (16px per level). Rows that have children get a
small expand triangle before the ID; leaf rows get no marker. Keep the existing columns
and the right panel unchanged for now.

Render these rows, preserving indentation:
  RQ-0100  Масса КА
    RQ-0101  Масса платформы
    RQ-0102  Масса полезной нагрузки
  RQ-0110  Время реакции контура C'
    RQ-0111  Ожидание восходящего канала
    RQ-0112  Транзит до наземной станции
    RQ-0113  Доставка команды

Add a segmented control above the table with three options: "Дерево", "Плоский список",
"Матрица", with "Дерево" active.
```

## Итерация 2 — колонка «Условие»

```
Add a column "Условие" after the requirement text. It holds the measurable condition as a
structured value, not prose: render the comparison operator as a distinct glyph followed by
the number and unit, in monospace.

Values for the rows:
  RQ-0100  ≤ 100 кг      RQ-0101  ≤ 60 кг     RQ-0102  ≤ 30 кг
  RQ-0110  ≤ 120 с       RQ-0111  ≤ 40 с      RQ-0112  ≤ 30 с      RQ-0113  ≤ 35 с

Show that other operators exist by rendering two additional rows elsewhere in the table:
"= 500 ± 5 г" and "от −20 до 50 °C".

Remove the numeric values from the requirement text column where they duplicate the
condition — the text states the intent, the condition carries the number.
```

## Итерация 3 — колонка «Свёртка»

```
Add a column "Свёртка", filled only for rows that have children. It shows the sum of the
children's budgets against the parent limit: a thin horizontal bar with the filled portion,
followed by a monospace figure.

  RQ-0100  bar at 90%   "90 / 100 кг · остаток 10"
  RQ-0110  bar at 87%   "105 / 120 с · остаток 15"

Add a third parent row where the budget is exceeded: the bar is red and the figure reads
"112 / 100 кг · превышение 12". Add a filter chip "Бюджет нарушен" to the toolbar.
```

## Итерация 4 — верификация с описанием подхода

```
Change the "Метод V&V" column so it no longer shows only an icon. Each cell now shows the
method tag on the first line and the beginning of the verification approach on a second
line in secondary text, truncated to one line.

  RQ-0100  Анализ · Суммирование масс подсистем по MEL с резервами по зрелости…
  RQ-0101  Испытание · Взвешивание собранной платформы на поверенном оборудовании…
  RQ-0110  Анализ · Монте-Карло по расписанию пролётов, распределение времени реакции…
  RQ-0113  Демонстрация · Передача команды на макет терминала в худшей геометрии…

Add one row whose cell shows the method tag next to a small amber warning icon and the
secondary line "не описано, как выполняется проверка" — this requirement cannot be
baselined.

In the right panel, replace the verification block with four labelled fields stacked
vertically: "Метод" (tag "Анализ" plus "Phase A"), "Как проверяется" (multiline text),
"Чем" (single line), "Критерий успеха" (single line, prefilled from the condition and
shown in secondary text as derived). Below them keep the linked evidence row with its
"устарело" tag.
```

---

## Если понадобится карточка требования отдельным экраном

```
[paste block B]

Screen: the requirement detail as a standalone 520px panel.

Header: ID "RQ-0100" in monospace, status pill "Baseline", version "v3", ghost button "История".

Section "Формулировка": multiline text "Сухая масса космического аппарата не должна
превышать 100 кг."

Section "Условие": a structured editor on one baseline, not a text field —
[ Показатель "Сухая масса" ] [ operator dropdown showing "≤ не более" ] [ number 100 ]
[ unit "кг" ]. The dropdown list contains "= ровно", "≤ не более", "≥ не менее",
"< менее", "> более", "диапазон", "± допуск". Below, two smaller optional fields:
"Пороговое" and "Целевое". Below them a select "Свёртка по потомкам" set to "сумма".

Section "Бюджет": a stacked horizontal bar with segments "Платформа 60", "ПН 30",
"Резерв 10" against 100 кг, and one monospace line "90 / 100 кг · остаток 10 кг".

Section "Декомпозиция": child rows "RQ-0101 · Масса платформы · ≤ 60 кг" and
"RQ-0102 · Масса полезной нагрузки · ≤ 30 кг", plus ghost button "Добавить потомка".

Section "Верификация": "Метод" (tag "Анализ", "Phase A"); "Как проверяется" — multiline,
filled with "Суммирование масс подсистем по MEL с применением резервов по зрелости и
системного резерва; результат сверяется с независимой оценкой по аналогам платформ";
"Чем" — "Сводный перечень оборудования (MEL)"; "Условия" — "Заправленная конфигурация,
худший случай резервов"; "Критерий успеха" — secondary text "Сухая масса: не более 100 кг"
with a small caption "выводится из условия".

Section "Трассировка": lists "Вверх" and "Вниз" of ID chips.
Section "Распределение": component chips, one with a dashed border captioned "частично".

Footer: "Базировать" (primary), "Отклонить" (ghost).
```
