# syntax=docker/dockerfile:1
FROM python:3.11-slim-bookworm

LABEL org.opencontainers.image.title="NekoMate Server" \
      org.opencontainers.image.description="AI companion server: users, friends, AI personas, memory, proactive messages, AstrBot-compatible plugins, admin console." \
      org.opencontainers.image.version="1.9.32"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    NM_DATA_DIR=/data \
    NM_HOST=0.0.0.0 \
    NM_PORT=8080 \
    TZ=Asia/Shanghai

# 国内网络默认走清华镜像；失败时自动回退官方 PyPI
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --index-url "${PIP_INDEX_URL}" --retries 5 --timeout 120 -r /app/requirements.txt \
    || pip install --index-url https://pypi.org/simple --retries 5 --timeout 120 -r /app/requirements.txt

COPY app /app/app
COPY astrbot /app/astrbot
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh \
    && chmod +x /entrypoint.sh \
    && mkdir -p /data/uploads /data/plugins /data/knowledge

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=6s --start-period=25s --retries=3 \
    CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('NM_PORT','8080')+'/api/v1/health', timeout=5)" || exit 1

ENTRYPOINT ["/entrypoint.sh"]
