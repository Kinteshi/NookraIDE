#![cfg(all(feature = "native-protocol", unix))]

use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::UnixListener,
    process::Command,
};

#[tokio::test]
async fn askpass_helper_forwards_prompt_and_returns_answer_without_logging_it() {
    let directory = tempfile::tempdir().unwrap();
    let socket_path = directory.path().join("prompt.sock");
    let listener = UnixListener::bind(&socket_path).unwrap();
    let askpass_path = directory.path().join("askpass");
    std::os::unix::fs::symlink(env!("CARGO_BIN_EXE_quirecode-daemon"), &askpass_path).unwrap();
    let prompt = "Password for 'https://example.test':";
    let helper = Command::new(&askpass_path)
        .arg(prompt)
        .env("AVF_GIT_ASKPASS_SOCKET", &socket_path)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .unwrap();

    let (mut stream, _) = listener.accept().await.unwrap();
    let mut length = [0_u8; 4];
    stream.read_exact(&mut length).await.unwrap();
    let length = u32::from_be_bytes(length) as usize;
    let mut received_prompt = vec![0; length];
    stream.read_exact(&mut received_prompt).await.unwrap();
    assert_eq!(received_prompt, prompt.as_bytes());

    let answer = b"not-a-real-password";
    stream
        .write_all(&(answer.len() as u32).to_be_bytes())
        .await
        .unwrap();
    stream.write_all(answer).await.unwrap();

    let output = helper.wait_with_output().await.unwrap();
    assert!(output.status.success());
    assert_eq!(output.stdout, b"not-a-real-password\n");
    assert!(output.stderr.is_empty());
}
