#!/usr/bin/env python3
"""Build a guarded combined dispatch/complete-lane-handler recipe. No USB access."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from arm_link import link
from iap2_lane_recipe import Elf,STOCK_MD5
from paths import REPO


def build(library,output):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    stock=Elf(library);assert hashlib.md5(stock.data).hexdigest()==STOCK_MD5
    sources=['iap2-lane-dispatch.S','iap2-lane-decoder.c','iap2-lane-api.S','iap2-lane-capability.S',
             'iap2-route-normalize.c','iap2-route-api.S']
    objects=[];hashes={}
    for name in sources:
        src=REPO/'wireless-carplay-aa/native'/name;obj=output/(src.stem+'.o')
        flags=['-Os','-fPIC','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-unwind-tables','-fno-asynchronous-unwind-tables'] if src.suffix=='.c' else []
        subprocess.run(['clang','--target=armv7-none-eabi','-march=armv7-a','-mthumb',*flags,'-c',str(src),'-o',str(obj)],check=True)
        objects.append(obj);hashes[str(src.relative_to(REPO))]=hashlib.sha256(src.read_bytes()).hexdigest()
    layout='SECTIONS { . = 0; .text : { *iap2-lane-dispatch.o(.text) *(.text*) *(.rodata*) } /DISCARD/ : { *(.ARM.exidx*) *(.ARM.extab*) *(.comment*) *(.note*) } }'
    linked=output/'decoder.elf';link(objects,linked,layout,entry='iap2_lane_dispatch_fix')
    elf=Elf(linked);code=elf.section_data(elf.sections['.text'])
    assert not any(s[1] in (9,4) and s[5] for s in elf.sh),'Unresolved relocation'
    assert len(code)<4096
    symbols={k:v[0] for k,v in elf.symbols.items()}
    assert symbols['iap2_lane_dispatch_fix']==1
    regions=[('iAP2ControlSessionHandleMsg',480),('iAP2ControlLaneGuidanceInformation',164),
             ('iAP2ControlRouteGuidanceUpdate',140),
             ('iAP2SessionGetControlContext',None),('_Z35iAP2SessionPacketCreateNotifyPacketii',132),('iAP2EaSess_Notify',54),
             ('_Z35iAP2ControlInfoAddRouteGuidanceDonettPv',220),
             ('_Z48iAP2ControlInfo_RouteGuidanceGetComponentPointerttPtPvPi',136),
             ('_Z48iAP2ControlIden_RouteGuidanceGetComponentPointerttPtPvPi',100),
             ('iAP2InterfaceLogError',36)]
    lines=['# Complete bounded lane handler plus guarded dispatch correction; memory only.','id iap2-lanes-decoder-fc7cb675','file /system/lib/libiap2session.so']
    verified=[]
    for name,expected in regions:
        at,n=stock.symbols[name];at&=~1
        if expected is not None:assert n==expected,(name,n)
        raw=stock.data[stock.offset(at):stock.offset(at)+n]
        lines.append(f'verify 0x{at:x} {n} {hashlib.md5(raw).hexdigest()} -');verified.append({'name':name,'offset':at,'length':n})
    lines.append(f'segment 0x40000 {code.hex()}')
    for slot,anchor,target in [('iap2_lane_target_delta','iap2_lane_target_pc',0x31c80),('iap2_lane_return_delta','iap2_lane_return_pc',0x14661),
                               ('lane_context_delta','lane_context_pc',0x17c49),('lane_allocate_delta','lane_allocate_pc',0x19ce1),('lane_notify_delta','lane_notify_pc',0x14525),
                               ('lane_log_delta','lane_log_pc',0x17881),('lane_capability_return_delta','lane_capability_return_pc',0x1d4e1),
                               ('route_return_delta','route_return_pc',0x235b1)]:
        lines.append(f'rel32 0x{symbols[slot]:x} 0x{symbols[anchor]+4:x} 0x{target:x}')
    # Replace the unfinished handler BEFORE enabling its message ID.
    lines.append(f'branch 0x23638 0x{symbols["palisade_lane_entry"]&~1:x}')
    original_route=stock.data[stock.offset(0x235ac):stock.offset(0x235ac)+4]
    assert original_route==code[symbols['route_original_prologue']:symbols['route_original_prologue']+4]
    lines.append(f'branch 0x235ac 0x{symbols["palisade_route_entry"]&~1:x}')
    lines.append('branch 0x1465c 0')
    original=stock.data[stock.offset(0x1d4dc):stock.offset(0x1d4dc)+4]
    assert original==code[symbols['iap2_lane_capability_prologue']:symbols['iap2_lane_capability_prologue']+4]
    lines.append(f'branch 0x1d4dc 0x{symbols["iap2_lane_capability_fix"]&~1:x}')
    recipe='\n'.join(lines)+'\n';(output/'iap2-lanes-decoder-fc7cb675.recipe').write_text(recipe)
    audit={'stock_md5':STOCK_MD5,'stock_sha256':hashlib.sha256(stock.data).hexdigest(),'sources':hashes,
           'recipe_sha256':hashlib.sha256(recipe.encode()).hexdigest(),'code_bytes':len(code),'symbols':symbols,'verify_regions':verified,
           'vehicle_test':'not run','full_pipeline_validation':'pending','usb_staging_allowed':False}
    (output/'decoder-audit.json').write_text(json.dumps(audit,indent=2)+'\n');print('Built decoder recipe:',len(code),'bytes; not approved for USB staging')


if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--library',type=Path,required=True);ap.add_argument('--output',type=Path,default=REPO/'out/palisade/lane-decoder-build');a=ap.parse_args();build(a.library,a.output)
