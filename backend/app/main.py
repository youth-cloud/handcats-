from contextlib import asynccontextmanager
import asyncio
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError

from app.api.routes.detection import router as detection_router
from app.api.routes.health import router as health_router
from app.api.routes.detection import service as detection_service
from app.core.config import settings
from app.api.routes.accounts import router as accounts_router
from app.api.routes.collection import router as collection_router
from app.db.initialize import initialize_database
from app.db.session import engine


@asynccontextmanager
async def lifespan(app):
    await initialize_database()
    await asyncio.to_thread(detection_service.preload_model)
    yield
    await engine.dispose()


def create_application() -> FastAPI:
    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        debug=settings.debug,
        lifespan=lifespan,
    )
    app.include_router(health_router)
    app.include_router(detection_router, prefix=settings.api_prefix)
    app.include_router(accounts_router, prefix=settings.api_prefix)
    app.include_router(collection_router, prefix=settings.api_prefix)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=422, content={"detail": "填写内容不符合要求，请检查账号、密码长度或输入格式"})

    return app


app = create_application()
