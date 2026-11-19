"""Public checks of shared payload, helper and exact India support boundary."""
from pathlib import Path
import json, struct
root=Path(__file__).resolve().parents[2]
def recipe(name):
 return [x.split() for x in (root/'recipes'/name).read_text().splitlines() if x and not x.startswith('#')]
a=recipe('aa-tbt-b7940034.recipe');b=recipe('aa-tbt-48ee793a.recipe')
code=bytes.fromhex(next(x[2] for x in a if x[0]=='segment'))
old=bytes.fromhex(next(x[2] for x in b if x[0]=='segment'))
assert code[:0x40]==old[:0x40] and code[0x54:len(old)]==old[0x54:]
assert code[0x15ac:]==(root/'native/india-tbt/string.bin').read_bytes()
assert struct.unpack_from('<i',code,0x48)[0]+0x40==0x15ac+0xd
assert [(int(x[1],0),int(x[2],0),int(x[3],0)) for x in a if x[0]=='rel32']==[(0x40,0x40,0xad873),(0x44,0x40,0xad7e3),(0x4c,0x40,0xa7419),(0x50,0x40,0xba3e1),(0x15ac,0x15ac,0xa531c),(0x15b0,0x15ac,0xa5328),(0x15b4,0x15ac,0xa537c)]
assert len([x for x in a if x[0]=='verify'])==9
assert [x for x in a if x[0]=='branch']==[['branch','0xba3dc','0x0']]
assert [x for x in a if x[0]=='patch']==[['patch','0xb60a2','06'],['patch','0x144eba','03']]
m=json.loads((root/'mod.json').read_text())
assert [s for s in m['system'] if s.get('region')=='IND']==[{'model':'CVEV','region':'IND','firmware':'260430','features':['hmgapi.wifi']}]
assert json.loads((root/'patches.json').read_text())['cars']['CVEV.IND.0000.V010.001.260430']=='ev6_in'
assert 'codevalley.ev6.india.projection' in m['nemesis_conflicts']
print('India recipe: shared bridge, own ABI helper, relocations, guards and exact firmware boundary passed')
