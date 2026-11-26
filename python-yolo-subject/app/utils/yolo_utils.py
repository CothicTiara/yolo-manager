"""YOLO 辅助函数：data.yaml 生成、指标解析、产物收集"""
import csv
import os
import time
from typing import Any, Dict, List, Optional

# ultralytics 为重型依赖，训练时才会真正导入
try:  # pragma: no cover - 训练环境
    from ultralytics import YOLO  # noqa: F401
    _HAS_YOLO = True
except ImportError:
    _HAS_YOLO = False


def has_yolo() -> bool:
    return _HAS_YOLO


def build_data_yaml(dataset_root: str, label_names: List[str]) -> str:
    """生成 YOLO data.yaml 配置"""
    lines = [
        f"path: {os.path.abspath(dataset_root)}",
        "train: images/train",
        "val: images/val",
        "names:",
    ]
    for idx, name in enumerate(label_names):
        lines.append(f"  {idx}: {name}")
    yaml_path = os.path.join(dataset_root, "data.yaml")
    with open(yaml_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return yaml_path


def parse_results_csv(results_csv: str) -> Dict[str, Any]:
    """解析 ultralytics 生成的 results.csv，返回最终指标"""
    if not os.path.exists(results_csv):
        return {}
    finals: Dict[str, Any] = {}
    with open(results_csv, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            finals["epoch"] = int(float(row.get("epoch", 0)))
            finals["map50"] = float(row.get("metrics/mAP50(B)", 0.0))
            finals["map5095"] = float(row.get("metrics/mAP50-95(B)", 0.0))
            finals["precision"] = float(row.get("metrics/precision(B)", 0.0))
            finals["recall"] = float(row.get("metrics/recall(B)", 0.0))
            finals["boxLoss"] = float(row.get("train/box_loss", 0.0))
            finals["clsLoss"] = float(row.get("train/cls_loss", 0.0))
    return finals


def collect_epoch_metrics(results_csv: str) -> List[Dict[str, Any]]:
    """读取全部 epoch 指标，供实时曲线展示"""
    if not os.path.exists(results_csv):
        return []
    metrics: List[Dict[str, Any]] = []
    with open(results_csv, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            metrics.append(
                {
                    "epoch": int(float(row.get("epoch", 0))),
                    "boxLoss": float(row.get("train/box_loss", 0.0) or 0),
                    "clsLoss": float(row.get("train/cls_loss", 0.0) or 0),
                    "map50": float(row.get("metrics/mAP50(B)", 0.0) or 0),
                    "map5095": float(row.get("metrics/mAP50-95(B)", 0.0) or 0),
                    "precision": float(row.get("metrics/precision(B)", 0.0) or 0),
                    "recall": float(row.get("metrics/recall(B)", 0.0) or 0),
                }
            )
    return metrics


def format_log_time() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def safe_nan(value: Optional[float]) -> Optional[float]:
    """NaN 转 None，避免 JSON 序列化失败"""
    if value is None:
        return None
    try:
        if value != value:  # NaN check
            return None
        return float(value)
    except (TypeError, ValueError):
        return None