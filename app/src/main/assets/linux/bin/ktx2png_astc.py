#!/usr/bin/env python3
"""KTX1 ASTC -> PNG (via astcenc)
Usage: python3 ktx2png_astc.py <input.ktx> <output.png>

Falls back with error if not ASTC or astcenc not available.
"""
import struct, sys, os, subprocess, tempfile

KTX1_ID = bytes([0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A])

# ASTC GL format -> block dims
ASTC_FMT = {
    0x93B0: (4, 4), 0x93B1: (5, 4), 0x93B2: (5, 5), 0x93B3: (6, 5),
    0x93B4: (6, 6), 0x93B5: (8, 5), 0x93B6: (8, 6), 0x93B7: (8, 8),
    0x93B8: (10, 5), 0x93B9: (10, 6), 0x93BA: (10, 8), 0x93BB: (10, 10),
    0x93BC: (12, 10), 0x93BD: (12, 12),
    # SRGB8_ALPHA8 ASTC
    0x93D0: (4, 4), 0x93D1: (5, 4), 0x93D2: (5, 5), 0x93D3: (6, 5),
    0x93D4: (6, 6), 0x93D5: (8, 5), 0x93D6: (8, 6), 0x93D7: (8, 8),
    0x93D8: (10, 5), 0x93D9: (10, 6), 0x93DA: (10, 8), 0x93DB: (10, 10),
    0x93DC: (12, 10), 0x93DD: (12, 12),
}

def fail(msg):
    print(f"[ASTC] {msg}", file=sys.stderr)
    sys.exit(1)

def main():
    if len(sys.argv) != 3:
        fail("Usage: ktx2png_astc.py <input.ktx> <output.png>")

    ktx_path, out_path = sys.argv[1], sys.argv[2]
    astcenc = "/bin/astcenc"

    if not os.path.exists(astcenc):
        fail("astcenc not found")

    with open(ktx_path, "rb") as f:
        data = f.read()

    if data[:12] != KTX1_ID:
        fail("Not KTX1")

    glFmt = struct.unpack_from("<I", data, 28)[0]
    pxW = struct.unpack_from("<I", data, 36)[0]
    pxH = struct.unpack_from("<I", data, 40)[0]
    kvBytes = struct.unpack_from("<I", data, 60)[0]

    bx, by = ASTC_FMT.get(glFmt, (None, None))
    if bx is None:
        fail(f"Not ASTC format: 0x{glFmt:08X}")

    print(f"[ASTC] {pxW}x{pxH} ASTC {bx}x{by}")

    # Extract raw ASTC data
    offset = 64 + kvBytes
    imgSize = struct.unpack_from("<I", data, offset)[0]
    offset += 4
    astc_data = data[offset:offset + imgSize]

    # Write .astc file (16-byte header + raw data)
    hdr = struct.pack("<I", 0x5CA1AB13)
    hdr += struct.pack("BBB", bx, by, 1)
    hdr += struct.pack("<I", pxW)[:3]
    hdr += struct.pack("<I", pxH)[:3]
    hdr += struct.pack("<I", 1)[:3]

    tmp_dir = "/mnt"  # proot bind mount
    astc_file = os.path.join(tmp_dir, "_astc_tmp.astc")
    png_file = out_path

    with open(astc_file, "wb") as f:
        f.write(hdr)
        f.write(astc_data)

    # Run astcenc
    cmd = [astcenc, "-dl", astc_file, png_file]
    print(f"[ASTC] Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        fail(f"astcenc exit={result.returncode}")

    # Cleanup
    try:
        os.unlink(astc_file)
    except:
        pass

    print("[ASTC] OK")

if __name__ == "__main__":
    main()