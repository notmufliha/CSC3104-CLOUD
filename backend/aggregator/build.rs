fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Aggregator only needs the server side (gateway is the client).
    tonic_build::configure()
        .build_client(false)
        .compile(&["../proto/scam_detect.proto"], &["../proto"])?;
    Ok(())
}
