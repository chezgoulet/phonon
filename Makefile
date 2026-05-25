.PHONY: all lint test build cross-build clean docker

all: lint test build

lint:
	golangci-lint run ./...
	go vet ./...

test:
	go test -race -count=1 ./...

build:
	CGO_ENABLED=0 go build -ldflags="-s -w" -o bin/phonon-coordinator ./cmd/phonon-coordinator

cross-build:
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o bin/phonon-coordinator-arm64 ./cmd/phonon-coordinator
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o bin/phonon-coordinator-amd64 ./cmd/phonon-coordinator

docker:
	docker build -t phonon-coordinator:latest .

web-build:
	cd web && npm ci && npm run build && mkdir -p ../internal/web/dist && cp -r dist/* ../internal/web/dist/

clean:
	rm -rf bin/ internal/web/dist/
