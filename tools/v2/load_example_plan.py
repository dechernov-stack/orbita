#!/usr/bin/env python3
"""План работ фазы для примера — из сида поставки, а не руками.

Сид `ПЛАН-PRE-A-IoT-ПРИМЕР.json` описывает ленту примера словами поставки
(точки с датами, окна сцен с ответственными). Продукт принимает план своим
контрактом (`gate_dates`, `scene_windows`), и перевод одного в другое —
работа генератора, а не человека в форме.

  python3 tools/v2/load_example_plan.py PJ-0001 [http://localhost:8080/api]
"""
import json
import pathlib
import sys
import urllib.request

КОРЕНЬ = pathlib.Path(__file__).resolve().parent.parent.parent
СИД = КОРЕНЬ / "docs/tz/v2/сиды/ПЛАН-PRE-A-IoT-ПРИМЕР.json"


def вход(база: str) -> str | None:
    """Стенд пускает витринную учётку без пароля — своей ручкой."""
    try:
        with urllib.request.urlopen(база + "/auth/whoami") as r:
            кто = json.loads(r.read().decode())
    except Exception:
        return None
    if кто.get("mode") != "stand":
        return None
    учётки = [u["login"] for u in кто.get("stand_users", [])]
    if not учётки:
        return None
    req = urllib.request.Request(
        база + "/auth/stand-login",
        data=json.dumps({"login": учётки[0]}).encode(),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        for куки in r.headers.get_all("Set-Cookie") or []:
            for часть in куки.split(";"):
                имя, _, значение = часть.strip().partition("=")
                if имя == "orbita_session" and значение:
                    return значение
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    проект = sys.argv[1]
    база = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080/api"
    сид = json.loads(СИД.read_text(encoding="utf-8"))

    тело = {
        "phase": сид.get("phase", "Pre-Phase A"),
        "gate_dates": [{"gate": т["key"], "date": т["date"]} for т in сид.get("points", [])],
        # Сид зовёт сцену числом, продукт — ключом строкой: перевод здесь.
        "scene_windows": [
            {"scene": str(с["scene"]), "start": с["start"], "end": с["end"]}
            for с in сид.get("scenes", [])
            if с.get("start") and с.get("end")
        ],
        "author": "поставка (пример)",
    }

    заголовки = {"Content-Type": "application/json; charset=utf-8"}
    сессия = вход(база)
    if сессия:
        заголовки["Cookie"] = f"orbita_session={сессия}"
    req = urllib.request.Request(
        f"{база}/v2/plan?project={проект}",
        data=json.dumps(тело, ensure_ascii=False).encode(),
        headers=заголовки,
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        ответ = json.loads(r.read().decode())
    print(
        f"план работ фазы {проект}: точек {len(тело['gate_dates'])}, "
        f"окон сцен {len(тело['scene_windows'])} → {ответ}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
