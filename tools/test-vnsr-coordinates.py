#!/usr/bin/env python3
"""Compare pinned top-left GLSL math with adapted bottom-left math on asymmetric RGB.

This FP64 CPU evaluator checks exported coefficients, taps and PixelShuffle, not
device GLSL compilation, FP16 precision or agreement with training checkpoints.
"""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def evaluate(text, image, flip_coordinates=False):
    """Evaluate exported convolution passes and residual shuffle in array coordinates."""
    height, width = len(image), len(image[0])
    textures = {"MAIN": image}
    passes = text.split("//!DESC ")[1:]
    for shader in passes[:-1]:
        save = re.search(r"//!SAVE (\w+)", shader)[1]
        inputs = dict(re.findall(r"vec4 sample_(\d+)\(vec2 p\) \{ return (\w+)_tex", shader))
        flip = ("vec2(p.x,-p.y)" in shader) != flip_coordinates
        bias = [float(x) for x in re.search(r"vec4 value=vec4\(([^)]+)\)", shader)[1].split(",")]
        terms = re.findall(r"mat4\(([^)]+)\)\*sample_(\d+)\(vec2\(([-.\d]+),([-.\d]+)\)\)", shader)
        assert terms and len(bias) == 4
        terms = [([float(v) for v in matrix.split(",")], inputs[index], int(float(dx)), int(float(dy)) * (-1 if flip else 1)) for matrix,index,dx,dy in terms]
        output = []
        for y in range(height):
            row = []
            for x in range(width):
                value = bias.copy()
                for matrix, name, dx, dy in terms:
                    sample = textures[name][min(height-1,max(0,y+dy))][min(width-1,max(0,x+dx))]
                    for out in range(4):
                        value[out] += sum(matrix[ch*4+out]*sample[ch] for ch in range(4))
                if "value=max(value,0.0)" in shader:
                    value = [max(v,0.0) for v in value]
                row.append(value)
            output.append(row)
        textures[save] = output
    final = passes[-1]
    residuals = re.findall(r"(vnsr_output_\d+)_tex\(center\)\[index\]", final)
    assert len(residuals) == 3 and "//!SAVE MAIN" in final
    flip_phase = ("int index=(1-phase.y)*2+phase.x;" in final) != flip_coordinates
    return [[[textures[name][y//2][x//2][((1-y%2) if flip_phase else y%2)*2+x%2]
              for name in residuals] for x in range(width*2)] for y in range(height*2)]


def main():
    """Check catalog invariants and full residual outputs for all retained variants."""
    filters = json.loads((ROOT/"engine/src/main/graphicsfilters/vnsr.json").read_text())["filters"]
    assert [f["id"] for f in filters] == ["vnsr_c08_x2", "vnsr_c12_x2", "vnsr_c16_x2"]
    image = [[[((x*3+y*7+c*11)%31)/31 for c in range(3)]+[1.0] for x in range(8)] for y in range(8)]
    for definition, count in zip(filters, (10,15,20)):
        name = Path(definition["asset"]).name
        original = (ROOT/"engine/patches/vnsr/sources"/name).read_text()
        adapted = (ROOT/"engine/src/main/assets"/definition["asset"]).read_text()
        assert definition["nativeScale"] == definition["maxOutputScale"] == 2
        assert definition["haloPerPass"] == 1
        assert adapted.count("//!DESC ") == count
        offsets = re.findall(r"sample_\d+\(vec2\(([-.\d]+),([-.\d]+)\)\)", adapted)
        assert offsets and max(abs(float(v)) for pair in offsets for v in pair) == 1
        expected = evaluate(original,image)
        assert definition["topLeftCoordinates"]
        assert evaluate(adapted,image) == expected
        actual = evaluate(adapted,image[::-1],flip_coordinates=True)[::-1]
        error = max(abs(a-b) for ra,rb in zip(expected,actual) for pa,pb in zip(ra,rb) for a,b in zip(pa,pb))
        assert error < 1e-10, (name,error)
        # Without the coordinate adaptation this asymmetric input must distinguish the output.
        wrong = evaluate(original,image[::-1])[::-1]
        assert max(abs(a-b) for ra,rb in zip(expected,wrong) for pa,pb in zip(ra,rb) for a,b in zip(pa,pb)) > 1e-5
        print(name+": residual coordinate equivalence passed; max error="+str(error))


if __name__ == "__main__":
    main()
