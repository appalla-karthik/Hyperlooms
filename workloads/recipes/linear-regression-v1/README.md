# Bounded PyTorch linear regression — recipe candidate v1

This is a fixed-code workload, not arbitrary uploaded Python. It trains a tiny linear regression with PyTorch using at most 256 rows, 64 features, 100 epochs, and a 1 MiB JSON request. CUDA is the default and requires **exactly one** visible GPU. `--device cpu` is only a development smoke path. It writes a deterministic JSON result with weights, bias and final mean-squared error. No model download, network request, shell execution, or user-supplied code is performed.

The container's base is the official PyTorch `2.9.1-cuda12.8-cudnn9-runtime` image pinned to an OCI digest. Build the candidate with:

```text
docker build -t hyperlooms/linear-regression-v1:dev workloads/recipes/linear-regression-v1
```

The runtime entrypoint reads `/inputs/request.json` and atomically writes `/outputs/result.json`. The example input is [example-request.json](example-request.json). Run parser tests with `python -m unittest discover -s workloads/recipes/linear-regression-v1 -p 'test_*.py' -v`. The CPU training test runs when PyTorch is installed; CI installs the pinned CPU wheel and executes it. A successful CPU smoke does **not** qualify CUDA, NVIDIA Container Toolkit, or the Linux provider sandbox.

The recipe is intentionally **not approved in `workload_recipes` yet**. Its final image must be built, published to an OCI registry, recorded by immutable digest, signed/verified according to the image policy, inspected for the expected entrypoint, and tested on a qualified Linux amd64 NVIDIA host before a trusted operator approves that exact digest. The current Go runtime also lacks input/output mounts and artifact verification, so the provider agent cannot execute this recipe end to end. No placeholder digest or auto-approval migration is safe.
