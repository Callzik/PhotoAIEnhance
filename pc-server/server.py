#!/usr/bin/env python3
"""Photo AI Enhance PC server. Accessible only over your own Tailscale network."""
from __future__ import annotations

import hmac
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from ipaddress import ip_address
from urllib.parse import urlsplit

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parent
CONFIG = ROOT / "server_config.json"
MAX_UPLOAD = 40 * 1024 * 1024
MAX_OUTPUT = 65 * 1024 * 1024
MAX_SIDE = 4096
BUSY = threading.BoundedSemaphore(1)
PORT = 8765
ENGINE_ROOT = ROOT / "engine"


def tailscale_address() -> str:
    """Read own Tailscale address, not an internet-facing LAN address."""
    paths = [
        Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Tailscale" / "tailscale.exe",
        Path("tailscale"),
    ]
    for exe in paths:
        try:
            result = subprocess.run(
                [str(exe), "ip", "-4"], capture_output=True, text=True, timeout=15, check=True
            )
            for line in result.stdout.splitlines():
                item = line.strip()
                ip = ip_address(item)
                if ip.version == 4 and ip in __import__("ipaddress").ip_network("100.64.0.0/10"):
                    return item
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
    raise RuntimeError(
        "Tailscale не подключён. Установите Tailscale на ПК, войдите в аккаунт, "
        "включите соединение и снова запустите сервер."
    )


def get_engine():
    candidates = sorted(ENGINE_ROOT.rglob("realesrgan-ncnn-vulkan.exe"))
    for exe in candidates:
        model_folder = exe.parent / "models"
        if (model_folder / "realesrgan-x4plus.param").is_file():
            return exe, model_folder
    raise RuntimeError(
        "Движок Real-ESRGAN не найден. Запустите start-server.bat: "
        "он скачает официальный Vulkan-движок для Windows."
    )


def config_token() -> str:
    if CONFIG.exists():
        obj = json.loads(CONFIG.read_text(encoding="utf-8"))
        token = str(obj.get("token", ""))
        if len(token) < 24:
            raise RuntimeError("Некорректный токен в server_config.json")
        return token
    token = secrets.token_urlsafe(36)
    # Local only. NEVER commit this config to a public repository.
    CONFIG.write_text(json.dumps({"token": token}, indent=2), encoding="utf-8")
    return token


def enhance(source: bytes, exe: Path, model_dir: Path) -> bytes:
    # Truncation, decompression bombs and unsupported formats are rejected by Pillow.
    Image.MAX_IMAGE_PIXELS = 65_000_000
    with tempfile.TemporaryDirectory(prefix="photoai_") as td:
        tmp = Path(td)
        input_file = tmp / "original.png"
        output_file = tmp / "enhanced.png"

        with Image.open(io.BytesIO(source)) as original:
            original.load()
            image = ImageOps.exif_transpose(original)
            image = image.convert("RGB")
            # A GTX 1650 has limited VRAM. Cap the input while preserving
            # considerably more pixels than the original phone-side pipeline.
            if max(image.size) > MAX_SIDE:
                image.thumbnail((MAX_SIDE, MAX_SIDE), Image.Resampling.LANCZOS)
            image.save(input_file, "PNG")
            image.close()

        command = [
            str(exe), "-i", str(input_file), "-o", str(output_file),
            "-m", str(model_dir), "-n", "realesrgan-x4plus",
            "-s", "2", "-t", "128", "-g", "0", "-j", "1:1:1"
        ]
        result = subprocess.run(
            command,
            cwd=str(exe.parent),
            capture_output=True,
            text=True,
            timeout=470,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if result.returncode != 0 or not output_file.is_file():
            snippet = (result.stderr or result.stdout)[-1100:]
            raise RuntimeError("Ошибка Real-ESRGAN: " + snippet)
        with Image.open(output_file) as done:
            out = io.BytesIO()
            done.convert("RGB").save(out, format="JPEG", quality=94, optimize=True)
            blob = out.getvalue()
            if len(blob) > MAX_OUTPUT:
                raise RuntimeError("Улучшенная фотография слишком большая")
            return blob


class Handler(BaseHTTPRequestHandler):
    server_version = "PhotoAI-PC/1.3"

    def log_message(self, fmt: str, *args):
        # No token or image content is logged.
        print("%s - %s" % (self.client_address[0], fmt % args), flush=True)

    def reply(self, status: int, body: bytes, mime: str = "text/plain; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def check_token(self) -> bool:
        supplied = self.headers.get("X-PhotoAI-Token", "")
        if not hmac.compare_digest(supplied, self.server.api_token):
            self.reply(401, "Неверный ключ подключения".encode("utf-8"))
            return False
        return True

    def do_GET(self):
        if urlsplit(self.path).path != "/health":
            return self.reply(404, b"Not found")
        if not self.check_token():
            return
        return self.reply(200, b"Photo AI PC server v1.3: OK")

    def do_POST(self):
        if urlsplit(self.path).path != "/enhance":
            return self.reply(404, b"Not found")
        if not self.check_token():
            return
        if self.headers.get("Content-Type", "").split(";")[0] != "application/octet-stream":
            return self.reply(415, b"Expected application/octet-stream")
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.reply(400, b"Invalid Content-Length")
        if size <= 0 or size > MAX_UPLOAD:
            return self.reply(413, "Фото слишком большое (до 40 МБ)".encode("utf-8"))
        if not BUSY.acquire(blocking=False):
            return self.reply(503, "Видеокарта занята обработкой другого фото".encode("utf-8"))
        try:
            source = self.rfile.read(size)
            if len(source) != size:
                return self.reply(400, b"Incomplete upload")
            try:
                enhanced = enhance(source, self.server.engine, self.server.models)
            except Exception as e:
                return self.reply(500, ("Ошибка обработки: " + str(e))[:1200].encode("utf-8"))
            self.reply(200, enhanced, "image/jpeg")
            print("Готово: фотография улучшена, ответ отправлен.", flush=True)
        finally:
            BUSY.release()


def main():
    host = tailscale_address()
    exe, models = get_engine()
    token = config_token()
    class Server(ThreadingHTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    server = Server((host, PORT), Handler)
    server.api_token = token
    server.engine = exe
    server.models = models
    print("\n=== PHOTO AI ENHANCE PC v1.3 ===")
    print("Адрес для телефона: http://%s:%d" % (host, PORT))
    print("Ключ подключения: %s" % token)
    print("Не публикуйте ключ. ПК должен оставаться включённым.")
    print("Сервер слушает только Tailscale IP. Остановка: Ctrl+C.\n", flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("\nСервер остановлен.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
