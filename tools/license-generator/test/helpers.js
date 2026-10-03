'use strict';
const { BinaryBitmap, HybridBinarizer, RGBLuminanceSource, QRCodeReader, DecodeHintType } = require('@zxing/library');

/**
 * Decodes a gray image (Uint8 luminance, row-major) with ZXing; returns the text.
 * PURE_BARCODE: the ZXing *JS port's* free-form detector is flaky on large synthetic symbols (~10% misses
 * even for valid codes – the C++/Java ZXing read the same images 100%), so we feed the exact module grid
 * to ZXing's real decoder (format/version info, de-masking, Reed-Solomon, byte-mode parsing).
 */
function decodeGray(lum, width, height) {
  const src = new RGBLuminanceSource(Uint8ClampedArray.from(lum), width, height);
  const hints = new Map([[DecodeHintType.PURE_BARCODE, true]]);
  return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src)), hints).getText();
}

/** Renders the module matrix like a printed/scanned code (scale px per module, 4-module quiet zone). */
function matrixToGray(qr, scale = 6, quiet = 4) {
  const n = qr.size + quiet * 2, px = n * scale, out = new Uint8Array(px * px).fill(255);
  for (let y = 0; y < px; y++) for (let x = 0; x < px; x++) {
    const my = Math.floor(y / scale) - quiet, mx = Math.floor(x / scale) - quiet;
    if (my >= 0 && my < qr.size && mx >= 0 && mx < qr.size && qr.modules[my][mx]) out[y * px + x] = 0;
  }
  return { lum: out, width: px, height: px };
}

module.exports = { decodeGray, matrixToGray };
