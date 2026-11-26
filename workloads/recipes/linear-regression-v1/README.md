# Bounded PyTorch linear regression — recipe candidate v1

This is a fixed-code workload, not arbitrary uploaded Python. It trains a tiny linear regression with PyTorch using at most 256 rows, 64 features, 100 epochs, and a 1 MiB JSON request. CUDA is the default and requires **exactly one** visible GPU. `--device cpu` is only a development smoke path. It writes a deterministic JSON result with weights, bias and final mean-squared error. No model download, network request, shell execution, or user-supplied code is performed.

The container's base is the official PyTorch `2.9.1-cuda12.8-cudnn9-runtime` image pinned to an OCI digest. Build the candidate with:

```text
docker build -t hyperlooms/linear-regression-v1:dev workloads/recipes/linear-regression-v1
```

The runtime entrypoint reads `/inputs/request.json` and writes `/outputs/result.json` directly into one pre-created bind-mounted file. The host consumes it only after exit 0, checking size and JSON validity. The example input is [example-request.json](example-request.json). Run parser tests with `python -m unittest discover -s workloads/recipes/linear-regression-v1 -p 'test_*.py' -v`. The CPU training test runs when PyTorch is installed; CI installs the pinned CPU wheel and executes it. A successful CPU smoke does **not** qualify CUDA, NVIDIA Container Toolkit, or the Linux provider sandbox.

The manual [signed-image workflow](../../../.github/workflows/recipe-image.yml) builds the Linux amd64 image to GHCR by immutable commit tag, then keylessly signs and verifies its digest using this repository's main-branch workflow identity. The first [successful release-candidate run](https://github.com/appalla-karthik/Hyperlooms/actions/runs/45812722815) published `ghcr.io/appalla-karthik/hyperlooms-linear-regression-v1@sha256:1d39207d047619cbf918fab61b859c93352b51bc42fce5ee37dd81133ad99f9b`. This is **not** automatic approval. A trusted operator must inspect the image and expected entrypoint, run it on a qualified Linux amd64 NVIDIA host, and only then approve that exact digest in `workload_recipes`. The internal Go runtime now has bounded input/result staging and signature verification, but its agent session has no executable command, artifact upload or fenced completion path. No placeholder digest or auto-approval migration is safe.
