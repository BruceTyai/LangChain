# LocalMind 本地知识库

基于 Spring Boot、LangChain4j、Chroma、MySQL 和 Ollama 的本地 RAG 知识库。

## 启动

1. 启动 MySQL 与 Chroma：`docker compose up -d`
2. 安装模型：`ollama pull deepseek-r1:7b` 与 `ollama pull bge-m3:latest`
3. 确保 Ollama 已运行，然后启动应用：`mvn spring-boot:run`
4. 浏览器访问 `http://localhost:8080`

默认连接参数已经写入 `application.yml`，都可以用同名环境变量覆盖。生产环境请通过 `MYSQL_PASSWORD` 注入密码，不要把密码提交到公共仓库。

## 功能

- PDF、Word、Markdown、TXT、HTML、CSV 文档解析与分块
- Ollama `bge-m3:latest` 本地向量化，Chroma V2 API 持久化
- `deepseek-r1:7b` 基于检索上下文回答，并展示命中文档与相关度
- MySQL 管理文档元数据与索引状态
