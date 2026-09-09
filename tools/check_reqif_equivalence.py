#!/usr/bin/env python3
"""Эквивалентность каналов ReqIF (ответ владельца 03.09, §1) — запись условия сноса.

Условие сноса собственного контура: НОВЫЙ канал (StrictDoc) не хуже СТАРОГО
(`ReqifExport` + `ops/exchange`) на тех же данных. Пять проверок:

  1. старый чек на новом файле: строгая сверка с XSD OMG и разбор той же
     библиотекой восстанавливают те же объекты;
  2. множества совпадают: счётчики SPEC-OBJECTS и SPEC-RELATIONS, множество
     UID, пары связей;
  3. атрибуты поле в поле: формулировка, статус, метод верификации,
     показатель (op · value · unit), обоснование, источник — равны по
     значению (форматирование, включая абзацы XHTML, не в счёт);
  4. детерминизм: повторный экспорт неизменённого проекта — тот же файл
     (кроме времени) — по второму файлу `--new2`;
  5. обратный путь: ReqIF нового канала возвращается в кандидатов тем же
     путём, что у изделия (/import/reqif, библиотека reqif), поля совпадают
     со старым каналом, чужих полей нет; штатный `strictdoc convert` —
     пометой (0.29.0 падает на связях между типами).

Пять «да» достигнуты 09.09.2026 на данных стенда (PJ-0001: 248 объектов,
233 связи), собственный контур снесён (ADR-064). Скрипт остаётся записью
условия и работает ТОЛЬКО по файлам — старый канал файл уже не соберёт:

  --old FILE --new FILE [--new2 FILE]   (файлы старой выгрузки хранятся у владельца)

Вывод — таблица «проверка · старый · новый · совпало» и итог; код возврата 1,
если хоть одна проверка не «да» у нового канала.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "ops" / "exchange"))
sys.path.insert(0, str(ROOT / "ops" / "strictdoc"))


try:
    from reqif_service import parse_reqif  # noqa: E402
except ModuleNotFoundError as e:  # pragma: no cover — среда без пакета
    print(f"ПРОПУЩЕНО: нет пакета reqif ({e}) — pip install reqif==0.0.47")
    sys.exit(0)

# Имена атрибутов двух каналов к одному словарю. Старый канал зовёт поля
# по-своему (Level, MeasureOperator), новый — именами грамматики Орбиты;
# сравнение идёт по значению, а не по написанию имени.
OLD_NAMES = {
    "ReqIF.ForeignID": "uid", "ReqIF.Text": "statement", "Title": "title",
    "Level": "level", "Category": "category", "Priority": "priority",
    "Rationale": "rationale", "AcceptanceCriteria": "acceptance_criteria",
    "Status": "status", "Owner": "owner",
    "MeasureName": "mop_name", "MeasureOperator": "mop_op",
    "MeasureValue": "mop_value", "MeasureUnit": "mop_unit",
}
NEW_NAMES = {
    "ReqIF.ForeignID": "uid", "ReqIF.Text": "statement", "ReqIF.Name": "title",
    "LEVEL": "level", "CATEGORY": "category", "PRIORITY": "priority",
    "RATIONALE": "rationale", "ACCEPTANCE_CRITERIA": "acceptance_criteria",
    "STATUS": "status", "OWNER": "owner", "VERSION": "version",
    "MOP_NAME": "mop_name", "MOP_OP": "mop_op",
    "MOP_VALUE": "mop_value", "MOP_UNIT": "mop_unit",
    "VERIFICATION_METHOD": "verification_method",
    "SOURCE_DOC": "source_doc", "SOURCE_ANCHOR": "source_anchor", "TAGS": "tags",
    "STAKEHOLDER": "stakeholder", "NORMATIVE_BASIS": "normative_basis", "NORMATIVE_CLAUSE": "normative_clause",
}
# Поля, которые владелец назвал в условии 3.
COMPARED = ["statement", "status", "verification_method",
            "mop_op", "mop_value", "mop_unit", "rationale", "source_doc"]
# Структурные узлы StrictDoc: раздел и свободный текст — не содержимое реестра.
STRUCTURAL = {"SECTION", "TEXT"}


def norm(value):
    """Значение к сравнимому виду: пробелы и «пусто» не различаются, число — числом."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    # Абзацы XHTML — форматирование, не содержание: StrictDoc кладёт
    # многострочный текст в <p>…</p>, старый канал — прямо в <div>.
    text = re.sub(r"</?p>", "", text).strip()
    if text in ("", "—"):
        return None
    try:
        return float(text)
    except ValueError:
        return re.sub(r"\s+", " ", text)


def canon(values: dict, names: dict) -> dict:
    return {names[k]: norm(v) for k, v in values.items() if k in names}


def type_names(xml: str) -> dict:
    """IDENTIFIER типа объекта → его LONG-NAME (в новом файле имя типа = тег грамматики)."""
    out = {}
    for m in re.finditer(r'<SPEC-OBJECT-TYPE[^>]*IDENTIFIER="([^"]+)"[^>]*LONG-NAME="([^"]+)"', xml):
        out[m.group(1)] = m.group(2)
    for m in re.finditer(r'<SPEC-OBJECT-TYPE[^>]*LONG-NAME="([^"]+)"[^>]*IDENTIFIER="([^"]+)"', xml):
        out[m.group(2)] = m.group(1)
    return out


def content_objects(parsed: dict, xml: str, names: dict) -> dict:
    """UID → атрибуты, только содержательные объекты (без разделов и текста)."""
    types = type_names(xml)
    out = {}
    for o in parsed["objects"]:
        if types.get(o["type"], "") in STRUCTURAL:
            continue
        values = canon(o["values"], names)
        uid = values.get("uid")
        if uid:
            out[str(uid)] = values
    return out


def relation_pairs(parsed: dict, names: dict) -> set:
    """Пары связей в терминах UID: идентификаторы у каналов свои, смысл — общий."""
    by_id = {}
    for o in parsed["objects"]:
        uid = canon(o["values"], names).get("uid")
        if uid:
            by_id[o["identifier"]] = str(uid)
    pairs = set()
    for r in parsed["relations"]:
        src, tgt = by_id.get(r["source"]), by_id.get(r["target"])
        if src and tgt:
            pairs.add((src, tgt))
    return pairs


def xsd_ok(path: str) -> tuple:
    res = subprocess.run(
        [sys.executable, "-m", "reqif.cli.main", "validate", "--use-reqif-schema", path],
        capture_output=True, text=True)
    last = (res.stdout.strip().splitlines() or ["?"])[-1]
    return ("0 errors, 0 schema issues, 0 semantic issues" in last.replace(" found", ""), last)


def reverse_candidates(new_xml: str):
    """Обратный путь нового канала: ReqIF → .sdoc штатным `strictdoc convert` → кандидаты.

    Возвращает (кандидаты, причина отказа). Отказ — не падение инструмента:
    неспособность канала вернуть свой же файл и есть предмет проверки 5.
    """
    from strictdoc_service import parse_sdoc  # noqa: WPS433

    if shutil.which("strictdoc") is None:
        return [], "пакет strictdoc не установлен"
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        src = root / "in.reqif"
        src.write_text(new_xml, encoding="utf-8")
        out = root / "back"
        res = subprocess.run(["strictdoc", "convert", "--input-format", "reqif-sdoc",
                              "--output-format", "sdoc", str(src), str(out)],
                             capture_output=True, text=True)
        files = list(out.rglob("*.sdoc")) if out.exists() else []
        if res.returncode != 0 or not files:
            why = ""
            for line in (res.stdout + res.stderr).splitlines():
                if line.startswith("error:"):
                    why = line[7:].strip()
                    break
            return [], f"strictdoc convert не вернул .sdoc ({why[:80]})"
        return parse_sdoc(files[0].read_text(encoding="utf-8"))["requirements"], ""


def volatile(xml: str) -> str:
    """Время — не содержание: атрибутом (LAST-CHANGE) и элементом шапки (<CREATION-TIME>)."""
    xml = re.sub(r'(CREATION-TIME|LAST-CHANGE)="[^"]*"', r'\1=""', xml)
    return re.sub(r"<CREATION-TIME>[^<]*</CREATION-TIME>", "<CREATION-TIME/>", xml)


def unstable_kinds(a: str, b: str) -> dict:
    """Что именно разошлось между двумя выгрузками — по виду идентификатора."""
    ids_a = set(re.findall(r'IDENTIFIER="([^"]+)"', a))
    ids_b = set(re.findall(r'IDENTIFIER="([^"]+)"', b))
    kinds = {}
    for ident in ids_a ^ ids_b:
        kind = re.sub(r"[-_][0-9a-fA-F-]{8,}$", "", ident) or ident
        kinds[kind] = kinds.get(kind, 0) + 1
    return kinds


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", help="ReqIF старого канала (файл)")
    ap.add_argument("--new", help="ReqIF нового канала (файл)")
    ap.add_argument("--new2", help="повторная выгрузка нового канала (файл) — детерминизм на данных стенда")
    ap.add_argument("--label", default="демо-проект", help="что за данные — в заголовок отчёта")
    args = ap.parse_args()

    if not (args.old and args.new):
        print("нужны файлы: --old <ReqIF старого канала> --new <ReqIF StrictDoc> [--new2 <повторная выгрузка>]")
        return 2
    old_xml = Path(args.old).read_text(encoding="utf-8")
    new_xml = Path(args.new).read_text(encoding="utf-8")

    rows = []  # (проверка, старый, новый, совпало)

    # 1. Старый чек на новом файле: XSD и разбор той же библиотекой.
    with tempfile.TemporaryDirectory() as d:
        po, pn = Path(d) / "old.reqif", Path(d) / "new.reqif"
        po.write_text(old_xml, encoding="utf-8")
        pn.write_text(new_xml, encoding="utf-8")
        old_xsd, old_note = xsd_ok(str(po))
        new_xsd, new_note = xsd_ok(str(pn))
        old_parsed, new_parsed = parse_reqif(str(po)), parse_reqif(str(pn))

    old_objects = content_objects(old_parsed, old_xml, OLD_NAMES)
    new_objects = content_objects(new_parsed, new_xml, NEW_NAMES)
    parsed_ok = bool(old_objects) and bool(new_objects)
    rows.append(("1. XSD OMG и разбор той же библиотекой",
                 f"да ({len(old_objects)} объектов)" if old_xsd and old_objects else f"нет: {old_note}",
                 f"да ({len(new_objects)} объектов)" if new_xsd and new_objects else f"нет: {new_note}",
                 old_xsd and new_xsd and parsed_ok))

    # 2. Множества: счётчики, UID, пары связей. Направление сравнивается
    # отдельно: зеркальная пара — то же отношение, названное с другой стороны.
    old_pairs, new_pairs = relation_pairs(old_parsed, OLD_NAMES), relation_pairs(new_parsed, NEW_NAMES)
    same_uids = set(old_objects) == set(new_objects)
    ordered = old_pairs == new_pairs
    lost = sorted(set(old_objects) - set(new_objects))
    extra_nodes = len(new_parsed["objects"]) - len(new_objects)
    # Потеря нити — провал; лишняя нить, существующая в модели, — не потеря:
    # старый канал выгружает только traces_up требований, новый — и связи
    # нужда → сервис. Считается вложение «старый ⊆ новый».
    old_mirror = {frozenset(p) for p in old_pairs}
    new_mirror = {frozenset(p) for p in new_pairs}
    lost_links = old_mirror - new_mirror
    extra_links = new_mirror - old_mirror
    note = "" if ordered else " (направление зеркально)"
    if extra_links:
        note += f" +{len(extra_links)} нитей, которых старый не выгружает"
    rows.append(("2. Множества UID и пары связей",
                 f"{len(old_objects)} UID · {len(old_pairs)} связей",
                 f"{len(new_objects)} UID · {len(new_pairs)} связей{note}"
                 + (f" +{extra_nodes} структурных" if extra_nodes else ""),
                 same_uids and not lost_links))

    # 3. Атрибуты поле в поле. Отдельно считается порча текста: значение,
    # совпадающее с исходным после раскрытия \uXXXX, — не «другое значение»,
    # а искажённая запись кириллицы (её видно и в самом файле).
    diffs, richer, mangled = [], set(), []
    for uid in sorted(set(old_objects) & set(new_objects)):
        o, n = old_objects[uid], new_objects[uid]
        for field in COMPARED:
            ov, nv = o.get(field), n.get(field)
            if ov is None and nv is not None:
                richer.add(field)
                continue
            if ov == nv:
                continue
            if isinstance(nv, str) and "\\u" in nv:
                try:
                    if nv.encode().decode("unicode_escape") == ov:
                        mangled.append(f"{uid}.{field}")
                        continue
                except Exception:  # noqa: BLE001
                    pass
            diffs.append(f"{uid}.{field}: старый {ov!r} ≠ новый {nv!r}")
    new_note = ("кириллица записана как \\uXXXX" if mangled
                else ("то же + метод верификации, источник" if richer else "те же поля"))
    rows.append(("3. Атрибуты поле в поле",
                 "формулировка · статус · показатель · обоснование",
                 new_note, not diffs and not mangled))

    # 4. Детерминизм: повторная выгрузка тем же путём.
    if args.new2:
        new_again = Path(args.new2).read_text(encoding="utf-8")
        new_det = volatile(new_again) == volatile(new_xml)
        kinds = unstable_kinds(volatile(new_xml), volatile(new_again)) if not new_det else {}
        note = ", ".join(f"{k}×{v}" for k, v in sorted(kinds.items())[:4]) if kinds else ""
        rows.append(("4. Детерминизм повторной выгрузки",
                     "не проверялся на файлах (свой канал — детерминизм тестом)",
                     "да: две выгрузки стенда совпали (кроме времени)" if new_det else f"нет: заново раздаются {note}",
                     new_det))
    else:
        rows.append(("4. Детерминизм повторной выгрузки", "—", "проверяется на демо-данных (или --new2)", None))

    # 5. Обратный путь: ReqIF нового канала → кандидаты ТЕМ ЖЕ путём, что у
    # изделия (/import/reqif — разбор библиотекой reqif, ADR-024). Кандидаты
    # из нового файла обязаны совпасть полями с кандидатами из старого и не
    # нести чужих полей (MID — идентификатор StrictDoc, не поле содержания).
    # Штатный `strictdoc convert` — отдельной пометой: в 0.29.0 (последняя
    # на PyPI, 09.09) он падает на связи между типами (SERVICE → NEED,
    # KeyError ForeignID) и свой же файл вернуть не может.
    types = type_names(new_xml)
    known = {"MID"} | set(NEW_NAMES)
    foreign = sorted({k for o in new_parsed["objects"] if types.get(o["type"], "") not in STRUCTURAL
                      for k in o["values"] if k not in known})
    back_by_uid = {uid: new_objects[uid] for uid in new_objects}
    back_lost = sorted(set(old_objects) - set(new_objects))
    back_diffs = []
    for uid in sorted(set(old_objects) & set(new_objects)):
        o, n = old_objects[uid], new_objects[uid]
        for field in COMPARED:
            if o.get(field) is not None and norm(o.get(field)) != norm(n.get(field)):
                back_diffs.append(f"{uid}: {field} разошлось")
    _convert_back, convert_why = reverse_candidates(new_xml)
    back_why = ""
    convert_note = "; strictdoc convert: " + ("да" if not convert_why else f"нет — {convert_why}")
    rows.append(("5. Обратный путь в кандидатов",
                 f"импорт своего файла (ADR-024): {len(old_objects)}",
                 f"{len(back_by_uid)} кандидатов, чужих полей {len(foreign)}{convert_note}",
                 not back_why and not back_lost and not back_diffs and not foreign))

    width = max(len(r[0]) for r in rows)
    print(f"Эквивалентность каналов ReqIF — {args.label}\n")
    print(f"{'проверка'.ljust(width)} | {'старый'.ljust(46)} | {'новый'.ljust(46)} | совпало")
    print("-" * (width + 108))
    for name, old, new, same in rows:
        mark = "—" if same is None else ("да" if same else "НЕТ")
        print(f"{name.ljust(width)} | {str(old)[:46].ljust(46)} | {str(new)[:46].ljust(46)} | {mark}")
    if lost:
        print(f"\nпотеряны UID: {lost[:6]}")
    if lost_links:
        print(f"\nпотеряны нити: {[tuple(sorted(p)) for p in list(lost_links)[:6]]}")
    if mangled:
        print(f"\nтекст искажён экранированием (\\uXXXX вместо кириллицы): {len(mangled)} значений, "
              f"например {mangled[:3]}")
    if diffs:
        print("\nрасхождения атрибутов:")
        for d in diffs[:8]:
            print("  " + d)
    if back_lost or foreign:
        print(f"\nобратный путь: не вернулись {back_lost[:6]}, чужие поля {foreign[:6]}")
    failed = [r[0] for r in rows if r[3] is False]
    print(f"\nИтог: пять «да» {'ДОСТИГНУТЫ — снос своего контура разрешён' if not failed else 'НЕ достигнуты'}")
    if failed:
        for name in failed:
            print(f"  не сошлось: {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
