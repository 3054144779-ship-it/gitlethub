# 标准定义
D DATE "Date: \w\w\w \w\w\w \d+ \d\d:\d\d:\d\d \d\d\d\d [-+]\d\d\d\d"
# 状态日志头部RE。 捕获提交ID在其唯一组中。
D COMMIT_HEAD "commit ([a-f0-9]+)[ \t]*\n(?:Merge:\s+[0-9a-f]{7}\s+[0-9a-f]{7}[ ]  *\n)?${DATE}"
# 完整的日志记录。 捕捉入口。假设日志消息不包含
# "==="
D COMMIT_LOG "(===[ ]*\ncommit [a-f0-9]+[ ]*\n(?:Merge:\s+[0-9a-f]{7}\s+[0-9a-f]{7}[ ]*\n)?${DATE}[ ]*\n(?:.|\n)*?(?=\Z|\n===))"
# 任意的文本行（即使带有 ？s）
D ARBLINE "[^\n]*(?=\n|\Z)"
# 零行或更多任意的完整文本。
D ARBLINES "(?:(?:.|\n)*(?:\n|\Z)|\A|\Z)"
