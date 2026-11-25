# Jetson host survey (read-only)

The only presently available physical GPU test device is described as a Jetson Nano. This survey identifies its exact model and software stack before any ARM64 execution design is selected. It does **not** enroll the device, run a container, change Docker configuration, or qualify Phase 1 GPU execution.

Run these commands in the Jetson's Ubuntu terminal as the user who would eventually run the agent. They do not require `sudo`. Share the output with the development team; redact a hostname if it appears. A missing file/package or Docker permission error is useful evidence, not a reason to install anything yet.

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

Until the survey and host smoke pass, continue Phase 1 control-plane and requester software independently, and keep this device offline/unschedulable.
