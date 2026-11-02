#!/usr/bin/env python3
"""make_tls.py - the HTTPS certificate for the gateway's Ingress (Workstream E.4).

Makes two things in demo/tls/ (gitignored -- private keys never go in git):

  ca.crt / ca.key          "ScamGuard demo CA". Made ONCE and then reused, so a phone only has to trust
                           it once. Install ca.crt on the phone/emulator (see demo/STABLE_ADDRESS.md).
  server.crt / server.key  The certificate the Ingress shows, signed by that CA. Valid for:
                             scamguard.test, localhost, 127.0.0.1, 10.0.2.2 (the emulator's name for the
                             laptop) and every private IPv4 address this laptop has right now.
                           Re-issued automatically when the laptop's IP changes (new Wi-Fi network) --
                           the CA stays the same, so the phone doesn't need to trust anything new.

With --apply it also loads the certificate into the cluster as the TLS Secret the Ingress uses
(`gateway-tls` in the scamguard namespace). Safe to run again and again: it does nothing new if the
certificate already covers this laptop's addresses.

Usage:
    python demo/make_tls.py                 # just make/refresh the files
    python demo/make_tls.py --apply         # ... and update the Secret in the cluster
    python demo/make_tls.py --ip 1.2.3.4    # also cover an extra address (e.g. a cloud VM's static IP)

Needs `openssl` (Git for Windows ships one; macOS/Linux have it). If none is found it runs openssl inside
Docker instead (the demo needs Docker anyway).
"""
import argparse
import ipaddress
import os
import shutil
import socket
import subprocess
import sys
from pathlib import Path

TLS_DIR = Path(__file__).resolve().parent / "tls"
NAMESPACE = "scamguard"
SECRET_NAME = "gateway-tls"
HOSTNAME = "scamguard.test"   # .test is reserved for testing (RFC 2606), so it can never clash with a real site
FIXED_DNS = [HOSTNAME, "localhost"]
FIXED_IPS = ["127.0.0.1", "10.0.2.2"]
CA_DAYS = 1825      # 5 years: trust it on the phone once and forget about it
SERVER_DAYS = 397   # the longest any browser/OS accepts for a server certificate


def find_openssl() -> list:
    """Returns the command prefix for running openssl, preferring a local binary."""
    exe = shutil.which("openssl")
    if not exe and os.name == "nt":
        for p in (r"C:\Program Files\Git\usr\bin\openssl.exe", r"C:\Program Files\Git\mingw64\bin\openssl.exe"):
            if Path(p).exists():
                exe = p
                break
    if exe:
        return [exe]
    if shutil.which("docker"):
        print("(no local openssl -- running it in Docker instead)")
        return ["docker", "run", "--rm", "-v", f"{TLS_DIR}:/w", "-w", "/w", "alpine/openssl"]
    sys.exit("error: need openssl (or Docker). On Windows, Git for Windows includes one.")


def local_ipv4s() -> list:
    """Every private IPv4 this machine has (Wi-Fi, Ethernet, hotspot). Extra ones are harmless."""
    found = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    try:   # the address of the interface that has the default route; sends nothing
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))
        found.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    keep = []
    for ip in found:
        a = ipaddress.ip_address(ip)
        if a.is_private and not a.is_loopback and not a.is_link_local:
            keep.append(ip)
    return sorted(keep, key=lambda x: tuple(int(p) for p in x.split(".")))


def run(openssl: list, *args: str) -> None:
    # Paths are passed relative to TLS_DIR so the same arguments work for local openssl and the Docker one.
    r = subprocess.run([*openssl, *args], cwd=TLS_DIR, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"openssl {args[0]} failed:\n{r.stderr}")


def make_ca(openssl: list) -> None:
    (TLS_DIR / "ca.ext").write_text(
        "[req]\ndistinguished_name=dn\n[dn]\n"
        "[v3_ca]\nbasicConstraints=critical,CA:TRUE,pathlen:0\n"
        "keyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n",
        encoding="ascii")
    run(openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
        "-keyout", "ca.key", "-out", "ca.crt", "-days", str(CA_DAYS),
        "-subj", "/CN=ScamGuard demo CA/O=CSC3104", "-config", "ca.ext", "-extensions", "v3_ca")
    print(f"made a new CA: {TLS_DIR / 'ca.crt'}  <- install this on the phone/emulator once")


def make_server(openssl: list, sans: list) -> None:
    alt = ",".join(sans)
    (TLS_DIR / "server.ext").write_text(
        "basicConstraints=critical,CA:FALSE\n"
        "keyUsage=critical,digitalSignature,keyEncipherment\n"
        "extendedKeyUsage=serverAuth\n"
        f"subjectAltName={alt}\n",
        encoding="ascii")
    run(openssl, "req", "-newkey", "rsa:2048", "-nodes", "-sha256",
        "-keyout", "server.key", "-out", "server.csr", "-subj", f"/CN={HOSTNAME}")
    run(openssl, "x509", "-req", "-in", "server.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
        "-CAcreateserial", "-out", "server.crt", "-days", str(SERVER_DAYS), "-sha256", "-extfile", "server.ext")
    (TLS_DIR / "server.csr").unlink(missing_ok=True)
    (TLS_DIR / "sans.txt").write_text(alt + "\n", encoding="ascii")
    print(f"made a new server certificate for: {alt}")


def apply_secret() -> None:
    if not shutil.which("kubectl"):
        sys.exit("error: kubectl not found")
    # `apply`, not `create`: up.sh/up.ps1 apply 00-namespace.yaml afterwards, and a namespace made by
    # `create` makes that print a "missing last-applied-configuration" warning.
    ns = f"apiVersion: v1\nkind: Namespace\nmetadata:\n  name: {NAMESPACE}\n"
    subprocess.run(["kubectl", "apply", "-f", "-"], input=ns, check=True, text=True, capture_output=True)
    rendered = subprocess.run(
        ["kubectl", "create", "secret", "tls", SECRET_NAME, "-n", NAMESPACE,
         f"--cert={TLS_DIR / 'server.crt'}", f"--key={TLS_DIR / 'server.key'}",
         "--dry-run=client", "-o", "yaml"],
        check=True, capture_output=True, text=True).stdout
    subprocess.run(["kubectl", "apply", "-f", "-"], input=rendered, check=True, text=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--apply", action="store_true", help=f"also write the {SECRET_NAME} Secret into the cluster")
    ap.add_argument("--ip", action="append", default=[], help="extra IP address to cover (repeatable)")
    ap.add_argument("--dns", action="append", default=[], help="extra DNS name to cover (repeatable)")
    args = ap.parse_args()

    TLS_DIR.mkdir(exist_ok=True)
    openssl = find_openssl()

    if not ((TLS_DIR / "ca.crt").exists() and (TLS_DIR / "ca.key").exists()):
        make_ca(openssl)

    ips = sorted(set(FIXED_IPS + local_ipv4s() + args.ip), key=lambda x: tuple(int(p) for p in x.split(".")))
    sans = [f"DNS:{d}" for d in FIXED_DNS + args.dns] + [f"IP:{ip}" for ip in ips]
    wanted = ",".join(sans)
    have = (TLS_DIR / "sans.txt").read_text(encoding="ascii").strip() if (TLS_DIR / "sans.txt").exists() else ""

    if wanted != have or not (TLS_DIR / "server.crt").exists():
        make_server(openssl, sans)
    else:
        print(f"server certificate already covers: {wanted}")

    if args.apply:
        apply_secret()
    return 0


if __name__ == "__main__":
    sys.exit(main())
