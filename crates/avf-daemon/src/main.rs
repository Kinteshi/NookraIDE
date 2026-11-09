#[cfg(all(feature = "native-protocol", unix))]
use avf_daemon::git_askpass_helper;
use avf_daemon::{serve, AppConfig};
use std::error::Error;
use tracing_subscriber::EnvFilter;

mod tools;

fn main() -> Result<(), Box<dyn Error>> {
    // `guest …` runs as root through sudo and starts nothing: no temp dir, runtime or logging.
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().is_some_and(|arg| arg == "guest") {
        if let Err(error) = avf_daemon::forwarder::run_cli(&args[1..]) {
            eprintln!("avf-daemon: {error}");
            std::process::exit(2);
        }
        return Ok(());
    }
    // Before the runtime starts any thread: this changes the environment children inherit.
    let disk_tmpdir = avf_daemon::tmpdir::prefer_disk_tmpdir();
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?
        .block_on(run(disk_tmpdir))
}

async fn run(disk_tmpdir: Option<std::path::PathBuf>) -> Result<(), Box<dyn Error>> {
    #[cfg(all(feature = "native-protocol", unix))]
    {
        let mut args_os = std::env::args_os();
        let invoked_as_askpass = args_os.next().is_some_and(|path| {
            std::path::PathBuf::from(path)
                .file_name()
                .is_some_and(|name| name == "askpass")
        });
        let internal_askpass = args_os.next().is_some_and(|arg| arg == "__git-askpass");
        if invoked_as_askpass || internal_askpass {
            if let Err(error) = git_askpass_helper() {
                eprintln!("avf-daemon askpass: {error}");
                std::process::exit(1);
            }
            return Ok(());
        }
    }
    let args: Vec<String> = std::env::args().skip(1).collect();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    if let Some(dir) = &disk_tmpdir {
        tracing::info!(dir = %dir.display(), "/tmp is a tmpfs in memory; programs started here use a temp dir on disk");
    }

    if args.first().is_some_and(|arg| arg == "pair") {
        if let Err(error) = avf_daemon::print_pairing_code(&args[1..]) {
            eprintln!("avf-daemon: {error}");
            std::process::exit(2);
        }
        return Ok(());
    }
    if let Some(command) = args.first().filter(|arg| *arg == "devices" || *arg == "log") {
        if let Err(error) = avf_daemon::run_devices_cli(command, &args[1..]) {
            eprintln!("avf-daemon: {error}");
            std::process::exit(2);
        }
        return Ok(());
    }
    if args.first().is_some_and(|arg| arg == "tools") {
        if let Err(error) = tools::run_cli(&args[1..]) {
            eprintln!("avf-daemon: {error}");
            std::process::exit(2);
        }
        return Ok(());
    }

    let config = AppConfig::load()?;
    tracing::info!(
        bind = %config.bind_addr,
        roots = config.workspace_roots.len(),
        "starting QuireCode daemon"
    );
    serve(config).await?;
    Ok(())
}
