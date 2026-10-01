fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Gateway only needs client stubs (it calls workers + aggregator), but
    // tonic_build emits both client and server code from the same proto;
    // unused server code is simply not instantiated.
    tonic_build::configure()
        .build_server(false)
        .compile(&["../proto/scam_detect.proto"], &["../proto"])?;
    Ok(())
}
