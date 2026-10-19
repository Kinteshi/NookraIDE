use avf_protocol::RpcKind;
use std::io;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};

const MAX_RPC_MESSAGE_SIZE: usize = 16 * 1024 * 1024;

pub(crate) fn validate(kind: RpcKind, body: &str) -> Result<(), String> {
    if body.len() > MAX_RPC_MESSAGE_SIZE {
        return Err("RPC message exceeds the 16 MiB limit".into());
    }
    if kind == RpcKind::Acp && (body.contains('\n') || body.contains('\r')) {
        return Err("ACP messages must be a single newline-free JSON value".into());
    }
    let value: serde_json::Value =
        serde_json::from_str(body).map_err(|error| format!("invalid RPC JSON: {error}"))?;
    if !value.is_object() {
        return Err("RPC messages must be JSON objects".into());
    }
    if kind == RpcKind::Acp && value.get("jsonrpc").and_then(|value| value.as_str()) != Some("2.0")
    {
        return Err("ACP messages must use JSON-RPC 2.0".into());
    }
    Ok(())
}

pub(crate) async fn read<R>(kind: RpcKind, reader: &mut R) -> io::Result<Option<String>>
where
    R: AsyncBufRead + Unpin,
{
    match kind {
        RpcKind::Dap => {
            let Some(message) = crate::lsp::read_content_length_message(reader).await? else {
                return Ok(None);
            };
            validate(kind, &message)
                .map_err(|message| io::Error::new(io::ErrorKind::InvalidData, message))?;
            Ok(Some(message))
        }
        RpcKind::Acp => read_line_json(reader).await,
        RpcKind::Lsp | RpcKind::Custom => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "stdio bridge requires a DAP or ACP kind",
        )),
    }
}

pub(crate) async fn write<W>(kind: RpcKind, writer: &mut W, body: &str) -> io::Result<()>
where
    W: AsyncWrite + Unpin,
{
    validate(kind, body).map_err(|message| io::Error::new(io::ErrorKind::InvalidData, message))?;
    match kind {
        RpcKind::Dap => {
            writer
                .write_all(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes())
                .await?;
            writer.write_all(body.as_bytes()).await?;
            writer.flush().await
        }
        RpcKind::Acp => {
            writer.write_all(body.as_bytes()).await?;
            writer.write_all(b"\n").await?;
            writer.flush().await
        }
        RpcKind::Lsp | RpcKind::Custom => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "stdio bridge requires a DAP or ACP kind",
        )),
    }
}

async fn read_line_json<R>(reader: &mut R) -> io::Result<Option<String>>
where
    R: AsyncBufRead + Unpin,
{
    let mut line = Vec::new();
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if line.is_empty() {
                return Ok(None);
            }
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "ACP stream ended before the JSON line delimiter",
            ));
        }
        let Some(delimiter) = available.iter().position(|byte| *byte == b'\n') else {
            if line.len().saturating_add(available.len()) > MAX_RPC_MESSAGE_SIZE {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "ACP line exceeds the 16 MiB limit",
                ));
            }
            line.extend_from_slice(available);
            let consumed = available.len();
            reader.consume(consumed);
            continue;
        };
        if line.len().saturating_add(delimiter) > MAX_RPC_MESSAGE_SIZE {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "ACP line exceeds the 16 MiB limit",
            ));
        }
        line.extend_from_slice(&available[..delimiter]);
        reader.consume(delimiter + 1);
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        let message = String::from_utf8(line)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        validate(RpcKind::Acp, &message)
            .map_err(|message| io::Error::new(io::ErrorKind::InvalidData, message))?;
        return Ok(Some(message));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{duplex, BufReader};

    #[tokio::test]
    async fn reads_acp_json_after_partial_line_writes() {
        let (mut writer, reader) = duplex(128);
        let mut reader = BufReader::new(reader);
        let task = tokio::spawn(async move {
            writer.write_all(b"{\"json").await.unwrap();
            tokio::task::yield_now().await;
            writer
                .write_all(b"rpc\":\"2.0\",\"id\":1}\r\n")
                .await
                .unwrap();
        });

        assert_eq!(
            read(RpcKind::Acp, &mut reader).await.unwrap(),
            Some("{\"jsonrpc\":\"2.0\",\"id\":1}".into())
        );
        task.await.unwrap();
    }

    #[tokio::test]
    async fn rejects_unterminated_and_invalid_acp_lines() {
        let (_, reader) = duplex(128);
        let mut reader = BufReader::new(reader);
        assert_eq!(read(RpcKind::Acp, &mut reader).await.unwrap(), None);

        let (mut writer, reader) = duplex(128);
        let mut reader = BufReader::new(reader);
        writer.write_all(b"{\"jsonrpc\":\"2.0\"}").await.unwrap();
        drop(writer);
        assert_eq!(
            read(RpcKind::Acp, &mut reader).await.unwrap_err().kind(),
            io::ErrorKind::UnexpectedEof
        );
    }

    #[test]
    fn validates_json_payloads_by_bridge_kind() {
        assert!(validate(RpcKind::Dap, r#"{"seq":1,"type":"request"}"#).is_ok());
        assert!(validate(RpcKind::Acp, r#"{"jsonrpc":"2.0","id":1}"#).is_ok());
        assert!(validate(RpcKind::Acp, r#"{"id":1}"#).is_err());
        assert!(validate(RpcKind::Dap, "not JSON").is_err());
    }
}
