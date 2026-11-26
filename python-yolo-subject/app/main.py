"""FastAPI 应用入口：训练/推理/状态查询接口"""
import logging
import time
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware

from .config import get_settings
from .models.schemas import (
    ModelRegisterRequest,
    PredictRequest,
    TrainLogLine,
    TrainMetric,
    TrainStartRequest,
    TrainStatusResponse,
    BaseWeightDownloadRequest,
)
from .services.inference import InferenceService
from .services.storage import build_storage
from .services import base_models as base_models_svc
from .utils import db
from .utils.logging_utils import setup_logging
from .utils.yolo_utils import has_yolo

settings = get_settings()
setup_logging(settings)

logger = logging.getLogger(__name__)

# 运行时组件（生命周期事件中初始化）
storage = None
inference_service = None
active_tasks: dict = {}  # task_id -> celery AsyncResult


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：初始化存储与推理服务"""
    global storage, inference_service
    logger.info("启动 %s v%s", settings.app_name, settings.app_version)
    storage = build_storage(settings)
    inference_service = InferenceService(settings)
    yield
    logger.info("服务关闭")


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health():
    return {
        "status": "ok",
        "yolo_installed": has_yolo(),
        "time": time.strftime("%Y-%m-%d %H:%M:%S"),
    }


# ============ 训练接口 ============

@app.post("/api/train/start", response_model=TrainStatusResponse)
def train_start(request: TrainStartRequest):
    """接收训练任务：投递到 Celery 队列。

    使用同步 def（而非 async def），FastAPI 会将其放入线程池执行，
    从而避免阻塞式 delay()/broker 调用卡死事件循环导致整个服务（含 /api/health）无响应。
    """
    from .worker import train_task

    async_result = train_task.delay(
        task_id=request.taskId,
        dataset_id=request.datasetId,
        model_base=request.modelBase,
        epochs=request.epochs,
        batch_size=request.batchSize,
        imgsz=request.imgsz,
        callback_url=request.callbackUrl,
        callback_token=request.callbackToken,
        base_model_type=request.baseModelType or "BASE",
        base_version_id=request.baseVersionId,
        expected_version=request.expectedVersion or "",
        storage_prefix=request.storagePrefix,
    )
    active_tasks[request.taskId] = async_result
    logger.info("训练任务 %s 已投递到 Celery，celery_id=%s", request.taskId, async_result.id)

    # 标记为 QUEUED（原子条件更新：仅 CREATED 可置 QUEUED，避免覆盖 worker 已写的 RUNNING）
    try:
        db.mark_queued_if_created(settings, request.taskId)
    except Exception as exc:
        logger.warning("更新任务状态失败: %s", exc)

    return TrainStatusResponse(taskId=request.taskId, status="QUEUED", progress=0)


@app.post("/api/train/{task_id}/cancel")
def train_cancel(task_id: int):
    """取消训练任务：设置 Redis 标志 + revoke Celery 任务 + 更新 DB 状态"""
    import redis as redis_lib

    # 1) 写 Redis 取消标志，worker 会在 epoch 结束时检查
    r = redis_lib.Redis(
        host=settings.redis_host, port=settings.redis_port,
        password=settings.redis_password or None,
        db=settings.redis_db_broker, decode_responses=True, protocol=2,
    )
    r.setex(f"train:cancel:{task_id}", 60 * 60 * 24, "1")

    # 2) 尽力 revoke Celery 任务（Windows 上 terminate 可能不完全生效）
    async_result = active_tasks.get(task_id)
    if async_result is not None:
        try:
            async_result.revoke(terminate=True, signal="SIGTERM")
        except Exception as exc:
            logger.warning("revoke Celery 任务失败: %s", exc)

    # 3) 更新 DB 状态
    try:
        db.update_task_status(settings, task_id, "CANCELLED", 0, "用户取消")
    except Exception as exc:
        logger.warning("更新任务状态为 CANCELLED 失败: %s", exc)

    logger.info("训练任务 %s 已发送取消信号", task_id)
    return {"taskId": task_id, "status": "CANCELLED"}


@app.get("/api/train/{task_id}/status", response_model=TrainStatusResponse)
def train_status(task_id: int):
    """查询训练状态（优先 MySQL，其次 Redis/Celery）"""
    row = db.query_task(settings, task_id)
    if not row:
        # 兜底：从 Celery 结果中取
        async_result = active_tasks.get(task_id)
        if async_result is not None:
            if async_result.ready():
                status = "SUCCESS" if async_result.successful() else "FAILED"
                msg = None if async_result.successful() else str(async_result.result)
                return TrainStatusResponse(taskId=task_id, status=status,
                                           progress=100, errorMsg=msg)
            state = (async_result.state or "PENDING").upper()
            mapped = "QUEUED" if state in ("PENDING", "RECEIVED", "STARTED") else state
            return TrainStatusResponse(taskId=task_id, status=mapped)
        raise HTTPException(status_code=404, detail="训练任务不存在")
    return TrainStatusResponse(
        taskId=task_id,
        status=row.get("status", "UNKNOWN"),
        progress=int(row.get("progress") or 0),
        errorMsg=row.get("error_msg"),
    )


@app.get("/api/train/{task_id}/logs", response_model=List[TrainLogLine])
def train_logs(task_id: int):
    """获取训练日志（当前简化：返回最近状态，完整日志由训练进程落盘）"""
    import datetime

    row = db.query_task(settings, task_id)
    lines = []
    if row:
        lines.append(TrainLogLine(
            timestamp=datetime.datetime.now().isoformat(),
            message=f"任务状态: {row.get('status')}, 进度: {row.get('progress')}",
        ))
        if row.get("error_msg"):
            lines.append(TrainLogLine(
                timestamp=datetime.datetime.now().isoformat(),
                message=f"错误信息: {row.get('error_msg')}",
            ))
    return lines

@app.get("/api/train/{task_id}/metrics", response_model=List[TrainMetric])
def train_metrics(task_id: int):
    """获取 epoch 指标（从 Redis 实时缓存读取）"""
    import json

    import redis as redis_lib

    r = redis_lib.Redis(
        host=settings.redis_host, port=settings.redis_port,
        password=settings.redis_password or None,
        db=settings.redis_metrics_db, decode_responses=True,
        protocol=2,
    )
    cached = r.get(f"train:metrics:{task_id}")
    if not cached:
        return []
    try:
        data = json.loads(cached)
        return [TrainMetric(**item) for item in data]
    except Exception as exc:
        logger.warning("解析训练指标失败: %s", exc)
        return []


@app.post("/api/model/register", response_model=dict)
def model_register(request: ModelRegisterRequest):
    """注册模型版本（与 Spring Boot 回调等价）"""
    db.update_task_status(settings, request.taskId, "SUCCESS", 100)
    db.register_model_version(
        settings, request.taskId, request.version,
        request.bestPtKey, request.lastPtKey,
        request.map50, request.map5095,
    )
    # 查询新版本 id
    from sqlalchemy import text as sql_text

    engine = db.get_engine(settings)
    with engine.connect() as conn:
        row = conn.execute(
            sql_text("SELECT id FROM model_version WHERE version = :v ORDER BY id DESC LIMIT 1"),
            {"v": request.version},
        ).mappings().first()
    model_id = row["id"] if row else None
    return {"modelId": model_id, "version": request.version}


# ============ 推理接口 ============

@app.post("/api/inference/predict", response_model=dict)
async def inference_predict(
    modelVersionId: int = Form(...),
    imageKey: str = Form(...),
    bucket: str = Form("yolo-tests"),
    file: Optional[UploadFile] = File(None),
):
    """执行推理测试（支持两种方式：直接传图片文件，或传 storage 中的 imageKey）"""
    if inference_service is None:
        raise HTTPException(status_code=503, detail="推理服务未初始化")
    try:
        # 若用户直接上传文件，则先存入 storage
        if file is not None:
            img_bytes = await file.read()
            test_key = f"upload_{int(time.time() * 1000)}/input.jpg"
            storage.upload_bytes(bucket, test_key, img_bytes, "image/jpeg")
            image_key = test_key
        else:
            image_key = imageKey
        result = inference_service.predict(modelVersionId, bucket, image_key)
        logger.info("推理完成 modelVersionId=%s detections=%s cost=%sms",
                    modelVersionId, len(result["detections"]), result["inferenceTimeMs"])
        return result
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("推理失败")
        raise HTTPException(status_code=500, detail=f"推理失败: {exc}")


@app.get("/api/inference/health")
def inference_health():
    if inference_service is None:
        return {"status": "initializing"}
    return {"status": "ok", **inference_service.health_detection()}


# ============ 基础模型目录接口 ============

@app.get("/api/base-models/local")
def base_models_local():
    """扫描 base_models_dir，返回已下载/在下载/上次失败文件清单，供 Java 回写 DB 状态。"""
    return base_models_svc.list_local(settings)


@app.get("/api/base-models/official")
def base_models_official():
    """从 ultralytics 内置配置解析 GitHub Release 最新 tag，过滤返回可下载的检测权重目录。"""
    return base_models_svc.list_official(settings)


@app.post("/api/base-models/download")
def base_models_download(req: BaseWeightDownloadRequest):
    """为每个条目起后台下载线程，立即返回 accepted 列表。

    使用同步 def，交给 FastAPI 线程池，避免阻塞事件循环。
    """
    items = [it.model_dump() for it in (req.items or [])]
    return {"accepted": base_models_svc.download_items(settings, items)}
