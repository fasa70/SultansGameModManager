"""Shared digest pin contracts for the frozen loader release."""

from __future__ import annotations

from dataclasses import dataclass
import re
from pathlib import Path


SHA256 = r"[0-9a-f]{64}"


@dataclass(frozen=True)
class PinContract:
    name: str
    relative_path: str
    pattern: re.Pattern[str]
    digest_kind: str

    def find(self, text: str) -> list[re.Match[str]]:
        return list(self.pattern.finditer(text))

    def update(self, text: str, value: str) -> str:
        matches = self.find(text)
        if len(matches) != 1:
            raise ValueError(
                f"{self.name}: expected exactly one match, found {len(matches)}"
            )
        match = matches[0]
        return text[: match.start(2)] + value + text[match.end(2) :]

    def read(self, text: str, expected: str) -> None:
        matches = self.find(text)
        if len(matches) != 1:
            raise ValueError(
                f"{self.name}: expected exactly one match, found {len(matches)}"
            )
        actual = matches[0].group(2)
        if actual != expected:
            raise ValueError(f"{self.name}: expected {expected}, found {actual}")


def contract(
    name: str, relative_path: str, expression: str, digest_kind: str
) -> PinContract:
    return PinContract(name, relative_path, re.compile(expression, re.MULTILINE), digest_kind)


PIN_CONTRACTS = (
    contract(
        "GameProfileRegistry native",
        "android/manager/app/src/main/java/com/sultansgame/modmanager/platform/patch/GameProfileRegistry.kt",
        rf'(nativeLoaderSha256\s*=\s*")({SHA256})(")',
        "native",
    ),
    contract(
        "DeviceSigningKeyStoreTest native",
        "android/manager/app/src/androidTest/java/com/sultansgame/modmanager/platform/patch/DeviceSigningKeyStoreTest.kt",
        rf'(AndroidLoaderSplitArtifactFactory\(\s*\n\s*context,\s*")({SHA256})(")',
        "native",
    ),
    contract(
        "release metadata template",
        "release/loader-template-10005.json",
        rf'("templateSha256"\s*:\s*")({SHA256})(")',
        "template",
    ),
    contract(
        "release metadata native",
        "release/loader-template-10005.json",
        rf'("nativeSha256"\s*:\s*")({SHA256})(")',
        "native",
    ),
)


def read_utf8(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as stream:
        return stream.read()


def write_utf8(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        stream.write(text)


def target_paths() -> tuple[str, ...]:
    return (
        "android/manager/app/src/main/assets/release/modloader-template-10005.apk",
        *sorted({item.relative_path for item in PIN_CONTRACTS}),
    )


def contracts_for(relative_path: str) -> tuple[PinContract, ...]:
    return tuple(item for item in PIN_CONTRACTS if item.relative_path == relative_path)


def validate_digest(value: str, name: str) -> None:
    if re.fullmatch(SHA256, value) is None:
        raise ValueError(f"{name} is not a lowercase SHA-256 digest")


__all__ = [
    "PIN_CONTRACTS",
    "PinContract",
    "contracts_for",
    "read_utf8",
    "target_paths",
    "validate_digest",
    "write_utf8",
]
