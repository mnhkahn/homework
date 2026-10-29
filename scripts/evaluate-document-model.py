#!/usr/bin/env python3
"""Replay saved camera images through a DocAligner ONNX model without a camera.

Requires: numpy, opencv-python-headless, onnxruntime.
Images and results should stay in an ignored build directory.
"""
import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--image', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.log_severity_level = 3
    session = ort.InferenceSession(str(args.model), sess_options=options, providers=['CPUExecutionProvider'])
    reports = []
    for path in args.image:
        image = cv2.imread(str(path))
        if image is None:
            raise ValueError(f'Cannot read image: {path}')
        started = time.perf_counter()
        found = False
        points = []
        attempts = []
        for turns in range(4):
            oriented = np.rot90(image, -turns).copy()
            data = cv2.resize(oriented, (256, 256)).transpose(2, 0, 1)[None].astype(np.float32) / 255
            heatmaps = session.run(None, {'img': data})[0][0]
            attempts.append({'rotation': turns * 90, 'confidence_peaks': [round(float(h.max()), 4) for h in heatmaps]})
            points = []
            for heat in heatmaps:
                mask = (heat >= .3).astype(np.uint8)
                count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, connectivity=8)
                if count <= 1:
                    break
                index = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
                x, y = centroids[index]
                u, v = (x + .5) / heat.shape[1], (y + .5) / heat.shape[0]
                sx, sy = [(u, v), (v, 1-u), (1-u, 1-v), (1-v, u)][turns]
                points.append([sx * image.shape[1], sy * image.shape[0]])
            if len(points) == 4:
                polygon = np.array(points, dtype=np.float32)
                area = abs(cv2.contourArea(polygon)) / (image.shape[0] * image.shape[1])
                found = bool(cv2.isContourConvex(polygon) and .08 <= area <= .98)
                if found:
                    break
        elapsed = (time.perf_counter() - started) * 1000
        preview = image.copy()
        if found:
            cv2.polylines(preview, [np.array(points).astype(int)], True, (0, 220, 0), max(2, image.shape[1] // 400))
        cv2.imwrite(str(args.output / f'{path.stem}-result.jpg'), preview)
        reports.append({'image': str(path), 'detected': found, 'milliseconds': round(elapsed, 1),
                        'attempts': attempts,
                        'corners': points if found else None})
    report = {'model': str(args.model), 'results': reports}
    (args.output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
