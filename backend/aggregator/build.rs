fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Server side for AggregatorService (the gateway is its client) + client side for BertService (the
    // second opinion). tonic generates both for every service; the unused ones cost nothing.
    tonic_build::configure()
        .build_client(true)
        .compile(&["../proto/scam_detect.proto"], &["../proto"])?;
    Ok(())
}
