"""配置管理模块"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全局配置，支持环境变量覆盖"""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # 服务
    app_name: str = "YOLO 训练与推理服务"
    app_version: str = "1.0.0"
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = False

    # 回调
    callback_token: str = "yolo-callback-token"

    # 存储类型: s3 / local（本地开发免去对象存储依赖）
    storage_type: str = "s3"

    # MySQL（用于回写训练状态与指标）
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = "123456"
    mysql_database: str = "yolo_manager"

    # Redis（Celery broker & 实时状态）
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""
    redis_db_broker: int = 1
    redis_db_result: int = 2
    # 训练指标实时缓存所用库，需与 Spring Boot 后端 spring.data.redis.database 保持一致
    redis_metrics_db: int = 5

    # RustFS / S3 对象存储
    s3_endpoint: str = "http://localhost:9000"
    s3_access_key: str = "yoloadmin"
    s3_secret_key: str = "yolosecret"
    s3_region: str = "us-east-1"
    s3_use_ssl: bool = False
    s3_bucket_datasets: str = "yolo-datasets"
    s3_bucket_models: str = "yolo-models"
    s3_bucket_tests: str = "yolo-tests"

    # 本地工作目录（训练数据集临时下载路径）
    work_dir: str = "./work"

    # 训练前数据准备（从对象存储下载图片/标注）的并发线程数
    dataset_prep_workers: int = 16

    # YOLO 训练输出根目录（Ultralytics runs 落盘位置，建议指向大容量磁盘）
    yolo_project_dir: str = "./yolo_projects"

    # 基础预训练权重目录：训练时优先从此目录按平台模型名（如 yolov8n.pt / yolov11m.pt）加载，
    # 避免运行时联网下载 GitHub 权重；生产环境应在该目录预置全部支持的 .pt 文件。
    base_models_dir: str = "./base_models"

    # 训练默认参数
    default_epochs: int = 100
    default_batch_size: int = 16
    default_imgsz: int = 640
    device: str = ""  # 为空时由 ultralytics 自动选择
    # DataLoader 数据加载子进程数：>0 时多进程并行解码/增强，缓解弱 CPU 串行加载瓶颈；
    # 训练前会临时解除 Celery prefork 子进程的 daemon 限制（见 train_yolo），0 则回退主进程串行加载
    train_workers: int = 8

    def _redis_auth(self) -> str:
        """拼 redis URL 的用户名口令段：口令必须百分号编码。

        部署脚本默认的 Redis 口令带 `#`（如 YoloRedis#2026），不编码时 urlparse 会把 `#` 当作
        fragment 起点，导致 netloc 变成 `:YoloRedis`，Celery 启动即报
        "Port could not be cast to integer value"。kombu 的 url_to_parts 会对口令做 unquote，
        所以编码后取回的仍是原始口令。
        """
        from urllib.parse import quote

        if not self.redis_password:
            return ""
        return f":{quote(self.redis_password, safe='')}@"

    def redis_broker_url(self) -> str:
        return f"redis://{self._redis_auth()}{self.redis_host}:{self.redis_port}/{self.redis_db_broker}"

    def redis_result_url(self) -> str:
        return f"redis://{self._redis_auth()}{self.redis_host}:{self.redis_port}/{self.redis_db_result}"

    def mysql_url(self) -> str:
        from urllib.parse import quote_plus

        user = quote_plus(self.mysql_user)
        password = quote_plus(self.mysql_password)
        return (
            f"mysql+pymysql://{user}:{password}"
            f"@{self.mysql_host}:{self.mysql_port}/{self.mysql_database}?charset=utf8mb4"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()