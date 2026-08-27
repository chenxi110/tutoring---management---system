# Zeabur 部署指南

## 项目信息
- 项目名称：上课通 - 基于数据驱动的课外辅导教学辅助管理系统
- 技术栈：Spring Boot 3.x + Java 17 + MySQL 8.0
- 本地端口：8081

## 部署前准备

### 1. 确保代码已推送到 GitHub
```bash
git add .
git commit -m "添加Zeabur部署配置：Dockerfile + 环境变量支持"
git push origin main
```

### 2. 注册 Zeabur 账号
- 访问 https://zeabur.com
- 使用 GitHub 账号登录

## 部署步骤

### 第一步：创建项目
1. 登录 Zeabur 控制台
2. 点击「Create Project」或「创建项目」
3. 选择区域（推荐新加坡/香港，国内访问更快）

### 第二步：添加 MySQL 服务
1. 在项目页面点击「Add Service」或「添加服务」
2. 选择「Marketplace」或「服务市场」
3. 搜索「MySQL」，选择 MySQL 8.0
4. 设置服务名称（如 `skt-mysql`），点击添加
5. 等待 MySQL 服务启动完成（约1-2分钟）

### 第三步：部署应用
1. 点击「Add Service」→「Git」
2. 授权 GitHub 账号，选择你的仓库 `tutoring---management---system`
3. 选择分支 `main`
4. Zeabur 会自动检测到 Dockerfile，使用 Docker 构建
5. 点击「Deploy」开始部署

### 第四步：配置环境变量（可选，Zeabur会自动注入）
Zeabur 的 MySQL 服务会自动注入以下环境变量：
- `MYSQL_HOST` - MySQL主机地址
- `MYSQL_PORT` - MySQL端口
- `MYSQL_DATABASE` - 数据库名
- `MYSQL_USERNAME` - 用户名
- `MYSQL_PASSWORD` - 密码

应用已配置为自动读取这些环境变量，无需手动配置。

如需自定义配置，可在服务设置的「Variables」中添加：
```
SPRING_PROFILES_ACTIVE=prod
AI_API_KEY=你的AI接口密钥（可选）
```

### 第五步：绑定域名
1. 部署完成后，在服务设置页面找到「Domains」
2. 点击「Add Domain」
3. 可以使用 Zeabur 提供的免费域名（如 `skt.zeabur.app`）
4. 或绑定自己的域名（需要配置DNS CNAME记录）

### 第六步：访问应用
- 访问 `https://你的域名/tutoring-management.html`
- 默认管理员账号：admin / admin123（首次登录后请修改密码）

## 常见问题

### Q: 部署失败，提示数据库连接失败
A: 确保 MySQL 服务已启动完成，并且应用服务在 MySQL 之后启动。可以在应用服务的「Variables」中手动确认数据库连接信息。

### Q: 应用启动后502错误
A: Spring Boot 启动需要10-30秒，请耐心等待。可以在「Logs」中查看启动日志。

### Q: 上传文件后重启丢失
A: Zeabur 容器文件系统是临时的，重启会丢失。如需持久化存储，建议在服务设置中添加「Volume」，挂载到 `/app/upload` 目录。

### Q: 国内访问慢
A: 部署时选择新加坡或香港区域，或绑定国内已备案域名并配置CDN。

## 本地 Docker 测试（可选）
```bash
# 构建镜像
docker build -t skt-app .

# 运行（需要本地MySQL）
docker run -p 8081:8081 -e MYSQL_HOST=host.docker.internal -e MYSQL_PORT=3306 -e MYSQL_DATABASE=skt_db -e MYSQL_USERNAME=root -e MYSQL_PASSWORD=123456 skt-app
```