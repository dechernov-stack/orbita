"""Контракт инфраструктуры Орбиты; выполняется без выката и доступа к сети."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DeployContract(unittest.TestCase):
    def test_owner_packets_stay_private(self):
        result = subprocess.run(['git', 'check-ignore', '--no-index', 'orbita/packets/private.json'],
                                cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn('orbita/', (ROOT / '.dockerignore').read_text().splitlines())
        self.assertIn('--exclude /orbita/', (ROOT / 'ops/build-and-ship.sh').read_text())

    def test_local_deploy_prepares_typst_before_build(self):
        script = (ROOT / 'ops/deploy-local.sh').read_text()
        self.assertLess(script.index('"$ROOT/ops/typst-fetch.sh"'), script.index('build_retry api'))
        self.assertLess(script.index('ops/.проход-владельца') if 'ops/.проход-владельца' in script
                        else script.index('.проход-владельца'), script.index('builder_hygiene\n'))
        self.assertIn('/api/auth/whoami', script)

    def test_shell_syntax(self):
        for name in ['deploy-local.sh', 'build-and-ship.sh', 'builder-hygiene.sh']:
            subprocess.run(['bash', '-n', str(ROOT / 'ops' / name)], check=True)


if __name__ == '__main__':
    unittest.main()
