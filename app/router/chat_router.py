from dependency_injector.wiring import inject
from fastapi import APIRouter

from schema.domain import ChatDTO
from service.impl.ai_service_impl import ChatServiceImpl

router = APIRouter(prefix="/api")

@router.post("/ai")
@inject
async def chat(chat_dto: ChatDTO):
    chat_service = ChatServiceImpl()
    result = await chat_service.chat(chat_dto)
    return result
