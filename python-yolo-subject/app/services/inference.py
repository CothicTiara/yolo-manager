"""推理服务：从存储加载测试图片与模型权重，执行 YOLO 推理"""
import base64
import os
import tempfile
import time
from typing import Dict, List

import numpy as np

from ..config import Settings
from ..services.storage import build_storage
from ..utils.yolo_utils import has_yolo


class InferenceService:
    """模型推理测试"""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.storage = build_storage(settings)
        self.models_bucket = settings.s3_bucket_models

    def predict(self, model_version_id: int, bucket: str, image_key: str) -> Dict:
        """执行推理，返回检测结果 + 耗时（毫秒）+ 服务端标注图（base64 与存储 key）+ 原图尺寸"""
        if not has_yolo():
            raise RuntimeError(
                "推理服务未安装 YOLO 依赖，请安装: pip install 'ultralytics>=8.3.0' torch torchvision"
            )

        # 1. 下载测试图片
        test_dir = tempfile.mkdtemp(prefix="yolo_test_")
        try:
            img_path = os.path.join(test_dir, "input.jpg")
            self.storage.download_to_file(bucket, image_key, img_path)

            # 2. 查找模型权重（模型记录中存 best_pt_key，这里按 format: {version}/best.pt）
            #    如果 key 不合法，尝试从 model_version 表查询
            best_key = self._resolve_model_key(model_version_id)

            # 3. 下载模型权重
            model_path = os.path.join(test_dir, "best.pt")
            self.storage.download_to_file(self.models_bucket, best_key, model_path)

            # 4. 执行推理（先注入 YOLOv13 自定义模块兼容层，支持上传的 YOLOv13 自训练权重）
            from ultralytics import YOLO

            from ..utils.yolov13_compat import ensure_yolov13_compat
            ensure_yolov13_compat()

            model = YOLO(model_path)
            start = time.time()
            results = model.predict(source=img_path, conf=0.25, verbose=False)
            cost_ms = int((time.time() - start) * 1000)

            detections: List[Dict] = []
            for result in results:
                boxes = result.boxes
                if boxes is None or len(boxes) == 0:
                    continue
                names = result.names
                xyxy = boxes.xyxy.cpu().numpy()
                confs = boxes.conf.cpu().numpy()
                cls_ids = boxes.cls.cpu().numpy().astype(int)
                for i in range(len(boxes)):
                    x1, y1, x2, y2 = [float(v) for v in xyxy[i]]
                    label_id = int(cls_ids[i])
                    detections.append({
                        "label": names.get(label_id, str(label_id)),
                        "labelId": label_id,
                        "confidence": round(float(confs[i]), 4),
                        "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                    })

            return {
                "detections": detections,
                "inferenceTimeMs": cost_ms,
                **self._build_annotated_image(results, detections, img_path, bucket, image_key),
            }
        finally:
            import shutil
            shutil.rmtree(test_dir, ignore_errors=True)

    def _build_annotated_image(self, results, detections: List[Dict],
                               img_path: str, bucket: str, image_key: str) -> Dict:
        """生成服务端标注图：优先 ultralytics 内置 plot()，失败时 OpenCV 手动画框。
        标注图上传到原图同目录 annotated.jpg，同时返回 base64 与原图尺寸。"""
        import cv2

        img = cv2.imread(img_path)
        image_height, image_width = (int(img.shape[0]), int(img.shape[1])) if img is not None else (0, 0)

        annotated = None
        if results:
            try:
                annotated = results[0].plot()
            except Exception as exc:
                print(f"[InferenceService] result.plot() 失败，回退手动绘制: {exc}")
        if annotated is None and img is not None:
            annotated = img.copy()
            for d in detections:
                p1 = (int(d["x1"]), int(d["y1"]))
                p2 = (int(d["x2"]), int(d["y2"]))
                cv2.rectangle(annotated, p1, p2, (0, 255, 0), 2)
                text = f"{d['label']} {d['confidence']:.2f}"
                cv2.putText(annotated, text, (p1[0], max(p1[1] - 6, 12)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)

        out = {"annotatedImageKey": None, "annotatedImageBase64": None,
               "imageWidth": image_width, "imageHeight": image_height}
        if annotated is None:
            return out
        ok, buf = cv2.imencode(".jpg", annotated)
        if not ok:
            return out
        jpg_bytes = buf.tobytes()
        out["annotatedImageBase64"] = "data:image/jpeg;base64," + base64.b64encode(jpg_bytes).decode("ascii")
        parent = image_key.rsplit("/", 1)[0] if "/" in image_key else ""
        annotated_key = f"{parent}/annotated.jpg" if parent else "annotated.jpg"
        try:
            self.storage.upload_bytes(bucket, annotated_key, jpg_bytes, "image/jpeg")
            out["annotatedImageKey"] = annotated_key
        except Exception as exc:
            print(f"[InferenceService] 标注图上传失败（仅返回 base64）: {exc}")
        return out

    def _resolve_model_key(self, model_version_id: int) -> str:
        """从 MySQL 读取模型 best_pt_key"""
        from ..utils import db

        try:
            engine = db.get_engine(self.settings)
            with engine.connect() as conn:
                row = conn.execute(
                    _model_sql(), {"mid": model_version_id}
                ).mappings().first()
            if row and row.get("best_pt_key"):
                return row["best_pt_key"]
        except Exception as exc:
            print(f"[InferenceService] 查询模型 key 失败: {exc}")
        # 兜底：按规范猜测
        return f"v{model_version_id}/best.pt"

    def health_detection(self) -> Dict:
        """环境自检"""
        return {
            "yolo_installed": has_yolo(),
            "storage": type(self.storage).__name__,
        }


def _model_sql():
    from sqlalchemy import text
    return text("SELECT best_pt_key FROM model_version WHERE id = :mid")