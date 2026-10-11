#!/usr/bin/env python3
"""Regenerate the pinned cunny2 GLES hooks without other optional filter sources."""
import argparse
import collections
import hashlib
import json
import pickle
from pathlib import Path

REVISION = "906031bb00c15dd6a6bbbaa21c0eb0b724ca8437"
PRESETS = ["veryfast", "faster", "fast", "3x12", "4x12", "4x16", "4x24", "4x32", "8x32"]

def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked(source, relative):
    origin = json.loads((source / "origin.json").read_text(encoding="utf-8"))
    expected = REVISION
    if origin["revision"] != expected:
        raise ValueError("Unexpected source revision")
    tree = json.loads((source / "tree.json").read_text(encoding="utf-8"))["tree"]
    blob = next(entry["sha"] for entry in tree if entry["path"] == relative)
    data = (source / relative).read_bytes()
    if hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() != blob:
        raise ValueError("Source differs from fixed Git blob: " + relative)
    return data, {"path": relative, "gitBlob": blob, "sha256": digest(data)}


def hook(description, binds, save, body):
    return ("//!DESC " + description + "\n//!HOOK MAIN\n"
            + "".join("//!BIND " + name + "\n" for name in binds)
            + ("//!SAVE " + save + "\n" if save else "") + body + "\n")


def read_model(path):
    import numpy as np
    # Never allow an arbitrary pickle GLOBAL from external model files.
    class ModelReader(pickle.Unpickler):
        def find_class(self, module, name):
            if (module, name) == ("collections", "OrderedDict"):
                return collections.OrderedDict
            if module in ["numpy.core.numeric", "numpy._core.numeric"] and name == "_frombuffer":
                from numpy._core.numeric import _frombuffer
                return _frombuffer
            if (module, name) == ("numpy", "dtype"):
                return np.dtype
            raise pickle.UnpicklingError("Unexpected model class: " + module + "." + name)
    with path.open("rb") as stream:
        model = ModelReader(stream).load()
    if not isinstance(model, dict) or model.get("quant-8"):
        raise ValueError("Only the fixed floating-point NVL models are supported")
    for key, value in model.items():
        if key.endswith((".weight", ".bias")):
            if not isinstance(value, np.ndarray) or value.dtype != np.float32 or not np.isfinite(value).all():
                raise ValueError("Invalid tensor: " + key)
    return model


def number(value):
    # Nine significant digits preserve all float32 model coefficients.
    return f"{float(value):.8e}"


def vector(values):
    return "vec4(" + ",".join(number(value) for value in values) + ")"


def cunny(model, preset):
    import numpy as np
    prefix = ("// CuNNy NVL GLES fragment adaptation, 2026-10-09.\n"
        "// Copyright (c) 2024 funnyplanter. Original: https://github.com/funnyplanter/CuNNy\n"
        "// GNU Lesser General Public License version 3.0 or (at your option) any later version.\n"
        "// See LICENSE-LGPL-3.0 and LICENSE-GPL-3.0 in this directory.\n"
        "// Original weights/activation retained; FP32 math, RGBA16F feature storage.\n")
    parts = [prefix]
    inputs = ["MAIN"]
    keys = [key[:-6] for key in model if key.endswith("weight")]
    for layer, key in enumerate(keys):
        weights = model[key + "weight"]
        outputs, channels, ky, kx = weights.shape
        if outputs % 4 or channels not in [1, 3] and channels % 4 or ky != kx or ky not in [1, 3]:
            raise ValueError("Unexpected convolution shape")
        bank = "A" if layer % 2 == 0 else "B"
        out_names = [bank + str(group) for group in range(outputs // 4)]
        for group, name in enumerate(out_names):
            lines = []
            for inp in inputs:
                lines.append(f"vec4 q_{inp}(ivec2 p){{return texelFetch(u_{inp},clamp(p,ivec2(0),ivec2({inp}_size)-ivec2(1)),0);}}")
            lines.append("vec4 hook(){ ivec2 p=ivec2(gl_FragCoord.xy);")
            bias = model.get(key + "bias", np.zeros(outputs, dtype=np.float32))
            lines.append("vec4 result=" + vector(bias[group * 4:(group + 1) * 4]) + ";")
            for inp_index, inp in enumerate(inputs):
                for y in range(ky):
                    for x in range(kx):
                        # Canonical engine textures have bottom-left origin; training uses top-left.
                        sample = f"q_{inp}(p+ivec2({x-kx//2},{ky//2-y}))"
                        w = weights[group * 4:(group + 1) * 4, inp_index * 4:min((inp_index + 1) * 4, channels), y, x]
                        if channels == 1:
                            value = f"dot({sample}.rgb,vec3(0.299,0.587,0.114))"
                            matrix = vector(w[:, 0])
                        else:
                            value = sample + (".rgb" if channels == 3 else "")
                            matrix = ("mat3x4" if channels == 3 else "mat4") + "(" + ",".join(number(n) for n in w.T.ravel()) + ")"
                        lines.append(f"result += {matrix} * {value};")
            if key != "cout.":
                lines.append("result=clamp(result,0.0,1.0);" if model["quant"] else "result=max(result,0.0);")
            lines.append("return result;}\n")
            parts.append(hook(f"CuNNy-{preset}-NVL {key}{group}", inputs, name, "\n".join(lines)))
        inputs = out_names
    # Residual channels are the native 2x pixel-shuffle. Invert phase Y to match texture origin.
    lines = ["vec4 hook(){ ivec2 pixel=ivec2(gl_FragCoord.xy); ivec2 p=pixel/2;",
             "int phase=(1-(pixel.y%2))*2+(pixel.x%2); vec3 base=MAIN_tex(vPos).rgb;"]
    if model["rgb"]:
        lines.append("vec3 residual=vec3(" + ",".join(f"texelFetch(u_{name},p,0)[phase]" for name in inputs) + ");")
        lines.append("return vec4(base+residual,1.0);}")
    else:
        lines.append(f"float residual=texelFetch(u_{inputs[0]},p,0)[phase];")
        lines.append("float y=dot(base,vec3(0.299,0.587,0.114)); return vec4(base+vec3(clamp(y+residual,0.0,1.0)-y),1.0);}")
    parts.append(hook(f"CuNNy-{preset}-NVL native 2x reconstruction", ["MAIN"] + inputs, None, "\n".join(lines)))
    return "".join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    inputs, generated = [], []
    for preset in PRESETS:
        relative = "pretrained/" + preset + "-NVL.pickle"
        _, info = checked(args.sources, relative)
        inputs.append(info)
        generated.append(("cunny2/CuNNy-" + preset + "-NVL.glsl", cunny(read_model(args.sources / relative), preset).encode("utf-8")))
    _, license_info = checked(args.sources, "LICENSE")
    manifest = {"sources": [{"repository": "https://github.com/funnyplanter/CuNNy", "revision": REVISION,
        "license": {"spdx": "LGPL-3.0-or-later", **license_info}, "files": inputs}], "generated": []}
    for relative, data in generated:
        output = root / "engine/src/main/assets" / relative
        if args.check:
            if output.read_bytes() != data: raise ValueError("Generated asset differs: " + relative)
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
        manifest["generated"].append({"file": relative, "sha256": digest(data)})
    path = root / "engine/patches/cunny2-filters.json"
    if args.check:
        if json.loads(path.read_text(encoding="utf-8")) != manifest: raise ValueError("Generated manifest differs")
    else:
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    print("Verified" if args.check else "Generated", len(generated), "assets")

if __name__ == "__main__":
    main()
