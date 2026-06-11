# Build stage
FROM golang:1.23-alpine AS builder

RUN apk add --no-cache gcc musl-dev

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Build web UI first (if ui/src exists)
RUN \
    if [ -d ui/src ]; then \
      apk add --no-cache nodejs npm && \
      cd ui && npm ci && npm run build && \
      cp -r dist ../cmd/phonon-coordinator/static/; \
    fi

RUN CGO_ENABLED=0 go build \
    -ldflags="-s -w" \
    -o /phonon-coordinator \
    ./cmd/phonon-coordinator

# Runtime stage
FROM scratch

COPY --from=builder /phonon-coordinator /phonon-coordinator

EXPOSE 8080

ENTRYPOINT ["/phonon-coordinator"]
