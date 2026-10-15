use avf_daemon::{serve, AppConfig};
use std::error::Error;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config = AppConfig::load()?;
    tracing::info!(
        bind = %config.bind_addr,
        roots = config.workspace_roots.len(),
        "starting AVF Studio daemon"
    );
    serve(config).await?;
    Ok(())
}
