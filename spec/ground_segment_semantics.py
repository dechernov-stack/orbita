#!/usr/bin/env python3
"""Исполняемый эталон наземного сегмента (шаг 12).

Размещение станций приёма: ручной режим и предложения от задач моделирования
(Концепция 5.4). Проверяются свойства, которые обязаны выполняться независимо
от алгоритма подбора.

  монотонность    добавление станции не ухудшает покрытие
  насыщение       вторая станция рядом с первой почти ничего не добавляет
  жадный выбор    берётся станция с наибольшим приростом
  широта          станция на высокой широте выгоднее для наклонных орбит
  происхождение   предложенное размещение помечается, ручное не переписывается
  задержка        больше станций — меньше время до сброса
"""
import math, sys

R = 6378.137

def visible_fraction(station_lat, inclination_deg, alt_km, min_elev_deg=5.0):
    """Доля витков, на которых станция видит аппарат (упрощённая модель:
    станция видна, если её широта попадает в полосу трассы плюс радиус зоны)."""
    lam = math.degrees(math.acos(R / (R + alt_km) * math.cos(math.radians(min_elev_deg)))) - min_elev_deg
    reach = abs(inclination_deg if inclination_deg <= 90 else 180 - inclination_deg) + lam
    if abs(station_lat) > reach:
        return 0.0
    # ближе к краю полосы — чаще пролёты (эффект сгущения трасс у широты наклонения)
    edge = abs(inclination_deg if inclination_deg <= 90 else 180 - inclination_deg)
    closeness = 1.0 - abs(abs(station_lat) - edge) / max(reach, 1e-9)
    return max(0.05, min(1.0, 0.3 + 0.7 * closeness))

def station_gain(existing, cand, inclination, alt, min_elev=5.0):
    """Прирост покрытия от станции с учётом перекрытия с уже размещёнными."""
    base = visible_fraction(cand['lat'], inclination, alt, min_elev)
    if not existing:
        return base
    overlap = max(_overlap(cand, e, alt) for e in existing)
    return base * (1.0 - overlap)

def _overlap(a, b, alt_km):
    """Перекрытие зон: 1 — совпадают, 0 — не пересекаются."""
    d = _arc_km(a, b)
    lam = math.degrees(math.acos(R / (R + alt_km) * math.cos(math.radians(5.0)))) - 5.0
    reach_km = R * math.radians(lam) * 2
    return max(0.0, 1.0 - d / reach_km) if reach_km > 0 else 0.0

def _arc_km(a, b):
    p1, p2 = math.radians(a['lat']), math.radians(b['lat'])
    dl = math.radians(b['lon'] - a['lon'])
    c = math.sin(p1) * math.sin(p2) + math.cos(p1) * math.cos(p2) * math.cos(dl)
    return R * math.acos(max(-1.0, min(1.0, c)))

def coverage(stations, inclination, alt, min_elev=5.0):
    """Суммарное покрытие набора станций с учётом перекрытий."""
    total, placed = 0.0, []
    for s in stations:
        total += station_gain(placed, s, inclination, alt, min_elev)
        placed.append(s)
    return min(1.0, total)

def suggest(candidates, inclination, alt, k, existing=None):
    """Жадный подбор: на каждом шаге станция с наибольшим приростом.
    Предложенные помечаются происхождением; ручные не переписываются."""
    placed = list(existing or [])
    manual = {(s['lat'], s['lon']) for s in placed}
    out = []
    pool = [c for c in candidates if (c['lat'], c['lon']) not in manual]
    for _ in range(k):
        if not pool:
            break
        best = max(pool, key=lambda c: station_gain(placed, c, inclination, alt))
        if station_gain(placed, best, inclination, alt) <= 1e-9:
            break
        chosen = {**best, 'placement': 'suggested'}
        placed.append(chosen); out.append(chosen); pool.remove(best)
    return out, placed

def mean_time_to_downlink_s(stations, inclination, alt, orbit_s=5736.0):
    cov = coverage(stations, inclination, alt)
    return orbit_s / max(cov, 1e-6) if cov > 0 else float('inf')

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    INC, ALT = 53.0, 550.0
    CAND = [{'id': 'GS-A', 'name': 'Мурманск',   'lat': 68.9,  'lon': 33.1},
            {'id': 'GS-B', 'name': 'Москва',     'lat': 55.7,  'lon': 37.6},
            {'id': 'GS-C', 'name': 'Химки',      'lat': 55.9,  'lon': 37.4},
            {'id': 'GS-D', 'name': 'Владивосток','lat': 43.1,  'lon': 131.9},
            {'id': 'GS-E', 'name': 'Кито',       'lat': -0.2,  'lon': -78.5}]
    B, C, D = CAND[1], CAND[2], CAND[3]

    print("Видимость и широта")
    check("станция в полосе наклонения видит аппарат", visible_fraction(53, INC, ALT) > 0)
    check("станция на полюсе наклонную орбиту не видит", visible_fraction(89, INC, ALT) == 0.0)
    check("широта у наклонения выгоднее экватора",
          visible_fraction(53, INC, ALT) > visible_fraction(0, INC, ALT))
    check("для полярной орбиты полюс доступен", visible_fraction(85, 97.6, ALT) > 0)
    check("выше орбита — шире полоса доступности",
          visible_fraction(70, INC, 1200) >= visible_fraction(70, INC, ALT))

    print("\nМонотонность и насыщение")
    c1 = coverage([B], INC, ALT)
    c2 = coverage([B, D], INC, ALT)
    check("добавление станции не ухудшает покрытие", c2 >= c1, f"{c1:.3f} → {c2:.3f}")
    c_near = coverage([B, C], INC, ALT)
    check("станция рядом с существующей добавляет мало",
          c_near - c1 < (c2 - c1) / 2, f"рядом +{c_near-c1:.4f}, далеко +{c2-c1:.4f}")
    check("совпадающая станция не добавляет ничего",
          abs(coverage([B, dict(B)], INC, ALT) - c1) < 1e-9)
    check("покрытие не превышает единицу", coverage(CAND * 3, INC, ALT) <= 1.0)
    check("перекрытие соседних зон близко к единице", _overlap(B, C, ALT) > 0.9)
    check("перекрытие удалённых зон нулевое", _overlap(B, D, ALT) == 0.0)

    print("\nЖадный подбор")
    sug, placed = suggest(CAND, INC, ALT, k=2)
    check("подобрано запрошенное число станций", len(sug) == 2)
    check("предложения помечены происхождением",
          all(s['placement'] == 'suggested' for s in sug))
    check("первым выбран наибольший прирост",
          station_gain([], sug[0], INC, ALT) >= station_gain([], sug[1], INC, ALT))
    check("подбор не дублирует близкие точки",
          _overlap(sug[0], sug[1], ALT) < 0.5, _overlap(sug[0], sug[1], ALT))
    sug2, placed2 = suggest(CAND, INC, ALT, k=2, existing=[dict(B, placement='manual')])
    check("ручная станция сохраняется в наборе",
          any(s.get('placement') == 'manual' for s in placed2))
    check("ручная станция не предлагается повторно",
          all((s['lat'], s['lon']) != (B['lat'], B['lon']) for s in sug2))
    check("подбор поверх ручной даёт покрытие не ниже, чем без неё",
          coverage(placed2, INC, ALT) >= coverage([B], INC, ALT))
    sug3, _ = suggest([B], INC, ALT, k=5)
    check("подбор не выдумывает станций сверх кандидатов", len(sug3) == 1)

    print("\nЗадержка до сброса")
    t1 = mean_time_to_downlink_s([B], INC, ALT)
    t2 = mean_time_to_downlink_s([B, D], INC, ALT)
    check("больше станций — меньше время до сброса", t2 < t1, f"{t1:.0f} → {t2:.0f} с")
    check("без станций сброс невозможен",
          mean_time_to_downlink_s([], INC, ALT) == float('inf'))
    check("станция вне полосы не сокращает задержку",
          mean_time_to_downlink_s([{'lat': 89, 'lon': 0}], INC, ALT) == float('inf'))

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
