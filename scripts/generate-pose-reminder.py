#!/usr/bin/env python3
"""Generate an original, softly attacked four-note reminder with a quiet loop boundary."""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
DURATION = 4.0
# C5, E5, G5, E5: a gentle rising and settling phrase, followed by a short rest.
NOTES = [(0.1, 523.25, .9), (.85, 659.25, .9), (1.6, 783.99, 1.0), (2.45, 659.25, 1.0)]
samples = []
for i in range(int(RATE * DURATION)):
    t = i / RATE
    value = 0.0
    for start, frequency, duration in NOTES:
        elapsed = t - start
        if 0 <= elapsed < duration:
            attack = min(elapsed / .045, 1.0)
            release = min((duration - elapsed) / .18, 1.0)
            envelope = attack * release * math.exp(-2.5 * elapsed / duration)
            phase = 2 * math.pi * frequency * elapsed
            value += .22 * envelope * (math.sin(phase) + .12 * math.sin(2 * phase))
    samples.append(round(value * 32767))
assert max(abs(x) for x in samples) < 32767
assert samples[0] == samples[-1] == 0
assert all(x == 0 for x in samples[-int(.5 * RATE):])
output = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/pose_reminder.wav'
with wave.open(str(output), 'wb') as audio:
    audio.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
    audio.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
print(f'{output}: {DURATION:.1f}s, peak {max(abs(x) for x in samples) / 32767:.3f}')
