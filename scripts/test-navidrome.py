#!/usr/bin/env python3
"""Start/stop the owned local Navidrome fixture used by integration tests."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from test_containers import image

ROOT = Path(__file__).resolve().parent.parent
ENVIRONMENT = ROOT / ".test-environment"
NAME = "nevumi-navidrome-test"
LABEL = "me.d3sox.nevumi.test"
ADDRESS = "http://127.0.0.1:14533"
PORT = 14533
MUSIC_FOLDER = "music"
DATA_FOLDER = "navidrome-data"


def docker(*arguments):
    return subprocess.run(["docker", *arguments], text=True, capture_output=True)


def checked(result):
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def owned_container():
    result = docker("inspect", NAME)
    if result.returncode:
        if "no such object" in result.stderr.lower() or "no such container" in result.stderr.lower():
            return None
        checked(result)
    container = json.loads(result.stdout)[0]
    mounts = {entry["Destination"]: entry for entry in container["Mounts"]}
    if container["Config"]["Labels"].get(LABEL) != "true" or any(
        destination not in mounts or Path(mounts[destination]["Source"]).resolve() != ENVIRONMENT / folder
        for destination, folder in [("/data", DATA_FOLDER), ("/music", MUSIC_FOLDER)]
    ):
        raise RuntimeError("The container name belongs to another environment")
    bindings = container["HostConfig"]["PortBindings"].get("4533/tcp", [])
    if bindings != [{"HostIp": "127.0.0.1", "HostPort": str(PORT)}] or mounts["/music"]["RW"]:
        raise RuntimeError("The fixture requires a loopback port and a read-only music mount")
    return container


def catalogue():
    salt = "fixture-test-salt"
    parameters = {"u": "fixture", "t": hashlib.md5(("fixture-password" + salt).encode()).hexdigest(),
        "s": salt, "v": "1.16.1", "c": "music-player-test", "f": "json", "query": "", "songCount": "500"}
    with urllib.request.urlopen(ADDRESS + "/rest/search3.view?" + urllib.parse.urlencode(parameters), timeout=2) as response:
        envelope = json.load(response)["subsonic-response"]
    if envelope["status"] != "ok":
        return None
    return {song["title"] for song in envelope.get("searchResult3", {}).get("song", [])}


def start():
    if os.getuid() == 0:
        raise RuntimeError("Run the fixture as your regular user")
    existing = owned_container()
    music = ENVIRONMENT / MUSIC_FOLDER / "Fixture Artist" / "Fixture Album"
    music.mkdir(parents=True, exist_ok=True)
    data = ENVIRONMENT / DATA_FOLDER
    data.mkdir(parents=True, exist_ok=True)
    configuration = data / "navidrome.toml"
    if not configuration.exists():
        # Independent integration tests each authenticate; this loopback-only fixture
        # needs a higher limit than a single running app, which reuses its session.
        configuration.write_text("AuthRequestLimit = 100\n")
    for extension, name in [("flac", "01 Northern Light"), ("mp3", "02 Long Way Home"), ("wav", "03 Night Walk")]:
        source = ROOT / "androidApp/src/androidTest/assets" / ("fixture." + extension)
        destination = music / (name + "." + extension)
        if not destination.exists() or destination.read_bytes() != source.read_bytes():
            shutil.copyfile(source, destination)
    if existing is None:
        checked(docker("run", "--detach", "--name", NAME, "--label", LABEL + "=true",
            "--user", f"{os.getuid()}:{os.getgid()}", "--publish", f"127.0.0.1:{PORT}:4533",
            "--mount", f"type=bind,source={data},target=/data",
            "--mount", f"type=bind,source={ENVIRONMENT / MUSIC_FOLDER},target=/music,readonly",
            "--env", "ND_SCANSCHEDULE=1m", "--env", "ND_LOGLEVEL=warn",
            "--env", "ND_ENABLEEXTERNALSERVICES=false", "--env", "ND_ENABLEINSIGHTS=false", image("navidrome")))
    elif not existing["State"]["Running"]:
        checked(docker("start", NAME))
    for attempt in range(120):
        try:
            songs = catalogue()
            if songs is None:
                request = urllib.request.Request(ADDRESS + "/auth/createAdmin",
                    data=json.dumps({"username": "fixture", "password": "fixture-password"}).encode(),
                    headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=2) as response:
                    response.read()
            elif songs == {"Northern Light", "Long Way Home", "Night Walk"}:
                print("Navidrome fixture ready at " + ADDRESS)
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(.25)
    raise RuntimeError("The fixture did not become ready; inspect docker logs " + NAME)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop"])
    parser.add_argument("--sftp", action="store_true", help="Use the separate fixture for remote file changes")
    arguments = parser.parse_args()
    if arguments.sftp:
        NAME = "nevumi-sftp-navidrome-test"
        PORT = 14536
        ADDRESS = "http://127.0.0.1:14536"
        MUSIC_FOLDER = "sftp-music"
        DATA_FOLDER = "sftp-navidrome-data"
    action = arguments.action
    if action == "start":
        start()
    elif owned_container() is not None:
        checked(docker("stop", NAME))
        print("Navidrome fixture stopped; test data retained")
