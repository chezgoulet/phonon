# Build stage
FROM golang:1.23-alpine AS builder

RUN apk add --no-cache gcc musl-dev

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Build web UI first (if web/src exists)
RUN --mount=type=bind,target=/src \
    if [ -d web/src ]; then \
      apk add --no-cache nodejs npm && \
      cd web && npm ci && npm run build && \
      cp -r dist ../internal/web/dist; \
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
