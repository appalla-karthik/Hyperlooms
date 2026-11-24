FROM golang:1.27.4-alpine AS build
WORKDIR /workspace
COPY go.mod ./
COPY go.sum ./
COPY cmd ./cmd
COPY internal ./internal
COPY gen ./gen
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/hyperlooms-agent ./cmd/hyperlooms-agent

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/hyperlooms-agent /hyperlooms-agent
USER nonroot:nonroot
EXPOSE 9091
ENTRYPOINT ["/hyperlooms-agent"]
