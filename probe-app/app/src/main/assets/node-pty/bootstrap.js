// bootstrap.js —— pty 探针 PRB-05 进程内 spawn 回程（消费方 node-pty JS 封装视图）
// 由 shim (node::Start) 以 node bootstrap.js 方式执行；PTY_DIR 由 Java 物化后注入。
// 流程：require node-pty（官方 npm 包装）→ spawn sh -c 'echo PTY_ROUNDTRIP_OK' →
// 收集 PTY 输出 → 写 PTY_OUT 文件（供 Java 回读判据）。
'use strict';
const fs = require('fs');
const path = require('path');

const PTY_DIR = process.env.PTY_DIR;
const PTY_OUT = process.env.PTY_OUT;
if (!PTY_DIR || !PTY_OUT) {
  fs.writeFileSync('/data/data/org.fellow99.ptyprobe/files/prb05-err.txt',
    'missing env PTY_DIR/PTY_OUT');
  process.exit(2);
}

let pty;
try {
  // 模拟 npm 消费视图：require('node-pty') → package.json main = ./lib/index.js
  pty = require(PTY_DIR);
} catch (e) {
  fs.writeFileSync(PTY_OUT, 'FAIL-require:' + (e && e.stack || e));
  process.exit(1);
}

try {
  const term = pty.spawn('/system/bin/sh', ['-c', 'echo PTY_ROUNDTRIP_OK'], {
    name: 'xterm-256color', cols: 80, rows: 24, cwd: '/data/data/org.fellow99.ptyprobe/files',
  });
  let output = '';
  term.onData((d) => { output += d; });
  term.onExit((e) => {
    fs.writeFileSync(PTY_OUT, (e && e.exitCode === 0 ? 'PTY_ROUNDTRIP_OK' : 'FAIL-exit:' + JSON.stringify(e))
      + '\nraw:' + JSON.stringify(output));
  });
  // 兜底超时（PTY 无输出时仍能落盘）
  setTimeout(() => {
    fs.writeFileSync(PTY_OUT, (output.includes('PTY_ROUNDTRIP_OK') ? 'PTY_ROUNDTRIP_OK' : 'FAIL-timeout')
      + '\nraw:' + JSON.stringify(output));
  }, 15000);
} catch (e) {
  fs.writeFileSync(PTY_OUT, 'FAIL-spawn:' + (e && e.stack || e));
  process.exit(1);
}
