from typing import Dict, Any, Optional

try:
    from . import client
except ImportError:
    import client

def notify(title: str, message: str, progress: Optional[int] = None, tag: str = "agy_status", channel: str = "agy_tasks", ongoing: bool = False) -> Dict[str, Any]:
    payload = {"action": "post", "title": title, "message": message, "tag": tag, "channel": channel}
    if progress is not None:
        payload["progress"] = max(0, min(100, progress))
    if ongoing:
        payload["ongoing"] = True
    return client.send_command("notify", payload)

def cancel_notify(tag: str = "agy_status", notification_id: int = 1001) -> Dict[str, Any]:
    return client.send_command("notify", {"action": "cancel", "tag": tag, "id": notification_id})

def open_url(url: str) -> Dict[str, Any]:
    return client.send_command("open_url", {"url": url})

def wakelock(acquire: bool = True, tag: str = "agy_task") -> Dict[str, Any]:
    action = "acquire" if acquire else "release"
    return client.send_command("wakelock", {"action": action, "tag": tag})

def clipboard_set(text: str) -> Dict[str, Any]:
    return client.send_command("clipboard", {"action": "set", "text": text})

def clipboard_get() -> Dict[str, Any]:
    return client.send_command("clipboard", {"action": "get"})

def system_info() -> Dict[str, Any]:
    return client.send_command("system_info")

def status() -> Dict[str, Any]:
    return client.send_command("status")
