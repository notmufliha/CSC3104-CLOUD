FROM rust:1.77-slim AS builder
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared with the gateway and lives at backend/proto.
WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler && rm -rf /var/lib/apt/lists/*

COPY proto ./proto
COPY aggregator/Cargo.toml aggregator/build.rs ./aggregator/
COPY aggregator/src ./aggregator/src
WORKDIR /build/aggregator
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/aggregator/target/release/aggregator /usr/local/bin/aggregator
EXPOSE 50052 8082
CMD ["aggregator"]
