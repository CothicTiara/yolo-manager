"""S3（RustFS / MinIO 兼容）对象存储客户端封装"""
import os
import tempfile
from typing import List, Optional

import boto3
from botocore.client import Config
from botocore.exceptions import ClientError

from ..config import Settings

SAMPLES_KEY = "datasets/__sample__/sample.jpg"


class S3Storage:
    """基于 boto3 的 S3 兼容存储客户端"""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
            region_name=settings.s3_region,
            use_ssl=settings.s3_use_ssl,
            config=Config(signature_version="s3v4", s3={"addressing_style": "path"},
                          max_pool_connections=32),
        )
        self.buckets = {
            "datasets": settings.s3_bucket_datasets,
            "models": settings.s3_bucket_models,
            "tests": settings.s3_bucket_tests,
        }
        self._ensure_buckets()

    def _ensure_buckets(self):
        for name in self.buckets.values():
            try:
                self.client.head_bucket(Bucket=name)
            except ClientError as e:
                code = getattr(e, "response", {}).get("Error", {}).get("Code", "")
                if code in ("404", "NoSuchBucket"):
                    try:
                        self.client.create_bucket(Bucket=name)
                    except ClientError as create_err:
                        print(f"[S3] 创建存储桶 {name} 失败: {create_err}")

    def exists(self, bucket: str, key: str) -> bool:
        try:
            self.client.head_object(Bucket=bucket, Key=key)
            return True
        except ClientError:
            return False

    def upload_bytes(self, bucket: str, key: str, data: bytes, content_type: str = "application/octet-stream"):
        self.client.put_object(Bucket=bucket, Key=key, Body=data, ContentType=content_type)

    def download_bytes(self, bucket: str, key: str) -> bytes:
        resp = self.client.get_object(Bucket=bucket, Key=key)
        return resp["Body"].read()

    def download_to_file(self, bucket: str, key: str, local_path: str) -> str:
        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        with open(local_path, "wb") as f:
            self.client.download_fileobj(bucket, key, f)
        return local_path

    def upload_file(self, bucket: str, key: str, local_path: str, content_type: str = "application/octet-stream"):
        self.client.upload_file(local_path, bucket, key, ExtraArgs={"ContentType": content_type})

    def delete(self, bucket: str, key: str):
        try:
            self.client.delete_object(Bucket=bucket, Key=key)
        except ClientError:
            pass

    def list_keys(self, bucket: str, prefix: str = "") -> List[str]:
        keys: List[str] = []
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
            for content in page.get("Contents", []):
                keys.append(content["Key"])
        return keys

    def public_url(self, bucket: str, key: str) -> str:
        return f"/storage/{bucket}/{key}"

    def download_sample(self) -> Optional[str]:
        """尝试下载示例图片（不存在时返回空）"""
        if self.exists(self.buckets["datasets"], SAMPLES_KEY):
            tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
            self.download_to_file(self.buckets["datasets"], SAMPLES_KEY, tmp.name)
            return tmp.name
        return ""

class LocalFileStorage:
    """本地磁盘存储（开发模式，模拟 S3 布局）"""

    def __init__(self, settings: Settings):
        self.settings = settings
        base = getattr(settings, "work_dir", "./work")
        self.root = os.path.abspath(os.path.join(base, "storage"))

    def _path(self, bucket: str, key: str) -> str:
        safe_key = key.lstrip("/").replace("\\", "/")
        return os.path.join(self.root, bucket, safe_key)

    def exists(self, bucket: str, key: str) -> bool:
        return os.path.exists(self._path(bucket, key))

    def upload_bytes(self, bucket: str, key: str, data: bytes, content_type: str = "application/octet-stream"):
        p = self._path(bucket, key)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)

    def download_bytes(self, bucket: str, key: str) -> bytes:
        with open(self._path(bucket, key), "rb") as f:
            return f.read()

    def upload_file(self, bucket: str, key: str, local_path: str, content_type: str = "image/jpeg"):
        with open(local_path, "rb") as f:
            self.upload_bytes(bucket, key, f.read(), content_type)

    def download_to_file(self, bucket: str, key: str, local_path: str) -> str:
        src = self._path(bucket, key)
        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        with open(src, "rb") as f, open(local_path, "wb") as out:
            out.write(f.read())
        return local_path

    def delete(self, bucket: str, key: str):
        p = self._path(bucket, key)
        if os.path.exists(p):
            try:
                os.remove(p)
            except OSError:
                pass

    def list_keys(self, bucket: str, prefix: str = "") -> List[str]:
        base = os.path.join(self.root, bucket)
        if not os.path.exists(base):
            return []
        result = []
        for dirpath, _, files in os.walk(base):
            for fname in files:
                rel = os.path.relpath(os.path.join(dirpath, fname), base).replace("\\", "/")
                if rel.startswith(prefix):
                    result.append(rel)
        return result

    def public_url(self, bucket: str, key: str) -> str:
        return f"/local/{bucket}/{key}"


def build_storage(settings: Settings):
    """根据配置选择存储实现（S3 或本地开发）"""
    storage_type = getattr(settings, "storage_type", "s3")
    if storage_type == "local":
        return LocalFileStorage(settings)
    return S3Storage(settings)