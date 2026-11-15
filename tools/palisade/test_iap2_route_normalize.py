#!/usr/bin/env python3
"""Direct packed-route bounds and synchronous wrapper ABI checks; no stock calls."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
from paths import REPO


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main(output):
    output=Path(output).resolve();assert output.is_relative_to(REPO/'private')
    output.mkdir(parents=True,exist_ok=True)
    capture=output/'capture.c'
    capture.write_text(r'''
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
static unsigned char data[8192];static const unsigned char *pointer;
static void *seen_session;static unsigned int seen_id;static int seen_length,calls,logs,bad;
int route_original(void *session,unsigned int id,const unsigned char *input,int length) {
    calls++;seen_session=session;seen_id=id;pointer=input;seen_length=length;
    if(input && length>0 && length<=(int)sizeof(data))memcpy(data,input,length);
    return -37;
}
void lane_log(const char *format,...) {
    if(strcmp(format,"PalisadeLane code=%d a=%d b=%d c=%d\n"))bad++;
    va_list args;va_start(args,format);for(int k=0;k<4;k++)(void)va_arg(args,int);va_end(args);logs++;
}
void capture_reset(void){calls=logs=bad=0;}
int capture_calls(void){return calls;} int capture_bad(void){return bad;}
uintptr_t capture_pointer(void){return (uintptr_t)pointer;}
uintptr_t capture_session(void){return (uintptr_t)seen_session;}
unsigned int capture_id(void){return seen_id;}
int capture_length(void){return seen_length;}
unsigned char *capture_data(void){return data;}
''')
    source=REPO/'wireless-carplay-aa/native/iap2-route-normalize.c'
    library=output/('capture.dylib' if sys.platform=='darwin' else 'capture.so')
    flags=['-dynamiclib'] if sys.platform=='darwin' else ['-shared','-fPIC']
    subprocess.run(['clang',*flags,'-O1','-Wall','-Wextra',str(capture),str(source),'-o',str(library)],check=True)
    lib=ctypes.CDLL(str(library))
    lib.palisade_route_expand.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_void_p,ctypes.c_int]
    lib.palisade_route_entry.argtypes=[ctypes.c_void_p,ctypes.c_uint,ctypes.c_void_p,ctypes.c_int]
    for name in ['capture_pointer','capture_session']:getattr(lib,name).restype=ctypes.c_size_t
    lib.capture_id.restype=ctypes.c_uint;lib.capture_data.restype=ctypes.c_void_p
    checks=[]
    def tlv(field,data):return struct.pack('>HH',len(data)+4,field)+data
    def selected(*values):return tlv(13,b''.join(struct.pack('>H',v) for v in values))
    component=tlv(0,b'\0\5')
    def direct(name,data,expected,capacity=4160,length=None):
        storage=ctypes.create_string_buffer(data) if data is not None else None
        before=bytes(storage) if storage is not None else None
        out=ctypes.create_string_buffer(b'Z'*4162);snapshot=bytes(out)
        n=len(data) if length is None and data is not None else length or 0
        result=lib.palisade_route_expand(storage,n,ctypes.byref(out,1),capacity)
        if isinstance(expected,bytes):
            assert result==len(expected) and bytes(out)[1:1+result]==expected,(name,result)
            assert bytes(out)[0:1]==b'Z' and bytes(out)[1+result:]==snapshot[1+result:],name+' output bounds'
        else:
            assert result==0 if expected=='unchanged' else result<0,(name,result)
            assert bytes(out)==snapshot,name+' rejected/unchanged output writes'
        assert storage is None or bytes(storage)==before,name+' input writes'
        checks.append(name)
    def forward(name,data,expected,length=None):
        storage=ctypes.create_string_buffer(data) if data is not None else None
        before=bytes(storage) if storage is not None else None
        n=len(data) if length is None and data is not None else length or 0
        lib.capture_reset();result=lib.palisade_route_entry(0x12345678,0xfedcba98,storage,n)
        assert result==-37 and lib.capture_calls()==1,name+' exact synchronous return/call count'
        assert lib.capture_session()==0x12345678 and lib.capture_id()==0xfedcba98,name+' opaque ABI'
        want=n if expected is None else len(expected)
        assert lib.capture_length()==want,name+' forwarded length'
        if expected is None:
            assert lib.capture_pointer()==(ctypes.addressof(storage) if storage is not None else 0),name+' passthrough pointer'
            if data is not None and 0<n<=len(data):assert ctypes.string_at(lib.capture_data(),n)==data[:n],name+' passthrough data'
        else:
            assert lib.capture_pointer()!=ctypes.addressof(storage),name+' copied repair'
            assert ctypes.string_at(lib.capture_data(),want)==expected,name+' copied data'
        assert lib.capture_bad()==0 and (storage is None or bytes(storage)==before),name+' privacy/input'
        checks.append(name)
    packed=component+selected(2,3);expanded=component+selected(2)+selected(3)
    direct('actual selected pair expansion',packed,expanded)
    direct('three indices',selected(1,2,3),selected(1)+selected(2)+selected(3))
    direct('mixed repeated and packed order',selected(1)+selected(2,3)+selected(4),b''.join(selected(k) for k in range(1,5)))
    other=tlv(3,b'PRIVATE_ROAD')+tlv(19,b'PRIVATE_APP')+tlv(99,b'unknown\0bytes')
    direct('all other fields preserved byte exact',other+packed,other+expanded)
    direct('scalar list unchanged',component+selected(2),'unchanged')
    direct('repeated scalar list unchanged',expanded,'unchanged')
    direct('missing selection unchanged',other,'unchanged')
    for name,data in [('empty selected unsupported',selected()),('odd selected unsupported',tlv(13,b'\0\2\xff')),
                      ('truncated header',b'\0\x06\0'),('zero TLV length',b'\0\0\0\x0d'),
                      ('TLV overrun',b'\0\x08\0\x0d'),('malformed tail after valid pair',packed+b'\0')]:
        direct(name,data,'reject');forward(name+' forwards original',data,None)
    direct('exact output capacity',packed,expanded,len(expanded))
    direct('short output capacity leaves output unchanged',packed,'reject',len(expanded)-1)
    direct('sixteen selected values',selected(*range(16)),b''.join(selected(k) for k in range(16)))
    direct('seventeen values unsupported',selected(*range(17)),'reject')
    direct('total cap includes scalar items',selected(*range(16))+selected(16),'reject')
    near=tlv(99,b'x'*4050)+selected(*range(16))
    assert len(near)==4090
    direct('near maximum payload grows within copy buffer',near,tlv(99,b'x'*4050)+b''.join(selected(k) for k in range(16)))
    direct('oversized input unsupported',b'x'*4097,'reject')
    direct('negative input length',packed,'reject',length=-1)
    direct('null input',None,'reject',length=10)
    direct('zero capacity',packed,'reject',capacity=0)
    out=ctypes.create_string_buffer(packed)
    assert lib.palisade_route_expand(out,len(packed),None,4160)<0;checks.append('null output')
    for offset in [0,1,-1]:
        shared=ctypes.create_string_buffer(b'Z'*32+packed+b'Z'*100)
        before=bytes(shared);start=ctypes.addressof(shared)+32
        assert lib.palisade_route_expand(start,len(packed),start+offset,4160)<0
        assert bytes(shared)==before;checks.append('overlapping output rejected '+str(offset))
    forward('packed wrapper uses live local copy',packed,expanded)
    forward('scalar wrapper preserves pointer',component+selected(2),None)
    forward('missing selector preserves pointer',other,None)
    forward('null pointer forwards original',None,None,length=10)
    forward('oversized input forwards original',b'x'*4097,None)
    proof={'passed':True,'checks':len(checks),'cases':checks,'input_unchanged':True,
           'rejected_output_unchanged':True,'opaque_return_and_session_id_preserved':True,
           'mock_forwarding_only':True,'validation_sources':{str(p.relative_to(REPO)):sha(p) for p in [Path(__file__).resolve(),source]}}
    (output/'route-normalize-helper-validation.json').write_text(json.dumps(proof,indent=2)+'\n')
    print('PASS',len(checks),'route normalization/ABI checks; original input unchanged')
    return proof


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output',type=Path,default=REPO/'private/route-normalize-helper-test')
    main(ap.parse_args().output)
