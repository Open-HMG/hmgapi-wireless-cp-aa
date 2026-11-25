# Android Auto developer identity

The `aa_developer_mode` setting defaults to true and takes effect after a head
unit restart. Disable it to restore the normal identity on the next connection
after restarting. This is a head unit protocol identity change; it does not
toggle Android's system developer options or change settings on the phone.

The implementation follows
[aa-proxy-rs's service discovery rewrite](https://github.com/aa-proxy/aa-proxy-rs/blob/main/src/mitm.rs):

| Service discovery field | Value |
| --- | --- |
| `make` | `Google` |
| `model` | `Desktop Head Unit` |
| `head_unit_make` | `Google` |
| `head_unit_model` | `Desktop Head Unit` |

Gen5W passes these values to `libautoreceiver_infobank.so` through the receiver's
`CarInfo` JNI argument (`manufacturer`, `model`, `headUnitMake`, `headUnitModel`).
The handoff runs after the stock app reads the vehicle information and before
it starts the receiver. Rewriting that argument supplies the values before
serialization and encryption, for wired and wireless sessions.

## SDK integration

The pinned SDK provides the mapped `gpm.receiver` class, but no CarInfo method or
field points. This implementation uses its public `Bridge.hookMethod` API for
the JNI handoff, resolved structurally on that mapped class. This is an exception
to the usual mapped-method workflow in CONTRIBUTING.md: no new catalogue point
is declared or assumed to exist in the pinned SDK. A future HMG-API contribution
can expose this handoff as a stable method point and replace the direct hook.

The resolver requires exactly one instance native void method with one argument
whose type is nested in the mapped receiver. The argument must have a no-argument
constructor and the four writable instance String fields required by the stock
JNI ABI. Other fields are copied unchanged. Unexpected layouts, ambiguous
methods, or reflection failures retain the original identity. No manufacturer
binary is included or modified, and this feature does not depend on the TBT
library recipes or `stepofspec`.

Each invocation copies the argument, replaces only those four strings, and
passes the copy to the original method. The receiver's saved object is never
changed. Because this hook uses Bridge directly, it explicitly checks both
`mod.enabled()` and the setting on every call. Logs contain only fixed status
messages, never vehicle fields or exception details.

## Validation

`wireless-carplay-aa/tools/aa-support/test.sh` covers the actual app-loading path,
all four rewritten fields, preservation of other data and the original object,
repeated calls, disable behavior, unrelated packages and incompatible layouts.
These are host tests with a recording bridge; they do not exercise Dalvik's JNI
dispatch or prove phone app availability. No on-car testing has been performed.

On a car, save/apply settings, restart the head unit, and connect an Android
phone. Confirm the enabled status in the mod log, verify that ordinary projection,
audio and turn-by-turn still work, and test an app intended for developer use.
Repeat after disconnecting and reconnecting, and with a wired connection if
available. Then disable the setting and restart to check restoration. Phone and
Android Auto versions can still affect which apps appear.
