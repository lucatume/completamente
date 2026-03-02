This project is an IntelliJ plugin called "completamente".

It provides fill-in-the-middle (FIM) and next-edit prediction (NEP) completions for IntelliJ IDEs using a local llama.cpp server. Built around the SweepAI 1.5b FIM/NEP model.

Language: Kotlin.

## Reference
The plugin is a hybrid of `llama.vim` and the SweepAI approach.
The `llama.vim` source is in `ref/llama.vim/autoload/llama.vim` for reference, but the plugin has diverged significantly.
Use `llama.vim` as a loose guide, not as the source of truth.

## Code style
Prefer data classes for moving information and pure functions for transforming it. 
Use classes only when required by IntelliJ's service system. Concessions for performance are acceptable.

## Testing
See [src/test/README.md](src/test/README.md) for testing conventions and data generation.
