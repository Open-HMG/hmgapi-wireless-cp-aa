/* Memory-only correction for the measured Palisade iAP2 dispatch table.
 * The loader's native recipes patch executable mappings. This entry stub repairs
 * the separate writable data word only when its full old value is 0x00005202,
 * then reproduces the displaced prologue and resumes the original function.
 * All registers and stack alignment are preserved. No file is written.
 */
.syntax unified
.arch armv7-a
.thumb
.text
.global iap2_lane_dispatch_fix
.type iap2_lane_dispatch_fix, %function
.thumb_func
iap2_lane_dispatch_fix:
    /* Measured entry ABI: (session, packet, length, flag). C may clobber
     * caller-saved registers; preserve all of them plus LR in 24 aligned bytes.
     * The diagnostic reads only; original entry arguments resume unchanged. */
    push.w {r0, r1, r2, r3, r12, lr}
    mov r0, r1
    mov r1, r2
    bl palisade_route_trace
    pop.w {r0, r1, r2, r3, r12, lr}
    push {r0, r1}
    ldr r0, .Ltarget_delta
.global iap2_lane_target_pc
iap2_lane_target_pc:
    add r0, pc
    ldrb r1, [r0]
    cmp r1, #2
    bne 1f
    ldrb r1, [r0, #1]
    cmp r1, #0x52
    bne 1f
    ldrh r1, [r0, #2]
    cmp r1, #0
    bne 1f
    movs r1, #4
    strb r1, [r0]
    dmb ish
1:
    pop {r0, r1}
.global iap2_lane_original_prologue
iap2_lane_original_prologue:
    push.w {r4, r5, r6, r7, r8, r9, r10, lr}
    /* Reuse the saved-r2 stack slot for PC; live r2 is never modified. */
    push {r0, r2}
    ldr r0, .Lreturn_delta
.global iap2_lane_return_pc
iap2_lane_return_pc:
    add r0, pc
    str r0, [sp, #4]
    pop {r0, pc}
.balign 4
.global iap2_lane_target_delta
iap2_lane_target_delta:
.Ltarget_delta:
    .word 0
.global iap2_lane_return_delta
iap2_lane_return_delta:
.Lreturn_delta:
    .word 0
.size iap2_lane_dispatch_fix, .-iap2_lane_dispatch_fix
