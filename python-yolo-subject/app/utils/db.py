"""数据库连接与状态回写工具"""
import json
import uuid
from typing import Any, Dict, List, Optional

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from ..config import Settings

_engine: Optional[Engine] = None


def get_engine(settings: Settings) -> Engine:
    """懒加载数据库引擎"""
    global _engine
    if _engine is None:
        _engine = create_engine(
            settings.mysql_url(),
            pool_pre_ping=True,
            pool_recycle=3600,
            echo=False,
        )
    return _engine


def query_task(settings: Settings, task_id: int) -> Dict[str, Any]:
    """查询训练任务"""
    engine = get_engine(settings)
    with engine.connect() as conn:
        row = conn.execute(
            text("SELECT * FROM train_task WHERE id = :tid"), {"tid": task_id}
        ).mappings().first()
        return dict(row) if row else {}


def update_task_status(settings: Settings, task_id: int, status: str, progress: int = 0,
                       error_msg: Optional[str] = None):
    """更新任务状态"""
    engine = get_engine(settings)
    sql = """
    UPDATE train_task
    SET status = :status, progress = :progress,
        error_msg = COALESCE(:error_msg, error_msg),
        started_at = COALESCE(started_at, NOW()),
        finished_at = CASE WHEN :status IN ('SUCCESS','FAILED','CANCELLED') THEN NOW() ELSE finished_at END
    WHERE id = :tid
    """
    with engine.begin() as conn:
        conn.execute(
            text(sql),
            {
                "status": status,
                "progress": progress,
                "error_msg": error_msg,
                "tid": task_id,
            },
        )


def mark_queued_if_created(settings: Settings, task_id: int) -> bool:
    """原子条件更新：仅 CREATED → QUEUED。

    worker 抢单后会直接写 RUNNING，若无条件回写 QUEUED 会把 RUNNING 覆盖回排队中，
    直到首个 epoch 结束才恢复。返回是否实际更新。
    """
    engine = get_engine(settings)
    sql = """
    UPDATE train_task
    SET status = 'QUEUED', error_msg = NULL
    WHERE id = :tid AND status = 'CREATED'
    """
    with engine.begin() as conn:
        result = conn.execute(text(sql), {"tid": task_id})
        return result.rowcount > 0


def update_task_metrics(settings: Settings, task_id: int, metrics: List[Dict[str, Any]]):
    """持久化训练指标到 Redis（实时读取）"""
    import redis

    r = redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        password=settings.redis_password or None,
        db=settings.redis_metrics_db,
        decode_responses=True,
        protocol=2,
    )
    r.set(f"train:metrics:{task_id}", json.dumps(metrics, ensure_ascii=False))
    r.expire(f"train:metrics:{task_id}", 60 * 60 * 24 * 7)


def register_model_version(settings: Settings, task_id: int, version: str,
                           best_pt_key: str, last_pt_key: str,
                           map50: Optional[float], map5095: Optional[float]) -> str:
    """注册模型版本（HTTP 回调由 Spring Boot 完成，此处直接写库保证一致性）"""
    engine = get_engine(settings)
    sql = """
    INSERT INTO model_version (uuid, version, task_id, best_pt_key, last_pt_key, map50, map50_95,
                               is_active, last_trained_at, created_at)
    VALUES (:uuid, :version, :task_id, :best_pt_key, :last_pt_key, :map50, :map5095,
            0, NOW(), NOW())
    ON DUPLICATE KEY UPDATE
        best_pt_key = VALUES(best_pt_key),
        last_pt_key = VALUES(last_pt_key),
        map50 = VALUES(map50),
        map50_95 = VALUES(map50_95),
        last_trained_at = NOW()
    """
    with engine.begin() as conn:
        conn.execute(
            text(sql),
            {
                "uuid": uuid.uuid4().hex,
                "version": version,
                "task_id": task_id,
                "best_pt_key": best_pt_key,
                "last_pt_key": last_pt_key,
                "map50": map50,
                "map5095": map5095,
            },
        )
    return version