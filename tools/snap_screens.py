#!/usr/bin/env python3
"""Снимки экранов «до / после» и лист-контакт (шип 5 §0).

Владелец смотрит картинки, а не localhost: каждый раздел рейки × три роли
(руководитель · ведущий СИ · инженер) при плотности умолчания роли, окна
1440×900 и 1280×800, плюс эксперт-разделы под руководителем. Снимки кладутся
в `docs/tz/v2/снимки-дизайн/<дата>-<метка>/` с именами
`<раздел>-<роль>-<ширина>.png`; рядом — `лист.html` (миниатюры сеткой
«раздел × роль», клик — полный снимок) и `ЛИСТ.md` (то же, читается прямо на
GitHub). Для папки «после» лист строится парами «до | после».

Прогон — локально, на копии стенда (docker compose, :8080). CI его не гоняет.

    python3 tools/snap_screens.py shoot --label до
    python3 tools/snap_screens.py shoot --label после --sections Постановка,Работа
    python3 tools/snap_screens.py shoot --label после --sections Постановка --tabs Постановка --card Постановка
    python3 tools/snap_screens.py sheet --dir docs/tz/v2/снимки-дизайн/2026-09-26-после \\
        --before docs/tz/v2/снимки-дизайн/2026-09-26-до
    python3 tools/snap_screens.py check --dir docs/tz/v2/снимки-дизайн/2026-09-26-после

`check` (шип 5 §8 g) — полнота папки: каждый раздел рейки × три роли × оба
окна; проверка при сборке отчёта, не в CI (снимки CI не снимает).

Браузер — установленный Google Chrome через Playwright из `web/node_modules`.
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import pathlib
import subprocess
import sys
import tempfile

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent
СНИМКИ = КОРЕНЬ / "docs/tz/v2/снимки-дизайн"

# Порядок рейки — как в web/src/v2/shell.tsx (SECTIONS). Имя файла — слово раздела.
РАЗДЕЛЫ = [
    "Проекты", "Мостик", "Моя работа", "Работа", "Постановка", "Поле знаний", "Концепция",
    "Требования", "Архитектура", "Модели", "Документы", "Риски", "Точки", "Паспорт", "Словарь",
]
ЭКСПЕРТ = ["Библиотека", "Обмен", "Внешняя модель", "Журналы"]
РОЛИ = [
    {"code": "lead", "file": "руководитель", "word": "руководитель"},
    {"code": "lead_se", "file": "ведущий-СИ", "word": "ведущий СИ"},
    {"code": "specialist", "file": "инженер", "word": "инженер"},
]
ШИРИНЫ = [1440, 1280]


def файл(раздел: str) -> str:
    return раздел.lower().replace(" ", "-")


def снять(args) -> pathlib.Path:
    дата = args.date or dt.date.today().isoformat()
    куда = СНИМКИ / f"{дата}-{args.label}"
    куда.mkdir(parents=True, exist_ok=True)
    разделы = [р.strip() for р in args.sections.split(",")] if args.sections else РАЗДЕЛЫ
    эксперт = [] if args.sections and not args.expert else (ЭКСПЕРТ if not args.sections else [р for р in разделы if р in ЭКСПЕРТ])
    разделы = [р for р in разделы if р not in ЭКСПЕРТ]
    роли = [dict(р) for р in РОЛИ if not args.roles or р["file"] in args.roles.split(",")]
    if эксперт:
        роли.append({"code": "lead", "file": "руководитель", "word": "руководитель", "expert": True})
    план = {
        "base": args.base, "entry": args.entry, "project": args.project, "login": args.login, "out": str(куда),
        "widths": [int(ш) for ш in args.widths.split(",")] if args.widths else ШИРИНЫ,
        "roles": роли,
        "sections": [{"title": р, "file": файл(р)} for р in разделы],
        "expertSections": [{"title": р, "file": файл(р)} for р in эксперт],
        # Шип 5: вкладки раздела — каждая своим снимком; карточка первой строки реестра.
        "tabSections": [р.strip() for р in args.tabs.split(",")] if args.tabs else [],
        "cardSections": [р.strip() for р in args.card.split(",")] if args.card else [],
        "scenes": [с.strip() for с in args.scenes.split(",")] if args.scenes else [],
        "bandSections": [р.strip() for р in args.band.split(",")] if args.band else [],
        # «Раздел=имя кнопки»: открыть кнопкой (документ из комплекта) и снять открытое.
        "open": dict(п.split("=", 1) for п in args.open.split(",")) if args.open else {},
        "scroll": dict(п.split("=", 1) for п in args.scroll.split(";")) if args.scroll else {},
        "tab": dict(п.split("=", 1) for п in args.tab.split(",")) if args.tab else {},
    }
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(план, f, ensure_ascii=False)
        путь_плана = f.name
    прогон = subprocess.run(["node", str(КОРЕНЬ / "tools/snap/shoot.mjs"), путь_плана], capture_output=True, text=True)
    sys.stdout.write(прогон.stdout[-4000:])
    if прогон.returncode != 0:
        sys.stderr.write(прогон.stderr[-4000:])
        raise SystemExit(f"снимки не сняты: node вернул {прогон.returncode}")
    return куда


def строки(папка: pathlib.Path) -> dict[tuple[str, str, int], pathlib.Path]:
    """Снимки папки по ключу (раздел, роль, ширина) из имени файла."""
    итог = {}
    for п in sorted(папка.glob("*.png")):
        стем = п.stem
        ширина = int(стем.rsplit("-", 1)[1])
        голова = стем.rsplit("-", 1)[0]
        for роль in [р["file"] for р in РОЛИ]:
            if голова.endswith("-" + роль):
                итог[(голова[: -len(роль) - 1], роль, ширина)] = п
                break
    return итог


def полнота(папка: pathlib.Path) -> list[str]:
    """Чего не хватает в папке: раздел рейки × роль × окно (эксперт-разделы — не обязательны)."""
    есть = строки(папка)
    return [
        f"{раздел} · {роль['word']} · {ширина}"
        for раздел in РАЗДЕЛЫ for роль in РОЛИ for ширина in ШИРИНЫ
        if (файл(раздел), роль["file"], ширина) not in есть
    ]


def лист(папка: pathlib.Path, до: pathlib.Path | None) -> None:
    сейчас = строки(папка)
    прежние = строки(до) if до else {}
    # Вкладки и карточка («раздел~вкладка») идут сразу за своим разделом.
    все_ключи = {к[0] for к in сейчас} | {к[0] for к in прежние}
    разделы = []
    for р in [файл(р) for р in РАЗДЕЛЫ + ЭКСПЕРТ]:
        разделы.append(р)
        разделы += sorted(к for к in все_ключи if к.startswith(р + "~"))
    роли = [р["file"] for р in РОЛИ]
    заголовок = f"Снимки {папка.name}" + (f" · пары с {до.name}" if до else "")
    # HTML: миниатюры сеткой «раздел × роль», клик — полный снимок.
    h = [f"<!doctype html><html lang=ru><meta charset=utf-8><title>{html.escape(заголовок)}</title>",
         "<style>body{font:14px/1.4 system-ui,sans-serif;margin:20px;background:#F4F6F8;color:#182130}"
         "table{border-collapse:collapse}td,th{padding:6px;vertical-align:top;border-bottom:1px solid #E6EAEF}"
         "th{text-align:left;color:#5C6675;font-weight:500}img{width:300px;border:1px solid #C9D0D8;display:block}"
         ".pair{display:flex;gap:6px}.pair span{font-size:12px;color:#5C6675}h2{font-size:15px;margin:24px 0 6px}</style>",
         f"<h1 style='font-size:16px'>{html.escape(заголовок)}</h1>"]
    md = [f"# {заголовок}", "", "Миниатюра — ссылка на полный снимок. " + ("Слева «до», справа «после»." if до else ""), ""]
    for ширина in ШИРИНЫ:
        h.append(f"<h2>Окно {ширина}</h2><table><tr><th>Раздел</th>" + "".join(f"<th>{html.escape(р)}</th>" for р in роли) + "</tr>")
        md += [f"## Окно {ширина}", "", "| Раздел | " + " | ".join(роли) + " |", "|---|" + "---|" * len(роли)]
        for раздел in разделы:
            if not any((раздел, р, ширина) in сейчас or (раздел, р, ширина) in прежние for р in роли):
                continue
            h.append(f"<tr><td>{html.escape(раздел)}</td>")
            ячейки = []
            for р in роли:
                пары = []
                for метка, набор in (("до", прежние), ("после", сейчас)) if до else (("", сейчас),):
                    п = набор.get((раздел, р, ширина))
                    if п is None:
                        continue
                    отн = п.relative_to(папка.parent).as_posix()
                    отн_из_папки = ("../" + отн) if п.parent != папка else п.name
                    пары.append((метка, отн_из_папки))
                h.append("<td><div class=pair>" + "".join(
                    f"<a href='{html.escape(src)}'><span>{метка}</span><img src='{html.escape(src)}' loading=lazy></a>" for метка, src in пары
                ) + "</div></td>" if пары else "<td>—</td>")
                ячейки.append(" ".join(f"[<img src=\"{src}\" width=\"220\">]({src})" for _, src in пары) or "—")
            h.append("</tr>")
            md.append(f"| {раздел} | " + " | ".join(ячейки) + " |")
        h.append("</table>")
        md.append("")
    (папка / "лист.html").write_text("\n".join(h), encoding="utf-8")
    (папка / "ЛИСТ.md").write_text("\n".join(md), encoding="utf-8")
    print(f"лист: {папка / 'лист.html'} и ЛИСТ.md — снимков {len(сейчас)}" + (f", пар с «до» {sum(1 for к in сейчас if к in прежние)}" if до else ""))
    нет = полнота(папка)
    print("полнота: " + (f"все {len(РАЗДЕЛЫ)} разделов рейки × {len(РОЛИ)} роли × {len(ШИРИНЫ)} окна" if not нет else f"нет {len(нет)}: " + "; ".join(нет[:12]) + (" …" if len(нет) > 12 else "")))


def main() -> int:
    п = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    под = п.add_subparsers(dest="cmd", required=True)
    с = под.add_parser("shoot")
    с.add_argument("--label", required=True, help="до · после")
    с.add_argument("--date", default=None)
    с.add_argument("--base", default="http://localhost:8080", help="стенд (:8080) либо сервер разработки клиента (:5173)")
    с.add_argument("--entry", default="/v2.html", help="страница клиента v2: /v2.html на стенде, / на сервере разработки")
    с.add_argument("--project", default="PJ-ПМИ7")
    с.add_argument("--login", default="chernov", help="учётка владельца системы на стенде (выступает от имени ролей)")
    с.add_argument("--sections", default=None, help="разделы через запятую; пусто — вся рейка и эксперт-разделы")
    с.add_argument("--roles", default=None, help="руководитель,ведущий-СИ,инженер")
    с.add_argument("--tabs", default=None, help="разделы, у которых снимается каждая вкладка: Постановка,Поле знаний")
    с.add_argument("--card", default=None, help="разделы, у которых снимается карточка первой строки реестра")
    с.add_argument("--scenes", default=None, help="сцены работы, каждая своим снимком: 3,4,5,6")
    с.add_argument("--band", default=None, help="разделы, у которых снимается первая полоса группы раскрытой")
    с.add_argument("--open", default=None, help="«Раздел=имя кнопки»: открыть кнопкой и снять, например Документы=открыть документ")
    с.add_argument("--tab", default=None, help="«Раздел=вкладка»: перед полосой и карточкой открыть вкладку раздела (Поле знаний=Факты)")
    с.add_argument("--scroll", default=None, help="«Раздел=селектор» через «;»: к чему прокрутить снимок «открыто» (замечания точки)")
    с.add_argument("--widths", default=None, help="1440,1280")
    с.add_argument("--expert", action="store_true", help="с --sections: снимать и эксперт-разделы из списка")
    с.add_argument("--before", default=None, help="папка «до» для листа парами")
    л = под.add_parser("sheet")
    л.add_argument("--dir", required=True)
    л.add_argument("--before", default=None)
    к = под.add_parser("check", help="полнота папки: раздел рейки × три роли × оба окна (шип 5 §8 g)")
    к.add_argument("--dir", required=True)
    args = п.parse_args()
    if args.cmd == "shoot":
        папка = снять(args)
        лист(папка, (КОРЕНЬ / args.before).resolve() if args.before else None)
    elif args.cmd == "check":
        нет = полнота((КОРЕНЬ / args.dir).resolve())
        if нет:
            print(f"снимков не хватает: {len(нет)}")
            for н in нет:
                print("  ", н)
            return 1
        print(f"полнота: все {len(РАЗДЕЛЫ)} разделов рейки × {len(РОЛИ)} роли × {len(ШИРИНЫ)} окна")
    else:
        лист((КОРЕНЬ / args.dir).resolve(), (КОРЕНЬ / args.before).resolve() if args.before else None)
    return 0


if __name__ == "__main__":
    sys.exit(main())
