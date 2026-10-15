# **SPEC.md — AVF Studio (Native-Feel VS Code IDE for Android Mobile, Tablets & DeX powered by AVF)**

## **1\. Visão Geral do Projeto**

O **AVF Studio** é uma IDE de código aberto, desenhada para o ecossistema Android moderno — adaptando-se dinamicamente desde **smartphones** (uso portátil em tela vertical) até **tablets em 120Hz** e **modos Desktop nativos (Samsung DeX / Android 16+ Desktop Mode)** com múltiplos monitores, mouse e teclado físico.

Em vez de rodar compiladores em emuladores lentos (`proot`) ou depender exclusivamente de nuvem remota, o AVF Studio utiliza uma arquitetura **Host-Guest Local**:

* **Host (Front-end Android Universal):** App **Tauri v2** contendo uma interface visual e muscular idêntica ao VS Code (`Monaco Editor` \+ `@codingame/monaco-vscode-api` \+ Workbench camaleão responsivo \+ `Xterm.js`).  
* **Guest (Back-end Linux):** Um Daemon assíncrono ultraleve escrito em **Rust** (`avf-daemon`) rodando dentro da máquina virtual Debian nativa do **Android Virtualization Framework (AVF / KVM)**.

## **2\. Arquitetura do Sistema e Fluxo de Dados**

\+-----------------------------------------------------------------------+  
| HOST: ANDROID DEVICE (Phone / Tablet / DeX \- Tauri v2 \+ SystemWebView)|  
|                                                                       |  
|  \+-----------------------+  \+-------------------+  \+---------------+  |  
|  |   Monaco Editor UI    |  |  Xterm.js (WebGL) |  | File Explorer |  |  
|  | (monaco-languageclient)|  |  Terminal Canvas  |  |  & Git Tree   |  |  
|  \+-----------+-----------+  \+---------+---------+  \+-------+-------+  |  
|              |                        |                    |          |  
|              \+------------------------+--------------------+          |  
|                                       |                               |  
|                     \[ Tauri IPC Bridge (Rust Core) \]                  |  
\+---------------------------------------|-------------------------------+  
                                        |  
                      Multiplexed WebSocket (localhost:19860)  
                  \[ 1 frame Binary = 1 MultiplexedPacket em MessagePack (Hello+token primeiro) \]  
                                        |  
\+---------------------------------------|-------------------------------+  
| GUEST: AVF DEBIAN VM (KVM Virtualized Linux)                          |  
|                                                                       |  
|  \+-----------------------------------------------------------------+  |  
|  |                  avf-ide-daemon (Tokio / Axum)                  |  |  
|  |                                                                 |  |  
|  |  \+-------------------+  \+------------------+  \+--------------+  |  |  
|  |  |    LSP Router     |  |   PTY Manager    |  | VFS & Git    |  |  |  
|  |  | (Async Stdio Pipe)|  |  (portable-pty)  |  | (notify/git2)|  |  |  
|  |  \+---------+---------+  \+--------+---------+  \+------+-------+  |  |  
|  \+------------|---------------------|-------------------|----------+  |  
|               |                     |                   |             |  
|       \+-------+-------+     \+-------+-------+   \+-------+-------+     |  
|       | rust-analyzer |     |  zsh / bash   |   |  Linux ext4   |     |  
|       | pyright / ruff|     |  tmux / htop  |   |  File System  |     |  
|       | clangd / vtsls|     |  python / uv  |   |  (Fast I/O)   |     |  
|       \+---------------+     \+---------------+   \+---------------+     |  
\+-----------------------------------------------------------------------+

## **3\. Estrutura do Monorepo (Cargo Workspace \+ pnpm)**

avf-studio/  
├── Cargo.toml                 \# Workspace Rust compartilhado  
├── pnpm-workspace.yaml        \# Workspace Node/Front-end  
├── crates/  
│   ├── avf-protocol/          \# Structs compartilhadas (Serde, envelopes RPC/LSP/PTY/VFS)  
│   └── avf-daemon/            \# Binário Linux (aarch64-unknown-linux-gnu / x86\_64) para a VM Debian  
├── apps/  
│   └── studio-ui/             \# Front-end React 19 \+ TypeScript \+ Tailwind \+ Monaco \+ Xterm.js  
│       └── src-tauri/         \# Core Tauri v2 (Android target \+ detecção de janela/DeX)  
├── docs/                      \# Guias de apoio (roteiro de execução, setup de modelos/Arc B580)  
└── scripts/  
    └── bootstrap-avf.sh       \# Provisiona o daemon no systemd da VM AVF e gera o token de autenticação

## **4\. Contrato de Protocolo (`crates/avf-protocol`)**

Toda a comunicação entre o Host (Android) e o Guest (VM Debian) passa por uma única conexão WebSocket multiplexada (`ws://127.0.0.1:19860/ws`) para evitar overhead de múltiplas conexões e facilitar reconexão automática após *sleep* do aparelho ou transição de tela (ex: plugar o celular num monitor DeX).

### **4.1 Codificação (Wire Format)**

* **Um frame = um pacote.** Cada mensagem WebSocket do tipo **Binary** carrega exatamente um `MultiplexedPacket` codificado em **MessagePack**. Frames do tipo Text são rejeitados com `SystemMessage::ProtocolError` e a conexão é encerrada.
* **Rust:** `rmp-serde` usando `rmp_serde::Serializer::new(buf).with_struct_map().with_human_readable()`:
  * `with_struct_map()` → structs viram mapas com nomes de campo (tolerante à evolução do protocolo e legível no DevTools).
  * `with_human_readable()` → `Uuid` é serializado como string (`"550e8400-..."`), não como 16 bytes crus.
  * Campos `Vec<u8>` usam `#[serde(with = "serde_bytes")]` para virar o tipo `bin` do MessagePack (sem o overhead de 3–4× de um array de números).
* **TypeScript:** `@msgpack/msgpack` (`encode`/`decode`). Campos `bin` chegam como `Uint8Array`, prontos para `xterm.write()`.
* **Enums usam a representação externa padrão do serde** (`{ "Variante": { ...campos } }` ou `"Variante"` para variantes unitárias). **Não usar** `#[serde(tag = ..., content = ...)]`: representações *internally/adjacently tagged* exigem `deserialize_any` e quebram com formatos binários.
* **Tamanho máximo de frame:** 32 MiB (`WebSocketUpgrade::max_message_size`). Leituras de arquivo acima de 16 MiB retornam `VfsErrorCode::TooLarge`.
* **Versionamento:** a constante `PROTOCOL_VERSION` é incrementada a cada mudança incompatível. Daemon e app recusam versões diferentes no handshake.

### **4.2 Handshake e Autenticação**

O daemon expõe um shell completo e leitura/escrita de arquivos. Como a porta é encaminhada para o Host Android, **qualquer app ou página web no aparelho** poderia tentar se conectar a `127.0.0.1:19860`. Por isso:

1. **Token compartilhado:** o `bootstrap-avf.sh` gera um token aleatório de 256 bits (hex), salvo em `~/.config/avf-studio/token` (permissão `0600`) na VM. O usuário cola/escaneia o token no app uma única vez; o Tauri o guarda no armazenamento seguro do Android. O daemon também aceita `AVF_TOKEN` via variável de ambiente (desenvolvimento).
2. **Primeiro frame obrigatório:** o cliente deve enviar `SystemMessage::Hello` em até **5 segundos** após o upgrade. Qualquer outro pacote antes do `Hello` é rejeitado e a conexão é fechada.
3. **Validação:** o daemon compara o token em tempo constante (`subtle::ConstantTimeEq`) e confere `protocol_version`. Responde `Welcome` (sucesso) ou `Rejected` (e fecha a conexão).
4. **Allowlist de `Origin` (defesa em profundidade):** o upgrade em `/ws` só é aceito se o header `Origin` estiver em uma lista configurável (padrão: `http://tauri.localhost`, `tauri://localhost`; em desenvolvimento também `http://localhost:5173`). O token continua sendo a barreira principal.
5. O token **nunca** vai na URL (evita vazar em logs); trafega apenas dentro do `Hello`.
6. `/health` não exige token e retorna apenas `{ "status": "ok", "protocol_version": N }`.

### **4.3 Tipos**

```rust
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum MultiplexedPacket {
    System(SystemMessage),
    Vfs(VfsMessage),
    /// `session_id` é gerado pelo CLIENTE (UUID v4) no momento do `Spawn`
    /// e identifica a sessão em todas as mensagens seguintes.
    Pty { session_id: Uuid, data: PtyPayload },
    /// `json_rpc` é o corpo JSON-RPC puro, sem o header `Content-Length`.
    Lsp { server_id: String, json_rpc: String },
}

// ───────────────────────────── System ─────────────────────────────

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum SystemMessage {
    // Handshake (cliente → daemon, sempre o primeiro frame)
    Hello { protocol_version: u32, auth_token: String, client_name: String },
    // Handshake (daemon → cliente)
    Welcome {
        protocol_version: u32,
        daemon_version: String,
        /// Sessões PTY vivas, para o cliente decidir quais reanexar.
        pty_sessions: Vec<PtySessionInfo>,
    },
    Rejected { reason: RejectReason },

    // Telemetria
    Ping { timestamp: u64 },
    Pong { timestamp: u64, cpu_usage: f32, mem_used_mb: u64, mem_total_mb: u64 },

    // Detector de portas (daemon → cliente)
    PortOpened { port: u16, protocol: PortProtocol, process_name: Option<String> },
    PortClosed { port: u16, protocol: PortProtocol },

    /// Frame malformado, tipo de frame inválido ou mensagem fora de ordem.
    ProtocolError { message: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum RejectReason {
    InvalidToken,
    VersionMismatch { daemon_version: u32 },
    HandshakeTimeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PortProtocol { Tcp, Tcp6 }

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PtySessionInfo {
    pub session_id: Uuid,
    pub shell: String,
    pub cwd: String,
    pub cols: u16,
    pub rows: u16,
}

// ───────────────────────────── PTY ─────────────────────────────

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PtyPayload {
    // cliente → daemon
    Spawn { shell: Option<String>, cwd: Option<String>, cols: u16, rows: u16 },
    Input(#[serde(with = "serde_bytes")] Vec<u8>),
    Resize { cols: u16, rows: u16 },
    /// Reanexa à sessão do envelope. O daemon responde com um `Output`
    /// contendo o ring buffer (até 64KB) e segue transmitindo ao vivo.
    Reattach,
    /// Encerra o processo (SIGHUP, depois SIGKILL após 2s).
    Kill,

    // daemon → cliente
    Spawned { pid: u32 },
    Output(#[serde(with = "serde_bytes")] Vec<u8>),
    /// `code` é `None` quando o processo foi morto por sinal.
    Exit { code: Option<i32> },
    /// Ex.: sessão inexistente em `Reattach`/`Input`, `session_id` duplicado em `Spawn`, falha ao iniciar o shell.
    Error { message: String },
}

// ───────────────────────────── VFS ─────────────────────────────

/// `req_id` é atribuído pelo cliente, único por conexão. Toda requisição
/// recebe exatamente UMA resposta: o `*Res` correspondente ou `VfsError`.
/// Caminhos são absolutos (POSIX) dentro da VM.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum VfsMessage {
    // cliente → daemon
    ListDirReq { req_id: u64, path: String },
    ReadFileReq { req_id: u64, path: String },
    WriteFileReq {
        req_id: u64,
        path: String,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        /// Concorrência otimista: se `Some` e o mtime atual do arquivo for
        /// diferente, a escrita é recusada com `VfsErrorCode::Conflict`.
        expected_modified_ts: Option<u64>,
    },
    WatchReq { req_id: u64, path: String, recursive: bool },
    UnwatchReq { req_id: u64, path: String },

    // daemon → cliente
    ListDirRes { req_id: u64, entries: Vec<FileEntry> },
    ReadFileRes {
        req_id: u64,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        modified_ts: u64,
    },
    WriteFileRes { req_id: u64, modified_ts: u64 },
    WatchRes { req_id: u64 },
    VfsError { req_id: u64, code: VfsErrorCode, message: String },

    // daemon → cliente (não solicitado)
    FsEvent { kind: FsEventKind, paths: Vec<String> },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum VfsErrorCode {
    NotFound,
    PermissionDenied,
    AlreadyExists,
    IsADirectory,
    NotADirectory,
    TooLarge,
    Conflict,
    InvalidPath,
    Io,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FsEventKind {
    Create,
    Modify,
    Remove,
    /// `paths` = `[origem, destino]`.
    Rename,
    /// O watcher perdeu eventos (overflow); o cliente deve recarregar a árvore.
    Rescan,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub size: u64,
    /// Milissegundos desde a época Unix.
    pub modified_ts: u64,
}
```

### **4.4 Garantias Exigidas por Testes (`avf-protocol`)**

* Teste de *round-trip* (`encode → decode == original`) para **todas** as variantes de todos os enums acima.
* Teste garantindo que `Vec<u8>` é codificado como `bin` do MessagePack (e não como array) e que `Uuid` é codificado como string.
* Funções públicas `encode(&MultiplexedPacket) -> Vec<u8>` e `decode(&[u8]) -> Result<MultiplexedPacket, DecodeError>` centralizadas na crate, para que daemon e testes nunca configurem o `rmp-serde` de formas diferentes.

## **5\. Especificação do `avf-daemon` (Rust / Guest VM)**

* **Runtime & Web Server:** `tokio` (full) \+ `axum` (com suporte a WebSockets em `/ws` e healthcheck em `/health`).  
* **Porta Padrão:** `127.0.0.1:19860` (exposta para o Host Android via Port Forwarding nativo do AVF).  
* **Segurança:** handshake obrigatório com token e allowlist de `Origin`, conforme Seção 4.2. Nenhuma mensagem PTY/VFS/LSP é processada antes do `Welcome`.  
* **Módulos Core:**  
  1. **PTY Manager (`portable-pty`):**  
     * Criação, redimensionamento dinâmico (`PtySize { rows, cols }`) e persistência de sessões de terminal (`/bin/bash`, `/bin/zsh` ou `tmux`).  
     * **Ring Buffer de Reconexão:** Mantém os últimos 64KB de output de cada sessão em memória (`VecDeque<u8>`). Se o usuário bloquear o celular ou plugar num monitor externo (causando recriação da Activity Android), ao enviar `PtyPayload::Reattach`, o daemon devolve o histórico imediatamente sem matar o processo em execução.  
  2. **LSP Bridge (`tokio::process`):**  
     * Gerencia o ciclo de vida de servidores LSP via `stdio` (ex: `rust-analyzer`, `basedpyright-langserver --stdio`, `vtsls --stdio`, `clangd`).  
     * **Framing / Unframing:**  
       * *Entrada (WebSocket \-\> StdIn):* Recebe a string `json_rpc` limpa do Monaco, calcula `json_rpc.len()`, formata `Content-Length: {}\r\n\r\n{}` e escreve no `ChildStdin`.  
       * *Saída (StdOut \-\> WebSocket):* Usa um `tokio::io::BufReader` no `ChildStdout`, lê os cabeçalhos até `\r\n\r\n`, extrai o `Content-Length`, lê o número exato de bytes do corpo JSON e despacha um `MultiplexedPacket::Lsp` pelo WebSocket.  
     * **Graceful Hibernation:** Se o WebSocket desconectar, os processos LSP permanecem vivos por até 30 minutos (configurável) para evitar reindexação quando o usuário voltar ao app.  
  3. **Virtual File System (VFS) & Watcher (`notify`):**  
     * Operações assíncronas usando `tokio::fs`. Escrita de arquivos sempre atômica (escreve em `.filename.avf.tmp` e faz `rename`).  
     * File watcher (`notify-debouncer-full`, que preserva o tipo do evento para `FsEventKind`) com debounce de 150ms, ativado por `WatchReq`, notificando o front-end sobre mudanças externas (ex: `git checkout`, artefatos gerados por scripts). Ignora `.git/objects`, `node_modules`, `target` e `.venv` por padrão.  
  4. **Port Detector (`/proc/net/tcp` scanner):**  
     * Task em background que lê `/proc/net/tcp` e `/proc/net/tcp6` a cada 2 segundos para detectar portas em estado `LISTEN` (`0A`). Emite `SystemMessage::PortOpened` para o front-end abrir um painel de Web Preview ou Jupyter automaticamente.

## **6\. Especificação do `studio-ui` (Front-end Camaleão: Mobile, Tablet & DeX)**

* **Framework UI:** React 19 \+ TypeScript \+ Zustand (gerenciamento de estado) \+ TailwindCSS.  
* **Core Editor:** `monaco-editor` integrado com `@codingame/monaco-vscode-api` e `monaco-languageclient`.  
* **Terminal:** `@xterm/xterm` com `@xterm/addon-webgl` e `@xterm/addon-fit`.  
* **Motor de Layout Adaptativo (`useAdaptiveLayout`):** O front-end reage em tempo real às dimensões da janela (`window.innerWidth`), orientação e presença de periféricos (`pointer: fine` vs `pointer: coarse`):  
  1. **Modo Compacto / Mobile (`width < 768px` \- Smartphones em modo retrato):**  
     * **Navegação Inferior (Bottom Navigation Bar):** Alternância rápida em 1 toque entre 4 visões em tela cheia: *Arquivos*, *Editor*, *Terminal* e *Preview/Git* (com suporte a *swipe* lateral nas bordas).  
     * **Editor Otimizado para Tela Pequena:** Oculta o *Minimap* automaticamente, reduz o espaçamento da *gutter* de números de linha e ativa *Word Wrap* por padrão para evitar rolagem horizontal no celular.  
     * **Drawer Deslizante:** A árvore de arquivos e a paleta de comandos abrem como *Bottom Sheets* ou gavetas sobrepostas.  
  2. **Modo Expandido / Workstation (`width >= 768px` \- Tablets, Foldables abertos, Samsung DeX e Android 16+ Desktop Mode):**  
     * **Layout VS Code Completo:** Activity Bar lateral esquerda, Sidebar retrátil, Área de Editores com suporte a *Split View* (Horizontal/Vertical) e Painel Inferior redimensionável para Terminal/Output.  
     * **Continuidade DeX Zero-Reload:** Ao conectar o celular em um monitor externo (transição de Modo Compacto para Expandido), o estado do Zustand preserva as instâncias do Monaco e chama `fitAddon.fit()` no Xterm.js, disparando `PtyPayload::Resize` para o daemon sem perder nenhuma linha digitada.  
* **Recursos de Ergonomia Mobile & Desktop:**  
  1. **Touch Modifier Bar (Auto-hide inteligente):** Barra compacta acoplada logo acima do teclado virtual (`visualViewport` API) exibida sempre que nenhum teclado físico é detectado. Contém teclas de precisão: `ESC`, `TAB`, `CTRL` (sticky), `ALT` (sticky), `{`, `}`, `(`, `)`, `[`, `]`, `:`, `;`, `/`, e trackpad virtual/setas direcionais com *repeat* suave.  
  2. **Keyboard Shortcut Interceptor:** Captura limpa de `Ctrl+P` (Quick Open), `Ctrl+Shift+P` (Command Palette), `Ctrl+B` (Toggle Sidebar) e \`Ctrl+\`\` (Toggle Terminal) quando em modo DeX/Teclado físico.  
  3. **Indicador de Saúde da VM:** Badge na Status Bar mostrando uso de CPU/RAM da VM Debian, latência do ping WebSocket (em ms) e botão de *Wake/Reconnect* rápido.  
  4. **Local AI Copilot Provider:** Integração nativa do Monaco (`registerInlineCompletionsProvider`) que aponta para um endpoint OpenAI-compatible configurável (ex: sua Intel Arc B580 na rede local/Tailscale rodando Qwen Coder para autocomplete FIM a custo zero).

## **7\. Plano de Implementação em Fases (Para Execução por Agentes)**

* **Fase 1 (Fundação & Protocolo):** Inicializar o workspace Cargo, definir todas as structs de mensagens em `crates/avf-protocol` e implementar o servidor `axum` básico em `crates/avf-daemon` com handshake autenticado (`Hello`/`Welcome`/`Rejected`), codificação MessagePack centralizada, ping/pong, telemetria de sistema e operações de arquivo (`VFS`, incluindo `VfsError` e watcher).  
* **Fase 2 (PTY & Terminal):** Implementar o `PtyManager` com ring buffer no daemon e conectar ao componente `Xterm.js` no front-end, garantindo suporte a cores ANSI, resize dinâmico e reconexão de sessão.  
* **Fase 3 (Adaptive Workbench, Monaco & VFS):** Construir o shell responsivo (Modo Compacto Mobile vs Modo Expandido Tablet/DeX) e integrar a árvore de arquivos real da VM ao Monaco Editor (múltiplas abas, dirty state, salvar com `Ctrl+S` ou botão na Touch Bar).  
* **Fase 4 (LSP Bridge):** Implementar o proxy de `stdio` para `Content-Length` no Rust e plugar no `monaco-languageclient` (validar com Python/`basedpyright` e Rust/`rust-analyzer`).  
* **Fase 5 (Empacotamento Android & Bootstrap):** Configurar o build Android do Tauri v2 (incluindo flags no `AndroidManifest.xml` para suporte a redimensionamento livre no DeX/Desktop Mode: `android:resizeableActivity="true"`) e criar o script `bootstrap-avf.sh` (instala o serviço systemd e gera o token em `~/.config/avf-studio/token`).

