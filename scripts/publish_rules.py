"""publish_rules.py — ONE-command publisher for the scraper-rules OTA payload.

The post-tester workflow, collapsed into one step (NO git, NO gradle):

    python scripts/publish_rules.py --rev 8 --flags-file flags.json \
        --out build/scraper_rules.json.enc

What it does, in order:
  1. Reads the AUTHORING scraper_rules.json (repo root by default — plaintext,
     gitignored, never pushed anywhere by this tool).
  2. OPTIONAL --rev N and/or --flags-file <json> are merged INTO MEMORY
     (the authoring file is only rewritten when --write-authoring is passed).
  3. Validates via encrypt_rules.validate_schema — ALL problems printed, any
     problem = loud failure, nothing emitted.
  4. Encrypts + signs by ROUTING THROUGH encrypt_rules.py's canonical builder
     (encrypt_rules.main()), so this tool can never drift from what CI and the
     app verify. Signing mode is chosen at runtime:
       - --root-key + --leaf-key  -> chain mode (root-pinned leaf, step 7:
         envelope gains "chain": {leafPubB64, rootSig, alg}) — used only when
         encrypt_rules.py actually grew that support (detected at runtime);
       - otherwise                -> legacy single-leaf ECDSA (envelope v2,
         default key lookup / --key), today's proven path.
  5. Self-checks before anything is written: byte round-trip AND
     encrypt_rules.verify_envelope against the FULL fleet root-pin set — an
     envelope the fleet would reject fails here, loudly.
  6. Writes the candidate envelope to --out (or the repo's real
     scraper_rules.json.enc ONLY with --write-repo) and prints sha256,
     byte size, version, rev.
  7. --sync-box pushes the built artifact to the OTA mirror box exactly as
     scripts/ota_mirror/DEPLOY.md documents (scp onto ota_files/, on-box sync
     to refresh latest.json, then sha256-verify FROM the box on 127.0.0.1:8000
     — the workstation cannot reach port 8000 directly).

--dry-run prints every step it WOULD take (including the exact scp/ssh lines)
while only performing read/merge/validate/encrypt-in-memory — no file is
written and no remote host is contacted.

Exit codes: 0 ok, 1 validation/crypto failure, 2 bad arguments/IO.
Never invokes git or any package/SCM tooling.
"""
import argparse
import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import encrypt_rules as er  # noqa: E402  (sibling module, repo scripts/)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUTHORING_DEFAULT = os.path.join(REPO, "scraper_rules.json")
REPO_ENC = os.path.join(REPO, "scraper_rules.json.enc")
# OTA mirror box — mirrors DEPLOY.md exactly (append-only target; 127.0.0.1
# verification happens ON the box because workstation egress to :8000 is blocked).
BOX_USER = "Anon"
BOX_HOST = "68.155.146.145"
BOX_KEY = os.path.join(os.path.expanduser("~"), ".ssh", "anon_vps")
BOX_ROOT = "/home/Anon/download-toolkit-server"
BOX_OTA_DIR = f"{BOX_ROOT}/ota_files"

# encrypt_rules module surface that step-7 (root+leaf chain) support implies.
CHAIN_SURFACE = ("CHAIN_ALG", "ROOT_PINS_B64", "pub_to_b64", "load_any_key")


def step(dry, text):
    print(f"[publish] {'would ' if dry else ''}{text}")


def chain_supported(er_mod) -> bool:
    return all(hasattr(er_mod, n) for n in CHAIN_SURFACE)


def build_envelope_via_encrypt_rules(er_mod, plain, key, root_key, leaf_key, no_sign):
    """In-process route through encrypt_rules.main() — THE canonical builder
    (legacy v2 and step-7 chain mode both live there, including its own
    round-trip + verify gates). A temp file carries the (possibly merged)
    payload so the authoring file is never touched by the builder."""
    tmpdir = tempfile.mkdtemp(prefix="publish-build-")
    tmp_in = os.path.join(tmpdir, "plain.json")
    tmp_out = os.path.join(tmpdir, "scraper_rules.json.enc")
    with open(tmp_in, "wb") as f:
        f.write(plain)
    argv = ["encrypt_rules.py", "--in", tmp_in, "--out", tmp_out]
    if no_sign:
        argv.append("--no-sign")
    elif root_key or leaf_key:
        argv += ["--root-key", root_key, "--leaf-key", leaf_key]
    elif key:
        argv += ["--key", key]
    old_argv = sys.argv
    sys.argv = argv
    try:
        er_mod.main()
    except SystemExit as e:
        shutil.rmtree(tmpdir, ignore_errors=True)
        if e.code not in (0, None):
            raise RuntimeError(f"encrypt_rules.main refused the build (exit {e.code})")
        raise
    finally:
        sys.argv = old_argv
    try:
        if not os.path.isfile(tmp_out):
            raise RuntimeError("encrypt_rules.main produced no envelope")
        with open(tmp_out, "rb") as f:
            env = json.loads(f.read().decode("utf-8"))
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
    if not isinstance(env, dict) or not isinstance(env.get("payload"), str):
        raise RuntimeError("encrypt_rules.main returned an unexpected envelope shape")
    return env


def self_check(er_mod, env, plain):
    """Mandatory gates before any write: byte round-trip + fleet-pin
    verification (chain-aware, full pinned-root set — a payload the phones
    would refuse must not survive this tool)."""
    rt = er_mod.decrypt_envelope_or_legacy(json.dumps(env, separators=(",", ":")))
    if rt != plain:
        raise RuntimeError("round-trip mismatch — REFUSING to emit")
    if env.get("sig"):
        try:
            er_mod.verify_envelope(json.dumps(env, separators=(",", ":")))
        except SystemExit as e:
            raise RuntimeError(f"fleet-pin verification FAILED ({e}) — envelope "
                               "would be rejected by every client")
        return "fleet-pin verified"
    return "no signature (--no-sign or no key)"


def box_cmdlines(out_path):
    enc_dst = f"{BOX_OTA_DIR}/scraper_rules.json.enc"
    scp = ("scp", "-i", BOX_KEY, out_path, f"{BOX_USER}@{BOX_HOST}:{enc_dst}")
    ssh_sync = ("ssh", "-i", BOX_KEY, f"{BOX_USER}@{BOX_HOST}",
                f"python3 {BOX_ROOT}/sync_ota_mirror.py --dir {BOX_OTA_DIR} "
                f"--file {BOX_ROOT}/ota_files/scraper_rules.json.enc")
    ssh_verify = ("ssh", "-i", BOX_KEY, f"{BOX_USER}@{BOX_HOST}",
                  "curl -s http://127.0.0.1:8000/ota/scraper_rules.json.enc | sha256sum")
    return scp, ssh_sync, ssh_verify


def sync_box(out_path):
    """Deploy steps of DEPLOY.md, verbatim in effect. Everything is verified
    from ON the box (workstation egress to :8000 is blocked)."""
    scp, ssh_sync, ssh_verify = box_cmdlines(out_path)
    print(f"[publish] scp {out_path} -> {BOX_USER}@{BOX_HOST}:{BOX_OTA_DIR}/")
    subprocess.run(scp, check=True)
    print("[publish] refreshing the box's served payloads + latest.json (on-box sync)")
    subprocess.run(ssh_sync, check=True)
    print("[publish] verifying served bytes from ON the box (127.0.0.1:8000)")
    r = subprocess.run(ssh_verify, capture_output=True, text=True, check=True)
    remote_sha = r.stdout.split()[0] if r.stdout.split() else "?"
    with open(out_path, "rb") as f:
        local_sha = hashlib.sha256(f.read()).hexdigest()
    print(f"[publish] local  sha256: {local_sha}")
    print(f"[publish] served sha256: {remote_sha}")
    if remote_sha != local_sha:
        print("[publish] MISMATCH — served file is NOT the built artifact", file=sys.stderr)
        return 1
    print("[publish] byte-identical on the box — publish complete")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Validate, encrypt, sign, (optionally) publish scraper_rules to the OTA mirror.")
    ap.add_argument("--in", dest="inp", default=AUTHORING_DEFAULT,
                    help=f"authoring payload (default: {AUTHORING_DEFAULT})")
    ap.add_argument("--rev", type=int, default=None,
                    help="integer rev to merge into the payload (in memory)")
    ap.add_argument("--flags-file", default=None,
                    help="JSON file whose top-level OBJECT is merged as 'flags'"
                         " (in memory); replaces any flags present in the authoring file")
    ap.add_argument("--write-authoring", action="store_true",
                    help="persist the merged payload back onto the authoring file"
                         " (only meaningful with --rev/--flags-file)")
    ap.add_argument("--out", default=None,
                    help="candidate envelope output path (nothing written if omitted)")
    ap.add_argument("--write-repo", action="store_true",
                    help="also write the repo's real scraper_rules.json.enc")
    ap.add_argument("--key", default=None, help="PEM private key for legacy single-key signing")
    ap.add_argument("--root-key", default=None,
                    help="ROOT private-key PEM for chain signing (with --leaf-key)")
    ap.add_argument("--leaf-key", default=None,
                    help="LEAF private-key PEM for chain signing (with --root-key)")
    ap.add_argument("--no-sign", action="store_true", help="emit unsigned v1 envelope")
    ap.add_argument("--sync-box", action="store_true",
                    help="after a successful local build, deploy to the OTA mirror box")
    ap.add_argument("--dry-run", action="store_true",
                    help="print every step that WOULD run; no writes, no remote contact")
    args = ap.parse_args()
    dry = args.dry_run

    # --- argument sanity -------------------------------------------------------
    if bool(args.root_key) != bool(args.leaf_key):
        print("[publish] FATAL: chain signing needs BOTH --root-key and "
              "--leaf-key (or neither for legacy)", file=sys.stderr)
        return 2
    if (args.root_key and args.no_sign) or (args.root_key and args.key):
        print("[publish] FATAL: chain mode signs with --root-key/--leaf-key only "
              "(no --no-sign, no --key)", file=sys.stderr)
        return 2
    chain_mode_requested = args.root_key is not None
    if chain_mode_requested and not chain_supported(er):
        print("[publish] FATAL: this encrypt_rules.py has no root+leaf chain "
              f"support (missing {', '.join(CHAIN_SURFACE)}); publish in legacy "
              "mode or land step 7 first", file=sys.stderr)
        return 2

    # --- 1. authoring payload ---------------------------------------------------
    if not os.path.isfile(args.inp):
        print(f"[publish] FATAL: authoring payload not found: {args.inp}", file=sys.stderr)
        return 2
    with open(args.inp, "rb") as f:
        raw = f.read()
    try:
        obj = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as e:
        print(f"[publish] FATAL: authoring payload is not UTF-8 JSON: {e}", file=sys.stderr)
        return 1
    step(dry, f"read authoring {args.inp} ({len(raw)} bytes)")
    merged = obj

    # --- 2. optional in-memory merge ---------------------------------------------
    if args.write_authoring and args.rev is None and args.flags_file is None:
        print("[publish] FATAL: --write-authoring without --rev/--flags-file "
              "would be a no-op; refusing", file=sys.stderr)
        return 2
    if args.rev is not None:
        if args.rev <= 0:
            print(f"[publish] FATAL: --rev must be a positive integer (got {args.rev!r})",
                  file=sys.stderr)
            return 2
        merged = copy.deepcopy(merged)
        merged["rev"] = args.rev
        print(f"[publish] merge payload['rev'] = {args.rev} (in memory)")
    if args.flags_file is not None:
        if not os.path.isfile(args.flags_file):
            print(f"[publish] FATAL: flags file not found: {args.flags_file}", file=sys.stderr)
            return 2
        try:
            with open(args.flags_file, "rb") as f:
                flags = json.loads(f.read().decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            print(f"[publish] FATAL: flags file is not JSON: {e}", file=sys.stderr)
            return 2
        if not isinstance(flags, dict):
            print("[publish] FATAL: flags file must contain a top-level JSON object",
                  file=sys.stderr)
            return 2
        merged = copy.deepcopy(merged)
        replaced = "flags" in merged
        merged["flags"] = flags
        print(f"[publish] merge payload['flags'] ({'replaces' if replaced else 'adds to'} "
              f"authoring, in memory) from {args.flags_file}")
    if args.write_authoring:
        if dry:
            step(True, f"write merged payload back onto {args.inp}")
        else:
            pretty = json.dumps(merged, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
            d = os.path.dirname(os.path.abspath(args.inp)) or "."
            fd, tmp = tempfile.mkstemp(prefix=".authoring-", dir=d)
            with os.fdopen(fd, "wb") as f:
                f.write(pretty)
            os.replace(tmp, args.inp)
            print(f"[publish] authoring file updated in place: {args.inp}")

    # --- 3. schema validation (all problems, loud) --------------------------------
    plain = raw if merged is obj else json.dumps(merged, ensure_ascii=False).encode("utf-8") + b"\n"
    print(f"[publish] validate_schema on {len(plain)} bytes of plaintext")
    problems = er.validate_schema(plain)
    if problems:
        print(f"[publish] SCHEMA VALIDATION FAILED — {len(problems)} problem(s):")
        for p in problems:
            print("  -", p)
        return 1
    print("[publish] validation: PASS (0 problems)")

    # --- 4. encrypt + sign via encrypt_rules.main() --------------------------------
    if dry:
        step(True, f"run encrypt_rules.main() ({'chain root+leaf' if chain_mode_requested else ('legacy single-key' if not args.no_sign else 'unsigned --no-sign')}), only reading/writing a build temp dir")
    else:
        build_mode = ("chain root+leaf" if chain_mode_requested
                      else "unsigned" if args.no_sign else "legacy single-key")
        print(f"[publish] building via encrypt_rules.main() ({build_mode})")
    try:
        env = build_envelope_via_encrypt_rules(er, plain, args.key,
                                               args.root_key, args.leaf_key,
                                               args.no_sign)
    except RuntimeError as e:
        print(f"[publish] FATAL: {e}", file=sys.stderr)
        return 1
    mode = "SIGNED" if env.get("sig") else "UNSIGNED"
    try:
        verdict = self_check(er, env, plain)
    except RuntimeError as e:
        print(f"[publish] FATAL: {e}", file=sys.stderr)
        return 1
    print(f"[publish] envelope: v{env['v']}, {mode}, {verdict}")

    out_text = json.dumps(env, separators=(",", ":"))
    out_bytes = out_text.encode("utf-8")
    sha = hashlib.sha256(out_bytes).hexdigest()
    plain_obj = json.loads(plain.decode("utf-8"))
    print(f"[publish] candidate: {len(out_bytes)} bytes | sha256 {sha}")
    print(f"[publish] content : version={plain_obj.get('version')!r} "
          f"rev={plain_obj.get('rev', '(none)')}")

    # --- 5. outputs -------------------------------------------------------------------
    outputs = []
    if args.out:
        outputs.append(args.out)
    if args.write_repo:
        outputs.append(REPO_ENC)
    for path in outputs:
        if dry:
            note = ""
            if os.path.exists(path):
                with open(path, "rb") as f:
                    note = f" — replaces existing sha256 {hashlib.sha256(f.read()).hexdigest()}"
            step(True, f"write {path} ({len(out_bytes)} bytes){note}")
        else:
            d = os.path.dirname(os.path.abspath(path)) or "."
            os.makedirs(d, exist_ok=True)
            fd, tmp = tempfile.mkstemp(prefix=".publish-", dir=d)
            with os.fdopen(fd, "wb") as f:
                f.write(out_bytes)
            os.replace(tmp, path)
            print(f"[publish] wrote {path} ({len(out_bytes)} bytes, sha256 {sha})")
    if not outputs:
        print("[publish] no --out / --write-repo: candidate NOT written "
              "(validation + build only)")
    if dry:
        print("[publish] DRY-RUN — every step above is what WOULD happen; no "
              "artifact written, no host contacted (sha is a this-run "
              "placeholder: random IV re-encrypts per run)")

    # --- 6. optional box sync ------------------------------------------------------------
    if args.sync_box:
        if not args.out:
            print("[publish] FATAL: --sync-box needs --out", file=sys.stderr)
            return 2
        if dry:
            for c in box_cmdlines(args.out):
                print("[publish] would run: " + subprocess.list2cmdline(c))
            print("[publish] would verify served sha256 == local candidate "
                  "sha256 from ON the box, then stop")
        else:
            return sync_box(args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
