"""Verify a scoped Whitecat migration through Android DocumentsUI on two paired emulators."""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import uuid

ROOT = Path(__file__).resolve().parent
PACKAGE = "dev.whitecat.desk"
FIELDS = ("history_sha256", "draft_sha256", "turns", "revision", "cursor", "index_sha256")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True, help="Existing verify_workspace.py create fixture JSON")
    parser.add_argument("--sender", default="127.0.0.1:5581")
    parser.add_argument("--receiver", default="127.0.0.1:5583")
    parser.add_argument("--adb", default=str(Path(os.getenv("LOCALAPPDATA", "")) / "WhitecatAndroidTools/adb/platform-tools/adb.exe"))
    parser.add_argument("--picker", choices=("documentsui", "content-uri"), default="documentsui", help="How to supply the selected image to the real scanner")
    parser.add_argument("--report", type=Path, default=ROOT / "migration-devices-acceptance.json")
    args = parser.parse_args()
    fixture = json.loads(args.run.read_text(encoding="utf8"))
    chat = str(uuid.UUID(fixture["chat"]))
    if args.sender == args.receiver or not (fixture["draft"] and fixture["question"]):
        raise ValueError("Two distinct paired emulators and an existing fixture are required")
    filename = "wcqr-" + uuid.uuid4().hex[:12] + ".png"

    def adb(serial, *cmd, data=None):
        result = subprocess.run([args.adb, "-s", serial, *cmd], input=data, capture_output=True, timeout=150)
        if result.returncode:
            raise RuntimeError(f"ADB failed on {serial} during {cmd[0]} (status {result.returncode})")
        return result.stdout

    def metadata(serial):
        def shell(*cmd):
            return adb(serial, "shell", *cmd).decode("utf8").strip()
        if shell("getprop", "ro.kernel.qemu") != "1":
            raise RuntimeError("Expected Android emulator")
        info = shell("dumpsys", "package", PACKAGE)
        version = re.search(r"^\s*versionName=([^\s]+)", info, re.M)
        if not version:
            raise RuntimeError("Whitecat APK unavailable")
        return {"serial": serial, "android": shell("getprop", "ro.build.version.release"),
                "sdk": int(shell("getprop", "ro.build.version.sdk")), "app_version": version[1]}

    def stage(serial, name, expected=None):
        values = {"stage": name, "chat": chat, "draft": fixture["draft"], "question": fixture["question"], "file": filename, "picker": args.picker}
        values.update(expected or {})
        command = ["am", "instrument", "-w"]
        for key, value in values.items():
            command.extend(["-e", key, value])
        command.append("dev.whitecat.check/.DeviceCheck")
        lines = adb(serial, "shell", shlex.join(command)).decode("utf8", "replace").splitlines()
        if f"INSTRUMENTATION_RESULT: whitecat=PASS {name}" not in lines:
            # Only controlled test assertions are usable as diagnostics; never log a scanner payload.
            reason = next((line.partition("FAIL AssertionError ")[2] for line in lines
                           if line.startswith("INSTRUMENTATION_RESULT: whitecat=FAIL AssertionError ")), "")
            if reason.startswith("Timed out waiting for "):
                reason = reason.partition(" / ")[0]
            if not re.fullmatch(r"[\w .，/\-]{1,140}", reason) or "whitecat:migrate" in reason:
                marker = next((line.partition("whitecat=FAIL ")[2].split(" ", 1)[0] for line in lines
                               if line.startswith("INSTRUMENTATION_RESULT: whitecat=FAIL ")), "process")
                reason = "Instrumentation did not complete (" + (marker if re.fullmatch("[A-Za-z]+", marker) else "process") + ")"
            raise AssertionError(name + ": " + reason)
        results = {}
        for key in FIELDS:
            matches = [line.partition("=")[2] for line in lines if line.startswith("INSTRUMENTATION_RESULT: " + key + "=")]
            if matches:
                value = matches[0]
                pattern = r"[0-9a-f]{64}" if key.endswith("sha256") else r"[0-9]+"
                if not re.fullmatch(pattern, value):
                    raise AssertionError("Malformed instrumentation metric " + key)
                results[key] = value if key.endswith("sha256") else int(value)
        if name == "migration_import":
            for key in ("first_picker", "repeat_picker"):
                selected = next((line.partition("=")[2] for line in lines if line.startswith("INSTRUMENTATION_RESULT: " + key + "=")), "")
                if selected not in {"DocumentsUI", "Instrumentation ACTION_OPEN_DOCUMENT result with owned MediaStore content URI"}:
                    raise AssertionError("Missing picker evidence")
                results[key] = selected
        print("PASS", name, flush=True)
        return results

    sender, receiver = metadata(args.sender), metadata(args.receiver)
    if sender["app_version"] != receiver["app_version"] or min(sender["sdk"], receiver["sdk"]) < 30:
        raise RuntimeError("This system-picker check requires matching Whitecat versions and Android 11 or newer")
    def through_adb(serial, stage_name, expected=None, png=None):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(.25)
            device_port = adb(serial, "reverse", "tcp:0", f"tcp:{listener.getsockname()[1]}").decode().strip()
            if not device_port.isdecimal():
                raise RuntimeError("ADB failed to allocate a QR transfer port")
            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                    options = {**(expected or {}), "port": device_port}
                    result = executor.submit(stage, serial, stage_name, options)
                    while not result.done():
                        try:
                            connection, _ = listener.accept()
                        except socket.timeout:
                            continue
                        with connection, connection.makefile("rb") as stream:
                            connection.settimeout(8)
                            if stream.readline(64) != (filename + "\n").encode("ascii"):
                                continue
                            if png is None:
                                count = int.from_bytes(stream.read(4), "big")
                                if not 128 <= count <= 256000:
                                    raise RuntimeError("Invalid QR image length")
                                png = stream.read(count)
                                if len(png) != count or not png.startswith(b"\x89PNG\r\n\x1a\n"):
                                    raise RuntimeError("Invalid QR image")
                                connection.sendall(b"\x01")
                            else:
                                connection.sendall(len(png).to_bytes(4, "big") + png)
                                if stream.read(1) != b"\x01":
                                    raise RuntimeError("Receiver did not acknowledge QR pixels")
                            break
                    return result.result(), png
            finally:
                adb(serial, "reverse", "--remove", "tcp:" + device_port)

    # Only the receiver's test-owned Downloads row reaches persistent storage.
    exported, pixels = through_adb(args.sender, "migration_export")
    if pixels is None:
        raise RuntimeError("Missing sender QR pixels")
    expected = {k: exported[k] for k in ("history_sha256", "draft_sha256")}
    imported, _ = through_adb(args.receiver, "migration_import", expected, pixels)
    del pixels
    if not all(imported[k] == exported[k] for k in ("history_sha256", "draft_sha256", "turns", "revision", "cursor")):
        raise AssertionError("Imported history or draft differs")
    report = {
        "verified_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "sender": sender, "receiver": receiver, "chat": chat, "export": exported, "import": imported,
        "scope": {"current_chat_only": True, "history": True, "drafts": True, "settings": False},
        "scan_path": "Sender QR ImageView PNG -> ADB in memory -> receiver MediaStore Downloads -> picker paths recorded in import.first_picker and import.repeat_picker -> MigrationScanner image decoder (ZXing), twice",
        "checks": {"cache_and_draft_absent_before_confirmation": True, "preview_one_index_one_history_one_draft": True,
                   "local_history_and_draft_match_before_opening_chat": True, "receiver_pairing_preserved": True,
                   "workstation_acknowledged": True, "same_qr_reported_already_imported": True,
                   "repeat_preserved_index_history_and_draft": True, "temporary_qr_copies_removed": True},
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    print("PASS two-device QR migration", chat)


if __name__ == "__main__":
    main()
