#!/usr/bin/env bash
# Experimental original-Nano diagnostic. Does not enroll/qualify a provider.
# Run as an explicitly chosen Docker operator after reviewing the local image.
set -euo pipefail

if [[ $# -ne 2 || ! "$1" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    printf 'Usage: %s <locally-built-image-id-sha256:...> <compiled-cuda-smoke-binary>\n' "$0" >&2
    exit 2
fi
image_id="$1"
binary="$2"
if [[ "$(uname -m)" != 'aarch64' ]] ||
    [[ ! -r /proc/device-tree/model ]] ||
    [[ "$(tr -d '\000' < /proc/device-tree/model)" != 'NVIDIA Jetson Nano Developer Kit' ]] ||
    [[ ! -r /etc/nv_tegra_release ]] ||
    [[ "$(head -n 1 /etc/nv_tegra_release)" != '# R32 (release), REVISION: 7.6,'* ]]; then
    printf 'Only the surveyed original Nano on L4T R32.7.6 is in scope.\n' >&2
    exit 2
fi
if [[ ! -f "$binary" || ! -x "$binary" ]]; then
    printf 'CUDA smoke binary must be a regular executable file.\n' >&2
    exit 2
fi
binary="$(readlink -f -- "$binary")"
if [[ "$binary" == *','* || "$binary" == *$'\n'* || "$binary" == *$'\r'* ]]; then
    printf 'Binary path cannot be safely mounted.\n' >&2
    exit 2
fi
for device in /dev/nvhost-gpu /dev/nvmap; do
    if [[ ! -c "$device" ]]; then
        printf 'Required GPU device is absent: %s\n' "$device" >&2
        exit 1
    fi
done

docker_host=(docker -H unix:///var/run/docker.sock)
server="$("${docker_host[@]}" info --format '{{.OSType}}/{{.Architecture}}')"
runtimes="$("${docker_host[@]}" info --format '{{range $name, $_ := .Runtimes}}{{$name}} {{end}}')"
if [[ "$server" != 'linux/aarch64' || " $runtimes " != *' nvidia '* ]]; then
    printf 'Local linux/aarch64 Docker with NVIDIA runtime is required.\n' >&2
    exit 1
fi
actual_id="$("${docker_host[@]}" image inspect --format '{{.Id}}' "$image_id")"
platform="$("${docker_host[@]}" image inspect --format '{{.Os}}/{{.Architecture}}' "$image_id")"
smoke_label="$("${docker_host[@]}" image inspect --format '{{index .Config.Labels "dev.hyperlooms.jetson.cuda-smoke"}}' "$image_id")"
if [[ "$actual_id" != "$image_id" || "$platform" != 'linux/arm64' || "$smoke_label" != 'v1' ]]; then
    printf 'Expected the reviewed local ARM64 CUDA-smoke image ID and label.\n' >&2
    exit 1
fi

# The NVIDIA runtime's device nodes are video-group readable on this host.
# Pass only their actual numeric groups to a fixed unprivileged UID/GID.
mapfile -t device_groups < <(stat -c '%g' /dev/nvhost-gpu /dev/nvmap | sort -u)
group_args=()
for group_id in "${device_groups[@]}"; do
    if [[ ! "$group_id" =~ ^[0-9]+$ ]]; then
        printf 'Invalid GPU device group.\n' >&2
        exit 1
    fi
    group_args+=("--group-add=$group_id")
done

container_id=''
cleanup() {
    local original_status="$?"
    trap - EXIT
    if [[ -n "$container_id" ]]; then
        if ! "${docker_host[@]}" rm -f "$container_id" >/dev/null; then
            printf 'Cleanup failed; inspect exact container ID %s.\n' "$container_id" >&2
            exit 1
        fi
    fi
    exit "$original_status"
}
trap cleanup EXIT

# An image ID is local-only, exact, and cannot trigger a registry pull.
created_id="$("${docker_host[@]}" create \
    --runtime=nvidia --network=none --read-only --cap-drop=ALL \
    --security-opt=no-new-privileges --pids-limit=64 \
    --memory=805306368 --memory-swap=805306368 \
    --user=65532:65532 "${group_args[@]}" \
    --tmpfs=/tmp:rw,nosuid,nodev,size=16m \
    --mount="type=bind,src=$binary,dst=/hyperlooms-smoke,readonly" \
    --entrypoint=/hyperlooms-smoke "$image_id")"
if [[ ! "$created_id" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'Docker returned no exact container ID; inspect the daemon.\n' >&2
    exit 1
fi
container_id="$created_id"

if ! output="$(timeout 30s "${docker_host[@]}" start -a "$container_id" 2>&1)"; then
    printf '%s\nContainer start or workload failed.\n' "$output" >&2
    exit 1
fi
printf '%s\n' "$output"
exit_code="$("${docker_host[@]}" inspect --format '{{.State.ExitCode}}' "$container_id")"
if [[ "$exit_code" != '0' || "$output" != *'GPU_WORKLOAD_PASS: 256 values verified on NVIDIA Tegra X1'* ]]; then
    printf 'Restricted CUDA smoke did not verify (exit=%s).\n' "$exit_code" >&2
    exit 1
fi
if ! "${docker_host[@]}" rm -f "$container_id" >/dev/null; then
    printf 'Workload passed but cleanup failed for exact ID %s.\n' "$container_id" >&2
    exit 1
fi
container_id=''
printf 'JETSON_RESTRICTED_CUDA_SMOKE_PASS: non-root GPU workload verified and container removed\n'
