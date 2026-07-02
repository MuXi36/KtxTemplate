#!/usr/bin/env python3
"""
KTX -> PNG (Sky: Children of the Light texture tool)
PVRTexTool decode -> warm-red palette -> denoise -> PNG
(fallback: texture2ddecoder -> pure Python)

Usage:
  python3 ktx2png.py <input> [output]
    <input>    .ktx file or directory
    [output]   output PNG file or directory (default: same dir)
"""

import struct, sys, os, json, subprocess, tempfile, zlib, bisect, array
# Pure Python image I/O — no numpy, no PIL

def png_read_r8(filepath):
    """Read a PNG file and return (width, height, r_channel_bytes)"""
    with open(filepath, 'rb') as f:
        sig = f.read(8)
        if sig[:8] != b'\x89PNG\r\n\x1a\n':
            raise ValueError("Not a PNG file")
        w = h = None
        idat = bytearray()
        while True:
            length = struct.unpack('>I', f.read(4))[0]
            ctype = f.read(4)
            data = f.read(length)
            crc = f.read(4)
            if ctype == b'IHDR':
                w, h = struct.unpack('>II', data[:8])
            elif ctype == b'IDAT':
                idat.extend(data)
            elif ctype == b'IEND':
                break
        if w is None or h is None:
            raise ValueError("No IHDR in PNG")
        raw = zlib.decompress(bytes(idat))
        bpp = 4  # RGBA
        stride = w * bpp + 1
        if len(raw) != stride * h:
            raise ValueError(f"Unexpected raw size: {len(raw)} != {stride}*{h}")
        # Unfilter
        prev = bytearray(w * bpp)
        rows = []
        for y in range(h):
            row_raw = raw[y*stride : (y+1)*stride]
            filt = row_raw[0]
            cur = bytearray(row_raw[1:])
            if filt == 0:
                pass  # None
            elif filt == 1:  # Sub
                for x in range(bpp, w*bpp):
                    cur[x] = (cur[x] + cur[x - bpp]) & 0xFF
            elif filt == 2:  # Up
                for x in range(w*bpp):
                    cur[x] = (cur[x] + prev[x]) & 0xFF
            elif filt == 3:  # Average
                for x in range(w*bpp):
                    a = cur[x - bpp] if x >= bpp else 0
                    b = prev[x]
                    cur[x] = (cur[x] + (a + b) // 2) & 0xFF
            elif filt == 4:  # Paeth
                for x in range(w*bpp):
                    a = cur[x - bpp] if x >= bpp else 0
                    b = prev[x]
                    c = prev[x - bpp] if x >= bpp else 0
                    p = a + b - c
                    pa = abs(p - a); pb = abs(p - b); pc = abs(p - c)
                    pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                    cur[x] = (cur[x] + pr) & 0xFF
            else:
                raise ValueError(f"Unsupported filter: {filt}")
            rows.append(cur)
            prev = cur
        # Extract R channel
        r = bytearray(w * h)
        for y in range(h):
            row = rows[y]
            for x in range(w):
                r[y*w + x] = row[x*4]
        return w, h, bytes(r)

def png_write_rgba(filepath, w, h, rgba):
    """Write RGBA bytes as PNG using zlib"""
    # Build raw image data (filter byte 0 per row + RGBA pixels)
    stride = w * 4 + 1
    raw = bytearray(stride * h)
    for y in range(h):
        raw[y*stride] = 0  # filter: None
        raw[y*stride+1 : (y+1)*stride] = rgba[y*w*4 : (y+1)*w*4]
    compressed = zlib.compress(bytes(raw))
    # PNG chunks
    def chunk(ctype, data):
        c = ctype + data
        crc = struct.pack('>I', zlib.crc32(c) & 0xFFFFFFFF)
        return struct.pack('>I', len(data)) + c + crc
    ihdr = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)  # 8bit RGBA
    with open(filepath, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chunk(b'IHDR', ihdr))
        f.write(chunk(b'IDAT', compressed))
        f.write(chunk(b'IEND', b''))

# ---- Decoder detection ----
PVR_CLI = None; PVR_LIB = None
for pvr_base in ['/home/pvr', os.path.expanduser('~/pvr')]:
    cli = os.path.join(pvr_base, 'CLI/Linux_armv8_64/PVRTexToolCLI')
    lib = os.path.join(pvr_base, 'Library/Linux_armv8_64')
    if os.path.exists(cli) and os.path.exists(lib):
        PVR_CLI = cli; PVR_LIB = lib; break
HAS_PVR = PVR_CLI is not None

try:
    import texture2ddecoder as _t2d
    HAS_T2D = True
except ImportError:
    HAS_T2D = False; _t2d = None

# ---- Palette ----
PALETTE = None
def load_palette():
    global PALETTE
    if PALETTE is not None: return PALETTE
    for p in [os.path.join(os.path.dirname(os.path.abspath(__file__)), '颜色调色板.json'),
              '颜色调色板.json']:
        if os.path.exists(p):
            data = json.load(open(p))
            PALETTE = sorted([(e['lum'], e['r'], e['g'], e['b'], e['a']) for e in data], key=lambda x: x[0])
            return PALETTE
    return None

def apply_palette(gray, alpha_cut=15):
    """Apply 182-step palette to grayscale values. Pure Python."""
    p = load_palette()
    n = len(gray)
    rgba = bytearray(n * 4)
    if p is None:
        for i in range(n):
            g = gray[i]
            rgba[i*4]=g; rgba[i*4+1]=g; rgba[i*4+2]=g; rgba[i*4+3]=255
        return rgba
    lums = [x[0] for x in p]
    cols = [(x[1], x[2], x[3], x[4]) for x in p]
    min_lum = lums[0]
    for i in range(n):
        g = gray[i]
        if g < min_lum:
            continue  # leave as 0,0,0,0
        idx = bisect.bisect_left(lums, g)
        if idx >= len(p): idx = len(p) - 1
        r, gv, b, a = cols[idx]
        if a <= alpha_cut:
            continue  # skip near-transparent entries → clean background
        rgba[i*4]=r; rgba[i*4+1]=gv; rgba[i*4+2]=b; rgba[i*4+3]=a
    return rgba

# ---- KTX constants ----
KTX1_ID = bytes([0xAB,0x4B,0x54,0x58,0x20,0x31,0x31,0xBB,0x0D,0x0A,0x1A,0x0A])
GL_R11 = 0x9270; GL_SR11 = 0x9271; GL_RG11 = 0x9272; GL_SRG11 = 0x9273
GL_ETC2 = 0x9274; GL_SRGB_ETC2 = 0x9275
GL_ETC2_A1 = 0x9276; GL_SRGB_ETC2_A1 = 0x9277
GL_ETC2_RGBA = 0x9278; GL_SRGB_ETC2_RGBA = 0x9279
GL_ETC1 = 0x8D64
FMT = {0x8D64:"ETC1",0x9274:"ETC2",0x9275:"sRGB_ETC2",0x9276:"ETC2_A1",0x9277:"sRGB_ETC2_A1",
       0x9278:"ETC2_RGBA",0x9279:"sRGB_ETC2_RGBA",0x9270:"R11_EAC",0x9271:"sR11_EAC",
       0x9272:"RG11_EAC",0x9273:"sRG11_EAC",0x8058:"RGBA8",0x8051:"RGB8",0x8229:"R8"}

# ---- PVRTexTool decoder ----
def decode_r11_pvr(filepath):
    env = os.environ.copy(); env['LD_LIBRARY_PATH'] = PVR_LIB
    with tempfile.NamedTemporaryFile(suffix='.png', delete=False) as tmp:
        tmp_path = tmp.name
    try:
        subprocess.run([PVR_CLI, '-i', filepath, '-d', tmp_path, '-f', 'r8g8b8a8', '-noout'],
                       env=env, capture_output=True, timeout=30, check=True)
        w, h, r = png_read_r8(tmp_path)
        return list(r)  # gray values
    finally:
        try: os.unlink(tmp_path)
        except: pass

# ---- Pure Python ETC2/EAC decoder (fallback) ----
ETM = [[2,8,-2,-8],[5,17,-5,-17],[9,29,-9,-29],[13,42,-13,-42],
       [18,60,-18,-60],[24,80,-24,-80],[33,106,-33,-106],[47,183,-47,-183]]
EACM = [[-3,-6,-9,-15,2,5,8,14],[-3,-7,-10,-13,2,6,9,12],[-2,-5,-8,-13,1,4,7,12],
        [-2,-4,-6,-13,1,3,5,12],[-3,-6,-8,-12,2,5,7,11],[-3,-7,-9,-11,2,6,8,10],
        [-4,-7,-8,-11,3,6,7,10],[-3,-5,-8,-11,2,4,7,10],[-2,-6,-8,-10,1,5,7,9],
        [-2,-5,-8,-10,1,4,7,9],[-2,-4,-8,-10,1,3,7,9],[-2,-5,-7,-10,1,4,6,9],
        [-3,-4,-7,-10,2,3,6,9],[-1,-2,-3,-10,0,1,2,9],[-4,-6,-8,-9,3,5,7,8],
        [-3,-5,-7,-9,2,4,6,8]]
def cl(v): return max(0,min(255,v))
def ex4(v): return (v<<4)|v
def ex5(v): return (v<<3)|(v>>2)
def sg3(v): return v-8 if v&4 else v

def dec_etc2_block(b):
    if len(b)<8: return bytearray(48)
    hi=struct.unpack('<I',b[:4])[0]; lo=struct.unpack('>I',b[4:8])[0]
    df=hi>>31&1; fl=hi>>30&1; cw2=hi>>27&7; cw1=hi>>24&7
    if df==0:
        c1=(ex4(hi&0xF),ex4(hi>>4&0xF),ex4(hi>>8&0xF))
        c2=(ex4(hi>>12&0xF),ex4(hi>>16&0xF),ex4(hi>>20&0xF))
    else:
        dr=sg3(hi>>15&7); dg=sg3(hi>>18&7); db=sg3(hi>>21&7)
        c1=(ex5(hi&0x1F),ex5(hi>>5&0x1F),ex5(hi>>10&0x1F))
        c2=(cl(ex5((hi&0x1F)+dr)),cl(ex5((hi>>5&0x1F)+dg)),cl(ex5((hi>>10&0x1F)+db)))
    t1,t2=ETM[cw1],ETM[cw2]; px=bytearray(48)
    for py in range(4):
        for px_ in range(4):
            pi=py*4+px_; uc2=(py>=2) if fl==0 else (px_>=2)
            idx=((lo>>(pi+16)&1)<<1)|(lo>>pi&1)
            bse=c2 if uc2 else c1; mod=(t2 if uc2 else t1)[idx]
            px[pi*3]=cl(bse[0]+mod); px[pi*3+1]=cl(bse[1]+mod); px[pi*3+2]=cl(bse[2]+mod)
    return px

def dec_eac_block_le(b):
    """EAC block decode — little-endian (most KTX files)"""
    if len(b)<8: return [0]*16
    hi=struct.unpack('<I',b[:4])[0]; lo=struct.unpack('<I',b[4:8])[0]
    base=hi>>24&0xFF; mul=hi>>20&0xF; tidx=hi>>16&0xF
    return [cl(base+EACM[tidx][( ((hi&0xFFFF)<<32|lo) >>(i*3))&7]*(mul+1)) for i in range(16)]

def dec_eac_block_be(b):
    """EAC block decode — big-endian (rare KTX files)"""
    if len(b)<8: return [0]*16
    hi=struct.unpack('>I',b[:4])[0]; lo=struct.unpack('>I',b[4:8])[0]
    base=hi>>24&0xFF; mul=hi>>20&0xF; tidx=hi>>16&0xF
    return [cl(base+EACM[tidx][( ((hi&0xFFFF)<<32|lo) >>(i*3))&7]*(mul+1)) for i in range(16)]

# Keep old name for backward compat, now delegates to correct endian
dec_eac_block = dec_eac_block_be  # legacy: big-endian

def dec_r11_py(d,w,h,endian='little'):
    """Pure Python R11_EAC decoder. endian='little' or 'big'"""
    dec = dec_eac_block_le if endian == 'little' else dec_eac_block_be
    bx=(w+3)//4; by=(h+3)//4; gray=bytearray(w*h)
    for y in range(by):
        for x in range(bx):
            al=dec(d[(y*bx+x)*8:][:8])
            for py in range(4):
                for px_ in range(4):
                    cx,cy=x*4+px_,y*4+py
                    if cx>=w or cy>=h: continue
                    gray[cy*w+cx]=al[py*4+px_]
    return gray

# ---- Edge-aware grayscale refinement (fills edge gradients) ----
def refine_gray(gray, w, h, radius=2, boost=10):
    """Dilate + boost edge pixels to recover soft gradients.
    radius=2 + boost=10 was calibrated against 精致.png reference."""
    # Pass 1: dilate (max filter) - fill missing edge pixels
    tmp = bytearray(w * h)
    for y in range(h):
        for x in range(w):
            mx = 0
            for dy in range(-radius, radius + 1):
                for dx in range(-radius, radius + 1):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h:
                        v = gray[ny * w + nx]
                        if v > mx: mx = v
            tmp[y * w + x] = mx
    # Pass 2: boost
    for i in range(w * h):
        v = tmp[i] + boost
        gray[i] = v if v < 256 else 255

def dec_etc2_rgb(d,w,h):
    bx=(w+3)//4; by=(h+3)//4; px=bytearray(w*h*4)
    for y in range(by):
        for x in range(bx):
            rgb=dec_etc2_block(d[(y*bx+x)*8:][:8])
            for py in range(4):
                for px_ in range(4):
                    cx,cy=x*4+px_,y*4+py
                    if cx>=w or cy>=h: continue
                    dst=(cy*w+cx)*4; src=(py*4+px_)*3
                    px[dst:dst+3]=rgb[src:src+3]; px[dst+3]=255
    return px

def bgra2rgba(bgra):
    """BGRA -> RGBA, pure Python"""
    out = bytearray(len(bgra))
    for i in range(0, len(bgra), 4):
        out[i]   = bgra[i+2]  # B->R
        out[i+1] = bgra[i+1]  # G->G
        out[i+2] = bgra[i]    # R->B
        out[i+3] = bgra[i+3]  # A->A
    return out

# ---- KTX parser ----
def parse_ktx1(data):
    if len(data)<64 or data[:12]!=KTX1_ID: raise ValueError("not KTX1")
    off=12; end='<' if struct.unpack_from('<I',data,off)[0]==0x04030201 else '>'; off+=4
    gt=struct.unpack_from(end+'I',data,off)[0]; off+=4; off+=4
    _=struct.unpack_from(end+'I',data,off)[0]; off+=4
    gi=struct.unpack_from(end+'I',data,off)[0]; off+=4
    _=struct.unpack_from(end+'I',data,off)[0]; off+=4
    pw=struct.unpack_from(end+'I',data,off)[0]; off+=4
    ph=struct.unpack_from(end+'I',data,off)[0]; off+=4
    off+=12
    nm=max(1,struct.unpack_from(end+'I',data,off)[0]); off+=4
    bkv=struct.unpack_from(end+'I',data,off)[0]; off+=4+bkv
    for m in range(nm):
        sz=struct.unpack_from(end+'I',data,off)[0]; off+=4
        if m==0: mip0=data[off:off+sz]
        off+=sz
    return {'gl_type':gt,'gl_internal_format':gi,'width':pw,'height':ph,
            'pixel_data':mip0,'endian':'little' if end=='<' else 'big'}

# ---- Core conversion ----
def gray_to_rgba(gray):
    """Convert grayscale bytes to RGBA (R=G=B=gray, A=255)"""
    n = len(gray)
    rgba = bytearray(n * 4)
    for i in range(n):
        v = gray[i]
        rgba[i*4] = v; rgba[i*4+1] = v; rgba[i*4+2] = v; rgba[i*4+3] = 255
    return bytes(rgba)

def ktx_to_pixels(filepath, refine=False, gray_only=False):
    with open(filepath,'rb') as f: data=f.read()
    info=parse_ktx1(data)
    fmt=info['gl_internal_format']; w=info['width']; h=info['height']; px=info['pixel_data']
    fn=FMT.get(fmt,f"0x{fmt:04X}"); is_r11=fmt in (GL_R11,GL_SR11)

    if info['gl_type']!=0:
        gtype=info['gl_type']
        if gtype==0x1401:
            gf=info.get('gl_format',0)
            if gf==0x1908: return w,h,bytearray(px),fn
            if gf==0x1907:
                out=bytearray(w*h*4)
                for i in range(w*h): out[i*4:i*4+3]=px[i*3:i*3+3]; out[i*4+3]=255
                return w,h,out,fn

    # Compressed: PVR > Python
    got_engine = None; gray = None

    if is_r11 and HAS_PVR:
        gray = decode_r11_pvr(filepath); got_engine = 'pvr'
    if is_r11 and gray is None:
        gray = dec_r11_py(px, w, h, endian=info.get('endian','little')); got_engine = 'py'

    if is_r11 and gray is not None:
        if refine:
            refine_gray(gray, w, h)
        if gray_only:
            out = gray_to_rgba(gray)
        else:
            out = apply_palette(gray)
        return w, h, bytes(out), fn+' ['+got_engine+']'

    raise ValueError(f"unsupported: {fn}")

def ktx_to_pixels_all(filepath, refine=False):
    """Try all available engines, return list of (engine_name, w, h, rgba_bytes)"""
    with open(filepath,'rb') as f: data=f.read()
    info=parse_ktx1(data)
    fmt=info['gl_internal_format']; w=info['width']; h=info['height']; px=info['pixel_data']
    fn=FMT.get(fmt,f"0x{fmt:04X}"); is_r11=fmt in (GL_R11,GL_SR11)
    if not is_r11:
        try:
            w2, h2, rgba, tag = ktx_to_pixels(filepath, refine)
            return [('ktx', w2, h2, rgba)]
        except Exception as e:
            print(f"  [ktx] FAIL: {e}", file=sys.stderr)
            return []
    results = []
    # 1) pure Python (always)
    try:
        gray = dec_r11_py(px,w,h)
        if refine: refine_gray(gray, w, h)
        results.append(('py', w, h, bytes(apply_palette(gray))))
    except Exception as e:
        print(f"  [py] FAIL: {e}", file=sys.stderr)
    # 2) PVR
    if HAS_PVR:
        try:
            gray = decode_r11_pvr(filepath)
            results.append(('pvr', w, h, bytes(apply_palette(gray))))
        except Exception as e:
            print(f"  [pvr] FAIL: {e}", file=sys.stderr)
    # 3) t2d
    if HAS_T2D:
        try:
            bgra=_t2d.decode_eacr(px,w,h); rgba=bgra2rgba(bgra)
            gray = rgba[0::4]
            results.append(('t2d', w, h, bytes(apply_palette(gray))))
        except Exception as e:
            print(f"  [t2d] FAIL: {e}", file=sys.stderr)
    return results

# ---- CLI ----
def main():
    if len(sys.argv)<2: print(__doc__); sys.exit(1)
    all_engines = '--all' in sys.argv
    refine = '--fine' in sys.argv
    gray_only = '--gray' in sys.argv
    args = [a for a in sys.argv[1:] if a not in ('--all', '--fine', '--gray')]
    inp = args[0]; out = args[1] if len(args) > 1 else None

    if os.path.isfile(inp):
        inputs=[inp]; single=True
    elif os.path.isdir(inp):
        inputs=sorted([os.path.join(inp,f) for f in os.listdir(inp) if f.lower().endswith('.ktx')])
        single=False
        if not inputs: print(f"[!] {inp}: no .ktx files"); sys.exit(1)
    else: print(f"[!] not found: {inp}"); sys.exit(1)

    if single:
        outputs=[out] if out else [os.path.splitext(inp)[0]+'.png']
        odir=os.path.dirname(outputs[0]) or '.'
    else:
        odir=out or os.path.join(os.path.dirname(inp) or '.','PNG')
        os.makedirs(odir,exist_ok=True)
        outputs=[os.path.join(odir,os.path.splitext(os.path.basename(f))[0]+'.png') for f in inputs]

    if all_engines and single:
        print(f"KTX->PNG | --all engines | {len(inputs)} files -> {odir}/\n")
        ok=fail=0
        for inp_f,out_f in zip(inputs, outputs):
            name=os.path.basename(inp_f)
            base_out = os.path.splitext(out_f)[0]
            try:
                results = ktx_to_pixels_all(inp_f, refine)
                for eng, w, h, rgba in results:
                    fname = f"{base_out}_{eng}.png"
                    png_write_rgba(fname, w, h, rgba)
                    size=os.path.getsize(fname)//1024
                    print(f"  {name} -> [{eng}] {w}x{h} {size}KB -> {os.path.basename(fname)}")
                # Also write primary (first engine) to main output path
                if results:
                    eng, w, h, rgba = results[0]
                    png_write_rgba(out_f, w, h, rgba)
                ok+=1
            except Exception as e:
                print(f"  {name} -> FAIL: {e}"); fail+=1
        print(f"\n{ok} ok {fail} fail")
        return

    engines = []
    if HAS_PVR: engines.append('PVR')
    if HAS_T2D: engines.append('t2d')
    if not engines: engines.append('py')
    mode = 'fine' if refine else 'std'
    print(f"KTX->PNG | engines: {'>'.join(engines)} | mode={mode} | {len(inputs)} files -> {odir}/\n")
    ok=fail=0
    for inp_f,out_f in zip(inputs,outputs):
        name=os.path.basename(inp_f)
        try:
            w,h,rgba,fmt=ktx_to_pixels(inp_f, refine, gray_only)
            png_write_rgba(out_f, w, h, rgba)
            size=os.path.getsize(out_f)//1024
            print(f"  {name} -> OK {fmt} {w}x{h} {size}KB"); ok+=1
        except Exception as e:
            print(f"  {name} -> FAIL: {e}"); fail+=1
    print(f"\n{ok} ok {fail} fail")

if __name__=='__main__': main()