'use strict';
const zlib = require('node:zlib');

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** Black-on-white 8-bit grayscale PNG with a 4-module quiet zone. */
function qrToPng(qr, scale = 8, quiet = 4) {
  const n = qr.size + quiet * 2, px = n * scale;
  const raw = Buffer.alloc((px + 1) * px, 0xff);
  for (let y = 0; y < px; y++) {
    raw[y * (px + 1)] = 0; // filter: none
    const my = Math.floor(y / scale) - quiet;
    for (let x = 0; x < px; x++) {
      const mx = Math.floor(x / scale) - quiet;
      if (my >= 0 && my < qr.size && mx >= 0 && mx < qr.size && qr.modules[my][mx]) raw[y * (px + 1) + 1 + x] = 0;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(px, 0); ihdr.writeUInt32BE(px, 4); ihdr[8] = 8; ihdr[9] = 0; // 8-bit grayscale
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Compact terminal rendering (two rows per text line). */
function qrToAscii(qr, quiet = 2) {
  const at = (x, y) => y >= 0 && y < qr.size && x >= 0 && x < qr.size && qr.modules[y][x];
  const lines = [];
  for (let y = -quiet; y < qr.size + quiet; y += 2) {
    let line = '';
    for (let x = -quiet; x < qr.size + quiet; x++) {
      const t = at(x, y), b = at(x, y + 1);
      line += t && b ? '█' : t ? '▀' : b ? '▄' : ' ';
    }
    lines.push(line);
  }
  return lines.join('\n');
}

module.exports = { qrToPng, qrToAscii };
