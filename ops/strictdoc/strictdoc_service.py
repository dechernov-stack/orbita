# Служба StrictDoc-канала (ADR-049): требования Орбиты ↔ .sdoc по грамматике
# Орбиты (.sgra), дальше штатный `strictdoc export` (ReqIF, HTML, PDF, XLSX).
# MID StrictDoc зарезервирован; внутренний id Орбиты равен коду — UID и есть MID.
# и `strictdoc import reqif`. Собственного конвертера форматов нет: здесь —
# только раскладка полей модели в грамматику (поле в поле) и разбор
# документа средствами самого StrictDoc. Идентификаторы: UID = код (RQ-NNNN),
# MID = внутренний id; повторный экспорт неизменённого проекта даёт тот же
# текст побайтно (детерминизм — тестом).
import json
import os
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("ORBITA_STRICTDOC_PORT", "8093"))

# Структура требования (ADR-045) + показатель парой (наше преимущество перед workbench)
REQUIREMENT_FIELDS = [
    ("UID", "id", True), ("TITLE", "title", False), ("STATEMENT", "statement", True),
    ("CATEGORY", "category", False), ("LEVEL", "level", False), ("PRIORITY", "priority", False),
    ("SOURCE_DOC", "source.doc", False), ("SOURCE_ANCHOR", "source.anchor", False),
    ("NORMATIVE_BASIS", "normative_basis.ref", False), ("NORMATIVE_CLAUSE", "normative_basis.clause", False),
    ("RATIONALE", "rationale", False), ("ACCEPTANCE_CRITERIA", "acceptance_criteria", False),
    ("MOP_NAME", "mop.name", False), ("MOP_OP", "mop.operator", False),
    ("MOP_VALUE", "mop.value.value", False), ("MOP_UNIT", "mop.value.unit", False),
    ("VERIFICATION_METHOD", "verification_method", False),
    ("STATUS", "lifecycle.status", False), ("VERSION", "lifecycle.version", False),
    ("OWNER", "owner", False), ("TAGS", "tags", False),
]
NEED_FIELDS = [("UID", "id", True), ("STATEMENT", "statement", True), ("STAKEHOLDER", "stakeholder.name", False)]
SERVICE_FIELDS = [("UID", "id", True), ("TITLE", "name", True)]
RELATION_ROLES = ["Refines", "Derives", "DependsOn", "ConflictsWith", "Traces"]
MULTILINE = {"STATEMENT", "RATIONALE", "ACCEPTANCE_CRITERIA"}


def _get(doc: dict, path: str):
    cur = doc
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def _value(doc: dict, path: str) -> str | None:
    v = _get(doc, path)
    if v is None or v == "" or v == []:
        return None
    if isinstance(v, list):
        return ", ".join(str(x) for x in v)
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def grammar() -> str:
    """Грамматика Орбиты (.sgra): элементы NEED · SERVICE · REQUIREMENT, роли связей."""
    lines = ["[GRAMMAR]", "ELEMENTS:",
             # раздел — составной узел (StrictDoc ≥ 0.29: [[SECTION]] вместо [SECTION])
             "- TAG: SECTION", "  PROPERTIES:", "    IS_COMPOSITE: True", "  FIELDS:",
             "  - TITLE: TITLE", "    TYPE: String", "    REQUIRED: True"]
    for tag, fields in (("NEED", NEED_FIELDS), ("SERVICE", SERVICE_FIELDS), ("REQUIREMENT", REQUIREMENT_FIELDS)):
        lines += [f"- TAG: {tag}", "  FIELDS:"]
        for name, _, required in fields:
            lines += [f"  - TITLE: {name}", "    TYPE: String", f"    REQUIRED: {'True' if required else 'False'}"]
        if tag == "REQUIREMENT":
            lines.append("  RELATIONS:")
            for role in RELATION_ROLES:
                lines += ["  - TYPE: Parent", f"    ROLE: {role}"]
    return "\n".join(lines) + "\n"


def _element(tag: str, doc: dict, fields, relations=None) -> list[str]:
    out = [f"[{tag}]"]
    for name, path, required in fields:
        v = _value(doc, path)
        if v is None:
            if required:
                v = "—"
            else:
                continue
        if name in MULTILINE or "\n" in v:
            out += [f"{name}: >>>", v.strip(), "<<<"]
        else:
            out.append(f"{name}: {v}")
    if relations:
        out.append("RELATIONS:")
        for value, role in relations:
            out += ["- TYPE: Parent", f"  VALUE: {value}", f"  ROLE: {role}"]
    out.append("")
    return out


def build_sdoc(payload: dict) -> tuple[str, str]:
    """(.sgra, .sdoc) из модели: нужды, сервисы, требования с полями и связями.
    Порядок — по идентификаторам: повторный экспорт того же проекта совпадает побайтно."""
    project = payload.get("project") or {}
    title = project.get("name") or project.get("id") or "Проект"
    lines = ["[DOCUMENT]", f"TITLE: {title}", "", "[GRAMMAR]", "IMPORT_FROM_FILE: orbita.sgra", ""]
    needs = sorted(payload.get("needs", []), key=lambda d: d.get("id", ""))
    services = sorted(payload.get("services", []), key=lambda d: d.get("id", ""))
    requirements = sorted(payload.get("requirements", []), key=lambda d: d.get("id", ""))
    if needs:
        lines += ["[[SECTION]]", "TITLE: Нужды", ""]
        for n in needs:
            lines += _element("NEED", n, NEED_FIELDS)
        lines += ["[[/SECTION]]", ""]
    if services:
        lines += ["[[SECTION]]", "TITLE: Сервисы", ""]
        for s in services:
            lines += _element("SERVICE", s, SERVICE_FIELDS)
        lines += ["[[/SECTION]]", ""]
    lines += ["[[SECTION]]", "TITLE: Требования", ""]
    for r in requirements:
        rels: list[tuple[str, str]] = []
        for parent in r.get("derives_from", []) or []:
            rels.append((parent, "Derives"))
        for rel in r.get("relations", []) or []:
            role = {"refines": "Refines", "derives": "Derives", "depends_on": "DependsOn", "conflicts_with": "ConflictsWith"}.get(rel.get("kind", ""), "Traces")
            rels.append((rel.get("ref", ""), role))
        for t in r.get("traces_up", []) or []:
            ref = t.get("ref", "")
            if ref:
                rels.append((ref, "Traces"))
        seen = set(); uniq = []
        for v, role in rels:
            if (v, role) not in seen:
                seen.add((v, role)); uniq.append((v, role))
        lines += _element("REQUIREMENT", r, REQUIREMENT_FIELDS, uniq)
    lines += ["[[/SECTION]]", ""]
    return grammar(), "\n".join(lines)


def parse_sdoc(sdoc: str, sgra: str | None = None) -> dict:
    """Разбор документа средствами StrictDoc (SDReader): кандидаты с чужими полями в foreign_attributes."""
    from strictdoc.backend.sdoc.reader import SDReader  # noqa: WPS433

    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / "orbita.sgra").write_text(sgra or grammar(), encoding="utf-8")
        path = root / "import.sdoc"
        path.write_text(sdoc, encoding="utf-8")
        document = SDReader().read(sdoc, file_path=str(path))
        known = {name for name, _, _ in REQUIREMENT_FIELDS}
        out = []
        for node in document.iterate_nodes():
            if getattr(node, "node_type", None) != "REQUIREMENT":
                continue
            values: dict[str, str] = {}
            for field in node.enumerate_fields():
                values[field.field_name] = field.get_text_value().strip()
            relations = []
            for rel in getattr(node, "relations", None) or []:
                relations.append({"ref": getattr(rel, "ref_uid", None), "role": getattr(rel, "role", None)})
            out.append({
                "id": values.get("UID"), "title": values.get("TITLE"), "statement": values.get("STATEMENT"),
                "category": values.get("CATEGORY"), "level": values.get("LEVEL"), "priority": values.get("PRIORITY"),
                "rationale": values.get("RATIONALE"), "acceptance_criteria": values.get("ACCEPTANCE_CRITERIA"),
                "mop": {k.lower().replace("mop_", ""): v for k, v in values.items() if k.startswith("MOP_")} or None,
                "foreign_attributes": {k: v for k, v in values.items() if k not in known},
                "relations": relations,
            })
    return {"requirements": out}


def export_formats(sgra: str, sdoc: str, formats: list[str]) -> dict:
    """Штатный strictdoc export: ReqIF/HTML/PDF/XLSX — самим StrictDoc."""
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / "orbita.sgra").write_text(sgra, encoding="utf-8")
        (root / "project.sdoc").write_text(sdoc, encoding="utf-8")
        out = root / "out"
        cmd = ["strictdoc", "export", "--formats", ",".join(formats), "--output-dir", str(out), str(root)]
        res = subprocess.run(cmd, capture_output=True, text=True)
        result = {"ok": res.returncode == 0, "stderr": res.stderr[-2000:], "files": {}}
        if out.exists():
            for f in out.rglob("*"):
                if f.is_file() and f.suffix in {".reqif", ".xlsx", ".pdf"}:
                    result["files"][f.name] = f.read_text(encoding="utf-8") if f.suffix == ".reqif" else None
        return result


class Handler(BaseHTTPRequestHandler):
    def _json(self, code: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode() or "{}")

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._json(200, {"ok": True})
        else:
            self._json(404, {"error": "нет такого пути"})

    def do_POST(self) -> None:  # noqa: N802
        try:
            body = self._body()
            if self.path == "/sdoc/build":
                sgra, sdoc = build_sdoc(body)
                self._json(200, {"sgra": sgra, "sdoc": sdoc})
            elif self.path == "/sdoc/export":
                sgra, sdoc = build_sdoc(body.get("payload", {}))
                self._json(200, {"sgra": sgra, "sdoc": sdoc, **export_formats(sgra, sdoc, body.get("formats") or ["reqif-sdoc"])})
            elif self.path == "/sdoc/import":
                self._json(200, parse_sdoc(body.get("sdoc", ""), body.get("sgra")))
            else:
                self._json(404, {"error": "нет такого пути"})
        except Exception as e:  # noqa: BLE001
            self._json(500, {"error": f"{type(e).__name__}: {e}"})

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        sys.stderr.write("strictdoc: " + fmt % args + "\n")


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
