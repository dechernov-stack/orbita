"""Сессия инструментов на стенде — одна дорога для прогона, загрузчика полок и
проверок (ADR-065 · ADR-066).

Режим stand: учётка стенда без пароля (`/auth/stand-login`), как раньше.
Режим telegram (стенд 216 за входом целиком): фиктивных учёток нет — инструмент
входит через бота: печатает ссылку, ждёт подтверждения владельца в Telegram,
сессию сохраняет в `~/.config/orbita/session-<хост>` и в следующий раз
переиспользует (срок сессии 30 дней). Смена «учётки» (chernov · ivanov ·
petrova) в этом режиме — «выступить от имени роли» одной учёткой владельца:
РП · ведущий СИ · инженер, журнал пишет «Чернов Д. как инженер».

Переменные: ORBITA_SESSION — готовый токен (вместо файла); ORBITA_LOGIN_WAIT —
сколько секунд ждать подтверждения (по умолчанию 600).
"""
from __future__ import annotations

import http.cookiejar
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

КУКИ = "orbita_session"
# учётка стенда → роль «от имени» на стенде с одной учёткой владельца
РОЛИ = {"chernov": None, "ivanov": "lead_se", "petrova": "specialist"}


def _файл_сессии(base: str) -> pathlib.Path:
    хост = urllib.parse.urlparse(base).netloc.replace(":", "_") or "local"
    return pathlib.Path.home() / ".config" / "orbita" / f"session-{хост}"


def _вызов(opener, base: str, метод: str, путь: str, тело=None):
    данные = json.dumps(тело, ensure_ascii=False).encode() if тело is not None else None
    з = urllib.request.Request(base + путь, data=данные, headers={"Content-Type": "application/json; charset=utf-8"}, method=метод)
    with opener.open(з, timeout=60) as о:
        сырое = о.read().decode()
        return json.loads(сырое) if сырое else {}


def _jar(opener) -> http.cookiejar.CookieJar | None:
    for h in opener.handlers:
        if isinstance(h, urllib.request.HTTPCookieProcessor):
            return h.cookiejar
    return None


def _положить_куки(opener, base: str, токен: str) -> None:
    jar = _jar(opener)
    if jar is None:
        return
    хост = urllib.parse.urlparse(base).hostname or "localhost"
    jar.set_cookie(http.cookiejar.Cookie(
        version=0, name=КУКИ, value=токен, port=None, port_specified=False,
        domain=хост, domain_specified=False, domain_initial_dot=False,
        path="/", path_specified=True, secure=False, expires=None, discard=True,
        comment=None, comment_url=None, rest={}, rfc2109=False,
    ))


def _токен_из_jar(opener) -> str | None:
    jar = _jar(opener)
    if jar is None:
        return None
    for c in jar:
        if c.name == КУКИ:
            return c.value
    return None


def _кто(opener, base: str) -> dict:
    try:
        return _вызов(opener, base, "GET", "/auth/whoami")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"GET /auth/whoami → {e.code}: {e.read().decode()[:200]}") from None


def войти(base: str, opener, учётка: str) -> str:
    """Вход учёткой стенда либо через Telegram с ролью «от имени». Возвращает режим."""
    кто = _кто(opener, base)
    if кто.get("mode") == "stand":
        _вызов(opener, base, "POST", "/auth/stand-login", {"login": учётка})
        return "stand"
    if кто.get("mode") != "telegram":
        return "open" if not кто.get("enabled") else "unknown"
    if not кто.get("user"):
        _сессия_telegram(base, opener)
        кто = _кто(opener, base)
    роль = РОЛИ.get(учётка, учётка)
    пользователь = кто.get("user") or {}
    if пользователь.get("acting_role") != роль:
        if not пользователь.get("can_act_as"):
            raise SystemExit(f"учётка {пользователь.get('login')} не владелец системы: выступить как {учётка!r} нельзя (ORBITA_ADMIN_TIDS)")
        try:
            _вызов(opener, base, "POST", "/auth/act-as", {"role": роль})
        except urllib.error.HTTPError as e:
            raise SystemExit(f"POST /auth/act-as → {e.code}: {e.read().decode()[:200]}") from None
    return "telegram"


def _сессия_telegram(base: str, opener) -> None:
    файл = _файл_сессии(base)
    for токен in [os.environ.get("ORBITA_SESSION"), файл.read_text().strip() if файл.is_file() else None]:
        if not токен:
            continue
        _положить_куки(opener, base, токен)
        if _кто(opener, base).get("user"):
            return
    try:
        начало = _вызов(opener, base, "POST", "/auth/start", {})
    except urllib.error.HTTPError as e:
        raise SystemExit(f"POST /auth/start → {e.code}: {e.read().decode()[:200]}") from None
    print(f"вход через Telegram: откройте ссылку и подтвердите в боте —\n  {начало.get('deep_link')}", file=sys.stderr, flush=True)
    ждать = int(os.environ.get("ORBITA_LOGIN_WAIT", "600"))
    срок = time.monotonic() + ждать
    while time.monotonic() < срок:
        статус = _вызов(opener, base, "GET", "/auth/status?token=" + urllib.parse.quote(начало.get("token", "")))
        if статус.get("status") == "approved":
            токен = _токен_из_jar(opener)
            if токен:
                файл.parent.mkdir(parents=True, exist_ok=True)
                файл.write_text(токен)
                файл.chmod(0o600)
            print(f"вошли через Telegram: {статус.get('display_name') or статус.get('login')}", file=sys.stderr)
            return
        if статус.get("status") in ("denied", "expired"):
            raise SystemExit(f"вход через Telegram: {статус.get('status')}")
        time.sleep(3)
    raise SystemExit(f"вход через Telegram не подтверждён за {ждать} с")


def токен(base: str, opener) -> str | None:
    """Токен текущей сессии — для инструментов, что ходят заголовком Bearer."""
    return _токен_из_jar(opener)
