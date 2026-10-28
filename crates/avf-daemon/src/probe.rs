//! Answers `ProbeCommands`: which guest commands exist, so the app can offer to install what a
//! feature needs (git for Source Control, conda or uv for kernels) instead of failing.

use avf_protocol::ProbedCommand;
use std::{
    ffi::OsStr,
    os::unix::fs::PermissionsExt,
    path::{Path, PathBuf},
};

const MAX_NAMES: usize = 32;
const MAX_NAME_LENGTH: usize = 64;

/// Where tools usually live but the daemon's PATH may not reach: the user's own bin directory
/// and the conda installers' default prefixes.
const HOME_BIN_DIRS: &[&str] = &[
    ".local/bin",
    "miniforge3/bin",
    "miniconda3/bin",
    "anaconda3/bin",
    "mambaforge/bin",
    ".cargo/bin",
];

pub fn probe_commands(
    home: Option<&Path>,
    path: Option<&OsStr>,
    names: &[String],
) -> Vec<ProbedCommand> {
    let mut dirs: Vec<PathBuf> = path
        .map(|path| std::env::split_paths(path).collect())
        .unwrap_or_default();
    if let Some(home) = home {
        dirs.extend(HOME_BIN_DIRS.iter().map(|dir| home.join(dir)));
    }
    names
        .iter()
        .take(MAX_NAMES)
        .map(|name| ProbedCommand {
            name: name.clone(),
            path: valid_name(name)
                .then(|| {
                    dirs.iter()
                        .map(|dir| dir.join(name))
                        .find(|candidate| is_executable(candidate))
                })
                .flatten()
                .map(|found| found.to_string_lossy().into_owned()),
        })
        .collect()
}

/// A bare command name: anything with a path separator could probe arbitrary files.
fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= MAX_NAME_LENGTH
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('\0')
}

fn is_executable(path: &Path) -> bool {
    path.metadata()
        .is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o111 != 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn executable(path: &Path) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, "#!/bin/sh\n").unwrap();
        fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
    }

    #[test]
    fn finds_commands_on_path_and_in_conda_prefixes() {
        let root = tempfile::tempdir().unwrap();
        let bin = root.path().join("usr/bin");
        let home = root.path().join("home");
        executable(&bin.join("git"));
        executable(&home.join("miniforge3/bin/conda"));
        fs::write(bin.join("notes"), "not executable").unwrap();
        let names: Vec<String> = ["git", "conda", "notes", "uv", "../usr/bin/git"]
            .map(String::from)
            .into();
        let found = probe_commands(Some(&home), Some(bin.as_os_str()), &names);
        let paths: Vec<Option<String>> = found.into_iter().map(|command| command.path).collect();
        assert_eq!(
            paths,
            vec![
                Some(bin.join("git").to_string_lossy().into_owned()),
                Some(
                    home.join("miniforge3/bin/conda")
                        .to_string_lossy()
                        .into_owned()
                ),
                None,
                None,
                None,
            ],
        );
    }

    #[test]
    fn caps_the_number_of_names() {
        let names: Vec<String> = (0..100).map(|index| format!("tool{index}")).collect();
        assert_eq!(probe_commands(None, None, &names).len(), MAX_NAMES);
    }
}
