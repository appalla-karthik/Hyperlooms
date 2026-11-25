#!/usr/bin/env bash
# Read-only Jetson compatibility inventory. No sudo, containers, or configuration changes.
set -u
export LC_ALL=C

printf 'hyperlooms-jetson-host-survey/v1\n'
printf 'Note: this is host inventory, not GPU execution qualification.\n'

value_or_missing() {
    local label="$1"
    local path="$2"
    printf '%s=' "$label"
    if [[ -r "$path" ]]; then
        tr -d '\000' < "$path" | head -c 240
        printf '\n'
    else
        printf 'unavailable\n'
    fi
}

package_version() {
    local package="$1"
    local version
    if command -v dpkg-query >/dev/null 2>&1; then
        version="$(dpkg-query -W -f='${Version}' "$package" 2>/dev/null)"
    else
        version=''
    fi
    printf 'package.%s=%s\n' "$package" "${version:-not-installed-or-unavailable}"
}

printf '\n[platform]\n'
printf 'kernel_arch=%s\n' "$(uname -m)"
printf 'kernel_release=%s\n' "$(uname -r)"
value_or_missing 'device_model' /proc/device-tree/model
if [[ -r /etc/os-release ]]; then
    grep -m 1 '^PRETTY_NAME=' /etc/os-release || true
fi
printf 'l4t_release='
if [[ -r /etc/nv_tegra_release ]]; then
    head -n 1 /etc/nv_tegra_release | head -c 240
    printf '\n'
else
    printf 'unavailable\n'
fi

printf '\n[installed_packages]\n'
for package in nvidia-jetpack nvidia-l4t-core nvidia-container-runtime nvidia-container-toolkit libnvidia-container1; do
    package_version "$package"
done

printf '\n[resources]\n'
if command -v free >/dev/null 2>&1; then
    free -m
else
    printf 'free=unavailable\n'
fi
if command -v df >/dev/null 2>&1; then
    printf 'root_disk_size_available_mib='
    df -m --output=size,avail / | tail -n 1 | xargs
fi

printf '\n[gpu_interfaces]\n'
for tool in nvidia-smi tegrastats nvcc; do
    if command -v "$tool" >/dev/null 2>&1; then
        printf '%s=present\n' "$tool"
    else
        printf '%s=absent\n' "$tool"
    fi
done
for device in /dev/nvhost-gpu /dev/nvhost-ctrl-gpu /dev/nvmap; do
    if [[ -e "$device" ]]; then
        printf '%s=present\n' "$device"
    else
        printf '%s=absent\n' "$device"
    fi
done

printf '\n[docker]\n'
if command -v docker >/dev/null 2>&1; then
    docker --version 2>&1 | head -c 240
    printf '\n'
    if command -v timeout >/dev/null 2>&1; then
        docker_probe=(timeout 10s docker)
    else
        docker_probe=(docker)
    fi
    server="$("${docker_probe[@]}" info --format '{{.OSType}}/{{.Architecture}}' 2>/dev/null)"
    runtimes="$("${docker_probe[@]}" info --format '{{range $name, $_ := .Runtimes}}{{$name}} {{end}}' 2>/dev/null)"
    printf 'server_platform=%s\n' "${server:-unavailable-or-access-denied}"
    printf 'runtime_names=%s\n' "${runtimes:-unavailable-or-access-denied}"
    printf 'daemon_locality=not-verified\n'
else
    printf 'docker=not-installed-or-not-on-PATH\n'
fi

printf '\nNo secrets, network addresses, serial numbers, or workloads were requested.\n'
