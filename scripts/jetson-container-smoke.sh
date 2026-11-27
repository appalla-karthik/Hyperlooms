#!/usr/bin/env bash
# Experimental, operator-witnessed diagnostic only. Never enrolls a provider.
# Requires a locally cached, separately reviewed ARM64/L4T-compatible image.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    printf 'Usage: %s <reviewed-image@sha256:digest> <compiled-cuda-smoke-binary>\n' "$0" >&2
    exit 2
fi

image="$1"
binary="$2"
if [[ ! "$image" =~ ^[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}$ ]]; then
    printf 'Image must be an exact repository@sha256:digest reference.\n' >&2
    exit 2
fi
if [[ "$(uname -m)" != 'aarch64' ]]; then
    printf 'This smoke is only for an ARM64 Jetson host.\n' >&2
    exit 2
fi
if [[ ! -r /proc/device-tree/model ]] ||
    [[ "$(tr -d '\000' < /proc/device-tree/model)" != 'NVIDIA Jetson Nano Developer Kit' ]]; then
    printf 'This smoke targets the surveyed original Jetson Nano Developer Kit only.\n' >&2
    exit 2
fi
if [[ ! -r /etc/nv_tegra_release ]] ||
    [[ "$(head -n 1 /etc/nv_tegra_release)" != '# R32 (release), REVISION: 7.6,'* ]]; then
    printf 'L4T R32.7.6 is required for this surveyed-host smoke.\n' >&2
    exit 2
fi
if [[ ! -f "$binary" || ! -x "$binary" ]]; then
    printf 'CUDA smoke binary is missing or not executable.\n' >&2
    exit 2
fi
binary="$(readlink -f -- "$binary")"
if [[ "$binary" == *','* || "$binary" == *$'\n'* || "$binary" == *$'\r'* ]]; then
    printf 'CUDA smoke binary path cannot be safely mounted.\n' >&2
    exit 2
fi

# This script never invokes sudo itself. Run as the explicitly chosen Docker
# operator. Docker socket access is privileged; review the image and script.
server="$(docker -H unix:///var/run/docker.sock info --format '{{.OSType}}/{{.Architecture}}')"
if [[ "$server" != 'linux/aarch64' ]]; then
    printf 'Expected local linux/aarch64 Docker daemon, got %s.\n' "$server" >&2
    exit 1
fi
runtimes="$(docker -H unix:///var/run/docker.sock info --format '{{range $name, $_ := .Runtimes}}{{$name}} {{end}}')"
if [[ " $runtimes " != *' nvidia '* ]]; then
    printf 'NVIDIA runtime is not registered with this Docker daemon.\n' >&2
    exit 1
fi
platform="$(docker -H unix:///var/run/docker.sock image inspect --format '{{.Os}}/{{.Architecture}}' "$image")"
if [[ "$platform" != 'linux/arm64' ]]; then
    printf 'Cached image must be linux/arm64, got %s.\n' "$platform" >&2
    exit 1
fi

container_id=''
cleanup() {
    local original_status="$?"
    trap - EXIT
    if [[ -n "$container_id" ]]; then
        if ! docker -H unix:///var/run/docker.sock rm -f "$container_id" >/dev/null; then
            printf 'Container cleanup failed; operator must inspect exact ID %s.\n' "$container_id" >&2
            exit 1
        fi
    fi
    exit "$original_status"
}
trap cleanup EXIT

created_id="$(docker -H unix:///var/run/docker.sock create \
    --pull=never --platform=linux/arm64 --runtime=nvidia \
    --network=none --read-only --cap-drop=ALL \
    --security-opt=no-new-privileges=true --pids-limit=64 \
    --memory=536870912 --memory-swap=536870912 \
    --user=65532:65532 --tmpfs=/tmp:rw,nosuid,nodev,size=16m \
    --mount="type=bind,src=$binary,dst=/hyperlooms-smoke,readonly" \
    --entrypoint=/hyperlooms-smoke "$image")"
if [[ ! "$created_id" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'Docker did not return an exact container ID; inspect the daemon before retrying.\n' >&2
    exit 1
fi
container_id="$created_id"

output="$(timeout 30s docker -H unix:///var/run/docker.sock start -a "$container_id")"
printf '%s\n' "$output"
exit_code="$(docker -H unix:///var/run/docker.sock inspect --format '{{.State.ExitCode}}' "$container_id")"
if [[ "$exit_code" != '0' || "$output" != *'GPU_WORKLOAD_PASS'* ]]; then
    printf 'Container CUDA smoke did not pass (exit=%s).\n' "$exit_code" >&2
    exit 1
fi
if ! docker -H unix:///var/run/docker.sock rm -f "$container_id" >/dev/null; then
    printf 'Container CUDA smoke passed, but cleanup failed for exact ID %s.\n' "$container_id" >&2
    exit 1
fi
container_id=''
printf 'JETSON_CONTAINER_GPU_SMOKE_PASS: workload verified and container removed\n'
