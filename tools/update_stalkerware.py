#!/usr/bin/env python3
"""Aggiorna app/src/main/assets/stalkerware.txt dall'elenco pubblico di Echap.

Fonte: https://github.com/AssoEchap/stalkerware-indicators (licenza CC-BY 4.0).

Uso:
    python3 tools/update_stalkerware.py            # scarica l'ultima versione
    python3 tools/update_stalkerware.py --file ioc.yaml   # usa un file locale

Misure di sicurezza: legge solo dati (nessun codice viene eseguito), accetta solo
nomi di pacchetto e impronte SHA-1 con formato valido e, se l'elenco ricevuto
sembra anomalo (troppo piccolo), lascia intatta la copia esistente.
"""
import datetime
import re
import subprocess
import sys
import urllib.request

URL = "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"
OUT = "app/src/main/assets/stalkerware.txt"
MIN_PACKAGES = 300
MIN_CERTS = 200

PKG_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,200}$")
CERT_RE = re.compile(r"^[0-9A-F]{40}$")


def load_yaml():
    try:
        import yaml
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "--quiet", "pyyaml"])
        import yaml
    if len(sys.argv) >= 3 and sys.argv[1] == "--file":
        with open(sys.argv[2], encoding="utf-8") as f:
            return yaml.safe_load(f)
    with urllib.request.urlopen(URL, timeout=60) as r:
        return yaml.safe_load(r.read().decode("utf-8"))


def main():
    data = load_yaml()
    packages, certs = set(), set()
    for entry in data:
        for p in entry.get("packages") or []:
            p = str(p).strip()
            if PKG_RE.match(p):
                packages.add(p)
        for c in entry.get("certificates") or []:
            c = str(c).strip().upper()
            if CERT_RE.match(c):
                certs.add(c)

    if len(packages) < MIN_PACKAGES or len(certs) < MIN_CERTS:
        print("Elenco ricevuto troppo piccolo (%d pacchetti, %d firme): mantengo quello esistente."
              % (len(packages), len(certs)))
        return 0

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Fonte: AssoEchap/stalkerware-indicators (ioc.yaml), CC-BY 4.0\n")
        f.write("# Aggiornato il %s\n" % datetime.date.today().isoformat())
        for p in sorted(packages):
            f.write("P:%s\n" % p)
        for c in sorted(certs):
            f.write("C:%s\n" % c)
    print("Aggiornato: %d pacchetti, %d firme." % (len(packages), len(certs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
