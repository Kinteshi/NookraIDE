use std::{collections::HashSet, io, time::Duration};

use avf_protocol::{PortProtocol, SystemMessage};
use tokio::{
    fs,
    sync::{broadcast, RwLock},
    time::interval,
};
use tracing::warn;

const SCAN_INTERVAL: Duration = Duration::from_secs(2);
const EVENT_CAPACITY: usize = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct ListeningPort {
    port: u16,
    protocol: PortProtocol,
}

#[derive(Clone)]
pub(crate) struct PortScanner {
    events: broadcast::Sender<SystemMessage>,
    current: std::sync::Arc<RwLock<HashSet<ListeningPort>>>,
}

impl Default for PortScanner {
    fn default() -> Self {
        let (events, _) = broadcast::channel(EVENT_CAPACITY);
        Self {
            events,
            current: std::sync::Arc::new(RwLock::new(HashSet::new())),
        }
    }
}

impl PortScanner {
    pub(crate) fn start(&self) {
        let scanner = self.clone();
        tokio::spawn(async move {
            let mut ticker = interval(SCAN_INTERVAL);
            let mut previous = HashSet::new();
            loop {
                ticker.tick().await;
                match read_listening_ports().await {
                    Ok(current) => {
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
        self.current
            .read()
            .await
            .iter()
            .map(|port| SystemMessage::PortOpened {
                port: port.port,
                protocol: port.protocol,
                process_name: None,
            })
            .collect()
    }
}

fn port_changes(
    previous: &HashSet<ListeningPort>,
    current: &HashSet<ListeningPort>,
) -> Vec<SystemMessage> {
    let mut opened = current.difference(previous).copied().collect::<Vec<_>>();
    let mut closed = previous.difference(current).copied().collect::<Vec<_>>();
    opened.sort_by_key(|port| (port.protocol as u8, port.port));
    closed.sort_by_key(|port| (port.protocol as u8, port.port));
    opened
        .into_iter()
        .map(|port| SystemMessage::PortOpened {
            port: port.port,
            protocol: port.protocol,
            process_name: None,
        })
        .chain(closed.into_iter().map(|port| SystemMessage::PortClosed {
            port: port.port,
            protocol: port.protocol,
        }))
        .collect()
}

async fn read_listening_ports() -> io::Result<HashSet<ListeningPort>> {
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

fn parse_table(contents: &str, protocol: PortProtocol) -> HashSet<ListeningPort> {
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
            (port != 0).then_some(ListeningPort { port, protocol })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const TCP_TABLE: &str = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345\n   1: 0100007F:1F91 00000000:0000 01 00000000:00000000 00:00000000 00000000 1000 0 12346\n";

    #[test]
    fn parses_only_tcp_listeners() {
        let ports = parse_table(TCP_TABLE, PortProtocol::Tcp);
        assert_eq!(
            ports,
            HashSet::from([ListeningPort {
                port: 8080,
                protocol: PortProtocol::Tcp
            }])
        );
    }

    #[test]
    fn emits_only_opened_and_closed_port_changes() {
        let old = HashSet::from([ListeningPort {
            port: 8080,
            protocol: PortProtocol::Tcp,
        }]);
        let current = HashSet::from([
            ListeningPort {
                port: 8081,
                protocol: PortProtocol::Tcp,
            },
            ListeningPort {
                port: 8080,
                protocol: PortProtocol::Tcp,
            },
        ]);
        let events = port_changes(&old, &current);
        assert_eq!(
            events,
            vec![SystemMessage::PortOpened {
                port: 8081,
                protocol: PortProtocol::Tcp,
                process_name: None,
            }]
        );
        assert_eq!(
            port_changes(&current, &HashSet::new()),
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
}
