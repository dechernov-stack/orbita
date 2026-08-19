#!/usr/bin/env python3
"""Исполняемый эталон ядра информационных потоков (шаг 4).

Проверяемые свойства выведены аналитически и не зависят от реализации:
  TZ-FLW-001  гибридное ядро: аналитика на ячейку + розыгрыш по представителям
  TZ-FLW-002  воспроизводимость при параллельности (счётчиковый ГПСЧ, ADR-014)
  TZ-FLW-003  пуассоновские потоки, суперпозиция, всплески событий
  TZ-FLW-004  предложенная и обслуженная нагрузка, лавина повторов
  TZ-FLW-005  эфемеридный backoff: надёжность за счёт хвоста задержки
  TZ-FLW-006  бюджет времени реакции класса C'
  TZ-FLW-007  узкие места
  TZ-FLW-008  деградированный режим по возрасту альманаха
"""
import math, sys, hashlib, statistics

# ---------- TZ-FLW-003: потоки ----------
def superpose(rates):
    """Суперпозиция независимых пуассоновских потоков — снова пуассоновский."""
    return sum(rates)

def poisson_pmf(k, lam):
    return math.exp(-lam) * lam**k / math.factorial(k)

def mmpp_rate(base_rate, burst_multiplier, in_burst):
    """Модулированный пуассоновский поток: фон и всплеск событий."""
    return base_rate * (burst_multiplier if in_burst else 1.0)

# ---------- TZ-FLW-004: коллизии и обратная связь нагрузки ----------
def aloha_throughput(G, capture=False):
    """Чистая ALOHA: S = G·e^(−2G). Захват сильного сигнала повышает пропускную способность."""
    S = G * math.exp(-2 * G)
    return S * 1.4 if capture else S

def carried_from_offered(offered, capacity):
    """Обслуженная нагрузка при заданной ёмкости канала."""
    G = offered / capacity if capacity > 0 else float('inf')
    return aloha_throughput(G) * capacity

def offered_with_retries(base_msgs, capacity, max_attempts=4):
    """Повторы — обратная связь: неудача порождает новую попытку.
    При падении ёмкости предложенная нагрузка растёт нелинейно."""
    offered, delivered = 0.0, 0.0
    pending = base_msgs
    for _ in range(max_attempts):
        if pending <= 0:
            break
        offered += pending
        got = min(pending, carried_from_offered(offered, capacity))
        delivered += got
        pending -= got
    return offered, delivered

# ---------- TZ-FLW-005: эфемеридный backoff ----------
def delivery_with_backoff(p_success, attempts_per_pass, passes):
    """Вероятность доставки за несколько пролётов с ожиданием между ними."""
    p_pass = 1 - (1 - p_success) ** attempts_per_pass
    return 1 - (1 - p_pass) ** passes

def latency_tail_s(pass_interval_s, passes_used):
    """Хвост задержки удлиняется на интервал до следующего аппарата."""
    return pass_interval_s * (passes_used - 1)

# ---------- TZ-FLW-006: бюджет времени реакции C' ----------
BUDGET_PARTS = ['detection', 'uplink_wait', 'uplink_transit',
                'external_decision', 'downlink_wait', 'downlink_transit', 'execution']

def reaction_time(parts):
    missing = [p for p in BUDGET_PARTS if p not in parts]
    if missing:
        raise ValueError(f'неполный бюджет: {missing}')
    return sum(parts[p] for p in BUDGET_PARTS)

def p_within(samples, required_s):
    return sum(1 for s in samples if s <= required_s) / len(samples)

# ---------- TZ-FLW-002: счётчиковый ГПСЧ ----------
def counter_rng(seed, run_index, entity_index, draw=0):
    """Поток адресуется ключом, а не последовательным состоянием: результат
    не зависит от порядка выполнения потоков (ADR-014)."""
    key = f'{seed}:{run_index}:{entity_index}:{draw}'.encode()
    h = hashlib.blake2b(key, digest_size=8).digest()
    return int.from_bytes(h, 'big') / 2**64

# ---------- TZ-FLW-001: выборка по представителям ----------
def weighted_estimate(reps):
    """Оценка по представителям с весами — несмещённая относительно полной популяции."""
    W = sum(r['weight'] for r in reps)
    return sum(r['weight'] * r['value'] for r in reps) / W if W else 0.0

def mc_stderr(values):
    return statistics.pstdev(values) / math.sqrt(len(values)) if len(values) > 1 else 0.0

# ---------- TZ-FLW-007 / 008 ----------
def bottleneck(loads):
    """Узкое место — участок с наибольшей загрузкой."""
    return max(loads.items(), key=lambda kv: kv[1])

def degraded_share(pass_interval_s, beacon_period_s, max_almanac_age_s):
    """Доля терминалов в деградированном режиме растёт с редкостью пролётов."""
    refresh = max(pass_interval_s, beacon_period_s)
    if refresh <= max_almanac_age_s:
        return 0.0
    return min(1.0, 1 - max_almanac_age_s / refresh)

# ================= проверки =================
def _run_checks():

    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("TZ-FLW-003: потоки")
    check("суперпозиция складывает интенсивности", superpose([1.5, 2.0, 0.5]) == 4.0)
    check("распределение нормировано",
          abs(sum(poisson_pmf(k, 3.0) for k in range(60)) - 1.0) < 1e-9)
    check("мода распределения около интенсивности",
          max(range(20), key=lambda k: poisson_pmf(k, 5.0)) in (4, 5))
    check("всплеск повышает интенсивность", mmpp_rate(10, 50, True) == 500)
    check("вне всплеска интенсивность фоновая", mmpp_rate(10, 50, False) == 10)

    print("\nTZ-FLW-004: коллизии и лавина повторов")
    peak = aloha_throughput(0.5)
    check("максимум чистой ALOHA ≈ 0.184 при нагрузке 0.5", abs(peak - 0.1839) < 0.001, f"{peak:.4f}")
    check("перегрузка снижает пропускную способность", aloha_throughput(2.0) < peak)
    check("максимум достигается именно при G=0.5",
          all(aloha_throughput(g) <= peak + 1e-12 for g in [0.1,0.3,0.7,1.0,1.5,3.0]))
    check("захват сильного сигнала повышает пропускную способность",
          aloha_throughput(0.5, capture=True) > peak)
    o_hi, d_hi = offered_with_retries(100, capacity=400)
    o_lo, d_lo = offered_with_retries(100, capacity=200)
    check("предложенная нагрузка больше обслуженной", o_hi > d_hi, f"{o_hi:.0f}/{d_hi:.0f}")
    check("падение ёмкости вдвое увеличивает долю повторов нелинейно",
          (o_lo / d_lo) > (o_hi / d_hi) * 1.2, f"{o_lo/d_lo:.2f} vs {o_hi/d_hi:.2f}")
    # Опасность механизма: до порога повторы компенсируют деградацию канала —
    # доставка не меняется, растут только повторы. За порогом происходит обвал.
    check("до порога повторы скрывают деградацию: доставка не падает",
          abs(d_lo - d_hi) < 1e-6, f"{d_hi:.1f} vs {d_lo:.1f}")
    o_cr, d_cr = offered_with_retries(100, capacity=100)
    check("за порогом доставка обваливается", d_cr < 0.5 * d_hi, f"{d_cr:.1f} из {d_hi:.1f}")
    check("обвал нелинеен: вдвое меньше ёмкости — впятеро больше повторов на доставленное",
          (o_cr / d_cr) > 5 * (o_lo / d_lo), f"{o_cr/d_cr:.1f} vs {o_lo/d_lo:.1f}")

    print("\nTZ-FLW-005: эфемеридный backoff")
    p1 = delivery_with_backoff(0.5, attempts_per_pass=4, passes=1)
    p3 = delivery_with_backoff(0.5, attempts_per_pass=4, passes=3)
    check("ожидание следующего аппарата повышает доставку", p3 > p1, f"{p1:.4f} → {p3:.4f}")
    check("хвост задержки удлиняется на интервал пролётов",
          latency_tail_s(600, 3) == 1200)
    check("один пролёт — хвоста нет", latency_tail_s(600, 1) == 0)
    check("больше КА в плоскости — короче хвост при той же надёжности",
          latency_tail_s(300, 3) < latency_tail_s(600, 3))

    print("\nTZ-FLW-006: бюджет времени реакции C'")
    parts = {'detection':2,'uplink_wait':45,'uplink_transit':1,'external_decision':20,
             'downlink_wait':30,'downlink_transit':1,'execution':3}
    check("бюджет складывается по всем участкам", reaction_time(parts) == 102)
    try:
        reaction_time({k:v for k,v in parts.items() if k != 'external_decision'})
        check("неполный бюджет отклонён", False)
    except ValueError:
        check("неполный бюджет отклонён", True)
    sf = dict(parts); sf['uplink_wait'] = 1800   # store-and-forward: ждём пролёта
    check("store-and-forward выводит контур за 120 с", reaction_time(sf) > 120, reaction_time(sf))
    check("ISL укладывает контур в 120 с", reaction_time(parts) <= 120)
    samples = [90, 100, 110, 130, 200]
    check("вероятность уложиться считается по выборке", abs(p_within(samples, 120) - 0.6) < 1e-9)
    check("время решения внешней системы входит в бюджет и не моделируется",
          'external_decision' in BUDGET_PARTS)

    print("\nTZ-FLW-002: воспроизводимость при параллельности")
    a = [counter_rng(42, r, e) for r in range(3) for e in range(4)]
    b = [counter_rng(42, r, e) for e in range(4) for r in range(3)]   # другой порядок обхода
    check("поток адресуется ключом, порядок обхода не влияет", sorted(a) == sorted(b))
    check("тот же ключ даёт то же число", counter_rng(42,1,2) == counter_rng(42,1,2))
    check("другое зерно даёт другой поток", counter_rng(43,1,2) != counter_rng(42,1,2))
    check("значения в допустимом диапазоне", all(0.0 <= x < 1.0 for x in a))
    check("разные сущности одной реализации независимы",
          len({counter_rng(42,0,e) for e in range(50)}) == 50)

    print("\nTZ-FLW-001: выборка по представителям")
    reps = [{'weight':1000,'value':0.9},{'weight':50,'value':0.4},{'weight':10,'value':0.1}]
    est = weighted_estimate(reps)
    check("оценка взвешена по численности, а не по числу представителей",
          abs(est - 0.8698) < 0.001, f"{est:.4f}")
    check("равные веса дают среднее",
          abs(weighted_estimate([{'weight':1,'value':0.2},{'weight':1,'value':0.8}]) - 0.5) < 1e-9)
    vals = [0.5 + 0.1*counter_rng(7,i,0) for i in range(1000)]
    se_100, se_1000 = mc_stderr(vals[:100]), mc_stderr(vals)
    check("погрешность убывает как 1/√N", se_1000 < se_100, f"{se_100:.5f} → {se_1000:.5f}")
    check("отношение погрешностей близко к √10",
          2.0 < se_100/se_1000 < 5.0, f"{se_100/se_1000:.2f}")

    print("\nTZ-FLW-007 / 008: узкие места и деградация")
    loads = {'user_uplink':0.42,'onboard_buffer':0.91,'isl':0.30,'feeder_downlink':0.55}
    name, val = bottleneck(loads)
    check("узкое место определено", name == 'onboard_buffer' and val == 0.91)
    check("нет деградации при частых пролётах",
          degraded_share(pass_interval_s=900, beacon_period_s=60, max_almanac_age_s=86400) == 0.0)
    d = degraded_share(pass_interval_s=200000, beacon_period_s=60, max_almanac_age_s=86400)
    check("редкие пролёты дают деградацию", 0 < d < 1, f"{d:.3f}")
    check("реже пролёты — больше деградация",
          degraded_share(400000, 60, 86400) > d)
    check("редкий маяк деградирует даже при частых пролётах",
          degraded_share(900, 200000, 86400) > 0)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
