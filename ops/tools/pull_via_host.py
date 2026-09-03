#!/usr/bin/env python3
"""Обход: демон Docker Desktop не достаёт реестр (внутренний прокси
http.docker.internal:3128 висит), а хост — достаёт. Образ Docker Hub
скачивается с хоста по HTTPS в OCI-layout и грузится `docker load`; затем
сборка идёт сборщиком docker-driver, который видит локальные образы:
    BUILDER=desktop-linux bash ops/deploy-local.sh api web
Использование: python3 ops/tools/pull_via_host.py <name:tag> <arch> [...]
Пример (стенд Орбиты на Apple Silicon):
    python3 ops/tools/pull_via_host.py eclipse-temurin:21-jre arm64 node:22-slim arm64 nginx:1.27-alpine arm64
Проверка целостности — по sha256 каждого слоя. Секретов и записи в реестр нет.
"""
import hashlib, io, json, os, subprocess, sys, tarfile, urllib.request

REG = "https://registry-1.docker.io"

def get(url, headers=None, raw=False):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
        return data if raw else json.loads(data)

def token(repo):
    return get(f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull")["token"]

ACCEPT = ", ".join([
    "application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json",
])

def pull(ref, arch):
    name, tag = ref.split(":", 1)
    repo = f"library/{name}" if "/" not in name else name
    tok = token(repo)
    h = {"Authorization": f"Bearer {tok}", "Accept": ACCEPT}
    top = get(f"{REG}/v2/{repo}/manifests/{tag}", h)
    if "manifests" in top:
        cands = [m for m in top["manifests"] if m.get("platform", {}).get("os") == "linux" and m["platform"].get("architecture") == arch]
        if arch == "arm64":
            cands = [m for m in cands if m["platform"].get("variant") in (None, "v8")] or cands
        if not cands:
            raise SystemExit(f"{ref}: нет варианта linux/{arch}")
        digest = cands[0]["digest"]
        manifest_bytes = get(f"{REG}/v2/{repo}/manifests/{digest}", h, raw=True)
    else:
        manifest_bytes = json.dumps(top).encode()
    manifest = json.loads(manifest_bytes)
    mdigest = "sha256:" + hashlib.sha256(manifest_bytes).hexdigest()
    blobs = {}
    def fetch_blob(d):
        data = get(f"{REG}/v2/{repo}/blobs/{d}", {"Authorization": f"Bearer {tok}"}, raw=True)
        assert "sha256:" + hashlib.sha256(data).hexdigest() == d, f"{ref}: слой {d} повреждён"
        blobs[d] = data
    fetch_blob(manifest["config"]["digest"])
    for layer in manifest["layers"]:
        fetch_blob(layer["digest"])
    index = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json",
             "manifests": [{"mediaType": manifest.get("mediaType", "application/vnd.oci.image.manifest.v1+json"),
                            "digest": mdigest, "size": len(manifest_bytes),
                            "platform": {"os": "linux", "architecture": arch},
                            "annotations": {"org.opencontainers.image.ref.name": f"docker.io/{repo}:{tag}"}}]}
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        def add(path, data):
            ti = tarfile.TarInfo(path); ti.size = len(data); tar.addfile(ti, io.BytesIO(data))
        add("oci-layout", b'{"imageLayoutVersion": "1.0.0"}')
        add("index.json", json.dumps(index).encode())
        add(f"blobs/sha256/{mdigest.split(':')[1]}", manifest_bytes)
        for d, data in blobs.items():
            add(f"blobs/sha256/{d.split(':')[1]}", data)
    buf.seek(0)
    res = subprocess.run(["docker", "load"], input=buf.getvalue(), capture_output=True)
    out = (res.stdout + res.stderr).decode(errors="replace").strip()
    print(f"{ref} [{arch}] слоёв {len(manifest['layers'])}, {sum(len(b) for b in blobs.values())//1_000_000} МБ → {out[-160:]}")
    if res.returncode != 0:
        raise SystemExit(res.returncode)
    # тег без docker.io/library/ префикса, если load дал полный
    subprocess.run(["docker", "tag", f"docker.io/{repo}:{tag}", ref], capture_output=True)

if __name__ == "__main__":
    args = sys.argv[1:]
    for i in range(0, len(args), 2):
        pull(args[i], args[i + 1])
