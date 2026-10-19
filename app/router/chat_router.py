from dependency_injector.wiring import inject
from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

from schema.domain import ChatDTO
from service.impl.ai_service_impl import ChatServiceImpl

router = APIRouter(prefix="/api")

@router.post("/ai", response_class=PlainTextResponse)
@inject
async def chat(chat_dto: ChatDTO):
    chat_service = ChatServiceImpl()
    result = await chat_service.chat(chat_dto)
    # 必须返回纯文本：默认 JSONResponse 会把字符串再编码一层，
    # 换行变成字面量 \n，Java 端拿到后 Markdown 无法解析
    return PlainTextResponse(result)
