from fastapi import FastAPI

from router import model_router

app = FastAPI()
app.include_router(model_router.router)

