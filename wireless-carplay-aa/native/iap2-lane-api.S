.syntax unified
.arch armv7-a
.thumb
.text
/* Loader-relocated veneers; compiler calls these within the same code blob. */
.macro api name
.global \name
.type \name,%function
.thumb_func
\name:
    ldr r12, 1f
.global \name\()_pc
\name\()_pc:
    add r12, pc
    bx r12
.balign 4
.global \name\()_delta
\name\()_delta:
1:  .word 0
.size \name, .-\name
.endm
api lane_context
api lane_allocate
api lane_notify
api lane_log
