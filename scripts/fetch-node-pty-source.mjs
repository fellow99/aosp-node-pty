#!/usr/bin/env node
/*
 * scripts/fetch-node-pty-source.mjs —— 取构建输入（node-pty npm 源不改 + Node 头文件）
 *
 * 输出目录（默认 .dist/src）：
 *   <out>/node-pty/            解压后的 node-pty 包（含 src/unix/pty.cc、deps/spawn-*）
 *   <out>/node-pty/package.json
 *   <out>/node-headers/include/node/...   node-v<ver>-headers 解压内容
 *   <out>/node-addon-api/      node-addon-api 头（napi.h 等），来自 node-pty 的依赖版本
 *
 * node-pty 源码**不改**（规划 §3.2）；本脚本只下载、解压、记录出处与 sha，不做任何补丁。
 *
 * 用法：
 *   node scripts/fetch-node-pty-source.mjs --node 24.22.1 --node-pty 1.1.0 --out .dist/src
 *
 * 退出码：0 成功 · 1 用法/IO/下载错误
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
function argOf(name, fallback = null) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
function say(m) { process.stdout.write(`[fetch] ${m}\n`); }

const NODE_VER = argOf('--node', '24.22.1');
const PTY_VER = argOf('--node-pty', '1.1.0');
const OUT = path.resolve(ROOT, argOf('--out', '.dist/src'));

function sha256File(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}
function dl(url, dest) {
  say(`下载 ${url}`);
  // curl 在 Linux/macOS/Windows(带系统 curl) 均可用；CI runner 一定有。
  execFileSync('curl', ['--fail', '--location', '--retry', '3', '--silent', '--show-error',
    '--output', dest, url], { stdio: ['ignore', 'inherit', 'inherit'] });
}
function run(cmd, cmdArgs, opts = {}) {
  return execFileSync(cmd, cmdArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], ...opts });
}
function rmrf(p) { fs.rmSync(p, { recursive: true, force: true }); }

// 极简 tar.gz 解包：优先系统 tar；解压到 dest（dest 需已存在/被创建）。
function extract(tgz, destDir, stripComponents = 0) {
  fs.mkdirSync(destDir, { recursive: true });
  const strip = stripComponents > 0 ? ['--strip-components', String(stripComponents)] : [];
  run('tar', ['-xzf', tgz, '-C', destDir, ...strip]);
}

function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pty-fetch-'));

  // 1) node-pty npm 包（源码不改）
  const ptyTgz = path.join(tmp, 'node-pty.tgz');
  dl(`https://registry.npmjs.org/node-pty/-/node-pty-${PTY_VER}.tgz`, ptyTgz);
  const ptySha = sha256File(ptyTgz);
  say(`node-pty@${PTY_VER} tarball sha256=${ptySha}`);
  const ptyDest = path.join(OUT, 'node-pty');
  rmrf(ptyDest);
  fs.mkdirSync(ptyDest, { recursive: true });
  // npm 包内顶层目录是 "package/"，剥掉一层。
  extract(ptyTgz, ptyDest, 1);
  if (!fs.existsSync(path.join(ptyDest, 'src', 'unix', 'pty.cc'))) {
    throw new Error('解压后缺 src/unix/pty.cc —— node-pty 版本/包内容异常');
  }

  // 2) node-addon-api 头（版本跟随 node-pty 的 dependencies，固定可复现）
  const pj = JSON.parse(fs.readFileSync(path.join(ptyDest, 'package.json'), 'utf8'));
  const addonSpec = (pj.dependencies && pj.dependencies['node-addon-api']) || null;
  if (!addonSpec) throw new Error('node-pty package.json 未声明 node-addon-api 依赖');
  const addonVer = addonSpec.replace(/^[\^~]/, '');
  const addonTgz = path.join(tmp, 'node-addon-api.tgz');
  dl(`https://registry.npmjs.org/node-addon-api/-/node-addon-api-${addonVer}.tgz`, addonTgz);
  const addonDest = path.join(OUT, 'node-addon-api');
  rmrf(addonDest);
  fs.mkdirSync(addonDest, { recursive: true });
  extract(addonTgz, addonDest, 1);
  if (!fs.existsSync(path.join(addonDest, 'napi.h'))) {
    throw new Error('解压后缺 node-addon-api/napi.h');
  }
  say(`node-addon-api@${addonVer} -> ${addonDest}`);

  // 3) Node 头文件（与配对 runtime 同版本，NODE_MODULE_VERSION 对齐）
  const hdrTgz = path.join(tmp, 'node-headers.tar.gz');
  dl(`https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-headers.tar.gz`, hdrTgz);
  const hdrSha = sha256File(hdrTgz);
  say(`node-v${NODE_VER}-headers sha256=${hdrSha}`);
  const hdrRoot = path.join(OUT, 'node-headers');
  rmrf(hdrRoot);
  fs.mkdirSync(hdrRoot, { recursive: true });
  // 包内顶层 node-v<ver>-headers/，剥一层（其下即 include/）。
  extract(hdrTgz, hdrRoot, 1);
  if (!fs.existsSync(path.join(hdrRoot, 'include', 'node', 'node_api.h'))) {
    throw new Error('解压后缺 include/node/node_api.h —— Node headers 异常');
  }

  // 出处清单（供 CI 回写 lock / 排障）
  fs.writeFileSync(path.join(OUT, 'FETCH-METADATA.json'), JSON.stringify({
    node: NODE_VER,
    nodePty: PTY_VER,
    nodePtyTarballSha256: ptySha,
    nodeAddonApi: addonVer,
    nodeHeadersTarballSha256: hdrSha,
    fetchedAt: new Date().toISOString(),
  }, null, 2) + '\n');
  say(`完成。源码树：${OUT}`);
  rmrf(tmp);
}

try {
  main();
} catch (e) {
  process.stderr.write(`[fetch] 失败：${e.message}\n`);
  process.exit(1);
}
