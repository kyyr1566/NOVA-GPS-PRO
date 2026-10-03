'use strict';
/**
 * Minimal dependency-free QR Code (Model 2) encoder: byte mode, error-correction level M, versions 1-40.
 * Used only by the license generator to turn a license code into a QR image. The QR carries the license
 * code text and nothing else. (Algorithm after the public-domain-style reference by Project Nayuki;
 * output is verified by decoding with ZXing in test/qr.test.js.)
 */
const ECC_CODEWORDS_PER_BLOCK_M = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28];
const NUM_ERROR_CORRECTION_BLOCKS_M = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49];
const FORMAT_BITS_M = 0; // ECC level M = 0b00
const PENALTY_N1 = 3, PENALTY_N2 = 3, PENALTY_N3 = 40, PENALTY_N4 = 10;

const getBit = (x, i) => ((x >>> i) & 1) !== 0;

function numRawDataModules(ver) {
  let result = (16 * ver + 128) * ver + 64;
  if (ver >= 2) {
    const numAlign = Math.floor(ver / 7) + 2;
    result -= (25 * numAlign - 10) * numAlign - 55;
    if (ver >= 7) result -= 36;
  }
  return result;
}
const numDataCodewords = (ver) =>
  Math.floor(numRawDataModules(ver) / 8) - ECC_CODEWORDS_PER_BLOCK_M[ver] * NUM_ERROR_CORRECTION_BLOCKS_M[ver];

/** Max bytes of text that fit in version `ver` (level M, byte mode). */
const byteCapacity = (ver) => Math.floor((numDataCodewords(ver) * 8 - 4 - (ver <= 9 ? 8 : 16)) / 8);

function rsMultiply(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11d); z ^= ((y >>> i) & 1) * x; }
  return z;
}
function rsDivisor(degree) {
  const result = new Array(degree - 1).fill(0); result.push(1);
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = rsMultiply(result[j], root);
      if (j + 1 < result.length) result[j] ^= result[j + 1];
    }
    root = rsMultiply(root, 2);
  }
  return result;
}
function rsRemainder(data, divisor) {
  const result = divisor.map(() => 0);
  for (const b of data) {
    const factor = b ^ result.shift(); result.push(0);
    divisor.forEach((coef, i) => { result[i] ^= rsMultiply(coef, factor); });
  }
  return result;
}

function encodeDataCodewords(bytes, ver) {
  const bits = [];
  const put = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
  put(0b0100, 4); put(bytes.length, ver <= 9 ? 8 : 16);
  for (const b of bytes) put(b, 8);
  const capBits = numDataCodewords(ver) * 8;
  put(0, Math.min(4, capBits - bits.length));
  put(0, (8 - (bits.length % 8)) % 8);
  for (let pad = 0xec; bits.length < capBits; pad ^= 0xec ^ 0x11) put(pad, 8);
  const out = new Array(bits.length / 8).fill(0);
  bits.forEach((b, i) => { out[i >>> 3] |= b << (7 - (i & 7)); });
  return out;
}

function addEccAndInterleave(data, ver) {
  const numBlocks = NUM_ERROR_CORRECTION_BLOCKS_M[ver], eccLen = ECC_CODEWORDS_PER_BLOCK_M[ver];
  const raw = Math.floor(numRawDataModules(ver) / 8);
  const numShort = numBlocks - (raw % numBlocks), shortLen = Math.floor(raw / numBlocks);
  const blocks = [], div = rsDivisor(eccLen);
  for (let i = 0, k = 0; i < numBlocks; i++) {
    const dat = data.slice(k, k + shortLen - eccLen + (i < numShort ? 0 : 1));
    k += dat.length;
    const ecc = rsRemainder(dat, div);
    if (i < numShort) dat.push(0);
    blocks.push(dat.concat(ecc));
  }
  const result = [];
  for (let i = 0; i < blocks[0].length; i++) {
    blocks.forEach((block, j) => { if (i !== shortLen - eccLen || j >= numShort) result.push(block[i]); });
  }
  return result;
}

class Matrix {
  constructor(ver) {
    this.ver = ver; this.size = ver * 4 + 17;
    this.modules = Array.from({ length: this.size }, () => new Array(this.size).fill(false));
    this.isFunction = Array.from({ length: this.size }, () => new Array(this.size).fill(false));
  }
  setFn(x, y, dark) { this.modules[y][x] = dark; this.isFunction[y][x] = true; }

  drawFunctionPatterns() {
    const s = this.size;
    for (let i = 0; i < s; i++) { this.setFn(6, i, i % 2 === 0); this.setFn(i, 6, i % 2 === 0); }
    this.drawFinder(3, 3); this.drawFinder(s - 4, 3); this.drawFinder(3, s - 4);
    const pos = this.alignmentPositions(), n = pos.length;
    for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
      if (!((i === 0 && j === 0) || (i === 0 && j === n - 1) || (i === n - 1 && j === 0))) this.drawAlignment(pos[i], pos[j]);
    }
    this.drawFormatBits(0); this.drawVersion();
  }
  drawFinder(x, y) {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const dist = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
      if (xx >= 0 && xx < this.size && yy >= 0 && yy < this.size) this.setFn(xx, yy, dist !== 2 && dist !== 4);
    }
  }
  drawAlignment(x, y) {
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) this.setFn(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }
  alignmentPositions() {
    if (this.ver === 1) return [];
    const n = Math.floor(this.ver / 7) + 2;
    const step = this.ver === 32 ? 26 : Math.ceil((this.ver * 4 + 4) / (n * 2 - 2)) * 2;
    const r = [6];
    for (let pos = this.size - 7; r.length < n; pos -= step) r.splice(1, 0, pos);
    return r;
  }
  drawFormatBits(mask) {
    const data = (FORMAT_BITS_M << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412, s = this.size;
    for (let i = 0; i <= 5; i++) this.setFn(8, i, getBit(bits, i));
    this.setFn(8, 7, getBit(bits, 6)); this.setFn(8, 8, getBit(bits, 7)); this.setFn(7, 8, getBit(bits, 8));
    for (let i = 9; i < 15; i++) this.setFn(14 - i, 8, getBit(bits, i));
    for (let i = 0; i < 8; i++) this.setFn(s - 1 - i, 8, getBit(bits, i));
    for (let i = 8; i < 15; i++) this.setFn(8, s - 15 + i, getBit(bits, i));
    this.setFn(8, s - 8, true);
  }
  drawVersion() {
    if (this.ver < 7) return;
    let rem = this.ver;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const bits = (this.ver << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const color = getBit(bits, i), a = this.size - 11 + (i % 3), b = Math.floor(i / 3);
      this.setFn(a, b, color); this.setFn(b, a, color);
    }
  }
  drawCodewords(data) {
    let i = 0;
    for (let right = this.size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < this.size; vert++) for (let j = 0; j < 2; j++) {
        const x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? this.size - 1 - vert : vert;
        if (!this.isFunction[y][x] && i < data.length * 8) { this.modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7)); i++; }
      }
    }
  }
  applyMask(mask) {
    for (let y = 0; y < this.size; y++) for (let x = 0; x < this.size; x++) {
      let invert;
      switch (mask) {
        case 0: invert = (x + y) % 2 === 0; break;
        case 1: invert = y % 2 === 0; break;
        case 2: invert = x % 3 === 0; break;
        case 3: invert = (x + y) % 3 === 0; break;
        case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
        case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break;
        case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
        default: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
      }
      if (!this.isFunction[y][x] && invert) this.modules[y][x] = !this.modules[y][x];
    }
  }
  penalty() {
    const s = this.size, m = this.modules; let result = 0;
    const addHistory = (run, h) => { if (h[0] === 0) run += s; h.pop(); h.unshift(run); };
    const countPatterns = (h) => {
      const n = h[1], core = n > 0 && h[2] === n && h[3] === n * 3 && h[4] === n && h[5] === n;
      return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    };
    const terminate = (color, run, h) => { if (color) { addHistory(run, h); run = 0; } run += s; addHistory(run, h); return countPatterns(h); };
    for (const vertical of [false, true]) {
      for (let a = 0; a < s; a++) {
        let color = false, run = 0; const h = [0, 0, 0, 0, 0, 0, 0];
        for (let b = 0; b < s; b++) {
          const v = vertical ? m[b][a] : m[a][b];
          if (v === color) { run++; if (run === 5) result += PENALTY_N1; else if (run > 5) result++; }
          else { addHistory(run, h); if (!color) result += countPatterns(h) * PENALTY_N3; color = v; run = 1; }
        }
        result += terminate(color, run, h) * PENALTY_N3;
      }
    }
    for (let y = 0; y < s - 1; y++) for (let x = 0; x < s - 1; x++) {
      const c = m[y][x];
      if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) result += PENALTY_N2;
    }
    let dark = 0; for (const row of m) for (const c of row) if (c) dark++;
    const total = s * s;
    result += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * PENALTY_N4;
    return result;
  }
}

/** Encodes text (UTF-8, byte mode, level M). Returns { version, size, modules: boolean[][] (true = dark) }. */
function encode(text) {
  const bytes = Array.from(Buffer.from(text, 'utf8'));
  let ver = 1;
  while (ver <= 40 && bytes.length > byteCapacity(ver)) ver++;
  if (ver > 40) throw new Error('text too long for a QR code');
  const m = new Matrix(ver);
  m.drawFunctionPatterns();
  m.drawCodewords(addEccAndInterleave(encodeDataCodewords(bytes, ver), ver));
  let best = 0, bestPenalty = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    m.applyMask(mask); m.drawFormatBits(mask);
    const p = m.penalty();
    if (p < bestPenalty) { best = mask; bestPenalty = p; }
    m.applyMask(mask); // undo (XOR)
  }
  m.applyMask(best); m.drawFormatBits(best);
  return { version: ver, size: m.size, modules: m.modules, mask: best };
}

module.exports = { encode, byteCapacity };
