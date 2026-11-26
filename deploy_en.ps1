# =============================================================================
# YOLO Vision Model Management Platform -- one-click deployment
# (Windows, Docker Compose)  --  ENGLISH VERSION
#
#   .\deploy_en.ps1                                   # interactive wizard (recommended on first run)
#   .\deploy_en.ps1 -Mode cpu                         # no questions, every component from docker images
#   .\deploy_en.ps1 -Mode gpu
#   .\deploy_en.ps1 -Action config                    # run the wizard, write .env only, no build/start
#   .\deploy_en.ps1 -Action env                       # build .env from the CONFIG BLOCK below and validate
#   .\deploy_en.ps1 -Action status|logs|down|restart|schema
#
#   Non-interactive external components (good for scripted or CI deployments):
#     .\deploy_en.ps1 -Mode cpu -Action env -ExternalMysql 10.0.0.9 -MysqlDatabase glm `
#                  -MysqlUser glm -MysqlPassword 'pwd' -AutoDdl `
#                  -ExternalRedis 10.0.0.9 -ExternalStorage http://10.0.0.9:9000 -AutoBucket
#
#   When MySQL / Redis already run on this machine, move the published
#   host ports aside so the built-in containers do not collide:
#     .\deploy_en.ps1 -Mode cpu -MysqlExposePort 13306 -RedisExposePort 16379
#
# The Chinese version of this script is deploy.ps1 (identical logic, only the
# prompt text differs). Both write the same .env consumed by docker-compose.yml.
#
# The configuration ends up in .env next to this file (gitignored);
# docker-compose.yml only reads it. Re-running is safe: image layers are cached
# and both the DDL and the bucket creation are idempotent.
# If PowerShell refuses to load the script:
#   Set-ExecutionPolicy -Scope Process RemoteSigned
# =============================================================================

[CmdletBinding()]
param(
  [ValidateSet('cpu', 'gpu')][string]$Mode = '',
  [ValidateSet('deploy', 'config', 'env', 'status', 'logs', 'down', 'restart', 'schema')][string]$Action = 'deploy',

  # Wizard depth: full = confirm every item (default), quick = only the essentials, none = no questions
  [ValidateSet('full', 'quick', 'none')][string]$Guide = '',

  # Non-interactive overrides: giving a value means "use an existing service",
  # and the matching container is not started at all
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
  # Host ports published by the built-in components (use them when MySQL/Redis
  # already run on this machine and would collide with the defaults)
  [string]$MysqlExposePort = '',
  [string]$RedisExposePort = '',
  [string]$RustfsApiPort = '',
  [string]$RustfsConsolePort = '',
  [switch]$SkipDeploy
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# =============================================================================
# [ CONFIG BLOCK ] Everything below is a default value; the interactive wizard
# lets you confirm or override each of them on top of these values.
# To deploy without any questions, edit this block and run:
#   .\deploy_en.ps1 -Mode cpu / -Mode gpu / -Action env
# Note: passwords must not contain $, spaces or "space+#" -- compose breaks
# when parsing .env in those cases.
# =============================================================================
$Conf = [ordered]@{
  # ---- Database ----
  BUILTIN_MYSQL       = 'true'          # true = run MySQL from a docker image; false = use an existing instance
  MYSQL_HOST          = 'mysql'         # fixed to mysql when self-hosted (service name on the compose network)
  MYSQL_PORT          = '3306'          # port the applications connect to
  MYSQL_EXPOSE_PORT   = '3306'          # extra host port mapping when self-hosted
  MYSQL_DATABASE      = 'yolo_manager'  # schema.sql has this name hard-coded; the script substitutes it
  MYSQL_USER          = 'yolo'          # application account (created by the MySQL image when self-hosted)
  MYSQL_PASSWORD      = 'YoloApp#2026'
  MYSQL_ADMIN_USER    = 'root'          # admin account used to create tables
  MYSQL_ADMIN_PASSWORD = 'YoloRoot#2026'
  AUTO_DDL            = 'false'         # run schema.sql against an existing MySQL too

  # ---- Redis ----
  BUILTIN_REDIS       = 'true'
  REDIS_HOST          = 'redis'
  REDIS_PORT          = '6379'
  REDIS_EXPOSE_PORT   = '6379'
  REDIS_PASSWORD      = 'YoloRedis#2026'  # required when self-hosted: the container uses --requirepass
  REDIS_METRICS_DB    = '5'               # metrics DB; one variable feeds both Java and Python
  REDIS_DB_BROKER     = '1'
  REDIS_DB_RESULT     = '2'

  # ---- Object storage (S3 compatible) ----
  BUILTIN_STORAGE     = 'true'
  S3_ENDPOINT         = 'http://rustfs:9000'  # must be reachable from the application containers
  S3_ACCESS_KEY       = 'yoloRustfsAdmin'     # 5-20 characters, alphanumeric recommended
  S3_SECRET_KEY       = 'YoloRustfs#2026'     # 8-40 characters, alphanumeric recommended
  S3_REGION           = 'us-east-1'
  S3_USE_SSL          = 'false'
  S3_PATH_STYLE       = 'true'                # the Python side always uses path-style
  S3_BUCKET_DATASETS  = 'yolo-datasets'
  S3_BUCKET_MODELS    = 'yolo-models'
  S3_BUCKET_TESTS     = 'yolo-tests'
  AUTO_BUCKET         = 'false'               # create missing buckets on an existing object store too
  RUSTFS_API_PORT     = '9000'                # the next five entries only apply to a self-hosted RustFS
  RUSTFS_CONSOLE_PORT = '9001'
  RUSTFS_IMAGE        = 'rustfs/rustfs:latest'
  # On a single host all four volumes sit on one block device, which makes RustFS's
  # disk check refuse to start; set this back to false for a real multi-disk setup
  RUSTFS_BYPASS_DISK_CHECK = 'true'

  # ---- Application ports ----
  WEB_PORT            = '80'            # front end (this is what the browser opens)
  JAVA_PORT           = '8080'          # management backend
  PY_PORT             = '8000'          # training / inference service

  # ---- Training and inference parameters ----
  INSTALL_YOLO        = 'true'          # false = no torch/ultralytics in the image
  CUDA_TAG            = 'cu124'         # GPU wheel tag: cu118 / cu121 / cu124 / cu126
  GPU_COUNT           = 'all'           # how many GPUs to expose: all or an integer
  WORKER_CONCURRENCY  = '1'             # concurrent training jobs per container, >1 needs care
  TRAIN_WORKERS       = '4'             # the two values below are the per-mode defaults
  TRAIN_WORKERS_GPU   = '8'
  DEFAULT_BATCH_SIZE  = '8'
  DEFAULT_BATCH_SIZE_GPU = '16'
  TRAIN_SHM_SIZE      = '8g'            # too little shared memory breaks DataLoader
  PIP_INDEX_URL       = 'https://pypi.org/simple'  # use a mirror where pypi.org is slow
  DEFAULT_EPOCHS      = '100'
  DEFAULT_IMGSZ       = '640'
  DATASET_PREP_WORKERS = '16'

  # ---- Cross-host deployment and callbacks (fine to keep on a single host) ----
  TRAIN_SERVICE_URL   = 'http://train-api:8000'  # cross-host: http://<train-host-ip>:<PY_PORT>
  CALLBACK_BASE_URL   = 'http://manager:8080'    # cross-host: http://<manager-host-ip>:<JAVA_PORT>
  TRAIN_DELIVERY_MODE = 'http'                   # http = call /api/train/start; redis = push train:queue
  CALLBACK_TOKEN      = ''                       # empty = not verified; must match on both sides

  # ---- Images and JVM (swap them for an offline registry if needed) ----
  MYSQL_IMAGE         = 'mysql:8.0'
  REDIS_IMAGE         = 'redis:7-alpine'
  ALPINE_IMAGE        = 'alpine:3.20'
  MC_IMAGE            = 'minio/mc:latest'
  JAVA_OPTS           = '-Xms512m -Xmx1024m'
}
$TrainMode = 'cpu'

# =============================================================================
# [ END OF CONFIG BLOCK ]
# =============================================================================

# ---------- helper functions ----------
# Collect the arguments with the simple-function $args on purpose. With
# ValueFromRemainingArguments on an advanced function, PowerShell binds -d to the
# common parameter -Debug and drops it, so compose would get `up --remove-orphans`
# (foreground mode) and the script would hang forever.
function Invoke-Compose {
  $ComposeArgs = @($args)
  Write-Verbose "docker compose $($ComposeArgs -join ' ')"
  & docker compose @ComposeArgs
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose $($ComposeArgs -join ' ') failed (exit code $LASTEXITCODE)"
  }
}

function Test-True([string]$Value) { return ($Value -eq 'true') }

# interactivity switch: -Guide none, or input coming from a pipe, skips all questions
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
    $v = Ask "$Message (required)" ''
    if ($v -ne '') { return $v }
    Write-Host '    ! This value must not be empty'
  }
}

function AskPort {
  param([string]$Message, [string]$Default)
  while ($true) {
    $v = Ask $Message $Default
    if ($v -match '^\d+$' -and [int]$v -ge 1 -and [int]$v -le 65535) { return $v }
    Write-Host '    ! Please enter a number in 1~65535'
  }
}

# returns 'true' / 'false'
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
      Write-Host ("    ! Only y / n accepted, keeping the default ({0})" -f $(if ($Default -eq 'true') { 'yes' } else { 'no' }))
      return $Default
    }
  }
}

# Ports published by this project's own running containers: re-deploying must not treat
# them as a conflict
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
    Write-Host "  [skip] Port $Port is published by this project's own running container ($Name); reusing it" -ForegroundColor DarkGray
    return $true
  }
  $listen = @(Get-NetTCPConnection -State Listen -LocalPort ([int]$Port) -ErrorAction SilentlyContinue)
  if (-not $listen) {
    # netstat fallback: match LISTENING rows only. A bare ":$Port" pattern also hits
    # outgoing connections whose *remote* port is 80, which reads as a false conflict.
    $listen = @(netstat -ano 2>$null | Select-String -Pattern ":$Port\s+\S+\s+LISTENING")
  }
  if ($listen.Count -gt 0) {
    Write-Host "[FAIL] Port $Port is already in use ($Name) -- change the matching host port in the config" -ForegroundColor Red
    return $false
  }
  return $true
}

function Wait-Url {
  param([string]$Url, [string]$Name, [string]$Service, [int]$Tries = 60)
  for ($i = 1; $i -le $Tries; $i++) {
    try {
      Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
      Write-Host "  [OK] $Name is up"
      return $true
    } catch { Start-Sleep -Seconds 5 }
  }
  Write-Host "  [FAIL] $Name did not answer within $(5 * $Tries) seconds: $Url" -ForegroundColor Yellow
  Write-Host "    Troubleshoot: docker compose ps ; docker compose logs --tail=100 $Service"
  return $false
}

# ---------- actions and wizard depth ----------
if ($Action -in @('status', 'logs', 'down', 'restart', 'schema')) {
  if (-not (Test-Path "$PSScriptRoot\.env")) {
    throw 'There is no .env yet -- run .\deploy_en.ps1 (wizard) or .\deploy_en.ps1 -Mode cpu first'
  }
  switch ($Action) {
    'status'  { Invoke-Compose ps; exit 0 }
    'logs'    { Invoke-Compose logs -f --tail=200; exit 0 }
    'down'    { Invoke-Compose down; exit 0 }
    'restart' { Invoke-Compose restart; exit 0 }
    'schema'  {
      Write-Host '-> Running schema.sql (idempotent); the target database comes from MYSQL_HOST/MYSQL_DATABASE in .env'
      Write-Host '   Using an existing MySQL without the auto-DDL option? Run .\deploy_en.ps1 -Action config and enable it first'
      Invoke-Compose run --rm db-init
      exit 0
    }
  }
}

# without -Mode / -Guide we enter the wizard; giving them explicitly means no questions
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

# non-interactive detection: -Guide none, or no keyboard (pipe / scheduled task)
if ($guideLevel -eq 'none') {
  $script:Interactive = $false
} else {
  $noKeyboard = (-not [Environment]::UserInteractive) -or [Console]::IsInputRedirected
  if ($noKeyboard) {
    Write-Host '! Input is not an interactive terminal, falling back to the CONFIG BLOCK defaults (same as -Guide none)' -ForegroundColor Yellow
    $guideLevel = 'none'
  }
  $script:Interactive = ($guideLevel -ne 'none')
}

# ---------- interactive wizard ----------
if ($script:Interactive) {
  Write-Host @'

=============================================================================
 YOLO platform deployment wizard
 (Enter keeps the value in brackets; y/n where offered)
 Requires: Docker Desktop (or Docker Engine) + Compose v2; GPU training also
 needs a CUDA-capable adapter
 Every component defaults to a docker image -- answer n to point it at a
 service you already run.
=============================================================================
'@

  $lvl = Ask '  Wizard depth (1=full, includes external-component details; 2=quick, everything from docker images)' '1'
  if ($lvl -eq '2') { $guideLevel = 'quick' }

  # ---- 1. training mode ----
  Write-Host ''
  Write-Host '[1/7] Training acceleration'
  while ($true) {
    $TrainMode = Ask '  Train with CPU or GPU acceleration (cpu/gpu)' $TrainMode
    if ($TrainMode -in @('cpu', 'gpu')) { break }
    Write-Host '    ! Only cpu or gpu'
  }
  Write-Host '    Note: the GPU is attached to the training worker containers only;'
  Write-Host '          online inference keeps running on CPU'

  # ---- 2. MySQL ----
  Write-Host ''
  Write-Host '[2/7] MySQL (needs MySQL 8.0+ and the utf8mb4 charset)'
  $Conf.BUILTIN_MYSQL = AskBool '  Run MySQL from a docker image? (n = use your existing instance)' $Conf.BUILTIN_MYSQL
  if (Test-True $Conf.BUILTIN_MYSQL) {
    $Conf.MYSQL_DATABASE       = Ask '  Database name (created if missing)' $Conf.MYSQL_DATABASE
    $Conf.MYSQL_USER           = Ask '  Application account (created by the image)' $Conf.MYSQL_USER
    $Conf.MYSQL_PASSWORD       = Ask '  Application account password' $Conf.MYSQL_PASSWORD
    $Conf.MYSQL_ADMIN_PASSWORD = Ask '  root password (used to create database and tables)' $Conf.MYSQL_ADMIN_PASSWORD
    $Conf.MYSQL_EXPOSE_PORT    = AskPort '  Host port mapping (change it if another MySQL uses it)' $Conf.MYSQL_EXPOSE_PORT
  } else {
    $Conf.MYSQL_HOST     = AskRequired '  Existing MySQL address (must be reachable from the containers, not 127.0.0.1)'
    $Conf.MYSQL_PORT     = AskPort '  Port' '3306'
    $Conf.MYSQL_DATABASE = Ask '  Database name (must exist, or the account may create it)' $Conf.MYSQL_DATABASE
    $Conf.MYSQL_USER     = Ask '  Application account' $Conf.MYSQL_USER
    $Conf.MYSQL_PASSWORD = Ask '  Application account password' $Conf.MYSQL_PASSWORD
    $Conf.AUTO_DDL       = AskBool '  Let the script run schema.sql? (n if the tables already exist)' $Conf.AUTO_DDL
    if (Test-True $Conf.AUTO_DDL) {
      $Conf.MYSQL_ADMIN_USER     = Ask '  Account with DDL privileges' $Conf.MYSQL_ADMIN_USER
      $Conf.MYSQL_ADMIN_PASSWORD = Ask '  Its password' $Conf.MYSQL_ADMIN_PASSWORD
    }
    Write-Host '    ! If that instance authenticates with caching_sha2_password, the Python side'
    Write-Host '      (PyMySQL without cryptography) may fail to connect: switch the account to'
    Write-Host '      mysql_native_password, or add cryptography to the image'
  }

  # ---- 3. Redis ----
  Write-Host ''
  Write-Host '[3/7] Redis (Celery queue + training metric cache)'
  $Conf.BUILTIN_REDIS = AskBool '  Run Redis from a docker image?' $Conf.BUILTIN_REDIS
  if (Test-True $Conf.BUILTIN_REDIS) {
    $Conf.REDIS_PASSWORD     = Ask '  Password (required when self-hosted)' $Conf.REDIS_PASSWORD
    $Conf.REDIS_EXPOSE_PORT  = AskPort '  Host port mapping' $Conf.REDIS_EXPOSE_PORT
  } else {
    $Conf.REDIS_HOST     = AskRequired '  Existing Redis address'
    $Conf.REDIS_PORT     = AskPort '  Port' '6379'
    $Conf.REDIS_PASSWORD = Ask '  Password (press Enter for none)' ''
  }
  $Conf.REDIS_METRICS_DB = AskPort '  Database index holding training metrics (shared by Java and Python)' $Conf.REDIS_METRICS_DB
  if ($guideLevel -eq 'full') {
    $Conf.REDIS_DB_BROKER = AskPort '  Celery broker database index' $Conf.REDIS_DB_BROKER
    $Conf.REDIS_DB_RESULT = AskPort '  Celery result database index' $Conf.REDIS_DB_RESULT
  }

  # ---- 4. object storage ----
  Write-Host ''
  Write-Host '[4/7] Object storage (S3 compatible: datasets / models / tests buckets)'
  $Conf.BUILTIN_STORAGE = AskBool '  Run RustFS from a docker image? (n = use your existing MinIO/RustFS etc.)' $Conf.BUILTIN_STORAGE
  if (Test-True $Conf.BUILTIN_STORAGE) {
    $Conf.S3_ACCESS_KEY       = Ask '  access key (5-20 characters, alphanumeric recommended)' $Conf.S3_ACCESS_KEY
    $Conf.S3_SECRET_KEY       = Ask '  secret key (8-40 characters, alphanumeric recommended)' $Conf.S3_SECRET_KEY
    $Conf.RUSTFS_API_PORT     = AskPort '  S3 API host port mapping (change it if MinIO owns 9000)' $Conf.RUSTFS_API_PORT
    $Conf.RUSTFS_CONSOLE_PORT = AskPort '  Console host port mapping' $Conf.RUSTFS_CONSOLE_PORT
    if ($guideLevel -eq 'full') {
      $Conf.RUSTFS_IMAGE = Ask '  RustFS image (pin a version such as rustfs/rustfs:1.0.4 if needed)' $Conf.RUSTFS_IMAGE
    }
  } else {
    $Conf.S3_ENDPOINT    = AskRequired '  Endpoint of the existing service, with scheme (e.g. http://10.0.0.9:9000)'
    $Conf.S3_ACCESS_KEY  = AskRequired '  access key'
    $Conf.S3_SECRET_KEY  = AskRequired '  secret key'
    $Conf.S3_REGION      = Ask '  region' $Conf.S3_REGION
    $Conf.S3_USE_SSL     = AskBool '  Access it over HTTPS?' $Conf.S3_USE_SSL
    $Conf.AUTO_BUCKET    = AskBool '  Let the script create the missing buckets? (n if they exist)' $Conf.AUTO_BUCKET
    Write-Host '    ! The Python side always uses path-style addressing (bucket name in the path);'
    Write-Host '      MinIO and RustFS support it. A virtual-host-only cloud service needs a'
    Write-Host '      path-style compatible endpoint'
  }
  $Conf.S3_BUCKET_DATASETS = Ask '  Dataset bucket' $Conf.S3_BUCKET_DATASETS
  $Conf.S3_BUCKET_MODELS   = Ask '  Model bucket' $Conf.S3_BUCKET_MODELS
  $Conf.S3_BUCKET_TESTS    = Ask '  Test bucket' $Conf.S3_BUCKET_TESTS
  if ($guideLevel -eq 'full') {
    $Conf.S3_PATH_STYLE = AskBool '  Should the Java side use path-style addressing too?' $Conf.S3_PATH_STYLE
  }

  # ---- 5. application ports ----
  Write-Host ''
  Write-Host '[5/7] Application ports'
  $Conf.WEB_PORT  = AskPort '  Front-end port (browser opens http://<IP>:port)' $Conf.WEB_PORT
  $Conf.JAVA_PORT = AskPort '  Management backend port' $Conf.JAVA_PORT
  if ($guideLevel -eq 'full') { $Conf.PY_PORT = AskPort '  Training/inference service port' $Conf.PY_PORT }

  # ---- 6. training parameters ----
  Write-Host ''
  Write-Host '[6/7] Training parameters'
  $Conf.INSTALL_YOLO = AskBool '  Install torch/ultralytics into the image? (n = management flow only, much faster build)' $Conf.INSTALL_YOLO
  if ($TrainMode -eq 'gpu') {
    $Conf.CUDA_TAG          = Ask '  CUDA wheel tag (cu118/cu121/cu124/cu126)' $Conf.CUDA_TAG
    $Conf.GPU_COUNT         = Ask '  How many GPUs (all or a number)' $Conf.GPU_COUNT
    $Conf.TRAIN_WORKERS     = Ask '  DataLoader workers' $Conf.TRAIN_WORKERS_GPU
    $Conf.DEFAULT_BATCH_SIZE = Ask '  Default batch_size' $Conf.DEFAULT_BATCH_SIZE_GPU
  } else {
    $Conf.TRAIN_WORKERS     = Ask '  DataLoader workers' $Conf.TRAIN_WORKERS
    $Conf.DEFAULT_BATCH_SIZE = Ask '  Default batch_size' $Conf.DEFAULT_BATCH_SIZE
  }
  if ($guideLevel -eq 'full') {
    $Conf.WORKER_CONCURRENCY = Ask '  Concurrent training jobs per worker container' $Conf.WORKER_CONCURRENCY
    $Conf.TRAIN_SHM_SIZE     = Ask '  Shared memory size' $Conf.TRAIN_SHM_SIZE
    $Conf.PIP_INDEX_URL      = Ask '  PyPI index (use a mirror where pypi.org is slow)' $Conf.PIP_INDEX_URL
  }

  # ---- 7. cross-host and callbacks ----
  Write-Host ''
  Write-Host '[7/7] Cross-host deployment and callbacks (press Enter on a single host)'
  if ($guideLevel -eq 'full') {
    $Conf.TRAIN_SERVICE_URL = Ask '  Address the backend uses to reach the training service (http://<train-host>:port)' $Conf.TRAIN_SERVICE_URL
    $Conf.CALLBACK_BASE_URL = Ask '  Address the training service uses to call the backend (http://<manager-host>:port)' $Conf.CALLBACK_BASE_URL
    $Conf.CALLBACK_TOKEN    = Ask '  Callback token (empty = not verified; must match on both sides)' $Conf.CALLBACK_TOKEN
    $Conf.JAVA_OPTS         = Ask '  JVM options' $Conf.JAVA_OPTS
  } else {
    Write-Host '    Keeping the defaults: containers talk to each other (train-api:8000 / manager:8080),'
    Write-Host '    and the callback is not token-checked'
  }

  if ($Action -eq 'deploy') {
    if ((AskBool '  Only generate .env this time (no build, no containers started)?' 'false') -eq 'true') { $doDeploy = $false }
  }
} else {
  # ---------- non-interactive overrides ----------
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

# ---------- normalisation: self-hosted components use fixed container addresses ----------
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

# ---------- required values ----------
function RequireValue {
  param([string]$Name, [string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name must not be empty" }
}
RequireValue 'MYSQL_DATABASE' $Conf.MYSQL_DATABASE
RequireValue 'MYSQL_USER' $Conf.MYSQL_USER
RequireValue 'MYSQL_PASSWORD' $Conf.MYSQL_PASSWORD
if (-not (Test-True $Conf.BUILTIN_MYSQL)) { RequireValue 'MYSQL_HOST' $Conf.MYSQL_HOST }
if (-not (Test-True $Conf.BUILTIN_REDIS)) { RequireValue 'REDIS_HOST' $Conf.REDIS_HOST }
if ((Test-True $Conf.BUILTIN_REDIS) -and $Conf.REDIS_PASSWORD -eq '') {
  throw 'Self-hosted Redis needs a password (compose starts it with --requirepass; empty would expose it)'
}
RequireValue 'S3_ENDPOINT' $Conf.S3_ENDPOINT
RequireValue 'S3_ACCESS_KEY' $Conf.S3_ACCESS_KEY
RequireValue 'S3_SECRET_KEY' $Conf.S3_SECRET_KEY
RequireValue 'S3_BUCKET_DATASETS' $Conf.S3_BUCKET_DATASETS
if ($Conf.S3_ENDPOINT -notmatch '^https?://') {
  throw "S3_ENDPOINT must start with http:// or https://, current value: $($Conf.S3_ENDPOINT)"
}

# ---------- prerequisites ----------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'docker was not found -- install Docker Desktop first (with the WSL2 or Hyper-V backend enabled)'
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Cannot reach the Docker daemon (is Docker Desktop running?)' }
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is missing -- update Docker Desktop' }

if ($TrainMode -eq 'gpu') {
  $dockerInfo = & docker info 2>$null
  $hasNvidia = [bool]($dockerInfo -match 'nvidia')
  if (-not $hasNvidia) {
    Write-Host '! Warning: no nvidia runtime reported by docker info. On Windows this needs Docker' -ForegroundColor Yellow
    Write-Host '  Desktop with a CUDA-capable adapter (or a driver exposed into WSL2), otherwise the' -ForegroundColor Yellow
    Write-Host '  GPU worker will not start.' -ForegroundColor Yellow
  }
  if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
    Write-Host '! Note: nvidia-smi was not found, so the driver cannot be verified here (the GPU is' -ForegroundColor Yellow
    Write-Host '  still requested when the containers start)' -ForegroundColor Yellow
  }
}

# ---------- write .env ----------
$envPath = Join-Path $PSScriptRoot '.env'
$backupNote = '(first run, nothing backed up)'
if (Test-Path $envPath) {
  Copy-Item $envPath "$envPath.bak" -Force
  $backupNote = '(previous .env backed up to .env.bak)'
}

$boolText = @{ true = 'self-hosted (docker image)'; false = 'external (existing)' }
$lines = @(
  "# Generated by deploy_en.ps1 on $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'); gitignored, do not commit"
  "# Training mode: $TrainMode"
  "# MySQL: $($boolText[$Conf.BUILTIN_MYSQL]) ($($Conf.MYSQL_HOST):$($Conf.MYSQL_PORT)/$($Conf.MYSQL_DATABASE))"
  "# Redis: $($boolText[$Conf.BUILTIN_REDIS]) ($($Conf.REDIS_HOST):$($Conf.REDIS_PORT), metrics DB $($Conf.REDIS_METRICS_DB))"
  "# Object storage: $($boolText[$Conf.BUILTIN_STORAGE]) ($($Conf.S3_ENDPOINT))"
  '# To change this: .\deploy_en.ps1 -Action config (wizard), or edit the CONFIG BLOCK and run -Action env'
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
# UTF-8 without BOM: a BOM turns the first name into <FEFF>COMPOSE_PROFILES and compose interpolation fails
[System.IO.File]::WriteAllLines($envPath, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "-> wrote .env $backupNote"
Write-Host "  profiles: $ComposeProfiles"
Write-Host "  training mode ${TrainMode}: DataLoader workers $($Conf.TRAIN_WORKERS), default batch_size $($Conf.DEFAULT_BATCH_SIZE)"

Write-Host '-> Validating docker-compose.yml'
Invoke-Compose config --quiet
Write-Host '  [OK] The manifest parses cleanly'

if (-not $doDeploy) {
  Write-Host "-> Nothing was built or started. To deploy run .\deploy_en.ps1 (wizard) or .\deploy_en.ps1 -Mode $TrainMode"
  exit 0
}

# ---------- port availability (only ports this run really publishes) ----------
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

# ---------- build ----------
# services are listed explicitly so the other training mode's image
# (the CUDA wheels are about 2.5GB) is not built either
$buildServices = @('manager', 'train-api', 'web')
if ($TrainMode -eq 'gpu') { $buildServices += 'train-worker-gpu' } else { $buildServices += 'train-worker-cpu' }
Write-Host "-> Building images: $($buildServices -join ' ') (first run takes 3-10 minutes, network dependent)"
Invoke-Compose build @buildServices

# ---------- staged start ----------
# start the self-hosted infrastructure and the init jobs first, so that the
# applications never retry against a dependency that is not up yet
$infra = New-Object System.Collections.Generic.List[string]
if (Test-True $Conf.BUILTIN_MYSQL) { $infra.Add('mysql') }
if (Test-True $Conf.BUILTIN_REDIS) { $infra.Add('redis') }
if (Test-True $Conf.BUILTIN_STORAGE) { $infra.Add('rustfs') }
if ($infra.Count -gt 0) {
  Write-Host "-> [1/3] Starting self-hosted infrastructure: $($infra -join ' ') (waiting for health checks)"
  Invoke-Compose up -d --wait @infra
} else {
  Write-Host '-> [1/3] Everything is external, no infrastructure containers to start'
}

Write-Host '-> [2/3] Initialisation jobs'
if ((Test-True $Conf.BUILTIN_MYSQL) -or (Test-True $Conf.AUTO_DDL)) {
  Invoke-Compose run --rm db-init
} else {
  Write-Host "  - Skipping the DDL (database $($Conf.MYSQL_DATABASE) is maintained by you)"
}
if ((Test-True $Conf.BUILTIN_STORAGE) -or (Test-True $Conf.AUTO_BUCKET)) {
  Invoke-Compose run --rm bucket-init
} else {
  Write-Host '  - Skipping bucket creation (buckets are maintained by you, or created on first use)'
}

Write-Host "-> [3/3] Starting the applications: manager / train-api / train-worker-$TrainMode / web"
Invoke-Compose up -d --remove-orphans

Write-Host '-> Waiting for the services to answer'
Wait-Url "http://127.0.0.1:$($Conf.PY_PORT)/api/health" 'training/inference service (FastAPI)' 'train-api' | Out-Null
Wait-Url "http://127.0.0.1:$($Conf.JAVA_PORT)/v3/api-docs" 'management backend (Spring Boot)' 'manager' | Out-Null
Wait-Url "http://127.0.0.1:$($Conf.WEB_PORT)/healthz" 'front end (nginx)' 'web' | Out-Null

Invoke-Compose ps

# ---------- summary ----------
$lanIp = try {
  (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -ne '127.0.0.1' } |
    Select-Object -First 1).IPAddress
} catch { '127.0.0.1' }
if (-not $lanIp) { $lanIp = '127.0.0.1' }

Write-Host @'

=============================================================================
'@
Write-Host "Deployment finished (training mode: $TrainMode)"
Write-Host ''
Write-Host "  Front end        http://${lanIp}:$($Conf.WEB_PORT)"
Write-Host "  Admin API        http://${lanIp}:$($Conf.JAVA_PORT)/swagger-ui.html"
Write-Host "  Train service    http://${lanIp}:$($Conf.PY_PORT)/docs"
Write-Host "  Database         $($boolText[$Conf.BUILTIN_MYSQL]) -> $($Conf.MYSQL_HOST):$($Conf.MYSQL_PORT) / db $($Conf.MYSQL_DATABASE) / user $($Conf.MYSQL_USER)"
Write-Host "  Cache            $($boolText[$Conf.BUILTIN_REDIS]) -> $($Conf.REDIS_HOST):$($Conf.REDIS_PORT) (metrics DB $($Conf.REDIS_METRICS_DB))"
Write-Host "  Object storage   $($boolText[$Conf.BUILTIN_STORAGE]) -> $($Conf.S3_ENDPOINT)"
if (Test-True $Conf.BUILTIN_STORAGE) {
  Write-Host "  Storage console    http://${lanIp}:$($Conf.RUSTFS_CONSOLE_PORT) (user $($Conf.S3_ACCESS_KEY))"
} else {
  Write-Host "  Buckets            $($Conf.S3_BUCKET_DATASETS) / $($Conf.S3_BUCKET_MODELS) / $($Conf.S3_BUCKET_TESTS)"
}
Write-Host @'

Suggested next steps:
  1. Open "Base models" in the front end -> "Sync official catalogue" -> download
     the .pt weights you need. Offline: put them into base_models inside the
     model-data volume and click "Refresh status" instead.
  2. Create a dataset -> import a zip (YOLO annotation package / COCO JSON) or
     upload images.
  3. After annotating online always click "Export annotations" before starting
     training -- training reads labels/*.txt from object storage.
  4. When training finishes -> "Activate" a model version -> call
     POST /api/inference/detect.

Frequently used commands:
  .\deploy_en.ps1 -Action config    re-run the wizard (rewrites .env only)
  .\deploy_en.ps1 -Action status    container status
  .\deploy_en.ps1 -Action logs      follow logs
  .\deploy_en.ps1 -Action schema    re-run the DDL only
  .\deploy_en.ps1 -Action down      stop and remove containers (volumes kept)
  Switch training mode: .\deploy_en.ps1 -Mode gpu or -Mode cpu
  Chinese version of this script: .\deploy.ps1
=============================================================================
'@
