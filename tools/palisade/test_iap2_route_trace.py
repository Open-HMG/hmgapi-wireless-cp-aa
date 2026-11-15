#!/usr/bin/env python3
"""Exercise malformed route diagnostic inputs without calling the stock SDK."""
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
#include <string.h>
static int rows[64][4], count, unexpected;
void lane_log(const char *format, ...) {
    if(strcmp(format,"PalisadeLane code=%d a=%d b=%d c=%d\n") || count==64) { unexpected++;return; }
    va_list args;va_start(args,format);
    for(int k=0;k<4;k++)rows[count][k]=va_arg(args,int);
    va_end(args);count++;
}
void *lane_context(void *x) { (void)x;unexpected++;return 0; }
void *lane_allocate(int x,int y) { (void)x;(void)y;unexpected++;return 0; }
void lane_notify(void *x,int a,int b,int c,void *p) { (void)x;(void)a;(void)b;(void)c;(void)p;unexpected++; }
void capture_reset(void) { count=unexpected=0; }
int capture_count(void) { return count; }
int capture_bad(void) { return unexpected; }
int capture_word(int i,int k) { return rows[i][k]; }
''')
    source=REPO/'wireless-carplay-aa/native/iap2-lane-decoder.c'
    library=output/('capture.dylib' if sys.platform=='darwin' else 'capture.so')
    flags=['-dynamiclib'] if sys.platform=='darwin' else ['-shared','-fPIC']
    subprocess.run(['clang',*flags,'-O1','-Wall','-Wextra',str(capture),str(source),'-o',str(library)],check=True)
    lib=ctypes.CDLL(str(library));lib.palisade_route_trace.argtypes=[ctypes.c_void_p,ctypes.c_int]
    lib.capture_word.argtypes=[ctypes.c_int,ctypes.c_int]
    checked=[]
    def call(name,data,expected,length=None):
        lib.capture_reset();storage=ctypes.create_string_buffer(data) if data is not None else None
        before=bytes(storage) if storage is not None else None
        lib.palisade_route_trace(storage,len(data) if length is None and data is not None else length or 0)
        rows=[[lib.capture_word(i,k) for k in range(4)] for i in range(lib.capture_count())]
        assert rows==expected,(name,rows,expected)
        assert lib.capture_bad()==0,'No nonnumeric logging, allocation or forwarding is allowed'
        assert storage is None or bytes(storage)==before,'Diagnostic changed input'
        checked.append(name)
    def tlv(field,data):return struct.pack('>HH',len(data)+4,field)+data
    def frame(payload=b'',kind=0x5201):return b'@@'+struct.pack('>HH',len(payload)+6,kind)+payload
    def selected(*values):return tlv(13,b''.join(struct.pack('>H',v) for v in values))
    component=tlv(0,b'\0\5')
    def summary(payload,seen,component_id=5):return [6,len(payload)+6,component_id,seen]
    call('null pointer',None,[],4096)
    for n in range(6):call('short packet '+str(n),b'@'*n,[])
    call('oversized packet',frame(),[],4097)
    call('wrong signature',b'!!'+frame()[2:],[])
    call('non-route message',frame(selected(10,20),0x5204),[])
    call('invalid declared length',b'@@\0\5\x52\1',[])
    call('incomplete declared packet',b'@@\0\x0a\x52\1',[])
    call('empty route packet',frame(),[[6,6,-1,0]])
    payload=component+selected(10,20)
    call('packed pair',frame(payload),[[5,4,10,20],summary(payload,2)])
    payload=component+selected(10)+selected(20)
    call('repeated pair',frame(payload),[[5,2,10,-1],[5,2,20,-1],summary(payload,3)])
    payload=component+selected()
    call('empty selected field',frame(payload),[[5,0,-1,-1],summary(payload,2)])
    payload=component+tlv(13,b'\0\x0a\xff')
    call('odd selected field',frame(payload),[[5,3,10,-1],summary(payload,2)])
    payload=component+tlv(3,b'PRIVATE_ROAD')+tlv(19,b'PRIVATE_PHONE')+tlv(99,b'PRIVATE_OTHER')+selected(10,20)
    call('only numeric selected metadata is logged',frame(payload),[[5,4,10,20],summary(payload,5)])
    payload=component+tlv(99,b'PRIVATE')*20+selected(10,20)
    call('selection after first sixteen TLVs',frame(payload),[[5,4,10,20],summary(payload,22)])
    payload=component+b''.join(selected(k) for k in range(20))
    call('selection log cap',frame(payload),[[5,2,k,-1] for k in range(16)]+[summary(payload,21),[7,2,4,21]])
    for label,bad in [('partial TLV header',b'\0\x06\0'),('zero TLV length',b'\0\0\0\x0d'),('overrun TLV length',b'\0\x08\0\x0d')]:
        call(label,frame(bad),[[6,len(bad)+6,-1,0],[7,1,len(bad),0]])
    payload=tlv(99,b'x'*4086)
    call('maximum bounded packet',frame(payload),[[6,4096,-1,1]])
    call('bytes outside declared packet are ignored',frame()+b'PRIVATE',[[6,6,-1,0]])
    proof={'passed':True,'checks':len(checked),'cases':checked,'input_unchanged':True,
           'allocation_or_forwarding_calls':0,'nonnumeric_logs':0,
           'malformed_inputs_forwarded_to_stock':False,
           'validation_sources':{str(p.relative_to(REPO)):sha(p) for p in [Path(__file__).resolve(),source]}}
    (output/'route-trace-helper-validation.json').write_text(json.dumps(proof,indent=2)+'\n')
    print('PASS',len(checked),'direct route trace checks; no forwarding, writes or nonnumeric logs')
    return proof


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output',type=Path,default=REPO/'private/route-trace-helper-test')
    main(ap.parse_args().output)
