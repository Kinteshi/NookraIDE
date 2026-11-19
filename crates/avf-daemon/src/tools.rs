use serde::{Deserialize, Serialize};
use std::{
    env, fs,
    io::Write,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::atomic::{AtomicU64, Ordering},
};

const CATALOG_JSON: &str = include_str!("../../../guest/tools/catalog.json");
/// Scripts the daemon ships itself, installed by the `script` method.
const BUNDLED_SCRIPTS: &[(&str, &str)] = &[
    (
        "avf-jupyter-bridge.py",
        include_str!("../../../guest/tools/avf-jupyter-bridge.py"),
    ),
    ("avf-debugpy.py", include_str!("../../../guest/tools/avf-debugpy.py")),
    (
        "avf-node-agent.sh",
        include_str!("../../../guest/tools/avf-node-agent.sh"),
    ),
    (
        "avf-codelldb.py",
        include_str!("../../../guest/tools/avf-codelldb.py"),
    ),
];
const UV_INSTALL_GUIDANCE: &str =
    "Install uv (https://docs.astral.sh/uv/getting-started/installation/) and rerun `avf-daemon tools install";
static TEMP_FILE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ToolManifest {
    pub name: String,
    pub version: String,
    pub kind: String,
    pub languages: Vec<String>,
    pub command: Vec<String>,
    pub install: InstallSpec,
    #[serde(rename = "settingsSection")]
    pub settings_section: String,
    #[serde(rename = "env_allowlist")]
    pub env_allowlist: Vec<String>,
    #[serde(rename = "initializationOptions")]
    pub initialization_options: serde_json::Value,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct InstallSpec {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub apt: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uv: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub npm: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cargo: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub download: Option<DownloadSpec>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub script: Option<ScriptSpec>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ScriptSpec {
    /// Executable name installed into `~/.local/bin`; must equal the manifest command.
    pub binary: String,
    /// Name of a script bundled into the daemon binary.
    pub asset: String,
    /// Arguments the installed script runs with once, to finish the install (fetch what it
    /// launches); output goes to the installer's terminal.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub setup: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct DownloadSpec {
    /// Executable name installed into `~/.local/bin`; must equal the manifest command.
    pub binary: String,
    /// Path of the executable inside the gzip tarball.
    pub archive_member: String,
    /// Per-architecture artifacts keyed by `std::env::consts::ARCH`.
    pub artifacts: std::collections::BTreeMap<String, DownloadArtifact>,
    /// Keep the member's whole directory in the tool's own folder (for executables that need
    /// files beside them, like TypeScript's `lib.*.d.ts`); `binary` then names a launcher put in
    /// `~/.local/bin`, which may differ from the member's file name.
    #[serde(default)]
    pub keep_directory: bool,
    /// What the download is: a gzip tarball (the default), one gzipped executable, or the
    /// executable itself.
    #[serde(default)]
    pub format: DownloadFormat,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum DownloadFormat {
    #[default]
    TarGz,
    Gzip,
    Raw,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct DownloadArtifact {
    pub url: String,
    pub sha256: String,
    /// Overrides the spec's `archive_member` when archives differ per architecture
    /// (e.g. ripgrep's `ripgrep-<version>-<target>/rg`).
    #[serde(default)]
    pub archive_member: Option<String>,
}

impl DownloadSpec {
    fn member_for<'a>(&'a self, artifact: &'a DownloadArtifact) -> &'a str {
        artifact.archive_member.as_deref().unwrap_or(&self.archive_member)
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Catalog {
    schema_version: u32,
    pub(crate) tools: Vec<ToolManifest>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum ToolCommand {
    List,
    Catalog,
    Install(String),
    Remove(String),
}

pub fn parse_cli(args: &[String]) -> Result<ToolCommand, String> {
    match args {
        [action] if action == "list" => Ok(ToolCommand::List),
        [action] if action == "catalog" => Ok(ToolCommand::Catalog),
        [action, name] if action == "install" => Ok(ToolCommand::Install(name.clone())),
        [action, name] if action == "remove" => Ok(ToolCommand::Remove(name.clone())),
        [action] if action == "--help" || action == "-h" || action == "help" => {
            Err(tool_usage().to_owned())
        }
        _ => Err(tool_usage().to_owned()),
    }
}

pub fn run_cli(args: &[String]) -> Result<(), String> {
    match parse_cli(args)? {
        ToolCommand::List => {
            let installed = list_installed(&default_home()?)?;
            if installed.is_empty() {
                println!("No guest tools are installed.");
            } else {
                for tool in installed {
                    println!("{} {}", tool.name, tool.version);
                }
            }
        }
        ToolCommand::Catalog => {
            let catalog = load_catalog()?;
            println!(
                "{}",
                serde_json::to_string_pretty(&catalog.tools).map_err(|e| e.to_string())?
            );
        }
        ToolCommand::Install(name) => {
            let manifest = install_tool(&default_home()?, &name)?;
            println!("Installed {} {}.", manifest.name, manifest.version);
        }
        ToolCommand::Remove(name) => {
            let manifest = remove_tool(&default_home()?, &name)?;
            println!("Removed {} {}.", manifest.name, manifest.version);
        }
    }
    Ok(())
}

fn tool_usage() -> &'static str {
    "Usage: avf-daemon tools <list|catalog|install <name>|remove <name>>"
}

pub(crate) fn default_home() -> Result<PathBuf, String> {
    env::var_os("HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .ok_or_else(|| "HOME must be set to an absolute directory path.".to_owned())
}

pub(crate) fn load_catalog() -> Result<Catalog, String> {
    let catalog: Catalog = serde_json::from_str(CATALOG_JSON)
        .map_err(|e| format!("The bundled guest tool catalog is invalid: {e}"))?;
    if catalog.schema_version != 1 {
        return Err(format!(
            "Unsupported guest tool catalog schema version {}.",
            catalog.schema_version
        ));
    }
    let mut names = std::collections::HashSet::new();
    for manifest in &catalog.tools {
        validate_manifest(manifest)?;
        if !names.insert(manifest.name.as_str()) {
            return Err(format!(
                "The bundled guest tool catalog contains duplicate tool '{}'.",
                manifest.name
            ));
        }
    }
    Ok(catalog)
}

fn catalog_lookup(name: &str) -> Result<ToolManifest, String> {
    validate_tool_name(name)?;
    load_catalog()?
        .tools
        .into_iter()
        .find(|tool| tool.name == name)
        .ok_or_else(|| format!("Tool '{name}' is not in the bundled allowlisted catalog."))
}

fn validate_tool_name(name: &str) -> Result<(), String> {
    let valid = !name.is_empty()
        && name.len() <= 64
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && name.as_bytes()[0].is_ascii_lowercase()
        && name.as_bytes()[name.len() - 1].is_ascii_alphanumeric();
    if valid {
        Ok(())
    } else {
        Err(format!("Invalid tool name '{name}'."))
    }
}

pub fn validate_manifest(manifest: &ToolManifest) -> Result<(), String> {
    validate_tool_name(&manifest.name)?;
    if manifest.version.trim().is_empty() || manifest.version.len() > 128 {
        return Err(format!("Tool '{}' has an invalid version.", manifest.name));
    }
    if !matches!(
        manifest.kind.as_str(),
        "lsp" | "dap" | "formatter" | "linter" | "acp-agent" | "kernel" | "cli"
    ) {
        return Err(format!(
            "Tool '{}' has unsupported kind '{}'.",
            manifest.name, manifest.kind
        ));
    }
    if manifest.command.is_empty()
        || manifest.command[0].is_empty()
        || manifest.command[0]
            .chars()
            .any(|character| matches!(character, '/' | '\\' | '\0'))
        || manifest.command[0] == "."
        || manifest.command[0] == ".."
        || manifest.command.iter().any(|part| part.contains('\0'))
    {
        return Err(format!(
            "Tool '{}' must use a bare executable name, not an executable path.",
            manifest.name
        ));
    }
    if manifest.settings_section.trim().is_empty() || !manifest.initialization_options.is_object() {
        return Err(format!(
            "Tool '{}' has invalid settings or initialization options.",
            manifest.name
        ));
    }
    for variable in &manifest.env_allowlist {
        let mut bytes = variable.bytes();
        let first = bytes.next();
        if !first.is_some_and(|b| b.is_ascii_uppercase() || b == b'_')
            || !bytes.all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
        {
            return Err(format!(
                "Tool '{}' has invalid environment allowlist entry '{variable}'.",
                manifest.name
            ));
        }
    }
    validate_install_spec(&manifest.name, &manifest.install)
}

fn validate_install_spec(name: &str, install: &InstallSpec) -> Result<(), String> {
    let package_lists = [
        install.apt.as_ref(),
        install.uv.as_ref(),
        install.npm.as_ref(),
        install.cargo.as_ref(),
    ];
    let configured = package_lists
        .iter()
        .filter(|option| option.is_some())
        .count()
        + usize::from(install.download.is_some())
        + usize::from(install.script.is_some());
    if configured != 1 {
        return Err(format!(
            "Tool '{name}' must declare exactly one supported install method."
        ));
    }
    for packages in package_lists.into_iter().flatten() {
        if packages.is_empty()
            || packages.iter().any(|package| {
                package.trim().is_empty()
                    || package.starts_with('-')
                    || package.len() > 256
                    || package.contains('\0')
            })
        {
            return Err(format!(
                "Tool '{name}' has an invalid package install list."
            ));
        }
    }
    if let Some(download) = &install.download {
        validate_download_spec(name, download)?;
    }
    if let Some(script) = &install.script {
        if validate_tool_name(&script.binary).is_err() || bundled_script(&script.asset).is_none() {
            return Err(format!(
                "Tool '{name}' script install needs a bare executable name and a bundled script."
            ));
        }
        if script.setup.len() > 8
            || script.setup.iter().any(|argument| {
                argument.is_empty() || argument.len() > 64 || argument.contains('\0')
            })
        {
            return Err(format!("Tool '{name}' has invalid script setup arguments."));
        }
    }
    Ok(())
}

fn bundled_script(asset: &str) -> Option<&'static str> {
    BUNDLED_SCRIPTS
        .iter()
        .find(|(name, _)| *name == asset)
        .map(|(_, contents)| *contents)
}

fn validate_download_spec(name: &str, download: &DownloadSpec) -> Result<(), String> {
    if validate_tool_name(&download.binary).is_err() {
        return Err(format!(
            "Tool '{name}' download must install a bare executable name."
        ));
    }
    let member_is_safe = |member_path: &str| {
        let member = Path::new(member_path);
        !member_path.is_empty()
            && !member_path.contains('\0')
            && member.is_relative()
            && member
                .components()
                .all(|component| matches!(component, std::path::Component::Normal(_)))
            && (download.keep_directory
                || member
                    .file_name()
                    .is_some_and(|file| file == download.binary.as_str()))
            && (!download.keep_directory || member.parent().is_some_and(|parent| !parent.as_os_str().is_empty()))
    };
    let members = std::iter::once(download.archive_member.as_str())
        .chain(download.artifacts.values().filter_map(|artifact| artifact.archive_member.as_deref()));
    for member in members {
        if !member_is_safe(member) {
            return Err(format!(
                "Tool '{name}' download archive member must be a relative path to '{}'.",
                download.binary
            ));
        }
    }
    if download.artifacts.is_empty() {
        return Err(format!("Tool '{name}' download declares no artifacts."));
    }
    for (arch, artifact) in &download.artifacts {
        let valid_hash = artifact.sha256.len() == 64
            && artifact
                .sha256
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
        if !matches!(arch.as_str(), "x86_64" | "aarch64")
            || !artifact.url.starts_with("https://")
            || !valid_hash
        {
            return Err(format!(
                "Tool '{name}' download artifacts require a supported architecture, an HTTPS URL, \
                 and a lowercase 64-digit SHA-256."
            ));
        }
    }
    Ok(())
}

pub fn install_tool(home: &Path, name: &str) -> Result<ToolManifest, String> {
    install_tool_with(home, name, |manifest| install_from_catalog(home, manifest))
}

fn install_tool_with(
    home: &Path,
    name: &str,
    installer: impl FnOnce(&ToolManifest) -> Result<(), String>,
) -> Result<ToolManifest, String> {
    let manifest = catalog_lookup(name)?;
    if !supports_tool_installation_platform(std::env::consts::OS, std::env::consts::ARCH) {
        return Err(
            "Guest tool installation is supported only on Linux x86_64 and aarch64.".into(),
        );
    }
    installer(&manifest)?;
    let tools_root = ensure_tools_root(home)?;
    let tool_dir = tools_root.join(&manifest.name);
    ensure_directory(&tool_dir)?;
    let manifest_path = tool_dir.join("manifest.json");
    write_manifest_atomically(&manifest_path, &manifest).map_err(|error| {
        format!(
            "The installer completed, but the tool manifest could not be registered at {}: {error}",
            manifest_path.display()
        )
    })?;
    Ok(manifest)
}

/// Undoes [`install_tool`]: the tool's files go (what its package manager installed, its download
/// or its script) and then its registry entry, so the app no longer offers it as installed. A
/// launcher script other installed tools share stays until the last of them goes.
pub fn remove_tool(home: &Path, name: &str) -> Result<ToolManifest, String> {
    remove_tool_with(home, name, |manifest, others| {
        uninstall_from_catalog(home, manifest, others)
    })
}

fn remove_tool_with(
    home: &Path,
    name: &str,
    uninstaller: impl FnOnce(&ToolManifest, &[ToolManifest]) -> Result<(), String>,
) -> Result<ToolManifest, String> {
    let manifest = catalog_lookup(name)?;
    let installed = list_installed(home)?;
    if !installed.iter().any(|tool| tool.name == manifest.name) {
        return Err(format!("'{}' is not installed.", manifest.name));
    }
    let others = installed
        .into_iter()
        .filter(|tool| tool.name != manifest.name)
        .collect::<Vec<_>>();
    uninstaller(&manifest, &others)?;
    let tool_dir = ensure_tools_root(home)?.join(&manifest.name);
    ensure_directory(&tool_dir)?;
    fs::remove_dir_all(&tool_dir).map_err(|error| {
        format!(
            "Removed '{}', but its registry entry {} could not be deleted: {error}",
            manifest.name,
            tool_dir.display()
        )
    })?;
    Ok(manifest)
}

fn uninstall_from_catalog(
    home: &Path,
    manifest: &ToolManifest,
    others: &[ToolManifest],
) -> Result<(), String> {
    let name = &manifest.name;
    if let Some(download) = &manifest.install.download {
        // A kept directory lives in the registry entry, which goes next.
        return remove_from_local_bin(home, name, &download.binary);
    }
    if let Some(script) = &manifest.install.script {
        let bin_dir = local_bin_directory(home)?;
        if let Some((_, rest)) = script.setup.split_first() {
            let mut command = Command::new(bin_dir.join(&script.binary));
            command.arg("remove").args(rest);
            run_uninstaller(name, command)?;
        }
        let shared = others.iter().any(|other| {
            other
                .install
                .script
                .as_ref()
                .is_some_and(|theirs| theirs.binary == script.binary)
        });
        return if shared {
            Ok(())
        } else {
            remove_from_local_bin(home, name, &script.binary)
        };
    }
    let command = uninstall_command(manifest)?;
    run_uninstaller(
        name,
        with_install_privileges(command, current_effective_uid()?),
    )
}

fn run_uninstaller(name: &str, mut command: Command) -> Result<(), String> {
    let program = command.get_program().to_string_lossy().into_owned();
    command.stdin(Stdio::null());
    let status = command
        .status()
        .map_err(|error| format!("Could not start {program} to remove '{name}': {error}."))?;
    if !status.success() {
        return Err(format!(
            "Removing '{name}' with {program} failed with {status}."
        ));
    }
    Ok(())
}

/// The package manager's own uninstall, for the packages [`install_command`] installed.
fn uninstall_command(manifest: &ToolManifest) -> Result<Command, String> {
    let (program, arguments, packages): (&str, &[&str], &Vec<String>) = match &manifest.install {
        InstallSpec {
            uv: Some(packages), ..
        } => ("uv", &["tool", "uninstall"], packages),
        InstallSpec {
            apt: Some(packages),
            ..
        } => ("apt-get", &["remove", "--yes"], packages),
        InstallSpec {
            npm: Some(packages),
            ..
        } => ("npm", &["uninstall", "--global"], packages),
        InstallSpec {
            cargo: Some(packages),
            ..
        } => ("cargo", &["uninstall"], packages),
        _ => {
            return Err(format!(
                "Tool '{}' has an invalid install method in the catalog.",
                manifest.name
            ))
        }
    };
    let mut command = Command::new(program);
    command
        .args(arguments)
        .args(packages.iter().map(|package| package_name(package)));
    Ok(command)
}

/// A package without its version (`ruff==0.6`, `@scope/name@1.2`): uninstalls take bare names.
fn package_name(package: &str) -> &str {
    let end = package
        .char_indices()
        .skip(1)
        .find(|&(_, c)| matches!(c, '@' | '=' | '<' | '>' | '~' | '!' | '[' | ' '))
        .map_or(package.len(), |(index, _)| index);
    &package[..end]
}

fn remove_from_local_bin(home: &Path, name: &str, binary: &str) -> Result<(), String> {
    let path = local_bin_directory(home)?.join(binary);
    match fs::symlink_metadata(&path) {
        Ok(metadata) if metadata.is_file() => fs::remove_file(&path)
            .map_err(|error| format!("Cannot remove '{name}' from {}: {error}", path.display())),
        Ok(_) => Err(format!(
            "Refusing to remove non-regular file {}.",
            path.display()
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("Cannot inspect {}: {error}", path.display())),
    }
}

fn supports_tool_installation_platform(os: &str, arch: &str) -> bool {
    os == "linux" && matches!(arch, "x86_64" | "aarch64")
}

fn install_from_catalog(home: &Path, manifest: &ToolManifest) -> Result<(), String> {
    if let Some(download) = &manifest.install.download {
        return install_download(home, &manifest.name, download, std::env::consts::ARCH);
    }
    if let Some(script) = &manifest.install.script {
        return install_script(home, &manifest.name, script);
    }
    let command = install_command(manifest)?;
    let effective_uid = current_effective_uid()?;
    let mut command = with_install_privileges(command, effective_uid);
    let manager = command.get_program().to_string_lossy().into_owned();
    command.stdin(Stdio::null());
    let status = command.status().map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            if manager == "uv" {
                uv_not_found_message(&manifest.name)
            } else {
                format!(
                    "Cannot install '{}': executable '{manager}' was not found on PATH.",
                    manifest.name
                )
            }
        } else {
            format!(
                "Could not start {manager} to install '{}': {error}.",
                manifest.name,
            )
        }
    })?;
    if !status.success() {
        return Err(format!(
            "{manager} install for '{}' failed with {status}.",
            manifest.name,
        ));
    }
    Ok(())
}

fn current_effective_uid() -> Result<u32, String> {
    let status = fs::read_to_string("/proc/self/status")
        .map_err(|error| format!("Could not determine effective user ID: {error}"))?;
    let uid = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .and_then(|line| line.split_whitespace().nth(1))
        .ok_or_else(|| {
            "Could not determine effective user ID from /proc/self/status.".to_owned()
        })?;
    uid.parse()
        .map_err(|error| format!("Could not parse effective user ID: {error}"))
}

fn with_install_privileges(command: Command, effective_uid: u32) -> Command {
    if effective_uid == 0 || command.get_program() != "apt-get" {
        return command;
    }
    let arguments = command
        .get_args()
        .map(std::ffi::OsStr::to_os_string)
        .collect::<Vec<_>>();
    let mut elevated = Command::new("sudo");
    elevated.args(["-n", "--", "apt-get"]).args(arguments);
    elevated
}

fn install_command(manifest: &ToolManifest) -> Result<Command, String> {
    let command = match &manifest.install {
        InstallSpec {
            uv: Some(packages), ..
        } => {
            let mut command = Command::new("uv");
            command
                .args(["tool", "install", "--upgrade"])
                .args(packages);
            command
        }
        InstallSpec {
            apt: Some(packages),
            ..
        } => {
            let mut command = Command::new("apt-get");
            command
                .args(["install", "--yes", "--no-install-recommends"])
                .args(packages);
            command
        }
        InstallSpec {
            npm: Some(packages),
            ..
        } => {
            let mut command = Command::new("npm");
            command.args(["install", "--global"]).args(packages);
            command
        }
        InstallSpec {
            cargo: Some(packages),
            ..
        } => {
            let mut command = Command::new("cargo");
            command.args(["install", "--locked"]).args(packages);
            command
        }
        InstallSpec {
            download: Some(_), ..
        }
        | InstallSpec {
            script: Some(_), ..
        } => {
            return Err(format!(
                "Tool '{}' uses a download install method, which has no package-manager command.",
                manifest.name
            ));
        }
        _ => {
            return Err(format!(
                "Tool '{}' has an invalid install method in the catalog.",
                manifest.name
            ));
        }
    };
    Ok(command)
}

fn uv_not_found_message(tool_name: &str) -> String {
    format!(
        "Cannot install '{tool_name}': executable 'uv' was not found on PATH. {UV_INSTALL_GUIDANCE} {tool_name}`."
    )
}

/// Downloads a pinned tarball with curl, verifies its SHA-256, and installs the
/// single executable it carries into `~/.local/bin`, which is on the daemon's PATH.
fn install_download(
    home: &Path,
    name: &str,
    download: &DownloadSpec,
    arch: &str,
) -> Result<(), String> {
    let artifact = download.artifacts.get(arch).ok_or_else(|| {
        format!("Tool '{name}' has no download artifact for the {arch} architecture.")
    })?;
    let staging = staging_directory(home, name)?;
    let result = (|| {
        let archive = staging.join("archive.tgz");
        let status = Command::new("curl")
            .args([
                "--fail",
                "--location",
                "--silent",
                "--show-error",
                "--proto",
                "=https",
                "--proto-redir",
                "=https",
                "--output",
            ])
            .arg(&archive)
            .arg(&artifact.url)
            .stdin(Stdio::null())
            .status()
            .map_err(|error| {
                if error.kind() == std::io::ErrorKind::NotFound {
                    format!(
                        "Cannot install '{name}': executable 'curl' was not found on PATH. \
                         Install it with `sudo apt-get install curl`."
                    )
                } else {
                    format!("Could not start curl to download '{name}': {error}.")
                }
            })?;
        if !status.success() {
            return Err(format!("Downloading '{name}' failed with {status}."));
        }
        install_downloaded_archive(home, name, download, artifact, &archive, &staging)
    })();
    let _ = fs::remove_dir_all(&staging);
    result
}

/// Writes a script bundled into the daemon into `~/.local/bin` as an executable.
fn install_script(home: &Path, name: &str, script: &ScriptSpec) -> Result<(), String> {
    let contents = bundled_script(&script.asset)
        .ok_or_else(|| format!("Tool '{name}' names an unknown bundled script."))?;
    let bin_dir = local_bin_directory(home)?;
    install_executable(name, &bin_dir, &script.binary, |staged| {
        fs::write(staged, contents)
            .map_err(|error| format!("Cannot write the '{name}' script: {error}"))
    })?;
    if script.setup.is_empty() {
        return Ok(());
    }
    let status = Command::new(bin_dir.join(&script.binary))
        .args(&script.setup)
        .stdin(Stdio::null())
        .status()
        .map_err(|error| format!("Could not run the '{name}' setup: {error}."))?;
    if !status.success() {
        return Err(format!("The '{name}' setup failed with {status}."));
    }
    Ok(())
}

fn local_bin_directory(home: &Path) -> Result<PathBuf, String> {
    let mut bin_dir = fs::canonicalize(home)
        .map_err(|error| format!("Cannot resolve home directory {}: {error}", home.display()))?;
    for component in [".local", "bin"] {
        bin_dir.push(component);
        ensure_directory(&bin_dir)?;
    }
    Ok(bin_dir)
}

/// Stages an executable next to its destination and renames it into place, so a failed
/// install never leaves a half-written binary on PATH.
fn install_executable(
    name: &str,
    bin_dir: &Path,
    binary: &str,
    write: impl FnOnce(&Path) -> Result<(), String>,
) -> Result<(), String> {
    let destination = bin_dir.join(binary);
    if fs::symlink_metadata(&destination).is_ok_and(|metadata| !metadata.is_file()) {
        return Err(format!(
            "Refusing to replace non-regular file {}.",
            destination.display()
        ));
    }
    let staged = bin_dir.join(format!(
        ".{binary}-{}-{}.tmp",
        std::process::id(),
        TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
    ));
    let result = (|| {
        write(&staged)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&staged, fs::Permissions::from_mode(0o755))
                .map_err(|error| format!("Cannot mark '{name}' executable: {error}"))?;
        }
        fs::rename(&staged, &destination).map_err(|error| {
            format!(
                "Cannot install '{name}' into {}: {error}",
                bin_dir.display()
            )
        })
    })();
    if result.is_err() {
        let _ = fs::remove_file(&staged);
    }
    result
}

fn staging_directory(home: &Path, name: &str) -> Result<PathBuf, String> {
    let tools_root = ensure_tools_root(home)?;
    for _ in 0..16 {
        let sequence = TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let candidate = tools_root.join(format!(
            ".download-{name}-{}-{sequence}",
            std::process::id()
        ));
        match fs::create_dir(&candidate) {
            Ok(()) => return Ok(candidate),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(format!("Cannot create download staging directory: {error}")),
        }
    }
    Err("Could not allocate a download staging directory.".to_owned())
}

fn install_downloaded_archive(
    home: &Path,
    name: &str,
    download: &DownloadSpec,
    artifact: &DownloadArtifact,
    archive: &Path,
    staging: &Path,
) -> Result<(), String> {
    use sha2::{Digest, Sha256};

    let mut file = fs::File::open(archive)
        .map_err(|error| format!("Cannot open the downloaded archive for '{name}': {error}"))?;
    let mut hasher = Sha256::new();
    std::io::copy(&mut file, &mut hasher)
        .map_err(|error| format!("Cannot hash the downloaded archive for '{name}': {error}"))?;
    let actual = hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    if actual != artifact.sha256 {
        return Err(format!(
            "Downloaded archive for '{name}' has SHA-256 {actual}, expected {}.",
            artifact.sha256
        ));
    }

    if download.format != DownloadFormat::TarGz {
        return install_single_executable(home, name, download, archive, staging);
    }

    let extract_dir = staging.join("extract");
    fs::create_dir(&extract_dir)
        .map_err(|error| format!("Cannot create extraction directory for '{name}': {error}"))?;
    let member = Path::new(download.member_for(artifact));
    // A kept directory is unpacked whole; otherwise only the executable.
    let unpack = if download.keep_directory {
        member.parent().unwrap_or(member)
    } else {
        member
    };
    let status = Command::new("tar")
        .arg("--extract")
        .arg("--gzip")
        .arg("--no-same-owner")
        .arg("--file")
        .arg(archive)
        .arg("--directory")
        .arg(&extract_dir)
        .arg("--")
        .arg(unpack)
        .stdin(Stdio::null())
        .status()
        .map_err(|error| format!("Could not start tar to unpack '{name}': {error}."))?;
    if !status.success() {
        return Err(format!("Unpacking '{name}' failed with {status}."));
    }
    let extracted = extract_dir.join(download.member_for(artifact));
    let metadata = fs::symlink_metadata(&extracted)
        .map_err(|error| format!("The archive for '{name}' has no executable: {error}"))?;
    if !metadata.is_file() {
        return Err(format!(
            "The archive member for '{name}' is not a regular file."
        ));
    }
    if download.keep_directory {
        return install_kept_directory(home, name, download, &extract_dir.join(unpack), member);
    }

    let bin_dir = local_bin_directory(home)?;
    install_executable(name, &bin_dir, &download.binary, |staged| {
        fs::copy(&extracted, staged)
            .map(|_| ())
            .map_err(|error| format!("Cannot stage the '{name}' executable: {error}"))
    })
}

/// Installs a download that is the executable itself, or the executable gzipped (taplo, marksman).
fn install_single_executable(
    home: &Path,
    name: &str,
    download: &DownloadSpec,
    downloaded: &Path,
    staging: &Path,
) -> Result<(), String> {
    let executable = match download.format {
        DownloadFormat::Gzip => {
            let unpacked = staging.join("unpacked");
            let output = fs::File::create(&unpacked)
                .map_err(|error| format!("Cannot create the unpacked '{name}': {error}"))?;
            let status = Command::new("gzip")
                .arg("--decompress")
                .arg("--stdout")
                .arg(downloaded)
                .stdin(Stdio::null())
                .stdout(output)
                .status()
                .map_err(|error| format!("Could not start gzip to unpack '{name}': {error}."))?;
            if !status.success() {
                return Err(format!("Unpacking '{name}' failed with {status}."));
            }
            unpacked
        }
        _ => downloaded.to_path_buf(),
    };
    let bin_dir = local_bin_directory(home)?;
    install_executable(name, &bin_dir, &download.binary, |staged| {
        fs::copy(&executable, staged)
            .map(|_| ())
            .map_err(|error| format!("Cannot stage the '{name}' executable: {error}"))
    })
}

/// Moves an unpacked directory into the tool's folder (`<tools>/<name>/files`) and puts a launcher
/// on PATH that runs the executable from there.
fn install_kept_directory(
    home: &Path,
    name: &str,
    download: &DownloadSpec,
    unpacked: &Path,
    member: &Path,
) -> Result<(), String> {
    let executable = member
        .file_name()
        .ok_or_else(|| format!("The archive member for '{name}' has no file name."))?;
    let tool_dir = ensure_tools_root(home)?.join(name);
    ensure_directory(&tool_dir)?;
    let files = tool_dir.join("files");
    let previous = tool_dir.join(format!(
        ".files-old-{}-{}",
        std::process::id(),
        TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
    ));
    if fs::symlink_metadata(&files).is_ok() {
        fs::rename(&files, &previous)
            .map_err(|error| format!("Cannot replace the files of '{name}': {error}"))?;
    }
    if let Err(error) = fs::rename(unpacked, &files) {
        let _ = fs::rename(&previous, &files);
        return Err(format!("Cannot install the files of '{name}': {error}"));
    }
    let _ = fs::remove_dir_all(&previous);
    let target = files.join(executable);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&target, fs::Permissions::from_mode(0o755))
            .map_err(|error| format!("Cannot mark '{name}' executable: {error}"))?;
    }
    let quoted = target.display().to_string().replace('\'', "'\\''");
    let launcher = format!("#!/bin/sh\nexec '{quoted}' \"$@\"\n");
    let bin_dir = local_bin_directory(home)?;
    install_executable(name, &bin_dir, &download.binary, |staged| {
        fs::write(staged, launcher)
            .map_err(|error| format!("Cannot write the '{name}' launcher: {error}"))
    })
}

fn ensure_tools_root(home: &Path) -> Result<PathBuf, String> {
    if !home.is_absolute() {
        return Err("Home directory must be an absolute path.".into());
    }
    let home = fs::canonicalize(home)
        .map_err(|error| format!("Cannot resolve home directory {}: {error}", home.display()))?;
    if !home.is_dir() {
        return Err(format!("Home path {} is not a directory.", home.display()));
    }
    let mut root = home;
    for component in [".local", "share", "avf-studio", "tools"] {
        root.push(component);
        ensure_directory(&root)?;
    }
    Ok(root)
}

fn ensure_directory(path: &Path) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(format!(
            "Refusing symlink in tool registry path: {}.",
            path.display()
        )),
        Ok(metadata) if metadata.is_dir() => Ok(()),
        Ok(_) => Err(format!(
            "Tool registry path component is not a directory: {}.",
            path.display()
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(path).map_err(|error| {
                format!(
                    "Cannot create tool registry directory {}: {error}",
                    path.display()
                )
            })?;
            let metadata = fs::symlink_metadata(path)
                .map_err(|error| format!("Cannot inspect {}: {error}", path.display()))?;
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                return Err(format!(
                    "Refusing unsafe tool registry directory: {}.",
                    path.display()
                ));
            }
            Ok(())
        }
        Err(error) => Err(format!(
            "Cannot inspect tool registry path {}: {error}",
            path.display()
        )),
    }
}

fn write_manifest_atomically(path: &Path, manifest: &ToolManifest) -> Result<(), String> {
    validate_manifest(manifest)?;
    let parent = path
        .parent()
        .ok_or_else(|| "Manifest path has no parent directory.".to_owned())?;
    let contents = serde_json::to_vec_pretty(manifest).map_err(|e| e.to_string())?;
    let mut temporary_path = None;
    let mut temporary_file = None;
    for _ in 0..16 {
        let sequence = TEMP_FILE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let candidate = parent.join(format!(".manifest-{}-{sequence}.tmp", std::process::id()));
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        match options.open(&candidate) {
            Ok(file) => {
                temporary_path = Some(candidate);
                temporary_file = Some(file);
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(format!("Cannot create temporary manifest: {error}")),
        }
    }
    let (temporary_path, mut temporary_file) = match (temporary_path, temporary_file) {
        (Some(path), Some(file)) => (path, file),
        _ => return Err("Could not allocate a temporary manifest name.".to_owned()),
    };
    let result = (|| {
        temporary_file
            .write_all(&contents)
            .map_err(|error| format!("Cannot write temporary manifest: {error}"))?;
        temporary_file
            .sync_all()
            .map_err(|error| format!("Cannot sync temporary manifest: {error}"))?;
        drop(temporary_file);
        fs::rename(&temporary_path, path)
            .map_err(|error| format!("Cannot publish manifest: {error}"))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary_path);
    }
    result
}

pub fn list_installed(home: &Path) -> Result<Vec<ToolManifest>, String> {
    let tools_root = ensure_tools_root(home)?;
    let catalog = load_catalog()?;
    let entries = fs::read_dir(&tools_root).map_err(|error| {
        format!(
            "Cannot read tool registry {}: {error}",
            tools_root.display()
        )
    })?;
    let mut installed = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|error| format!("Cannot read tool registry entry: {error}"))?;
        let file_type = entry
            .file_type()
            .map_err(|error| format!("Cannot inspect tool registry entry: {error}"))?;
        if !file_type.is_dir() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        // Staging directories of a download in progress (possibly another daemon's, during an
        // update) are not tools yet.
        if name.starts_with(".download-") {
            continue;
        }
        validate_tool_name(&name)?;
        let manifest_path = entry.path().join("manifest.json");
        let metadata = match fs::symlink_metadata(&manifest_path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(format!(
                    "Cannot inspect tool manifest {}: {error}",
                    manifest_path.display()
                ))
            }
        };
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Err(format!(
                "Refusing non-regular tool manifest: {}.",
                manifest_path.display()
            ));
        }
        let contents = fs::read_to_string(&manifest_path)
            .map_err(|error| format!("Cannot read {}: {error}", manifest_path.display()))?;
        let manifest: ToolManifest = serde_json::from_str(&contents).map_err(|error| {
            format!("Invalid tool manifest {}: {error}", manifest_path.display())
        })?;
        validate_manifest(&manifest)?;
        if manifest.name != name {
            return Err(format!(
                "Tool directory '{}' does not match manifest name '{}'.",
                name, manifest.name
            ));
        }
        let expected = catalog
            .tools
            .iter()
            .find(|tool| tool.name == name)
            .ok_or_else(|| format!("Installed tool '{name}' is not in the allowlisted catalog."))?;
        if &manifest != expected {
            return Err(format!(
                "Installed tool '{name}' does not match its allowlisted catalog manifest."
            ));
        }
        installed.push(manifest);
    }
    installed.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(installed)
}

#[allow(dead_code)]
pub(crate) fn installed_tool_map(
    home: &Path,
) -> Result<std::collections::HashMap<String, ToolManifest>, String> {
    Ok(list_installed(home)?
        .into_iter()
        .map(|manifest| (manifest.name.clone(), manifest))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn catalog_has_the_language_servers_offered_on_demand() {
        assert_eq!(catalog_lookup("gopls").unwrap().install.apt, Some(vec!["gopls".to_owned()]));
        assert_eq!(
            catalog_lookup("bash-language-server").unwrap().command,
            ["avf-node-agent", "bash-language-server", "start"]
        );
        let taplo = catalog_lookup("taplo").unwrap();
        assert_eq!(taplo.install.download.unwrap().format, DownloadFormat::Gzip);
        let marksman = catalog_lookup("marksman").unwrap();
        assert_eq!(marksman.install.download.unwrap().format, DownloadFormat::Raw);
        assert_eq!(
            catalog_lookup("ripgrep").unwrap().install.download.unwrap().format,
            DownloadFormat::TarGz
        );
    }

    #[cfg(unix)]
    #[test]
    fn single_file_downloads_install_their_executable() {
        use sha2::{Digest, Sha256};
        use std::io::Write;

        let home = tempdir().unwrap();
        let staging = tempdir().unwrap();
        let raw = staging.path().join("tool");
        fs::write(&raw, b"#!/bin/sh\necho raw\n").unwrap();
        let gzipped = staging.path().join("tool.gz");
        let mut encoder = Command::new("gzip")
            .arg("--stdout")
            .stdin(Stdio::piped())
            .stdout(fs::File::create(&gzipped).unwrap())
            .spawn()
            .unwrap();
        encoder.stdin.take().unwrap().write_all(b"#!/bin/sh\necho gz\n").unwrap();
        assert!(encoder.wait().unwrap().success());
        for (path, format, binary, expected) in [
            (&raw, DownloadFormat::Raw, "raw-tool", "echo raw"),
            (&gzipped, DownloadFormat::Gzip, "gz-tool", "echo gz"),
        ] {
            let sha256 = Sha256::digest(fs::read(path).unwrap())
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            let artifact = DownloadArtifact { url: String::new(), sha256, archive_member: None };
            let spec = DownloadSpec {
                binary: binary.to_owned(),
                archive_member: binary.to_owned(),
                artifacts: Default::default(),
                keep_directory: false,
                format,
            };
            let work = tempdir().unwrap();
            install_downloaded_archive(home.path(), binary, &spec, &artifact, path, work.path()).unwrap();
            let installed = fs::read_to_string(home.path().join(".local/bin").join(binary)).unwrap();
            assert!(installed.contains(expected), "{installed}");
        }
    }

    #[test]
    fn catalog_lookup_only_returns_allowlisted_tools() {
        assert_eq!(catalog_lookup("basedpyright").unwrap().kind, "lsp");
        assert_eq!(catalog_lookup("ruff").unwrap().command, ["ruff", "server"]);
        assert_eq!(
            catalog_lookup("vtsls").unwrap().languages,
            ["typescript", "javascript"]
        );
        assert_eq!(catalog_lookup("rust-analyzer").unwrap().languages, ["rust"]);
        assert!(catalog_lookup("unknown-tool")
            .unwrap_err()
            .contains("not in the bundled allowlisted catalog"));
        assert!(catalog_lookup("../basedpyright").is_err());
    }

    #[test]
    fn tool_installation_supports_linux_x86_64_and_aarch64() {
        assert!(supports_tool_installation_platform("linux", "x86_64"));
        assert!(supports_tool_installation_platform("linux", "aarch64"));
        assert!(!supports_tool_installation_platform("linux", "arm"));
        assert!(!supports_tool_installation_platform("android", "aarch64"));
        assert!(!supports_tool_installation_platform("windows", "x86_64"));
    }

    #[test]
    fn catalog_installer_commands_are_manager_specific_and_shell_free() {
        let basedpyright = catalog_lookup("basedpyright").unwrap();
        let basedpyright_command = install_command(&basedpyright).unwrap();
        assert_eq!(basedpyright_command.get_program(), "uv");
        assert_eq!(
            basedpyright_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            ["tool", "install", "--upgrade", "basedpyright"]
        );

        let ruff = catalog_lookup("ruff").unwrap();
        let ruff_command = install_command(&ruff).unwrap();
        assert_eq!(ruff_command.get_program(), "uv");
        assert_eq!(
            ruff_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            ["tool", "install", "--upgrade", "ruff"]
        );

        let vtsls = catalog_lookup("vtsls").unwrap();
        let vtsls_command = install_command(&vtsls).unwrap();
        assert_eq!(vtsls_command.get_program(), "npm");
        assert_eq!(
            vtsls_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            ["install", "--global", "@vtsls/language-server"]
        );

        let rust_analyzer = catalog_lookup("rust-analyzer").unwrap();
        let rust_command = install_command(&rust_analyzer).unwrap();
        assert_eq!(rust_command.get_program(), "apt-get");
        assert_eq!(
            rust_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            [
                "install",
                "--yes",
                "--no-install-recommends",
                "rust-analyzer"
            ]
        );
    }

    #[test]
    fn apt_installs_use_noninteractive_sudo_only_for_non_root_users() {
        let manifest = catalog_lookup("rust-analyzer").unwrap();
        let apt_command = install_command(&manifest).unwrap();

        let root_command = with_install_privileges(apt_command, 0);
        assert_eq!(root_command.get_program(), "apt-get");

        let apt_command = install_command(&manifest).unwrap();
        let user_command = with_install_privileges(apt_command, 1000);
        assert_eq!(user_command.get_program(), "sudo");
        assert_eq!(
            user_command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            [
                "-n",
                "--",
                "apt-get",
                "install",
                "--yes",
                "--no-install-recommends",
                "rust-analyzer"
            ]
        );
    }

    #[test]
    fn manifest_validation_rejects_client_executable_paths() {
        let mut manifest = catalog_lookup("basedpyright").unwrap();
        manifest.command[0] = "/tmp/attacker".into();
        assert!(validate_manifest(&manifest)
            .unwrap_err()
            .contains("bare executable name"));
    }

    #[test]
    fn install_behavior_is_testable_without_running_an_installer() {
        let home = tempdir().unwrap();
        let mut invoked = false;
        let installed = install_tool_with(home.path(), "basedpyright", |manifest| {
            invoked = true;
            assert_eq!(
                manifest.install.uv.as_deref(),
                Some(&["basedpyright".to_owned()][..])
            );
            Ok(())
        })
        .unwrap();
        assert!(invoked);
        let registered: ToolManifest = serde_json::from_slice(
            &fs::read(
                home.path()
                    .join(".local/share/avf-studio/tools/basedpyright/manifest.json"),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(installed, registered);
        assert_eq!(list_installed(home.path()).unwrap(), vec![installed]);
    }

    #[test]
    fn removing_a_tool_unregisters_it_and_refuses_unknown_ones() {
        let home = tempdir().unwrap();
        install_tool_with(home.path(), "ruff", |_| Ok(())).unwrap();
        let failed = remove_tool_with(home.path(), "ruff", |_, _| Err("simulated".into()));
        assert_eq!(failed.unwrap_err(), "simulated");
        assert_eq!(list_installed(home.path()).unwrap().len(), 1);
        let removed = remove_tool_with(home.path(), "ruff", |manifest, others| {
            assert_eq!(manifest.name, "ruff");
            assert!(others.is_empty());
            Ok(())
        })
        .unwrap();
        assert_eq!(removed.name, "ruff");
        assert!(list_installed(home.path()).unwrap().is_empty());
        assert!(!home
            .path()
            .join(".local/share/avf-studio/tools/ruff")
            .exists());
        assert_eq!(
            remove_tool_with(home.path(), "ruff", |_, _| Ok(())).unwrap_err(),
            "'ruff' is not installed."
        );
        assert!(remove_tool_with(home.path(), "../ruff", |_, _| Ok(())).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn a_shared_launcher_script_stays_until_its_last_tool_goes() {
        use std::os::unix::fs::PermissionsExt;
        let home = tempdir().unwrap();
        for name in ["yaml-language-server", "sql-language-server"] {
            install_tool_with(home.path(), name, |_| Ok(())).unwrap();
        }
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        let log = home.path().join("calls.log");
        let agent = bin.join("avf-node-agent");
        fs::write(
            &agent,
            format!("#!/bin/sh\necho \"$@\" >> '{}'\n", log.display()),
        )
        .unwrap();
        fs::set_permissions(&agent, fs::Permissions::from_mode(0o755)).unwrap();
        remove_tool(home.path(), "yaml-language-server").unwrap();
        assert!(agent.exists());
        remove_tool(home.path(), "sql-language-server").unwrap();
        assert!(!agent.exists());
        assert_eq!(
            fs::read_to_string(&log).unwrap(),
            "remove yaml-language-server\nremove sql-language-server\n"
        );
        assert!(list_installed(home.path()).unwrap().is_empty());
    }

    #[test]
    fn uninstalls_use_each_package_managers_own_command() {
        let program = |name: &str| {
            let command = uninstall_command(&catalog_lookup(name).unwrap()).unwrap();
            let mut words = vec![command.get_program().to_string_lossy().into_owned()];
            words.extend(
                command
                    .get_args()
                    .map(|arg| arg.to_string_lossy().into_owned()),
            );
            words
        };
        assert_eq!(program("ruff"), ["uv", "tool", "uninstall", "ruff"]);
        assert_eq!(
            program("vtsls"),
            ["npm", "uninstall", "--global", "@vtsls/language-server"]
        );
        assert_eq!(program("gopls"), ["apt-get", "remove", "--yes", "gopls"]);
        assert_eq!(package_name("ruff==0.6.1"), "ruff");
        assert_eq!(package_name("@scope/name@1.2.3"), "@scope/name");
        assert_eq!(package_name("basedpyright"), "basedpyright");
    }

    #[test]
    fn installer_failure_does_not_register_a_tool() {
        let home = tempdir().unwrap();
        let result = install_tool_with(home.path(), "basedpyright", |_| {
            Err("simulated installer failure".into())
        });
        assert_eq!(result.unwrap_err(), "simulated installer failure");
        assert!(!home.path().join(".local").exists());
    }

    #[test]
    fn uv_missing_error_is_actionable_and_does_not_claim_success() {
        let message = uv_not_found_message("basedpyright");
        assert_eq!(
            message,
            "Cannot install 'basedpyright': executable 'uv' was not found on PATH. \
             Install uv (https://docs.astral.sh/uv/getting-started/installation/) and rerun \
             `avf-daemon tools install basedpyright`."
        );
        assert!(!message.contains("Installed"));
    }

    #[test]
    fn copilot_tools_are_pinned_per_architecture_downloads() {
        for (name, binary) in [
            ("copilot-language-server", "copilot-language-server"),
            ("copilot", "copilot"),
        ] {
            let manifest = catalog_lookup(name).unwrap();
            let download = manifest.install.download.as_ref().unwrap();
            assert_eq!(download.binary, binary);
            assert_eq!(manifest.command[0], binary);
            assert!(download.artifacts.contains_key("aarch64"));
            assert!(download.artifacts.contains_key("x86_64"));
        }
        assert_eq!(catalog_lookup("copilot").unwrap().kind, "acp-agent");
        assert_eq!(catalog_lookup("ty").unwrap().command, ["ty", "server"]);
    }

    fn sample_download(sha256: &str) -> DownloadSpec {
        DownloadSpec {
            binary: "sample-tool".into(),
            archive_member: "package/sample-tool".into(),
            artifacts: [(
                "aarch64".to_owned(),
                DownloadArtifact {
                    url: "https://example.invalid/sample.tgz".into(),
                    sha256: sha256.into(),
                    archive_member: None,
                },
            )]
            .into(),
            keep_directory: false,
            format: DownloadFormat::TarGz,
        }
    }

    #[test]
    fn download_specs_reject_unsafe_members_and_unpinned_artifacts() {
        let valid = sample_download(&"a".repeat(64));
        assert!(validate_download_spec("sample", &valid).is_ok());

        let mut traversal = valid.clone();
        traversal.archive_member = "../sample-tool".into();
        assert!(validate_download_spec("sample", &traversal).is_err());

        let mut absolute = valid.clone();
        absolute.archive_member = "/package/sample-tool".into();
        assert!(validate_download_spec("sample", &absolute).is_err());

        let mut wrong_file = valid.clone();
        wrong_file.archive_member = "package/other".into();
        assert!(validate_download_spec("sample", &wrong_file).is_err());

        let mut per_arch = valid.clone();
        per_arch.artifacts.get_mut("aarch64").unwrap().archive_member = Some("sample-aarch64/sample-tool".into());
        assert!(validate_download_spec("sample", &per_arch).is_ok());
        per_arch.artifacts.get_mut("aarch64").unwrap().archive_member = Some("../sample-tool".into());
        assert!(validate_download_spec("sample", &per_arch).is_err());

        let mut path_binary = valid.clone();
        path_binary.binary = "bin/sample".into();
        assert!(validate_download_spec("sample", &path_binary).is_err());

        let mut plain_http = valid.clone();
        plain_http.artifacts.get_mut("aarch64").unwrap().url =
            "http://example.invalid/a.tgz".into();
        assert!(validate_download_spec("sample", &plain_http).is_err());

        assert!(validate_download_spec("sample", &sample_download("abc")).is_err());
        assert!(validate_download_spec("sample", &sample_download(&"A".repeat(64))).is_err());
    }

    #[test]
    fn jupyter_bridge_installs_the_bundled_script() {
        let manifest = catalog_lookup("jupyter").unwrap();
        assert_eq!(manifest.kind, "kernel");
        let home = tempdir().unwrap();
        install_from_catalog(home.path(), &manifest).unwrap();
        let installed = home.path().join(".local/bin/avf-jupyter-bridge");
        let contents = fs::read_to_string(&installed).unwrap();
        assert!(contents.starts_with("#!/usr/bin/env -S uv run --quiet --script"));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&installed).unwrap().permissions().mode() & 0o777,
                0o755
            );
        }
    }

    #[test]
    fn script_setup_runs_after_writing_and_its_failure_fails_the_install() {
        let mut manifest = catalog_lookup("claude-code").unwrap();
        assert_eq!(manifest.kind, "acp-agent");
        let script = manifest.install.script.as_mut().unwrap();
        assert_eq!(script.setup, ["setup", "claude-code"]);
        // An argument the launcher rejects at once, so the test never downloads Node.js.
        script.setup = vec!["not-a-mode".into()];
        let home = tempdir().unwrap();
        let error = install_from_catalog(home.path(), &manifest).unwrap_err();
        assert!(error.contains("setup failed"), "{error}");
        let installed = fs::read_to_string(home.path().join(".local/bin/avf-node-agent")).unwrap();
        assert!(installed.starts_with("#!/usr/bin/env bash"));
    }

    #[test]
    fn script_setup_arguments_are_bounded() {
        let mut manifest = catalog_lookup("claude-code").unwrap();
        manifest.install.script.as_mut().unwrap().setup = vec![String::new()];
        assert!(validate_manifest(&manifest).unwrap_err().contains("setup arguments"));
    }

    #[test]
    fn antigravity_catalog_entry_is_a_standalone_acp_download() {
        let manifest = catalog_lookup("antigravity").unwrap();
        assert_eq!(manifest.kind, "acp-agent");
        assert_eq!(manifest.command, ["antigravity", "acp", "--stdio"]);
        let download = manifest.install.download.as_ref().unwrap();
        assert_eq!(download.binary, "antigravity");
        assert_eq!(download.archive_member, "antigravity");
        assert!(download.artifacts.contains_key("aarch64"));
        assert!(download.artifacts.contains_key("x86_64"));
        assert!(validate_manifest(&manifest).is_ok());
    }

    #[test]
    fn script_installs_must_name_a_bundled_script() {
        let mut manifest = catalog_lookup("jupyter").unwrap();
        manifest.install.script.as_mut().unwrap().asset = "missing.py".into();
        assert!(validate_manifest(&manifest)
            .unwrap_err()
            .contains("bundled script"));
    }

    #[test]
    fn missing_architecture_artifact_fails_before_downloading() {
        let home = tempdir().unwrap();
        let error = install_download(
            home.path(),
            "sample",
            &sample_download(&"a".repeat(64)),
            "x86_64",
        )
        .unwrap_err();
        assert!(error.contains("no download artifact for the x86_64"));
    }

    fn sample_archive(directory: &Path) -> (PathBuf, String) {
        use sha2::{Digest, Sha256};

        let package = directory.join("package");
        fs::create_dir(&package).unwrap();
        fs::write(package.join("sample-tool"), "#!/bin/sh\necho sample\n").unwrap();
        let archive = directory.join("sample.tgz");
        let status = Command::new("tar")
            .arg("-czf")
            .arg(&archive)
            .arg("-C")
            .arg(directory)
            .arg("package/sample-tool")
            .status()
            .unwrap();
        assert!(status.success());
        let digest = Sha256::digest(fs::read(&archive).unwrap())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        (archive, digest)
    }

    #[test]
    fn verified_archive_installs_executable_into_local_bin() {
        let home = tempdir().unwrap();
        let work = tempdir().unwrap();
        let (archive, digest) = sample_archive(work.path());
        let download = sample_download(&digest);
        let artifact = download.artifacts["aarch64"].clone();
        let staging = staging_directory(home.path(), "sample").unwrap();
        install_downloaded_archive(
            home.path(),
            "sample",
            &download,
            &artifact,
            &archive,
            &staging,
        )
        .unwrap();
        let installed = home.path().join(".local/bin/sample-tool");
        assert_eq!(
            fs::read_to_string(&installed).unwrap(),
            "#!/bin/sh\necho sample\n"
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&installed).unwrap().permissions().mode() & 0o777,
                0o755
            );
        }
    }

    #[test]
    fn kept_directory_installs_its_files_and_a_launcher() {
        use sha2::{Digest, Sha256};
        let home = tempdir().unwrap();
        let work = tempdir().unwrap();
        let lib = work.path().join("package/lib");
        fs::create_dir_all(&lib).unwrap();
        fs::write(lib.join("tsc"), "#!/bin/sh\necho compiler\n").unwrap();
        fs::write(lib.join("lib.d.ts"), "declare var x: number;\n").unwrap();
        fs::write(work.path().join("package/README.md"), "not needed\n").unwrap();
        let archive = work.path().join("ts.tgz");
        assert!(Command::new("tar")
            .arg("-czf")
            .arg(&archive)
            .arg("-C")
            .arg(work.path())
            .arg("package")
            .status()
            .unwrap()
            .success());
        let digest: String = Sha256::digest(fs::read(&archive).unwrap())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        let mut download = sample_download(&digest);
        download.binary = "tsgo".into();
        download.archive_member = "package/lib/tsc".into();
        download.keep_directory = true;
        assert!(validate_download_spec("typescript", &download).is_ok());
        let artifact = download.artifacts["aarch64"].clone();
        for _ in 0..2 {
            // A second install replaces the first one's files.
            let staging = staging_directory(home.path(), "typescript").unwrap();
            install_downloaded_archive(home.path(), "typescript", &download, &artifact, &archive, &staging).unwrap();
            let _ = fs::remove_dir_all(&staging);
        }
        let files = fs::canonicalize(home.path()).unwrap().join(".local/share/avf-studio/tools/typescript/files");
        assert!(files.join("lib.d.ts").is_file());
        assert!(!files.join("README.md").exists());
        let launcher = fs::read_to_string(home.path().join(".local/bin/tsgo")).unwrap();
        assert_eq!(launcher, format!("#!/bin/sh\nexec '{}' \"$@\"\n", files.join("tsc").display()));
        let output = Command::new(home.path().join(".local/bin/tsgo")).output().unwrap();
        assert_eq!(String::from_utf8_lossy(&output.stdout), "compiler\n");

        // Without keep_directory the member must still be the executable itself.
        download.keep_directory = false;
        assert!(validate_download_spec("typescript", &download).is_err());
        download.keep_directory = true;
        download.archive_member = "tsc".into();
        assert!(validate_download_spec("typescript", &download).is_err());
    }

    #[test]
    fn archive_with_wrong_digest_is_never_installed() {
        let home = tempdir().unwrap();
        let work = tempdir().unwrap();
        let (archive, _) = sample_archive(work.path());
        let download = sample_download(&"0".repeat(64));
        let artifact = download.artifacts["aarch64"].clone();
        let staging = staging_directory(home.path(), "sample").unwrap();
        let error = install_downloaded_archive(
            home.path(),
            "sample",
            &download,
            &artifact,
            &archive,
            &staging,
        )
        .unwrap_err();
        assert!(error.contains("expected 0000"));
        assert!(!home.path().join(".local/bin/sample-tool").exists());
    }

    #[test]
    fn refuses_symlinked_registry_directories_and_manifest_files() {
        let home = tempdir().unwrap();
        let outside = tempdir().unwrap();
        let tools_path = home.path().join(".local/share/avf-studio/tools");
        fs::create_dir_all(tools_path.parent().unwrap()).unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(outside.path(), &tools_path).unwrap();
        #[cfg(unix)]
        assert!(ensure_tools_root(home.path())
            .unwrap_err()
            .contains("Refusing symlink"));

        let home = tempdir().unwrap();
        let tools = ensure_tools_root(home.path()).unwrap();
        let tool = tools.join("basedpyright");
        fs::create_dir(&tool).unwrap();
        let target = outside.path().join("manifest.json");
        fs::write(&target, "{}").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(target, tool.join("manifest.json")).unwrap();
        #[cfg(unix)]
        assert!(list_installed(home.path())
            .unwrap_err()
            .contains("Refusing non-regular tool manifest"));
    }

    #[test]
    fn parser_accepts_install_and_rejects_extra_arguments() {
        assert_eq!(
            parse_cli(&["install".into(), "basedpyright".into()]).unwrap(),
            ToolCommand::Install("basedpyright".into())
        );
        assert!(parse_cli(&["install".into(), "basedpyright".into(), "/bin/sh".into()]).is_err());
        assert_eq!(
            parse_cli(&["remove".into(), "ruff".into()]).unwrap(),
            ToolCommand::Remove("ruff".into())
        );
    }

    #[test]
    fn downloads_in_progress_are_not_listed_as_tools() {
        let home = tempdir().unwrap();
        let tools = ensure_tools_root(home.path()).unwrap();
        fs::create_dir(tools.join(".download-copilot-language-server-866-0")).unwrap();
        assert_eq!(list_installed(home.path()).unwrap(), vec![]);
    }

    #[test]
    fn unsafe_tool_names_never_reach_the_installer() {
        let home = tempdir().unwrap();
        let mut invoked = false;
        let result = install_tool_with(home.path(), "../basedpyright", |_| {
            invoked = true;
            Ok(())
        });
        assert!(result.unwrap_err().contains("Invalid tool name"));
        assert!(!invoked);
    }
}
