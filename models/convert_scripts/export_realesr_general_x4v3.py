#!/usr/bin/env python3
"""Optional: download xinntao Real-ESRGAN realesr-general-x4v3 and export a 64x64 ONNX tile.

This weight is NOT packaged in the APK. The bundled model is SESR-M5 INT8
(app/src/main/assets/models/sesr_m5_int8.onnx, Apache-2.0, about 120 KB).
Real-ESRGAN general-x4v3 is about 4.7 MB, BSD-3-Clause (see RealESRGAN-BSD-3-Clause.txt).
RealBasicVSR is not used; the recurrent checkpoint is about 210 MB and is not bundled.

Official weights (BSD-3-Clause):
https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth

Usage:
  python3 models/convert_scripts/export_realesr_general_x4v3.py
"""

from __future__ import annotations

import argparse
import urllib.request
from pathlib import Path

import torch
from torch import nn
from torch.nn import functional as F

WEIGHT_URL = (
    "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/"
    "realesr-general-x4v3.pth"
)
TILE = 64


class SRVGGNetCompact(nn.Module):
    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        body = [
            nn.Conv2d(num_in_ch, num_feat, 3, 1, 1),
            nn.PReLU(num_parameters=num_feat),
        ]
        for _ in range(num_conv):
            body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            body.append(nn.PReLU(num_parameters=num_feat))
        body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.body = nn.ModuleList(body)
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        base = F.interpolate(x, scale_factor=self.upscale, mode="nearest")
        return out + base


def load_state(path: Path):
    checkpoint = torch.load(path, map_location="cpu", weights_only=False)
    if isinstance(checkpoint, dict) and "params_ema" in checkpoint:
        return checkpoint["params_ema"]
    if isinstance(checkpoint, dict) and "params" in checkpoint:
        return checkpoint["params"]
    return checkpoint


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pth", type=Path, default=Path("/tmp/realesr/realesr-general-x4v3.pth"))
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("app/src/main/assets/models/realesr-general-x4v3.onnx"),
    )
    args = parser.parse_args()
    if not args.pth.exists():
        args.pth.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {WEIGHT_URL}")
        urllib.request.urlretrieve(WEIGHT_URL, args.pth)
    model = SRVGGNetCompact()
    model.load_state_dict(load_state(args.pth))
    model.eval()
    dummy = torch.rand(1, 3, TILE, TILE)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        dummy,
        args.out,
        input_names=["input"],
        output_names=["output"],
        opset_version=17,
        dynamo=False,
    )
    try:
        import onnx
        onnx.checker.check_model(str(args.out))
    except Exception as error:
        print(f"onnx checker skipped or failed: {error}")
    print(f"wrote {args.out} ({args.out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
