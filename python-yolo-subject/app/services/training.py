"""训练执行服务：数据集下载、YOLO 训练、产物归档、指标回传"""
import os
import random
import shutil
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict, List, Optional
from sqlalchemy import text

from ..config import Settings
from ..services.storage import build_storage
from ..utils import db, yolo_utils

TASK_STATUS_QUEUED = "QUEUED"
TASK_STATUS_RUNNING = "RUNNING"
TASK_STATUS_SUCCESS = "SUCCESS"
TASK_STATUS_FAILED = "FAILED"
TASK_STATUS_CANCELLED = "CANCELLED"


class TrainingService:
    """YOLO 训练编排核心"""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.storage = build_storage(settings)
        self.datasets_bucket = settings.s3_bucket_datasets
        self.models_bucket = settings.s3_bucket_models

    def run_training(self, task_id: int, dataset_id: int, model_base: str,
                     epochs: int, batch_size: int, imgsz: int,
                     callback_url: Optional[str] = None,
                     callback_token: Optional[str] = None,
                     base_model_type: Optional[str] = "BASE",
                     base_version_id: Optional[int] = None,
                     expected_version: Optional[str] = None,
                     storage_prefix: Optional[str] = None) -> Dict[str, Any]:
        """执行完整训练流程"""
        results: Dict[str, Any] = {}
        self.log(task_id, f"开始处理训练任务 task_id={task_id}, dataset_id={dataset_id}, "
                          f"base_type={base_model_type}, base_version={base_version_id}")

        # 临时下载目录（提前定义，确保 finally 始终可清理）
        task_dir = os.path.join(self.settings.work_dir, f"task_{task_id}")

        try:
            # 1. 检查 YOLO 环境（置于 try 内：前置失败也能回写 FAILED，避免任务永久停在 QUEUED）
            if not yolo_utils.has_yolo():
                raise RuntimeError(
                    "训练服务未安装 YOLO 依赖，请安装: pip install 'ultralytics>=8.3.0' torch torchvision "
                    "(建议在 GPU 服务器上运行)"
                )

            # 2. 标记运行中
            db.update_task_status(self.settings, task_id, TASK_STATUS_RUNNING, 0)
            self.log(task_id, f"任务进入 RUNNING 状态，模型: {model_base}, epochs={epochs}, batch={batch_size}, imgsz={imgsz}")

            # 3. 准备临时下载目录 + YOLO 训练输出目录（对应 YOLO_PROJECT_DIR）
            shutil.rmtree(task_dir, ignore_errors=True)
            os.makedirs(task_dir, exist_ok=True)
            yolo_task_dir = os.path.join(self.settings.yolo_project_dir, f"task_{task_id}")
            os.makedirs(yolo_task_dir, exist_ok=True)

            # 3.1 续训场景：从存储下载源版本的 last.pt 作为初始权重
            resume_weight_path: Optional[str] = None
            if (base_model_type or "BASE").upper() == "TRAINED" and base_version_id:
                resume_weight_path = self._fetch_resume_weight(task_id, int(base_version_id), task_dir)
                self.log(task_id, f"续训权重已准备：{resume_weight_path}")

            # 4. 下载数据集并构建 YOLO 目录结构
            dataset_root = self._prepare_dataset(task_id, dataset_id, task_dir)
            results["dataset_root"] = dataset_root

            # 5. 生成 data.yaml
            label_names = self._load_label_names(dataset_id)
            if not label_names:
                raise RuntimeError("数据集没有标签信息，无法训练")
            data_yaml = yolo_utils.build_data_yaml(dataset_root, label_names)
            results["data_yaml"] = data_yaml
            self.log(task_id, f"已生成 data.yaml: {data_yaml}, 类别: {label_names}")

            # 6. 执行训练（ultralytics）
            self.log(task_id, f"开始 YOLO 训练 {model_base} ...")
            train_start = time.time()
            self.train_yolo(task_id, dataset_root, data_yaml, model_base,
                            epochs, batch_size, imgsz, yolo_task_dir,
                            resume_weight_path=resume_weight_path)
            results["train_cost_seconds"] = round(time.time() - train_start, 2)
            self.log(task_id, f"训练完成，耗时 {results['train_cost_seconds']}s")

            # 7. 收集产物
            best_pt, last_pt, results_csv, args_yaml = self._collect_artifacts(yolo_task_dir)
            results.update({"best_pt": best_pt, "last_pt": last_pt,
                            "results_csv": results_csv, "args_yaml": args_yaml})

            # 8. 指标解析
            final_metrics = yolo_utils.parse_results_csv(results_csv)
            metrics = yolo_utils.collect_epoch_metrics(results_csv)
            db.update_task_metrics(self.settings, task_id, metrics)
            results["metrics"] = final_metrics
            results["epoch_metrics"] = metrics

            # 9. 上传模型产物到 yolo-models/{prefix}/：版本号跨系列可重复，
            # 存储目录优先用后端下发的唯一前缀（task-{id}），缺省回退版本号目录
            version = (expected_version or "").strip() or f"v{time.strftime('%Y%m%d%H%M%S')}"
            prefix = (storage_prefix or "").strip() or version
            self.storage.upload_file(self.models_bucket, f"{prefix}/best.pt", best_pt)
            self.storage.upload_file(self.models_bucket, f"{prefix}/last.pt", last_pt)
            if os.path.exists(results_csv):
                self.storage.upload_file(self.models_bucket, f"{prefix}/results.csv", results_csv, "text/csv")
            if os.path.exists(args_yaml):
                self.storage.upload_file(self.models_bucket, f"{prefix}/args.yaml", args_yaml, "text/yaml")
            results["model_version"] = version
            results["best_pt_key"] = f"{prefix}/best.pt"
            results["last_pt_key"] = f"{prefix}/last.pt"

            # 10. 更新任务状态 + 注册模型版本（成功时清空残留 errorMsg，避免历史失败信息误导）
            db.update_task_status(self.settings, task_id, TASK_STATUS_SUCCESS, epochs, error_msg="")
            db.register_model_version(
                self.settings, task_id, version,
                results["best_pt_key"], results["last_pt_key"],
                final_metrics.get("map50"), final_metrics.get("map5095"),
            )

            # 11. 回调 Spring Boot（可选）
            if callback_url:
                self._send_callback(callback_url, callback_token, {
                    "taskId": task_id, "status": TASK_STATUS_SUCCESS,
                    "version": version, "bestPtKey": results["best_pt_key"],
                    "lastPtKey": results["last_pt_key"],
                    "map50": final_metrics.get("map50"),
                    "map5095": final_metrics.get("map5095"),
                    "progress": epochs,
                })

            self.log(task_id, "训练任务结束：SUCCESS")
            return results

        except KeyboardInterrupt:
            # 用户取消：状态由 cancel 接口已设为 CANCELLED，仅清理不覆盖
            self.log(task_id, "训练已被用户取消")
            try:
                # 清理取消标志，避免下次复用同名 task_id
                import redis as redis_lib
                r = redis_lib.Redis(
                    host=self.settings.redis_host, port=self.settings.redis_port,
                    password=self.settings.redis_password or None,
                    db=self.settings.redis_db_broker, protocol=2,
                )
                r.delete(f"train:cancel:{task_id}")
            except Exception:
                pass
            return {"status": TASK_STATUS_CANCELLED, "task_id": task_id}

        except Exception as e:
            db.update_task_status(self.settings, task_id, TASK_STATUS_FAILED, 0, str(e))
            if callback_url:
                try:
                    self._send_callback(callback_url, callback_token, {
                        "taskId": task_id, "status": TASK_STATUS_FAILED, "errorMsg": str(e),
                    })
                except Exception:
                    pass
            self.log(task_id, f"训练任务失败: {e}")
            raise
        finally:
            shutil.rmtree(task_dir, ignore_errors=True)
    # ------------------------------------------------------------------
    def _fetch_resume_weight(self, task_id: int, base_version_id: int, work_dir: str) -> str:
        """从 MySQL 查 base_version_id 的 last_pt_key，从存储下载到本地临时路径。"""
        from sqlalchemy import text as sql_text
        engine = db.get_engine(self.settings)
        with engine.connect() as conn:
            row = conn.execute(
                sql_text("SELECT version, last_pt_key FROM model_version WHERE id = :vid"),
                {"vid": base_version_id},
            ).mappings().first()
        if not row or not row["last_pt_key"]:
            raise RuntimeError(f"源版本 {base_version_id} 不存在或缺少 last.pt，无法续训")
        key = row["last_pt_key"]
        local_path = os.path.join(work_dir, "resume_last.pt")
        self.log(task_id, f"下载续训权重 version={row['version']} key={key}")
        self.storage.download_to_file(self.models_bucket, key, local_path)
        return local_path

    def _prepare_dataset(self, task_id: int, dataset_id: int, work_dir: str) -> str:
        """从存储下载图片与标注，构建 train/val 目录。

        大数据集优化：
        - 一次性列举 labels/ 得到标注映射，避免逐张 head_object(exists) 探测；
        - 使用线程池并发下载（boto3 client 线程安全），显著缩短准备时间。
        """
        prefix = f"{dataset_id}/"
        image_keys = self.storage.list_keys(self.datasets_bucket, prefix + "images/")
        label_keys = self.storage.list_keys(self.datasets_bucket, prefix + "labels/")
        if not image_keys:
            raise RuntimeError(f"数据集 {dataset_id} 没有图片")

        img_files: Dict[str, str] = {}
        for key in image_keys:
            base = os.path.basename(key)
            if base:
                img_files[os.path.splitext(base)[0]] = key

        # 标注按文件名(去后缀)建索引，直接命中则无需逐张 exists() 探测
        label_by_id: Dict[str, str] = {}
        for key in label_keys:
            base = os.path.basename(key)
            if base:
                label_by_id[os.path.splitext(base)[0]] = key

        self.log(task_id, f"数据集图片: {len(img_files)} 张，标注文件: {len(label_by_id)} 个")

        dataset_root = os.path.join(work_dir, "dataset")
        for split in ("train", "val"):
            os.makedirs(os.path.join(dataset_root, "images", split), exist_ok=True)
            os.makedirs(os.path.join(dataset_root, "labels", split), exist_ok=True)

        # 简单随机划分：80% train / 20% val
        file_ids = sorted(img_files.keys())
        random.seed(42)
        random.shuffle(file_ids)
        split_point = max(1, int(len(file_ids) * 0.8))
        train_ids, val_ids = file_ids[:split_point], file_ids[split_point:]

        work_items: List[tuple] = []
        for split, ids in (("train", train_ids), ("val", val_ids)):
            for file_id in ids:
                work_items.append((split, file_id))

        def fetch_one(item: tuple) -> bool:
            split, file_id = item
            img_key = img_files[file_id]
            try:
                self.storage.download_to_file(
                    self.datasets_bucket, img_key,
                    os.path.join(dataset_root, "images", split, os.path.basename(img_key)),
                )
            except Exception as exc:
                self.log(task_id, f"下载图片失败 {img_key}: {exc}")
                return False
            label_key = label_by_id.get(file_id)
            if label_key:
                try:
                    self.storage.download_to_file(
                        self.datasets_bucket, label_key,
                        os.path.join(dataset_root, "labels", split, f"{file_id}.txt"),
                    )
                except Exception as exc:
                    self.log(task_id, f"下载标注失败 {label_key}: {exc}")
            return True

        workers = max(1, int(getattr(self.settings, "dataset_prep_workers", 16) or 16))
        downloaded = 0
        with ThreadPoolExecutor(max_workers=workers) as executor:
            for ok in executor.map(fetch_one, work_items):
                if ok:
                    downloaded += 1
        if downloaded == 0:
            raise RuntimeError(f"数据集 {dataset_id} 图片下载全部失败，无法训练")

        self.log(task_id, f"数据划分完成: train={len(train_ids)}, val={len(val_ids)}，成功下载 {downloaded} 张")
        return dataset_root

    def _load_label_names(self, dataset_id: int) -> List[str]:
        """从 MySQL 加载标签名称列表（按 code 排序）"""
        try:
            engine = db.get_engine(self.settings)
            with engine.connect() as conn:
                rows = conn.execute(
                    text("SELECT name FROM label WHERE dataset_id = :did ORDER BY code ASC"),
                    {"did": dataset_id},
                ).mappings().all()
            return [row["name"] for row in rows]
        except Exception as exc:
            print(f"[TrainingService] 加载标签失败: {exc}")
            return []
    def _is_cancelled(self, task_id: int) -> bool:
        """检查 Redis 中是否有取消标志 train:cancel:{task_id}"""
        try:
            import redis as redis_lib

            r = redis_lib.Redis(
                host=self.settings.redis_host, port=self.settings.redis_port,
                password=self.settings.redis_password or None,
                db=self.settings.redis_db_broker, decode_responses=True, protocol=2,
            )
            return bool(r.exists(f"train:cancel:{task_id}"))
        except Exception as exc:
            print(f"[_is_cancelled] 检查取消标志失败: {exc}")
            return False

    def _resolve_base_model(self, model_base: str) -> str:
        """解析基础模型权重路径。

        优先从 base_models_dir 按平台模型名加载（如 yolov8n.pt / yolov11m.pt / yolov26m.pt），实现离线训练；
        兼容 Ultralytics 官方命名（v11/v26 权重名不带 v，如 yolo11m.pt / yolo26m.pt）；若目录
        未命中则回退为名称，交给 ultralytics 自行解析（可能联网下载）。
        """
        base_dir = self.settings.base_models_dir
        cand = os.path.join(base_dir, f"{model_base}.pt")
        if os.path.isfile(cand):
            return cand
        # 官方 v11/v26 权重文件名不带 v：yolov11m -> yolo11m，yolov26m -> yolo26m
        if model_base.startswith("yolov"):
            alias = os.path.join(base_dir, "yolo" + model_base[len("yolov"):] + ".pt")
            if os.path.isfile(alias):
                return alias
        return f"{model_base}.pt"

    def train_yolo(self, task_id: int, dataset_root: str, data_yaml: str, model_base: str,
                   epochs: int, batch_size: int, imgsz: int, work_dir: str,
                   resume_weight_path: Optional[str] = None) -> None:
        """调用 ultralytics YOLO.train 执行训练（含进度回调）。如提供 resume_weight_path，则从该权重初始化。"""
        from ultralytics import YOLO

        # 注入 YOLOv13 自定义模块兼容层：上传的 YOLOv13 自训练权重续训时需要
        from ..utils.yolov13_compat import ensure_yolov13_compat
        ensure_yolov13_compat()

        if resume_weight_path:
            self.log(task_id, f"从续训权重加载模型: {resume_weight_path}")
            model = YOLO(resume_weight_path)
        else:
            weight = self._resolve_base_model(model_base)
            self.log(task_id, f"加载基础模型: {weight}")
            model = YOLO(weight)

        def progress_callback(trainer):
            try:
                # 检查取消标志（Spring Boot 或 FastAPI 会写入 Redis）
                if self._is_cancelled(task_id):
                    self.log(task_id, "检测到取消标志，终止训练")
                    trainer.stop = True
                    raise KeyboardInterrupt("用户取消训练")
                progress = getattr(trainer, "epoch", 0) + 1
                db.update_task_status(self.settings, task_id, TASK_STATUS_RUNNING, progress)
            except KeyboardInterrupt:
                raise
            except Exception:
                pass

        # ultralytics 8.4.x 不再接受把 callbacks 作为 model.train() 的 kwargs（会被
        # check_dict_alignment 依据 DEFAULT_CFG_KEYS 校验并抛 "'callbacks' is not a valid YOLO argument"）。
        # 官方推荐做法：在 train() 之前用 model.add_callback(event, fn) 注册，YOLO 会把它传给 trainer。
        model.add_callback("on_train_epoch_end", progress_callback)

        # 提速方案 A：Celery prefork 子进程默认 daemon=True，PyTorch DataLoader workers>0
        # 再 fork 会被 multiprocessing 的 "daemonic processes are not allowed to have
        # children" 检查拦截。训练前临时解除当前进程 daemon 标志恢复多进程数据加载
        # （DataLoader 子进程自身仍 daemon=True，父进程退出时自动回收），结束后恢复原值。
        import multiprocessing
        workers = max(0, int(getattr(self.settings, "train_workers", 8) or 0))
        workers = min(workers, os.cpu_count() or workers)
        proc = multiprocessing.current_process()
        old_daemon = proc.daemon
        # Python 3.14 起 Linux 默认 start method 改为 forkserver，需 pickle 进程对象，
        # 而 Celery billiard 的 AuthenticationString 禁止 pickle（报 "Pickling an
        # AuthenticationString object is disallowed"）。DataLoader 子进程只做 CPU
        # 加载不碰 CUDA，fork 安全；训练期间临时切回 fork，结束后恢复。
        old_start_method = multiprocessing.get_start_method(allow_none=True)
        if workers > 0:
            proc.daemon = False
            if old_start_method != "fork":
                multiprocessing.set_start_method("fork", force=True)
            self.log(task_id, f"启用 DataLoader 多进程数据加载: workers={workers}, start_method=fork")

        train_kwargs = dict(
            data=data_yaml,
            epochs=epochs,
            batch=batch_size,
            imgsz=imgsz,
            project=work_dir,
            name="yolo_run",
            exist_ok=True,
            workers=workers,
        )
        if self.settings.device not in (None, "", "auto"):
            train_kwargs["device"] = self.settings.device
        try:
            model.train(**train_kwargs)
        except (RuntimeError, TypeError) as exc:
            # 多进程加载启动失败（daemon 检查 / AuthenticationString pickle 等）时回退串行加载，保证任务不因此失败
            msg = str(exc).lower()
            if workers > 0 and ("daemon" in msg or "authenticationstring" in msg or "pickle" in msg):
                self.log(task_id, f"DataLoader 多进程启动失败，回退 workers=0: {exc}")
                train_kwargs["workers"] = 0
                model.train(**train_kwargs)
            else:
                raise
        finally:
            proc.daemon = old_daemon
            if workers > 0 and old_start_method and old_start_method != "fork":
                multiprocessing.set_start_method(old_start_method, force=True)

    def _collect_artifacts(self, work_dir: str):
        """收集训练产物 best.pt / last.pt / results.csv / args.yaml

        ultralytics 8.4.x 将权重放在 {run}/weights/ 子目录，results.csv / args.yaml 在 {run}/ 根。
        兼容旧版（直接在根目录）：先查 weights/，再查根目录。
        """
        run_dir = os.path.join(work_dir, "yolo_run")
        weights_dir = os.path.join(run_dir, "weights")
        best = last = results_csv = args_yaml = None
        if os.path.exists(run_dir):
            # 权重：优先 weights/ 子目录（8.4.x），回退根目录（旧版）
            for d in (weights_dir, run_dir):
                if not os.path.isdir(d):
                    continue
                for fname in os.listdir(d):
                    fp = os.path.join(d, fname)
                    if fname == "best.pt" and os.path.isfile(fp) and best is None:
                        best = fp
                    elif fname == "last.pt" and os.path.isfile(fp) and last is None:
                        last = fp
            # 指标与配置：根目录
            for fname in os.listdir(run_dir):
                fp = os.path.join(run_dir, fname)
                if fname == "results.csv" and os.path.isfile(fp):
                    results_csv = fp
                elif fname == "args.yaml" and os.path.isfile(fp):
                    args_yaml = fp
        # 1-epoch 或 mAP=0 时 ultralytics 可能不生成 best.pt，回退 last.pt
        if best is None:
            best = last
        if best is None:
            raise RuntimeError("训练未生成 best.pt / last.pt，检查训练配置")
        return best, last, results_csv, args_yaml

    def _send_callback(self, callback_url: str, token: Optional[str], payload: Dict[str, Any]):
        import requests

        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-Callback-Token"] = token
        resp = requests.post(callback_url, json=payload, headers=headers, timeout=10)
        resp.raise_for_status()

    def log(self, task_id: int, message: str):
        print(f"[task={task_id}] {yolo_utils.format_log_time()} {message}", flush=True)