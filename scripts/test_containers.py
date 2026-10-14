"""Read the shared image pins used by integration tests and packaging."""
import json
from pathlib import Path
import subprocess


def image(service):
    configuration = subprocess.run([
        "docker", "compose", "-f", str(Path(__file__).with_name("compose.yaml")),
        "config", "--format", "json",
    ], check=True, capture_output=True, text=True)
    return json.loads(configuration.stdout)["services"][service]["image"]
