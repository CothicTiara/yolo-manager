# 训练与推理服务镜像
# 构建参数：
#   INSTALL_YOLO=false   仅跑管理端联调（不装 torch/ultralytics，健康检查会显示 yolo_installed: false）
#   TORCH_INDEX_URL      只影响 torch/torchvision 轮子来源：
#                        CPU -> https://download.pytorch.org/whl/cpu
#                        GPU -> https://download.pytorch.org/whl/cu124（需宿主机有 nvidia-container-toolkit）
#   PIP_INDEX_URL        其余依赖的 PyPI 源；国内网络可换成镜像站（由 compose / 部署脚本传入）
FROM python:3.11-slim

ARG INSTALL_YOLO=true
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_INDEX_URL=${PIP_INDEX_URL} \
    TZ=Asia/Shanghai \
    OPENCV_LOG_LEVEL=ERROR

WORKDIR /app

# libglib2.0-0 / libgl1：opencv 运行库；libgomp1：torch CPU 推理需要
# 重试：deb.debian.org 的 CDN 偶发 502 / 连接中断，单次 apt 失败不应该让整个镜像重建
RUN set -eux; \
    apt-get update; \
    i=0; \
    until apt-get install -y --no-install-recommends \
            -o Acquire::Retries=5 -o Acquire::http::Timeout=30 \
            libglib2.0-0 libgl1 libgomp1; do \
        i=$((i+1)); \
        if [ "$i" -ge 3 ]; then exit 1; fi; \
        echo "apt install 失败，重试 $i/3"; sleep 10; \
        apt-get update; \
    done; \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install -r requirements.txt

# 注意：download.pytorch.org 的索引只提供 torch/torchvision，ultralytics
# 及其依赖必须仍从 PyPI 安装，所以分两步；先装好的 torch 版本号（如 2.5.1+cpu）
# 已满足 ultralytics 的 torch>=1.8.0 要求，不会被替换成 PyPI 的 CUDA 版。
RUN if [ "$INSTALL_YOLO" = "true" ]; then \
        echo "使用 torch 源：$TORCH_INDEX_URL"; \
        pip install --index-url "$TORCH_INDEX_URL" torch torchvision; \
        pip install "ultralytics>=8.3.0"; \
    else \
        echo "跳过 ultralytics/torch 安装（CPU/GPU 训练不可用）"; \
    fi

COPY app ./app

EXPOSE 8000

# 默认启动 FastAPI；Celery worker 由 compose 覆盖 command
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
