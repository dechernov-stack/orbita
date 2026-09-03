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
import uuid
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
# У сервиса имя — и заголовок, и текст: старый канал кладёт его в ReqIF.Text,
# и без STATEMENT сравнение каналов показывало «текста нет»
SERVICE_FIELDS = [("UID", "id", True), ("TITLE", "name", True), ("STATEMENT", "name", True)]
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
    # MID объявляется полем каждого элемента: без него ENABLE_MID отвергается
    # семантической проверкой StrictDoc, а без ENABLE_MID идентификаторы узлов
    # в ReqIF раздаются заново при каждом экспорте
    mid_field = ["  - TITLE: MID", "    TYPE: String", "    REQUIRED: True"]
    lines = ["[GRAMMAR]", "ELEMENTS:",
             # раздел — составной узел (StrictDoc ≥ 0.29: [[SECTION]] вместо [SECTION])
             "- TAG: SECTION", "  PROPERTIES:", "    IS_COMPOSITE: True", "  FIELDS:",
             *mid_field,
             "  - TITLE: TITLE", "    TYPE: String", "    REQUIRED: True"]
    for tag, fields in (("NEED", NEED_FIELDS), ("SERVICE", SERVICE_FIELDS), ("REQUIREMENT", REQUIREMENT_FIELDS)):
        lines += [f"- TAG: {tag}", "  FIELDS:", *mid_field]
        for name, _, required in fields:
            lines += [f"  - TITLE: {name}", "    TYPE: String", f"    REQUIRED: {'True' if required else 'False'}"]
        if tag in ("REQUIREMENT", "SERVICE"):
            lines.append("  RELATIONS:")
            for role in RELATION_ROLES:
                lines += ["  - TYPE: Parent", f"    ROLE: {role}"]
    return "\n".join(lines) + "\n"


# Пространство имён устойчивых MID: StrictDoc сам раздаёт узлам случайные
# uuid4 при каждом экспорте, и файл ReqIF выходит каждый раз новым. MID,
# выведенный из нашего идентификатора, делает идентичность узла нашей —
# и повторный экспорт неизменённого проекта даёт те же SPEC-OBJECT.
MID_NS = uuid.UUID("6f2b9a4e-0c4d-5f7a-9b1e-3d5c7a9f1b2d")


def mid_of(key: str) -> str:
    return uuid.uuid5(MID_NS, f"orbita:{key}").hex


def _dedupe(pairs):
    seen, out = set(), []
    for pair in pairs:
        if pair[0] and pair not in seen:
            seen.add(pair)
            out.append(pair)
    return out


def _link_parents(payload: dict, node_id, needs, services, requirements):
    """Связи модели из ТАБЛИЦЫ СВЯЗЕЙ (trace · derive): документ объекта знает не
    про все нити — часть система выводит сама (распределение на корень,
    пересчёт), и без них .sdoc терял бы трассировку, которую нёс старый канал."""
    if not node_id:
        return []
    known = {x.get("id") for x in (list(needs) + list(services) + list(requirements))}
    out = []
    for link in payload.get("links", []) or []:
        if link.get("to") != node_id:
            continue
        ref = link.get("from", "")
        if ref not in known:
            continue
        out.append((ref, {"trace": "Traces", "derive": "Derives"}.get(link.get("kind", ""), "Traces")))
    return out


def _element(tag: str, doc: dict, fields, relations=None) -> list[str]:
    out = [f"[{tag}]"]
    key = _value(doc, "id")
    if key:
        out.append(f"MID: {mid_of(key)}")
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
    lines = ["[DOCUMENT]", f"MID: {mid_of(project.get('id') or title)}", f"TITLE: {title}",
             "OPTIONS:", "  ENABLE_MID: True", "", "[GRAMMAR]", "IMPORT_FROM_FILE: orbita.sgra", ""]
    needs = sorted(payload.get("needs", []), key=lambda d: d.get("id", ""))
    services = sorted(payload.get("services", []), key=lambda d: d.get("id", ""))
    requirements = sorted(payload.get("requirements", []), key=lambda d: d.get("id", ""))
    if needs:
        lines += ["[[SECTION]]", f"MID: {mid_of('section:needs')}", "TITLE: Нужды", ""]
        for n in needs:
            lines += _element("NEED", n, NEED_FIELDS)
        lines += ["[[/SECTION]]", ""]
    if services:
        lines += ["[[SECTION]]", f"MID: {mid_of('section:services')}", "TITLE: Сервисы", ""]
        for svc in services:
            rels = [(ref, role) for ref, role in _link_parents(payload, svc.get("id"), needs, services, requirements)]
            rels += [(nd, "Traces") for nd in (svc.get("traces_up") or []) if isinstance(nd, str)]
            lines += _element("SERVICE", svc, SERVICE_FIELDS, _dedupe(rels))
        lines += ["[[/SECTION]]", ""]
    lines += ["[[SECTION]]", f"MID: {mid_of('section:requirements')}", "TITLE: Требования", ""]
    for r in requirements:
        rels: list[tuple[str, str]] = []
        # связь на объект, которого нет в документе, принимающий инструмент
        # считает битой — такие в файл не идут
        rels += _link_parents(payload, r.get("id"), needs, services, requirements)
        for parent in r.get("derives_from", []) or []:
            rels.append((parent, "Derives"))
        for rel in r.get("relations", []) or []:
            role = {"refines": "Refines", "derives": "Derives", "depends_on": "DependsOn", "conflicts_with": "ConflictsWith"}.get(rel.get("kind", ""), "Traces")
            rels.append((rel.get("ref", ""), role))
        for t in r.get("traces_up", []) or []:
            ref = t.get("ref", "")
            if ref:
                rels.append((ref, "Traces"))
        lines += _element("REQUIREMENT", r, REQUIREMENT_FIELDS, _dedupe(rels))
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
        cmd = ["strictdoc", "export", "--formats", ",".join(formats), "--reqif-enable-mid",
               "--output-dir", str(out), str(root)]
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
