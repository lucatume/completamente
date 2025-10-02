# completamente

Code writing helper from local models.
"Completamente" means "completely" in Italian and it's also the union of the words "complete" and "mind".

This plugin is my attempt at a port of the excellent [llama.vim][1] plugin to the IntelliJ plaform.
All the good ideas in this code come from the original plugin, all the bad ideas are my mistakes.

## Installation

The only way to install this plugin is to build it from the command-line.

```
git clone https://github.com/lucatume/completamente.git
cd completamente
./gradlew buildPlugin
```

Then install the plugin in the IDE and install it manually using `Settings/Preferences` -> `Plugins` -> `Install plugin from disk...`

## Usage

Start the `llama.cpp` server to provide completions using a command like this one:

```bash
llama-server --port 8012 --fim-qwen-30b-default
```

The plugin is set up to connect to the server at `http://localhost:8012`.

### Configuration

TODO - currently the setting are hard-coded and not available for changing.

## Support

None.
This project is a personal tool I'm actively developing for my own understanding and learning.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
[1]: https://github.com/ggml-org/llama.vim
