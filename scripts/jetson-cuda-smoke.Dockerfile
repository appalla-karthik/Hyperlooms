# Operator-witnessed diagnostic image for the original Jetson Nano only.
# The tiny base must already be cached; build with --pull=false --network=none.
FROM ubuntu@sha256:f97a5103cca28097326814718e711c9c41b54853c26959d73495e40b1dd608f2

# Jetson's NVIDIA runtime bind-mounts the host driver into this directory.
# Generic Ubuntu lacks the libcuda.so.1 loader link/configuration supplied by
# L4T images. No driver or CUDA toolkit is copied into this image.
RUN mkdir -p /usr/lib/aarch64-linux-gnu/tegra \
    && ln -s libcuda.so.1.1 /usr/lib/aarch64-linux-gnu/tegra/libcuda.so.1

ENV LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu/tegra
ENV NVIDIA_VISIBLE_DEVICES=all
ENV NVIDIA_DRIVER_CAPABILITIES=compute,utility

LABEL dev.hyperlooms.jetson.cuda-smoke="v1"
