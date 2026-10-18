#!/usr/bin/env python3
"""Run host-JVM packet/state tests with installed Python/javac/java only.

No Gradle, Android runtime, SDK, downloaded dependencies, or native library is
needed. MoonBridge and its plain-Java interfaces are compiled from real sources;
its primitive constants are inlined, so its JNI initializer is never executed.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
NATIVE = ROOT / "app/src/main/jni/moonlight-core/moonlight-common-c/src"


def check_protocol_and_resources():
    bridge = (JAVA / "com/limelight/nvstream/jni/MoonBridge.java").read_text()
    header = (NATIVE / "Limelight.h").read_text()
    names = re.findall(r"#define (LI_TOUCH_EVENT_\w+|LI_ROT_UNKNOWN)\s+(0x[0-9a-fA-F]+)", header)
    assert names, "Initialize native submodules before running tests."
    for name, value in names:
        match = re.search(rf"\b{name}\s*=\s*(?:\(\w+\))?(0x[0-9a-fA-F]+)", bridge)
        assert match and int(match[1], 16) == int(value, 16), f"Java/native constant mismatch: {name}"
    assert re.search(r"uint32_t\s+pointerId", (NATIVE / "Input.h").read_text())
    prefs = ET.parse(ROOT / "app/src/main/res/xml/preferences.xml")
    android = "{http://schemas.android.com/apk/res/android}"
    preference = next(p for p in prefs.iter("CheckBoxPreference")
                      if p.get(android + "key") == "checkbox_native_touchscreen_passthrough")
    assert preference.get(android + "defaultValue") == "false", "Must be opt-in."
    strings = {item.get("name") for item in ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()}
    for attribute in ("title", "summary"):
        assert preference.get(android + attribute).removeprefix("@string/") in strings
    print("PASS real Java/native protocol constants and 32-bit pointer field")
    print("PASS preference XML, strings, and default OFF", flush=True)


def main():
    check_protocol_and_resources()
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("Installed javac and java are required; this script never downloads tools.")
    work = (ROOT / "build/native-touch-tests").resolve()
    if work.is_relative_to(Path("/tmp")):
        raise SystemExit("Test output must not use /tmp.")
    mounts = []
    for line in Path("/proc/self/mountinfo").read_text().splitlines():
        location, filesystem = line.split(" - ", 1)
        mount = Path(re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), location.split()[4]))
        if work.is_relative_to(mount):
            mounts.append((mount, filesystem.split()))
    if not mounts:
        raise SystemExit("Cannot determine the test output filesystem.")
    _, (filesystem, source, *_) = max(mounts, key=lambda item: len(item[0].parts))
    if filesystem in {"tmpfs", "ramfs", "rootfs"} or source.startswith(("/dev/ram", "/dev/zram")):
        raise SystemExit("Test output must use a disk-backed filesystem.")
    classes, temporary = work / "classes", work / "tmp"
    classes.mkdir(parents=True, exist_ok=True)
    temporary.mkdir(exist_ok=True)
    env = dict(os.environ)
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    for name in ("TMPDIR", "TMP", "TEMP"):
        env[name] = str(temporary)
    sources = [
        JAVA / "com/limelight/nvstream/jni/MoonBridge.java",
        JAVA / "com/limelight/nvstream/NvConnectionListener.java",
        JAVA / "com/limelight/nvstream/av/audio/AudioRenderer.java",
        JAVA / "com/limelight/nvstream/av/video/VideoDecoderRenderer.java",
        JAVA / "com/limelight/binding/input/touch/NativeTouchState.java",
        ROOT / "tests/java/com/limelight/binding/input/touch/NativeTouchStateTest.java",
    ]
    jvm = [f"-Djava.io.tmpdir={temporary}", "-XX:-UsePerfData", "-XX:+DisableAttachMechanism"]
    subprocess.run([javac, "-J-Xmx256m", *("-J" + option for option in jvm),
                    "--release", "17", "-d", str(classes), *(str(path) for path in sources)],
                   cwd=ROOT, env=env, check=True, timeout=60)
    subprocess.run([java, "-Xmx128m", *jvm, "-cp", str(classes),
                    "com.limelight.binding.input.touch.NativeTouchStateTest"],
                   cwd=ROOT, env=env, check=True, timeout=30)


if __name__ == "__main__":
    main()
