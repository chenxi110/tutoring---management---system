const express = require('express');
const fs = require('fs');
const path = require('path');
const cors = require('cors');
const db = require('./db');
const { authMiddleware, requireRole, login, register, getParentChildren, bindParentToStudent } = require('./auth');

// 轻量 .env 加载
(function loadEnv() {
    const envPath = path.join(__dirname, '.env');
    if (!fs.existsSync(envPath)) return;
    const lines = fs.readFileSync(envPath, 'utf8').split('\n');
    for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) continue;
        const eqIdx = trimmed.indexOf('=');
        if (eqIdx === -1) continue;
        const key = trimmed.slice(0, eqIdx).trim();
        let val = trimmed.slice(eqIdx + 1).trim();
        // 去除首尾引号
        if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
            val = val.slice(1, -1);
        }
        if (!(key in process.env)) process.env[key] = val;
    }
})();

// 手机号数据清洗：trim + 全角转半角 + 去除所有空格和不可见字符
function sanitizePhone(raw) {
    if (!raw) return '';
    var s = String(raw);
    // 全角数字０-９ (U+FF10..U+FF19) → 半角 0-9
    s = s.replace(/[\uFF10-\uFF19]/g, function(ch) {
        return String.fromCharCode(ch.charCodeAt(0) - 0xFF10 + 0x30);
    });
    // 去除所有空格（含全角空格U+3000、半角空格、tab、换行等）
    s = s.replace(/[\s\u3000\u00A0\u200B\u200C\u200D\uFEFF]/g, '');
    return s.trim();
}

const app = express();
const PORT = process.env.PORT || 3001;

// CORS
const ALLOWED_ORIGINS = (process.env.CORS_ORIGINS || 'http://localhost:3001,http://127.0.0.1:3001,http://localhost:3000')
    .split(',').map(s => s.trim()).filter(Boolean);
app.use(cors({
    origin(origin, callback) {
        // 允许同源请求（origin 为 undefined，如手机浏览器直接访问IP）
        if (!origin) return callback(null, true);
        // 允许白名单来源
        if (ALLOWED_ORIGINS.includes(origin)) return callback(null, true);
        // 允许局域网IP访问（http://192.168.x.x:port, http://10.x.x.x:port, http://172.16-31.x.x:port）
        if (/^http:\/\/(192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|localhost|127\.0\.0\.1)(:\d+)?/.test(origin)) {
            return callback(null, true);
        }
        return callback(new Error('CORS 策略已阻止该来源: ' + origin));
    },
    methods: ['GET', 'POST', 'PUT', 'DELETE'],
    allowedHeaders: ['Content-Type', 'Authorization']
}));
app.use(express.json({ limit: '10mb' }));

// JSON 响应统一 UTF-8
app.use((req, res, next) => {
    const originalJson = res.json.bind(res);
    res.json = function(data) {
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        return originalJson(data);
    };
    next();
});

const DB_DIR = './data';
if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });

function loadData(file) {
    const p = path.join(DB_DIR, file);
    try {
        if (!fs.existsSync(p)) return [];
        return JSON.parse(fs.readFileSync(p, 'utf8'));
    } catch (err) {
        console.error(`[loadData] 读取 ${file} 失败:`, err.message);
        return [];
    }
}

function saveData(file, data) {
    try {
        fs.writeFileSync(path.join(DB_DIR, file), JSON.stringify(data, null, 2));
    } catch (err) {
        console.error(`[saveData] 写入 ${file} 失败:`, err.message);
        throw new Error('数据保存失败，请检查 data 目录权限');
    }
}

// 从环境变量读取免费提供商密钥（不再硬编码）
const FREEGPT_API_KEY = process.env.FREEGPT_API_KEY || '';
const FREECHAT_API_KEY = process.env.FREECHAT_API_KEY || '';

let knowledge = loadData('knowledge.json');
let aiConfig = loadData('ai_config.json');
let chatHistory = loadData('chat_history.json');

function getActiveAIConfig() {
    return aiConfig.find(c => c.active) || null;
}

async function callAI(prompt, config) {
    const provider = config.provider.toLowerCase();
    const headers = { 'Content-Type': 'application/json' };
    
    if (provider === 'openai') {
        headers['Authorization'] = `Bearer ${config.apiKey}`;
        const url = config.baseUrl || 'https://api.openai.com/v1/chat/completions';
        const body = {
            model: config.model || 'gpt-4o-mini',
            messages: [{ role: 'user', content: prompt }],
            temperature: 0.7
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.choices?.[0]?.message?.content || 'AI响应错误';
    }
    
    if (provider === 'google') {
        const url = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent';
        const body = { contents: [{ parts: [{ text: prompt }] }] };
        const res = await fetch(`${url}?key=${config.apiKey}`, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.candidates?.[0]?.content?.parts?.[0]?.text || 'AI响应错误';
    }
    
    if (provider === 'doubao') {
        const url = config.baseUrl || 'https://api.doubao.com/v1/chat/completions';
        headers['Authorization'] = `Bearer ${config.apiKey}`;
        const body = {
            model: config.model || 'doubao-pro',
            messages: [{ role: 'user', content: prompt }],
            temperature: 0.7
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.choices?.[0]?.message?.content || data.result || 'AI响应错误';
    }
    
    if (provider === 'baidu') {
        const url = config.baseUrl || 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions';
        const body = {
            model: config.model || 'completions_pro',
            messages: [{ role: 'user', content: prompt }]
        };
        const res = await fetch(`${url}?access_token=${config.apiKey}`, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.result || data.choices?.[0]?.message?.content || 'AI响应错误';
    }
    
    if (provider === 'deepseek') {
        headers['Authorization'] = `Bearer ${config.apiKey}`;
        const url = config.baseUrl || 'https://api.deepseek.com/v1/chat/completions';
        const body = {
            model: config.model || 'deepseek-chat',
            messages: [{ role: 'user', content: prompt }],
            temperature: 0.7
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.choices?.[0]?.message?.content || 'AI响应错误';
    }
    
    if (provider === 'huggingface') {
        headers['Authorization'] = `Bearer ${config.apiKey}`;
        const url = config.baseUrl || 'https://api-inference.huggingface.co/models/mistralai/Mistral-7B-Instruct-v0.3';
        const body = { inputs: prompt };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data[0]?.generated_text || data.generated_text || 'AI响应错误';
    }
    
    if (provider === 'ollama') {
        const url = config.baseUrl || 'http://localhost:11434/api/chat';
        const body = {
            model: config.model || 'qwen2:7b',
            messages: [{ role: 'user', content: prompt }],
            stream: false
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.message?.content || 'AI响应错误';
    }
    
    if (provider === 'freegpt') {
        if (!FREEGPT_API_KEY) return 'freegpt 未配置环境变量 FREEGPT_API_KEY';
        const url = 'https://api.chatanywhere.cn/v1/chat/completions';
        headers['Authorization'] = `Bearer ${FREEGPT_API_KEY}`;
        const body = {
            model: config.model || 'gpt-3.5-turbo',
            messages: [{ role: 'user', content: prompt }],
            temperature: 0.7
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.choices?.[0]?.message?.content || 'AI响应错误';
    }
    
    if (provider === 'freechat') {
        if (!FREECHAT_API_KEY) return 'freechat 未配置环境变量 FREECHAT_API_KEY';
        const url = 'https://api.deepseek.com/v1/chat/completions';
        headers['Authorization'] = `Bearer ${FREECHAT_API_KEY}`;
        const body = {
            model: 'deepseek-chat',
            messages: [{ role: 'user', content: prompt }],
            temperature: 0.7
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.choices?.[0]?.message?.content || 'AI响应错误';
    }
    
    if (provider === 'doubao-free') {
        if (!FREECHAT_API_KEY) return 'doubao-free 未配置环境变量 FREECHAT_API_KEY';
        const url = 'https://api.doubao.com/v1/chat/completions';
        headers['Authorization'] = `Bearer ${FREECHAT_API_KEY}`;
        const body = {
            model: 'doubao-pro',
            messages: [{ role: 'user', content: prompt }],
            temperature: 0.7
        };
        const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
        const data = await res.json();
        return data.choices?.[0]?.message?.content || data.result || 'AI响应错误';
    }
    
    return '不支持的AI提供商';
}

// 教学领域知识库 - 结构化存储常用知识
const teachingKnowledge = {
  greetings: {
    keywords: ['你好', 'hi', 'hello', '在吗', '您好'],
    responses: [
      '你好！我是您的智能助教助手 🤖\n\n我可以帮您：\n• 📝 生成试题和练习题\n• 📚 管理和查询知识库\n• 💡 解答教学相关问题\n• 📊 提供教学建议\n\n请问有什么可以帮助您的？'
    ]
  },
  math: {
    keywords: ['数学', '计算', '算式', '加减乘除', '几何', '代数'],
    handler: (prompt) => {
      if (prompt.includes('计算') || prompt.includes('算式')) {
        const num1 = Math.floor(Math.random() * 100) + 1;
        const num2 = Math.floor(Math.random() * 100) + 1;
        const ops = ['+', '-', '×', '÷'];
        const op = ops[Math.floor(Math.random() * (prompt.includes('除') ? 4 : 3))];
        let answer;
        if (op === '+') answer = num1 + num2;
        else if (op === '-') { if (num1 < num2) [num1, num2] = [num2, num1]; answer = num1 - num2; }
        else if (op === '×') { answer = num1 * num2; }
        else { const safeNum2 = Math.floor(Math.random() * 10) + 1; answer = (num1 / safeNum2).toFixed(2); return `📝 数学计算题：\n\n${num1} ÷ ${safeNum2} = ?\n\n✅ 答案：${answer}\n\n💡 解题步骤：将${num1}平均分成${safeNum2}份，每份是${answer}`; }
        return `📝 数学计算题：\n\n${num1} ${op} ${num2} = ?\n\n✅ 答案：${answer}\n\n💡 这是一道${op === '+' ? '加法' : op === '-' ? '减法' : '乘法'}运算题，适合练习基础计算能力。`;
      }
      if (prompt.includes('几何')) {
        return '📐 几何知识小贴士：\n\n• 三角形内角和 = 180°\n• 矩形面积 = 长 × 宽\n• 圆的周长 = 2πr\n• 圆的面积 = πr²\n• 勾股定理：a² + b² = c²\n\n💡 想要更多几何题目吗？试试"帮我出一道几何题"';
      }
      return '📚 数学学习建议：\n\n1. **基础练习**：每天坚持15-20分钟的计算题训练\n2. **错题本**：记录做错的题目，定期回顾\n3. **理解概念**：不仅要会算，还要理解原理\n4. **举一反三**：做一道题，学会一类题\n\n💡 需要生成数学练习题吗？告诉我具体主题（如：分数、方程、几何）';
    }
  },
  english: {
    keywords: ['英语', '单词', '词汇', 'english', 'vocabulary'],
    handler: () => {
      const words = [
        { en: 'apple', zh: '苹果', example: 'I eat an apple every day.' },
        { en: 'book', zh: '书本', example: 'This book is very interesting.' },
        { en: 'cat', zh: '猫', example: 'The cat is sleeping.' },
        { en: 'dog', zh: '狗', example: 'My dog loves to run.' },
        { en: 'student', zh: '学生', example: 'She is a good student.' },
        { en: 'teacher', zh: '老师', example: 'My teacher is very kind.' },
        { en: 'school', zh: '学校', example: 'I go to school by bus.' },
        { en: 'happy', zh: '快乐的', example: 'I am happy to meet you.' },
        { en: 'learn', zh: '学习', example: 'I learn English every day.' },
        { en: 'practice', zh: '练习', example: 'Practice makes perfect.' }
      ];
      const w = words[Math.floor(Math.random() * words.length)];
      return `📚 每日英语单词：\n\n**${w.en}** /${w.en.split('').join('')}/\n\n📖 中文意思：${w.zh}\n\n✏️ 例句：${w.example}\n\n💡 每天坚持学习10个单词，一年就能掌握3600个词汇！`;
    }
  },
  generate: {
    keywords: ['生成', '试题', '试卷', '出题', '练习题', 'quiz', 'test'],
    handler: (prompt) => {
      const topics = ['数学', '语文', '英语', '科学', '历史', '地理', '艺术'];
      const match = prompt.match(/(数学|语文|英语|科学|历史|地理|艺术)/);
      const topic = match ? match[0] : topics[Math.floor(Math.random() * topics.length)];
      const templates = {
        '数学': () => `📋 **${topic}试题示例**\n\n**一、选择题**\n1. 下列哪个数是质数？（ ）\n   A. 4  B. 9  C. 11  D. 15\n\n2. 一个长方形的长是8cm，宽是5cm，它的周长是？（ ）\n   A. 13cm  B. 26cm  C. 40cm  D. 21cm\n\n**二、填空题**\n3. 3/4 + 1/4 = ______\n4. 一个数的3倍是24，这个数是______\n\n**三、解答题**\n5. 小明有12个苹果，他想平均分给4个朋友，每人能得到几个？请写出计算过程。\n\n💡 答案提示：1.C  2.B  3.1  4.8  5.3个`,
        '语文': () => `📋 **${topic}试题示例**\n\n**一、字词练习**\n1. 请写出下列词语的近义词：\n   美丽→______  快乐→______  勇敢→______\n\n2. 选出下列词语中没有错别字的一项（ ）\n   A. 再接再厉  B. 迫不急待  C. 甘败下风  D. 一如继往\n\n**二、阅读理解**\n3. 阅读下面的短文，回答问题：\n   "春天来了，小草从土里钻出来，花儿也开了。"\n   这句话使用了什么修辞手法？\n\n**三、作文**\n4. 以"我的周末"为题，写一篇不少于200字的短文。\n\n💡 答案提示：1.漂亮/开心/勇敢  2.A  3.拟人`,
        '英语': () => `📋 **${topic}试题示例**\n\n**一、选择题**\n1. She ______ to school every day.\n   A. go  B. goes  C. going  D. went\n\n2. I ______ a student.\n   A. am  B. is  C. are  D. be\n\n**二、填空题**\n3. 用适当的词填空：He ______ (play) football on weekends.\n4. 翻译：我喜欢学习英语。______\n\n**三、句型转换**\n5. 将下列句子改为一般疑问句：She is my sister.\n   ______\n\n💡 答案提示：1.B  2.A  3.plays  4.I like learning English.  5.Is she your sister?`,
        '科学': () => `📋 **${topic}试题示例**\n\n**一、选择题**\n1. 下列哪个是哺乳动物？（ ）\n   A. 鲨鱼  B. 鲸鱼  C. 金鱼  D. 鳄鱼\n\n2. 植物进行光合作用需要（ ）\n   A. 月光  B. 星光  C. 阳光  D. 灯光\n\n**二、判断题**\n3. 地球是太阳系中最大的行星。（ ）\n4. 水在标准大气压下的沸点是100℃。（ ）\n\n**三、简答题**\n5. 简述水循环的过程。\n\n💡 答案提示：1.B  2.C  3.×  4.√  5.蒸发→凝结→降水→循环`,
        '历史': () => `📋 **${topic}试题示例**\n\n**一、选择题**\n1. 中华人民共和国成立于哪一年？（ ）\n   A. 1945年  B. 1949年  C. 1950年  D. 1978年\n\n2. 丝绸之路是在哪个朝代开通的？（ ）\n   A. 秦朝  B. 汉朝  C. 唐朝  D. 宋朝\n\n**二、填空题**\n3. ______年，中国共产党成立。\n4. 改革开放是______年开始的。\n\n**三、论述题**\n5. 简述中国古代的四大发明及其对世界的影响。\n\n💡 答案提示：1.B  2.B  3.1921  4.1978`
      };
      
      const generator = templates[topic];
      return generator ? generator() : templates['数学']();
    }
  },
  teaching: {
    keywords: ['教学', '课堂', '管理', '班级', '学生', '方法', '技巧'],
    responses: [
      '📚 **教学管理建议**\n\n**1. 课堂管理技巧：**\n• 建立明确的课堂规则\n• 使用多样化的教学方法\n• 适时给予学生正向反馈\n• 保持课堂节奏紧凑\n\n**2. 学生激励方法：**\n• 设立合理的奖励机制\n• 关注每个学生的进步\n• 创造良性竞争环境\n• 培养学生的学习兴趣\n\n**3. 家校沟通：**\n• 定期与家长沟通学生情况\n• 组织家长会和开放日\n• 建立家长微信群/QQ群\n• 共同关注学生成长\n\n💡 更多教学资源可以在"知识库"中查找'
    ]
  },
  programming: {
    keywords: ['python', '编程', '代码', '程序', '循环', '函数', '变量'],
    responses: [
      '💻 **编程学习小贴士**\n\n**Python 基础概念：**\n\n**1. 变量：** 存储数据的容器\n```python\nname = "张三"\nage = 15\nprint(f"{name}今年{age}岁")\n```\n\n**2. 循环：** 重复执行代码\n```python\nfor i in range(5):\n    print(f"第{i+1}次循环")\n```\n\n**3. 函数：** 可复用的代码块\n```python\ndef greet(name):\n    return f"你好，{name}！"\n\nprint(greet("同学"))\n```\n\n💡 想要学习更多编程知识吗？可以告诉我你想了解的具体内容'
    ]
  },
  study: {
    keywords: ['学习', '方法', '技巧', '复习', '记忆', '效率'],
    responses: [
      '📖 **高效学习方法**\n\n**1. 艾宾浩斯遗忘曲线应用：**\n• 学习后20分钟复习\n• 1小时后再次复习\n• 1天后复习\n• 1周后复习\n• 1个月后复习\n\n**2. 主动学习技巧：**\n• 用自己的话复述知识点\n• 制作思维导图\n• 教别人（费曼学习法）\n• 做练习题巩固\n\n**3. 时间管理：**\n• 使用番茄工作法（25分钟+5分钟休息）\n• 制定学习计划\n• 避免拖延症\n• 保证充足睡眠\n\n**4. 记忆技巧：**\n• 联想记忆法\n• 思维导图记忆\n• 分段记忆\n• 主动回忆\n\n💡 找到适合自己的学习方法最重要！'
    ]
  },
  course: {
    keywords: ['课程', '排课', '进度', '大纲', '计划'],
    responses: [
      '📅 **课程规划建议**\n\n**1. 学期课程大纲：**\n• 明确学期教学目标\n• 规划每周教学进度\n• 预留复习和考试时间\n• 考虑学生接受程度\n\n**2. 单次课程结构：**\n• 5分钟：复习上次内容\n• 10分钟：引入新课\n• 20分钟：新知识讲解\n• 10分钟：练习巩固\n• 5分钟：总结回顾\n\n**3. 灵活调整：**\n• 根据学生反应调整节奏\n• 重点难点多花时间\n• 适时插入趣味内容\n\n💡 使用"课表"功能可以更好地管理课程安排'
    ]
  }
};

// 意图识别函数
function detectIntent(prompt) {
  const lowerPrompt = prompt.toLowerCase();
  for (const [intent, config] of Object.entries(teachingKnowledge)) {
    if (config.keywords.some(kw => lowerPrompt.includes(kw.toLowerCase()))) {
      return intent;
    }
  }
  return 'general';
}

// 从知识库中检索相关内容（带相关性评分）
function searchKnowledgeBase(prompt, maxResults = 3) {
  if (!knowledge || knowledge.length === 0) return [];
  
  const lowerPrompt = prompt.toLowerCase();
  const promptWords = lowerPrompt.split(/[\s,，。？？、；;]+/).filter(w => w.length > 0);
  
  const scored = knowledge.map(item => {
    let score = 0;
    const titleLower = item.title.toLowerCase();
    const contentLower = item.content.toLowerCase();
    
    // 标题完全匹配
    if (titleLower === lowerPrompt) score += 100;
    // 标题包含完整查询
    if (titleLower.includes(lowerPrompt)) score += 50;
    // 内容包含完整查询
    if (contentLower.includes(lowerPrompt)) score += 30;
    
    // 关键词匹配
    promptWords.forEach(word => {
      if (titleLower.includes(word)) score += 10;
      if (contentLower.includes(word)) score += 5;
    });
    
    return { item, score };
  });
  
  return scored
    .filter(s => s.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, maxResults)
    .map(s => s.item);
}

function localAIResponse(prompt, useKnowledge, conversationHistory = []) {
    const lowerPrompt = prompt.toLowerCase();
    
    // 1. 意图识别
    const intent = detectIntent(prompt);
    
    // 2. 处理已知意图
    if (intent !== 'general') {
      const config = teachingKnowledge[intent];
      if (config.handler) {
        return config.handler(prompt);
      }
      if (config.responses) {
        return config.responses[0];
      }
    }
    
    // 3. 检查特定问题模式
    // 时间相关
    if (lowerPrompt.includes('时间') || lowerPrompt.includes('几点') || lowerPrompt.includes('日期')) {
      const now = new Date();
      return `🕐 当前时间：${now.toLocaleString('zh-CN')}\n\n📅 日期：${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日\n⏰ 时间：${now.getHours()}时${now.getMinutes()}分`;
    }
    
    if (lowerPrompt.includes('谢谢') || lowerPrompt.includes('感谢') || lowerPrompt.includes('thanks')) {
      return '不客气！😊 很高兴能帮助您。如果还有其他问题，随时可以问我！';
    }
    
    if (lowerPrompt.includes('再见') || lowerPrompt.includes('拜拜') || lowerPrompt.includes('bye')) {
      return '再见！👋 祝您教学顺利，学生进步！';
    }
    
    // 4. 知识库检索
    if (useKnowledge && knowledge.length > 0) {
      const matched = searchKnowledgeBase(prompt, 3);
      if (matched.length > 0) {
        let response = '📚 **根据知识库检索到的相关内容：**\n\n';
        matched.forEach((k, i) => {
          response += `${i + 1}. **${k.title}**\n`;
          response += `   ${k.content.substring(0, 200)}${k.content.length > 200 ? '...' : ''}\n\n`;
        });
        response += '💡 您可以在"知识库"页面查看完整内容或添加更多相关知识。';
        return response;
      }
    }
    
    // 5. 教学管理系统相关帮助
    const systemKeywords = ['功能', '怎么用', '如何', '帮助', '使用', '系统', '管理'];
    if (systemKeywords.some(kw => lowerPrompt.includes(kw))) {
      return `🎓 **上课通系统功能指引：**\n\n**📊 仪表盘：** 查看整体数据概览\n**📝 上课记录：** 记录和管理每节课\n**👥 学生名单：** 管理班级学生信息\n**📋 缺勤管理：** 追踪学生出勤情况\n**📈 月度统计：** 查看教学统计数据\n**🗓️ 课表：** 排课和查看课程表\n**📚 作业管理：** 发布和批改作业\n**📢 通知公告：** 发布班级通知\n**💰 财务台账：** 管理收支记录\n**🤖 AI助手：** 智能问答和出题\n\n💡 请告诉我您具体想了解哪个功能，我可以提供更详细的说明！`;
    }
    
    // 6. 兜底回复 - 提供具体的帮助建议
    const fallbackResponses = [
      '这个问题很有意思！让我尝试为您解答...\n\n💡 您可以尝试以下方式获取帮助：\n• 添加相关知识到"知识库"中\n• 查看"教学管理"模块的使用说明\n• 向我提问具体的教学相关问题',
      '我理解您的问题。作为一个本地AI助手，我擅长处理教学相关的问题。\n\n📚 我可以帮您：\n• 生成各类试题\n• 解答教学方法问题\n• 提供学习建议\n• 回答编程基础问题\n\n请尝试更具体地描述您的问题！',
      '抱歉，我暂时无法准确回答这个问题。\n\n🔍 您可以：\n1. 在"知识库"中添加相关知识\n2. 配置外部AI服务获取更强大的问答能力\n3. 尝试问我一些教学相关的问题\n\n常见问题示例：\n• "帮我出一道数学题"\n• "如何管理课堂纪律"\n• "Python循环怎么用"\n• "有什么好的学习方法"'
    ];
    
    // 根据对话历史提供更有针对性的回复
    if (conversationHistory.length > 0) {
      const lastMessages = conversationHistory.slice(-3);
      const contextHints = [];
      lastMessages.forEach(msg => {
        if (msg.role === 'user') {
          contextHints.push(msg.content);
        }
      });
      if (contextHints.length > 0) {
        return `根据我们之前的对话，我理解您可能在讨论相关话题。\n\n您之前提到："${contextHints[contextHints.length - 1]}"\n\n💡 建议您尝试：\n• 更具体地描述您的需求\n• 添加相关知识到知识库\n• 配置外部AI获取更强能力`;
      }
    }
    
    return fallbackResponses[Math.floor(Math.random() * fallbackResponses.length)];
}

app.get('/api/knowledge', (req, res) => {
    const { category, keyword, page = 1, limit = 20 } = req.query;
    let data = [...knowledge];
    
    if (category && category !== 'all') {
        data = data.filter(k => k.category === category);
    }
    
    if (keyword) {
        data = data.filter(k => k.title.includes(keyword) || k.content.includes(keyword));
    }
    
    data.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    
    const start = (parseInt(page) - 1) * parseInt(limit);
    const end = start + parseInt(limit);
    
    res.json({ data: data.slice(start, end), total: data.length });
});

app.post('/api/knowledge', (req, res) => {
    const { title, content, category, tags } = req.body;
    if (!title || !content) return res.status(400).json({ error: '标题和内容不能为空' });
    
    const item = {
        id: Date.now().toString(),
        title: validateString(title, 200), content: validateString(content, 10000),
        category: validateString(category, 50) || '未分类',
        tags: validateString(tags, 200),
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
    };
    
    knowledge.push(item);
    saveData('knowledge.json', knowledge);
    res.json({ success: true, id: item.id });
});

app.put('/api/knowledge/:id', (req, res) => {
    const idx = knowledge.findIndex(k => k.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: '知识不存在' });
    
    const { title, content, category, tags } = req.body;
    knowledge[idx] = {
        ...knowledge[idx], title, content,
        category: category || '未分类',
        tags: tags || '',
        updatedAt: new Date().toISOString()
    };
    
    saveData('knowledge.json', knowledge);
    res.json({ success: true });
});

app.delete('/api/knowledge/:id', (req, res) => {
    const idx = knowledge.findIndex(k => k.id === req.params.id);
    if (idx === -1) return res.status(404).json({ error: '知识不存在' });
    
    knowledge.splice(idx, 1);
    saveData('knowledge.json', knowledge);
    res.json({ success: true });
});

app.get('/api/knowledge/categories', (req, res) => {
    const cats = [...new Set(knowledge.map(k => k.category))].sort();
    res.json(cats);
});

app.post('/api/ai/chat', async (req, res) => {
    const { message, useKnowledge = false, sessionId } = req.body;
    if (!message || typeof message !== 'string') return res.status(400).json({ error: '消息不能为空' });
    const safeMessage = message.slice(0, 5000);
    const config = getActiveAIConfig();
    
    // 获取会话历史（最多10轮）
    let conversationHistory = [];
    if (sessionId) {
      conversationHistory = chatHistory
        .filter(h => h.sessionId === sessionId)
        .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp))
        .slice(-10)
        .map(h => ({ role: h.role, content: h.content }));
    }
    
    let prompt = safeMessage;
    let externalMessages = [{ role: 'user', content: safeMessage }];
    
    if (useKnowledge && knowledge.length > 0) {
        // 使用改进的知识库检索
        const matched = searchKnowledgeBase(safeMessage, 3);
        if (matched.length > 0) {
            const context = matched.map(k => `【${k.title}】\n${k.content.substring(0, 300)}`).join('\n\n');
            prompt = `根据以下知识库内容回答问题：\n\n${context}\n\n问题：${safeMessage}`;
        }
    }
    
    // 构建多轮对话消息（用于外部AI）
    if (conversationHistory.length > 0 && config) {
      externalMessages = [
        ...conversationHistory.filter(m => m.role !== 'assistant' || conversationHistory.indexOf(m) < conversationHistory.length - 2),
        ...conversationHistory.slice(-4),
        { role: 'user', content: safeMessage }
      ].slice(-8);
    }
    
    try {
        let response;
        
        if (config) {
            try {
                // 使用多轮对话调用外部AI
                const headers = { 'Content-Type': 'application/json' };
                const provider = config.provider.toLowerCase();
                
                let aiResponse = null;
                
                if (['openai', 'doubao', 'deepseek'].includes(provider)) {
                    headers['Authorization'] = `Bearer ${config.apiKey}`;
                    const url = config.baseUrl || getDefaultUrl(provider);
                    const body = {
                        model: config.model || getDefaultModel(provider),
                        messages: externalMessages,
                        temperature: 0.7
                    };
                    const res2 = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
                    const data = await res2.json();
                    aiResponse = data.choices?.[0]?.message?.content;
                } else if (provider === 'google') {
                    const url = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent';
                    const body = { contents: [{ parts: [{ text: externalMessages.map(m => `${m.role}: ${m.content}`).join('\n') }] }] };
                    const res2 = await fetch(`${url}?key=${config.apiKey}`, { method: 'POST', headers, body: JSON.stringify(body) });
                    const data = await res2.json();
                    aiResponse = data.candidates?.[0]?.content?.parts?.[0]?.text;
                } else if (provider === 'ollama') {
                    const url = config.baseUrl || 'http://localhost:11434/api/chat';
                    const body = {
                        model: config.model || 'qwen2:7b',
                        messages: externalMessages,
                        stream: false
                    };
                    const res2 = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
                    const data = await res2.json();
                    aiResponse = data.message?.content;
                } else {
                    // 其他提供商使用单一消息
                    aiResponse = await callAI(prompt, config);
                }
                
                if (aiResponse && aiResponse !== 'AI响应错误') {
                    response = aiResponse;
                } else {
                    throw new Error('外部AI服务响应错误');
                }
            } catch (externalError) {
                console.log('外部AI服务失败，回退到本地AI:', externalError.message);
                response = localAIResponse(message, useKnowledge, conversationHistory);
            }
        } else {
            response = localAIResponse(message, useKnowledge, conversationHistory);
        }
        
        if (sessionId) {
            chatHistory.push({
                id: Date.now().toString(), sessionId, role: 'user', content: safeMessage,
                timestamp: new Date().toISOString()
            });
            chatHistory.push({
                id: (Date.now() + 1).toString(), sessionId, role: 'assistant', content: response,
                timestamp: new Date().toISOString()
            });
            // 只保留最近50条记录
            const sessionHistory = chatHistory.filter(h => h.sessionId === sessionId);
            if (sessionHistory.length > 50) {
                const toRemove = sessionHistory.length - 50;
                let removed = 0;
                for (let i = 0; i < chatHistory.length && removed < toRemove; i++) {
                    if (chatHistory[i].sessionId === sessionId) {
                        chatHistory.splice(i, 1);
                        i--;
                        removed++;
                    }
                }
            }
            saveData('chat_history.json', chatHistory);
        }
        
        res.json({ success: true, response });
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// 获取默认URL
function getDefaultUrl(provider) {
  const urls = {
    'openai': 'https://api.openai.com/v1/chat/completions',
    'doubao': 'https://api.doubao.com/v1/chat/completions',
    'deepseek': 'https://api.deepseek.com/v1/chat/completions'
  };
  return urls[provider] || urls['openai'];
}

// 获取默认模型
function getDefaultModel(provider) {
  const models = {
    'openai': 'gpt-4o-mini',
    'doubao': 'doubao-pro',
    'deepseek': 'deepseek-chat'
  };
  return models[provider] || 'gpt-3.5-turbo';
}

app.post('/api/ai/generate-questions', async (req, res) => {
    const { topic, count = 5, types = ['single', 'truefalse', 'fill', 'essay'] } = req.body;
    const config = getActiveAIConfig();
    
    if (!topic) return res.status(400).json({ error: '请提供出题主题' });
    
    // 如果没有配置AI接口，使用本地出题
    if (!config) {
        const questions = generateLocalQuestions(topic, count, types);
        return res.json({ success: true, questions, local: true });
    }
    
    const typeNames = { single: '单选题', multiple: '多选题', truefalse: '判断题', fill: '填空题', essay: '问答题' };
    const typeList = types.map(t => typeNames[t] || t).join('、');
    
    const prompt = `请围绕"${topic}"主题生成${count}道${typeList}，返回JSON格式：
    {
        "questions": [
            {
                "type": "题型(single/multiple/truefalse/fill/essay)",
                "question": "题目内容",
                "options": ["选项A", "选项B", "选项C", "选项D"],
                "correctAnswer": "正确答案",
                "score": 分值
            }
        ]
    }`;
    
    try {
        const response = await callAI(prompt, config);
        const jsonMatch = response.match(/\{[\s\S]*\}/);
        if (jsonMatch) {
            const result = JSON.parse(jsonMatch[0]);
            res.json({ success: true, questions: result.questions || [] });
        } else {
            // AI返回格式错误，使用本地出题
            const questions = generateLocalQuestions(topic, count, types);
            res.json({ success: true, questions, local: true, warning: 'AI响应格式异常，已使用本地出题' });
        }
    } catch (error) {
        // AI调用失败，使用本地出题
        console.log('AI出题失败，使用本地出题:', error.message);
        const questions = generateLocalQuestions(topic, count, types);
        res.json({ success: true, questions, local: true, warning: 'AI服务不可用，已使用本地出题' });
    }
});

// 本地出题引擎
function generateLocalQuestions(topic, count, types) {
    const questions = [];
    const typeList = types.length > 0 ? types : ['single', 'truefalse', 'fill', 'essay'];
    
    // 题库数据库
    const questionBank = {
        '数学': {
            single: [
                { question: '下列哪个数是质数？', options: ['4', '9', '11', '15'], answer: '11' },
                { question: '一个长方形的长是8cm，宽是5cm，它的面积是？', options: ['13cm²', '26cm²', '40cm²', '21cm²'], answer: '40cm²' },
                { question: '3/4 + 1/4 = ?', options: ['0.5', '0.75', '1', '1.5'], answer: '1' },
                { question: '下列哪个是偶数？', options: ['7', '13', '22', '31'], answer: '22' },
                { question: '圆的半径是5cm，它的周长约是？', options: ['15.7cm', '31.4cm', '78.5cm', '10cm'], answer: '31.4cm' }
            ],
            truefalse: [
                { question: '所有的质数都是奇数。', answer: '错误' },
                { question: '三角形的内角和是180°。', answer: '正确' },
                { question: '0是正数。', answer: '错误' },
                { question: '平行四边形的对边相等。', answer: '正确' },
                { question: '一个数的平方一定大于这个数。', answer: '错误' }
            ],
            fill: [
                { question: '一个数的3倍是24，这个数是______。', answer: '8' },
                { question: '50%的小数形式是______。', answer: '0.5' },
                { question: '1小时=______分钟。', answer: '60' },
                { question: '正方形有______条对称轴。', answer: '4' },
                { question: '最小的自然数是______。', answer: '0' }
            ],
            essay: [
                { question: '小明有12个苹果，他想平均分给4个朋友，每人能得到几个？请写出计算过程。', answer: '12÷4=3，每人得到3个苹果' },
                { question: '一个三角形的底边是10cm，高是6cm，求它的面积。', answer: 'S=1/2×底×高=1/2×10×6=30cm²' },
                { question: '一件商品原价200元，打8折后的价格是多少？', answer: '200×0.8=160元' },
                { question: '甲乙两地相距240公里，一辆车以每小时60公里的速度行驶，需要多长时间到达？', answer: '240÷60=4小时' },
                { question: '一个水池有进水管和出水管，进水管每小时进水5吨，出水管每小时出水3吨，同时打开时每小时净进水多少吨？', answer: '5-3=2吨' }
            ]
        },
        '英语': {
            single: [
                { question: 'She ______ to school every day.', options: ['go', 'goes', 'going', 'went'], answer: 'goes' },
                { question: 'I ______ a student.', options: ['am', 'is', 'are', 'be'], answer: 'am' },
                { question: 'What ______ you doing?', options: ['am', 'is', 'are', 'be'], answer: 'are' },
                { question: 'The cat is sitting ______ the table.', options: ['on', 'in', 'at', 'to'], answer: 'on' },
                { question: 'She likes ______ music.', options: ['listen', 'listens', 'listening to', 'listened'], answer: 'listening to' }
            ],
            truefalse: [
                { question: '"apple"的意思是"苹果"。', answer: '正确' },
                { question: '"I am agree"是正确的英语表达。', answer: '错误' },
                { question: '英语中形容词放在名词前面。', answer: '正确' },
                { question: '"go"的过去式是"goed"。', answer: '错误' },
                { question: "How old are you? 的意思是\"你好吗？\"。", answer: '错误' }
            ],
            fill: [
                { question: 'He ______ (play) football on weekends.', answer: 'plays' },
                { question: '翻译：我喜欢学习英语。______', answer: 'I like learning English.' },
                { question: 'The book is ______ (interest).', answer: 'interesting' },
                { question: 'She ______ (go) to school by bus every day.', answer: 'goes' },
                { question: 'There ______ (be) some milk in the glass.', answer: 'is' }
            ],
            essay: [
                { question: '将下列句子改为一般疑问句：She is my sister.', answer: 'Is she your sister?' },
                { question: '用英语描述你的一天（不少于5句话）。', answer: '参考答案：I get up at 7am. Then I have breakfast. I go to school by bus. After school, I do my homework. I go to bed at 10pm.' },
                { question: '写出5个关于天气的英语单词。', answer: 'sunny, rainy, cloudy, windy, snowy' },
                { question: '用"be going to"造3个句子。', answer: '参考答案：I am going to study tonight. She is going to visit her grandma. They are going to have a picnic.' },
                { question: '翻译：学习英语需要多听、多说、多读、多写。', answer: 'To learn English, we need to listen more, speak more, read more and write more.' }
            ]
        }
    };
    
    // 查找题库或生成通用题目
    const bank = questionBank[topic] || generateGenericQuestions(topic);
    
    for (let i = 0; i < count && questions.length < count; i++) {
        const type = typeList[i % typeList.length];
        const typeQuestions = bank[type];
        
        if (typeQuestions && typeQuestions.length > 0) {
            const qData = typeQuestions[i % typeQuestions.length];
            questions.push({
                type: type,
                question: qData.question,
                options: qData.options || [],
                correctAnswer: qData.answer,
                score: 10
            });
        }
    }
    
    return questions;
}

// 生成通用题目（当主题不在题库中时）
function generateGenericQuestions(topic) {
    return {
        single: [
            { question: `关于"${topic}"，下列说法正确的是？`, options: ['选项A', '选项B', '选项C', '选项D'], answer: '选项A' },
            { question: `${topic}的主要特点是？`, options: ['特点一', '特点二', '特点三', '以上都是'], answer: '以上都是' }
        ],
        truefalse: [
            { question: `"${topic}"是一个重要的学习内容。`, answer: '正确' },
            { question: `${topic}不需要学习。`, answer: '错误' }
        ],
        fill: [
            { question: `${topic}的学习需要______。`, answer: '坚持' },
            { question: '学习${topic}最好的方法是______。', answer: '多练习' }
        ],
        essay: [
            { question: `请简要介绍一下"${topic}"。`, answer: `${topic}是一个重要的知识点，需要通过学习和实践来掌握。` },
            { question: `谈谈你对"${topic}"的理解。`, answer: `${topic}涉及多个方面，需要综合运用所学知识来理解和应用。` }
        ]
    };
}

app.get('/api/ai/config', (req, res) => {
    res.json(aiConfig);
});

app.post('/api/ai/config', (req, res) => {
    const { provider, apiKey, model, baseUrl } = req.body;
    if (!provider || typeof provider !== 'string') return res.status(400).json({ error: '请选择提供商' });
    
    // 免费提供商不需要 API Key
    const freeProviders = ['freegpt', 'freechat', 'doubao-free', 'ollama'];
    if (!freeProviders.includes(provider.toLowerCase()) && !apiKey) {
        return res.status(400).json({ error: '该提供商需要输入API密钥' });
    }
    
    aiConfig.forEach(c => c.active = 0);
    aiConfig.push({
        id: Date.now(), provider: validateString(provider, 50),
        apiKey: validateString(apiKey || 'free', 200), model: validateString(model, 100),
        baseUrl: validateString(baseUrl, 500), active: 1,
        createdAt: new Date().toISOString()
    });
    
    saveData('ai_config.json', aiConfig);
    res.json({ success: true });
});

app.delete('/api/ai/config/:id', (req, res) => {
    const idx = aiConfig.findIndex(c => c.id == req.params.id);
    if (idx === -1) return res.status(404).json({ error: '配置不存在' });
    
    aiConfig.splice(idx, 1);
    saveData('ai_config.json', aiConfig);
    res.json({ success: true });
});

app.get('/api/chat/history/:sessionId', (req, res) => {
    const history = chatHistory.filter(h => h.sessionId === req.params.sessionId);
    res.json(history);
});

app.delete('/api/chat/history/:sessionId', (req, res) => {
    chatHistory = chatHistory.filter(h => h.sessionId !== req.params.sessionId);
    saveData('chat_history.json', chatHistory);
    res.json({ success: true });
});

app.post('/api/knowledge/batch', (req, res) => {
    const { items } = req.body;
    if (!items || !Array.isArray(items)) return res.status(400).json({ error: '请提供知识条目数组' });
    
    let count = 0;
    items.forEach(item => {
        if (item.title && item.content) {
            knowledge.push({
                id: Date.now().toString() + Math.random().toString(36).substr(2, 5),
                title: item.title, content: item.content,
                category: item.category || '未分类', tags: item.tags || '',
                createdAt: new Date().toISOString(), updatedAt: new Date().toISOString()
            });
            count++;
        }
    });
    
    saveData('knowledge.json', knowledge);
    res.json({ success: true, count });
});

app.get('/health', (req, res) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// 家校消息模块
let notices = loadData('notices.json');
let parentInfo = loadData('parent_info.json');
let msgTemplates = loadData('msg_templates.json');

// 初始化默认模板
if (msgTemplates.length === 0) {
    msgTemplates = [
        { id: 'tpl_1', name: '上课提醒', content: '各位家长您好，明日按时上课，请准时接送孩子，路途注意安全。', sort: 1 },
        { id: 'tpl_2', name: '作业通知', content: '今日课后作业，请家长督促孩子认真完成，及时提交。', sort: 2 },
        { id: 'tpl_3', name: '考勤提醒', content: '您的孩子今日上课出勤异常，请您抽空沟通了解情况。', sort: 3 },
        { id: 'tpl_4', name: '放假通知', content: '近期课程临时暂停，假期注意孩子安全，开课时间另行通知。', sort: 4 }
    ];
    saveData('msg_templates.json', msgTemplates);
}

// 短信推送（需配置环境变量）
async function sendSMS(phone, content) {
    const smsApiKey = process.env.SMS_API_KEY || '';
    if (!smsApiKey) {
        console.log(`[SMS] 短信推送未配置，跳过 -> ${phone}`);
        return { success: false, reason: 'SMS_NOT_CONFIGURED' };
    }
    // 实际调用示例（阿里云/腾讯云短信）：
    // const result = await fetch('https://dysmsapi.aliyuncs.com', { ... });
    console.log(`[SMS] 推送至 ${phone}: ${content.substring(0, 50)}`);
    return { success: true };
}

// 微信推送（需配置环境变量）
async function sendWechatTemplate(openid, title, content) {
    const wechatAppId = process.env.WECHAT_APP_ID || '';
    if (!wechatAppId) {
        console.log(`[WeChat] 微信推送未配置，跳过 -> ${openid}`);
        return { success: false, reason: 'WECHAT_NOT_CONFIGURED' };
    }
    console.log(`[WeChat] 推送模板消息至 ${openid}: ${title}`);
    return { success: true };
}

app.post('/api/notice/send', async (req, res) => {
    const { teacherId, classId, title, content, sendType, templateType, targetStudents, targetClassName } = req.body;
    if (!teacherId || !content) {
        return res.status(400).json({ code: 400, msg: '参数不全：缺少 teacherId 或 content' });
    }
    const msgId = 'msg_' + Date.now();
    const notice = {
        id: msgId,
        teacherId,
        classId: classId || '',
        targetClassName: targetClassName || '',
        title: title || '',
        content,
        sendType: sendType || 1,
        templateType: templateType || '',
        targetStudents: targetStudents || [],
        createTime: new Date().toISOString(),
        records: [],
        status: 'sent'
    };

    if (targetStudents && targetStudents.length > 0) {
        targetStudents.forEach(studentName => {
            const info = parentInfo.find(p => p.studentName === studentName);
            const parents = (info && info.parents) || [];
            if (parents.length === 0) {
                notice.records.push({ studentName, parentName: '', phone: '', isRead: 0, readTime: null });
            } else {
                parents.forEach(p => {
                    notice.records.push({ studentName, parentName: p.name, phone: p.phone, isRead: 0, readTime: null });
                });
            }
        });
    }

    notices.push(notice);
    saveData('notices.json', notices);

    // 异步推送短信（不阻塞响应）
    const smsPromises = notice.records
        .filter(r => r.phone)
        .map(r => sendSMS(r.phone, `${title || ''}\n${content}`).catch(e => ({ success: false, reason: e.message })));
    Promise.all(smsPromises).then(results => {
        const ok = results.filter(r => r.success).length;
        if (ok > 0) console.log(`[Notice] 短信推送成功 ${ok} 条`);
    });

    res.json({ code: 200, msg: '消息下发成功', msgId, recordCount: notice.records.length });
});

app.get('/api/notice/list', (req, res) => {
    const { teacherId, classId } = req.query;
    let list = notices;
    if (teacherId) list = list.filter(n => String(n.teacherId) === String(teacherId));
    if (classId) list = list.filter(n => n.classId === classId || n.targetClassName === classId);
    list = list.sort((a, b) => new Date(b.createTime) - new Date(a.createTime));
    res.json({ code: 200, data: list });
});

app.get('/api/notice/parent/:studentName', (req, res) => {
    const { studentName } = req.params;
    const list = notices
        .filter(n => {
            if (n.targetStudents && n.targetStudents.includes(studentName)) return true;
            if (n.records && n.records.some(r => r.studentName === studentName)) return true;
            return false;
        })
        .map(n => {
            const studentRecords = (n.records || []).filter(r => r.studentName === studentName);
            const isRead = studentRecords.length > 0 ? studentRecords.every(r => r.isRead) : false;
            return { id: n.id, title: n.title, content: n.content, createTime: n.createTime, isRead };
        })
        .sort((a, b) => new Date(b.createTime) - new Date(a.createTime));
    res.json({ code: 200, data: list });
});

app.post('/api/notice/read', (req, res) => {
    const { msgId, studentName } = req.body;
    if (!msgId || !studentName) return res.status(400).json({ code: 400, msg: '缺少 msgId 或 studentName' });
    const nt = notices.find(n => n.id === msgId);
    if (!nt) return res.status(404).json({ code: 404, msg: '消息不存在' });
    if (!nt.records) nt.records = [];
    let hasRecord = false;
    nt.records.forEach(r => {
        if (r.studentName === studentName) { r.isRead = 1; r.readTime = new Date().toISOString(); hasRecord = true; }
    });
    if (!hasRecord) {
        nt.records.push({ studentName, parentName: '', phone: '', isRead: 1, readTime: new Date().toISOString() });
    }
    saveData('notices.json', notices);
    res.json({ code: 200, msg: '已标记已读' });
});

app.get('/api/notice/stat/:msgId', (req, res) => {
    const { msgId } = req.params;
    const nt = notices.find(n => n.id === msgId);
    if (!nt) return res.status(404).json({ code: 404, msg: '消息不存在' });
    const records = nt.records || [];
    const total = records.length;
    const readCount = records.filter(r => r.isRead).length;
    const unreadList = records.filter(r => !r.isRead).map(r => ({
        studentName: r.studentName, parentName: r.parentName, phone: r.phone
    }));
    res.json({ code: 200, total, read: readCount, unread: total - readCount, unreadList });
});

app.get('/api/templates', (req, res) => {
    res.json({ code: 200, data: msgTemplates.sort((a, b) => (a.sort || 0) - (b.sort || 0)) });
});

app.post('/api/templates', (req, res) => {
    const { name, content, sort } = req.body;
    if (!name || !content) return res.status(400).json({ code: 400, msg: '模板名称和内容不能为空' });
    const tpl = { id: 'tpl_' + Date.now(), name: validateString(name, 30), content: validateString(content, 2000), sort: sort || msgTemplates.length + 1 };
    msgTemplates.push(tpl);
    saveData('msg_templates.json', msgTemplates);
    res.json({ code: 200, msg: '模板已添加', data: tpl });
});

app.put('/api/templates/:id', (req, res) => {
    const { id } = req.params;
    const { name, content, sort } = req.body;
    const tpl = msgTemplates.find(t => t.id === id);
    if (!tpl) return res.status(404).json({ code: 404, msg: '模板不存在' });
    if (name !== undefined) tpl.name = validateString(name, 30);
    if (content !== undefined) tpl.content = validateString(content, 2000);
    if (sort !== undefined) tpl.sort = sort;
    saveData('msg_templates.json', msgTemplates);
    res.json({ code: 200, msg: '模板已更新', data: tpl });
});

app.delete('/api/templates/:id', (req, res) => {
    const { id } = req.params;
    const idx = msgTemplates.findIndex(t => t.id === id);
    if (idx === -1) return res.status(404).json({ code: 404, msg: '模板不存在' });
    msgTemplates.splice(idx, 1);
    saveData('msg_templates.json', msgTemplates);
    res.json({ code: 200, msg: '模板已删除' });
});

app.get('/api/parent/info/:studentName', (req, res) => {
    const { studentName } = req.params;
    const info = parentInfo.find(p => p.studentName === studentName);
    res.json({ code: 200, data: info ? info.parents : [] });
});

app.post('/api/parent/info', (req, res) => {
    const { studentName, parents } = req.body;
    if (!studentName) return res.status(400).json({ code: 400, msg: '缺少 studentName' });
    let info = parentInfo.find(p => p.studentName === studentName);
    if (info) {
        info.parents = parents || [];
    } else {
        parentInfo.push({ studentName, parents: parents || [] });
    }
    saveData('parent_info.json', parentInfo);
    res.json({ code: 200, msg: '家长信息已保存' });
});

app.post('/api/parent/import', (req, res) => {
    const { dataList } = req.body;
    if (!Array.isArray(dataList)) return res.status(400).json({ code: 400, msg: 'dataList 必须为数组' });
    let count = 0;
    dataList.forEach(item => {
        if (!item.studentName || !item.parents) return;
        let info = parentInfo.find(p => p.studentName === item.studentName);
        if (info) {
            info.parents = item.parents;
        } else {
            parentInfo.push({ studentName: item.studentName, parents: item.parents });
        }
        count++;
    });
    saveData('parent_info.json', parentInfo);
    res.json({ code: 200, msg: `已导入 ${count} 条家长信息` });
});

// 家长群发消息
app.post('/api/messages/send', async (req, res) => {
    const { className, content, receivers, title } = req.body;
    if (!content || !receivers || !Array.isArray(receivers) || receivers.length === 0) {
        return res.status(400).json({ success: false, error: '参数不完整：需要 content 和 receivers' });
    }

    const msg = {
        id: 'msg_' + Date.now(),
        className: validateString(className, 100),
        title: validateString(title || '', 100),
        content: validateString(content, 2000),
        sender: '教师',  // 后续从 session 获取
        receivers: validateArray(receivers, 500),
        sendTime: new Date().toISOString(),
        status: 'sent'
    };

    // 存储到本地 JSON 文件
    let messages = loadData('messages.json');
    messages.push(msg);
    saveData('messages.json', messages);

    res.json({ success: true, id: msg.id, sendTime: msg.sendTime });
});

// 接口：获取群发消息历史
app.get('/api/messages/history', (req, res) => {
    const { className } = req.query;
    let messages = loadData('messages.json');
    if (className) {
        messages = messages.filter(m => m.className === className);
    }
    messages.sort((a, b) => new Date(b.sendTime) - new Date(a.sendTime));
    res.json({ success: true, data: messages });
});

// 接口：删除群发消息记录
app.delete('/api/messages/:id', (req, res) => {
    const { id } = req.params;
    let messages = loadData('messages.json');
    const idx = messages.findIndex(m => m.id === id);
    if (idx === -1) return res.status(404).json({ success: false, error: '消息不存在' });
    messages.splice(idx, 1);
    saveData('messages.json', messages);
    res.json({ success: true, msg: '已删除' });
});

// SSE 实时推送
const sseClients = new Map();
app.get('/api/sse', authMiddleware, (req, res) => {
    res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive'
    });
    res.write('data: {"type":"connected"}\n\n');
    const userId = req.user.id;
    if (!sseClients.has(userId)) sseClients.set(userId, []);
    sseClients.get(userId).push(res);

    // 心跳保活（每30秒发送一次，防止代理/浏览器断开空闲连接）
    const heartbeat = setInterval(() => {
        try { res.write(': heartbeat\n\n'); } catch(e) { /* 连接已断开 */ }
    }, 30000);

    req.on('close', () => {
        clearInterval(heartbeat);
        const clients = sseClients.get(userId) || [];
        const idx = clients.indexOf(res);
        if (idx >= 0) clients.splice(idx, 1);
    });
});
function pushToUser(userId, data) {
    const clients = sseClients.get(userId) || [];
    clients.forEach(res => res.write(`data: ${JSON.stringify(data)}\n\n`));
}

// 认证 API
app.post('/api/auth/login', (req, res) => {
    const { username, password } = req.body;
    if (!username || !password) return res.status(400).json({ code: 400, error: '用户名和密码不能为空' });
    const result = login(username, password);
    if (!result.success) return res.status(401).json({ code: 401, error: result.error });
    res.json({ code: 200, token: result.token, user: result.user });
});

app.post('/api/auth/register', (req, res) => {
    const { username, password, role, displayName, phone } = req.body;
    if (!username || !password || !role || !displayName) {
        return res.status(400).json({ code: 400, error: '用户名、密码、角色和显示名称不能为空' });
    }
    if (!['teacher', 'parent'].includes(role)) {
        return res.status(400).json({ code: 400, error: '角色必须为 teacher 或 parent' });
    }
    const cleanPhone = sanitizePhone(phone);
    const result = register(username, password, role, displayName, cleanPhone);
    if (!result.success) return res.status(400).json({ code: 400, error: result.error });
    res.json({ code: 200, token: result.token, user: result.user });
});

app.get('/api/auth/profile', authMiddleware, (req, res) => {
    res.json({ code: 200, user: req.user });
});

// 家长绑定学生（按学生ID + 家长手机号匹配）
app.post('/api/parent/bind', authMiddleware, (req, res) => {
    if (req.user.role !== 'parent') return res.status(403).json({ code: 403, error: '仅家长可绑定学生' });
    const { studentId } = req.body;
    if (!studentId) return res.status(400).json({ code: 400, error: '学生ID不能为空' });
    // 从 users 表获取当前登录家长的手机号
    const parentUser = db.prepare('SELECT phone FROM users WHERE id = ?').get(req.user.id);
    if (!parentUser || !parentUser.phone) {
        return res.status(400).json({ code: 400, error: '当前家长账号未设置手机号，请在设置中补充' });
    }
    // 查询学生记录
    const student = db.prepare(
        'SELECT s.*, c.name as class_name, c.course as class_course ' +
        'FROM students s LEFT JOIN classes c ON s.class_id = c.id ' +
        'WHERE s.id = ? AND s.status = \'active\''
    ).get(studentId);
    if (!student) return res.status(404).json({ code: 404, error: '未找到该学生记录' });
    // 校验：学生记录的家长手机号必须与当前登录家长手机号一致（双方都做数据清洗）
    if (sanitizePhone(student.parent_phone) !== sanitizePhone(parentUser.phone)) {
        return res.status(403).json({ code: 403, error: '学生家长手机号与您的账号手机号不一致，无法绑定' });
    }
    if (student.parent_id) {
        return res.status(409).json({ code: 409, error: '该学生已被其他家长账号绑定' });
    }
    // 生成家长-学生关联记录
    db.prepare('UPDATE students SET parent_id = ? WHERE id = ?').run(req.user.id, studentId);
    console.log('[/api/parent/bind] 绑定成功: studentId=', studentId, ', parentId=', req.user.id);
    res.json({ code: 200, student: { id: student.id, name: student.name, class_name: student.class_name || '', class_course: student.class_course || '' } });
});

// 家长获取可绑定学生列表（手机号一致且未绑定的学生）
app.get('/api/parent/bindable-students', authMiddleware, (req, res) => {
    if (req.user.role !== 'parent') return res.status(403).json({ code: 403, error: '仅家长可查看' });
    const parentUser = db.prepare('SELECT phone FROM users WHERE id = ?').get(req.user.id);
    console.log('[GET /api/parent/bindable-students] parentId=%s, db phone=[%s] (len=%d)', req.user.id, parentUser ? parentUser.phone : 'NULL', parentUser ? (parentUser.phone||'').length : 0);
    if (!parentUser || !parentUser.phone) {
        console.log('[GET /api/parent/bindable-students] 家长phone为空，返回空列表');
        return res.json({ code: 200, data: [] });
    }
    const cleanParentPhone = sanitizePhone(parentUser.phone);
    console.log('[GET /api/parent/bindable-students] 清洗后家长phone=[%s] (len=%d)', cleanParentPhone, cleanParentPhone.length);
    const students = db.prepare(
        'SELECT s.id, s.name, s.parent_phone, s.class_id, c.name as class_name, c.course as class_course ' +
        'FROM students s LEFT JOIN classes c ON s.class_id = c.id ' +
        'WHERE s.parent_phone = ? AND s.parent_id IS NULL AND s.status = \'active\' ' +
        'ORDER BY s.name'
    ).all(cleanParentPhone);
    console.log('[GET /api/parent/bindable-students] SQL查询结果: %d 条可绑定学生', students.length);
    if (students.length > 0) {
        students.forEach(s => console.log('  -> id=%s, name=%s, parent_phone=[%s]', s.id, s.name, s.parent_phone));
    } else {
        // 调试：打印所有未绑定学生的 parent_phone 帮助排查
        const allUnbound = db.prepare("SELECT id, name, parent_phone FROM students WHERE parent_id IS NULL AND status='active'").all();
        console.log('[GET /api/parent/bindable-students] 调试: 所有未绑定学生(%d条):', allUnbound.length);
        allUnbound.forEach(s => console.log('  -> id=%s, name=%s, parent_phone=[%s]', s.id, s.name, s.parent_phone));
    }
    res.json({ code: 200, data: students });
});

// 家长获取孩子列表
app.get('/api/parent/children', authMiddleware, (req, res) => {
    if (req.user.role !== 'parent') return res.status(403).json({ code: 403, error: '仅家长可查看' });
    const children = getParentChildren(req.user.id);
    res.json({ code: 200, data: children });
});

// 家长解除绑定
app.delete('/api/parent/bind/:studentId', authMiddleware, (req, res) => {
    if (req.user.role !== 'parent') return res.status(403).json({ code: 403, error: '仅家长可操作' });
    const studentId = req.params.studentId;
    const student = db.prepare('SELECT * FROM students WHERE id = ? AND parent_id = ?')
        .get(studentId, req.user.id);
    if (!student) return res.status(404).json({ code: 404, error: '未找到该绑定关系' });
    db.prepare('UPDATE students SET parent_id = NULL WHERE id = ?').run(studentId);
    res.json({ code: 200, message: '已解除绑定' });
});

// 学期 API
app.get('/api/semesters', authMiddleware, (req, res) => {
    const list = db.prepare('SELECT * FROM semesters ORDER BY id DESC').all();
    res.json({ code: 200, data: list });
});

app.post('/api/semesters', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, startDate, endDate, isActive } = req.body;
    if (!name || !startDate || !endDate) return res.status(400).json({ code: 400, error: '参数不完整' });
    if (isActive) db.prepare('UPDATE semesters SET is_active=0').run();
    const result = db.prepare('INSERT INTO semesters (name, start_date, end_date, is_active) VALUES (?,?,?,?)')
        .run(name, startDate, endDate, isActive ? 1 : 0);
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.put('/api/semesters/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, startDate, endDate, isActive } = req.body;
    if (isActive) db.prepare('UPDATE semesters SET is_active=0 WHERE id != ?').run(req.params.id);
    db.prepare('UPDATE semesters SET name=?, start_date=?, end_date=?, is_active=? WHERE id=?')
        .run(name, startDate, endDate, isActive ? 1 : 0, req.params.id);
    res.json({ code: 200 });
});

// 班级 API
app.get('/api/classes', authMiddleware, (req, res) => {
    const { semesterId } = req.query;
    let sql = `SELECT c.*, s.name as semester_name,
        (SELECT COUNT(*) FROM students WHERE class_id=c.id) as student_count
        FROM classes c LEFT JOIN semesters s ON c.semester_id=s.id WHERE 1=1`;
    const params = [];
    // 教师只能看到自己的班级
    if (req.user.role === 'teacher') {
        sql += ' AND c.teacher_id=?';
        params.push(req.user.id);
    }
    if (semesterId) { sql += ' AND c.semester_id=?'; params.push(semesterId); }
    sql += ' ORDER BY c.id DESC';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

// 教师获取自己的班级列表（简版，用于下拉选择等）
app.get('/api/classes/my', authMiddleware, requireRole('teacher'), (req, res) => {
    const { semesterId } = req.query;
    let sql = `SELECT c.*, (SELECT COUNT(*) FROM students WHERE class_id=c.id) as student_count
        FROM classes c WHERE c.teacher_id=?`;
    const params = [req.user.id];
    if (semesterId) { sql += ' AND c.semester_id=?'; params.push(semesterId); }
    sql += ' ORDER BY c.id DESC';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

app.post('/api/classes', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, course, semesterId } = req.body;
    if (!name || !course) return res.status(400).json({ code: 400, error: '班级名称和课程不能为空' });
    const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
    const result = db.prepare('INSERT INTO classes (name, course, semester_id, teacher_id) VALUES (?,?,?,?)')
        .run(name, course, semesterId || (activeSem ? activeSem.id : null), req.user.id);
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.put('/api/classes/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, course, semesterId } = req.body;
    db.prepare('UPDATE classes SET name=?, course=?, semester_id=? WHERE id=?')
        .run(name, course, semesterId, req.params.id);
    res.json({ code: 200 });
});

app.delete('/api/classes/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    db.prepare('DELETE FROM classes WHERE id=?').run(req.params.id);
    res.json({ code: 200 });
});

// 学生 API
app.get('/api/classes/:id/students', authMiddleware, (req, res) => {
    const list = db.prepare(`
        SELECT s.*, u.display_name as parent_name, u.phone as parent_phone
        FROM students s LEFT JOIN users u ON s.parent_id=u.id
        WHERE s.class_id=? AND s.status='active'
        ORDER BY s.name
    `).all(req.params.id);
    res.json({ code: 200, data: list });
});

app.get('/api/students', authMiddleware, (req, res) => {
    const { classId } = req.query;
    let sql = `SELECT s.*, c.name as class_name FROM students s LEFT JOIN classes c ON s.class_id=c.id WHERE s.status='active'`;
    const params = [];
    if (classId) { sql += ' AND s.class_id=?'; params.push(classId); }
    sql += ' ORDER BY s.name';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

app.get('/api/students/:id', authMiddleware, (req, res) => {
    const student = db.prepare(`
        SELECT s.*, c.name as class_name, c.course as class_course, c.id as class_id
        FROM students s LEFT JOIN classes c ON s.class_id=c.id WHERE s.id=?
    `).get(req.params.id);
    if (!student) return res.status(404).json({ code: 404, error: '学生不存在' });

    // 获取上课记录
    const records = db.prepare(`
        SELECT * FROM records WHERE class_name=? ORDER BY date DESC
    `).all(student.class_name);

    // 获取课表
    const schedule = db.prepare(`
        SELECT * FROM schedules WHERE class_id=? ORDER BY weekday, start_time
    `).all(student.class_id);

    res.json({ code: 200, data: { ...student, records, schedule } });
});

app.post('/api/students', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, classId, parentPhone, parentName, parentRelation, phone } = req.body;
    if (!name || !classId) return res.status(400).json({ code: 400, error: '学生姓名和班级不能为空' });
    const cleanPhone = sanitizePhone(parentPhone);
    if (!cleanPhone) return res.status(400).json({ code: 400, error: '家长手机号不能为空' });
    const result = db.prepare(`
        INSERT INTO students (name, class_id, phone, parent_phone, parent_name, parent_relation, enrollment_date)
        VALUES (?,?,?,?,?,?,?)
    `).run(name, classId, phone || '', cleanPhone, parentName || '', parentRelation || '', new Date().toISOString().slice(0, 10));
    // 校验入库结果
    const saved = db.prepare('SELECT parent_phone FROM students WHERE id = ?').get(result.lastInsertRowid);
    console.log('[POST /api/students] 新增学生 id=%s, name=%s, 入库parent_phone=[%s] (len=%d), 原始parentPhone=[%s]',
        result.lastInsertRowid, name, saved.parent_phone, (saved.parent_phone||'').length, parentPhone);
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.put('/api/students/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, classId, phone, parentPhone, parentName, parentRelation, status, tags } = req.body;
    console.log('[PUT /api/students/:id] 请求body=%j', req.body);
    console.log('[PUT /api/students/:id] parentPhone参数=[%s] (type=%s)', parentPhone, typeof parentPhone);
    // 如果parentPhone为undefined/null，不覆盖数据库字段，保留原值
    let finalPhone;
    if (parentPhone === undefined || parentPhone === null) {
        const existing = db.prepare('SELECT parent_phone FROM students WHERE id=?').get(req.params.id);
        finalPhone = existing ? existing.parent_phone : '';
        console.log('[PUT /api/students/:id] parentPhone未提供，保留数据库原值=[%s]', finalPhone);
    } else {
        finalPhone = sanitizePhone(parentPhone);
        console.log('[PUT /api/students/:id] 清洗后parentPhone=[%s]', finalPhone);
    }
    db.prepare(`
        UPDATE students SET name=?, class_id=?, phone=?, parent_phone=?, parent_name=?, parent_relation=?, status=?, tags=? WHERE id=?
    `).run(name, classId, phone || '', finalPhone, parentName || '', parentRelation || '', status || 'active', tags ? JSON.stringify(tags) : null, req.params.id);
    // 校验入库结果
    const saved = db.prepare('SELECT parent_phone FROM students WHERE id=?').get(req.params.id);
    console.log('[PUT /api/students/:id] 更新后parent_phone=[%s] (len=%d)', saved.parent_phone, (saved.parent_phone||'').length);
    res.json({ code: 200 });
});

app.delete('/api/students/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    db.prepare('UPDATE students SET status=? WHERE id=?').run('inactive', req.params.id);
    res.json({ code: 200 });
});

// 上课记录 API
app.get('/api/records', authMiddleware, (req, res) => {
    const { classId, className, date, semesterId } = req.query;
    let sql = `SELECT * FROM records WHERE 1=1`;
    const params = [];
    if (classId) { sql += ' AND class_id=?'; params.push(classId); }
    if (className) { sql += ' AND class_name=?'; params.push(className); }
    if (date) { sql += ' AND date=?'; params.push(date); }
    if (semesterId) { sql += ' AND semester_id=?'; params.push(semesterId); }
    sql += ' ORDER BY date DESC, id DESC';
    const list = db.prepare(sql).all(...params);
    // 解析 JSON 字段
    list.forEach(r => {
        try { r.absent = JSON.parse(r.absent_json || '[]'); } catch(e) { r.absent = []; }
        try { r.trialStudents = JSON.parse(r.trial_students || '[]'); } catch(e) { r.trialStudents = []; }
        delete r.absent_json; delete r.trial_students;
    });
    res.json({ code: 200, data: list });
});

app.post('/api/records', authMiddleware, requireRole('teacher'), (req, res) => {
    const { date, classId, className, course, sessions, type, semesterId, remark, absent, trialStudents } = req.body;
    if (!date || !className) return res.status(400).json({ code: 400, error: '日期和班级名称不能为空' });
    const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
    const result = db.prepare(`
        INSERT INTO records (date, class_id, class_name, course, sessions, type, semester_id, remark, absent_json, trial_students)
        VALUES (?,?,?,?,?,?,?,?,?,?)
    `).run(date, classId || null, className, course || '', sessions || 1, type || '正常课',
        semesterId || (activeSem ? activeSem.id : null), remark || '',
        JSON.stringify(absent || []), JSON.stringify(trialStudents || []));
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.put('/api/records/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    const { date, classId, className, course, sessions, type, semesterId, remark, absent, trialStudents } = req.body;
    db.prepare(`
        UPDATE records SET date=?, class_id=?, class_name=?, course=?, sessions=?, type=?, semester_id=?, remark=?, absent_json=?, trial_students=? WHERE id=?
    `).run(date, classId || null, className, course || '', sessions || 1, type || '正常课',
        semesterId, remark || '', JSON.stringify(absent || []), JSON.stringify(trialStudents || []), req.params.id);
    res.json({ code: 200 });
});

app.delete('/api/records/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    db.prepare('DELETE FROM records WHERE id=?').run(req.params.id);
    res.json({ code: 200 });
});

// 课表 API
app.get('/api/schedules', authMiddleware, (req, res) => {
    const { classId, semesterId } = req.query;
    let sql = `SELECT * FROM schedules WHERE 1=1`;
    const params = [];
    // 教师只能看到自己的课表
    if (req.user.role === 'teacher') {
        sql += ' AND teacher_id=?';
        params.push(req.user.id);
    }
    if (classId) { sql += ' AND class_id=?'; params.push(classId); }
    if (semesterId) { sql += ' AND semester_id=?'; params.push(semesterId); }
    sql += ' ORDER BY weekday, start_time';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

// 教师查看自己的课表（关联班级名称和课程）
app.get('/api/schedules/my', authMiddleware, requireRole('teacher'), (req, res) => {
    const { semesterId } = req.query;
    let sql = `SELECT s.*, c.name as class_name, c.course
        FROM schedules s
        LEFT JOIN classes c ON s.class_id = c.id
        WHERE s.teacher_id=?`;
    const params = [req.user.id];
    if (semesterId) { sql += ' AND s.semester_id=?'; params.push(semesterId); }
    sql += ' ORDER BY s.weekday, s.start_time';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

// 家长查看孩子的课表
app.get('/api/schedules/child', authMiddleware, (req, res) => {
    if (req.user.role !== 'parent') return res.status(403).json({ code: 403, error: '仅家长可查看' });
    const parentId = req.user.id;
    // 查询已绑定孩子所在班级的课表
    const list = db.prepare(`
        SELECT s.*, c.name as class_name, c.course, st.name as student_name
        FROM schedules s
        JOIN classes c ON s.class_id = c.id
        JOIN students st ON st.class_id = c.id
        WHERE st.parent_id = ? AND st.status = 'active'
        ORDER BY s.weekday, s.start_time
    `).all(parentId);
    res.json({ code: 200, data: list });
});

// 获取下节课（教师/家长角色区分）
app.get('/api/schedules/next', authMiddleware, (req, res) => {
    const now = new Date();
    const currentWeekday = now.getDay();
    const currentTime = now.getHours() * 60 + now.getMinutes();

    let schedules = [];
    if (req.user.role === 'teacher') {
        schedules = db.prepare(`
            SELECT s.*, c.name as class_name, c.course,
                (SELECT COUNT(*) FROM students WHERE class_id=c.id AND status='active') as student_count
            FROM schedules s
            JOIN classes c ON s.class_id = c.id
            WHERE s.teacher_id=?
        `).all(req.user.id);
    } else if (req.user.role === 'parent') {
        schedules = db.prepare(`
            SELECT s.*, c.name as class_name, c.course, st.name as student_name
            FROM schedules s
            JOIN classes c ON s.class_id = c.id
            JOIN students st ON st.class_id = c.id
            WHERE st.parent_id=? AND st.status='active'
        `).all(req.user.id);
    } else {
        return res.status(400).json({ code: 400, error: '未知角色' });
    }

    if (!schedules || schedules.length === 0) {
        return res.json({ code: 200, data: null, message: '暂无排课' });
    }

    let nextClass = null;
    let minDiff = Infinity;
    for (const s of schedules) {
        const weekdayDiff = ((s.weekday - currentWeekday + 7) % 7) * 24 * 60;
        const [sh, sm] = (s.start_time || '').split(':').map(Number);
        const startMin = (sh || 0) * 60 + (sm || 0);
        let diff = weekdayDiff + (startMin - currentTime);
        if (s.weekday === currentWeekday && startMin <= currentTime) {
            diff += 7 * 24 * 60;
        }
        if (diff >= 0 && diff < minDiff) {
            minDiff = diff;
            nextClass = { ...s, minutesAhead: diff };
        }
    }
    res.json({ code: 200, data: nextClass });
});

app.post('/api/schedules', authMiddleware, requireRole('teacher'), (req, res) => {
    const { weekday, startTime, endTime, classId, className, course, sessions, semesterId } = req.body;
    if (weekday === undefined || !startTime || !endTime || !classId) {
        return res.status(400).json({ code: 400, error: '参数不完整' });
    }
    // 验证班级归属
    const cls = db.prepare('SELECT * FROM classes WHERE id=? AND teacher_id=?').get(classId, req.user.id);
    if (!cls) return res.status(403).json({ code: 403, error: '您不是该班级的任课教师' });
    const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
    const result = db.prepare(`
        INSERT INTO schedules (weekday, start_time, end_time, class_id, class_name, course, sessions, semester_id, teacher_id)
        VALUES (?,?,?,?,?,?,?,?,?)
    `).run(weekday, startTime, endTime, classId, className || cls.name, course || cls.course, sessions || 1, semesterId || (activeSem ? activeSem.id : null), req.user.id);
    res.json({ code: 200, id: result.lastInsertRowid });
});

// 批量规律排课
app.post('/api/schedules/batch', authMiddleware, requireRole('teacher'), (req, res) => {
    const { classId, entries, startTime, endTime, sessions, course } = req.body;
    if (!classId || !entries || !Array.isArray(entries) || entries.length === 0) {
        return res.status(400).json({ code: 400, error: '参数不完整：需要 classId 和 entries 数组' });
    }
    // 验证班级归属
    const cls = db.prepare('SELECT * FROM classes WHERE id=? AND teacher_id=?').get(classId, req.user.id);
    if (!cls) return res.status(403).json({ code: 403, error: '您不是该班级的任课教师' });
    const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
    const semId = activeSem ? activeSem.id : null;
    const insert = db.prepare(`
        INSERT INTO schedules (weekday, start_time, end_time, class_id, class_name, course, sessions, semester_id, teacher_id)
        VALUES (?,?,?,?,?,?,?,?,?)
    `);
    const insertBatch = db.transaction(() => {
        for (const e of entries) {
            insert.run(
                e.weekday,
                e.startTime || startTime,
                e.endTime || endTime,
                classId,
                cls.name,
                course || cls.course,
                e.sessions || sessions || 1,
                e.semesterId || semId,
                req.user.id
            );
        }
    });
    insertBatch();
    res.json({ code: 200, count: entries.length });
});

app.delete('/api/schedules/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    // 验证课表归属
    const sched = db.prepare('SELECT teacher_id FROM schedules WHERE id=?').get(req.params.id);
    if (sched && sched.teacher_id && sched.teacher_id !== req.user.id) {
        return res.status(403).json({ code: 403, error: '无权删除他人的课表' });
    }
    db.prepare('DELETE FROM schedules WHERE id=?').run(req.params.id);
    res.json({ code: 200 });
});

// 消息通知 API
app.get('/api/messages', authMiddleware, (req, res) => {
    const userId = req.user.id;
    const list = db.prepare(`
        SELECT m.*, s.role as sender_role, s.display_name as sender_display
        FROM messages m
        LEFT JOIN users s ON m.sender_id=s.id
        WHERE m.receiver_id=? OR m.sender_id=?
        ORDER BY m.created_at DESC
    `).all(userId, userId);
    res.json({ code: 200, data: list });
});

app.post('/api/messages', authMiddleware, (req, res) => {
    const { content, title, classId, className, receiverIds, receiverId, studentIds, sendToAll, studentName, msgType } = req.body;
    if (!content || content.trim().length === 0) return res.status(400).json({ code: 400, error: '消息内容不能为空' });
    if (content.length > 2000) return res.status(400).json({ code: 400, error: '消息内容不能超过2000字' });

    const senderId = req.user.id;
    const senderName = req.user.displayName || req.user.username;
    const senderRole = req.user.role;

    let receivers = [];

    // 家长发消息给教师
    if (senderRole === 'parent') {
        if (receiverId) {
            // 指定教师ID
            receivers = [Number(receiverId)];
        } else if (receiverIds && Array.isArray(receiverIds) && receiverIds.length > 0) {
            receivers = receiverIds;
        } else {
            // 未指定教师，发送给所有教师
            const teachers = db.prepare(`SELECT DISTINCT id FROM users WHERE role='teacher'`).all();
            receivers = teachers.map(t => t.id);
        }
    }

    // 教师发消息/通知给家长
    if (senderRole === 'teacher') {
        if (sendToAll && classId) {
            // 发送给全班学生家长
            receivers = db.prepare(`
                SELECT DISTINCT parent_id FROM students
                WHERE class_id=? AND parent_id IS NOT NULL
            `).all(classId).map(r => r.parent_id);
        } else if (studentIds && Array.isArray(studentIds) && studentIds.length > 0) {
            // 发送给指定学生的家长
            const placeholders = studentIds.map(() => '?').join(',');
            receivers = db.prepare(`
                SELECT DISTINCT parent_id FROM students
                WHERE id IN (${placeholders}) AND parent_id IS NOT NULL
            `).all(...studentIds).map(r => r.parent_id);
        } else if (receiverIds && Array.isArray(receiverIds) && receiverIds.length > 0) {
            receivers = receiverIds;
        } else if (classId) {
            // 群发：查该班级所有绑定家长的用户ID
            receivers = db.prepare(`
                SELECT DISTINCT parent_id FROM students WHERE class_id=? AND parent_id IS NOT NULL
            `).all(classId).map(r => r.parent_id);
        } else if (studentName) {
            receivers = db.prepare('SELECT DISTINCT parent_id FROM students WHERE name=? AND parent_id IS NOT NULL').all(studentName).map(r => r.parent_id);
        }
    }

    // 排除发送者自己
    receivers = receivers.filter(rid => rid !== senderId);

    if (receivers.length === 0) {
        return res.json({ code: 200, receiverCount: 0, msg: '没有符合条件的接收者（学生未绑定家长）' });
    }

    const insertMsg = db.prepare(`
        INSERT INTO messages (sender_id, sender_name, receiver_id, student_name, class_id, class_name, title, content, msg_type)
        VALUES (?,?,?,?,?,?,?,?,?)
    `);
    const insertMany = db.transaction((rids) => {
        rids.forEach(rid => {
            insertMsg.run(senderId, senderName, rid, studentName || '', classId || null, className || '', title || '', content, msgType || 'notice');
        });
    });
    insertMany(receivers);

    // SSE 推送给所有接收者（不推送给发送者本人）
    receivers.forEach(rid => {
        pushToUser(rid, { type: 'new_message', title: title || '新消息', content: content.substring(0, 100), senderName, senderRole });
    });

    res.json({ code: 200, receiverCount: receivers.length });
});

app.put('/api/messages/:id/read', authMiddleware, (req, res) => {
    db.prepare('UPDATE messages SET status=?, read_at=CURRENT_TIMESTAMP WHERE id=? AND receiver_id=?')
        .run('read', req.params.id, req.user.id);
    res.json({ code: 200 });
});

// 获取未读消息数
app.get('/api/messages/unread/count', authMiddleware, (req, res) => {
    const count = db.prepare(`
        SELECT COUNT(*) as c FROM messages WHERE receiver_id=? AND status='unread'
    `).get(req.user.id).c;
    res.json({ code: 200, count });
});

// 回复消息
app.post('/api/messages/:id/reply', authMiddleware, (req, res) => {
    const { content } = req.body;
    if (!content || content.trim().length === 0) return res.status(400).json({ code: 400, error: '回复内容不能为空' });
    if (content.length > 2000) return res.status(400).json({ code: 400, error: '回复内容不能超过2000字' });

    const messageId = req.params.id;
    const original = db.prepare('SELECT * FROM messages WHERE id=?').get(messageId);
    if (!original) return res.status(404).json({ code: 404, error: '原消息不存在' });

    const senderId = req.user.id;
    const senderName = req.user.displayName || req.user.username;
    const senderRole = req.user.role;
    const receiverId = original.sender_id;

    if (!receiverId || receiverId === senderId) {
        return res.status(400).json({ code: 400, error: '无法回复该消息' });
    }

    const result = db.prepare(`
        INSERT INTO messages (sender_id, sender_name, receiver_id, student_name, class_id, class_name, title, content, msg_type)
        VALUES (?,?,?,?,?,?,?,?,?)
    `).run(senderId, senderName, receiverId, original.student_name || '', original.class_id || null, original.class_name || '', '回复：' + (original.title || ''), content, 'consult');

    // SSE 推送给接收者（不推送给发送者）
    pushToUser(receiverId, { type: 'new_message', title: '回复：' + (original.title || ''), content: content.substring(0, 100), senderName, senderRole });

    res.json({ code: 200, id: result.lastInsertRowid });
});

// 作业 API
app.get('/api/homework', authMiddleware, (req, res) => {
    const { classId } = req.query;
    let sql = `SELECT h.*, c.name as class_name FROM homework h LEFT JOIN classes c ON h.class_id=c.id`;
    const params = [];
    if (classId) { sql += ' WHERE h.class_id=?'; params.push(classId); }
    sql += ' ORDER BY h.created_at DESC';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

app.post('/api/homework', authMiddleware, requireRole('teacher'), (req, res) => {
    const { classId, title, content, deadline } = req.body;
    if (!title) return res.status(400).json({ code: 400, error: '作业标题不能为空' });
    const result = db.prepare('INSERT INTO homework (class_id, title, content, deadline, created_by) VALUES (?,?,?,?,?)')
        .run(classId || null, title, content || '', deadline || '', req.user.id);
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.post('/api/homework/:id/submit', authMiddleware, (req, res) => {
    const { studentId, studentName, content } = req.body;
    if (!studentId || !content) return res.status(400).json({ code: 400, error: '参数不完整' });
    const result = db.prepare('INSERT INTO homework_submissions (homework_id, student_id, student_name, content, submitted_at) VALUES (?,?,?,?,?)')
        .run(req.params.id, studentId, studentName || '', content, new Date().toISOString());
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.put('/api/homework/submissions/:id/grade', authMiddleware, requireRole('teacher'), (req, res) => {
    const { score, comment } = req.body;
    db.prepare('UPDATE homework_submissions SET score=?, comment=? WHERE id=?')
        .run(score, comment || '', req.params.id);
    res.json({ code: 200 });
});

// 消息模板 API
app.get('/api/msg-templates', authMiddleware, (req, res) => {
    const list = db.prepare('SELECT * FROM msg_templates ORDER BY sort').all();
    res.json({ code: 200, data: list });
});

app.post('/api/msg-templates', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, content, sort } = req.body;
    if (!name || !content) return res.status(400).json({ code: 400, error: '模板名称和内容不能为空' });
    const result = db.prepare('INSERT INTO msg_templates (name, content, sort) VALUES (?,?,?)')
        .run(name, content, sort || 99);
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.put('/api/msg-templates/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    const { name, content, sort } = req.body;
    db.prepare('UPDATE msg_templates SET name=?, content=?, sort=? WHERE id=?')
        .run(name, content, sort || 99, req.params.id);
    res.json({ code: 200 });
});

app.delete('/api/msg-templates/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    db.prepare('DELETE FROM msg_templates WHERE id=?').run(req.params.id);
    res.json({ code: 200 });
});

// 数据导出/导入 API

function exportAllData() {
    const records = db.prepare('SELECT * FROM records').all();
    // 解析 records 中的 JSON 字段
    records.forEach(r => {
        try { r.absent = JSON.parse(r.absent_json || '[]'); } catch(e) { r.absent = []; }
        try { r.trialStudents = JSON.parse(r.trial_students || '[]'); } catch(e) { r.trialStudents = []; }
        delete r.absent_json; delete r.trial_students;
    });

    const homework = db.prepare('SELECT * FROM homework').all();
    homework.forEach(h => {
        try { h.submissions = JSON.parse(h.submissions_json || '[]'); } catch(e) { h.submissions = []; }
        delete h.submissions_json;
    });

    return {
        semesters: db.prepare('SELECT * FROM semesters').all(),
        classes: db.prepare('SELECT * FROM classes').all(),
        students: db.prepare('SELECT * FROM students').all(),
        records: records,
        schedules: db.prepare('SELECT * FROM schedules').all(),
        messages: db.prepare('SELECT * FROM messages').all(),
        homework: homework,
        grades: db.prepare('SELECT * FROM grades').all(),
        msgTemplates: db.prepare('SELECT * FROM msg_templates').all()
    };
}

// /api/export 和 /api/data/export 均可用
app.get('/api/export', authMiddleware, requireRole('teacher'), (req, res) => {
    res.json({ code: 200, data: exportAllData() });
});
app.get('/api/data/export', authMiddleware, requireRole('teacher'), (req, res) => {
    res.json({ code: 200, data: exportAllData() });
});

app.post('/api/import', authMiddleware, requireRole('teacher'), (req, res) => {
    const { data } = req.body;
    if (!data) return res.status(400).json({ code: 400, error: '无数据' });

    let count = 0;
    try {
        db.exec('BEGIN');

        // 兼容旧版 appData 格式：students 为对象 { '班级名': ['学生1', ...] }
        const oldStudents = (data.students && typeof data.students === 'object' && !Array.isArray(data.students)) ? data.students : null;

        // 1. 导入学期
        if (data.semesters && Array.isArray(data.semesters)) {
            const stmt = db.prepare('INSERT OR IGNORE INTO semesters (name,start_date,end_date,is_active) VALUES (?,?,?,?)');
            data.semesters.forEach(s => {
                const r = stmt.run(s.name, s.start_date || s.startDate || '', s.end_date || s.endDate || '', (s.is_active !== undefined ? s.is_active : (s.isActive ? 1 : 0)) || 0);
                if (r.changes > 0) count++;
            });
        }

        // 构建 name->id 映射
        const semesterMap = {};
        db.prepare('SELECT id, name FROM semesters').all().forEach(s => { semesterMap[s.name] = s.id; });

        // 2. 导入班级（兼容旧版 semesters[].classes 和顶层 classes）
        const classStmt = db.prepare('INSERT OR IGNORE INTO classes (name,course,semester_id) VALUES (?,?,?)');
        if (data.semesters && Array.isArray(data.semesters)) {
            data.semesters.forEach(sem => {
                const semId = semesterMap[sem.name];
                if (!semId) return;
                (sem.classes || []).forEach(c => {
                    const r = classStmt.run(c.name, c.course || '', semId);
                    if (r.changes > 0) count++;
                });
            });
        }
        if (data.classes && Array.isArray(data.classes)) {
            const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
            const defaultSemId = activeSem ? activeSem.id : 1;
            data.classes.forEach(c => {
                const r = classStmt.run(c.name, c.course || '', c.semester_id || defaultSemId);
                if (r.changes > 0) count++;
            });
        }

        // 构建 class name->id 映射
        const classMap = {};
        db.prepare('SELECT id, name FROM classes').all().forEach(c => { classMap[c.name] = c.id; });

        // 3. 导入学生
        if (oldStudents) {
            const stmt = db.prepare('INSERT INTO students (name,class_id,parent_phone,parent_name,enrollment_date,status) VALUES (?,?,?,?,?,?)');
            Object.entries(oldStudents).forEach(([cn, names]) => {
                const cid = classMap[cn];
                if (!cid) return;
                names.forEach(n => {
                    const sName = typeof n === 'object' ? n.name : n;
                    const pInfo = data.parentInfo && data.parentInfo[sName];
                    stmt.run(sName, cid, (pInfo && pInfo[0] && pInfo[0].phone) || '', (pInfo && pInfo[0] && pInfo[0].name) || '', new Date().toISOString().slice(0,10), 'active');
                    count++;
                });
            });
        } else if (data.students && Array.isArray(data.students)) {
            const stmt = db.prepare('INSERT INTO students (name,class_id,parent_phone,parent_name,enrollment_date,status) VALUES (?,?,?,?,?,?)');
            data.students.forEach(s => {
                stmt.run(s.name, s.class_id || classMap[s.class_name] || null, s.parent_phone || '', s.parent_name || '', s.enrollment_date || '', s.status || 'active');
                count++;
            });
        }

        // 4. 导入上课记录
        if (data.records && Array.isArray(data.records)) {
            const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
            const defaultSemId = activeSem ? activeSem.id : 1;
            const stmt = db.prepare('INSERT INTO records (date,class_id,class_name,course,sessions,type,semester_id,remark,absent_json,trial_students) VALUES (?,?,?,?,?,?,?,?,?,?)');
            data.records.forEach(r => {
                const cn = r.class_name || r.className || r.class || '';
                stmt.run(r.date || '', r.class_id || classMap[cn] || null, cn, r.course || '', r.sessions || 1, r.type || '正常课', r.semester_id || defaultSemId, r.remark || '', JSON.stringify(r.absent || []), JSON.stringify(r.trialStudents || []));
                count++;
            });
        }

        db.exec('COMMIT');
        res.json({ code: 200, imported: count });
    } catch (err) {
        db.exec('ROLLBACK');
        console.error('[Import] 导入失败:', err.message);
        res.status(500).json({ code: 500, error: '导入失败: ' + err.message });
    }
});

// /api/data/import 别名
app.post('/api/data/import', authMiddleware, requireRole('teacher'), (req, res) => {
    // 复用 /api/import 的逻辑
    req.url = '/api/import';
    app.handle(req, res);
});

// 成绩管理 API
app.get('/api/grades', authMiddleware, (req, res) => {
    const { classId, studentId, examName, semesterId } = req.query;
    let sql = `SELECT g.*, s.name as student_real_name FROM grades g LEFT JOIN students s ON g.student_id=s.id WHERE 1=1`;
    const params = [];
    if (classId) { sql += ' AND g.class_id=?'; params.push(classId); }
    if (studentId) { sql += ' AND g.student_id=?'; params.push(studentId); }
    if (examName) { sql += ' AND g.exam_name=?'; params.push(examName); }
    if (semesterId) { sql += ' AND g.semester_id=?'; params.push(semesterId); }
    if (req.user.role === 'teacher') {
        sql += ' AND g.teacher_id=?';
        params.push(req.user.id);
    } else if (req.user.role === 'parent') {
        sql += ' AND g.student_id IN (SELECT id FROM students WHERE parent_id=?)';
        params.push(req.user.id);
    }
    sql += ' ORDER BY g.created_at DESC';
    const list = db.prepare(sql).all(...params);
    res.json({ code: 200, data: list });
});

app.post('/api/grades', authMiddleware, requireRole('teacher'), (req, res) => {
    const { studentId, studentName, classId, className, examName, examType, score, totalScore, semesterId, remark } = req.body;
    if (!studentId || !examName || score === undefined) {
        return res.status(400).json({ code: 400, error: '学生、考试名称和分数不能为空' });
    }
    const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
    const result = db.prepare(
        'INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, semester_id, teacher_id, remark) VALUES (?,?,?,?,?,?,?,?,?,?,?)'
    ).run(studentId, studentName || '', classId || null, className || '', examName,
        examType || 'unit_test', score, totalScore || 100,
        semesterId || (activeSem ? activeSem.id : null), req.user.id, remark || '');
    res.json({ code: 200, id: result.lastInsertRowid });
});

app.post('/api/grades/batch', authMiddleware, requireRole('teacher'), (req, res) => {
    const { classId, className, examName, examType, totalScore, semesterId, entries } = req.body;
    if (!classId || !examName || !entries || !Array.isArray(entries)) {
        return res.status(400).json({ code: 400, error: '参数不完整' });
    }
    const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
    const semId = semesterId || (activeSem ? activeSem.id : null);
    const insert = db.prepare(
        'INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, semester_id, teacher_id, remark) VALUES (?,?,?,?,?,?,?,?,?,?,?)'
    );
    const batch = db.transaction(function() {
        for (const e of entries) {
            insert.run(e.studentId, e.studentName || '', classId, className || '',
                examName, examType || 'unit_test', e.score, totalScore || 100,
                semId, req.user.id, e.remark || '');
        }
    });
    batch();
    res.json({ code: 200, count: entries.length });
});

app.put('/api/grades/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    const { score, totalScore, rank, remark } = req.body;
    db.prepare('UPDATE grades SET score=?, total_score=?, rank=?, remark=? WHERE id=?')
        .run(score, totalScore || 100, rank || null, remark || '', req.params.id);
    res.json({ code: 200 });
});

app.delete('/api/grades/:id', authMiddleware, requireRole('teacher'), (req, res) => {
    db.prepare('DELETE FROM grades WHERE id=?').run(req.params.id);
    res.json({ code: 200 });
});

// 成绩统计
app.get('/api/grades/stats', authMiddleware, (req, res) => {
    const { classId, examName } = req.query;
    if (!classId || !examName) return res.status(400).json({ code: 400, error: '需要 classId 和 examName' });
    let sql = `SELECT score FROM grades WHERE class_id=? AND exam_name=?`;
    const params = [classId, examName];
    if (req.user.role === 'teacher') { sql += ' AND teacher_id=?'; params.push(req.user.id); }
    const scores = db.prepare(sql).all(...params).map(r => r.score);
    if (scores.length === 0) return res.json({ code: 200, data: { count: 0 } });
    const sum = scores.reduce(function(a, b) { return a + b; }, 0);
    const avg = (sum / scores.length).toFixed(1);
    const sorted = scores.slice().sort(function(a, b) { return b - a; });
    const max = sorted[0];
    const min = sorted[sorted.length - 1];
    const passCount = scores.filter(function(s) { return s >= 60; }).length;
    res.json({
        code: 200,
        data: {
            count: scores.length, avg: parseFloat(avg), max: max, min: min,
            passRate: ((passCount / scores.length) * 100).toFixed(1)
        }
    });
});

function validateString(val, maxLen) {
    maxLen = maxLen || 5000;
    if (typeof val !== 'string') return '';
    return val.slice(0, maxLen);
}
function validateArray(val, maxItems) {
    maxItems = maxItems || 200;
    return Array.isArray(val) ? val.slice(0, maxItems) : [];
}

// 全局错误处理
app.use((err, req, res, next) => {
    console.error(`[ERROR] ${new Date().toISOString()} ${req.method} ${req.url}:`, err.message);
    if (err.message && err.message.includes('CORS')) {
        return res.status(403).json({ error: '请求来源不被允许' });
    }
    res.status(500).json({ error: '服务器内部错误' });
});

// 优先服务根目录的tutoring-management.html，如不存在则使用public目录
const mainHtmlPath = path.join(__dirname, 'tutoring-management.html');
if (fs.existsSync(mainHtmlPath)) {
    app.use(express.static(__dirname));
    app.get('*', (req, res) => {
        res.sendFile(mainHtmlPath);
    });
} else {
    app.use(express.static(path.join(__dirname, 'public')));
    app.get('*', (req, res) => {
        res.sendFile(path.join(__dirname, 'public', 'tutoring-management.html'));
    });
}

app.listen(PORT, '0.0.0.0', () => {
    const nets = require('os').networkInterfaces();
    const ips = [];
    for (const name of Object.keys(nets)) {
        for (const net of nets[name]) {
            if (net.family === 'IPv4' && !net.internal) ips.push(net.address);
        }
    }
    console.log(`🚀 AI服务运行在 http://localhost:${PORT}`);
    if (ips.length) console.log('📱 局域网访问地址: http://' + ips[0] + ':' + PORT);
});
