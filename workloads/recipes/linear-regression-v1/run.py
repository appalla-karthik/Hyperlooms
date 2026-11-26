"""Bounded PyTorch linear-regression training recipe (input/output contract v1)."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path


MAX_INPUT_BYTES = 1_048_576
MAX_ROWS = 256
MAX_FEATURES = 64
MAX_EPOCHS = 100


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _number(value):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and abs(value) <= 1000)


def parse_request(raw: bytes) -> dict:
    if len(raw) > MAX_INPUT_BYTES:
        raise ValueError("request exceeds 1 MiB")
    request = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_pairs,
                         parse_constant=lambda _: (_ for _ in ()).throw(
                             ValueError("non-finite JSON number")))
    if not isinstance(request, dict) or set(request) != {
            "schemaVersion", "features", "targets", "epochs", "learningRate"}:
        raise ValueError("unexpected request fields")
    if type(request["schemaVersion"]) is not int or request["schemaVersion"] != 1:
        raise ValueError("unsupported request schema")
    features = request["features"]
    targets = request["targets"]
    if not isinstance(features, list) or not 2 <= len(features) <= MAX_ROWS:
        raise ValueError("features must have 2 to 256 rows")
    width = len(features[0]) if isinstance(features[0], list) else 0
    if not 1 <= width <= MAX_FEATURES or any(
            not isinstance(row, list) or len(row) != width
            or not all(_number(value) for value in row) for row in features):
        raise ValueError("features must be a finite rectangular matrix")
    if not isinstance(targets, list) or len(targets) != len(features) or not all(
            _number(value) for value in targets):
        raise ValueError("targets must match feature rows")
    if type(request["epochs"]) is not int or not 1 <= request["epochs"] <= MAX_EPOCHS:
        raise ValueError("epochs must be between 1 and 100")
    if not _number(request["learningRate"]) or not 0 < request["learningRate"] <= 0.1:
        raise ValueError("learningRate must be finite and in (0, 0.1]")
    return request


def train(request: dict, *, device: str = "cuda") -> dict:
    import torch

    if device == "cuda":
        if not torch.cuda.is_available() or torch.cuda.device_count() != 1:
            raise RuntimeError("recipe requires exactly one visible CUDA GPU")
    elif device != "cpu":
        raise ValueError("unsupported device")
    torch.manual_seed(7)
    features = torch.tensor(request["features"], dtype=torch.float32, device=device)
    targets = torch.tensor(request["targets"], dtype=torch.float32, device=device).view(-1, 1)
    weights = torch.zeros((features.shape[1], 1), dtype=torch.float32,
                          device=device, requires_grad=True)
    bias = torch.zeros(1, dtype=torch.float32, device=device, requires_grad=True)
    for _ in range(request["epochs"]):
        prediction = features @ weights + bias
        loss = ((prediction - targets) ** 2).mean()
        if not bool(torch.isfinite(loss).item()):
            raise RuntimeError("training diverged")
        loss.backward()
        with torch.no_grad():
            weights -= request["learningRate"] * weights.grad
            bias -= request["learningRate"] * bias.grad
            weights.grad.zero_()
            bias.grad.zero_()
    with torch.no_grad():
        final_loss = ((features @ weights + bias - targets) ** 2).mean().item()
        result = {
            "schemaVersion": 1,
            "device": device,
            "epochs": request["epochs"],
            "weights": [float(value) for value in weights.view(-1).tolist()],
            "bias": float(bias.item()),
            "meanSquaredError": float(final_loss),
        }
    if not math.isfinite(final_loss):
        raise RuntimeError("non-finite final result")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Hyperlooms bounded linear regression recipe")
    parser.add_argument("--input", default="/inputs/request.json")
    parser.add_argument("--output", default="/outputs/result.json")
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    args = parser.parse_args()
    input_path = Path(args.input)
    with input_path.open("rb") as stream:
        raw = stream.read(MAX_INPUT_BYTES + 1)
    request = parse_request(raw)
    result = train(request, device=args.device)
    output_path = Path(args.output)
    encoded = json.dumps(result, allow_nan=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8") + b"\n"
    # The provider bind-mounts this one pre-created output file. A rename over a
    # bind mount fails, so write directly; the host reads only after exit 0 and
    # validates the complete JSON and size.
    with output_path.open("wb") as stream:
        stream.write(encoded)
        stream.flush()
        os.fsync(stream.fileno())


if __name__ == "__main__":
    main()
