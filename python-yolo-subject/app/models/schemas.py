"""Pydantic 请求/响应模型"""
from typing import List, Optional

from pydantic import BaseModel, Field


class TrainStartRequest(BaseModel):
    """训练任务启动请求（由 Spring Boot 投递）"""

    taskId: int = Field(..., description="训练任务 ID")
    datasetId: int = Field(..., description="数据集 ID")
    modelBase: str = Field("yolov8n", description="基础模型，如 yolov8n/yolov8s")
    epochs: int = Field(100, ge=1, le=1000)
    batchSize: int = Field(16, ge=1, le=256)
    imgsz: int = Field(640, ge=32, le=4096)
    callbackUrl: Optional[str] = Field(None, description="训练结果回调地址")
    callbackToken: Optional[str] = Field("", description="回调鉴权 token")
    baseModelType: Optional[str] = Field("BASE", description="BASE / TRAINED")
    baseVersionId: Optional[int] = Field(None, description="续训时源 model_version.id")
    expectedVersion: Optional[str] = Field(None, description="预期注册的版本号，后端提前计算")
    storagePrefix: Optional[str] = Field(None, description="存储目录前缀（唯一，如 task-{id}），缺省回退版本号目录")


class TrainStatusResponse(BaseModel):
    """训练状态响应"""

    taskId: int
    status: str
    progress: int = 0
    errorMsg: Optional[str] = None


class TrainMetric(BaseModel):
    """单 epoch 指标"""

    epoch: int
    boxLoss: Optional[float] = None
    clsLoss: Optional[float] = None
    dflLoss: Optional[float] = None
    map50: Optional[float] = None
    map5095: Optional[float] = None
    precision: Optional[float] = None
    recall: Optional[float] = None


class TrainLogLine(BaseModel):
    """训练日志行"""

    timestamp: str
    message: str


class ModelRegisterRequest(BaseModel):
    """模型版本注册请求"""

    taskId: int
    version: str
    bestPtKey: str
    lastPtKey: str
    map50: Optional[float] = None
    map5095: Optional[float] = None


class Detection(BaseModel):
    """单个检测结果"""

    label: str
    labelId: int = 0
    confidence: float
    x1: float
    y1: float
    x2: float
    y2: float


class PredictRequest(BaseModel):
    """推理请求（multipart 表单）"""

    modelVersionId: int
    imageKey: str
    bucket: str = "yolo-tests"


class PredictResponse(BaseModel):
    """推理响应"""

    detections: List[Detection] = []
    inferenceTimeMs: int = 0


class BaseWeightItem(BaseModel):
    """基础权重下载项（Java → Python）。"""

    model_name: str = Field(..., description="平台名 yolov8n / yolov26n …")
    official_file: str = Field(..., description="官方 .pt 文件名 yolov8n.pt / yolo26n.pt …")
    download_url: Optional[str] = Field(None, description="GitHub Release 直链（可选，缺省则按 tag v8.4.0 拼接）")


class BaseWeightDownloadRequest(BaseModel):
    """POST /api/base-models/download 请求体。"""

    items: List[BaseWeightItem] = Field(default_factory=list)
