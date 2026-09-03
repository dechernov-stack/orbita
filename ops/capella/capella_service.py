# Адаптер Capella (ADR-048): читает модель библиотекой capellambse (Apache-2.0)
# и отдаёт элементы по HTTP. ТОЛЬКО ЧТЕНИЕ: модель монтируется в контейнер
# только на чтение, в .capella/.aird адаптер не пишет никогда — сторож
# tools/validate_capella_readonly.py проверяет и код, и монтирование.
#
# Без модели (ORBITA_CAPELLA_MODEL не задан или файла нет) адаптер отвечает
# 503 с причиной: система показывает fixture с баннером, а не «интеграцию».
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = os.environ.get("ORBITA_CAPELLA_MODEL", "")
PORT = int(os.environ.get("ORBITA_CAPELLA_PORT", "8092"))

LAYERS = {"oa": "OA", "sa": "SA", "la": "LA", "pa": "PA"}


def load_elements() -> list[dict]:
    """Элементы модели: UUID — истина, имя — снимок; типы по словарю решения."""
    import capellambse  # импорт здесь: без модели библиотека не нужна

    model = capellambse.MelodyModel(MODEL)
    out: list[dict] = []

    def add(objs, typ: str, layer: str) -> None:
        for o in objs:
            out.append({
                "uuid": o.uuid, "type": typ, "layer": layer, "name": o.name or o.uuid,
                "parent_uuid": getattr(getattr(o, "parent", None), "uuid", None),
            })

    add(model.oa.all_capabilities, "OperationalCapability", "OA")
    add(model.sa.all_functions, "SystemFunction", "SA")
    add(model.la.all_functions, "LogicalFunction", "LA")
    add(model.la.all_components, "LogicalComponent", "LA")
    add(model.pa.all_components, "PhysicalComponent", "PA")
    add(model.la.all_function_exchanges, "FunctionalExchange", "LA")
    add(model.la.all_functional_chains, "FunctionalChain", "LA")
    return out


class Handler(BaseHTTPRequestHandler):
    def _json(self, code: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._json(200, {"ok": True, "model": bool(MODEL and os.path.exists(MODEL))})
            return
        if self.path.startswith("/elements"):
            if not MODEL or not os.path.exists(MODEL):
                self._json(503, {"error": "модели нет: ORBITA_CAPELLA_MODEL не задан или файл отсутствует — работает fixture, не интеграция"})
                return
            try:
                elements = load_elements()
            except Exception as e:  # noqa: BLE001
                self._json(500, {"error": f"модель не прочитана: {e}"})
                return
            self._json(200, {"model_id": os.path.basename(MODEL), "elements": elements})
            return
        self._json(404, {"error": "нет такого пути"})

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        sys.stderr.write("capella: " + fmt % args + "\n")


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
