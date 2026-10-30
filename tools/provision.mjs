#!/usr/bin/env node
/*
 * tools/provision.mjs —— node-pty 投放与 ELF 门禁（模块 000-scaffold）
 *
 * 唯一事实源是仓库根的 `pty.lock.json`（版本 + 配对 Node + 大小 + SHA-256 + 门禁 + 出处）。
 * 二进制**不入库**：本工具把 CI 产物从本地文件幂等投放进模块的 jniLibs。
 *
 * 与 aosp-node-runtime/tools/provision.mjs 同口径（退出码 0/2/1、同一套极简 ELF64 解析），
 * pty 特有差异：
 *   1. 硬门禁「DT_NEEDED 必须含 libnode.so」（neededLibnode）——宿主 RTLD_LOCAL 加载引擎时，
 *      pty 里数十个 napi_* 未定义引用只有按 soname 复用已加载 libnode.so 才能解析。
 *   2. 门禁「存在未定义的 napi_ 与 node_ 符号」（undefinedNapiResolvable）——NAPI 实现由引擎
 *      在运行期提供，本件不静态编入。
 *   3. 投放**只复制 libpty_node.so**：绝不携带 libnode.so / libc++_shared.so（由 node-runtime 供给）。
 *
 * 用法：
 *   node tools/provision.mjs --check                        # 只校验已投放的产物
 *   node tools/provision.mjs --out node-pty/src/main/jniLibs --from <libpty_node.so>
 *   node tools/provision.mjs --inspect <libpty_node.so>      # 只打印门禁结果
 *   node tools/provision.mjs --node 24.22.1 --abi arm64-v8a ...
 *
 * 退出码：0 全过 · 2 门禁不合格 · 1 用法/IO 错误
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCK_PATH = path.join(ROOT, 'pty.lock.json');
const MODULE_JNILIBS = path.join('node-pty', 'src', 'main', 'jniLibs');

const args = process.argv.slice(2);
const has = (f) => args.includes(f);
function argOf(name, fallback = null) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}

function say(msg) {
  process.stdout.write(`[provision] ${msg}\n`);
}

// ── 极简 ELF64（小端）解析：只回答门禁需要的问题（与 runtime 同款）────────────

function parseElf(buf) {
  if (buf.length < 64 || buf.readUInt32BE(0) !== 0x7f454c46) throw new Error('不是 ELF 文件');
  const little = buf[5] === 1;
  if (!little) throw new Error('只支持小端 ELF（AArch64 一定是小端）');
  const u16 = (o) => buf.readUInt16LE(o);
  const u32 = (o) => buf.readUInt32LE(o);
  const u64 = (o) => Number(buf.readBigUInt64LE(o));

  const eType = u16(16);
  const eMachine = u16(18);
  const ePhoff = u64(32);
  const ePhentsize = u16(54);
  const ePhnum = u16(56);
  const eShoff = u64(40);
  const eShentsize = u16(58);
  const eShnum = u16(60);
  const eShstrndx = u16(62);

  let ptInterp = 0;
  const loadAligns = [];
  for (let i = 0; i < ePhnum; i++) {
    const o = ePhoff + i * ePhentsize;
    const pType = u32(o);
    const pAlign = u64(o + 48);
    if (pType === 3) ptInterp++;
    if (pType === 1) loadAligns.push(pAlign);
  }

  const sections = [];
  for (let i = 0; i < eShnum; i++) {
    const o = eShoff + i * eShentsize;
    sections.push({
      nameOff: u32(o),
      type: u32(o + 4),
      offset: u64(o + 24),
      size: u64(o + 32),
      link: u32(o + 40),
      entsize: u64(o + 56),
    });
  }
  const shstr = sections[eShstrndx];
  const strAt = (base, off) => {
    const s = base + off;
    let e = s;
    while (e < buf.length && buf[e] !== 0) e++;
    return buf.toString('utf8', s, e);
  };
  const byName = new Map();
  for (const s of sections) byName.set(strAt(shstr.offset, s.nameOff), s);

  const dynamic = byName.get('.dynamic');
  const dynKeys = [];
  if (dynamic) {
    for (let i = 0; i * 16 < dynamic.size; i++) {
      const o = dynamic.offset + i * 16;
      const tag = Number(buf.readBigInt64LE(o));
      const val = u64(o + 8);
      if (tag === 0) break;
      if (tag === 1 || tag === 14) {
        const nm = strAt(byName.get('.dynstr').offset, val);
        dynKeys.push(tag === 14 ? `soname:${nm}` : `needed:${nm}`);
      }
    }
  }
  const soname = dynKeys.filter((k) => k.startsWith('soname:')).map((k) => k.slice(7));
  const needed = dynKeys.filter((k) => k.startsWith('needed:')).map((k) => k.slice(7));

  // 动态符号：找出未定义（st_shndx === 0）的 napi_* / node_* / 以及 pty 自身导出入口。
  const dynsym = byName.get('.dynsym');
  const undefNapi = [];
  const undefNode = [];
  const exported = [];
  if (dynsym) {
    const strtab = sections[dynsym.link];
    for (let i = 0; i * 24 < dynsym.size; i++) {
      const o = dynsym.offset + i * 24;
      const nameOff = u32(o);
      if (nameOff === 0) continue;
      const stInfo = buf[o + 4];
      const stShndx = u16(o + 6);
      const type = stInfo & 0xf;
      const bind = stInfo >> 4;
      const name = strAt(strtab.offset, nameOff);
      const isUndef = stShndx === 0;
      if (isUndef && name.startsWith('napi_')) undefNapi.push(name);
      else if (isUndef && name.startsWith('node_')) undefNode.push(name);
      else if (!isUndef && (bind === 1 || bind === 2) && type === 2 &&
               (name.startsWith('napi_register') || name.includes('pty') || name.startsWith('_Z'))) {
        exported.push(name);
      }
    }
  }

  return { eType, eMachine, ptInterp, loadAligns, soname, needed, undefNapi, undefNode, exported };
}

function gates(buf, rel) {
  let elf;
  try {
    elf = parseElf(buf);
  } catch (e) {
    return { ok: false, results: [{ id: 'parse', ok: false, detail: e.message }] };
  }
  const sha256 = crypto.createHash('sha256').update(buf).digest('hex');
  const minAlign = elf.loadAligns.length ? Math.min(...elf.loadAligns) : 0;
  const expectSha = rel.sha256 && rel.sha256.length === 64 ? rel.sha256 : null;
  const expectSize = rel.size && rel.size > 0 ? rel.size : null;
  const r = [];
  const add = (id, ok, detail) => r.push({ id, ok, detail });

  add('① e_type = ET_DYN', elf.eType === 3, `e_type=${elf.eType}`);
  add('② PT_INTERP = 0', elf.ptInterp === 0, `PT_INTERP=${elf.ptInterp}`);
  add('③ e_machine = AArch64', elf.eMachine === 183, `e_machine=${elf.eMachine}`);
  // 本工程不可妥协项：必须登记对 libnode.so 的 DT_NEEDED。
  add('④ DT_NEEDED 含 libnode.so', elf.needed.includes('libnode.so'),
    `NEEDED=${JSON.stringify(elf.needed)}`);
  // napi_* 必须是未定义引用（由已加载引擎解析）；至少 1 个 napi_ 未定义符号。
  const napiUndefOk = elf.undefNapi.length > 0;
  add('⑤ napi_* 为未定义引用（交引擎解析）', napiUndefOk,
    `undef napi_*=${elf.undefNapi.length} 个${elf.undefNapi.length ? `（如 ${elf.undefNapi.slice(0, 3).join(', ')}）` : ''}` +
    (elf.undefNode.length ? `；undef node_*=${elf.undefNode.length} 个` : ''));
  if (expectSha) add('⑥ SHA-256 与锁一致', sha256 === expectSha, `${sha256.slice(0, 16)}… vs ${expectSha.slice(0, 16)}…`);
  else add('⑥ SHA-256（draft 无锁值，仅记录）', true, sha256);
  if (expectSize) add('⑦ 大小与锁一致', buf.length === expectSize, `${buf.length} vs ${expectSize}`);
  else add('⑦ 大小（draft 无锁值，仅记录）', true, `${buf.length}`);
  add('⑧ 16 KB 页对齐', minAlign >= 0x4000, `min LOAD align=0x${minAlign.toString(16)}（4 KB 产物在 16 KB 设备上会装不上）`);

  const allow4k = process.argv.includes('--allow-page-align-4k');
  const gate8 = r.find((x) => x.id.startsWith('⑧'));
  const hardOk = r.every((x) => (allow4k && x === gate8 ? true : x.ok));
  if (allow4k && gate8 && !gate8.ok) {
    say('⚠ --allow-page-align-4k 放行「16 KB 页对齐」：仅 bring-up，上架/16 KB 设备不合格');
  }
  return { ok: hardOk, results: r, elf, sha256 };
}

function printGates(g) {
  for (const x of g.results) say(`${x.ok ? '✔' : '✘'} ${x.id} — ${x.detail}`);
}

// ── 主流程 ──────────────────────────────────────────────────────────────────

function readLock() {
  if (!fs.existsSync(LOCK_PATH)) throw new Error(`缺 ${LOCK_PATH}`);
  return JSON.parse(fs.readFileSync(LOCK_PATH, 'utf8'));
}

function main() {
  const lock = readLock();
  const wantNode = argOf('--node') || lock.active.node;
  const wantAbi = argOf('--abi') || lock.active.abi;
  const rel = lock.releases.find((x) => x.node === wantNode && x.abi === wantAbi);
  if (!rel) {
    throw new Error(
      `pty.lock.json 里没有记录 node=${wantNode} abi=${wantAbi}；现有：` +
      lock.releases.map((x) => `${x.node}/${x.abi}`).join(', '),
    );
  }
  const active = { node: rel.node, abi: rel.abi, name: rel.name || 'libpty_node.so' };

  say(`目标 release：node=${rel.node} abi=${rel.abi}（nodeAbi=${rel.nodeAbi}）`);
  if (rel.status === 'draft') say('⚠ lock 中该 release 为 draft：sha/size 无锁值，仅记录不比对');

  const inspectPath = argOf('--inspect');
  if (inspectPath) {
    const buf = fs.readFileSync(inspectPath);
    const g = gates(buf, rel);
    printGates(g);
    say(g.ok ? '门禁：全过' : '门禁：不合格');
    process.exit(g.ok ? 0 : 2);
  }

  const outDir = argOf('--out');
  const from = argOf('--from');

  if (!outDir) {
    // --check：校验已投放产物
    const target = path.join(ROOT, MODULE_JNILIBS, active.abi, active.name);
    if (!fs.existsSync(target)) {
      say(`未投放：${target}（先跑 --out …）`);
      process.exit(2);
    }
    const buf = fs.readFileSync(target);
    const g = gates(buf, rel);
    printGates(g);
    // 纪律：jniLibs 目录里只能有 libpty_node.so，绝不能混入 libnode.so / libc++_shared.so。
    const dir = path.dirname(target);
    const siblings = fs.readdirSync(dir).filter((f) => f.endsWith('.so'));
    const illegal = siblings.filter((f) => f !== active.name);
    say(`${illegal.length === 0 ? '✔' : '✘'} jniLibs 仅含 ${active.name}（其它 .so：${illegal.length ? JSON.stringify(illegal) : '无'}）`);
    const ok = g.ok && illegal.length === 0;
    say(ok ? '门禁：全过' : '门禁：不合格');
    process.exit(ok ? 0 : 2);
  }

  if (!from) throw new Error('--out 需要配 --from <libpty_node.so>');
  const buf = fs.readFileSync(from);
  const g = gates(buf, rel);
  printGates(g);
  if (!g.ok) {
    say('门禁不合格 ⇒ 拒绝投放');
    process.exit(2);
  }

  // 投放：只复制 libpty_node.so，绝不携带引擎/C++ 运行时（由 node-runtime 提供）。
  const destDir = path.resolve(ROOT, outDir, active.abi);
  fs.mkdirSync(destDir, { recursive: true });
  const dest = path.join(destDir, active.name);
  fs.copyFileSync(from, dest);
  say(`✔ 投放（唯一原生件）：${dest}`);
  for (const banned of ['libnode.so', 'libc++_shared.so']) {
    if (fs.existsSync(path.join(destDir, banned))) {
      throw new Error(`${banned} 不得进入 pty AAR（应由 node-runtime 供给）`);
    }
  }

  // 版本元数据（供 AAR resources 使用）
  const resDir = path.join(ROOT, 'node-pty', 'src', 'main', 'resources', 'META-INF');
  fs.mkdirSync(resDir, { recursive: true });
  fs.writeFileSync(
    path.join(resDir, 'node-pty.properties'),
    [
      `node.version=${rel.node}`,
      `node.modules.abi=${rel.nodeAbi}`,
      `abi=${rel.abi}`,
      `name=${active.name}`,
      `size=${buf.length}`,
      `sha256=${g.sha256}`,
      `paired.runtime=${rel.pairedRuntime.coordinates}`,
      `provisionedAt=${new Date().toISOString()}`,
      '',
    ].join('\n'),
  );
  say('投放完成');
}

try {
  main();
} catch (e) {
  process.stderr.write(`[provision] 失败：${e.message}\n`);
  process.exit(1);
}
