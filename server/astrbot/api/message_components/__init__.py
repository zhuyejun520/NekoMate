from __future__ import annotations

from typing import Any


class BaseMessageComponent:
    type: str = "base"

    def __init__(self, **kwargs: Any) -> None:
        self.data: dict[str, Any] = dict(kwargs)

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type, **self.data}

    def to_text(self) -> str:
        return ""

    def __repr__(self) -> str:  # pragma: no cover
        return f"<{self.__class__.__name__} {self.data}>"


class Plain(BaseMessageComponent):
    type = "plain"

    def __init__(self, text: str = "") -> None:
        super().__init__()
        self.text = str(text or "")

    def to_dict(self) -> dict[str, Any]:
        return {"type": "plain", "text": self.text}

    def to_text(self) -> str:
        return self.text


class Image(BaseMessageComponent):
    type = "image"

    def __init__(self, file: str | None = None, url: str | None = None, path: str | None = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.file = file or url or path or ""

    def to_dict(self) -> dict[str, Any]:
        return {"type": "image", "file": self.file}

    def to_text(self) -> str:
        return "[图片]"


class At(BaseMessageComponent):
    type = "at"

    def __init__(self, qq: str = "", name: str | None = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.qq = str(qq)
        self.name = name

    def to_dict(self) -> dict[str, Any]:
        return {"type": "at", "qq": self.qq, "name": self.name}

    def to_text(self) -> str:
        return f"@{self.name or self.qq} "


class AtAll(At):
    def __init__(self, **kwargs: Any) -> None:
        super().__init__(qq="all", name="全体成员", **kwargs)


class Reply(BaseMessageComponent):
    type = "reply"

    def __init__(self, id: str | int = "", **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.id = str(id)

    def to_dict(self) -> dict[str, Any]:
        return {"type": "reply", "id": self.id}


class Face(BaseMessageComponent):
    type = "face"

    def __init__(self, id: str | int = "", **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.id = str(id)

    def to_dict(self) -> dict[str, Any]:
        return {"type": "face", "id": self.id}


class Record(BaseMessageComponent):
    type = "record"

    def __init__(self, file: str | None = None, url: str | None = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.file = file or url or ""

    def to_dict(self) -> dict[str, Any]:
        return {"type": "record", "file": self.file}

    def to_text(self) -> str:
        return "[语音]"


class Video(Record):
    type = "video"

    def to_text(self) -> str:
        return "[视频]"


class File(BaseMessageComponent):
    type = "file"

    def __init__(self, name: str = "", file: str = "", url: str | None = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.name = name or "文件"
        self.file = file or url or ""

    def to_dict(self) -> dict[str, Any]:
        return {"type": "file", "name": self.name, "file": self.file}

    def to_text(self) -> str:
        return f"[文件:{self.name}]"


class Json(BaseMessageComponent):
    type = "json"

    def __init__(self, data: Any = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.data = data

    def to_dict(self) -> dict[str, Any]:
        return {"type": "json", "data": self.data}

    def to_text(self) -> str:
        return "[卡片消息]"


class Poke(BaseMessageComponent):
    type = "poke"

    def __init__(self, type: str = "poke", id: str = "", **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.poke_type = type
        self.id = id

    def to_dict(self) -> dict[str, Any]:
        return {"type": "poke", "poke_type": self.poke_type, "id": self.id}

    def to_text(self) -> str:
        return "[戳一戳]"


class Node(BaseMessageComponent):
    type = "node"

    def __init__(self, name: str = "", uin: str = "", content: Any = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.name = name
        self.uin = uin
        self.content = content

    def to_dict(self) -> dict[str, Any]:
        return {"type": "node", "name": self.name, "uin": self.uin, "content": self.content}


class Nodes(BaseMessageComponent):
    type = "nodes"

    def __init__(self, nodes: Any = None, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.nodes = nodes or []

    def to_dict(self) -> dict[str, Any]:
        return {"type": "nodes", "nodes": self.nodes}


__all__ = [
    "BaseMessageComponent",
    "Plain",
    "Image",
    "At",
    "AtAll",
    "Reply",
    "Face",
    "Record",
    "Video",
    "File",
    "Json",
    "Poke",
    "Node",
    "Nodes",
]
