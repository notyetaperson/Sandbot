# Nunkin SandBot

A client-side Fabric mod that automates sand farming on DonutSMP. SandBot searches for a large patch of sky-exposed sand, mines it, sells sand on a cycle, and can send profits to a configured player.

The repository also includes an optional Python runner that can launch or attach to Minecraft, supervise the mod, reconnect and restart after interruptions, and provide console or Discord status updates.

> **Please use automation responsibly.** Check the server's rules before using a bot or automation. This project is client-side; it does not install a bot on the Minecraft server.

## Requirements

- Minecraft **1.21.11**
- Fabric Loader **0.20.1 or later** and Fabric API
- Java **21 or later**
- (Optional) Python **3.9 or later** for the runner; it uses only the Python standard library

## Install the mod

1. Install Fabric for Minecraft 1.21.11 and add Fabric API to the game instance.
2. Download or build the SandBot mod JAR and place it in that instance's `mods` folder.
3. Launch the Fabric instance and press **B** to open the SandBot control panel.

The default key bindings can be changed in Minecraft's Controls settings. The bot toggle key is unbound by default.

## Build from source

Clone the repository and build with the Gradle wrapper:

```bash
git clone https://github.com/notyetaperson/Sandbot.git
cd Sandbot
./gradlew build
```

On Windows, use `gradlew.bat build`. The built mod JAR is written to `build/libs/`.

## Optional Python runner

The runner is useful when you want the game and mod to be supervised from a terminal. In the examples below, replace `YourMainName` with the player who should receive payouts.

### Launch with Prism Launcher

```bash
python run_sandbot.py run --prism-instance "SandBot" --pay-to YourMainName
```

If Prism Launcher or its data directory is not detected automatically, provide `--prism-path` or `--prism-data` as needed. Use `python3` instead of `python` on systems where that is the Python 3 command.

### Launch with another command

```bash
python run_sandbot.py run \
  --launch-cmd "<your Minecraft launch command>" \
  --game-dir "<path to the game directory>" \
  --pay-to YourMainName
```

The game directory should be the instance directory containing `mods` and `config`.

### Attach to a game you started yourself

```bash
python run_sandbot.py attach --game-dir "<path to the game directory>" --pay-to YourMainName
```

Enable **Python runner bridge** in the mod's **Settings → Automation** before attaching. For a standard Minecraft installation, `--game-dir` may be omitted and the runner uses the default `.minecraft` location.

### Runner commands

Check status or send a one-off command to a running game:

```bash
python run_sandbot.py status
python run_sandbot.py send ping
python run_sandbot.py send join donutsmp.net
```

The runner's interactive console also accepts commands such as `start`, `stop`, `pause`, `resume`, `sell`, `rtp`, `pay`, `join`, `status`, and `help`. Run `python run_sandbot.py --help` or `python run_sandbot.py run --help` to see all options, including Discord webhook notifications and restart behavior.

## Payouts

Set a payout player in the mod's settings or pass `--pay-to` to the runner. If the recipient is offline and the server refuses the payment, the mod can hold the amount and retry when the player appears online. Review the in-game status and payout settings before leaving automation unattended.

## License

The mod metadata currently declares **All Rights Reserved**. No permission to redistribute or modify the project is granted by that declaration; contact the project owner for licensing questions.
