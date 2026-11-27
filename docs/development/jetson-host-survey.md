# Jetson host survey (read-only)

The only presently available physical GPU test device is described as a Jetson Nano. This survey identifies its exact model and software stack before any ARM64 execution design is selected. It does **not** enroll the device, run a container, change Docker configuration, or qualify Phase 1 GPU execution.

Run the repository's [`scripts/jetson-host-survey.sh`](../../scripts/jetson-host-survey.sh) in the Jetson's Ubuntu terminal as the user who would eventually run the agent. It does not require `sudo`. Share its output with the development team. A missing file/package or Docker permission error is useful evidence, not a reason to install anything yet. Review the script before running it on your machine.

```sh
bash scripts/jetson-host-survey.sh
```

If the repository is not on the Jetson, use the single-file download and run instructions in the provider [README](../../agents/provider/README.md). The script does not write an output file; copy its terminal output into the conversation.

Equivalent individual read-only checks, if you prefer not to download the script:

```sh
uname -m
tr -d '\000' < /proc/device-tree/model
cat /etc/nv_tegra_release
dpkg-query -W -f='${Package} ${Version}\n' nvidia-jetpack nvidia-container-runtime nvidia-container-toolkit 2>/dev/null
docker info --format '{{.OSType}}/{{.Architecture}} runtimes={{json .Runtimes}}'
free -h
df -h /
command -v nvidia-smi
command -v tegrastats
```

Do **not** enable `HYPERLOOMS_AGENT_RUNTIME_RECOVERY_ENABLED`, run a public-provider session, or try the current `preflight` pass criterion on this machine. The agent's existing GPU discovery relies on `nvidia-smi` and its runtime requires Linux amd64 with a discrete NVIDIA GPU UUID. The Jetson path needs a different hardware identity, shared-memory accounting, architecture-specific image policy, and a witnessed NVIDIA-container smoke before it can become schedulable. An ARM64 agent build or successful mTLS hello alone would prove none of those.

The model matters: original Jetson Nano and Jetson Orin Nano are different product generations with different JetPack support. Select a container image only after the exact device and L4T/JetPack version are known. Do not use an arbitrary current desktop CUDA/PyTorch image as a compatibility test. NVIDIA's [JetPack archive](https://developer.nvidia.com/embedded/jetpack-archive) lists supported modules per release; its [Jetson Nano specifications](https://developer.nvidia.com/embedded/jetson-nano) describe the original Nano's 4 GB system memory, and the [Jetson container-runtime documentation](https://docs.nvidia.com/deepstream/text/DS_docker_containers) distinguishes Jetson images from other ARM or desktop GPU images.

CI now cross-compiles a static Linux ARM64 agent and uploads a short-lived `hyperlooms-agent-linux-arm64-experimental` artifact with its SHA-256 checksum. After downloading it from a successful Engineering quality run on GitHub, check the checksum and run `./hyperlooms-agent-linux-arm64 version` on the Jetson. This verifies only that the binary starts on that host. Its local console can run without enrollment. The ARM64 `preflight` now reports the surveyed Jetson model, L4T release, GPU device interfaces, Docker server, and NVIDIA runtime, but deliberately exits nonzero and reports **zero schedulable GPUs**. The mTLS session still refuses ARM64 GPU reporting; do **not** enroll or schedule the Jetson yet.

### Observed original Nano baseline (operator-reported, 27 November 2026)

The operator's terminal output identified an original **NVIDIA Jetson Nano Developer Kit**, Ubuntu 18.04.6, kernel `4.9.337-tegra`, L4T R32.7.6, and `aarch64`. `tegrastats`, CUDA `nvcc`, and the expected Jetson GPU device nodes were present; `nvidia-smi` was absent. The host had 3,960 MiB shared RAM and about 1,879 MiB free on `/`. The Docker daemon answered `linux/aarch64` through its local Unix socket and listed the `nvidia` runtime when queried with `sudo`; the regular user lacked socket access. No Docker images were cached. These are untrusted operator observations, not remote attestation or proof of container GPU injection.

The operator compiled and ran a 256-element CUDA square kernel on the host twice; both runs printed `GPU_WORKLOAD_PASS`. The versioned reproduction source is [`scripts/jetson-cuda-smoke.cu`](../../scripts/jetson-cuda-smoke.cu). NVIDIA lists the original Nano's compute capability as [5.3](https://developer.nvidia.com/cuda/gpus/legacy):

```sh
nvcc -arch=sm_53 scripts/jetson-cuda-smoke.cu -o /tmp/hyperlooms-jetson-cuda-smoke
/tmp/hyperlooms-jetson-cuda-smoke
```

This first run proves host CUDA computation only. Subsequently, the operator pulled the small official Ubuntu 18.04 ARM64 image at `ubuntu@sha256:f97a5103cca28097326814718e711c9c41b54853c26959d73495e40b1dd608f2` and confirmed `linux/arm64`. A Docker run with the NVIDIA runtime, no network, dropped capabilities, no-new-privileges, 64-PID and 768-MiB limits initially failed with `CUDA driver version is insufficient for CUDA runtime version`. Inspection found that the runtime had mounted `/dev/nvhost-gpu`, `/dev/nvmap`, and the host's Tegra `libcuda.so.1.1`, but generic Ubuntu lacked Jetson's loader path. With an **ephemeral change inside that container only** to register `/usr/lib/aarch64-linux-gnu/tegra` and run `ldconfig`, the same compiled kernel printed `GPU_WORKLOAD_PASS: 256 values verified on NVIDIA Tegra X1`. This is an operator-reported container GPU computation, not an independently witnessed or production-qualified Hyperlooms job. That run used root inside the container and did not prove read-only root, non-root device access, artifact exchange, PyTorch, or remote scheduling.

### Next: restricted, non-root container diagnostic

The [`scripts/jetson-cuda-smoke.Dockerfile`](../../scripts/jetson-cuda-smoke.Dockerfile) derives from the exact already-cached Ubuntu digest, adds only the missing `libcuda.so.1` link and Tegra loader path, and installs **no packages or driver**. The [`scripts/jetson-restricted-cuda-smoke.sh`](../../scripts/jetson-restricted-cuda-smoke.sh) accepts only the resulting local image ID, checks the surveyed Jetson/L4T and image architecture, runs the already-compiled kernel as UID 65532 with the device-node groups, no network, read-only filesystem, dropped capabilities, no-new-privileges and bounded resources, and removes its exact container ID. The local Docker image remains cached. The operator ran this path on the surveyed Jetson and reported both `GPU_WORKLOAD_PASS: 256 values verified on NVIDIA Tegra X1` and `JETSON_RESTRICTED_CUDA_SMOKE_PASS: non-root GPU workload verified and container removed`. This is a successful **restricted CUDA diagnostic**, not an independently witnessed Hyperlooms execution or proof of malicious-workload isolation. Docker socket access remains privileged; the surveyed regular user used `sudo`.

From the Windows repository root, transfer these two files (the CUDA binary is already in `/home/pranav`):

```powershell
scp .\scripts\jetson-cuda-smoke.Dockerfile .\scripts\jetson-restricted-cuda-smoke.sh pranav@JETSON_IP:/home/pranav/
```

Then on the Jetson, after confirming the previously pulled base image is still cached and checking free disk space:

```sh
cd /home/pranav
df -h /
sudo docker -H unix:///var/run/docker.sock image inspect ubuntu@sha256:f97a5103cca28097326814718e711c9c41b54853c26959d73495e40b1dd608f2
sudo docker -H unix:///var/run/docker.sock build --pull=false --network=none -t hyperlooms-jetson-cuda-smoke:local - < jetson-cuda-smoke.Dockerfile
image_id="$(sudo docker -H unix:///var/run/docker.sock image inspect --format '{{.Id}}' hyperlooms-jetson-cuda-smoke:local)"
sudo bash ./jetson-restricted-cuda-smoke.sh "$image_id" /home/pranav/hyperlooms_cuda_smoke
```

Feed the Dockerfile through standard input as above; this image has no `COPY`/`ADD` from the host and needs **no filesystem build context**. Using `.` from `/home/pranav` sends the whole home directory to the Docker daemon unnecessarily. If the image was already built successfully, do not rebuild it just to change the command: inspect its ID and run the smoke. The `image_id` assignment must be run in the **same shell** as the smoke command; an empty variable produces the usage message.

If the restricted run fails, preserve the exact error and container-cleanup message for diagnosis; do not weaken isolation flags blindly. The older experimental [`scripts/jetson-container-smoke.sh`](../../scripts/jetson-container-smoke.sh) targets a separately reviewed, already cached L4T-compatible registry digest and has not been validated on this host. Neither diagnostic authorizes Jetson enrollment or GPU advertising. Shared system RAM is not dedicated VRAM. The remaining gates are hardware identity/accounting, signed ARM64 workload image, verified input/result artifacts, lease-bound command and cancellation, and an end-to-end remote test. Keep the Jetson offline/unschedulable until those gates are implemented and witnessed.
