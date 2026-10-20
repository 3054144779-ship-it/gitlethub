import asyncio
import contextlib

from dependency_injector.wiring import inject
from fastapi import APIRouter
from fastapi.responses import PlainTextResponse
from starlette.responses import StreamingResponse

from schema.domain import ChatDTO
from service.impl.ai_service_impl import ChatServiceImpl

router = APIRouter(prefix="/api")


async def with_heartbeat(agen, interval: float = 15.0):
    """在等待下一个 token 的空隙里，每 interval 秒产出一次 SSE 心跳注释"""
    it = agen.__aiter__()
    pending = asyncio.ensure_future(it.__anext__())
    try:
        while True:
            done, _ = await asyncio.wait({pending}, timeout=interval)
            if not done:
                # 超时仍无新 token，发心跳保活（客户端会忽略以 ':' 开头的行）
                yield ": keepalive\n\n"
                continue
            try:
                token = pending.result()
            except StopAsyncIteration:
                break
            yield token
            pending = asyncio.ensure_future(it.__anext__())
    finally:
        # 1) 取消挂起的 __anext__ 任务，并【等它真正结束】，
        #    让事件循环有机会把 CancelledError 投进内层 agen、触发它的 finally，
        #    避免 "Task was destroyed but it is pending!"
        if not pending.done():
            pending.cancel()
            # shield：即使外层此刻正在被取消，也能等内层 task 跑完这一轮
            with contextlib.suppress(BaseException):
                await asyncio.shield(pending)
        # 2) 显式关闭内层异步生成器，确保其 finally（关游标 / 关 HTTP 流）执行
        with contextlib.suppress(BaseException):
            await it.aclose()


@router.post("/ai")
@inject
async def chat(chat_dto: ChatDTO):
    chat_service = ChatServiceImpl()

    async def event_generator():
        async for token in with_heartbeat(chat_service.chat(chat_dto)):
            if not token:
                continue
            if token.startswith(":"):
                # 心跳注释行，原样透传，不加 data: 前缀
                yield token
                continue
            for line in token.splitlines():
                yield f"data: {line}\n"
            yield "\n"
        yield "data: [DONE]\n\n"

    # 必须返回纯文本：默认 JSONResponse 会把字符串再编码一层，
    # 换行变成字面量 \n，Java 端拿到后 Markdown 无法解析
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        }
    )
