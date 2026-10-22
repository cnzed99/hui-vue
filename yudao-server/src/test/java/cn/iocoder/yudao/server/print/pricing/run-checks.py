"""Compile and run only the independent pricing checks, then clean temporary classes."""
from pathlib import Path
import subprocess
import tempfile

server = next(parent for parent in Path(__file__).resolve().parents if parent.name == "yudao-server")
workspace = server.parents[2]
source_dir = server / "src/main/java/cn/iocoder/yudao/server/print/pricing"
check_source = Path(__file__).with_name("PaperQuoteCalculatorChecks.java")
evidence = workspace / "evidence/pricing"
evidence.mkdir(parents=True, exist_ok=True)
log = []
try:
    with tempfile.TemporaryDirectory(prefix="pricing-checks-", dir=workspace / "runtime") as temp:
        commands = [
            ["javac", "-J-Xmx128m", "--release", "17", "-d", temp,
             *map(str, sorted(source_dir.glob("*.java"))), str(check_source)],
            ["java", "-Xmx64m", "-cp", temp,
             "cn.iocoder.yudao.server.print.pricing.PaperQuoteCalculatorChecks"],
        ]
        for command in commands:
            result = subprocess.run(command, text=True, encoding="utf-8", stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=30, check=False)
            log.append(command[0] + " exit=" + str(result.returncode) + "\n" + result.stdout)
            if result.returncode:
                raise RuntimeError(command[0] + " failed")
    log.append("temporary_classes_cleaned=true\n")
finally:
    text = "\n".join(log)
    (evidence / "unit-checks.log").write_text(text, encoding="utf-8")
    print(text)
