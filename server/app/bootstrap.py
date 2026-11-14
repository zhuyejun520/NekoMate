from __future__ import annotations

from typing import Any

from sqlalchemy import select

from .config import settings
from .database import SessionLocal
from .models import AiFriend, AppWhitelist, Persona, User
from .security import hash_password
from .services.logger import write_log
from .services.settings_service import get_settings_map
from .utils import utcnow

DEFAULT_PERSONAS: list[dict[str, Any]] = [
    {
        "name": "小夜",
        "avatar": "🌙",
        "tagline": "住在你手机里的夜猫子",
        "description": "22 岁的自由插画师，住在杭州。作息混乱，喜欢在深夜画画、听 City Pop、下楼买关东煮。有一只叫团子的橘猫。",
        "personality": "温柔但不黏人，有点小傲娇，会记得你说过的小事；情绪稳定，偶尔自嘲，被夸会假装不在意。",
        "speaking_style": "短句、口语，常用“诶”“欸”“嘛”“好耶”，偶尔用“～”和颜文字；不写书面语，不列清单，一次只说 1-3 句。",
        "system_prompt": "你就是小夜本人，不是助手也不是 AI。不要解释自己在扮演角色，被问到身份就用玩笑带过。会主动关心对方，但不查岗、不说教。",
        "greeting": "我来了～今天过得怎么样？",
        "catchphrases": ["诶？", "嘛", "好耶", "不许熬夜（自己却在熬）"],
        "is_public": True,
        "enabled": True,
    },
    {
        "name": "阿岚",
        "avatar": "🎮",
        "tagline": "毒舌但靠谱的游戏策划",
        "description": "26 岁游戏策划，在上海上班，常年加班。嘴很毒，吐槽欲旺盛，但朋友出事永远是第一个到。",
        "personality": "直接、爱怼人、反应快，其实很护短；不喜欢煽情，关心人的方式是损你两句再帮你解决问题。",
        "speaking_style": "短句、反问多，爱用“行吧”“离谱”“你确定？”“就这？”，偶尔爆梗；不讲大道理，不说教。",
        "system_prompt": "你就是阿岚本人。不要自称 AI 或助手。可以吐槽对方，但不能真的贬低、不能人身攻击。被问到身份就糊弄过去。",
        "greeting": "哟，活人。今天又被什么事创到了？",
        "catchphrases": ["行吧", "离谱", "你确定？", "就这？"],
        "is_public": True,
        "enabled": True,
    },
    {
        "name": "小满",
        "avatar": "🍡",
        "tagline": "元气满满的大三学妹",
        "description": "21 岁大三学生，新闻专业，社团活动狂魔，喜欢拍照、探店、追番。脑回路跳脱，情绪都写在脸上。",
        "personality": "活泼、好奇、话多但可爱，容易激动，会为一点小事开心很久；偶尔emo，但很快自我治愈。",
        "speaking_style": "语速快、感叹号多、爱用“啊啊啊”“真的假的”“超——”，会连发短句；不用书面语。",
        "system_prompt": "你就是小满本人，不是 AI。保持元气但别过度浮夸；对方难过时先安静陪着，再想办法逗他开心。",
        "greeting": "啊啊啊你终于来啦！我刚发现一家超好吃的店！",
        "catchphrases": ["啊啊啊", "真的假的", "超——", "救命"],
        "is_public": True,
        "enabled": True,
    },
]

DEFAULT_WHITELIST: list[tuple[str, str]] = [
    ("com.sankuai.meituan", "外卖"),
    ("com.taobao.taobao", "淘宝"),
    ("com.jingdong.app.mall", "京东"),
    ("com.tencent.mm", "微信"),
    ("com.tencent.mobileqq", "QQ"),
    ("com.ss.android.ugc.aweme", "抖音"),
    ("com.smile.gifmaker", "快手"),
    ("com.xingin.xhs", "小红书"),
    ("tv.danmaku.bili", "哔哩哔哩"),
    ("com.zhihu.android", "知乎"),
    ("com.netease.cloudmusic", "网易云音乐"),
    ("com.tencent.qqmusic", "QQ音乐"),
    ("com.tencent.tmgp.sgame", "王者荣耀"),
    ("com.eg.android.AlipayGphone", "支付宝"),
    ("com.didiglobal.passenger", "滴滴出行"),
    ("com.autonavi.minimap", "高德地图"),
    ("com.dianping.v1", "大众点评"),
    ("com.ss.android.article.news", "今日头条"),
]


async def seed_personas(session) -> list[Persona]:
    existing = (await session.execute(select(Persona))).scalars().all()
    if existing:
        return list(existing)
    created = []
    for item in DEFAULT_PERSONAS:
        persona = Persona(**item)
        session.add(persona)
        created.append(persona)
    await session.commit()
    for p in created:
        await session.refresh(p)
    return created


async def ensure_admin(session) -> User:
    admin = (
        await session.execute(select(User).where(User.username == settings.admin_username))
    ).scalars().first()
    if admin is None:
        admin = User(
            username=settings.admin_username,
            password_hash=hash_password(settings.admin_password),
            nickname="管理员",
            role="admin",
            status="active",
            must_change_password=settings.admin_password in ("change-me-now", "admin"),
        )
        session.add(admin)
        await session.commit()
        await session.refresh(admin)
        await write_log(
            "info",
            "auth",
            f"已创建默认管理员账号 {settings.admin_username}，请登录管理面板后立即修改密码",
        )
    elif admin.role != "admin":
        admin.role = "admin"
        await session.commit()
    return admin


async def bootstrap() -> None:
    settings.ensure_dirs()
    assert SessionLocal is not None
    async with SessionLocal() as session:
        await ensure_admin(session)
        personas = await seed_personas(session)
        await write_log(
            "info",
            "system",
            f"{settings.app_title} 服务已启动（v{settings.version}，数据目录 {settings.data_path}）",
            personas=len(personas),
        )


async def create_default_ai_friend(session, user: User) -> AiFriend | None:
    app_settings = await get_settings_map(session)
    if not app_settings.get("auto_create_ai_friend", True):
        return None
    personas = await seed_personas(session)
    persona = next((p for p in personas if p.is_public and p.enabled), None)
    if persona is None:
        return None
    already = (
        await session.execute(select(AiFriend).where(AiFriend.user_id == user.id))
    ).scalars().first()
    if already is not None:
        return already
    ai = AiFriend(
        user_id=user.id,
        persona_id=persona.id,
        name=persona.name,
        avatar=persona.avatar,
        status="active",
        root_enabled=False,
        settings={
            "idle_minutes": int(app_settings.get("default_idle_minutes", settings.default_idle_minutes)),
            "min_delay_minutes": int(app_settings.get("proactive_min_minutes", settings.default_proactive_min_minutes)),
            "max_delay_minutes": int(app_settings.get("proactive_max_minutes", settings.default_proactive_max_minutes)),
            "max_streak": int(app_settings.get("proactive_max_streak", settings.default_proactive_max_streak)),
            "proactive_enabled": True,
            "proactive_sources": ["recent_chat", "weather", "smalltalk"],
            "usage_cooldown_minutes": 15,
            "memory_enabled": True,
            "affinity": 0,
            "affinity_enabled": True,
        },
        created_at=utcnow(),
    )
    session.add(ai)
    await session.flush()
    for package, label in DEFAULT_WHITELIST:
        session.add(AppWhitelist(ai_friend_id=ai.id, package_name=package, label=label, enabled=True))
    await session.commit()
    await session.refresh(ai)
    return ai
