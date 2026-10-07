#!/usr/bin/env python3
"""Loudness of mic clips the way the app measures it (MainActivity: Wav.rms over the whole clip, 1.0 = full scale), plus
the 50 ms windows (min / median / max) so a click or a voice burst shows apart from the room's floor.

  K/venv/bin/python -I K/scripts/wav_rms.py <clip.wav> [...]
"""
import math
import sys
import wave

import numpy as np


def db(x):
    return 20 * math.log10(x) if x > 0 else float("-inf")


for path in sys.argv[1:]:
    with wave.open(path) as w:
        rate, n = w.getframerate(), w.getnframes()
        x = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float64) / 32768.0
    rms = float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0
    win = rate // 20
    frames = [float(np.sqrt(np.mean(x[i:i + win] ** 2))) for i in range(0, len(x) - win + 1, win)]
    f = sorted(frames)
    med = f[len(f) // 2] if f else 0.0
    print(f"{path}: {n / rate:.2f} s, rms {rms:.5f} ({db(rms):.1f} dBFS); 50 ms windows n={len(f)} "
          f"min {f[0] if f else 0:.5f} median {med:.5f} max {f[-1] if f else 0:.5f} ({db(f[-1]) if f else 0:.1f} dBFS); "
          f"peak {float(np.max(np.abs(x))) if len(x) else 0:.4f}; first 3 windows {[round(v, 5) for v in frames[:3]]}")
