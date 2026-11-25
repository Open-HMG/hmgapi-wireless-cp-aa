#!/usr/bin/env python3
"""engine-check.py - what a build of the car navigation's engine (libExSLNavi.so,
com.mnsoft.navi) offers for drawing intersections, as one JSON profile.

usage: engine-check.py <libExSLNavi.so> [<libExSLAndroidJNI.so>]

  identity      md5 (the recipe ids), sha256 (ch0iiih's EngineProfiles), size
  side_streets  whether tools/navi/side-streets-recipe.py finds the flag and its three
                reads here (a recipe can be generated for this build), and where
  stock_graph   what a port of ch0iiih's stock road-graph helper (HMG_Phone_Projection_TBT,
                navigation-api/native) needs from the engine: its 28 symbols (missing
                ones listed, with sizes for comparing builds), the NewComponent address
                its helper anchors on (pinned per engine), and whether the two weak
                mArray<...>::clear symbols are exported (else it needs fixed offsets)
  adasis        the ADASIS v2 horizon pieces (stub messages: side branches with turn
                angles), which the engine keeps making with no route of its own
  jni           with the JNI library: the Java callbacks a mod can hook
                (NativeCall.sendGISInfo for the position, sendClusterADAS*)

Reads the files only. Prints no bytes of them, only hashes, names, addresses and sizes,
so the profile can be shared to compare builds across cars.
"""
import hashlib, importlib.util, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ssr", os.path.join(HERE, "side-streets-recipe.py"))
ssr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ssr)

# the symbols ch0iiih's helper binds (abi-manifest.json + geometry-abi-manifest.json, v1.0.5)
STOCK_GRAPH = [
    "_ZN17CLifecycleManager12NewComponentEt",
    "_ZN17CLifecycleManager15DeleteComponentEtP10CComponent",
    "_ZN16CVehiclePositionC2Ev",
    "_ZN16CVehiclePositionD2Ev",
    "_ZN13CPositionInfo18GetDisplayPositionER16CVehiclePositionb",
    "_ZN16CVehiclePosition11GetParcelIdEv",
    "_ZN16CVehiclePosition9GetLinkIdEv",
    "_ZN16CVehiclePosition12GetDirectionEv",
    "_ZN14mByteAllocator19GetDefaultAllocatorEv",
    "_ZN16mEntityAllocatorC2EP14mByteAllocator",
    "_ZN11CEntityLinkC2ERK16mEntityAllocator",
    "_ZN11CEntityLinkD2Ev",
    "_ZN11CEntityLink10InitializeEyyt",
    "_ZN11CEntityLink5SpawnEP6mArrayI13stConnectLinkEh",
    "_ZN6mArrayI13stConnectLinkE5clearEv",
    "_ZN11CEntityLink15GetAttrOverPassEv",
    "_ZN11CEntityLink16GetAttrUnderPassEv",
    "_ZN11CEntityLink11GetPositionEP6mArrayI9mLonLat_tIiEE",
    "_ZN11CEntityLink9GetLengthEv",
    "_ZN6mArrayI9mLonLat_tIiEE5clearEv",
    "_ZN11CEntityLink9GetNodeIdEb",
    "_ZN11CEntityNodeC2ERK16mEntityAllocator",
    "_ZN11CEntityNodeD2Ev",
    "_ZN11CEntityNode10InitializeEyyjt",
    "_ZN11CEntityNode13GetAttrAdjestEv",
    "_ZN11CEntityNode12GetLinkCountEv",
    "_ZN11CEntityNode11GetConMapIdEv",
    "_ZN11CEntityNode12GetConLinkIdEi",
]
WEAK = ["_ZN6mArrayI13stConnectLinkE5clearEv", "_ZN6mArrayI9mLonLat_tIiEE5clearEv"]
ADASIS = {
    "make_stub": "_ZN11CStubMsgMgr11MakeStubMsgEbP9EHSubInfoS1_j",
    "send_stub_v23_android": "_ZN24NaviDrivingAssistManager24sendV2_3_StubMsg_AndroidERSt3mapIN7NaviEng12AdasSignalIDEjSt4lessIS2_ESaISt4pairIKS2_jEEE",
    "send_stub_android": "_ZN24NaviDrivingAssistManager19sendStubMsg_AndroidERSt3mapIN7NaviEng12AdasSignalIDEjSt4lessIS2_ESaISt4pairIKS2_jEEE",
}
JNI = ["sendGISInfo", "getCanSpeedKMH", "sendClusterADAS20", "sendClusterADASUnited", "sendADASData"]


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    b = open(sys.argv[1], "rb").read()
    syms = ssr.symbols(b)
    out = {
        "identity": {"md5": hashlib.md5(b).hexdigest(), "sha256": hashlib.sha256(b).hexdigest(), "size": len(b)},
    }
    try:
        flag = ssr.flag_offset(b, syms)
        sites = ssr.reads(b, syms, flag)
        out["side_streets"] = {"recipe": True, "flag": "0x%x" % flag,
                               "reads": ["0x%x" % at for _, at in sites]}
    except SystemExit as e:
        out["side_streets"] = {"recipe": False, "why": str(e)}
    nc = syms.get(STOCK_GRAPH[0])
    out["stock_graph"] = {
        "missing": [s for s in STOCK_GRAPH if s not in syms],
        "sizes": {s: syms[s][1] for s in STOCK_GRAPH if s in syms},
        "anchor_new_component": "0x%x" % (nc[0] | 1) if nc else None,
        "weak_exported": all(s in syms for s in WEAK),
        "unverified": ["NewComponent id 0x7200 is CPositionInfo", "its vtable slot 0x20",
                       "stConnectLink stride 0x58", "CVehiclePosition fits 1024 bytes"],
    }
    out["adasis"] = {k: v in syms for k, v in ADASIS.items()}
    if len(sys.argv) == 3:
        j = open(sys.argv[2], "rb").read()
        out["jni"] = {n: ("call_java_com_mnsoft_navi_NativeCall_" + n).encode() in j for n in JNI}
    json.dump(out, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
