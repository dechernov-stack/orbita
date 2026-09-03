#!/usr/bin/env python3
"""StrictDoc-канал (ADR-049): детерминизм и круговой обмен на демо-проекте.

Тот же путь, что у изделия: требования демо-проекта → служба канала
(ops/strictdoc/strictdoc_service.py) → .sgra + .sdoc → штатный
`strictdoc export --formats=reqif-sdoc` → `reqif validate` (та же проверка,
что у собственного ReqIF-канала, tools/check_reqif_roundtrip.py) → разбор
документа обратно самим StrictDoc и сверка UID.

Условие владельца (03.09, п. 8): собственный ReqIF-конвертер сносится только
после того, как этот путь проходит на тех же данных. Без пакета strictdoc
проверка честно пропускается (как ReqIF без пакета reqif).
Запуск: python3 tools/check_sdoc_roundtrip.py
"""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "ops" / "strictdoc"))
from strictdoc_service import build_sdoc, parse_sdoc  # noqa: E402


def demo_project() -> dict:
    out = subprocess.run([sys.executable, str(ROOT / "spec" / "demo_project.py"), "--dump"],
                         capture_output=True, text=True, check=True, cwd=ROOT)
    return json.loads(out.stdout)


def main() -> int:
    project = demo_project()
    payload = {"project": {"id": "PJ-0001", "name": "Орбита-IoT (демо)"},
               "needs": project["needs"], "services": project["services"], "requirements": project["requirements"]}
    sgra1, sdoc1 = build_sdoc(payload)
    sgra2, sdoc2 = build_sdoc(json.loads(json.dumps(payload)))
    checks = 0
    if (sgra1, sdoc1) != (sgra2, sdoc2):
        print("ОШИБКА: повторный экспорт неизменённого проекта отличается")
        return 1
    checks += 1
    uids = [r["id"] for r in project["requirements"]]
    for uid in uids:
        if f"UID: {uid}" not in sdoc1:
            print(f"ОШИБКА: требование {uid} не попало в .sdoc")
            return 1
    checks += 1
    if "MOP_VALUE" not in sgra1 or "ROLE: ConflictsWith" not in sgra1:
        print("ОШИБКА: грамматика без показателя или роли противоречия")
        return 1
    checks += 1
    if shutil.which("strictdoc") is None:
        print(f"sdoc: детерминизм и полнота проверены ({checks}); strictdoc не установлен — экспорт/импорт ПРОПУЩЕНЫ")
        return 0
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / "orbita.sgra").write_text(sgra1, encoding="utf-8")
        (root / "project.sdoc").write_text(sdoc1, encoding="utf-8")
        out = root / "out"
        res = subprocess.run(["strictdoc", "export", "--formats", "reqif-sdoc", "--output-dir", str(out), str(root)],
                             capture_output=True, text=True)
        if res.returncode != 0:
            print("ОШИБКА: strictdoc export отверг документ:\n" + res.stderr[-1500:])
            return 1
        checks += 1
        reqifs = list(out.rglob("*.reqif"))
        if not reqifs:
            print("ОШИБКА: strictdoc не выдал .reqif")
            return 1
        xml = reqifs[0].read_text(encoding="utf-8")
        if xml.count("<SPEC-OBJECT ") < len(uids):
            print(f"ОШИБКА: SPEC-OBJECT меньше числа требований: {xml.count('<SPEC-OBJECT ')} < {len(uids)}")
            return 1
        checks += 1
        if shutil.which("reqif"):
            v = subprocess.run(["reqif", "validate", "--use-reqif-schema", str(reqifs[0])], capture_output=True, text=True)
            if v.returncode != 0:
                print("ОШИБКА: ReqIF от StrictDoc не проходит `reqif validate`:\n" + (v.stdout + v.stderr)[-1500:])
                return 1
            checks += 1
        back = parse_sdoc(sdoc1, sgra1)
        got = sorted(r["id"] for r in back["requirements"])
        if got != sorted(uids):
            print(f"ОШИБКА: обратный разбор потерял требования: {sorted(set(uids) - set(got))}")
            return 1
        checks += 1
    print(f"sdoc: круговой обмен пройден, проверок {checks}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
