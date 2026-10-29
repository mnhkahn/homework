#!/usr/bin/env python3
"""Reduce DocAligner ONNX weight storage while retaining FP32 computation.

Requires onnx and numpy. Full FP16 execution produced near-zero heatmaps on
our ARM64 Android tablet; do not convert the intermediate tensors to FP16.
"""
import argparse
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    model = onnx.load(args.input)
    initializers, casts = [], []
    for tensor in model.graph.initializer:
        values = numpy_helper.to_array(tensor)
        if tensor.data_type == TensorProto.FLOAT and values.size >= 128:
            # Preserve small normalization constants and avoid overflowing FP16.
            with np.errstate(over='ignore'):
                packed = values.astype(np.float16)
            if np.isfinite(packed).all():
                name = tensor.name + '__fp16_storage'
                initializers.append(numpy_helper.from_array(packed, name))
                casts.append(helper.make_node('Cast', [name], [tensor.name],
                                              to=TensorProto.FLOAT, name=name + '__to_float32'))
                continue
        initializers.append(tensor)
    nodes = casts + list(model.graph.node)
    del model.graph.initializer[:]
    model.graph.initializer.extend(initializers)
    del model.graph.node[:]
    model.graph.node.extend(nodes)
    onnx.checker.check_model(model)
    onnx.save(model, args.output)
    print(f'Stored {len(casts)} large weight tensors as FP16; computation remains FP32.')


if __name__ == '__main__':
    main()
