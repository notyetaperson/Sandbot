#!/usr/bin/env python3
r"""
Nunkin SandBot runner.

Keeps the SandBot mod working while you're away: starts Minecraft (through Prism
Launcher or any launch command), joins the server, starts the bot, respawns and
rejoins after deaths and kicks, restarts the game if it crashes or freezes, and
shows the bot's progress in this console (optionally on Discord too).

Payouts keep working while your main account is offline: if the server refuses
/pay because the player is offline, the mod holds the money and pays it the
moment that player shows up in the player list (and retries every few minutes).

Needs only Python 3.9+ and no extra packages.

  Prism Launcher (recommended, fully automatic):
    python run_sandbot.py run --prism-instance "SandBot" --pay-to YourMainName

  Any other launcher that can start the game from a command line:
    python run_sandbot.py run --launch-cmd "<command>" --game-dir "<.minecraft folder>"

  Game started by hand (e.g. the official launcher):
    python run_sandbot.py attach --pay-to YourMainName

  One-off commands / status:
    python run_sandbot.py send pay
    python run_sandbot.py status
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

STATUS_FILE = "nunkin-status.json"
CONTROL_FILE = "nunkin-control.json"
CONFIG_FILE = "nunkin-sandbot.json"
DEFAULT_SERVER = "donutsmp.net"

BOT_COMMANDS = ("start", "stop", "pause", "resume", "sell", "rtp", "pay", "endcycle", "respawn", "join", "disconnect", "quit", "ping")
LEVEL_TAGS = {"DEBUG": "DBG", "INFO": "INF", "SUCCESS": "OK ", "WARN": "WRN", "ERROR": "ERR", "SERVER": "SRV"}


# --------------------------------------------------------------------------- helpers

def now() -> float:
    return time.time()


def say(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def money(value: float) -> str:
    value = float(value or 0)
    for limit, suffix in ((1e12, "T"), (1e9, "B"), (1e6, "M"), (1e4, "K")):
        if abs(value) >= limit:
            text = f"{value / limit:.2f}".rstrip("0").rstrip(".")
            return f"${text}{suffix}"
    return f"${value:,.0f}"


def clock(seconds: float) -> str:
    seconds = max(0, int(seconds))
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}" if hours else f"{minutes}:{secs:02d}"


def pid_alive(pid: int) -> bool:
    if not pid or pid <= 0:
        return False
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.OpenProcess.restype = wintypes.HANDLE
        kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel32.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == 259  # STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def process_name(pid: int) -> str:
    """Executable name of a running process ('' if unknown)."""
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.OpenProcess.restype = wintypes.HANDLE
        kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel32.QueryFullProcessImageNameW.argtypes = (wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD))
        kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = kernel32.OpenProcess(0x1000, False, pid)
        if not handle:
            return ""
        try:
            size = wintypes.DWORD(1024)
            buffer = ctypes.create_unicode_buffer(size.value)
            if not kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
                return ""
            return Path(buffer.value).name.lower()
        finally:
            kernel32.CloseHandle(handle)
    try:
        return Path(f"/proc/{pid}/comm").read_text().strip().lower()
    except OSError:
        result = subprocess.run(["ps", "-p", str(pid), "-o", "comm="], capture_output=True, text=True)
        return Path(result.stdout.strip()).name.lower()


def is_game_process(pid: int) -> bool:
    return pid_alive(pid) and "java" in process_name(pid)


def kill_process(pid: int) -> None:
    if not is_game_process(pid):
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
        return
    try:
        os.kill(pid, signal.SIGTERM)
        for _ in range(50):
            if not pid_alive(pid):
                return
            time.sleep(0.2)
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def default_minecraft_dir() -> Path:
    if os.name == "nt":
        return Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming")) / ".minecraft"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "minecraft"
    return Path.home() / ".minecraft"


# --------------------------------------------------------------------------- Prism Launcher

def find_prism_executable(explicit: str | None) -> Path | None:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    for name in ("prismlauncher", "PrismLauncher", "multimc", "MultiMC", "polymc"):
        found = shutil.which(name)
        if found:
            candidates.append(Path(found))
    if os.name == "nt":
        local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        program_files = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
        candidates += [
            local / "Programs" / "PrismLauncher" / "prismlauncher.exe",
            program_files / "PrismLauncher" / "prismlauncher.exe",
            Path.home() / "scoop" / "apps" / "prismlauncher" / "current" / "prismlauncher.exe",
        ]
    elif sys.platform == "darwin":
        candidates.append(Path("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def find_prism_data_dir(explicit: str | None, executable: Path | None) -> Path | None:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    if executable:
        candidates.append(executable.parent)  # portable install
    if os.name == "nt":
        candidates.append(Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming")) / "PrismLauncher")
    elif sys.platform == "darwin":
        candidates.append(Path.home() / "Library" / "Application Support" / "PrismLauncher")
    else:
        candidates += [Path.home() / ".local" / "share" / "PrismLauncher",
                       Path.home() / ".var" / "app" / "org.prismlauncher.PrismLauncher" / "data" / "PrismLauncher"]
    for candidate in candidates:
        if (candidate / "instances").is_dir():
            return candidate
    return None


def resolve_prism_instance(data_dir: Path, wanted: str) -> tuple[str, Path] | None:
    """Accepts an instance folder name or its display name; returns (instance id, game dir)."""
    instances = data_dir / "instances"
    folders = [p for p in instances.iterdir() if p.is_dir() and (p / "instance.cfg").is_file()]
    match = next((p for p in folders if p.name == wanted), None)
    if match is None:
        for folder in folders:
            try:
                for line in (folder / "instance.cfg").read_text(encoding="utf-8", errors="replace").splitlines():
                    if line.startswith("name=") and line[5:].strip().lower() == wanted.lower():
                        match = folder
            except OSError:
                continue
            if match:
                break
    if match is None:
        return None
    for sub in ("minecraft", ".minecraft"):
        if (match / sub).is_dir():
            return match.name, match / sub
    return match.name, match / "minecraft"


def list_prism_instances(data_dir: Path) -> list[str]:
    return sorted(p.name for p in (data_dir / "instances").iterdir() if (p / "instance.cfg").is_file())


# --------------------------------------------------------------------------- mod bridge

class Bridge:
    """Talks to the mod through the status/control files in the game's config folder."""

    def __init__(self, game_dir: Path):
        self.config_dir = game_dir / "config"
        self.status_path = self.config_dir / STATUS_FILE
        self.control_path = self.config_dir / CONTROL_FILE
        self.config_path = self.config_dir / CONFIG_FILE
        self.lock = threading.Lock()
        self.last_sent_id = 0

    def status(self) -> dict | None:
        try:
            with open(self.status_path, encoding="utf-8") as handle:
                return json.load(handle)
        except (OSError, ValueError):
            return None

    @staticmethod
    def age(status: dict | None) -> float:
        if not status:
            return float("inf")
        return max(0.0, now() - status.get("updated", 0) / 1000.0)

    def call(self, command: str, timeout: float = 10.0, **args) -> str | None:
        """Sends a command and waits for the game's answer. The game reads one command at a time,
        so calls are serialized."""
        with self.lock:
            return self.wait_ack(self.send(command, **args), timeout)

    def send(self, command: str, **args) -> int:
        self.config_dir.mkdir(parents=True, exist_ok=True)
        last_id = 0
        try:
            last_id = int(json.loads(self.control_path.read_text(encoding="utf-8")).get("id", 0))
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        command_id = max(last_id + 1, self.last_sent_id + 1, int(now() * 1000))
        self.last_sent_id = command_id
        payload = {"id": command_id, "command": command, "args": args, "sent": int(now() * 1000)}
        temp = self.control_path.with_name(CONTROL_FILE + ".tmp")
        temp.write_text(json.dumps(payload), encoding="utf-8")
        for attempt in range(40):
            try:
                os.replace(temp, self.control_path)
                break
            except PermissionError:
                time.sleep(0.05)
        return command_id

    def wait_ack(self, command_id: int, timeout: float = 15.0) -> str | None:
        deadline = now() + timeout
        while now() < deadline:
            ack = (self.status() or {}).get("ack") or {}
            if ack.get("id") == command_id:
                return str(ack.get("result", ""))
            time.sleep(0.25)
        return None

    def update_mod_config(self, updates: dict) -> dict:
        """Writes settings into the mod's config file (game must be closed); returns the previous values."""
        try:
            config = json.loads(self.config_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            config = {}
        previous = {key: config.get(key) for key in updates}
        config.update(updates)
        self.config_dir.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(json.dumps(config, indent=2), encoding="utf-8")
        return previous

    def restore_mod_config(self, previous: dict) -> None:
        try:
            config = json.loads(self.config_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        for key, value in previous.items():
            if value is None:
                config.pop(key, None)
            else:
                config[key] = value
        self.config_path.write_text(json.dumps(config, indent=2), encoding="utf-8")


# --------------------------------------------------------------------------- Discord notifications

class Notifier:
    """Posts messages to a Discord webhook from a background thread (batched, rate-limit aware)."""

    def __init__(self, url: str | None, account: str = ""):
        self.url = url
        self.account = account
        self.queue: queue.Queue[str] = queue.Queue()
        if url:
            threading.Thread(target=self._worker, name="discord-webhook", daemon=True).start()

    def post(self, text: str) -> None:
        if self.url:
            self.queue.put(text)

    def _worker(self) -> None:
        while True:
            lines = [self.queue.get()]
            time.sleep(2)
            while not self.queue.empty() and len("\n".join(lines)) < 1700:
                lines.append(self.queue.get_nowait())
            content = "\n".join(lines)[:1900]
            body = json.dumps({"username": f"SandBot {self.account}".strip(), "content": content}).encode("utf-8")
            request = urllib.request.Request(self.url, data=body, headers={
                "Content-Type": "application/json", "User-Agent": "NunkinSandBotRunner/1.0"})
            for attempt in range(3):
                try:
                    urllib.request.urlopen(request, timeout=15).close()
                    break
                except urllib.error.HTTPError as error:
                    if error.code == 429:
                        time.sleep(float(error.headers.get("Retry-After", "5")))
                        continue
                    say(f"Discord webhook error: HTTP {error.code}")
                    break
                except (urllib.error.URLError, OSError) as error:
                    say(f"Discord webhook unreachable: {error}")
                    time.sleep(5)


# --------------------------------------------------------------------------- supervisor

class Supervisor:
    def __init__(self, bridge: Bridge, options: argparse.Namespace, launch_command: list[str] | str | None, notifier: Notifier):
        self.bridge = bridge
        self.options = options
        self.launch_command = launch_command
        self.notifier = notifier
        self.exit_requested = threading.Event()
        self.close_game_on_exit = launch_command is not None and not options.leave_running
        self.launched_at = 0.0
        self.next_launch = 0.0
        self.launch_failures = 0
        self.game_pid = 0
        self.last_event_id: int | None = None
        self.last_summary = 0.0
        self.last_phase = ""
        self.not_in_world_since: float | None = None
        self.in_world_since: float | None = None
        self.last_join = 0.0
        self.last_start = 0.0
        self.last_respawn = 0.0
        self.pay_target_sent = False
        self.notices: set[str] = set()

    # -- launching -------------------------------------------------------

    def launch(self) -> None:
        self.launched_at = now()
        self.game_pid = 0
        say("Launching Minecraft...")
        creation = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        try:
            if isinstance(self.launch_command, str):
                subprocess.Popen(self.launch_command, shell=True, creationflags=creation, start_new_session=os.name != "nt",
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                subprocess.Popen(self.launch_command, creationflags=creation, start_new_session=os.name != "nt",
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError as error:
            say(f"Could not start the launcher: {error}")
            self.schedule_relaunch("launcher failed to start")

    def schedule_relaunch(self, reason: str) -> None:
        self.launch_failures += 1
        delay = min(600, self.options.restart_delay * (2 ** min(self.launch_failures - 1, 5)))
        self.next_launch = now() + delay
        self.launched_at = 0.0
        say(f"Restarting the game in {int(delay)}s ({reason})")
        self.notifier.post(f":warning: Restarting Minecraft in {int(delay)}s ({reason})")

    # -- main loop -------------------------------------------------------

    def run(self) -> None:
        status = self.bridge.status()
        if status and self.bridge.age(status) < 15 and status.get("state") == "running":
            say(f"Found a running game (pid {status.get('pid')}) - taking over")
        elif self.launch_command is None:
            say("Waiting for Minecraft with the SandBot mod (the 'Python runner bridge' setting must be on)...")
        while not self.exit_requested.is_set():
            try:
                self.step()
            except Exception as error:  # keep supervising no matter what
                say(f"Runner error: {error}")
            self.exit_requested.wait(self.options.poll)

    def step(self) -> None:
        status = self.bridge.status()
        age = self.bridge.age(status)
        state = (status or {}).get("state")
        pid = int((status or {}).get("pid") or 0)

        if status and state == "disabled":
            self.notice("bridge-off", "The 'Python runner bridge' setting was turned off in game - turn it back on to keep the runner in control.")
            return
        self.notices.discard("bridge-off")

        if status and state == "running" and age <= 15 and pid_alive(pid):
            if pid != self.game_pid:
                # A new game session numbers its log events from 1 again.
                self.last_event_id = 0 if self.launched_at else None
                self.game_pid = pid
                self.launched_at = 0.0
                self.launch_failures = 0
                self.last_join = self.last_start = self.last_respawn = 0.0
                self.not_in_world_since = self.in_world_since = None
                say(f"Connected to the game (pid {pid}, account {status.get('account', '?')})")
                self.notifier.account = status.get("account", "")
            self.notices.discard("frozen")
            self.handle_live(status)
            return

        if self.game_pid and pid == self.game_pid and is_game_process(pid):
            if state == "closing":
                return
            if age > self.options.freeze_timeout:
                if self.launch_command is not None:
                    say(f"The game has not responded for {int(age)}s - killing it")
                    self.notifier.post(f":warning: Minecraft froze ({int(age)}s without a heartbeat), restarting it")
                    kill_process(pid)
                    self.game_pid = 0
                    self.schedule_relaunch("game froze")
                else:
                    self.notice("frozen", f"The game has not responded for {int(age)}s")
            return

        if self.game_pid and not is_game_process(self.game_pid):
            say("The game closed")
            self.notifier.post(":warning: Minecraft closed")
            self.game_pid = 0
            self.in_world_since = None
            if self.launch_command is not None:
                self.schedule_relaunch("game closed")

        if self.launch_command is None:
            return
        if self.launched_at:
            if now() - self.launched_at > self.options.startup_timeout:
                self.schedule_relaunch(f"no heartbeat within {self.options.startup_timeout}s of launching")
            return
        if now() >= self.next_launch:
            self.launch()

    def handle_live(self, status: dict) -> None:
        self.print_events(status)
        self.print_summary(status)
        if self.options.no_keep_alive:
            return

        bot = status.get("bot") or {}
        stop_kind = bot.get("stopKind", "")
        stop_age = now() - (bot.get("stopTime") or 0) / 1000.0
        safety_ok = self.options.resume_after_safety_stop > 0 and stop_age > self.options.resume_after_safety_stop * 60

        if self.options.pay_to and not self.pay_target_sent:
            payout = status.get("payout") or {}
            if str(payout.get("target", "")).lower() != self.options.pay_to.lower():
                result = self.command("set-pay-target", quiet=True, player=self.options.pay_to)
                say(f"Payout player set to {self.options.pay_to} ({result})")
            self.pay_target_sent = True

        if not status.get("inWorld"):
            self.in_world_since = None
            if self.not_in_world_since is None:
                self.not_in_world_since = now()
            screen = status.get("screen")
            reconnecting = (status.get("reconnect") or {}).get("waiting") and screen == "disconnected"
            if screen == "connecting" or reconnecting:
                return
            if stop_kind == "SAFETY" and not safety_ok:
                self.notice("safety-offline", f"Bot stopped for safety ({bot.get('stopReason')}) - not rejoining. Use 'join' to rejoin.")
                return
            if stop_kind == "USER":
                self.notice("user-offline", "Bot was stopped and disconnected on purpose - use 'join' then 'start' to resume.")
                return
            if now() - self.not_in_world_since > self.options.join_delay and now() - self.last_join > 60:
                self.last_join = now()
                server = self.options.server or status.get("server") or DEFAULT_SERVER
                say(f"Joining {server}")
                self.command("join", quiet=True, server=server)
            return

        self.not_in_world_since = None
        if self.in_world_since is None:
            self.in_world_since = now()
        self.notices.discard("safety-offline")
        self.notices.discard("user-offline")

        if status.get("dead"):
            if now() - self.last_respawn > 15:
                self.last_respawn = now()
                self.command("respawn", quiet=True)
            return
        if bot.get("running"):
            self.notices.discard("user-stop")
            self.notices.discard("safety-stop")
            return
        if stop_kind == "USER":
            self.notice("user-stop", "Bot is stopped (stopped on purpose) - type 'start' to resume.")
            return
        if stop_kind == "SAFETY" and not safety_ok:
            self.notice("safety-stop", f"Bot stopped for safety: {bot.get('stopReason')} - type 'start' to resume.")
            return
        if now() - self.in_world_since > 10 and now() - self.last_start > 30:
            self.last_start = now()
            say("Starting the bot")
            self.command("start", quiet=True)

    def command(self, name: str, quiet: bool = False, **args) -> str:
        result = self.bridge.call(name, timeout=10, **args)
        if result is None:
            result = "no answer (is the game running with the bridge on?)"
        if not quiet:
            say(f"{name}: {result}")
        return result

    def notice(self, key: str, message: str) -> None:
        if key not in self.notices:
            self.notices.add(key)
            say(message)

    def print_events(self, status: dict) -> None:
        events = status.get("events") or []
        if self.last_event_id is None:
            self.last_event_id = max((e.get("id", 0) for e in events), default=0)
        for event in events:
            event_id = event.get("id", 0)
            if event_id <= self.last_event_id:
                continue
            self.last_event_id = event_id
            level = event.get("level", "INFO")
            if level == "DEBUG" and not self.options.verbose:
                continue
            message = event.get("message", "")
            print(f"[{event.get('time', '')}] {LEVEL_TAGS.get(level, level[:3])} {message}", flush=True)
            if level in ("SUCCESS", "WARN", "ERROR"):
                icon = {"SUCCESS": ":white_check_mark:", "WARN": ":warning:", "ERROR": ":x:"}[level]
                self.notifier.post(f"{icon} {message}")

    def print_summary(self, status: dict, force: bool = False) -> None:
        bot = status.get("bot") or {}
        phase = bot.get("phase", "?") if bot.get("running") else "STOPPED"
        if bot.get("paused"):
            phase = "PAUSED"
        if not force and phase == self.last_phase and now() - self.last_summary < self.options.summary_every:
            return
        self.last_phase = phase
        self.last_summary = now()
        session = (status.get("stats") or {}).get("session") or {}
        payout = status.get("payout") or {}
        parts = [phase]
        if bot.get("running"):
            parts.append(f"cycle #{bot.get('cycle', 0)} {clock(bot.get('cycleRemainingSeconds', 0))} left")
        parts.append(f"mined {session.get('sandMined', 0):,}")
        parts.append(f"sold {session.get('sandSold', 0):,}")
        parts.append(f"paid {money(session.get('paid', 0))}")
        if payout.get("pending", 0) > 0:
            who = payout.get("target", "?")
            parts.append(f"HELD {money(payout['pending'])} for {who} ({'online' if payout.get('targetOnline') else 'offline'})")
        if not status.get("inWorld"):
            parts.append(f"not in a world ({status.get('screen')})")
        say(" | ".join(parts))

    # -- shutdown --------------------------------------------------------

    def shutdown(self) -> None:
        if not self.close_game_on_exit:
            say("Runner stopped; the game keeps running.")
            return
        pid = self.game_pid
        if not pid or not is_game_process(pid):
            return
        say("Closing Minecraft...")
        self.bridge.call("quit", timeout=5)
        deadline = now() + 30
        while now() < deadline and pid_alive(pid):
            time.sleep(0.5)
        if pid_alive(pid):
            say("The game did not close in time - killing it")
            kill_process(pid)


def console_loop(supervisor: Supervisor) -> None:
    help_text = ("Commands: start, stop, pause, resume, sell, rtp, pay, endcycle, respawn, join [server], disconnect, "
                 "pay-to <name>, status, quit (close game + runner), detach (runner only), help")
    say(help_text)
    for line in sys.stdin:
        words = line.strip().split()
        if not words:
            continue
        name = words[0].lower()
        if name == "help":
            say(help_text)
        elif name == "status":
            status = supervisor.bridge.status()
            if status:
                supervisor.print_summary(status, force=True)
                say(f"Detail: {(status.get('bot') or {}).get('detail', '')}")
            else:
                say("No status yet")
        elif name in ("quit", "exit"):
            supervisor.exit_requested.set()
            return
        elif name == "detach":
            supervisor.close_game_on_exit = False
            supervisor.exit_requested.set()
            return
        elif name == "pay-to" and len(words) == 2:
            supervisor.options.pay_to = words[1]
            supervisor.pay_target_sent = True
            supervisor.command("set-pay-target", player=words[1])
        elif name == "join":
            supervisor.command("join", server=words[1] if len(words) > 1 else (supervisor.options.server or DEFAULT_SERVER))
        elif name in BOT_COMMANDS:
            supervisor.command(name)
        else:
            say(f"Unknown command '{name}'. {help_text}")


# --------------------------------------------------------------------------- entry points

def resolve_game_dir(options: argparse.Namespace) -> tuple[Path, str | None, Path | None]:
    """Returns (game dir, prism instance id, prism executable)."""
    if getattr(options, "prism_instance", None):
        executable = find_prism_executable(options.prism_path)
        data_dir = find_prism_data_dir(options.prism_data, executable)
        if data_dir is None:
            sys.exit("Couldn't find Prism Launcher's data folder; pass --prism-data <folder containing 'instances'>.")
        resolved = resolve_prism_instance(data_dir, options.prism_instance)
        if resolved is None:
            names = ", ".join(list_prism_instances(data_dir)) or "none"
            sys.exit(f"Prism instance '{options.prism_instance}' not found. Instances: {names}")
        instance_id, game_dir = resolved
        return (Path(options.game_dir) if options.game_dir else game_dir), instance_id, executable
    return (Path(options.game_dir) if options.game_dir else default_minecraft_dir()), None, None


def cmd_run(options: argparse.Namespace) -> None:
    game_dir, instance_id, executable = resolve_game_dir(options)
    if options.launch_cmd:
        launch_command: list[str] | str = options.launch_cmd
    elif instance_id:
        if executable is None:
            sys.exit("Couldn't find prismlauncher.exe; pass --prism-path <path to prismlauncher.exe>.")
        launch_command = [str(executable), "--launch", instance_id]
        if options.account:
            launch_command += ["--profile", options.account]
    else:
        sys.exit("Tell the runner how to start the game: --prism-instance <name> or --launch-cmd \"<command>\" (or use 'attach').")

    bridge = Bridge(game_dir)
    say(f"Game folder: {game_dir}")
    if not (game_dir / "mods").is_dir():
        say("Warning: no 'mods' folder there - make sure the SandBot mod is installed in this game folder.")

    previous_settings = None
    running = bridge.status()
    if not (running and bridge.age(running) < 15 and running.get("state") == "running"):
        updates = {"remoteControl": True, "autoReconnect": True, "autoRespawn": not options.no_respawn, "keepRunningUnfocused": True}
        if options.pay_to:
            updates.update({"payTarget": options.pay_to, "payEnabled": True})
        previous_settings = bridge.update_mod_config(updates)

    notifier = Notifier(options.webhook)
    supervisor = Supervisor(bridge, options, launch_command, notifier)
    supervise(supervisor)
    if previous_settings is not None and supervisor.close_game_on_exit and not options.keep_settings:
        bridge.restore_mod_config(previous_settings)


def cmd_attach(options: argparse.Namespace) -> None:
    game_dir, _, _ = resolve_game_dir(options)
    bridge = Bridge(game_dir)
    say(f"Game folder: {game_dir}")
    status = bridge.status()
    if not status or bridge.age(status) > 15:
        say("Tip: in the SandBot panel open Settings > Automation and turn on 'Python runner bridge'.")
    supervise(Supervisor(bridge, options, None, Notifier(options.webhook)))


def supervise(supervisor: Supervisor) -> None:
    threading.Thread(target=console_loop, args=(supervisor,), name="console", daemon=True).start()
    try:
        supervisor.run()
    except KeyboardInterrupt:
        pass
    supervisor.exit_requested.set()
    supervisor.shutdown()


def cmd_send(options: argparse.Namespace) -> int:
    game_dir, _, _ = resolve_game_dir(options)
    bridge = Bridge(game_dir)
    status = bridge.status()
    if not status or bridge.age(status) > 15 or status.get("state") != "running":
        print("The game isn't running with the SandBot runner bridge turned on.", file=sys.stderr)
        return 1
    args = {}
    if options.command == "join" and options.value:
        args["server"] = options.value
    if options.command == "set-pay-target":
        args["player"] = options.value or ""
    result = bridge.call(options.command, timeout=options.timeout, **args)
    if result is None:
        print("No answer from the game.", file=sys.stderr)
        return 1
    print(result)
    return 0 if result in ("ok", "pong") else 2


def cmd_status(options: argparse.Namespace) -> int:
    game_dir, _, _ = resolve_game_dir(options)
    status = Bridge(game_dir).status()
    if not status:
        print("No status file - start the game with the 'Python runner bridge' setting on.", file=sys.stderr)
        return 1
    if options.json:
        print(json.dumps(status, indent=2))
        return 0
    age = Bridge.age(status)
    bot = status.get("bot") or {}
    session = (status.get("stats") or {}).get("session") or {}
    payout = status.get("payout") or {}
    alive = age < 15 and status.get("state") == "running"
    print(f"Game:    {'running' if alive else 'not responding'} (pid {status.get('pid')}, updated {int(age)}s ago)")
    print(f"Account: {status.get('account', '?')} on {status.get('server') or '-'} ({'in world' if status.get('inWorld') else status.get('screen')})")
    print(f"Bot:     {bot.get('phase') if bot.get('running') else 'stopped'} - {bot.get('detail', '')}")
    print(f"Session: mined {session.get('sandMined', 0):,}, sold {session.get('sandSold', 0):,}, "
          f"earned {money(session.get('earned', 0))}, paid {money(session.get('paid', 0))}")
    target = payout.get("target") or "(not set)"
    held = f", holding {money(payout['pending'])} until they're online" if payout.get("pending", 0) > 0 else ""
    print(f"Payout:  to {target} ({'online' if payout.get('targetOnline') else 'offline'}){held}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run and supervise the Nunkin SandBot mod.",
                                     formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)

    def add_game_location(p: argparse.ArgumentParser) -> None:
        p.add_argument("--prism-instance", help="Prism Launcher instance (folder or display name) that has the mod installed")
        p.add_argument("--prism-path", help="path to prismlauncher.exe (auto-detected if omitted)")
        p.add_argument("--prism-data", help="Prism data folder containing 'instances' (auto-detected if omitted)")
        p.add_argument("--game-dir", help="game folder that contains 'mods' and 'config' (default: the normal .minecraft folder)")

    def add_supervisor_options(p: argparse.ArgumentParser) -> None:
        p.add_argument("--server", default=DEFAULT_SERVER, help=f"server to join (default {DEFAULT_SERVER})")
        p.add_argument("--pay-to", help="player that receives the payouts")
        p.add_argument("--webhook", help="Discord webhook URL for payout / warning / error messages")
        p.add_argument("--join-delay", type=float, default=20, help="seconds out of a world before rejoining (default 20)")
        p.add_argument("--resume-after-safety-stop", type=float, default=0, metavar="MINUTES",
                       help="restart the bot this many minutes after a safety stop/disconnect (default 0 = never)")
        p.add_argument("--no-keep-alive", action="store_true", help="only watch; never join/start/respawn automatically")
        p.add_argument("--summary-every", type=float, default=60, help="seconds between status lines (default 60)")
        p.add_argument("--poll", type=float, default=2, help=argparse.SUPPRESS)
        p.add_argument("--verbose", action="store_true", help="also print debug log lines")

    run = sub.add_parser("run", help="launch the game and keep the bot running")
    add_game_location(run)
    add_supervisor_options(run)
    run.add_argument("--launch-cmd", help="command that starts the game (instead of Prism Launcher)")
    run.add_argument("--account", help="Prism account (profile name) to launch with")
    run.add_argument("--no-respawn", action="store_true", help="don't respawn automatically after dying")
    run.add_argument("--restart-delay", type=float, default=15, help="seconds before restarting a closed/crashed game (default 15)")
    run.add_argument("--startup-timeout", type=float, default=420, help="seconds to wait for the game to start (default 420)")
    run.add_argument("--freeze-timeout", type=float, default=120, help="seconds without a heartbeat before killing the game (default 120)")
    run.add_argument("--leave-running", action="store_true", help="keep the game open when the runner exits")
    run.add_argument("--keep-settings", action="store_true", help="don't undo the runner's mod settings on exit")
    run.set_defaults(handler=cmd_run)

    attach = sub.add_parser("attach", help="watch and control a game you started yourself")
    add_game_location(attach)
    add_supervisor_options(attach)
    attach.set_defaults(handler=cmd_attach, freeze_timeout=120, leave_running=True, restart_delay=15, startup_timeout=300)

    send = sub.add_parser("send", help="send one command to the running bot")
    add_game_location(send)
    send.add_argument("command", choices=BOT_COMMANDS + ("set-pay-target",))
    send.add_argument("value", nargs="?", help="server for 'join', player for 'set-pay-target'")
    send.add_argument("--timeout", type=float, default=15)
    send.set_defaults(handler=cmd_send)

    status = sub.add_parser("status", help="print the bot's current status")
    add_game_location(status)
    status.add_argument("--json", action="store_true", help="print the raw status JSON")
    status.set_defaults(handler=cmd_status)
    return parser


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    options = build_parser().parse_args()
    result = options.handler(options)
    return result if isinstance(result, int) else 0


if __name__ == "__main__":
    sys.exit(main())
