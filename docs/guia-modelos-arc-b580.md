# **Guia de Autocraft (Outubro 2026\) \+ Setup Local Intel Arc B580**

Este guia resume como orquestrar os modelos de fronteira atuais sem estourar o orçamento de API e como configurar sua **Intel Arc B580 (12GB VRAM)** no Linux para servir como motor local de código e autocomplete para o projeto **AVF Studio**.

## **1\. Matriz de Modelos para Construir o AVF Studio**

Em vez de usar um único modelo caro para tudo, divida as fases do `SPEC.md` de acordo com o ponto forte e o custo de cache de cada um:

| Tarefa no Projeto | Modelo Recomendado | Por que usar? |
| ----- | ----- | ----- |
| **Arquitetura Inicial & Geração de Multi-Arquivos (Fase 1\)** | **Gemini 4 Argon** | Limite de saída de **1M tokens** e preço subsidiado até 30/nov/2026. Capaz de ler o `SPEC.md` inteiro e cuspir todas as crates Rust (`avf-protocol` e `avf-daemon`) de uma vez sem truncar. |
| **UI/UX do Workbench & Integração Monaco (Fase 3\)** | **Claude Fable 5.1** *(ou Sonnet 5.5)* | Melhor senso estético de interface React/Tailwind e custo de leitura de cache baixíssimo (\$0.25/M tokens) para manter o front-end na memória. |
| **Debug Pesado de Concorrência Tokio / LSP (Fase 4\)** | **GPT-6 Astra** *(ou GPT-6.1 Sol)* | Excelente raciocínio sobre *deadlocks*, *async streams* em Rust e execução direta no terminal para validar se os pacotes JSON-RPC estão batendo com o header `Content-Length`. |
| **Iteração em Massa, Testes Unitários e Boilerplate** | **DeepSeek V4.1 Flash** | Bateu 90.6% no Terminal-Bench 2.1 custando **$0.15/Minput($0.003 cache hit)**. Ideal para deixar rodando em loop no Aider / Roo Code / Claude Code corrigindo warnings do `cargo clippy`. |
| **Autocomplete (FIM) e Refatoração Rápida de Funções** | **Qwen 2.5 Coder 14B** *(Local na Arc B580)* | **Custo zero**, latência local na sua rede e cabe perfeitamente nos 12GB de VRAM da sua B580 em `Q4_K_M`. |

## **2\. Configurando a Intel Arc B580 (12GB) no Linux**

A arquitetura Battlemage (Xe2) da B580 funciona muito bem no Linux usando o driver kernel **`xe`** (evite o antigo `i915`). Hoje você tem dois caminhos excelentes para rodar LLMs nela:

### **Opção A: Máxima Estabilidade e Zero Dor de Cabeça (Backend Vulkan via `llama.cpp`)**

O backend Vulkan no `llama.cpp` é matematicamente preciso, não sofre com quebras de atualização do toolkit oneAPI e entrega **18 a 25 tokens/s** em modelos de 14B quantizados.

\# 1\. Garanta que os drivers Vulkan da Intel (Mesa ANV) estejam instalados  
\# No Ubuntu/Debian:  
sudo apt update && sudo apt install \-y mesa-vulkan-drivers vulkan-tools libvulkan-dev cmake build-essential

\# No Arch/CachyOS/Fedora, verifique com:  
vulkaninfo \--summary | grep \-i "Arc"

\# 2\. Clone e compile o llama.cpp com suporte a Vulkan  
git clone https\://github.com/ggml-org/llama.cpp  
cd llama.cpp  
cmake \-B build \-DGGML\_VULKAN=ON  
cmake \--build build \--config Release \-j\$(nproc)

\# 3\. Suba o servidor OpenAI-compatible na porta 8012 com Qwen 2.5 Coder 14B  
./build/bin/llama-server \\  
  \-hf bartowski/Qwen2.5-Coder-14B-Instruct-GGUF:Q4\_K\_M \\  
  \--device Vulkan0 \\  
  \-ngl 99 \\  
  \-c 16384 \\  
  \--flash-attn \\  
  \--host 0.0.0.0 \\  
  \--port 8012

### **Opção B: Máxima Velocidade Bruta (IPEX-LLM / SYCL Oficial da Intel)**

Se você quiser extrair **35 a 45 tokens/s** da B580 usando as extensões matriciais XMX da Intel, a forma mais limpa (sem sujar o sistema operacional principal com pacotes oneAPI) é rodar a imagem Docker oficial do **IPEX-LLM**:

docker run \-d \\  
  \--net=host \\  
  \--device=/dev/dri \\  
  \-v \~/.ollama/models:/root/.ollama/models \\  
  \-e OLLAMA\_HOST=0.0.0.0:11434 \\  
  \-e OLLAMA\_NUM\_GPU=999 \\  
  \-e ZES\_ENABLE\_SYSMAN=1 \\  
  \--name=arc-b580-llm \\  
  intelanalytics/ipex-llm-inference-cpp-xpu:latest \\  
  bash \-c "mkdir \-p /llm/ollama && cd /llm/ollama && init-ollama && ./ollama serve"

Depois, basta puxar o modelo otimizado:

docker exec \-it arc-b580-llm /llm/ollama/ollama run qwen2.5-coder:14b

## **3\. Como usar a Arc B580 junto com a sua IDE no Tablet**

1. **Durante o Desenvolvimento do AVF Studio (no PC):** Aponte sua extensão **Continue.dev** ou **Roo Code** no VS Code para `http://localhost:8012/v1` (ou `11434` no IPEX-LLM) para ter *Tab Autocomplete* ilimitado enquanto usa o **Argon / Fable** para arquitetar os arquivos maiores.  
2. **Como Feature Nativa do seu App no Tablet:** Com o **Tailscale** instalado no seu PC Linux e no Tablet Android (ou na mesma rede Wi-Fi), o seu app **AVF Studio** no tablet pode se conectar direto ao IP da sua máquina com a Arc B580 (`http://100.x.y.z:8012/v1`). Assim, você programa no sofá usando o tablet em 120Hz, compilando o código localmente no Debian do AVF e usando a GPU do seu desktop para inteligência artificial sem gastar um centavo de API.

