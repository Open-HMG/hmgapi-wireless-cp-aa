/* Exact measured route-handler prologue and Thumb continuation. */
.syntax unified
.arch armv7-a
.thumb
.text
.global route_original
.type route_original, %function
.thumb_func
route_original:
.global route_original_prologue
route_original_prologue:
    push {r4, r5, r6, r7, lr}
    sub sp, #28
    push {r0, r2}
    ldr r0, .Lroute_return_delta
.global route_return_pc
route_return_pc:
    add r0, pc
    str r0, [sp, #4]
    pop {r0, pc}
.balign 4
.global route_return_delta
route_return_delta:
.Lroute_return_delta:
    .word 0
.size route_original, .-route_original
