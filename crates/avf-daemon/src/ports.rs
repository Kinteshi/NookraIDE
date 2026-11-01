use std::{
    collections::{HashMap, HashSet},
    io,
    path::Path,
    time::Duration,
};

use avf_protocol::{PortProtocol, SystemMessage};
use tokio::{
    fs,
    sync::{broadcast, RwLock},
    time::interval,
};
use tracing::warn;

const SCAN_INTERVAL: Duration = Duration::from_secs(2);
const EVENT_CAPACITY: usize = 128;
/// A process's command line is cut here; the app shows one line of it.
const MAX_COMMAND_CHARS: usize = 200;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct ListeningPort {
    port: u16,
    protocol: PortProtocol,
}

/// Each listening port and the command line of the process holding it, when that process is
/// one of this user's (other users' `/proc/<pid>/fd` can't be read).
type PortTable = HashMap<ListeningPort, Option<String>>;

#[derive(Clone)]
pub(crate) struct PortScanner {
    events: broadcast::Sender<SystemMessage>,
    current: std::sync::Arc<RwLock<PortTable>>,
}

impl Default for PortScanner {
    fn default() -> Self {
        let (events, _) = broadcast::channel(EVENT_CAPACITY);
        Self {
            events,
            current: std::sync::Arc::new(RwLock::new(HashMap::new())),
        }
    }
}

impl PortScanner {
    pub(crate) fn start(&self) {
        let scanner = self.clone();
        tokio::spawn(async move {
            let mut ticker = interval(SCAN_INTERVAL);
            let mut previous = PortTable::new();
            loop {
                ticker.tick().await;
                match read_listening_ports().await {
                    Ok(listening) => {
                        // Only new ports cost a walk of /proc; known ones keep their process.
                        let new_inodes = listening
                            .iter()
                            .filter(|(port, _)| !previous.contains_key(port))
                            .map(|(_, inode)| *inode)
                            .collect::<HashSet<_>>();
                        let owners = if new_inodes.is_empty() {
                            HashMap::new()
                        } else {
                            tokio::task::spawn_blocking(move || {
                                socket_owners(Path::new("/proc"), &new_inodes)
                            })
                            .await
                            .unwrap_or_default()
                        };
                        let current = listening
                            .into_iter()
                            .map(|(port, inode)| {
                                let command = match previous.get(&port) {
                                    Some(known) => known.clone(),
                                    None => owners.get(&inode).cloned(),
                                };
                                (port, command)
                            })
                            .collect::<PortTable>();
                        for event in port_changes(&previous, &current) {
                            let _ = scanner.events.send(event);
                        }
                        *scanner.current.write().await = current.clone();
                        previous = current;
                    }
                    Err(error) => {
                        warn!(%error, "failed scanning /proc/net/tcp and tcp6");
                    }
                }
            }
        });
    }

    pub(crate) fn subscribe(&self) -> broadcast::Receiver<SystemMessage> {
        self.events.subscribe()
    }

    pub(crate) async fn current(&self) -> Vec<SystemMessage> {
        let mut ports = self
            .current
            .read()
            .await
            .iter()
            .map(|(port, command)| (*port, command.clone()))
            .collect::<Vec<_>>();
        ports.sort_by_key(|(port, _)| (port.protocol as u8, port.port));
        ports
            .into_iter()
            .map(|(port, command)| SystemMessage::PortOpened {
                port: port.port,
                protocol: port.protocol,
                process_name: command,
            })
            .collect()
    }
}

fn port_changes(previous: &PortTable, current: &PortTable) -> Vec<SystemMessage> {
    let mut opened = current
        .iter()
        .filter(|(port, _)| !previous.contains_key(port))
        .collect::<Vec<_>>();
    let mut closed = previous
        .keys()
        .filter(|port| !current.contains_key(port))
        .copied()
        .collect::<Vec<_>>();
    opened.sort_by_key(|(port, _)| (port.protocol as u8, port.port));
    closed.sort_by_key(|port| (port.protocol as u8, port.port));
    opened
        .into_iter()
        .map(|(port, command)| SystemMessage::PortOpened {
            port: port.port,
            protocol: port.protocol,
            process_name: command.clone(),
        })
        .chain(closed.into_iter().map(|port| SystemMessage::PortClosed {
            port: port.port,
            protocol: port.protocol,
        }))
        .collect()
}

async fn read_listening_ports() -> io::Result<HashMap<ListeningPort, u64>> {
    let ipv4 = fs::read_to_string("/proc/net/tcp").await?;
    let ipv6 = match fs::read_to_string("/proc/net/tcp6").await {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error),
    };
    let mut ports = parse_table(&ipv4, PortProtocol::Tcp);
    ports.extend(parse_table(&ipv6, PortProtocol::Tcp6));
    Ok(ports)
}

/// Listening sockets (state 0A) and their inodes.
fn parse_table(contents: &str, protocol: PortProtocol) -> HashMap<ListeningPort, u64> {
    contents
        .lines()
        .skip(1)
        .filter_map(|line| {
            let fields = line.split_whitespace().collect::<Vec<_>>();
            if fields.get(3).copied() != Some("0A") {
                return None;
            }
            let (_, port) = fields.get(1)?.rsplit_once(':')?;
            let port = u16::from_str_radix(port, 16).ok()?;
            let inode = fields.get(9).and_then(|inode| inode.parse().ok()).unwrap_or(0);
            (port != 0).then_some((ListeningPort { port, protocol }, inode))
        })
        .collect()
}

/// Which process holds each socket inode: the `socket:[inode]` links under `<proc>/<pid>/fd`,
/// then that process's command line (its `comm` when the command line is empty).
fn socket_owners(proc_root: &Path, inodes: &HashSet<u64>) -> HashMap<u64, String> {
    let mut owners = HashMap::new();
    let Ok(processes) = std::fs::read_dir(proc_root) else {
        return owners;
    };
    for process in processes.flatten() {
        if owners.len() == inodes.len() {
            break;
        }
        let name = process.file_name();
        if !name.to_string_lossy().bytes().all(|byte| byte.is_ascii_digit()) {
            continue;
        }
        let Ok(fds) = std::fs::read_dir(process.path().join("fd")) else {
            continue;
        };
        for fd in fds.flatten() {
            let Ok(target) = std::fs::read_link(fd.path()) else {
                continue;
            };
            let target = target.to_string_lossy();
            let Some(inode) = target
                .strip_prefix("socket:[")
                .and_then(|rest| rest.strip_suffix(']'))
                .and_then(|inode| inode.parse::<u64>().ok())
            else {
                continue;
            };
            if inodes.contains(&inode) && !owners.contains_key(&inode) {
                if let Some(command) = command_line(&process.path()) {
                    owners.insert(inode, command);
                }
            }
        }
    }
    owners
}

fn command_line(process: &Path) -> Option<String> {
    let raw = std::fs::read(process.join("cmdline")).unwrap_or_default();
    let command = raw
        .split(|byte| *byte == 0)
        .filter(|part| !part.is_empty())
        .map(String::from_utf8_lossy)
        .collect::<Vec<_>>()
        .join(" ");
    let command = if command.is_empty() {
        std::fs::read_to_string(process.join("comm"))
            .ok()?
            .trim()
            .to_owned()
    } else {
        command
    };
    (!command.is_empty()).then(|| command.chars().take(MAX_COMMAND_CHARS).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    const TCP_TABLE: &str = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345\n   1: 0100007F:1F91 00000000:0000 01 00000000:00000000 00:00000000 00000000 1000 0 12346\n";

    fn port(port: u16) -> ListeningPort {
        ListeningPort {
            port,
            protocol: PortProtocol::Tcp,
        }
    }

    #[test]
    fn parses_only_tcp_listeners_with_their_inodes() {
        let ports = parse_table(TCP_TABLE, PortProtocol::Tcp);
        assert_eq!(ports, HashMap::from([(port(8080), 12345)]));
    }

    #[test]
    fn emits_only_opened_and_closed_port_changes() {
        let old = PortTable::from([(port(8080), None)]);
        let current = PortTable::from([
            (port(8081), Some("python3 -m http.server 8081".to_owned())),
            (port(8080), None),
        ]);
        let events = port_changes(&old, &current);
        assert_eq!(
            events,
            vec![SystemMessage::PortOpened {
                port: 8081,
                protocol: PortProtocol::Tcp,
                process_name: Some("python3 -m http.server 8081".into()),
            }]
        );
        assert_eq!(
            port_changes(&current, &PortTable::new()),
            vec![
                SystemMessage::PortClosed {
                    port: 8080,
                    protocol: PortProtocol::Tcp,
                },
                SystemMessage::PortClosed {
                    port: 8081,
                    protocol: PortProtocol::Tcp,
                },
            ]
        );
    }

    #[cfg(unix)]
    #[test]
    fn finds_the_process_holding_a_socket_inode() {
        let root = tempfile::tempdir().unwrap();
        let process = root.path().join("4242");
        std::fs::create_dir_all(process.join("fd")).unwrap();
        std::fs::write(process.join("cmdline"), b"python3\0-m\0http.server\08000\0").unwrap();
        std::os::unix::fs::symlink("socket:[777]", process.join("fd").join("3")).unwrap();
        std::os::unix::fs::symlink("/dev/null", process.join("fd").join("0")).unwrap();
        let other = root.path().join("99");
        std::fs::create_dir_all(other.join("fd")).unwrap();
        std::fs::write(other.join("cmdline"), b"").unwrap();
        std::fs::write(other.join("comm"), b"sshd\n").unwrap();
        std::os::unix::fs::symlink("socket:[778]", other.join("fd").join("4")).unwrap();
        std::fs::create_dir_all(root.path().join("self")).unwrap();

        let owners = socket_owners(root.path(), &HashSet::from([777, 778, 779]));
        assert_eq!(
            owners,
            HashMap::from([
                (777, "python3 -m http.server 8000".to_owned()),
                (778, "sshd".to_owned()),
            ])
        );
    }
}
