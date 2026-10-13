"""
获取 MQ 消息
"""
from logging import info
import asyncio
from aio_pika import connect_robust
from config.Settings import Settings


async def start_consume_mq_message(callback: callable):
    print("开始获取 MQ 消息")

    settings = Settings()
    MESSAGE_QUEUE_URL = settings.MESSAGE_QUEUE_URL
    PR_MERGED_QUEUE = settings.PR_MERGED_QUEUE

    print("MQ 消息队列地址:", MESSAGE_QUEUE_URL)
    print("PR 合并消息队列名称:", PR_MERGED_QUEUE)

    connection = await connect_robust(MESSAGE_QUEUE_URL)

    async with connection:
        # 获取通道，需要加 await，因为 channel 是异步的
        channel = await connection.channel()
        # 设置 prefetch_count，每次处理一条消息
        await channel.set_qos(prefetch_count=1)

        print("连接成功！正在监听队列：pr_merged_queue")

        # 声明队列
        queue = await channel.declare_queue(
            PR_MERGED_QUEUE,
            durable=True,
            arguments={
                "x-dead-letter-exchange": "",
                "x-dead-letter-routing-key": "pr_merged_dlq"
            })

        messages = []
        # 处理消息队列的信息
        async with queue.iterator() as iterator:
            async for message in iterator:
                body = message.body.decode()
                print("获取 MQ 消息:", body)
                messages.append(body)
                callback(body)
                await message.ack() # 确认消息
        return messages

if __name__ == '__main__':
    print(asyncio.run(start_consume_mq_message()))



