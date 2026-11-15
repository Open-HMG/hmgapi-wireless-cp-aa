/* Add the missing lane capacities to the measured route component before stock
 * finalization. Preserve the argument registers and displaced 48-byte prologue. */
.syntax unified
.arch armv7-a
.thumb
.text
.global iap2_lane_capability_fix
.type iap2_lane_capability_fix,%function
.thumb_func
iap2_lane_capability_fix:
    push.w {r0, r1, r2, r3, r12, lr}
    mov r0, r2
    bl palisade_lane_capability
    pop.w {r0, r1, r2, r3, r12, lr}
.global iap2_lane_capability_prologue
iap2_lane_capability_prologue:
    push.w {r0, r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, lr}
    push {r0, r2}
    ldr r0, 1f
.global lane_capability_return_pc
lane_capability_return_pc:
    add r0, pc
    str r0, [sp, #4]
    pop {r0, pc}
.balign 4
.global lane_capability_return_delta
lane_capability_return_delta:
1:  .word 0
.size iap2_lane_capability_fix,.-iap2_lane_capability_fix
