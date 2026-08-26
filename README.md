# 上课通-基于数据驱动的课外辅导教学辅助管理系统

> 简称：辅导班管理系统 | 版本：v1.0.0 | 开发完成日期：2026-08-20

---

## 项目简介

上课通是一套面向课外辅导机构的全场景教学管理系统，融合**教学管理 + 家校协同 + 线上测评 + 数据决策**四大核心能力，解决机构在日常教学中多软件切换、数据孤岛、家校沟通不畅的痛点。

系统采用 B/S 架构，支持教师、家长、学生、超级管理员四种角色，覆盖从排课、上课、作业、考试、成绩、家校沟通、学情分析的完整教学闭环。

### 核心亮点

- **教学管理一体化**：排课、上课签到、课堂互动、作业、考试全流程线上化
- **家校协同实时化**：SSE 实时消息推送，教师家长双向沟通，通知秒级触达
- **数据驱动决策**：ECharts 教学数据看板，班级成绩趋势、出勤率、作业提交率可视化
- **权限分级管控**：四种角色权责分离，学生数据严格隔离，保障信息安全
- **操作审计追踪**：关键操作全量日志记录，支持按用户、类型、时间追溯

---

## 技术栈

### 后端
- **框架**：Spring Boot 3.2.0
- **JDK**：Java 17+
- **数据库**：MySQL 8.0
- **ORM**：Spring JDBC (JdbcTemplate)
- **安全**：JWT 鉴权 + BCrypt 密码加密
- **缓存**：Caffeine
- **构建**：Maven 3.9+

### 前端
- **架构**：原生 HTML + CSS + JavaScript（单文件应用）
- **图表**：ECharts 5.4.3
- **通信**：Fetch API + SSE (Server-Sent Events)
- **响应式**：CSS Media Queries

### 部署
- **打包**：可执行 JAR（内嵌 Tomcat）
- **平台**：Windows / Linux / macOS
- **容器化**：支持 Docker（见 deploy/ 目录）

---

## 功能模块

| 模块 | 功能说明 | 角色 |
|------|---------|------|
| **登录鉴权** | 账号密码登录、JWT Token、路由权限拦截、退出登录 | 全部 |
| **仪表盘** | 教学数据看板、成绩趋势图、出勤率、未读消息计数、SSE实时更新 | 全部 |
| **用户管理** | 教师/家长/学生账号增删改查、重置密码、角色权限 | admin |
| **学生管理** | 学生信息维护、班级绑定、学生账号绑定/解绑 | admin/teacher |
| **班级管理** | 班级创建、课程设置、学期关联、授课教师分配 | admin |
| **成绩管理** | 单条录入、Excel批量导入、成绩统计、分数段分析 | teacher |
| **作业管理** | 教师发布作业、学生提交、教师批改打分、成绩自动同步 | teacher/student |
| **考试模块** | 创建考试、组卷、启动考试、学生答题、客观题自动阅卷、主观题打分、成绩归档 | teacher/student |
| **上课模式** | 选择班级开始上课、课堂聊天、举手抢答、签到、分组任务、课堂积分、试题发布、文件互传 | teacher/student/parent |
| **课堂文件互传** | 教师下发课件、学生提交作业、权限隔离、下载鉴权 | teacher/student/parent |
| **家校互通** | 教师家长双向私信、快捷回复弹窗、班级通知发布、SSE实时推送、已读未读状态 | teacher/parent |
| **请假审批** | 家长提交请假、教师审批、批准后自动生成缺勤记录 | parent/teacher |
| **学情分析** | 学生个体学情报告、班级整体分析、成绩趋势、薄弱知识点 | teacher/parent |
| **知识图谱** | 学科知识点树、知识点关联、学生掌握程度可视化 | teacher/student |
| **错题本** | 考试/作业错题自动收集、掌握程度跟踪、按学科筛选 | student |
| **AI学情报告** | AI自动生成周报/月报/自定义学情分析报告 | teacher/parent |
| **财务管理** | 学生账户、缴费记录、欠费统计 | admin |
| **排课管理** | 按星期时间段排课、课程表查看 | admin/teacher |
| **教学评价** | 家长评价教师、评分统计 | parent |
| **操作审计日志** | 关键操作全量记录、按类型/时间/用户筛选查询 | admin |
| **个人中心** | 修改密码、个人信息查看 | 全部 |
| **忘记密码** | 管理员后台重置密码为初始密码123456 | admin |

---

## 测试账号

> 首次启动空数据库时，系统会自动初始化以下演示账号和测试数据。

| 角色 | 账号 | 密码 | 说明 |
|------|------|------|------|
| 超级管理员 | `admin` | `admin123` | 全部功能权限，含用户管理、操作日志、财务 |
| 教师 | `teacher` | `admin123` | 教学管理、成绩、作业、考试、家校消息 |
| 家长 | `parent` | `admin123` | 查看孩子数据、收发消息、请假、下载课件 |
| 学生 | `student01` | `admin123` | 查看本人成绩/作业/出勤、答题、提交作业 |
| 学生 | `student02` | `admin123` | 同上（绑定不同学生） |
| 学生 | `student03` | `admin123` | 同上 |
| 学生 | `student04` | `admin123` | 同上 |
| 学生 | `student05` | `admin123` | 同上 |

---

## 快速启动

### 环境要求

- JDK 17 或更高版本
- MySQL 8.0
- Maven 3.9+（开发环境需要，运行只需 JRE）

### 步骤一：配置数据库

1. 创建数据库：
```sql
CREATE DATABASE skt_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 修改数据库配置（如非默认配置）：
   - 编辑 `src/main/resources/application.yml`
   - 修改 `spring.datasource.url`、`username`、`password`

默认配置：
```yaml
url: jdbc:mysql://localhost:3306/skt_db
username: root
password: 123456
```

### 步骤二：编译打包

```bash
# 开发环境运行
mvn spring-boot:run

# 或打包后运行
mvn clean package -DskipTests
java -jar target/skt-server-1.0.0.jar
```

### 步骤三：访问系统

打开浏览器访问：
- **主系统**：http://localhost:8081/tutoring-management.html
- **学生端**：http://localhost:8081/student.html

首次启动会自动：
1. 执行 `schema.sql` 创建全部数据库表
2. 执行 `data.sql` 初始化基础数据（学期、消息模板）
3. `DataInitializer` 自动创建演示账号和测试数据

---

## 项目目录结构

```
上课通/
├── src/
│   ├── main/
│   │   ├── java/com/skt/
│   │   │   ├── controller/          # 控制器层（24个Controller）
│   │   │   ├── service/             # 业务逻辑层（23个Service）
│   │   │   ├── config/              # 配置类（全局异常、缓存、数据初始化）
│   │   │   └── security/            # 安全相关（JWT、权限校验）
│   │   └── resources/
│   │       ├── static/               # 前端静态资源
│   │       │   ├── tutoring-management.html  # 主系统（单文件应用）
│   │       │   └── student.html               # 学生端独立入口
│   │       ├── schema.sql            # 数据库表结构
│   │       ├── data.sql              # 初始化数据
│   │       ├── application.yml       # 主配置
│   │       ├── application-local.yml # 本地开发配置
│   │       └── application-prod.yml  # 生产环境配置
│   └── test/                          # 单元测试
├── doc-graduation-design/             # 毕设文档（见下方说明）
├── demo-resources/                     # 演示样例资源
├── deploy/                              # 部署配置（Docker、Zeabur等）
├── target/                              # 编译输出（Maven生成）
├── pom.xml                              # Maven项目配置
├── start-app.bat                        # Windows启动脚本
├── stop-app.bat                         # Windows停止脚本
└── README.md                            # 本文件
```

### doc-graduation-design 文档目录

```
doc-graduation-design/
├── 01_系统设计/          # 需求文档、功能说明、数据库设计文档
├── 02_软著材料/          # 软著说明书、源程序文档
├── 03_测试文档/          # 系统测试报告、测试用例
├── 04_部署运维/          # 部署指南、运维文档、快速启动
├── 05_答辩素材/          # 作品报告、演示PPT
├── 06_历史归档/          # 旧版本代码归档（不参与编译）
└── README.md             # 文档目录说明
```

---

## 部署说明

### 本地部署
见上方「快速启动」章节。

### 局域网访问
1. 确保防火墙开放 8081 端口
2. 其他设备通过 `http://<本机IP>:8081/tutoring-management.html` 访问
3. 查看本机IP：Windows 执行 `ipconfig`

### Docker 部署
```bash
cd deploy
docker-compose up -d
```

### Zeabur 云部署
详见 `doc-graduation-design/04_部署运维/Zeabur部署指南.md`

---

## 数据库说明

- **数据库名**：skt_db
- **表数量**：23张核心业务表
- **字符集**：utf8mb4（支持emoji和生僻字）
- **引擎**：InnoDB（支持事务和外键）

详细表结构说明见 `doc-graduation-design/01_系统设计/数据库设计文档.md`

---

## 常见问题

### Q: 启动后访问页面404？
A: 确认端口8081未被占用，访问路径为 `/tutoring-management.html`，不是根路径。

### Q: 数据库连接失败？
A: 检查MySQL是否启动，确认 `application.yml` 中的数据库地址、账号、密码正确。

### Q: 登录账号不存在？
A: 首次启动会自动创建演示账号，确认数据库中 `users` 表有数据。如为空，删除数据库后重新启动。

### Q: 忘记管理员密码？
A: 使用其他管理员账号登录后在用户管理中重置密码；或直接修改数据库 `users` 表的 `password_hash` 字段为 BCrypt 加密后的 `admin123`。

### Q: 如何修改端口？
A: 修改 `application.yml` 中的 `server.port`，或启动时指定 `--server.port=8080`。

---

## 许可证

本项目为毕业设计作品，仅供学习和研究使用。

---

## 联系方式

- 项目作者：罗晨喜
- 学校：陕西国际商贸学院
- 专业：信息管理与信息系统
- GitHub：https://github.com/chenxi110/tutoring---management-system.git

---

**更新日期**：2026-08-27
**版本**：v1.0.0
