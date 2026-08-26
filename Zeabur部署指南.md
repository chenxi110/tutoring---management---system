# 上课通 - Zeabur 免费部署指南

## 一、Zeabur 平台介绍

Zeabur 是一个支持一键部署的云平台，提供免费额度：
- 1个项目免费部署
- 内置 MySQL 数据库服务
- 自动分配域名（*.zeabur.app）
- 支持 Docker / Docker Compose / 多种语言框架
- 无需信用卡即可使用

**官网**：https://zeabur.com

---

## 二、部署前准备

### 2.1 注册 Zeabur 账号

1. 访问 https://zeabur.com
2. 使用 GitHub 账号登录（推荐）
3. 完成邮箱验证

### 2.2 确认 GitHub 仓库

代码已推送到：
```
https://github.com/chenxi110/tutoring---management---system
```

仓库包含：
- ✅ `Dockerfile` - 应用容器构建
- ✅ `docker-compose.yml` - MySQL + 应用编排
- ✅ `zeabur.yaml` - Zeabur 部署配置
- ✅ `schema.sql` - 数据库表结构（自动初始化）
- ✅ `data.sql` - 初始数据（默认账号）

---

## 三、部署步骤（图文）

### 步骤 1：创建新项目

1. 登录 Zeabur 控制台：https://dash.zeabur.com
2. 点击 **「Create Project」**（创建项目）
3. 选择区域：**「Singapore」**（新加坡，国内访问较快）或 **「US West」**
4. 点击 **「Create」**

### 步骤 2：添加 MySQL 数据库服务

1. 在项目页面点击 **「Add Service」**（添加服务）
2. 选择 **「Marketplace」**（市场）
3. 搜索并选择 **「MySQL」**
4. 选择版本：**8.0**
5. 点击 **「Add」**
6. 等待 MySQL 启动完成（状态变为 Running）

### 步骤 3：记录 MySQL 连接信息

MySQL 启动后，点击 MySQL 服务 → **「Connection」**（连接），记录以下信息：
- **Host**：如 `mysql.zeabur.internal`（内部连接地址）
- **Port**：`3306`
- **Username**：`root`
- **Password**：点击显示复制
- **Database**：需要手动创建，记为 `skt_db`

> 💡 也可以在 MySQL 服务的 **「Console」** 中执行：
> ```sql
> CREATE DATABASE skt_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
> ```

### 步骤 4：添加应用服务（从 GitHub 导入）

1. 在项目页面点击 **「Add Service」**
2. 选择 **「Git」**
3. 授权 GitHub 账号（首次需要）
4. 选择仓库：**`chenxi110/tutoring---management---system`**
5. 选择分支：**`main`**
6. 构建方式选择：**「Dockerfile」**（Zeabur会自动识别根目录的Dockerfile）
7. 点击 **「Add」**

### 步骤 5：配置环境变量

应用服务添加后，点击应用服务 → **「Variables」**（变量），添加以下环境变量：

| 变量名 | 值 | 说明 |
|--------|-----|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | 使用生产配置 |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://<MySQL-Host>:3306/skt_db?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_general_ci&allowPublicKeyRetrieval=true` | 数据库连接地址，替换`<MySQL-Host>`为步骤3的Host |
| `SPRING_DATASOURCE_USERNAME` | `root` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `<MySQL-Password>` | 数据库密码，替换为步骤3的Password |
| `JWT_SECRET` | `skt_secret_key_2024_springboot_v1` | JWT密钥（可自定义） |
| `TZ` | `Asia/Shanghai` | 时区 |
| `JAVA_OPTS` | `-Xms128m -Xmx384m -XX:+UseSerialGC` | JVM内存参数（适配免费额度） |

> ⚠️ **重要**：`SPRING_DATASOURCE_URL` 中的 Host 必须使用 Zeabur 提供的**内部连接地址**（如 `mysql.zeabur.internal`），不能使用 `localhost` 或 `127.0.0.1`。

### 步骤 6：配置端口

应用服务 → **「Networking」**（网络）：
- 确认端口为 **`8081`**
- Zeabur 会自动分配域名，如 `https://your-app.zeabur.app`

### 步骤 7：启动应用

1. 点击应用服务 → **「Deploy」**（部署）
2. 等待构建和启动完成（首次约3-5分钟）
3. 状态变为 **「Running」** 表示部署成功

### 步骤 8：验证部署

1. 访问分配的域名：`https://your-app.zeabur.app/tutoring-management.html`
2. 使用默认账号登录：
   - 教师账号：`admin` / `admin123`
   - 家长账号：需教师端先创建

---

## 四、Docker Compose 方式部署（备选）

如果不想分开配置，也可以使用 Docker Compose 一键部署：

### 4.1 部署方式

1. 创建项目
2. 点击 **「Add Service」** → **「Git」**
3. 选择仓库和分支
4. 构建方式选择：**「Docker Compose」**
5. Zeabur 会自动识别 `docker-compose.yml`，同时部署 MySQL 和应用

### 4.2 注意事项

- Docker Compose 方式下，MySQL 和应用在同一个项目中
- 应用通过服务名 `mysql:3306` 访问数据库
- 环境变量在 `docker-compose.yml` 中已配置好
- 首次启动时，应用会等待 MySQL 健康检查通过后再启动

---

## 五、常见问题

### Q1：应用启动失败，提示数据库连接失败

**原因**：数据库地址配置错误，或 MySQL 未完全启动。

**解决**：
1. 确认 `SPRING_DATASOURCE_URL` 使用的是 Zeabur 内部连接地址
2. 确认 MySQL 服务状态为 Running
3. 在应用的 **「Logs」** 中查看具体错误信息

### Q2：页面访问显示 502 Bad Gateway

**原因**：应用还在启动中，或端口配置错误。

**解决**：
1. 等待2-3分钟，Spring Boot 启动较慢
2. 确认应用端口为 8081
3. 查看应用日志确认启动完成

### Q3：中文显示乱码

**原因**：数据库字符集配置问题。

**解决**：
1. 确认 MySQL 创建时使用 `utf8mb4` 字符集
2. 确认 `SPRING_DATASOURCE_URL` 包含 `characterEncoding=UTF-8`
3. 重建数据库：`DROP DATABASE skt_db; CREATE DATABASE skt_db CHARACTER SET utf8mb4;`

### Q4：文件上传失败

**原因**：上传目录权限问题，或存储卷未配置。

**解决**：
1. 确认应用服务配置了存储卷（Volume）挂载到 `/app/upload/courseFile`
2. Zeabur 的存储卷在服务设置中配置

### Q5：如何更新代码

**方式一：自动部署**
- 在 Zeabur 应用设置中开启 **「Auto Deploy」**
- 每次推送到 GitHub main 分支，Zeabur 自动重新部署

**方式二：手动部署**
- 在应用服务页面点击 **「Redeploy」**（重新部署）

---

## 六、免费额度说明

| 资源 | 免费额度 | 上课通占用 |
|------|----------|------------|
| 项目数 | 1个 | 1个 |
| 内存 | 512MB | 应用384MB + MySQL 128MB |
| 流量 | 100GB/月 | 极低 |
| 数据库 | 内置MySQL | skt_db |
| 域名 | *.zeabur.app | 自动分配 |

> 💡 如果免费额度不够，可以升级到付费计划（$5/月起），或使用其他免费平台。

---

## 七、部署完成后的配置

### 7.1 修改默认密码

登录后立即修改默认管理员密码：
- 账号：`admin`
- 初始密码：`admin123`

### 7.2 配置 AI 功能（可选）

如需使用 AI 助手功能，在应用环境变量中添加：
- `AI_API_KEY`：你的 Agnes-AI API Key

### 7.3 绑定自定义域名（可选）

1. 应用服务 → **「Networking」** → **「Add Domain」**
2. 输入你的域名
3. 在域名DNS管理中添加 CNAME 记录，指向 Zeabur 提供的地址

---

## 八、部署检查清单

- [ ] Zeabur 账号已注册并登录
- [ ] GitHub 仓库代码已推送
- [ ] MySQL 服务已创建并启动
- [ ] 数据库 `skt_db` 已创建
- [ ] 应用服务已从 GitHub 导入
- [ ] 环境变量已正确配置（数据库地址、密码、JWT密钥）
- [ ] 应用端口为 8081
- [ ] 应用状态为 Running
- [ ] 访问域名能正常打开登录页
- [ ] 使用 admin/admin123 能正常登录
- [ ] 各功能模块正常使用

---

**部署过程中遇到问题，查看应用服务的「Logs」（日志）获取详细错误信息。**
