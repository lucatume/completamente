# Reference Documents

| # | File | Type | Description |
|---|------|------|-------------|
| 01 | 01-discovery-nep-solutions.md | discovery | How existing tools implement Next Edit Prediction |
| 02 | 02-discovery-intellij-nep-api.md | discovery | IntelliJ platform APIs for inline completion and NEP |
| 03 | 03-discovery-zed-approach.md | discovery | How Zed editor implements inline completions |
| 04 | 04-discovery-continue-approach.md | discovery | How Continue.dev implements inline completions |
| 05 | 05-discovery-sweep-approach.md | discovery | How SweepAI approaches FIM and NEP |
| 06 | 06-harness-download-sweepai-model.py | harness | Script to download the SweepAI 1.5b model |
| 07 | 07-harness-sweepai-llama-cpp-server.sh | harness | Script to start llama.cpp server with the SweepAI model |
| 08 | 08-harness-test-sweepai-model.py | harness | Test SweepAI model with a Python FIM prompt |
| 09 | 09-harness-test-sweepai-model-php.py | harness | Test SweepAI model with a PHP FIM prompt |
| 10 | 10-harness-test-sweepai-model-js.py | harness | Test SweepAI model with a JavaScript FIM prompt |
| 11 | 11-harness-test-sweepai-model-ts.py | harness | Test SweepAI model with a TypeScript FIM prompt |
| 12 | 12-harness-test-sweepai-model-css.py | harness | Test SweepAI model with a CSS FIM prompt |
| 13 | 13-harness-test-sweepai-model-php-extra-input.py | harness | Test SweepAI model with PHP and extra context input |
| 14 | 14-output-sweepai-model.txt | output | Output from harness 08 (Python FIM test) |
| 15 | 15-output-sweepai-model-php.txt | output | Output from harness 09 (PHP FIM test) |
| 16 | 16-output-sweepai-model-js.txt | output | Output from harness 10 (JavaScript FIM test) |
| 17 | 17-output-sweepai-model-ts.txt | output | Output from harness 11 (TypeScript FIM test) |
| 18 | 18-output-sweepai-model-css.txt | output | Output from harness 12 (CSS FIM test) |
| 19 | 19-output-sweepai-model-php-extra-input.txt | output | Output from harness 13 (PHP with extra context) |
| 20 | 20-harness-test-nep-dependency-change.py | harness | Test NEP with dependency file changes |
| 21 | 21-output-nep-dependency-change.txt | output | Output from harness 20 (NEP dependency test) |
| 22 | 22-discovery-sweepai-client-approach.md | discovery | SweepAI client-side approach to completions |
| 23 | 23-design-diff-tracking.md | design | Design for tracking file diffs for NEP context |
| 24 | 24-discovery-sweepai-prompt-structure.md | discovery | Structure and format of SweepAI prompts |
| 25 | 25-design-chunk-ring-context-assembly.md | design | Design for chunk ring buffer context assembly |
| 26 | 26-discovery-remote-robot-agent-integration.md | discovery | Integration with Remote Robot for agent testing |
| 27 | 27-harness-prompt-from-file.py | harness | Harness to build and send prompts from file input |
| 28 | 28-discovery-llamacpp-prompt-caching-for-fim.md | discovery | llama.cpp prompt caching strategies for FIM completions |
| 29 | 29-design-server-management.md | design | Design for managed completion server lifecycle |
| 30 | 30-plan-server-management.md | plan | Implementation plan for server management feature |
| 31 | 31-harness-prompt-cache-effectiveness.py | harness | Measure KV cache reuse across editing sessions with different prompt orderings |
| 32 | 32-output-prompt-cache-effectiveness.txt | output | Output from harness 31 (prompt cache effectiveness) |
| 33 | 33-discovery-intellij-file-level-symbol-references.md | discovery | IntelliJ API for collecting project files referenced by symbols in a file |
| 34 | 34-design-token-budgeting.md | design | Token budgeting for prompt assembly within 8192-token context window |
