"""Celery 异步任务：YOLO 训练任务调度与执行"""
import logging
import threading
from typing import Optional

import redis as redis_lib
from celery import Celery

from .config import get_settings
from .services.storage import build_storage
from .services.training import TrainingService
from .utils import db

logger = logging.getLogger(__name__)

settings = get_settings()

celery_app = Celery(
    "yolo_training",
    broker=settings.redis_broker_url(),
    backend=settings.redis_result_url(),
)

celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    timezone="Asia/Shanghai",
    enable_utc=True,
    task_track_started=True,
    worker_prefetch_multiplier=1,
    task_acks_late=True,
    # Redis broker 默认 visibility_timeout=1h：acks_late 下超过 1h 未 ack 的长训练会被
    # 重新投递给另一个 worker 重复执行（第二份 run_training 会 rmtree 掉第一份的数据目录，
    # 导致 FileNotFoundError）。拉长到 24h，并配合下方任务级去重双保险。
    broker_transport_options={"visibility_timeout": 86400},
)

# 训练执行锁：防止同一 task_id 被重复投递时并发执行
_LOCK_TTL = 600
_LOCK_RENEW_INTERVAL = 180


def _redis_client() -> redis_lib.Redis:
    return redis_lib.Redis(
        host=settings.redis_host, port=settings.redis_port,
        password=settings.redis_password or None,
        db=settings.redis_db_broker, protocol=2,
    )


@celery_app.task(name="yolo.train_task", bind=True, max_retries=0)
def train_task(self, task_id: int, dataset_id: int, model_base: str,
               epochs: int, batch_size: int, imgsz: int,
               callback_url: Optional[str] = None,
               callback_token: Optional[str] = "",
               base_model_type: Optional[str] = "BASE",
               base_version_id: Optional[int] = None,
               expected_version: Optional[str] = "",
               storage_prefix: Optional[str] = None) -> dict:
    """
    Celery 训练任务：
    - 支持通过 Redis 队列由 Spring Boot 投递
    - 也可以由 FastAPI 接口直接 .delay() 触发
    - 任务级去重：DB 状态 + Redis 执行锁，重复投递直接跳过，避免并发互删数据目录
    """
    logger.info("Celery 开始执行训练任务 task_id=%s base_type=%s base_version=%s expected=%s",
                task_id, base_model_type, base_version_id, expected_version)

    # 去重第一层：DB 状态。SUCCESS/CANCELLED 直接跳过；RUNNING 且执行锁仍被持有
    # 说明另一 worker 正在训练，跳过；RUNNING 但锁已丢失（进程崩溃残留）则允许重新执行。
    r = _redis_client()
    lock_key = f"train:running:{task_id}"
    try:
        status = (db.query_task(settings, task_id) or {}).get("status")
    except Exception as exc:
        logger.warning("task_id=%s 查询任务状态失败，继续执行: %s", task_id, exc)
        status = None
    if status in ("SUCCESS", "CANCELLED"):
        logger.info("task_id=%s 已是 %s，跳过重复投递", task_id, status)
        return {"status": "SKIPPED", "task_id": task_id, "reason": f"already {status}"}
    if status == "RUNNING" and r.exists(lock_key):
        logger.warning("task_id=%s 已有 worker 在训练（执行锁存在），跳过重复投递", task_id)
        return {"status": "SKIPPED", "task_id": task_id, "reason": "already running"}

    # 去重第二层：Redis 执行锁（SET NX），训练期间后台线程定期续租
    if not r.set(lock_key, "1", nx=True, ex=_LOCK_TTL):
        logger.warning("task_id=%s 抢锁失败，另一 worker 正在执行，跳过", task_id)
        return {"status": "SKIPPED", "task_id": task_id, "reason": "lock held"}
    stop_heartbeat = threading.Event()

    def _heartbeat():
        while not stop_heartbeat.wait(_LOCK_RENEW_INTERVAL):
            try:
                r.expire(lock_key, _LOCK_TTL)
            except Exception:
                pass

    hb = threading.Thread(target=_heartbeat, daemon=True)
    hb.start()
    try:
        service = TrainingService(settings)
        result = service.run_training(
            task_id=task_id,
            dataset_id=dataset_id,
            model_base=model_base,
            epochs=epochs,
            batch_size=batch_size,
            imgsz=imgsz,
            callback_url=callback_url,
            callback_token=callback_token,
            base_model_type=base_model_type,
            base_version_id=base_version_id,
            expected_version=expected_version or None,
            storage_prefix=storage_prefix,
        )
        logger.info("训练任务完成 task_id=%s result=%s", task_id, result)
        return result
    finally:
        stop_heartbeat.set()
        try:
            r.delete(lock_key)
        except Exception:
            pass