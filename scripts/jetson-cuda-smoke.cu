// Tiny CUDA diagnostic for the original Jetson Nano (SM 5.3).
// It can run on the host or in a test container. It does not test PyTorch,
// Hyperlooms scheduling, artifact exchange, or production isolation.
#include <cuda_runtime.h>
#include <stdio.h>

#define CHECK(call) do {                                                     \
    cudaError_t error = (call);                                              \
    if (error != cudaSuccess) {                                              \
        fprintf(stderr, "%s: %s\n", #call, cudaGetErrorString(error));     \
        return 1;                                                            \
    }                                                                        \
} while (0)

__global__ void square(int *output) {
    const int index = threadIdx.x;
    output[index] = index * index;
}

int main(void) {
    int count = 0;
    int result[256];
    int *deviceResult = NULL;
    cudaDeviceProp properties;

    CHECK(cudaGetDeviceCount(&count));
    if (count < 1) {
        fprintf(stderr, "No CUDA GPU found\n");
        return 1;
    }
    CHECK(cudaGetDeviceProperties(&properties, 0));
    CHECK(cudaMalloc((void **)&deviceResult, sizeof(result)));
    square<<<1, 256>>>(deviceResult);
    CHECK(cudaGetLastError());
    CHECK(cudaDeviceSynchronize());
    CHECK(cudaMemcpy(result, deviceResult, sizeof(result), cudaMemcpyDeviceToHost));

    for (int index = 0; index < 256; ++index) {
        if (result[index] != index * index) {
            fprintf(stderr, "Incorrect GPU result at index %d\n", index);
            cudaFree(deviceResult);
            return 1;
        }
    }
    CHECK(cudaFree(deviceResult));
    printf("GPU_WORKLOAD_PASS: 256 values verified on %s\n", properties.name);
    return 0;
}
