# 上课通 - 项目目录说明

> **上课通 - 基于数据驱动的课外辅导教学辅助管理系统**
>
> 信息管理与信息系统专业毕业设计项目

---

## 一、项目概述

上课通是一款面向课外辅导机构的一站式教学管理平台，涵盖**教学管理、家校协同、线上测评、数据分析**四大核心能力，支持超级管理员、教师、家长、学生四种角色。

### 技术栈
| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2.0 + Java 17+ + MySQL 8.0 |
| 前端 | 原生HTML/CSS/JavaScript单页应用 + ECharts 5.4.3 |
| 鉴权 | JWT Token + 基于角色的权限控制(RBAC) |
| 实时通信 | SSE (Server-Sent Events) |
| 缓存 | Spring Cache + Caffeine |
| 构建 | Maven |

---

## 二、根目录结构

```
上课通/
├── src/                          # 【源码目录】Maven标准结构，业务代码全部在此
│   └── main/
│       ├── java/com/skt/         # Java源代码
│       │   ├── controller/        # 控制器层（API接口）
│       │   ├── service/           # 业务逻辑层
│       │   ├── security/          # 安全鉴权（JWT/权限控制）
│       │   ├── config/            # 配置类（缓存/异常处理等）
│       │   └── SktApplication.java # Spring Boot启动类
│       └── resources/
│           ├── application.yml     # 应用配置（数据库/端口/SSE）
│           └── static/             # 前端静态资源
│               ├── tutoring-management.html  # 主页面（单页应用，约650KB）
│               ├── apiService.js   # 前端API客户端
│               └── dataSync.js     # 前端数据同步逻辑
│
├── target/                       # 【编译产物】Maven构建输出，包含jar包
│   └── skt-server-1.0.0.jar      # 可执行jar包（约43MB）
│
├── doc-graduation-design/        # 【毕设文档归档】全部毕设相关文档
│   ├── README.md                  # 本文档（目录说明）
│   ├── 01_系统设计/               # 需求文档、设计文档、功能说明
│   ├── 02_软著材料/               # 软著操作说明书、源程序文档
│   ├── 03_测试文档/               # 系统测试报告、测试用例
│   ├── 04_部署运维/               # 部署指南、运维手册、启动说明
│   ├── 05_答辩素材/               # 作品报告、演示素材
│   └── 06_历史归档/               # 旧版代码/废弃文档（封存，不参与编译）
│
├── deploy/                        # 【部署配置】Docker/云平台部署文件
│   ├── Dockerfile                 # Docker镜像构建
│   ├── docker-compose.yml         # Docker Compose编排
│   ├── zeabur.yaml                # Zeabur部署配置
│   ├── render.yaml                # Render部署配置
│   ├── vercel.json                # Vercel部署配置
│   ├── install-linux.sh           # Linux安装脚本
│   ├── install-windows.bat        # Windows安装脚本
│   └── skt-server.service         # Systemd服务配置
│
├── upload/                        # 【运行时目录】用户上传文件（课堂文件互传）
│   └── courseFile/                # 课堂课件/作业存储
│
├── demo-resources/                # 【演示素材】演示用示例Excel/课件/图片
│
├── pom.xml                        # Maven项目配置（依赖/构建插件）
├── start-app.bat                  # Windows启动脚本
├── stop-app.bat                   # Windows停止脚本
├── README.md                      # 项目说明（根目录）
├── AGENTS.md                      # AI代理项目指导
└── .gitignore                     # Git忽略规则
```

---

## 三、doc-graduation-design 文档明细

### 01_系统设计/
| 文件 | 说明 |
|------|------|
| 毕业设计配套设计文档.md | 完整毕设设计文档（需求分析/系统设计/数据库设计/详细设计/系统测试/创新点，6大章节） |
| 功能说明文档.md | 系统功能模块详细说明 |

### 02_软著材料/
| 文件 | 说明 |
|------|------|
| 上课通-基于数据驱动的课外辅导教学辅助管理系统 V1.0 操作说明书.docx | 软件著作权操作说明书（约7MB） |
| 上课通-基于数据驱动的课外辅导教学辅助管理系统 V1.0 源程序.docx | 软件著作权源程序文档 |

### 03_测试文档/
| 文件 | 说明 |
|------|------|
| （待补充）系统测试报告.md | 功能测试/接口测试/性能测试结果 |

### 04_部署运维/
| 文件 | 说明 |
|------|------|
| 系统部署与运维文档.md | 完整部署运维手册（环境搭建/部署步骤/开机自启/运维手册/常见问题） |
| 部署指南.md | 快速部署指南 |
| 快速启动指南.md | 快速启动说明 |
| Zeabur部署指南.md | Zeabur云平台部署教程 |

### 05_答辩素材/
| 文件 | 说明 |
|------|------|
| 上课通作品报告.md | 项目作品报告（用于比赛/答辩） |

### 06_历史归档/
| 文件/目录 | 说明 |
|-----------|------|
| ai-service.js / auth.js / db.js / migrate.js | Node.js旧版后端代码（项目早期版本，已转向Spring Boot） |
| package.json / package-lock.json | Node.js项目配置 |
| data-nodejs/ | Node.js版本SQLite数据目录 |
| teaching_manager.py / students.json / teaching_data.json / config.json | Python CLI旧版工具 |
| start-springboot.bat / start.bat | 旧版启动脚本（已被start-app.bat替代） |
| .env / .env.example | Node.js版本环境变量配置 |
| settings.xml | Maven临时配置文件 |

> **注意**：06_历史归档中的文件不参与项目编译运行，仅作为历史版本封存保留。

---

## 四、系统功能模块

### 核心业务模块（30+功能页面）
| 模块 | 功能说明 |
|------|---------|
| 登录认证 | 多角色登录、JWT鉴权、密码加密、忘记密码/重置密码 |
| 仪表盘 | 数据统计、未读消息、教学数据看板(ECharts图表) |
| 学生班级管理 | 学生增删改查、班级维护、学生账号绑定 |
| 成绩管理 | 单条录入、Excel批量导入、成绩统计分析 |
| 作业管理 | 作业发布、学生提交、教师批改、成绩自动同步 |
| 家校互通 | 双向私信、快捷回复、SSE实时推送、未读红点 |
| 通知公告 | 班级通知发布、实时推送 |
| 上课模式 | 课堂聊天、举手抢答、签到、文件互传、课堂积分 |
| 考试管理 | 考试创建、分配班级、启动考试、自动阅卷、成绩归档 |
| 请假审批 | 家长申请、教师审批、缺勤自动同步 |
| 财务管理 | 缴费记录、学生账户、欠费统计、财务报表 |
| 学情分析 | 学生个体分析、班级分析、成绩趋势、改进建议 |
| 知识图谱 | 知识点体系、学生掌握情况、薄弱环节识别 |
| 错题本 | 错题收集、掌握程度跟踪、错题统计 |
| AI学情报告 | AI自动生成学情分析报告、历史报告管理 |
| 教学评价 | 家长/学生评价、评分统计、评价反馈 |
| 操作日志 | 关键操作记录、可追溯查询、按类型筛选 |
| 学生端 | 我的成绩、我的作业、我的出勤、我的积分 |

### 系统角色
| 角色 | 权限范围 |
|------|---------|
| 超级管理员(admin) | 全部功能 + 操作日志 + 用户管理 + 系统配置 |
| 教师(teacher) | 所带班级教学管理 + 成绩 + 作业 + 家校沟通 + 上课模式 + 考试管理 |
| 家长(parent) | 查看自家孩子数据 + 收发私信 + 上课模式参与 + 请假申请 |
| 学生(student) | 我的成绩 + 我的作业 + 我的出勤 + 我的积分 + 上课模式参与 |

---

## 五、快速启动

### 环境要求
- JDK 17+
- MySQL 8.0
- Maven 3.6+（编译时需要）

### 启动步骤
```bash
# 1. 编译打包
mvn package -DskipTests

# 2. 启动应用
java -jar target/skt-server-1.0.0.jar

# 或使用Windows启动脚本
start-app.bat
```

### 访问地址
- 本机：http://localhost:8081/tutoring-management.html
- 局域网：http://<本机IP>:8081/tutoring-management.html

### 默认账号
| 角色 | 账号 | 密码 |
|------|------|------|
| 超级管理员 | admin | admin123 |
| 学生 | student01~student05 | admin123 |

---

## 六、数据库

- 数据库名：skt_db
- 表数量：37张
- 字符集：utf8mb4
- 核心表：users, classes, students, grades, homework, exam, messages, operation_logs 等

详细表结构设计见 `01_系统设计/毕业设计配套设计文档.md`

---

## 七、版本信息

- 当前版本：v2.1
- 最后更新：2026-08-26
- Git仓库：https://github.com/chenxi110/tutoring---management-system

---

*本文档用于毕设答辩/项目审阅，说明项目目录结构与各文件用途。*
