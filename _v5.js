const fs=require('fs');
const g=require('child_process');
// 找 markRead / read 接口
const out=g.execSync('cmd /c findstr /n /i "read" src\\main\\java\\com\\skt\\controller\\MessageController.java').toString();
console.log(out.split('\n').slice(0,20).join('\n'));
