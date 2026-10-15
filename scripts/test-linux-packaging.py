#!/usr/bin/env python3
"""Verify the portable archive and real pacman installation without host changes."""
import os
import contextlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
IMAGE = ROOT / "desktopApp/build/compose/binaries/main/app/manida"
spec = importlib.util.spec_from_file_location("website", ROOT / "scripts/package-website.py")
website_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(website_module)


class LinuxPackagingTest(unittest.TestCase):
    def test_website_packaging_rejects_a_missing_arch_fingerprint(self):
        with tempfile.TemporaryDirectory(prefix="manida-website-") as temporary:
            directory = Path(temporary)
            source = directory / "source"
            shutil.copytree(ROOT / "website", source / "website")
            shutil.copytree(ROOT / "packaging", source / "packaging")
            index = source / "website/index.html"
            index.write_text(index.read_text().replace("5948F17F1611CC2B995BC6121F0D542126D514AB", "stale-key"))
            with patch.object(website_module, "__file__", str(source / "scripts/package-website.py")):
                with self.assertRaisesRegex(ValueError, "Arch signing-key fingerprint"):
                    website_module.copy_website(directory / "site", "A" * 40)

    def test_arch_repository_signatures_are_verified_when_installing_from_the_repository(self):
        with tempfile.TemporaryDirectory(prefix="manida-signed-repository-") as temporary, contextlib.ExitStack() as cleanup:
            directory = Path(temporary)
            release = directory / "release"
            result = subprocess.run(
                ["python", str(ROOT / "scripts/package-linux.py"), "--image", str(IMAGE),
                 "--output", str(release), "--version", "0.1.0"], capture_output=True, text=True, timeout=180,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            keys = directory / "keys"
            keys.mkdir(mode=0o700)
            environment = dict(os.environ, GNUPGHOME=str(keys), MANIDA_ARCH_SIGNING_PASSPHRASE="test-repository-passphrase")
            cleanup.callback(subprocess.run, ["gpgconf", "--kill", "gpg-agent"], env=environment, capture_output=True)
            subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "test-repository-passphrase",
                            "--quick-generate-key", "Manida package test <packages@example.test>", "ed25519", "sign", "1d"],
                           env=environment, check=True, capture_output=True, timeout=30)
            subprocess.run(["gpgconf", "--kill", "gpg-agent"], env=environment, check=True, capture_output=True)
            listing = subprocess.run(["gpg", "--batch", "--with-colons", "--list-secret-keys"],
                                     env=environment, check=True, capture_output=True, text=True)
            fingerprint = next(line.split(":")[9] for line in listing.stdout.splitlines() if line.startswith("fpr:"))
            site = directory / "site"
            result = subprocess.run(
                ["python", str(ROOT / "scripts/package-repository.py"), "--release", str(release),
                 "--output", str(site), "--signing-key", fingerprint],
                env=environment, capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse((site / "index.html").exists(), "The website is deployed separately from the repositories")
            result = subprocess.run(["python", str(ROOT / "scripts/package-website.py"), "--output", str(site),
                                     "--signing-key", fingerprint], capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            repository = site / "arch/x86_64"
            self.assertIn("Your music.", (site / "index.html").read_text())
            self.assertIn(fingerprint, (site / "index.html").read_text())
            self.assertNotIn("5948F17F1611CC2B995BC6121F0D542126D514AB", (site / "index.html").read_text())
            self.assertIn("FDEBFCB9DD790730FE607ACB8340588A839C44D9EA5BA6671AB3FAB83952F94B",
                          (site / "index.html").read_text())
            for screenshot in ["home", "now-playing", "queue", "audio-effects"]:
                self.assertTrue((site / f"assets/screenshots/{screenshot}.png").is_file())
            self.assertTrue((site / "theme.js").is_file())
            self.assertTrue((site / "assets/fdroid-qr-dark.png").is_file())
            self.assertFalse(any(path.is_symlink() for path in site.rglob("*")), "Pages artifacts must contain regular files")
            package = next(repository.glob("*.pkg.tar.zst"))
            for path in [package, repository / "manida.db"]:
                verified = subprocess.run(["gpg", "--batch", "--verify", str(path) + ".sig", str(path)],
                                          env=environment, capture_output=True, text=True)
                self.assertEqual(0, verified.returncode, verified.stderr)
            installed = directory / "installed"
            database = installed / "var/lib/pacman"
            database.mkdir(parents=True)
            (directory / "cache").mkdir()
            configuration = directory / "pacman.conf"
            configuration.write_text("[options]\nArchitecture = x86_64\nSigLevel = Required DatabaseRequired\n"
                                     "[manida]\nServer = file://" + str(repository) + "\n")
            result = subprocess.run(
                ["fakeroot", "pacman", "--root", str(installed), "--dbpath", str(database),
                 "--config", str(configuration), "--logfile", str(directory / "pacman.log"),
                 "--cachedir", str(directory / "cache"), "--gpgdir", str(keys),
                 "-Sy", "--nodeps", "--nodeps", "--noscriptlet", "--noconfirm", "manida"],
                env=environment, capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue((installed / "opt/manida/bin/manida").is_file())

    def test_release_archive_and_arch_package_install_and_launch(self):
        with tempfile.TemporaryDirectory(prefix="manida-package-") as temporary:
            directory = Path(temporary)
            output = directory / "release"
            result = subprocess.run(
                ["python", str(ROOT / "scripts/package-linux.py"), "--image", str(IMAGE),
                 "--output", str(output), "--version", "0.1.0"], capture_output=True, text=True, timeout=180,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            archive = output / "manida-0.1.0-linux-x86_64.tar.gz"
            portable = directory / "portable"
            portable.mkdir()
            subprocess.run(["bsdtar", "-xf", str(archive), "-C", str(portable)], check=True, capture_output=True)
            self.assertTrue((portable / "manida/bin/manida").is_file())
            self.assertEqual((ROOT / "LICENSE").read_text(), (portable / "manida/LICENSE").read_text())
            package = next(output.glob("manida-0.1.0-1-x86_64.pkg.tar.zst"))
            installed = directory / "installed"
            database = installed / "var/lib/pacman"
            database.mkdir(parents=True)
            (directory / "cache").mkdir()
            (directory / "keyring").mkdir(mode=0o700)
            configuration = directory / "pacman.conf"
            configuration.write_text("[options]\nArchitecture = x86_64\nSigLevel = Never\n")
            result = subprocess.run(
                ["fakeroot", "pacman", "--root", str(installed), "--dbpath", str(database),
                 "--config", str(configuration), "--logfile", str(directory / "pacman.log"),
                 "--cachedir", str(directory / "cache"), "--gpgdir", str(directory / "keyring"),
                 "-U", "--nodeps", "--nodeps", "--noscriptlet", "--noconfirm", str(package)],
                capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue((installed / "usr/share/applications/io.github.d3sox.musicplayer.desktop").is_file())
            self.assertTrue((installed / "usr/share/icons/hicolor/scalable/apps/io.github.d3sox.musicplayer.svg").is_file())
            self.assertEqual((ROOT / "LICENSE").read_text(),
                             (installed / "usr/share/licenses/manida/LICENSE").read_text())
            result = subprocess.run(
                ["python", str(ROOT / "scripts/test-linux-player.py"),
                 "LinuxPlayerTest.test_packaged_player_registers_desktop_media_controls"],
                env=dict(os.environ, MANIDA_TEST_APP=str(installed / "opt/manida/bin/manida")),
                capture_output=True, text=True, timeout=45,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
