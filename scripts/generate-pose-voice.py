#!/usr/bin/env python3
"""Build bundled Mandarin reminder clips using the macOS Tingting voice."""
from pathlib import Path
import subprocess
import tempfile
import wave

phrases = {
    'pose_head_high': '头抬得有点高，请低一点头，看着作业。',
    'pose_head_low': '头太低了，请抬高一点。',
    'pose_turn_back': '头转到旁边了，请转回来，专心写作业。',
    'pose_too_close': '离平板太近了，请往后坐一点。',
    'pose_too_far': '离平板有点远，请回到原来的位置。',
}
root = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
with tempfile.TemporaryDirectory() as temp:
    for name, phrase in phrases.items():
        intermediate = Path(temp) / (name + '.aiff')
        output = root / (name + '.wav')
        subprocess.run(['say', '-v', 'Tingting', '-r', '175', '-o', str(intermediate), phrase], check=True)
        subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@22050', '-c', '1', str(intermediate), str(output)], check=True)
        with wave.open(str(output), 'rb') as audio:
            assert audio.getnframes() > 0
            print(f'{name}: {audio.getnframes()/audio.getframerate():.1f}s')
