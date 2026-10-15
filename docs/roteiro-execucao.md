# **Roteiro de Execução Local — Como Iniciar com Claude Code / Antigravity**

Você não precisa de um tablet ou celular plugado agora para desenvolver 95% do projeto. Como o avf-daemon é um binário Linux padrão e o front-end roda sobre Tauri/Vite, **você pode desenvolver e testar o Daemon e a UI diretamente no seu PC Linux (x86\_64)**, simulando a tela de celular, tablet e DeX no próprio desktop antes de cruzar a compilação para **ARM64 (aarch64)** e jogar no celular.

## **Entendendo as duas dúvidas cruciais: ARM64 e Async**

### **1\. "O Linux no Android roda em ARM (aarch64). Como vou testar isso no meu PC (x86\_64)?"**

O sistema operacional dentro do AVF do Android é um **Debian Linux comum**. Para o código Rust (tokio, axum, portable-pty, notify) e para o Linux (ext4, /proc/net/tcp, stdio), **não faz diferença nenhuma se o processador embaixo é um Intel/AMD x86\_64 ou um Snapdragon/Tensor aarch64**.  
Portanto, seu fluxo de testes tem **3 estágios super simples**:

> 1. **Estágio 1 — No dia a dia (Nativo no seu PC x86\_64):** Você roda cargo run \-p avf-daemon normalmente no seu Linux desktop. Ele vai abrir o terminal do seu PC, ler os arquivos do seu PC e iniciar o pyright/rust-analyzer instalados no seu PC. Zero emulação, compilação instantânea\!  
> 2. **Estágio 2 — Teste do Binário ARM64 no próprio PC (qemu-user-static):** Quando quiser garantir que o binário compilado para ARM64 está funcionando antes de mandar pro celular, você não precisa subir uma VM pesada. Basta compilar com cargo zigbuild \--target aarch64-unknown-linux-musl (que gera um binário estático único, sem depender da versão do glibc do Debian do celular) e rodar ./target/aarch64-unknown-linux-musl/debug/avf-daemon direto no seu terminal\! O Linux do seu PC (via binfmt\_misc \+ qemu-user-static) executa binários ARM64 de forma transparente.  
> 3. **Estágio 3 — Teste Real no Celular/Tablet via adb forward:** Mesmo antes de gerar o APK do Tauri, você pode copiar o binário ARM64 do avf-daemon para dentro do Debian do seu celular, rodá-lo lá dentro e usar no seu PC o comando adb forward tcp:19860 tcp:19860. Assim, o navegador do seu PC se conecta em tempo real ao Daemon rodando dentro do processador ARM do seu celular via cabo USB\!

### **2\. "Vai ter que ser async?"**

**Sim, obrigatoriamente async (usando o runtime tokio no Rust)**, mas não por causa do ARM — e sim porque o Daemon é um **multiplexador de eventos concorrentes**.  
Pense no que acontece ao mesmo tempo em uma única conexão WebSocket entre o app e a VM:

> * Você está digitando no editor \-\> enviando pacotes LSP (stdin).  
> * O rust-analyzer ou pyright termina de analisar um tipo \-\> responde pelo stdout de forma assíncrona.  
> * Um script Python está rodando no terminal (PTY) \-\> cuspindo logs no stdout a cada 50ms.  
> * O watcher de arquivos (notify) detecta que o script criou um .csv novo \-\> avisa a árvore de arquivos da UI.  
> * O scanner de portas vê que o FastAPI abriu a porta 8000 \-\> dispara um evento para abrir o preview.

Se o daemon fosse síncrono/bloqueante, enquanto ele esperasse o pyright responder, o seu terminal travaria. Com o tokio::select\! e canais assíncronos (tokio::sync::mpsc), o Rust gerencia todas essas fontes de I/O simultaneamente consumindo menos de **15 MB de RAM** e quase zero de CPU em idle.

## **Passo 1: Preparar o Terreno no seu Terminal Linux (2 minutos)**

Abra o terminal na sua máquina Linux, crie a pasta do projeto e instale o cargo-zigbuild (a melhor ferramenta hoje para compilar Rust para ARM64 sem dor de cabeça de linker C):  
mkdir \-p \~/AVFStudio  
cd \~/AVFStudio  
git init

\# Crie as pastas base do monorepo para o agente não se perder  
mkdir \-p crates/avf-protocol crates/avf-daemon apps scripts

\# Adicione o target ARM64 estático (musl) no seu Rust e o cargo-zigbuild  
rustup target add aarch64-unknown-linux-musl  
cargo install cargo-zigbuild

*(Salve o arquivo SPEC.md na raiz \~/AVFStudio/SPEC.md).*

## **Passo 2: Onde usar cada Ferramenta (Claude Code vs. Antigravity)**

> * **Claude Code (no terminal do VS Code):** É o seu **construtor principal de back-end, concorrência tokio e arquitetura**. Como ele roda direto no shell, ele mesmo vai executar cargo check, cargo test, instalar pacotes com pnpm e corrigir qualquer erro de borrow checker ou async/await do Rust em tempo real.  
> * **Antigravity:** Excelente para **geração visual rápida, ajustes finos do Layout Adaptativo (Mobile vs. DeX) e exploração paralela de componentes React/Tailwind**. Quando chegar na Fase 3 (UI do Workbench), você pode usar o Antigravity para prototipar e refinar a *Touch Modifier Bar* e as transições responsivas entre tela vertical de celular e tela horizontal de DeX.

## **Passo 3: Os Prompts Exatos (Copie e Cole em Ordem)**

Abra o **Claude Code** dentro da pasta \~/AVFStudio e mande um prompt por vez. Só avance para a próxima fase quando a anterior estiver compilando sem erros.

### **Prompt 1 — Fase 1: Fundação Rust Async (avf-protocol \+ avf-daemon VFS)**

**Cole no Claude Code:**  
Leia o arquivo @SPEC.md na raiz do projeto. Vamos executar estritamente a Fase 1 (Fundação & Protocolo) de forma 100% assíncrona com Tokio e compatível tanto com x86\_64 quanto com aarch64.

1\. Crie o \`Cargo.toml\` do workspace na raiz incluindo \`crates/avf-protocol\` e \`crates/avf-daemon\`.  
2\. Implemente \`crates/avf-protocol\` com todas as structs e enums especificados na Seção 4 do SPEC.md (\`MultiplexedPacket\`, \`SystemMessage\`, \`PtyPayload\`, \`VfsMessage\`, \`FileEntry\`), adicionando suporte a \`serde\`, \`serde\_bytes\`, \`uuid\` (feature \`serde\`) e \`rmp-serde\`, com as funções centralizadas \`encode\`/\`decode\` e os testes de round-trip da Seção 4.4.  
3\. Implemente \`crates/avf-daemon\` usando \`tokio\` (full), \`axum\` (com feature \`ws\`), \`sysinfo\` (para métricas reais de CPU/RAM no \`Pong\`), e \`notify\` / \`tokio::fs\` para responder de forma não-bloqueante a todas as mensagens do \`VfsMessage\` (\`ListDirReq\`, \`ReadFileReq\`, \`WriteFileReq\` com escrita atômica via arquivo temporário).  
4\. Use um canal \`tokio::sync::mpsc::unbounded\_channel\` por conexão WebSocket para que múltiplas tasks em background (VFS watcher, PTY, LSP, System) possam enviar \`MultiplexedPacket\` para o \`SplitSink\` do WebSocket sem concorrência de lock.  
5\. Exponha o endpoint \`/health\` e o WebSocket multiplexado em \`/ws\` na porta \`127.0.0.1:19860\`, com o handshake \`Hello\`/\`Welcome\`/\`Rejected\`, token (\`AVF\_TOKEN\` ou \`\~/.config/avf-studio/token\`) e allowlist de \`Origin\` da Seção 4.2.  
6\. Rode \`cargo check\` e \`cargo test\` até compilar com zero erros e zero warnings.

### **Prompt 2 — Fase 2: PTY Manager com Ring Buffer e Bridge Async**

**Cole no Claude Code após o Prompt 1 passar:**  
Excelente. Agora vamos para a parte de back-end da Fase 2 do @SPEC.md (Seção 5, item 1 \- PTY Manager).

1\. Adicione a crate \`portable-pty\` ao \`crates/avf-daemon\`.  
2\. Crie o módulo \`src/pty.rs\` que gerencia múltiplas sessões de terminal identificadas por \`Uuid\` em um \`Arc\< tokio::sync::RwLock\<HashMap\<Uuid, PtySession\>\> \>\`.  
3\. Como a leitura/escrita do \`portable-pty\` é síncrona (bloqueante de sistema operacional), isole o loop de leitura do \`MasterPty\` dentro de \`tokio::task::spawn\_blocking\`, enviando os bytes lidos por um canal assíncrono do Tokio para o WebSocket, sem jamais bloquear o runtime async.  
4\. Implemente \`PtyPayload::Spawn\`, \`Input\`, \`Output\`, \`Resize\` e \`Reattach\`.  
5\. Implemente um Ring Buffer (\`VecDeque\<u8\>\`) de 64KB por sessão para que, caso o cliente WebSocket reconecte e envie \`PtyPayload::Reattach { session\_id }\`, o daemon devolva todo o histórico recente imediatamente e continue transmitindo o output do processo vivo.  
6\. Integre o \`PtyManager\` ao loop principal do WebSocket em \`/ws\` e valide a compilação com \`cargo check\`.

### **Prompt 3 — Fase 3: Front-end Camaleão (Mobile, Tablet & DeX) \+ Xterm \+ Monaco**

**Cole no Claude Code (ou no Antigravity):**  
Agora vamos criar o front-end em \`apps/studio-ui\` seguindo as Fases 2 e 3 e a Seção 6 do @SPEC.md.

1\. Inicialize um projeto React \+ TypeScript \+ Vite \+ TailwindCSS em \`apps/studio-ui\`.  
2\. Instale \`zustand\`, \`lucide-react\`, \`@xterm/xterm\`, \`@xterm/addon-fit\`, \`@xterm/addon-webgl\` e \`@monaco-editor/react\` (ou \`monaco-editor\` direto).  
3\. Crie o cliente WebSocket multiplexado (\`src/services/wsClient.ts\`) que conecta em \`ws://127.0.0.1:19860/ws\`, mantém reconexão automática, calcula a latência via \`Ping/Pong\` e despacha eventos para a store do Zustand.  
4\. Implemente o hook \`useAdaptiveLayout\` e o Workbench Camaleão:  
   \- Em telas \`\< 768px\` (Modo Smartphone): exiba Bottom Navigation Bar alternando entre Explorer, Editor e Terminal em tela cheia, com a Touch Modifier Bar (\`ESC\`, \`TAB\`, \`CTRL\` sticky, chaves, setas) fixa na parte inferior.  
   \- Em telas \`\>= 768px\` (Modo Tablet / Samsung DeX / Desktop): exiba o layout completo do VS Code (Activity Bar, Sidebar do File Explorer, Editor com múltiplas abas e Painel Inferior de Terminal redimensionável).  
5\. Conecte o File Explorer às mensagens \`VfsMessage\` do daemon (permitindo navegar nas pastas reais do Linux, abrir arquivos no Monaco Editor e salvar com \`Ctrl+S\` ou botão na Touch Bar) e conecte o componente Xterm.js ao \`PtyPayload\` do daemon.  
6\. Garanta que \`pnpm build\` rode sem erros de TypeScript.

### **Prompt 4 — Fase 4: LSP Bridge Async em Rust**

**Cole no Claude Code:**  
Vamos implementar a Fase 4 do @SPEC.md: o \`LspRouter\` em \`crates/avf-daemon/src/lsp.rs\` e o scanner de portas em \`src/ports.rs\`.

1\. Crie o módulo \`lsp.rs\` usando \`tokio::process::Command\` para iniciar servidores LSP sob demanda (ex: \`basedpyright-langserver \--stdio\`, \`rust-analyzer\`, \`vtsls \--stdio\`) com \`Stdio::piped()\`.  
2\. Implemente o framing e unframing assíncrono (\`tokio::io::AsyncBufReadExt\` e \`AsyncReadExt::read\_exact\`) do cabeçalho \`Content-Length: \<bytes\>\\r\\n\\r\\n\` no \`ChildStdin\` e \`ChildStdout\`, repassando apenas a string JSON-RPC limpa através de \`MultiplexedPacket::Lsp\` no WebSocket.  
3\. Implemente a hibernação graciosa: se o WebSocket cair, mantenha os processos LSP vivos em memória para reconexão rápida.  
4\. Crie \`src/ports.rs\` que lê \`/proc/net/tcp\` a cada 2 segundos de forma assíncrona para detectar portas que entraram em estado LISTEN (\`0A\`) e emite \`SystemMessage::PortOpened\`.  
5\. Valide com \`cargo check\` e crie um teste unitário assíncrono (\`\#\[tokio::test\]\`) para o parser de \`Content-Length\` do LSP.

## **Passo 4: Como Testar no PC e Como Compilar para ARM64 (Android AVF)**

### **A. Testando no seu PC agora mesmo (Desenvolvimento diário em x86\_64)**

> 1. **Terminal 1 (Rodando o Daemon no PC):**  
>    cargo run \-p avf-daemon

> 2. **Terminal 2 (Rodando a UI):**  
>    cd apps/studio-ui && pnpm dev

> 3. Abra http\://localhost:5173 no navegador, aperte F12 e alterne entre resolução de **Celular (412x915)** e **DeX/Tablet (1920x1080)**.

### **B. Gerando o Binário ARM64 Estático para a VM Debian do Android**

Quando quiser rodar o daemon dentro do Linux (AVF) do seu celular ou tablet:  
\# Compila para ARM64 estático (musl) — funciona em qualquer versão do Debian sem erro de glibc\!  
cargo zigbuild \--release \-p avf-daemon \--target aarch64-unknown-linux-musl

O binário final estará pronto em:  
target/aarch64-unknown-linux-musl/release/avf-daemon  
Você pode simplesmente jogá-lo na pasta Downloads do seu Android (que aparece montada em /mnt/shared dentro da VM Debian do AVF) e rodá-lo lá dentro\!