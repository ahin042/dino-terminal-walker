# Dino Walker

**English** | [한국어](README.ko.md)

A JetBrains IDE plugin that lets a tiny pixel dinosaur walk back and forth along the top edge of your IDE terminal
(IntelliJ IDEA, WebStorm, and any IntelliJ-based IDE 2024.2 or newer).

Type `start dino` in the IDE terminal and the dinosaur appears. Type `exit dino` and a meteor takes care of the rest.

## Installation

### Install from a build artifact

```bash
./gradlew buildPlugin
```

Then in your IDE open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select
`build/distributions/dino-walker-<version>.zip`. This is a dynamic plugin, so a restart is usually not required.

### Try it in a sandbox IDE

```bash
./gradlew runIde
```

Requirements: JDK 21. The Gradle wrapper downloads everything else.

## Usage

| In the IDE terminal | What happens |
|---------------------|---|
| `start dino`        | A dinosaur appears above the Terminal tool window and starts walking. |
| `exit dino`         | The dinosaur stops, a meteor falls, and it blinks three times before disappearing. |
| Click the dino      | It jumps, using the original game's jump physics. |

## Shell setup fallback

If `start dino` / `exit dino` are not recognized (for example, if you turned off the IDE's shell integration under
**Settings → Tools → Terminal**, or your shell starts up in an unusual way), add the following line to your
`~/.bashrc` or `~/.zshrc`:

```sh
[ -n "$DINO_WALKER_INIT" ] && . "$DINO_WALKER_INIT"
```

It does nothing outside the IDE terminal. `dino start` / `dino exit` and the Find Action entries work without this
setup.

## Build

```bash
./gradlew buildPlugin    # build/distributions/*.zip
./gradlew verifyPlugin   # IntelliJ Plugin Verifier against IDEA 2024.2.6, IDEA 2026.3, WebStorm 2026.3
```

## Credits / License

- **Plugin code**: MIT License, see [`LICENSE`](LICENSE).
- **Dino sprites** (`src/main/resources/assets/dino_*.png`): cut from the sprite sheet of the
  [Chromium](https://www.chromium.org/) project's offline page (`components/neterror`), licensed under
  **BSD-3-Clause**. Copyright 2015 The Chromium Authors. See [`NOTICE`](NOTICE) for the full notice, which is also
  bundled inside the plugin as `META-INF/NOTICE`.
  The PNG files are distributed unmodified. The Chromium 200% sheet contains a few pixels of the offline page's
  background color (`#F7F7F7`) inside the T-Rex's feet; in the browser they blend into the page background, so the
  plugin renders exactly those pixels as transparent.
- **Meteor sprites** (`src/main/resources/assets/meteor_*.png`): created for this project and distributed under the
  same MIT License as the plugin code.
