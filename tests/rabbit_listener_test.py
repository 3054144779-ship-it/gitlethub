import asyncio
import aio_pika

RABBITMQ_URL = "amqp://gitlet:gitlet@192.168.12.168:5672/%2Fgitlet"

async def main():
    print("正在连接RabbitMQ")
    connection = await aio_pika.connect_robust(RABBITMQ_URL)

    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=1)

        print("连接成功！正在监听队列：pr_merged_queue")

        # 声明队列
        queue = await channel.declare_queue("pr_merged_queue", durable=True, arguments={
            "x-dead-letter-exchange": "",
            "x-dead-letter-routing-key": "pr_merged_dlq"
        })

        async with queue.iterator() as queue_iter:
            async for message in queue_iter:
                async with message.process():
                    body = message.body.decode()
                    print(f"收到消息: {body}")

if __name__ == '__main__':
    asyncio.run(main())
