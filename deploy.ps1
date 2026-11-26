# =============================================================================
# YOLO 视觉识别模型管理平台 —— 一键部署（Windows，Docker Compose）
#
#   .\deploy.ps1                                   # 交互式引导（推荐首次使用）
#   .\deploy.ps1 -Mode cpu                         # 不提问，全部组件走 docker 镜像
#   .\deploy.ps1 -Mode gpu
#   .\deploy.ps1 -Action config                    # 只引导并生成 .env，不构建不启动
#   .\deploy.ps1 -Action env                       # 用下面【配置区】的值生成 .env 并校验
#   .\deploy.ps1 -Action status|logs|down|restart|schema
#
#   非交互指定外部组件（适合脚本化重复部署 / CI）：
#     .\deploy.ps1 -Mode cpu -Action env -ExternalMysql 10.0.0.9 -MysqlDatabase glm `
#                  -MysqlUser glm -MysqlPassword 'pwd' -AutoDdl `
#                  -ExternalRedis 10.0.0.9 -ExternalStorage http://10.0.0.9:9000 -AutoBucket
#
#   本机已装 MySQL / Redis 时，避让自建容器映射到宿主机的端口：
#     .\deploy.ps1 -Mode cpu -MysqlExposePort 13306 -RedisExposePort 16379
#
# 配置最终写入同目录 .env（已 gitignore），docker-compose.yml 只负责读取。
# 重复执行是安全的：镜像走缓存，建表与建桶都是 IF NOT EXISTS / --ignore-existing。
# 若提示无法加载脚本：Set-ExecutionPolicy -Scope Process RemoteSigned
# =============================================================================

[CmdletBinding()]
param(
  [ValidateSet('cpu', 'gpu')][string]$Mode = '',
  [ValidateSet('deploy', 'config', 'env', 'status', 'logs', 'down', 'restart', 'schema')][string]$Action = 'deploy',

  # 引导粒度：full=逐项确认（默认），quick=只问关键项，none=完全不提问
  [ValidateSet('full', 'quick', 'none')][string]$Guide = '',

  # 非交互覆盖项：给了值就视为使用「已有」组件，对应的容器不会启动
  [string]$ExternalMysql = '',
  [string]$ExternalRedis = '',
  [string]$ExternalStorage = '',
  [string]$MysqlDatabase = '',
  [string]$MysqlUser = '',
  [string]$MysqlPassword = '',
  [switch]$AutoDdl,
  [switch]$AutoBucket,
  [string]$CudaTag = '',
  [string]$GpuCount = '',
  [string]$WebPort = '',
  [string]$JavaPort = '',
  [string]$PyPort = '',
  # 自建组件映射到宿主机的端口（本机已装 MySQL/Redis 时用它们避让）
  [string]$MysqlExposePort = '',
  [string]$RedisExposePort = '',
  [string]$RustfsApiPort = '',
  [string]$RustfsConsolePort = '',
  [switch]$SkipDeploy
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# =============================================================================
# 【配置区】以下即为默认值；交互式引导会在它们的基础上让你逐项确认/覆盖。
# 注意：口令不要包含 $ 、空格或 “空格+#”，compose 解析 .env 时会出错。
# =============================================================================
$Conf = [ordered]@{
  # ---- 数据库 ----
  BUILTIN_MYSQL       = 'true'          # true=容器内自建 MySQL；false=使用已有实例
  MYSQL_HOST          = 'mysql'         # 自建时固定 mysql（容器网络内的服务名）
  MYSQL_PORT          = '3306'          # 应用连接的端口
  MYSQL_EXPOSE_PORT   = '3306'          # 自建时额外映射到宿主机的端口
  MYSQL_DATABASE      = 'yolo_manager'  # 业务库名（schema.sql 里写死的库名会自动换成它）
  MYSQL_USER          = 'yolo'          # 应用账号（自建时由 MySQL 镜像自动创建）
  MYSQL_PASSWORD      = 'YoloApp#2026'
  MYSQL_ADMIN_USER    = 'root'          # 建表使用的管理账号
  MYSQL_ADMIN_PASSWORD = 'YoloRoot#2026'
  AUTO_DDL            = 'false'         # 用已有 MySQL 时是否仍自动执行 schema.sql

  # ---- Redis ----
  BUILTIN_REDIS       = 'true'
  REDIS_HOST          = 'redis'
  REDIS_PORT          = '6379'
  REDIS_EXPOSE_PORT   = '6379'
  REDIS_PASSWORD      = 'YoloRedis#2026'  # 自建时不能留空：容器以 --requirepass 启动
  REDIS_METRICS_DB    = '5'               # 指标库；Java 与 Python 由同一个变量注入，天然一致
  REDIS_DB_BROKER     = '1'
  REDIS_DB_RESULT     = '2'

  # ---- 对象存储（S3 兼容）----
  BUILTIN_STORAGE     = 'true'
  S3_ENDPOINT         = 'http://rustfs:9000'  # 必须是「应用容器能访问」的地址
  S3_ACCESS_KEY       = 'yoloRustfsAdmin'     # 长度 5~20 字符，建议纯字母数字
  S3_SECRET_KEY       = 'YoloRustfs#2026'     # 长度 8~40 字符，建议纯字母数字
  S3_REGION           = 'us-east-1'
  S3_USE_SSL          = 'false'
  S3_PATH_STYLE       = 'true'                # Python 侧固定 path-style，外部存储需支持
  S3_BUCKET_DATASETS  = 'yolo-datasets'
  S3_BUCKET_MODELS    = 'yolo-models'
  S3_BUCKET_TESTS     = 'yolo-tests'
  AUTO_BUCKET         = 'false'               # 用已有对象存储时是否自动建缺失的桶
  RUSTFS_API_PORT     = '9000'                # 以下五项仅自建 RustFS 时生效
  RUSTFS_CONSOLE_PORT = '9001'
  RUSTFS_IMAGE        = 'rustfs/rustfs:latest'
  # 单机部署时四个数据卷落在同一块设备上，RustFS 的磁盘校验会直接拒启；
  # 多盘真分布式部署请改回 false
  RUSTFS_BYPASS_DISK_CHECK = 'true'

  # ---- 应用端口 ----
  WEB_PORT            = '80'            # 前端入口（浏览器访问这个）
  JAVA_PORT           = '8080'          # 管理后端
  PY_PORT             = '8000'          # 训练/推理服务

  # ---- 训练与推理参数 ----
  INSTALL_YOLO        = 'true'          # false 则镜像不装 torch/ultralytics
  CUDA_TAG            = 'cu124'         # GPU 轮子后缀：cu118 / cu121 / cu124 / cu126
  GPU_COUNT           = 'all'           # GPU 模式使用几张卡：all 或整数
  WORKER_CONCURRENCY  = '1'             # 单容器并发训练任务数，>1 需谨慎
  TRAIN_WORKERS       = '4'             # 下面两个值按训练模式取默认
  TRAIN_WORKERS_GPU   = '8'
  DEFAULT_BATCH_SIZE  = '8'
  DEFAULT_BATCH_SIZE_GPU = '16'
  TRAIN_SHM_SIZE      = '8g'            # DataLoader 多进程共享内存，不足会报 broken pipe
  PIP_INDEX_URL       = 'https://pypi.org/simple'  # 国内建议 https://pypi.tuna.tsinghua.edu.cn/simple
  DEFAULT_EPOCHS      = '100'
  DEFAULT_IMGSZ       = '640'
  DATASET_PREP_WORKERS = '16'

  # ---- 跨机部署与回调（单机保持默认即可）----
  TRAIN_SERVICE_URL   = 'http://train-api:8000'  # 跨机改成 http://<训练机IP>:<PY_PORT>
  CALLBACK_BASE_URL   = 'http://manager:8080'    # 跨机改成 http://<管理机IP>:<JAVA_PORT>
  TRAIN_DELIVERY_MODE = 'http'                   # http=调 /api/train/start；redis=写 train:queue
  CALLBACK_TOKEN      = ''                       # 留空不校验；设置后两端必须一致

  # ---- 镜像与 JVM（内网/离线环境按需替换）----
  MYSQL_IMAGE         = 'mysql:8.0'
  REDIS_IMAGE         = 'redis:7-alpine'
  ALPINE_IMAGE        = 'alpine:3.20'
  MC_IMAGE            = 'minio/mc:latest'
  JAVA_OPTS           = '-Xms512m -Xmx1024m'
}
$TrainMode = 'cpu'

# =============================================================================
# 【配置区结束】
# =============================================================================

# ---------- 工具函数 ----------
# 必须用简单函数的 $args 收集参数：用 ValueFromRemainingArguments 的 advanced function 时，
# -d 会被 PowerShell 当成公共参数 -Debug 的前缀吞掉，compose 实际收到的是 up --remove-orphans
# （前台模式），脚本会永久挂住。
function Invoke-Compose {
  $ComposeArgs = @($args)
  Write-Verbose "docker compose $($ComposeArgs -join ' ')"
  & docker compose @ComposeArgs
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose $($ComposeArgs -join ' ') 执行失败（退出码 $LASTEXITCODE）"
  }
}

function Test-True([string]$Value) { return ($Value -eq 'true') }

# 交互开关：-Guide none、无键盘输入（管道/计划任务）时自动跳过提问
$script:Interactive = $true

function Ask {
  param([string]$Message, [string]$Default = '')
  if (-not $script:Interactive) { return $Default }
  $hint = if ($Default -ne '') { " [$Default]" } else { '' }
  $v = Read-Host "$Message$hint"
  if ($null -eq $v) { return $Default }
  $v = $v.TrimEnd("`r")
  if ($v -eq '') { return $Default }
  return $v
}

function AskRequired {
  param([string]$Message)
  while ($true) {
    $v = Ask "$Message（不能留空）" ''
    if ($v -ne '') { return $v }
    Write-Host '    ! 该项不能为空'
  }
}

function AskPort {
  param([string]$Message, [string]$Default)
  while ($true) {
    $v = Ask $Message $Default
    if ($v -match '^\d+$' -and [int]$v -ge 1 -and [int]$v -le 65535) { return $v }
    Write-Host '    ! 请输入 1~65535 的数字'
  }
}

# 返回 'true' / 'false'
function AskBool {
  param([string]$Message, [string]$Default)
  if (-not $script:Interactive) { return $Default }
  $hint = if ($Default -eq 'true') { 'Y/n' } else { 'y/N' }
  $v = Read-Host "$Message [$hint]"
  if ($null -eq $v) { $v = '' }
  switch ($v.TrimEnd("`r").ToLower()) {
    'y'     { return 'true' }
    'yes'   { return 'true' }
    'n'     { return 'false' }
    'no'    { return 'false' }
    ''      { return $Default }
    default {
      Write-Host ("    ! 只接受 y / n，已保留默认（{0}）" -f $(if ($Default -eq 'true') { '是' } else { '否' }))
      return $Default
    }
  }
}

# 本项目自己已在跑的容器所映射的端口（重复部署时不应该算冲突）
function Get-OwnPublishedPorts {
  $ports = @()
  try {
    $ids = & docker compose ps -q 2>$null | Where-Object { $_ }
    foreach ($id in $ids) {
      foreach ($line in (& docker port $id 2>$null)) {
        if ("$line" -match '->.*:(\d+)$') { $ports += [int]$Matches[1] }
      }
    }
  } catch { }
  return $ports | Select-Object -Unique
}

function Test-PortFree {
  param([string]$Port, [string]$Name)
  if ($script:OwnPorts -contains [int]$Port) {
    Write-Host "  [skip] 端口 $Port 由本项目已在跑的容器占用（$Name），重复部署沿用现有容器" -ForegroundColor DarkGray
    return $true
  }
  $listen = @(Get-NetTCPConnection -State Listen -LocalPort ([int]$Port) -ErrorAction SilentlyContinue)
  if (-not $listen) {
    # 回退 netstat：只匹配 LISTENING 行，不能写成 ":$Port\s"，否则作为远端端口的
    # 普通出站连接（例如浏览器访问别人的 80）会被误判成占用
    $listen = @(netstat -ano 2>$null | Select-String -Pattern ":$Port\s+\S+\s+LISTENING")
  }
  if ($listen.Count -gt 0) {
    Write-Host "[FAIL] 端口 $Port 已被占用（$Name），请改配置里对应的映射端口" -ForegroundColor Red
    return $false
  }
  return $true
}

function Wait-Url {
  param([string]$Url, [string]$Name, [string]$Service, [int]$Tries = 60)
  for ($i = 1; $i -le $Tries; $i++) {
    try {
      Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
      Write-Host "  [OK] $Name 就绪"
      return $true
    } catch { Start-Sleep -Seconds 5 }
  }
  Write-Host "  [FAIL] $Name 在 $(5 * $Tries) 秒内没有响应：$Url" -ForegroundColor Yellow
  Write-Host "    排查：docker compose ps ；docker compose logs --tail=100 $Service"
  return $false
}

# ---------- 动作与引导粒度 ----------
if ($Action -in @('status', 'logs', 'down', 'restart', 'schema')) {
  if (-not (Test-Path "$PSScriptRoot\.env")) {
    throw '还没有 .env，请先执行 .\deploy.ps1（引导）或 .\deploy.ps1 -Mode cpu'
  }
  switch ($Action) {
    'status'  { Invoke-Compose ps; exit 0 }
    'logs'    { Invoke-Compose logs -f --tail=200; exit 0 }
    'down'    { Invoke-Compose down; exit 0 }
    'restart' { Invoke-Compose restart; exit 0 }
    'schema'  {
      Write-Host '→ 执行 schema.sql（幂等）；目标库见 .env 的 MYSQL_HOST/MYSQL_DATABASE'
      Write-Host '  若用的是已有 MySQL 且没勾选自动建表，请先 .\deploy.ps1 -Action config 打开该选项'
      Invoke-Compose run --rm db-init
      exit 0
    }
  }
}

# 没显式给 -Mode / -Guide 时进入引导；显式给了就完全不提问
if ($Guide -ne '') {
  $guideLevel = $Guide
} elseif ($Mode -ne '') {
  $guideLevel = 'none'
} else {
  $guideLevel = 'full'
}
if ($Mode -ne '') { $TrainMode = $Mode }
if ($Action -eq 'env') { $guideLevel = 'none' }
$doDeploy = -not $SkipDeploy -and ($Action -eq 'deploy')

# 非交互判定：-Guide none，或没有键盘输入（管道/计划任务/RemoteSigned 脚本调用）
if ($guideLevel -eq 'none') {
  $script:Interactive = $false
} else {
  $noKeyboard = (-not [Environment]::UserInteractive) -or [Console]::IsInputRedirected
  if ($noKeyboard) {
    Write-Host '! 检测到输入不是交互终端，改为使用【配置区】默认值（等效于 -Guide none）' -ForegroundColor Yellow
    $guideLevel = 'none'
  }
  $script:Interactive = ($guideLevel -ne 'none')
}

# ---------- 交互式引导 ----------
if ($script:Interactive) {
  Write-Host @'

=============================================================================
 YOLO 平台一键部署引导      （直接回车 = 保留中括号里的默认值；y/n 二选一）
 依赖：Docker Desktop（或 Docker Engine）+ Compose v2；GPU 训练另需支持 CUDA 的显卡
 全程默认「组件都用 docker 镜像」，想指向已部署好的组件就在对应问题选 n
=============================================================================
'@

  $lvl = Ask '  引导粒度（1=完整引导，含外部组件细节；2=快速引导，全部走 docker 镜像）' '1'
  if ($lvl -eq '2') { $guideLevel = 'quick' }

  # ---- 1. 训练模式 ----
  Write-Host ''
  Write-Host '[1/7] 训练加速方式'
  while ($true) {
    $TrainMode = Ask '  YOLO 训练使用 CPU 还是 GPU 加速（cpu/gpu）' $TrainMode
    if ($TrainMode -in @('cpu', 'gpu')) { break }
    Write-Host '    ! 只能填 cpu 或 gpu'
  }
  Write-Host '    说明：GPU 只挂在训练 worker 容器上；在线识别仍走 CPU'

  # ---- 2. MySQL ----
  Write-Host ''
  Write-Host '[2/7] 数据库 MySQL（需要 MySQL 8.0+ 且字符集 utf8mb4）'
  $Conf.BUILTIN_MYSQL = AskBool '  用 docker 镜像自建 MySQL 吗？（n=连你已有的实例）' $Conf.BUILTIN_MYSQL
  if (Test-True $Conf.BUILTIN_MYSQL) {
    $Conf.MYSQL_DATABASE       = Ask '  业务库名（不存在时自动创建）' $Conf.MYSQL_DATABASE
    $Conf.MYSQL_USER           = Ask '  应用账号（容器自动创建）' $Conf.MYSQL_USER
    $Conf.MYSQL_PASSWORD       = Ask '  应用账号口令' $Conf.MYSQL_PASSWORD
    $Conf.MYSQL_ADMIN_PASSWORD = Ask '  root 口令（建库建表用）' $Conf.MYSQL_ADMIN_PASSWORD
    $Conf.MYSQL_EXPOSE_PORT    = AskPort '  映射到宿主机的端口（与其他 MySQL 冲突时改）' $Conf.MYSQL_EXPOSE_PORT
  } else {
    $Conf.MYSQL_HOST     = AskRequired '  已有 MySQL 地址（应用容器要能访问到它，别写 127.0.0.1）'
    $Conf.MYSQL_PORT     = AskPort '  端口' '3306'
    $Conf.MYSQL_DATABASE = Ask '  业务库名（需已存在或账号有建库权限）' $Conf.MYSQL_DATABASE
    $Conf.MYSQL_USER     = Ask '  应用账号' $Conf.MYSQL_USER
    $Conf.MYSQL_PASSWORD = Ask '  应用账号口令' $Conf.MYSQL_PASSWORD
    $Conf.AUTO_DDL       = AskBool '  由脚本自动执行 schema.sql 建表吗？（表已建好选 n）' $Conf.AUTO_DDL
    if (Test-True $Conf.AUTO_DDL) {
      $Conf.MYSQL_ADMIN_USER     = Ask '  有建表权限的账号' $Conf.MYSQL_ADMIN_USER
      $Conf.MYSQL_ADMIN_PASSWORD = Ask '  该账号口令' $Conf.MYSQL_ADMIN_PASSWORD
    }
    Write-Host '    ! 该实例若用 caching_sha2_password 认证，Python 侧（PyMySQL 未装 cryptography）'
    Write-Host '      可能连不上：把账号改成 mysql_native_password，或在镜像里补装 cryptography'
  }

  # ---- 3. Redis ----
  Write-Host ''
  Write-Host '[3/7] Redis（Celery 队列 + 训练指标缓存）'
  $Conf.BUILTIN_REDIS = AskBool '  用 docker 镜像自建 Redis 吗？' $Conf.BUILTIN_REDIS
  if (Test-True $Conf.BUILTIN_REDIS) {
    $Conf.REDIS_PASSWORD     = Ask '  口令（自建时不能留空）' $Conf.REDIS_PASSWORD
    $Conf.REDIS_EXPOSE_PORT  = AskPort '  映射到宿主机的端口' $Conf.REDIS_EXPOSE_PORT
  } else {
    $Conf.REDIS_HOST     = AskRequired '  已有 Redis 地址'
    $Conf.REDIS_PORT     = AskPort '  端口' '6379'
    $Conf.REDIS_PASSWORD = Ask '  口令（没有就回车留空）' ''
  }
  $Conf.REDIS_METRICS_DB = AskPort '  训练指标所在库编号（Java/Python 共用此值，会自动一致）' $Conf.REDIS_METRICS_DB
  if ($guideLevel -eq 'full') {
    $Conf.REDIS_DB_BROKER = AskPort '  Celery broker 库编号' $Conf.REDIS_DB_BROKER
    $Conf.REDIS_DB_RESULT = AskPort '  Celery result 库编号' $Conf.REDIS_DB_RESULT
  }

  # ---- 4. 对象存储 ----
  Write-Host ''
  Write-Host '[4/7] 对象存储（S3 兼容，数据集/模型/测试三个桶）'
  $Conf.BUILTIN_STORAGE = AskBool '  用 docker 镜像自建 RustFS 吗？（n=连你已有的 MinIO/RustFS 等）' $Conf.BUILTIN_STORAGE
  if (Test-True $Conf.BUILTIN_STORAGE) {
    $Conf.S3_ACCESS_KEY       = Ask '  access key（5~20 字符，建议纯字母数字）' $Conf.S3_ACCESS_KEY
    $Conf.S3_SECRET_KEY       = Ask '  secret key（8~40 字符，建议纯字母数字）' $Conf.S3_SECRET_KEY
    $Conf.RUSTFS_API_PORT     = AskPort '  S3 API 映射到宿主机的端口（9000 被 MinIO 占用时改）' $Conf.RUSTFS_API_PORT
    $Conf.RUSTFS_CONSOLE_PORT = AskPort '  控制台映射到宿主机的端口' $Conf.RUSTFS_CONSOLE_PORT
    if ($guideLevel -eq 'full') {
      $Conf.RUSTFS_IMAGE = Ask '  RustFS 镜像（要钉版本可改成 rustfs/rustfs:1.0.4）' $Conf.RUSTFS_IMAGE
    }
  } else {
    $Conf.S3_ENDPOINT    = AskRequired '  已有服务的 endpoint，需带协议（如 http://10.0.0.9:9000）'
    $Conf.S3_ACCESS_KEY  = AskRequired '  access key'
    $Conf.S3_SECRET_KEY  = AskRequired '  secret key'
    $Conf.S3_REGION      = Ask '  region' $Conf.S3_REGION
    $Conf.S3_USE_SSL     = AskBool '  通过 HTTPS 访问吗？' $Conf.S3_USE_SSL
    $Conf.AUTO_BUCKET    = AskBool '  由脚本自动创建缺失的桶吗？（桶已建好选 n）' $Conf.AUTO_BUCKET
    Write-Host '    ! Python 侧固定使用 path-style 寻址（桶名在路径里），MinIO/RustFS 都支持；'
    Write-Host '      只支持 virtual-host 风格的云服务需要换成兼容 path-style 的接入点'
  }
  $Conf.S3_BUCKET_DATASETS = Ask '  数据集桶名' $Conf.S3_BUCKET_DATASETS
  $Conf.S3_BUCKET_MODELS   = Ask '  模型桶名' $Conf.S3_BUCKET_MODELS
  $Conf.S3_BUCKET_TESTS    = Ask '  测试桶名' $Conf.S3_BUCKET_TESTS
  if ($guideLevel -eq 'full') {
    $Conf.S3_PATH_STYLE = AskBool '  Java 侧也使用 path-style 寻址吗？' $Conf.S3_PATH_STYLE
  }

  # ---- 5. 应用端口 ----
  Write-Host ''
  Write-Host '[5/7] 应用对外端口'
  $Conf.WEB_PORT  = AskPort '  前端入口端口（浏览器访问 http://<IP>:端口）' $Conf.WEB_PORT
  $Conf.JAVA_PORT = AskPort '  管理后端端口' $Conf.JAVA_PORT
  if ($guideLevel -eq 'full') { $Conf.PY_PORT = AskPort '  训练/推理服务端口' $Conf.PY_PORT }

  # ---- 6. 训练参数 ----
  Write-Host ''
  Write-Host '[6/7] 训练相关参数'
  $Conf.INSTALL_YOLO = AskBool '  镜像内安装 torch/ultralytics 吗？（n=只能跑管理端流程，构建快很多）' $Conf.INSTALL_YOLO
  if ($TrainMode -eq 'gpu') {
    $Conf.CUDA_TAG          = Ask '  CUDA 轮子后缀（cu118/cu121/cu124/cu126）' $Conf.CUDA_TAG
    $Conf.GPU_COUNT         = Ask '  使用几张显卡（all 或数字）' $Conf.GPU_COUNT
    $Conf.TRAIN_WORKERS     = Ask '  DataLoader 子进程数' $Conf.TRAIN_WORKERS_GPU
    $Conf.DEFAULT_BATCH_SIZE = Ask '  默认 batch_size' $Conf.DEFAULT_BATCH_SIZE_GPU
  } else {
    $Conf.TRAIN_WORKERS     = Ask '  DataLoader 子进程数' $Conf.TRAIN_WORKERS
    $Conf.DEFAULT_BATCH_SIZE = Ask '  默认 batch_size' $Conf.DEFAULT_BATCH_SIZE
  }
  if ($guideLevel -eq 'full') {
    $Conf.WORKER_CONCURRENCY = Ask '  单容器并发训练任务数' $Conf.WORKER_CONCURRENCY
    $Conf.TRAIN_SHM_SIZE     = Ask '  共享内存大小' $Conf.TRAIN_SHM_SIZE
    $Conf.PIP_INDEX_URL      = Ask '  PyPI 源（国内建议换成镜像站）' $Conf.PIP_INDEX_URL
  }

  # ---- 7. 跨机与回调 ----
  Write-Host ''
  Write-Host '[7/7] 跨机部署与回调（单机直接回车用默认值）'
  if ($guideLevel -eq 'full') {
    $Conf.TRAIN_SERVICE_URL = Ask '  管理后端访问训练服务的地址（跨机改成 http://<训练机IP>:端口）' $Conf.TRAIN_SERVICE_URL
    $Conf.CALLBACK_BASE_URL = Ask '  训练服务回调管理后端的地址（跨机改成 http://<管理机IP>:端口）' $Conf.CALLBACK_BASE_URL
    $Conf.CALLBACK_TOKEN    = Ask '  回调 token（留空=不校验；设置后两端必须一致）' $Conf.CALLBACK_TOKEN
    $Conf.JAVA_OPTS         = Ask '  JVM 参数' $Conf.JAVA_OPTS
  } else {
    Write-Host '    保持默认：容器内部互联（train-api:8000 / manager:8080），回调不校验'
  }

  if ($Action -eq 'deploy') {
    if ((AskBool '  本次只生成 .env（不构建、不启动容器）吗？' 'false') -eq 'true') { $doDeploy = $false }
  }
} else {
  # ---------- 非交互参数覆盖 ----------
  if ($ExternalMysql -ne '') {
    $Conf.BUILTIN_MYSQL = 'false'
    $Conf.MYSQL_HOST = $ExternalMysql
    if ($MysqlDatabase -ne '') { $Conf.MYSQL_DATABASE = $MysqlDatabase }
    if ($MysqlUser -ne '') { $Conf.MYSQL_USER = $MysqlUser }
    if ($MysqlPassword -ne '') { $Conf.MYSQL_PASSWORD = $MysqlPassword }
  }
  if ($MysqlDatabase -ne '' -and $ExternalMysql -eq '') { $Conf.MYSQL_DATABASE = $MysqlDatabase }
  if ($MysqlUser -ne '' -and $ExternalMysql -eq '') { $Conf.MYSQL_USER = $MysqlUser }
  if ($MysqlPassword -ne '' -and $ExternalMysql -eq '') { $Conf.MYSQL_PASSWORD = $MysqlPassword }
  if ($ExternalRedis -ne '') {
    $Conf.BUILTIN_REDIS = 'false'
    $Conf.REDIS_HOST = $ExternalRedis
  }
  if ($ExternalStorage -ne '') {
    $Conf.BUILTIN_STORAGE = 'false'
    $Conf.S3_ENDPOINT = $ExternalStorage
  }
  if ($AutoDdl.IsPresent) { $Conf.AUTO_DDL = 'true' }
  if ($AutoBucket.IsPresent) { $Conf.AUTO_BUCKET = 'true' }
  if ($CudaTag -ne '') { $Conf.CUDA_TAG = $CudaTag }
  if ($GpuCount -ne '') { $Conf.GPU_COUNT = $GpuCount }
  if ($WebPort -ne '') { $Conf.WEB_PORT = $WebPort }
  if ($JavaPort -ne '') { $Conf.JAVA_PORT = $JavaPort }
  if ($PyPort -ne '') { $Conf.PY_PORT = $PyPort }
  if ($MysqlExposePort -ne '') { $Conf.MYSQL_EXPOSE_PORT = $MysqlExposePort }
  if ($RedisExposePort -ne '') { $Conf.REDIS_EXPOSE_PORT = $RedisExposePort }
  if ($RustfsApiPort -ne '') { $Conf.RUSTFS_API_PORT = $RustfsApiPort }
  if ($RustfsConsolePort -ne '') { $Conf.RUSTFS_CONSOLE_PORT = $RustfsConsolePort }
}

# ---------- 归一化：内置组件用容器网络里的固定地址 ----------
if (Test-True $Conf.BUILTIN_MYSQL) {
  $Conf.MYSQL_HOST = 'mysql'; $Conf.MYSQL_PORT = '3306'
  $Conf.MYSQL_ADMIN_USER = 'root'; $Conf.AUTO_DDL = 'false'
}
if (Test-True $Conf.BUILTIN_REDIS) {
  $Conf.REDIS_HOST = 'redis'; $Conf.REDIS_PORT = '6379'
}
if (Test-True $Conf.BUILTIN_STORAGE) {
  $Conf.S3_ENDPOINT = 'http://rustfs:9000'; $Conf.S3_USE_SSL = 'false'
  $Conf.S3_PATH_STYLE = 'true'; $Conf.AUTO_BUCKET = 'false'
}
if ($TrainMode -notin @('cpu', 'gpu')) { $TrainMode = 'cpu' }
if ($TrainMode -eq 'gpu' -and $guideLevel -eq 'none') {
  $Conf.TRAIN_WORKERS = $Conf.TRAIN_WORKERS_GPU
  $Conf.DEFAULT_BATCH_SIZE = $Conf.DEFAULT_BATCH_SIZE_GPU
}
$Conf.TORCH_INDEX_URL_CPU = 'https://download.pytorch.org/whl/cpu'
$Conf.TORCH_INDEX_URL_GPU = "https://download.pytorch.org/whl/$($Conf.CUDA_TAG)"

# ---------- COMPOSE_PROFILES ----------
$profiles = New-Object System.Collections.Generic.List[string]
$profiles.Add($TrainMode) | Out-Null
if (Test-True $Conf.BUILTIN_MYSQL) { $profiles.Add('builtin-mysql') } elseif (Test-True $Conf.AUTO_DDL) { $profiles.Add('auto-ddl') }
if (Test-True $Conf.BUILTIN_REDIS) { $profiles.Add('builtin-redis') }
if (Test-True $Conf.BUILTIN_STORAGE) { $profiles.Add('builtin-storage') } elseif (Test-True $Conf.AUTO_BUCKET) { $profiles.Add('auto-bucket') }
$ComposeProfiles = $profiles -join ','

# ---------- 必填校验 ----------
function RequireValue {
  param([string]$Name, [string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name 不能为空" }
}
RequireValue 'MYSQL_DATABASE' $Conf.MYSQL_DATABASE
RequireValue 'MYSQL_USER' $Conf.MYSQL_USER
RequireValue 'MYSQL_PASSWORD' $Conf.MYSQL_PASSWORD
if (-not (Test-True $Conf.BUILTIN_MYSQL)) { RequireValue 'MYSQL_HOST' $Conf.MYSQL_HOST }
if (-not (Test-True $Conf.BUILTIN_REDIS)) { RequireValue 'REDIS_HOST' $Conf.REDIS_HOST }
if ((Test-True $Conf.BUILTIN_REDIS) -and $Conf.REDIS_PASSWORD -eq '') {
  throw '自建 Redis 必须设置口令（compose 用 --requirepass 启动，留空会变成无密码服务）'
}
RequireValue 'S3_ENDPOINT' $Conf.S3_ENDPOINT
RequireValue 'S3_ACCESS_KEY' $Conf.S3_ACCESS_KEY
RequireValue 'S3_SECRET_KEY' $Conf.S3_SECRET_KEY
RequireValue 'S3_BUCKET_DATASETS' $Conf.S3_BUCKET_DATASETS
if ($Conf.S3_ENDPOINT -notmatch '^https?://') {
  throw "S3_ENDPOINT 必须以 http:// 或 https:// 开头，当前为：$($Conf.S3_ENDPOINT)"
}

# ---------- 前置检查 ----------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw '未找到 docker 命令，请先安装 Docker Desktop（并开启 WSL2 或 Hyper-V 后端）'
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw '无法连接 Docker 守护进程（Docker Desktop 未启动？）' }
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { throw '缺少 Docker Compose v2，请更新 Docker Desktop' }

if ($TrainMode -eq 'gpu') {
  $dockerInfo = & docker info 2>$null
  $hasNvidia = [bool]($dockerInfo -match 'nvidia')
  if (-not $hasNvidia) {
    Write-Host '! 警告：docker info 中未发现 nvidia 运行时。Windows 上需要 Docker Desktop + 支持' -ForegroundColor Yellow
    Write-Host '  CUDA 的显卡（WSL2 后端则需驱动已装到 WSL），否则 GPU worker 起不来。' -ForegroundColor Yellow
  }
  if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
    Write-Host '! 提示：未找到 nvidia-smi，无法在此校验驱动（部署时仍会尝试挂显卡）' -ForegroundColor Yellow
  }
}

# ---------- 生成 .env ----------
$envPath = Join-Path $PSScriptRoot '.env'
$backupNote = '（首次生成，无备份）'
if (Test-Path $envPath) {
  Copy-Item $envPath "$envPath.bak" -Force
  $backupNote = '（原 .env 已备份为 .env.bak）'
}

$boolText = @{ true = 'docker 自建'; false = '外部已有' }
$lines = @(
  "# 由 deploy.ps1 于 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') 生成；已被 .gitignore 忽略，勿提交"
  "# 训练模式：$TrainMode"
  "# MySQL：$($boolText[$Conf.BUILTIN_MYSQL]) ($($Conf.MYSQL_HOST):$($Conf.MYSQL_PORT)/$($Conf.MYSQL_DATABASE))"
  "# Redis：$($boolText[$Conf.BUILTIN_REDIS]) ($($Conf.REDIS_HOST):$($Conf.REDIS_PORT)，指标库 $($Conf.REDIS_METRICS_DB))"
  "# 对象存储：$($boolText[$Conf.BUILTIN_STORAGE]) ($($Conf.S3_ENDPOINT))"
  '# 改配置：.\deploy.ps1 -Action config（引导）或改 deploy.ps1 顶部【配置区】后 -Action env'
  "COMPOSE_PROFILES=$ComposeProfiles"
  ''
)
foreach ($key in $Conf.Keys) {
  if ($key -in @('TRAIN_WORKERS_GPU', 'DEFAULT_BATCH_SIZE_GPU')) { continue }
  $lines += "$key=$($Conf[$key])"
  if ($key -eq 'MYSQL_ADMIN_PASSWORD') { $lines += '' }
  if ($key -eq 'REDIS_DB_RESULT') { $lines += '' }
  if ($key -eq 'RUSTFS_BYPASS_DISK_CHECK') { $lines += '' }
  if ($key -eq 'JAVA_PORT') { $lines += '' }
  if ($key -eq 'DATASET_PREP_WORKERS') { $lines += '' }
  if ($key -eq 'CALLBACK_TOKEN') { $lines += '' }
}
# UTF-8 无 BOM：带 BOM 会让第一个变量名变成 <FEFF>COMPOSE_PROFILES，compose 插值失败
[System.IO.File]::WriteAllLines($envPath, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "→ 已写入 .env $backupNote"
Write-Host "  profiles: $ComposeProfiles"
Write-Host "  训练模式 $TrainMode：DataLoader 子进程 $($Conf.TRAIN_WORKERS)，默认 batch_size $($Conf.DEFAULT_BATCH_SIZE)"

Write-Host '→ 校验 docker-compose.yml 解析结果'
Invoke-Compose config --quiet
Write-Host '  [OK] 配置可解析'

if (-not $doDeploy) {
  Write-Host "→ 本次未构建、未启动容器。要部署请执行 .\deploy.ps1（引导）或 .\deploy.ps1 -Mode $TrainMode"
  exit 0
}

# ---------- 端口占用检查（只检查本次真正会映射到宿主机的端口）----------
$script:OwnPorts = @(Get-OwnPublishedPorts)
$portChecks = New-Object System.Collections.Generic.List[object]
$portChecks.Add([pscustomobject]@{ Port = $Conf.WEB_PORT; Name = 'web' })
$portChecks.Add([pscustomobject]@{ Port = $Conf.JAVA_PORT; Name = 'manager' })
$portChecks.Add([pscustomobject]@{ Port = $Conf.PY_PORT; Name = 'train-api' })
if (Test-True $Conf.BUILTIN_MYSQL) { $portChecks.Add([pscustomobject]@{ Port = $Conf.MYSQL_EXPOSE_PORT; Name = 'mysql' }) }
if (Test-True $Conf.BUILTIN_REDIS) { $portChecks.Add([pscustomobject]@{ Port = $Conf.REDIS_EXPOSE_PORT; Name = 'redis' }) }
if (Test-True $Conf.BUILTIN_STORAGE) {
  $portChecks.Add([pscustomobject]@{ Port = $Conf.RUSTFS_API_PORT; Name = 'rustfs' })
  $portChecks.Add([pscustomobject]@{ Port = $Conf.RUSTFS_CONSOLE_PORT; Name = 'rustfs-console' })
}
foreach ($p in $portChecks) {
  if (-not (Test-PortFree $p.Port $p.Name)) { exit 1 }
}

# ---------- 构建 ----------
# 显式列出服务，避免把另一种训练模式的镜像（CUDA 轮子约 2.5GB）也构建出来
$buildServices = @('manager', 'train-api', 'web')
if ($TrainMode -eq 'gpu') { $buildServices += 'train-worker-gpu' } else { $buildServices += 'train-worker-cpu' }
Write-Host "→ 构建镜像：$($buildServices -join ' ')（首次约 3~10 分钟，取决于网络）"
Invoke-Compose build @buildServices

# ---------- 分阶段启动 ----------
# 内置组件与初始化任务先完成，再起应用，避免应用连不上依赖而反复重启
$infra = New-Object System.Collections.Generic.List[string]
if (Test-True $Conf.BUILTIN_MYSQL) { $infra.Add('mysql') }
if (Test-True $Conf.BUILTIN_REDIS) { $infra.Add('redis') }
if (Test-True $Conf.BUILTIN_STORAGE) { $infra.Add('rustfs') }
if ($infra.Count -gt 0) {
  Write-Host "→ [1/3] 启动内置基础设施：$($infra -join ' ')（等待健康检查通过）"
  Invoke-Compose up -d --wait @infra
} else {
  Write-Host '→ [1/3] 全部为外部组件，无需启动内置容器'
}

Write-Host '→ [2/3] 初始化任务'
if ((Test-True $Conf.BUILTIN_MYSQL) -or (Test-True $Conf.AUTO_DDL)) {
  Invoke-Compose run --rm db-init
} else {
  Write-Host "  - 跳过建表（已有库 $($Conf.MYSQL_DATABASE)，表结构由你自己维护）"
}
if ((Test-True $Conf.BUILTIN_STORAGE) -or (Test-True $Conf.AUTO_BUCKET)) {
  Invoke-Compose run --rm bucket-init
} else {
  Write-Host '  - 跳过建桶（桶由你维护，或由应用首次访问时自动创建）'
}

Write-Host "→ [3/3] 启动应用：manager / train-api / train-worker-$TrainMode / web"
Invoke-Compose up -d --remove-orphans

Write-Host '→ 等待服务响应'
Wait-Url "http://127.0.0.1:$($Conf.PY_PORT)/api/health" '训练/推理服务 (FastAPI)' 'train-api' | Out-Null
Wait-Url "http://127.0.0.1:$($Conf.JAVA_PORT)/v3/api-docs" '管理后端 (Spring Boot)' 'manager' | Out-Null
Wait-Url "http://127.0.0.1:$($Conf.WEB_PORT)/healthz" '前端 (nginx)' 'web' | Out-Null

Invoke-Compose ps

# ---------- 结果汇总 ----------
$lanIp = try {
  (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -ne '127.0.0.1' } |
    Select-Object -First 1).IPAddress
} catch { '127.0.0.1' }
if (-not $lanIp) { $lanIp = '127.0.0.1' }

Write-Host @'

=============================================================================
'@
Write-Host "部署完成（训练模式：$TrainMode）"
Write-Host ''
Write-Host "  前端入口      http://${lanIp}:$($Conf.WEB_PORT)"
Write-Host "  管理后端      http://${lanIp}:$($Conf.JAVA_PORT)/swagger-ui.html"
Write-Host "  训练服务      http://${lanIp}:$($Conf.PY_PORT)/docs"
Write-Host "  数据库        $($boolText[$Conf.BUILTIN_MYSQL]) → $($Conf.MYSQL_HOST):$($Conf.MYSQL_PORT) / 库 $($Conf.MYSQL_DATABASE) / 账号 $($Conf.MYSQL_USER)"
Write-Host "  缓存          $($boolText[$Conf.BUILTIN_REDIS]) → $($Conf.REDIS_HOST):$($Conf.REDIS_PORT)（指标库 $($Conf.REDIS_METRICS_DB)）"
Write-Host "  对象存储      $($boolText[$Conf.BUILTIN_STORAGE]) → $($Conf.S3_ENDPOINT)"
if (Test-True $Conf.BUILTIN_STORAGE) {
  Write-Host "  存储控制台      http://${lanIp}:$($Conf.RUSTFS_CONSOLE_PORT)（账号 $($Conf.S3_ACCESS_KEY)）"
} else {
  Write-Host "  存储桶          $($Conf.S3_BUCKET_DATASETS) / $($Conf.S3_BUCKET_MODELS) / $($Conf.S3_BUCKET_TESTS)"
}
Write-Host @'

建议的下一步：
  1. 打开前端「基础模型」页 → 「同步官方清单」→ 下载需要的 .pt 权重
     （离线环境请把权重放到 model-data 卷的 base_models 目录，再点「刷新状态」）
  2. 新建数据集 → 导入 zip（YOLO 标注包 / COCO JSON）或上传图片
  3. 在线标注后务必点「导出标注」，再发起训练 —— 训练读的是存储里的 labels/*.txt
  4. 训练完成 → 模型版本「激活」→ 对外识别接口 POST /api/inference/detect

常用命令：
  .\deploy.ps1 -Action config     重新引导配置（只改 .env，不动容器）
  .\deploy.ps1 -Action status     查看容器状态
  .\deploy.ps1 -Action logs       跟踪日志
  .\deploy.ps1 -Action schema     只重跑建表脚本
  .\deploy.ps1 -Action down       停止并删除容器（数据卷保留）
  切换到另一种训练模式：.\deploy.ps1 -Mode gpu 或 -Mode cpu
=============================================================================
'@
