# AGENTS.md

## Project Overview
上课通 (Tutoring Management System) - A teaching management system for tutoring classes with three runtime components.

## Architecture

| Component | Entry Point | Port | Database |
|-----------|-------------|------|----------|
| Node.js backend | `ai-service.js` | 3001 | SQLite (`data/app.db`) |
| Spring Boot backend | `src/main/java/com/skt/SktApplication.java` | 8080 | MySQL |
| Python CLI | `teaching_manager.py` | N/A | JSON files |
| Frontend | `public/tutoring-management.html` | - | - |

## Startup Commands

### Node.js (primary)
```bash
npm install        # first run only
npm start          # production
npm run dev        # development with nodemon
```
Access: http://localhost:3001

### Spring Boot (alternative backend)
```bash
start-springboot.bat    # Windows only
```
Requires: Java 17, Maven, MySQL running on localhost:3306

### Python CLI (standalone tool)
```bash
python teaching_manager.py
```

## Key Files

- `ai-service.js` - Express server, routes, JWT auth, API endpoints
- `auth.js` - JWT token generation, password hashing, role middleware
- `db.js` - SQLite schema, database initialization
- `apiService.js` - Frontend API client (browser-side)
- `dataSync.js` - Frontend data synchronization logic
- `.env` - Environment variables (copy from `.env.example`)

## Data Files
- `data/app.db` - SQLite database (primary data store)
- `data/ai_config.json` - AI service configuration
- `data/course_config.json` - Course configuration
- `data/knowledge.json` - Knowledge base for AI
- `data/msg_templates.json` - Message templates
- `data/chat_history.json` - Chat history
- `students.json` - Student lists (Python CLI)
- `teaching_data.json` - Teaching records (Python CLI)
- `config.json` - Price/semester config (Python CLI)

## Environment Variables
Copy `.env.example` to `.env`. Required:
- `JWT_SECRET` - Authentication secret (dev fallback exists, but set explicitly)
- `FREEGPT_API_KEY` - AI assistant API key
- `FREECHAT_API_KEY` - AI chat API key

Optional:
- `PORT` - Server port (default: 3001)
- `CORS_ORIGINS` - Allowed origins (comma-separated)

## Conventions
- JWT tokens stored in browser as `skt_token`
- User roles: `teacher`, `parent`
- Chinese UI - all user-facing text is in Chinese
- Date format: YYYY-MM-DD, Month format: YYYY-MM
- SQLite WAL mode enabled for concurrent reads

## Known Gotchas
- `better-sqlite3` requires native compilation - may fail on some systems
- Spring Boot backend requires MySQL - not optional
- Python CLI uses JSON files independently from Node.js SQLite database
- Frontend `apiService.js` and `dataSync.js` are browser-side scripts, not Node.js modules
