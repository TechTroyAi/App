#!/usr/bin/env python3
"""Generate Blockhold Defense's original mono WAV sound effects.

The sounds use only synthesized oscillators and deterministic noise, so every
asset is original and reproducible without third-party samples.
"""

from __future__ import annotations

import math
import random
import struct
import wave
from pathlib import Path
from typing import Callable, Iterable

RATE = 22_050
RNG = random.Random(20260826)
OUTPUT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"


def tone(duration: float, start_hz: float, end_hz: float, amplitude: float = 0.6,
         shape: str = "sine", decay: float = 2.0) -> list[float]:
    count = max(1, int(duration * RATE))
    phase = 0.0
    result: list[float] = []
    for index in range(count):
        progress = index / max(1, count - 1)
        frequency = start_hz + (end_hz - start_hz) * progress
        phase += 2.0 * math.pi * frequency / RATE
        if shape == "square":
            value = 1.0 if math.sin(phase) >= 0.0 else -1.0
        elif shape == "triangle":
            value = 2.0 / math.pi * math.asin(math.sin(phase))
        else:
            value = math.sin(phase)
        envelope = (1.0 - progress) ** decay
        attack = min(1.0, index / max(1.0, RATE * 0.004))
        result.append(value * amplitude * envelope * attack)
    return result


def noise(duration: float, amplitude: float = 0.5, decay: float = 2.0,
          smooth: float = 0.0) -> list[float]:
    count = max(1, int(duration * RATE))
    result: list[float] = []
    previous = 0.0
    for index in range(count):
        progress = index / max(1, count - 1)
        raw = RNG.uniform(-1.0, 1.0)
        value = previous * smooth + raw * (1.0 - smooth)
        previous = value
        attack = min(1.0, index / max(1.0, RATE * 0.002))
        result.append(value * amplitude * ((1.0 - progress) ** decay) * attack)
    return result


def silence(duration: float) -> list[float]:
    return [0.0] * max(1, int(duration * RATE))


def mix(*tracks: tuple[list[float], float]) -> list[float]:
    length = 0
    for samples, offset in tracks:
        length = max(length, int(offset * RATE) + len(samples))
    output = [0.0] * length
    for samples, offset in tracks:
        start = int(offset * RATE)
        for index, sample in enumerate(samples):
            output[start + index] += sample
    return output


def soften(samples: list[float], amount: float = 0.2) -> list[float]:
    if not samples:
        return samples
    output = [samples[0]]
    for sample in samples[1:]:
        output.append(output[-1] * amount + sample * (1.0 - amount))
    return output


def save(name: str, samples: Iterable[float]) -> None:
    values = list(samples)
    peak = max(0.001, max(abs(value) for value in values))
    gain = min(0.94 / peak, 1.5)
    pcm = bytearray()
    for value in values:
        clipped = max(-1.0, min(1.0, value * gain))
        pcm.extend(struct.pack("<h", int(clipped * 32767)))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUTPUT / f"{name}.wav"), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm)


def main() -> None:
    save("ui_click", mix(
        (tone(0.065, 620, 980, 0.45, "triangle", 1.5), 0.0),
        (tone(0.045, 1240, 820, 0.18, "sine", 2.0), 0.008),
    ))
    save("dig", soften(mix(
        (noise(0.14, 0.60, 2.5, 0.25), 0.0),
        (tone(0.11, 155, 92, 0.38, "triangle", 2.0), 0.0),
        (tone(0.045, 720, 360, 0.12, "square", 3.0), 0.015),
    ), 0.28))
    save("build", mix(
        (tone(0.22, 330, 510, 0.42, "triangle", 1.2), 0.0),
        (tone(0.18, 495, 760, 0.33, "sine", 1.4), 0.08),
        (tone(0.14, 760, 1020, 0.24, "sine", 1.8), 0.16),
    ))
    save("bolt", mix(
        (tone(0.10, 1280, 360, 0.44, "square", 2.2), 0.0),
        (noise(0.07, 0.18, 3.0, 0.05), 0.0),
    ))
    save("frost", mix(
        (tone(0.24, 1180, 1640, 0.34, "sine", 1.3), 0.0),
        (tone(0.18, 1780, 920, 0.20, "sine", 2.0), 0.035),
        (tone(0.10, 2380, 2050, 0.12, "triangle", 2.4), 0.06),
    ))
    save("cannon", soften(mix(
        (noise(0.38, 0.85, 2.7, 0.13), 0.0),
        (tone(0.34, 108, 42, 0.82, "sine", 1.7), 0.0),
        (tone(0.13, 265, 78, 0.28, "square", 2.8), 0.0),
    ), 0.08))
    save("enemy_down", mix(
        (tone(0.20, 310, 92, 0.40, "triangle", 1.6), 0.0),
        (noise(0.17, 0.24, 2.1, 0.32), 0.02),
    ))
    save("wave", mix(
        (tone(0.15, 440, 520, 0.38, "square", 1.2), 0.0),
        (tone(0.15, 520, 620, 0.40, "square", 1.2), 0.18),
        (tone(0.28, 660, 880, 0.46, "triangle", 1.1), 0.36),
    ))
    save("base_hit", soften(mix(
        (tone(0.42, 92, 44, 0.78, "square", 1.6), 0.0),
        (noise(0.34, 0.48, 2.0, 0.18), 0.0),
        (tone(0.18, 240, 82, 0.26, "triangle", 2.0), 0.04),
    ), 0.12))
    save("victory", mix(
        (tone(0.30, 392, 392, 0.34, "triangle", 1.0), 0.0),
        (tone(0.30, 494, 494, 0.33, "triangle", 1.0), 0.18),
        (tone(0.30, 587, 587, 0.35, "triangle", 1.0), 0.36),
        (tone(0.62, 784, 880, 0.40, "sine", 1.0), 0.54),
        (tone(0.55, 392, 440, 0.20, "sine", 1.0), 0.54),
    ))
    # 1.3 Phase A+ — dedicated tower fire + shared impact
    save("ember", soften(mix(
        (noise(0.22, 0.55, 2.0, 0.35), 0.0),
        (tone(0.18, 220, 110, 0.38, "triangle", 1.8), 0.0),
        (tone(0.12, 540, 280, 0.22, "sine", 2.2), 0.02),
        (noise(0.08, 0.22, 3.5, 0.1), 0.04),
    ), 0.18))
    save("beacon", mix(
        (tone(0.20, 660, 880, 0.32, "sine", 1.4), 0.0),
        (tone(0.18, 990, 1320, 0.24, "sine", 1.6), 0.02),
        (tone(0.14, 1320, 990, 0.16, "triangle", 2.0), 0.05),
        (tone(0.10, 1760, 1480, 0.10, "sine", 2.4), 0.07),
    ))
    save("impact", soften(mix(
        (noise(0.10, 0.55, 3.0, 0.12), 0.0),
        (tone(0.09, 420, 90, 0.48, "triangle", 2.4), 0.0),
        (tone(0.06, 980, 640, 0.18, "sine", 2.8), 0.012),
        (tone(0.045, 1480, 920, 0.12, "square", 3.2), 0.018),
    ), 0.15))
    print(f"Generated original sound effects in {OUTPUT}")


if __name__ == "__main__":
    main()
