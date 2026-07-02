#!/usr/bin/env python3
"""KTX -> Grayscale PNG (no palette, PVRTexTool decode only)
Usage: python3 ktx2png_raw.py <input> [output]"""

import struct, sys, os, subprocess, tempfile, zlib

# ── PVR decoder detection ──
PVR_CLI = None; PVR_LIB = None
for base in ['/home/pvr', os.path.expanduser('~/pvr')]:
    cli = os.path.join(base, 'CLI/Linux_armv8_64/PVRTexToolCLI')
    lib = os.path.join(base, 'Library/Linux_armv8_64')
    if os.path.exists(cli) and os.path.exists(lib):
        PVR_CLI = cli; PVR_LIB = lib; break

# ── KTX constants ──
KTX1_ID = bytes([0xAB,0x4B,0x54,0x58,0x20,0x31,0x31,0xBB,0x0D,0x0A,0x1A,0x0A])
GL_R11=0x9270; GL_SR11=0x9271; GL_ETC2_RGBA=0x9278; GL_SRGB_ETC2_RGBA=0x9279
GL_ETC2=0x9274; GL_SRGB_ETC2=0x9275; GL_ETC2_A1=0x9276; GL_SRGB_ETC2_A1=0x9277
FMT={0x9270:"R11_EAC",0x9271:"sR11_EAC",0x9278:"ETC2_RGBA",0x9279:"sRGB_ETC2_RGBA",
     0x9274:"ETC2",0x9275:"sRGB_ETC2",0x9276:"ETC2_A1",0x9277:"sRGB_ETC2_A1",
     0x8058:"RGBA8",0x8051:"RGB8",0x8229:"R8"}

# ── PNG I/O ──
def png_read_r8(fp):
    with open(fp,'rb') as f:
        f.read(8); w=h=None; idat=bytearray()
        while True:
            l=struct.unpack('>I',f.read(4))[0]; c=f.read(4); d=f.read(l); f.read(4)
            if c==b'IHDR': w,h=struct.unpack('>II',d[:8])
            elif c==b'IDAT': idat.extend(d)
            elif c==b'IEND': break
        raw=zlib.decompress(bytes(idat)); bpp=4; stride=w*bpp+1
        prev=bytearray(w*bpp); rows=[]
        for y in range(h):
            row=raw[y*stride:(y+1)*stride]; filt=row[0]; cur=bytearray(row[1:])
            if filt==0: pass
            elif filt==1:
                for x in range(bpp,w*bpp): cur[x]=(cur[x]+cur[x-bpp])&0xFF
            elif filt==2:
                for x in range(w*bpp): cur[x]=(cur[x]+prev[x])&0xFF
            elif filt==3:
                for x in range(w*bpp):
                    a=cur[x-bpp] if x>=bpp else 0; b=prev[x]
                    cur[x]=(cur[x]+(a+b)//2)&0xFF
            elif filt==4:
                for x in range(w*bpp):
                    a=cur[x-bpp] if x>=bpp else 0; b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
                    p=a+b-c; pa=abs(p-a); pb=abs(p-b); pc=abs(p-c)
                    pr=a if pa<=pb and pa<=pc else(b if pb<=pc else c)
                    cur[x]=(cur[x]+pr)&0xFF
            rows.append(cur); prev=cur
        r=bytearray(w*h)
        for y in range(h):
            row=rows[y]
            for x in range(w): r[y*w+x]=row[x*4]
        return w,h,bytes(r)

def png_read_rgba(fp):
    """Read PNG and return RGBA bytes"""
    with open(fp,'rb') as f:
        f.read(8); w=h=None; idat=bytearray()
        while True:
            l=struct.unpack('>I',f.read(4))[0]; c=f.read(4); d=f.read(l); f.read(4)
            if c==b'IHDR': w,h=struct.unpack('>II',d[:8])
            elif c==b'IDAT': idat.extend(d)
            elif c==b'IEND': break
        raw=zlib.decompress(bytes(idat)); bpp=4; stride=w*bpp+1
        prev=bytearray(w*bpp); rgba=bytearray(w*h*4)
        for y in range(h):
            row=raw[y*stride:(y+1)*stride]; filt=row[0]; cur=bytearray(row[1:])
            if filt==0: pass
            elif filt==1:
                for x in range(bpp,w*bpp): cur[x]=(cur[x]+cur[x-bpp])&0xFF
            elif filt==2:
                for x in range(w*bpp): cur[x]=(cur[x]+prev[x])&0xFF
            elif filt==3:
                for x in range(w*bpp):
                    a=cur[x-bpp] if x>=bpp else 0; b=prev[x]
                    cur[x]=(cur[x]+(a+b)//2)&0xFF
            elif filt==4:
                for x in range(w*bpp):
                    a=cur[x-bpp] if x>=bpp else 0; b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
                    p=a+b-c; pa=abs(p-a); pb=abs(p-b); pc=abs(p-c)
                    pr=a if pa<=pb and pa<=pc else(b if pb<=pc else c)
                    cur[x]=(cur[x]+pr)&0xFF
            rgba[y*w*4:(y+1)*w*4]=cur; prev=cur
        return w,h,bytes(rgba)

def png_write_rgba(fp, w, h, rgba):
    stride=w*4+1; raw=bytearray(stride*h)
    for y in range(h):
        raw[y*stride]=0
        raw[y*stride+1:(y+1)*stride]=rgba[y*w*4:(y+1)*w*4]
    compressed=zlib.compress(bytes(raw))
    def chk(ct,dt):
        c=ct+dt; crc=struct.pack('>I',zlib.crc32(c)&0xFFFFFFFF)
        return struct.pack('>I',len(dt))+c+crc
    ihdr=struct.pack('>IIBBBBB',w,h,8,6,0,0,0)
    with open(fp,'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n')
        f.write(chk(b'IHDR',ihdr)); f.write(chk(b'IDAT',compressed)); f.write(chk(b'IEND',b''))

# ── PVR decode ──
def decode_r11_pvr(filepath):
    env=os.environ.copy(); env['LD_LIBRARY_PATH']=PVR_LIB
    with tempfile.NamedTemporaryFile(suffix='.png',delete=False) as t: tp=t.name
    try:
        subprocess.run([PVR_CLI,'-i',filepath,'-d',tp,'-f','r8g8b8a8','-noout'],
                       env=env,capture_output=True,timeout=30,check=True)
        w,h,r=png_read_r8(tp); return list(r)
    finally:
        try: os.unlink(tp)
        except: pass

def decode_rgba_pvr(filepath):
    """PVR decode to RGBA for color formats (ETC2_RGBA etc.)"""
    env=os.environ.copy(); env['LD_LIBRARY_PATH']=PVR_LIB
    with tempfile.NamedTemporaryFile(suffix='.png',delete=False) as t: tp=t.name
    try:
        subprocess.run([PVR_CLI,'-i',filepath,'-d',tp,'-f','r8g8b8a8','-noout'],
                       env=env,capture_output=True,timeout=30,check=True)
        w,h,rgba=png_read_rgba(tp); return w,h,bytes(rgba)
    finally:
        try: os.unlink(tp)
        except: pass

# ── KTX parser ──
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
    return {'gl_type':gt,'gl_internal_format':gi,'width':pw,'height':ph,'pixel_data':mip0}

# ── Core: grayscale (no palette) ──
def convert(filepath):
    with open(filepath,'rb') as f: data=f.read()
    info=parse_ktx1(data)
    fmt=info['gl_internal_format']; w=info['width']; h=info['height']
    fn=FMT.get(fmt,f"0x{fmt:04X}")
    is_r11=fmt in(GL_R11,GL_SR11)

    if info['gl_type']!=0:
        gtype=info['gl_type']
        if gtype==0x1401:
            gf=info.get('gl_format',0)
            if gf==0x1908: return w,h,bytearray(info['pixel_data']),fn
            if gf==0x1907:
                out=bytearray(w*h*4); px=info['pixel_data']
                for i in range(w*h): out[i*4:i*4+3]=px[i*3:i*3+3]; out[i*4+3]=255
                return w,h,out,fn

    if PVR_CLI is None: raise RuntimeError("PVR not available")

    if is_r11:
        gray=decode_r11_pvr(filepath)
        rgba=bytearray(w*h*4)
        for i,g in enumerate(gray):
            rgba[i*4]=g; rgba[i*4+1]=g; rgba[i*4+2]=g; rgba[i*4+3]=255
        return w,h,bytes(rgba),fn+' [gray]'
    else:
        # Color format: decode directly
        try:
            w2,h2,rgba=decode_rgba_pvr(filepath)
            return w2,h2,bytes(rgba),fn+' [color]'
        except Exception:
            raise ValueError(f"unsupported: {fn}")

# ── CLI ──
if __name__=='__main__':
    if len(sys.argv)<2: print(__doc__); sys.exit(1)
    inp=sys.argv[1]; out=sys.argv[2] if len(sys.argv)>2 else os.path.splitext(inp)[0]+'.png'

    if os.path.isfile(inp):
        inputs=[inp]; single=True
    elif os.path.isdir(inp):
        inputs=sorted([os.path.join(inp,f) for f in os.listdir(inp) if f.lower().endswith('.ktx')])
        single=False
        if not inputs: print(f"[!] {inp}: no .ktx files"); sys.exit(1)
    else: print(f"[!] not found: {inp}"); sys.exit(1)

    if single:
        outputs=[out]
        odir=os.path.dirname(out) or '.'
    else:
        odir=out or os.path.join(os.path.dirname(inp) or '.','GrayPNG')
        os.makedirs(odir,exist_ok=True)
        outputs=[os.path.join(odir,os.path.splitext(os.path.basename(f))[0]+'.png') for f in inputs]

    print(f"KTX->Gray | {len(inputs)} files -> {odir}/\n")
    ok=fail=0
    for inf,ouf in zip(inputs,outputs):
        nm=os.path.basename(inf)
        try:
            w,h,rgba,ft=convert(inf)
            png_write_rgba(ouf,w,h,rgba); sz=os.path.getsize(ouf)//1024
            print(f"  {nm} -> OK {ft} {w}x{h} {sz}KB"); ok+=1
        except Exception as e:
            print(f"  {nm} -> FAIL: {e}"); fail+=1
    print(f"\n{ok} ok {fail} fail")
