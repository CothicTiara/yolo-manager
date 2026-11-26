"""基础预训练权重目录服务：
- list_local：扫描 settings.base_models_dir，返回已下载/在下载/失败文件清单，供 Java 回写 DB 状态；
- list_official：通过 ultralytics.utils.downloads 拿 GitHub Release 最新 tag 与 assets 列表，过滤"检测权重"（严格匹配 yolo(v?N)[n/s/m/l/x].pt），
  映射为平台命名（统一带 v，如 yolov11n / yolov26n），返回可选下载项；
- download_items：为每个条目起后台线程，先写 {target}.part 成功后 rename 到 {target}.pt；失败落 {target}.failed 记录原因。
  优先走 gh-proxy.com 镜像（国内直连 GitHub ~29KB/s，镜像 ~700KB/s），镜像失败回退直链。
"""
from __future__ import annotations

import logging
import os
import re
import threading
from typing import Any, Dict, Iterable, List, Optional

import requests

from ..config import Settings

logger = logging.getLogger(__name__)

# 检测权重严格匹配：yolo + 可选 v + 家族号(8/11/26/…) + 尺度(n/s/m/l/x) + .pt；
# 排除 yolo11n-seg.pt / yolo11n-pose.pt / yolov8n-cls.pt 等带后缀的多任务变体。
DETECT_RE = re.compile(r"^yolo(v?)(\d+)([nsmlx])\.pt$")

GITHUB_ASSETS_REPO = "ultralytics/assets"
ASSET_BASE = "https://github.com/{repo}/releases/download/{tag}/{name}"
MIRROR_PREFIX = "https://gh-proxy.com/"

# 避免同一 target 并发重复下载；线程集合自然回收（下载完 discard）
_active_locks = threading.Lock()
_active: set[str] = set()


# ------------------------------ 本地扫描 ------------------------------

def list_local(settings: Settings) -> Dict[str, Any]:
    """扫描 base_models_dir，返回已下载 / 在下载 (.part) / 上次失败 (.failed) 三类文件清单。"""
    d = settings.base_models_dir
    out: Dict[str, Any] = {"dir": d, "exists": os.path.isdir(d),
                           "files": [], "parts": [], "failed": []}
    if not out["exists"]:
        return out
    for name in sorted(os.listdir(d)):
        full = os.path.join(d, name)
        if not os.path.isfile(full):
            continue
        try:
            size = os.path.getsize(full)
            mtime = int(os.path.getmtime(full))
        except OSError:
            size, mtime = 0, 0
        if name.endswith(".pt"):
            out["files"].append({"filename": name, "size_bytes": size, "mtime": mtime})
        elif name.endswith(".pt.part"):
            out["parts"].append({"filename": name, "size_bytes": size, "mtime": mtime})
        elif name.endswith(".pt.failed"):
            err = ""
            try:
                with open(full, "r", encoding="utf-8", errors="replace") as fp:
                    err = fp.read(2048)
            except OSError:
                pass
            out["failed"].append({"filename": name, "error": err.strip(), "mtime": mtime})
    return out


# ------------------------------ 官网目录 ------------------------------

def list_official(settings: Settings) -> Dict[str, Any]:  # noqa: ARG001 (settings 保留参数以便未来按目录过滤)
    """从 ultralytics 已知配置解析 GitHub Release tag，返回可下载的检测权重目录。

    注意：get_github_assets 内部会请求 GitHub API；若离线则退化到内置 GITHUB_ASSETS_NAMES 与 tag 'v8.4.0'。
    """
    tag = None
    names: Iterable[str] = []
    try:
        from ultralytics.utils.downloads import get_github_assets, GITHUB_ASSETS_NAMES  # type: ignore
        try:
            tag, assets = get_github_assets(GITHUB_ASSETS_REPO)
            names = [a for a in (assets or []) if DETECT_RE.match(a)]
        except Exception as exc:  # 网络问题等
            logger.warning("拉取 GitHub Release 资产列表失败，回退到内置清单: %s", exc)
            tag = "v8.4.0"
            names = [n for n in GITHUB_ASSETS_NAMES if DETECT_RE.match(n)]
    except Exception as exc:
        logger.error("ultralytics 不可用: %s", exc)
        return {"tag": None, "items": [], "error": f"ultralytics 不可用: {exc}"}

    # 去重并按 family/scale 排序
    seen = set()
    items: List[Dict[str, Any]] = []
    for fn in sorted(names):
        m = DETECT_RE.match(fn)
        if not m:
            continue
        has_v, family_num, scale = m.group(1), m.group(2), m.group(3)
        family = f"v{family_num}"
        # 平台命名统一带 v：yolov8n / yolov11n / yolov26n（与 base_models_dir 已有文件命名一致）
        platform = f"yolov{family_num}{scale}"
        if platform in seen:
            continue
        seen.add(platform)
        url = ASSET_BASE.format(repo=GITHUB_ASSETS_REPO, tag=tag or "v8.4.0", name=fn)
        items.append({
            "model_name": platform,
            "family": family,
            "scale": scale,
            "official_file": fn,
            "official_has_v": bool(has_v),
            "download_url": url,
        })
    return {"tag": tag, "items": items}


# ------------------------------ 下载（异步线程） ------------------------------

def download_items(settings: Settings, items: List[Dict[str, str]]) -> List[Dict[str, Any]]:
    """为每个 item 起一个后台下载线程；立即返回 accepted 列表。

    item 结构：{model_name, official_file, download_url?}。
    - 目标文件名统一为 base_models_dir/{model_name}.pt（带 v 的平台名），
      这与 training.py 的 _resolve_base_model 首选命中项一致，避免二次映射。
    - 已存在且 >1MB 直接跳过（幂等）。
    """
    os.makedirs(settings.base_models_dir, exist_ok=True)
    accepted: List[Dict[str, Any]] = []
    for it in items:
        model_name = (it.get("model_name") or "").strip()
        official_file = (it.get("official_file") or "").strip()
        url = (it.get("download_url") or "").strip()
        if not model_name or not official_file:
            accepted.append({"model_name": model_name, "status": "INVALID",
                             "message": "缺少 model_name 或 official_file"})
            continue
        target = os.path.join(settings.base_models_dir, f"{model_name}.pt")
        if os.path.isfile(target) and os.path.getsize(target) > 1_000_000:
            accepted.append({"model_name": model_name, "status": "EXISTS",
                             "message": target})
            continue
        if not url:
            url = ASSET_BASE.format(repo=GITHUB_ASSETS_REPO, tag="v8.4.0", name=official_file)
        with _active_locks:
            if target in _active:
                accepted.append({"model_name": model_name, "status": "ALREADY_RUNNING",
                                 "message": "已有同权重下载在进行"})
                continue
            _active.add(target)
        t = threading.Thread(
            target=_do_download, args=(url, target, model_name),
            name=f"base-model-dl-{model_name}", daemon=True,
        )
        t.start()
        accepted.append({"model_name": model_name, "status": "STARTED", "message": target})
    return accepted


def _candidate_urls(url: str) -> List[str]:
    """镜像优先，其次原始 GitHub 直链。"""
    cands: List[str] = []
    if url.startswith("https://github.com/"):
        cands.append(MIRROR_PREFIX + url)
    cands.append(url)
    return cands


def _do_download(url: str, target: str, model_name: str) -> None:
    part = target + ".part"
    fail = target + ".failed"
    # 清掉旧的失败标记（若有）
    try:
        if os.path.isfile(fail):
            os.remove(fail)
    except OSError:
        pass

    last_err: Optional[str] = None
    try:
        for cand in _candidate_urls(url):
            try:
                logger.info("开始下载基础模型 %s <- %s", model_name, cand)
                if _fetch(cand, part):
                    os.replace(part, target)
                    logger.info("基础模型下载完成 %s size=%d", model_name,
                                os.path.getsize(target))
                    last_err = None
                    break
            except Exception as exc:
                last_err = f"{cand}: {exc}"
                logger.warning("基础模型下载尝试失败 %s: %s", cand, exc)
                try:
                    if os.path.isfile(part):
                        os.remove(part)
                except OSError:
                    pass
        if last_err is not None:
            with open(fail, "w", encoding="utf-8") as fp:
                fp.write(str(last_err))
            logger.error("基础模型最终下载失败 %s: %s", model_name, last_err)
    finally:
        with _active_locks:
            _active.discard(target)


def _fetch(url: str, target: str) -> bool:
    """流式下载到 target；成功返回 True。使用较短的 read timeout 避免长时间僵死。"""
    with requests.get(url, stream=True, timeout=(15, 120)) as r:
        if r.status_code != 200:
            raise RuntimeError(f"HTTP {r.status_code}")
        tmp = target + ".tmp"
        with open(tmp, "wb") as fp:
            for chunk in r.iter_content(chunk_size=1024 * 256):
                if chunk:
                    fp.write(chunk)
        # 简单校验：至少 1MB
        size = os.path.getsize(tmp)
        if size < 1_000_000:
            os.remove(tmp)
            raise RuntimeError(f"下载内容过小 size={size}")
        os.replace(tmp, target)
        return True
