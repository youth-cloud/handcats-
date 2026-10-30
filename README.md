# 拾猫

拍下遇见的猫，留下照片和手记，慢慢集齐自己的猫咪图鉴。

拾猫是一个 Android 毕设项目，目前为 MVP 1.0。照片上传到个人服务器，由 YOLO26 检测猫咪并判断外观类别，用户核对后收进图鉴。识别结果用于外观参考，不作为血统鉴定。

## 功能

- 注册、登录和账号管理
- 拍照或从相册上传，保存识别结果与照片
- 单图多猫编号框、逐项确认收集
- 12 类猫咪图鉴，按类别回看历史相遇
- 相遇手记、收藏、收集撤销和记录删除

## 技术

Android 使用 Kotlin、Jetpack Compose 和 CameraX；后端使用 Python、FastAPI、SQLAlchemy 和 PostgreSQL，模型在服务端推理。

```text
android/    Android 客户端
backend/    API 与后端测试
.github/    持续集成
```

## 运行配置

安卓工程使用 JDK 21+、Android SDK 37 和 Gradle Wrapper。调试版支持局域网 HTTP，正式版使用 HTTPS；Android 17 访问本地服务器时会申请本地网络权限。

后端使用 Python 3.11+ 和 PostgreSQL。配置项见 `backend/.env.example`，本地配置保存在 `backend/.env`。`MODEL_WEIGHTS_PATH` 指向本机模型权重，`DATABASE_URL` 指向 PostgreSQL。服务启动时初始化表和图鉴类别。

可选的智能复核使用 DeepSeek 图像理解接口。将 `DEEPSEEK_API_KEY` 写入服务器端 `backend/.env`，并按账户可用模型设置 `DEEPSEEK_MODEL`（官方视觉接口文档中的模型名为 `deepseek-flash`）。密钥只在后端使用，客户端不会保存或上传密钥。

复核结果可按只勾选收进图鉴；图鉴外类别保留鉴定名称并提示尚未收录。相机支持双指缩放、倍率滑条与设备开放的镜头切换。

Ubuntu 云服务器的小范围生产部署、ONNX 与 PT 对比、HTTPS、systemd 加密配置、并发验收和备份步骤见 [`backend/deploy/README.md`](backend/deploy/README.md)。

```bash
cd backend
pip install -r requirements.txt
python run.py
```

API 默认监听 `8000`，健康检查为 `/health` 和 `/health/db`，接口文档为 `/docs`。手机端可在连接设置中配置服务地址。

## CI

GitHub Actions 检查 Android 调试构建、Lint、单元测试，以及临时 PostgreSQL 数据库上的后端测试。后端测试使用受控模型输出，不依赖训练权重。

仓库只包含应用源码、必要资源和测试源码。数据集、训练输出、模型权重、上传照片、本地配置及构建产物不随源码提交。
