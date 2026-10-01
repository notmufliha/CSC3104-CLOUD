FROM rust:1.77-slim AS builder
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared with the aggregator and lives at backend/proto.
WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler && rm -rf /var/lib/apt/lists/*

COPY proto ./proto
COPY gateway/Cargo.toml gateway/build.rs ./gateway/
COPY gateway/src ./gateway/src
WORKDIR /build/gateway
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/gateway/target/release/gateway /usr/local/bin/gateway
EXPOSE 8080
CMD ["gateway"]
