# 拾猫后端：Ubuntu 24.04 小范围测试

适用环境：2 核 CPU、8 GB 内存，现有网站 youthd.cn 已运行。API 使用独立子域名 `api.youthd.cn`，应用只监听 `127.0.0.1:8001`。这是一台服务器、一个 API worker 的部署方案，不是多实例集群。

目前没有登录云服务器或改变现有网站；以下步骤须在 FinalShell 的服务器终端执行。本地 Windows 操作另行标注。不要把服务器密码和 API Key 发到聊天、命令参数或 GitHub。

## 1. 先检查现有服务

```bash
sudo ss -lntp | grep -E ':80 |:443 |:5432 |:8001 '
systemctl is-active nginx apache2 caddy postgresql
systemd --version
```

若端口 80/443 已由宝塔、1Panel、Docker、Apache 或 Caddy 管理，沿用原来的反向代理添加子站点，不安装或替换另一套 Nginx，也不修改 youthd.cn 原站点。下文 Nginx 命令只适用于系统 Nginx。检查监听 8001 未被占用。先对原网站配置做快照/备份。

域名解析新增 A 记录 `api.youthd.cn` 指向服务器公网 IPv4；只有 IPv6 实际配置完成时才增加 AAAA。云安全组开放 443，以及签发证书需要的 80，SSH 保留现有可用配置；不要公开 8001、5432。不要直接 `ufw enable`，以免锁掉 SSH 或现有网站。

## 2. 安装后端与本机 PostgreSQL

```bash
sudo apt update
sudo apt install -y python3-venv python3-dev build-essential libpq-dev postgresql postgresql-client git
sudo adduser --system --group --home /var/lib/handcats handcats
sudo install -d -o root -g root -m 755 /opt/handcats
sudo git clone https://github.com/youth-cloud/handcats-.git /opt/handcats/source
sudo cp -a /opt/handcats/source/backend /opt/handcats/backend
sudo python3 -m venv /opt/handcats/venv
sudo chown -R handcats:handcats /opt/handcats/venv
sudo /opt/handcats/venv/bin/python -m pip install --upgrade pip
sudo /opt/handcats/venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
sudo -u handcats /opt/handcats/venv/bin/pip install -r /opt/handcats/backend/requirements-onnx.txt
sudo install -d -o handcats -g handcats -m 700 /var/lib/handcats/photos
sudo install -d -o root -g handcats -m 750 /opt/handcats/models
```

部署前先确认 GitHub 已含本轮改动；否则由 FinalShell 上传本地 `backend/` 源码到 `/opt/handcats/backend/`，不要上传本地 `.env`、storage、训练数据、缓存、venv。上面的 clone 不能让尚未提交的改动自动上云。

新建角色和空库（只执行一次；已经有同名角色或数据库时先核实，不删除重建）：

```bash
sudo -u postgres createuser --no-superuser --no-createdb --no-createrole handcats
sudo -u postgres createdb -O handcats handcats
sudo -u handcats psql -h /var/run/postgresql -d handcats -c 'select current_user;'
```

同机使用 Unix socket + `peer` 验证，服务进程以 Linux 用户 handcats 运行，数据库角色同名，**不需要数据库连接密码**。检查 Ubuntu 的 pg_hba.conf 的本地 socket 规则是 peer；不要改成 trust。不要把 PostgreSQL 的监听地址改成 0.0.0.0。应用角色不使用 postgres 超级用户。

用户登录密码由应用的 scrypt 加盐哈希保存；PostgreSQL 登录密码如果改用 TCP，采用 SCRAM-SHA-256；应用的连接凭据放加密配置或云 Secret Manager。远端数据库必须要求可信证书验证的 TLS，并限制网络来源。密码含 `@:/#%` 等字符时要正确 URL 编码。

## 3. 导出模型并单独上传

本地 Anaconda 的 handcats-yolo 环境中执行（当前权重位置可从本机配置确认，不修改配置）：

```text
yolo export model=F:/hand_cats/backend/models/cat_breed_12classes_20261017_best.pt format=onnx imgsz=640 batch=1 dynamic=False simplify=False half=False device=cpu
```

不要重复 NMS：先使用默认导出，不开启 `nms=True`，保持当前 YOLO26 推理方式。INT8 需要有代表性的校准数据和精度回归，本轮不直接量化。

用 FinalShell 文件面板分别上传 `.onnx` 和原 `.pt` 到临时目录，再移动为 `/opt/handcats/models/best.onnx`、`/opt/handcats/models/best.pt`。使用服务器命令 `sha256sum` 与本地 PowerShell `Get-FileHash` 比对文件。模型不进 Git，训练数据不上传。

```bash
sudo chown root:handcats /opt/handcats/models/best.*
sudo chmod 640 /opt/handcats/models/best.*
```

用 20～50 张具有代表性的测试照片，放到私有目录，测试 CPU 延迟、框和类别（这些图片不进 Git）：

```bash
OMP_NUM_THREADS=2 /opt/handcats/venv/bin/python /opt/handcats/backend/scripts/benchmark_model.py /opt/handcats/models/best.pt /opt/handcats/models/best.onnx --images /path/to/private-test-photos --threads 2
```

需要以能读取模型的账号执行。脚本输出每张图的类别、分数和框以及中位数/p95；不是完整精度验证。先比对相同图片输出，再用保留验证集做 Ultralytics `yolo val` 精度回归。ONNX 只有在实际更快且精度可接受时上线，否则配置指回 `.pt`。当前服务仍通过 Ultralytics 加载 ONNX，仍需安装 CPU PyTorch，并非纯 ONNX 轻量镜像。

## 4. 加密配置，交给 systemd 解密

Ubuntu 24.04 的 systemd 支持 LoadCredentialEncrypted。密钥配置先在内存文件系统编辑，加密后才写入持久磁盘。

```bash
sudo install -d -m 700 /etc/credstore.encrypted
sudo install -m 600 /opt/handcats/backend/deploy/production.env.example /dev/shm/handcats.env
sudo nano /dev/shm/handcats.env
```

填 DeepSeek Key，核实数据库、模型、照片路径；生产配置默认 `REGISTRATION_ENABLED=false`，用邀请测试账号。不要把 Key 复制进 `.env.example`。第一次建立凭据：

```bash
sudo systemd-creds encrypt --name=backend.env /dev/shm/handcats.env /etc/credstore.encrypted/handcats.env
sudo rm /dev/shm/handcats.env
sudo chmod 600 /etc/credstore.encrypted/handcats.env
sudo install -m 644 /opt/handcats/backend/deploy/handcats.service /etc/systemd/system/handcats.service
sudo systemctl daemon-reload
sudo systemctl enable --now handcats
sudo systemctl status handcats --no-pager
curl --fail http://127.0.0.1:8001/health/db
```

不要使用 `python run.py` 在生产启动，不使用 `--reload`，不增加 `--workers`。第一次 ONNX 启动会预热，缺模型/依赖会启动失败而不是悄悄下载通用权重。

加密文件不是抵御服务器 root 被攻陷的手段。无 TPM 云主机通常依赖 `/var/lib/systemd/credential.secret` 的主机密钥，必须妥善保护；该凭据不能假定可直接搬到另一台服务器解密。新机器应从独立安全保管的原始密钥重新签发。运行时服务必须能读到解密后的凭据；这与用户密码哈希是不同需求。

更新配置时在 `/dev/shm/` 创建新配置，加密到新文件后用 `sudo mv` 替换加密文件，删除临时明文，然后 `sudo systemctl restart handcats`。不要尝试把连接密码简单 Base64 后当成加密。

创建邀请测试员（需要交互输入密码，不会出现在命令历史里）：

```bash
sudo -u handcats /opt/handcats/venv/bin/python /opt/handcats/backend/scripts/create_test_user.py
```

脚本默认本机 peer 数据库，适合本方案；改远端库时先提供受控配置。不要用该脚本把生产登录密码写进命令参数。

## 5. HTTPS 与现有网站共存

以下仅适用于已经由系统 Nginx 监听 80/443 的机器。若目前是其他代理，请在原管理界面增加 `api.youthd.cn`，上游 `http://127.0.0.1:8001`，采用相同体积、超时、IP 转发规则。

```bash
sudo apt install -y certbot
sudo mkdir -p /var/www/handcats-acme
sudo cp /opt/handcats/backend/deploy/nginx-http.conf /etc/nginx/sites-available/handcats-http
sudo ln -s /etc/nginx/sites-available/handcats-http /etc/nginx/sites-enabled/handcats-http
sudo nginx -t
sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/handcats-acme -d api.youthd.cn
sudo cp /opt/handcats/backend/deploy/nginx-https.conf /etc/nginx/sites-available/handcats-https
sudo ln -s /etc/nginx/sites-available/handcats-https /etc/nginx/sites-enabled/handcats-https
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
curl --fail https://api.youthd.cn/health/db
```

证书续期后需要 reload Nginx：在 `/etc/letsencrypt/renewal-hooks/deploy/` 增加脚本执行 `nginx -t && systemctl reload nginx`，并检查 certbot.timer 已启用。如果已有证书管理系统，沿用其流程，不重复签发。

上传照片在鉴权后通过 API 返回；**不配置 Nginx alias 暴露照片目录**。Nginx 必须覆盖客户端伪造的 X-Forwarded-For，API 只信任回环代理。若前面还有 CDN，需要在代理按可信 CDN 网段恢复真实 IP，不能直接信任任意客户端头。

手机服务地址改成 `https://api.youthd.cn`，不带 `/api/v1`。证书要完整且受系统信任。核实 youthd.cn 原网页和 API 都正常，断开 Wi-Fi 用移动网络再测。

## 6. 多人同时上传和上线验收

- YOLO 单路 CPU 推理，最多 5 个任务（含执行中任务），排队最长 25 秒；满或超时返回 503 和 Retry-After，重试沿用同一个 X-Request-ID，不盲目立即自动重试。
- DeepSeek 与 YOLO 分开：最多 2 个复核同时执行、共 4 个等待/执行中任务。每用户每小时最多 20 次、整台服务每天最多 100 次复核尝试。内存配额在服务重启后清零，不能替代服务商余额/消费限额。
- 每用户每分钟最多 12 次上传尝试；Nginx 体积/连接/频率限制防止文件在进入应用之前无限堆积。照片最多 12 MiB，2500 万像素，服务保存时最长边 2048 并去 EXIF；图片中已烘焙的坐标/水印不会自动消除。
- 等待推理时释放数据库连接，网络断开也不提前释放仍在运行的 YOLO 线程锁。相同请求 ID 的并发提交只持久化一条相遇。
- 单 worker 不代表一个客户端；等待线程和网络 I/O 时其他客户端仍能登录、查看图鉴。不能保证“五人都立即完成”，需要实际服务器延迟与出口带宽测量。

测试账号上传同一测试图 5 次（独立记录），检查重试不会重复保存，最后自动删除新增测试照片：

```bash
/opt/handcats/venv/bin/python /opt/handcats/backend/scripts/check_concurrency.py https://api.youthd.cn /path/to/test-cat.jpg --count 5
```

观察 `systemctl status handcats`、`journalctl -u handcats -n 100 --no-pager`、`free -h`、`df -h`、`top`。记录成功数、503/429、p95、内存峰值，并让第二个测试员同时浏览首页。测试跨账号照片访问为 404、删除和收集撤销、多猫/复核/图鉴外类别、无效图片、过大照片、断网重试、服务重启。500、泄露照片、重复入库、进程 OOM 都是暂停开放测试的信号。

建议先邀请 3～5 人，注册默认关闭。确认每个测试员使用自己的账号。没有上传总量配额与自动清理用户照片，需监控磁盘并明确测试照片保留周期；不要自动删除用户照片腾空间。当前数据库迁移仍是启动时的兼容性增列，只适用于这一阶段；复杂迁移前改用版本化 Alembic，先备份。

## 7. 备份、回滚和运维

```bash
sudo bash /opt/handcats/backend/deploy/backup.sh
sudo install -m 644 /opt/handcats/backend/deploy/handcats-backup.service /etc/systemd/system/
sudo install -m 644 /opt/handcats/backend/deploy/handcats-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now handcats-backup.timer
sudo systemctl list-timers handcats-backup.timer
```

备份脚本会短暂停止 API，取得同一时间的数据库和照片备份，再启动 API；每天凌晨 04:00（服务器时区）执行，务必确认这个时间对你合适。数据库 dump 与照片压缩包**尚未加密**，目录为 root 私有；使用云备份加密或加密工具后传到其他机器/对象存储，配置保留策略。单机同盘备份不等于容灾。可用空间约 85 GiB，要为备份和日志预留，备份脚本不会擅自删除旧备份。

恢复应在独立空数据库和目录演练：`pg_restore` 数据库并恢复照片，核实数量、登录、照片访问与图鉴。新机器重新签发配置。先演练再对外测试。用户删除后历史备份里仍可能有旧照片，隐私说明需给出备份过期时间。

发布先保存当前代码版本、依赖清单与模型哈希；停止 API、备份、替换代码/配置后启动，健康检查和手机烟测通过才开放。不要在运行中覆盖模型。发生故障回滚代码和模型；数据库仅增列不删历史，涉及数据回滚先恢复到隔离环境确认。

需要监控的最少指标：API/DB 健康、证书到期、5xx 和队列拒绝数、推理延迟、CPU/内存、磁盘容量、DeepSeek 花费。Nginx 日志不记录 Authorization/请求体，日志和备份设保留周期。建议云磁盘加密；8 GB 内存机器可以酌情增加 1～2 GB swap 缓冲，但 swap 不能替代实际内存容量。

测试前向用户说明照片保存位置、删除方式，以及点击复核时图片会发给 DeepSeek。备案网页不自动覆盖 APP 发布所需事项，公开分发前核实 APP 备案、隐私政策和第三方模型/数据资源许可；Ultralytics 使用许可也需要核对。小规模邀请测试不等于可以忽略数据保护。
