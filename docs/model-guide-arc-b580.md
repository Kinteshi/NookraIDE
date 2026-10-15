# Model Orchestration Guide (October 2026) + Local Intel Arc B580 Setup

This guide summarizes how to orchestrate current frontier models without blowing the API budget, and how to configure your **Intel Arc B580 (12GB VRAM)** on Linux as a local code and autocomplete engine for the **AVF Studio** project.

## 1. Model Matrix for Building AVF Studio

Instead of using a single expensive model for everything, split the `SPEC.md` phases by each model's strengths and cache cost:

| Project Task | Recommended Model | Why? |
| ----- | ----- | ----- |
| **Initial Architecture & Multi-File Generation (Phase 1)** | **Gemini 4 Argon** | **1M token** output limit and subsidized pricing until Nov 30, 2026. Can read the whole `SPEC.md` and emit all the Rust crates (`avf-protocol` and `avf-daemon`) at once without truncating. |
| **Workbench UI/UX & Monaco Integration (Phase 3)** | **Claude Fable 5.1** *(or Sonnet 5.5)* | Best aesthetic sense for React/Tailwind UI and very low cache read cost ($0.25/M tokens) to keep the front-end in memory. |
| **Heavy Tokio / LSP Concurrency Debugging (Phase 4)** | **GPT-6 Astra** *(or GPT-6.1 Sol)* | Excellent reasoning about *deadlocks* and *async streams* in Rust, and direct terminal execution to validate that JSON-RPC packets match the `Content-Length` header. |
| **Bulk Iteration, Unit Tests and Boilerplate** | **DeepSeek V4.1 Flash** | Scored 90.6% on Terminal-Bench 2.1 at **$0.15/M input ($0.003 cache hit)**. Ideal for running in a loop in Aider / Roo Code / Claude Code fixing `cargo clippy` warnings. |
| **Autocomplete (FIM) and Quick Function Refactoring** | **Qwen 2.5 Coder 14B** *(local on the Arc B580)* | **Zero cost**, local network latency, and fits comfortably in the B580's 12GB VRAM at `Q4_K_M`. |

## 2. Configuring the Intel Arc B580 (12GB) on Linux

The B580's Battlemage (Xe2) architecture works very well on Linux with the **`xe`** kernel driver (avoid the old `i915`). There are two good paths to run LLMs on it:

### Option A: Maximum Stability, Zero Headaches (Vulkan backend via `llama.cpp`)

The Vulkan backend in `llama.cpp` is numerically accurate, doesn't suffer from oneAPI toolkit update breakage, and delivers **18 to 25 tokens/s** on quantized 14B models.

```bash
# 1. Make sure the Intel Vulkan drivers (Mesa ANV) are installed
# On Ubuntu/Debian:
sudo apt update && sudo apt install -y mesa-vulkan-drivers vulkan-tools libvulkan-dev cmake build-essential

# On Arch/CachyOS/Fedora, check with:
vulkaninfo --summary | grep -i "Arc"

# 2. Clone and build llama.cpp with Vulkan support
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp
cmake -B build -DGGML_VULKAN=ON
cmake --build build --config Release -j$(nproc)

# 3. Start the OpenAI-compatible server on port 8012 with Qwen 2.5 Coder 14B
./build/bin/llama-server \
  -hf bartowski/Qwen2.5-Coder-14B-Instruct-GGUF:Q4_K_M \
  --device Vulkan0 \
  -ngl 99 \
  -c 16384 \
  --flash-attn \
  --host 0.0.0.0 \
  --port 8012
```

### Option B: Maximum Raw Speed (Intel's official IPEX-LLM / SYCL)

To get **35 to 45 tokens/s** from the B580 using Intel's XMX matrix extensions, the cleanest way (without polluting the main OS with oneAPI packages) is the official **IPEX-LLM** Docker image:

```bash
docker run -d \
  --net=host \
  --device=/dev/dri \
  -v ~/.ollama/models:/root/.ollama/models \
  -e OLLAMA_HOST=0.0.0.0:11434 \
  -e OLLAMA_NUM_GPU=999 \
  -e ZES_ENABLE_SYSMAN=1 \
  --name=arc-b580-llm \
  intelanalytics/ipex-llm-inference-cpp-xpu:latest \
  bash -c "mkdir -p /llm/ollama && cd /llm/ollama && init-ollama && ./ollama serve"
```

Then pull the optimized model:

```bash
docker exec -it arc-b580-llm /llm/ollama/ollama run qwen2.5-coder:14b
```

## 3. Using the Arc B580 with Your IDE on the Tablet

1. **While developing AVF Studio (on the PC):** Point your **Continue.dev** or **Roo Code** extension in VS Code to `http://localhost:8012/v1` (or `11434` with IPEX-LLM) for unlimited *Tab Autocomplete* while using **Argon / Fable** to architect the larger files.
2. **As a native feature of your app on the tablet:** With **Tailscale** installed on your Linux PC and the Android tablet (or on the same Wi-Fi), your **AVF Studio** app on the tablet can connect directly to your Arc B580 machine's IP (`http://100.x.y.z:8012/v1`). You code on the couch with the 120Hz tablet, compile locally in AVF's Debian, and use your desktop GPU for AI without spending a cent on APIs.
