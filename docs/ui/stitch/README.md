# Макеты Stitch

25 макетов из двух выгрузок Stitch. У каждого — снимок (`screen.png`) и
сгенерированная разметка (`code.html`). Токены темы — `DESIGN.md` (Material:
роли `surface-*`, `on-*`, шкала типографики).

**Это опорные макеты, а не исходники клиента.** Разметка Stitch содержит
подставные данные и не подключена к API; клиент собирается в `web/` и берёт
данные только из `core/com`. Макет отвечает на вопрос «как это выглядит»,
а не «откуда берутся числа».

## Расхождение с токенами в `stitch-prompts.md`

Блок B файла `docs/ui/stitch-prompts.md` задаёт плотную систему: фон `#FFFFFF`,
акцент `#0B5FFF`, углы до 2 px, волосяные линии вместо теней, строка таблицы
28 px. Именно она реализована в `web/src/ui/tokens.css`.

`DESIGN.md` этих макетов задаёт другую систему: акцент `#115cb9`, скруглённые
карточки, тёмная левая панель, крупная типографика, роли Material. Названия
в макетах — «SpaceSys» и «Альфа-Центавра», а не «Орбита».

Две системы не сводятся друг к другу автоматически, и выбор между ними —
решение владельца продукта, а не следствие кода. Пока действует блок B:
по нему сделаны экраны 3, 3б и 11. Макеты ниже используются как источник
структуры экранов (что на экране и в каком порядке), а не палитры.

## Состав

| Каталог | Заголовок макета | Ближе всего к экрану задания |
| --- | --- | --- |
| `stakeholder-map-needs` | Stakeholder Map & Need Portfolio | 1 — мастер, нужды |
| `ai-stakeholder-discovery` | AI Stakeholder Discovery | 1 — нужды через ИИ |
| `service-portfolio` | Service Portfolio Builder | 2 — сервисы и QoS-профили |
| `conops-overview` | Концепция операций (ConOps): обзор | ConOps, заготовка `TZ-OUT-001` |
| `conops-scenario-editor` | ConOps Scenario Editor | ConOps, сценарии |
| `requirements-vv-dashboard` | Требования и В&В | 3 — требования |
| `requirements-baseline-vv-a` | Requirements Baseline & V&V | 3 — базирование |
| `requirements-baseline-vv-b` | Requirements Baseline & V&V | 3 — вариант компоновки |
| `verification-validation` | Requirements Baseline & V&V | 3 — верификация и валидация |
| `requirement-card` | карточка требования RQ-0100 | 3б — карточка требования |
| `requirement-derivation` | Requirement Derivation | 3б — декомпозиция |
| `component-specification` | Платформа — спецификация | 11 — спецификация элемента |
| `architecture-workspace` | Architecture Workspace | 12 — система в целом |
| `architecture-editor-multishell` | Multi-shell Physical Architecture Editor | 12 — дерево изделия |
| `interface-matrix-icd` | Interface Matrix / ICD | 11 — интерфейсы элемента |
| `budgets-trade-studies` | Budgets & Trade Studies | 7 — сравнение вариантов |
| `analysis-of-alternatives` | Анализ альтернатив (AoA) | 7 — Парето и роза KPI |
| `portfolio-dashboard` | Portfolio Dashboard | 12 — обзор |
| `lifecycle-cockpit` | Project Lifecycle Cockpit | 12 — фазы и блокираторы |
| `technology-risk-trl` | Technology & Risk Management | реестр рисков (шаг 9) |
| `mcr-readiness` | MCR Readiness Checklist | 9 — готовность к контрольной точке |
| `srr-workspace` | SRR Workspace | 9 — готовность к SRR |
| `kdp-b-decision` | KDP B Decision Panel | 9 — решение контрольной точки |
| `ai-copilot-review` | AI Copilot Review Panel | 8 — предложение ИИ и акцепт |
| `reports-documents` | Отчётные документы | отчёты и экспорт `TZ-OUT-001/005` |

Соответствие проставлено по содержанию макета; в задании (`STEP-6` §3.1,
`STEP-7-9` §8–9) номера экранов свои, и один макет может закрывать часть
экрана, а не экран целиком.
