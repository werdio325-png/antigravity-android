.text
.global _init_lse_emulator
.type _init_lse_emulator, %function

.section .init_array, "aw"
.align 3
.quad _init_lse_emulator

.text
_init_lse_emulator:
    stp x29, x30, [sp, #-32]!
    mov x29, sp
    str x19, [sp, #16]

    // Set up sigaction for SIGILL (4)
    // rt_sigaction(4, &act, NULL, 8)
    adrp x1, sigill_act
    add x1, x1, :lo12:sigill_act
    mov x0, #4        // SIGILL
    mov x2, #0        // NULL
    mov x3, #8        // sizeof(sigset_t)
    mov x8, #134      // __NR_rt_sigaction
    svc #0

    ldr x19, [sp, #16]
    ldp x29, x30, [sp], #32
    ret


.global sigill_handler
.type sigill_handler, %function
sigill_handler:
    // Save all callee-saved registers and frame
    stp x19, x20, [sp, #-96]!
    stp x21, x22, [sp, #16]
    stp x23, x24, [sp, #32]
    stp x25, x26, [sp, #48]
    stp x27, x28, [sp, #64]
    stp x29, x30, [sp, #80]
    mov x29, sp

    mov x19, x2                 // x19 = ucontext_t*
    ldr x20, [x19, #0x1b8]      // x20 = faulting PC

    // Fetch instruction at PC
    ldr w21, [x20]              // w21 = instruction opcode

    // Advance PC by 4 so returning resumes at next instruction
    add x0, x20, #4
    str x0, [x19, #0x1b8]

    // Check for ARMv8.2-A vector Dot Product (UDOT / SDOT)
    // Format: bit 31=0, bits [28:21]=0x74, bits [15:10]=0x25
    tbnz w21, #31, .Lcheck_lse
    ubfx w0, w21, #21, #8
    cmp w0, #0x74
    b.ne .Lcheck_lse
    ubfx w0, w21, #10, #6
    cmp w0, #0x25
    b.eq .Lhandle_dotprod

.Lcheck_lse:
    // Check bit 21 (must be 1 for LSE/LRCPC)
    tbz w21, #21, .Lnot_emulated

    // Check opcode [29:24]
    ubfx w0, w21, #24, #6
    cmp w0, #0x38               // 0b111000: Atomic memory ops OR LDAPR
    b.eq .Lcheck_atomic_or_ldapr
    cmp w0, #0x08               // 0b001000: CAS
    b.eq .Lcheck_cas
    b .Lnot_emulated

.Lcheck_atomic_or_ldapr:
    ubfx w1, w21, #10, #6
    cmp w1, #0x30               // LDAPR has bits [15:10] == 0x30
    b.eq .Lhandle_ldapr
    tst w1, #3                  // Atomic memory ops have bits [11:10] == 0
    b.ne .Lnot_emulated
    b .Lhandle_atomic_op

.Lcheck_cas:
    ubfx w1, w21, #10, #5       // CAS has bits [14:10] == 0x1f
    cmp w1, #0x1f
    b.eq .Lhandle_cas
    b .Lnot_emulated

// ==========================================
// Handler 0: Vector Dot Product (UDOT / SDOT)
// ==========================================
.Lhandle_dotprod:
    // Scan for FPSIMD_MAGIC (0x46508001) in ucontext [0x1b0 .. 0x220]
    mov x0, #0x1b0
    mov w1, #0x8001
    movk w1, #0x4650, lsl #16   // w1 = 0x46508001 (FPSIMD_MAGIC)
.Lscan_fpsimd:
    add x0, x0, #8
    cmp x0, #0x220
    b.ge .Lnot_emulated
    ldr w2, [x19, x0]
    cmp w2, w1
    b.ne .Lscan_fpsimd

    // Found magic at x19 + x0!
    // vregs[0] is at x19 + x0 + 16
    add x26, x19, x0
    add x26, x26, #16           // x26 = &vregs[0]

    // x23 = pointer to Vd in ucontext
    and w0, w21, #0x1f
    add x23, x26, x0, lsl #4

    // x24 = pointer to Vn in ucontext
    ubfx w0, w21, #5, #5
    add x24, x26, x0, lsl #4

    // x25 = pointer to Vm in ucontext
    ubfx w0, w21, #16, #5
    add x25, x26, x0, lsl #4

    // Number of lanes: 4 if Q=1 (bit 30), 2 if Q=0
    tbz w21, #30, 1f
    mov w27, #4
    b 2f
1:  mov w27, #2
2:
    mov x28, #0

.Ldot_loop:
    ldr w1, [x24, x28]
    ldr w2, [x25, x28]

    // Check U bit (bit 29): 1=UDOT, 0=SDOT
    tbz w21, #29, .Lsdot_calc

    // UDOT: unsigned
    uxtb w3, w1
    uxtb w4, w2
    mul w5, w3, w4

    ubfx w3, w1, #8, #8
    ubfx w4, w2, #8, #8
    madd w5, w3, w4, w5

    ubfx w3, w1, #16, #8
    ubfx w4, w2, #16, #8
    madd w5, w3, w4, w5

    lsr w3, w1, #24
    lsr w4, w2, #24
    madd w5, w3, w4, w5
    b .Ldot_accum

.Lsdot_calc:
    // SDOT: signed
    sxtb w3, w1
    sxtb w4, w2
    mul w5, w3, w4

    sbfx w3, w1, #8, #8
    sbfx w4, w2, #8, #8
    madd w5, w3, w4, w5

    sbfx w3, w1, #16, #8
    sbfx w4, w2, #16, #8
    madd w5, w3, w4, w5

    asr w3, w1, #24
    asr w4, w2, #24
    madd w5, w3, w4, w5

.Ldot_accum:
    ldr w6, [x23, x28]
    add w6, w6, w5
    str w6, [x23, x28]

    add x28, x28, #4
    subs w27, w27, #1
    b.ne .Ldot_loop
    b .Ldone

.Lnot_emulated:
    // Format and print: "SIGILL at PC=0x<x20> ins=0x<w21>\n"
    sub sp, sp, #128
    adr x0, err_prefix
    mov x1, sp
    ldp x2, x3, [x0]
    stp x2, x3, [x1]
    ldr x2, [x0, #16]
    str x2, [x1, #16]           // "SIGILL at PC=0x"

    // Format x20 (PC) at sp + 15 (16 hex digits)
    mov x0, x20
    add x1, sp, #15
    mov w2, #16
    bl .Lformat_hex

    // Append " ins=0x" at sp + 31
    adr x0, err_ins
    ldr x2, [x0]
    str x2, [sp, #31]

    // Format w21 at sp + 38 (8 hex digits)
    mov w0, w21
    add x1, sp, #38
    mov w2, #8
    bl .Lformat_hex

    // Append "\n" at sp + 46
    mov w0, #'\n'
    strb w0, [sp, #46]

    // write(2, sp, 47)
    mov x0, #2
    mov x1, sp
    mov x2, #47
    mov x8, #64
    svc #0

    add sp, sp, #128

    mov x0, #132                // SIGILL exit code
    mov x8, #93                 // exit_group
    svc #0

// Helper: format x0 as hex into buffer [x1], length w2 digits
.Lformat_hex:
    add x1, x1, x2              // point past end
.Lhex_loop:
    sub x1, x1, #1
    and w3, w0, #0xf
    cmp w3, #10
    blt .Lhex_digit
    add w3, w3, #('a' - 10)
    b .Lhex_store
.Lhex_digit:
    add w3, w3, #'0'
.Lhex_store:
    strb w3, [x1]
    lsr x0, x0, #4
    subs w2, w2, #1
    b.ne .Lhex_loop
    ret

// ==========================================
// Handler 1: Atomic memory operations (LDADD, LDCLR, LDEOR, LDSET, SWP, etc.)
// ==========================================
.Lhandle_atomic_op:
    ubfx w22, w21, #30, #2      // w22 = size (0=8b, 1=16b, 2=32b, 3=64b)
    ubfx w0, w21, #5, #5        // w0 = Rn (memory base address reg)
    bl .Lget_addr_reg
    mov x23, x0                 // x23 = mem_addr

    ubfx w0, w21, #16, #5       // w0 = Rs (operand register)
    bl .Lget_reg_val
    mov x24, x0                 // x24 = reg_val (operand)

    // Decode opcode: bit 15 is 1 -> SWP (code 8), else bits [14:12] (code 0..7)
    tbnz w21, #15, .Latom_is_swp
    ubfx w27, w21, #12, #3      // w27 = opc (0=ADD, 1=CLR, 2=EOR, 3=SET, 4=SMAX, 5=SMIN, 6=UMAX, 7=UMIN)
    b .Latom_start
.Latom_is_swp:
    mov w27, #8

.Latom_start:
    dmb ish
    cmp w22, #3
    b.eq .Latom_loop_64
    cmp w22, #2
    b.eq .Latom_loop_32
    cmp w22, #1
    b.eq .Latom_loop_16

// 8-bit atomic loop
.Latom_loop_8:
    ldxrb w25, [x23]
    cmp w27, #0
    b.eq 1f
    cmp w27, #8
    b.eq 2f
    cmp w27, #1
    b.eq 3f
    cmp w27, #2
    b.eq 4f
    cmp w27, #3
    b.eq 5f
    mov w26, w24; b 9f
1:  add w26, w25, w24; b 9f
2:  mov w26, w24; b 9f
3:  bic w26, w25, w24; b 9f
4:  eor w26, w25, w24; b 9f
5:  orr w26, w25, w24; b 9f
9:
    stxrb w2, w26, [x23]
    cbnz w2, .Latom_loop_8
    b .Latom_done_mem

// 16-bit atomic loop
.Latom_loop_16:
    ldxrh w25, [x23]
    cmp w27, #0
    b.eq 1f
    cmp w27, #8
    b.eq 2f
    cmp w27, #1
    b.eq 3f
    cmp w27, #2
    b.eq 4f
    cmp w27, #3
    b.eq 5f
    mov w26, w24; b 9f
1:  add w26, w25, w24; b 9f
2:  mov w26, w24; b 9f
3:  bic w26, w25, w24; b 9f
4:  eor w26, w25, w24; b 9f
5:  orr w26, w25, w24; b 9f
9:
    stxrh w2, w26, [x23]
    cbnz w2, .Latom_loop_16
    b .Latom_done_mem

// 32-bit atomic loop
.Latom_loop_32:
    ldxr w25, [x23]
    cmp w27, #0
    b.eq 1f
    cmp w27, #8
    b.eq 2f
    cmp w27, #1
    b.eq 3f
    cmp w27, #2
    b.eq 4f
    cmp w27, #3
    b.eq 5f
    cmp w27, #4
    b.eq 6f
    cmp w27, #5
    b.eq 7f
    cmp w27, #6
    b.eq 8f
    // opc 7: UMIN
    cmp w25, w24
    csel w26, w25, w24, lo
    b 9f
1:  add w26, w25, w24; b 9f
2:  mov w26, w24; b 9f
3:  bic w26, w25, w24; b 9f
4:  eor w26, w25, w24; b 9f
5:  orr w26, w25, w24; b 9f
6:  cmp w25, w24; csel w26, w25, w24, gt; b 9f
7:  cmp w25, w24; csel w26, w25, w24, lt; b 9f
8:  cmp w25, w24; csel w26, w25, w24, hi; b 9f
9:
    stxr w2, w26, [x23]
    cbnz w2, .Latom_loop_32
    b .Latom_done_mem

// 64-bit atomic loop
.Latom_loop_64:
    ldxr x25, [x23]
    cmp w27, #0
    b.eq 1f
    cmp w27, #8
    b.eq 2f
    cmp w27, #1
    b.eq 3f
    cmp w27, #2
    b.eq 4f
    cmp w27, #3
    b.eq 5f
    cmp w27, #4
    b.eq 6f
    cmp w27, #5
    b.eq 7f
    cmp w27, #6
    b.eq 8f
    // opc 7: UMIN
    cmp x25, x24
    csel x26, x25, x24, lo
    b 9f
1:  add x26, x25, x24; b 9f
2:  mov x26, x24; b 9f
3:  bic x26, x25, x24; b 9f
4:  eor x26, x25, x24; b 9f
5:  orr x26, x25, x24; b 9f
6:  cmp x25, x24; csel x26, x25, x24, gt; b 9f
7:  cmp x25, x24; csel x26, x25, x24, lt; b 9f
8:  cmp x25, x24; csel x26, x25, x24, hi; b 9f
9:
    stxr w2, x26, [x23]
    cbnz w2, .Latom_loop_64

.Latom_done_mem:
    dmb ish
    and w0, w21, #0x1f          // w0 = Rt
    mov x1, x25                 // original loaded value
    bl .Lset_reg_val
    b .Ldone

// ==========================================
// Handler 2: Compare and Swap (CAS)
// ==========================================
.Lhandle_cas:
    ubfx w22, w21, #30, #2      // w22 = size (0=8b, 1=16b, 2=32b, 3=64b)
    ubfx w0, w21, #5, #5        // w0 = Rn (mem_addr)
    bl .Lget_addr_reg
    mov x23, x0                 // x23 = mem_addr

    ubfx w0, w21, #16, #5       // w0 = Rs (expected value)
    bl .Lget_reg_val
    mov x24, x0                 // x24 = expected

    and w0, w21, #0x1f          // w0 = Rt (desired value)
    bl .Lget_reg_val
    mov x25, x0                 // x25 = desired

    dmb ish
    cmp w22, #0
    b.eq .Lcas_loop_8
    cmp w22, #1
    b.eq .Lcas_loop_16
    cmp w22, #2
    b.eq .Lcas_loop_32

// 64-bit CAS
.Lcas_loop_64:
    ldxr x26, [x23]
    cmp x26, x24
    b.ne .Lcas_mismatch
    stxr w2, x25, [x23]
    cbnz w2, .Lcas_loop_64
    b .Lcas_done_mem

// 32-bit CAS
.Lcas_loop_32:
    ldxr w26, [x23]
    cmp w26, w24
    b.ne .Lcas_mismatch
    stxr w2, w25, [x23]
    cbnz w2, .Lcas_loop_32
    b .Lcas_done_mem

// 16-bit CAS
.Lcas_loop_16:
    ldxrh w26, [x23]
    and w1, w24, #0xffff
    cmp w26, w1
    b.ne .Lcas_mismatch
    stxrh w2, w25, [x23]
    cbnz w2, .Lcas_loop_16
    b .Lcas_done_mem

// 8-bit CAS
.Lcas_loop_8:
    ldxrb w26, [x23]
    and w1, w24, #0xff
    cmp w26, w1
    b.ne .Lcas_mismatch
    stxrb w2, w25, [x23]
    cbnz w2, .Lcas_loop_8
    b .Lcas_done_mem

.Lcas_mismatch:
    clrex

.Lcas_done_mem:
    dmb ish
    // Set destination register Rs to original memory value x26
    ubfx w0, w21, #16, #5       // w0 = Rs
    mov x1, x26
    bl .Lset_reg_val
    b .Ldone

// ==========================================
// Handler 3: Load-Acquire RCpc (LDAPR)
// ==========================================
.Lhandle_ldapr:
    ubfx w22, w21, #30, #2      // w22 = size
    ubfx w0, w21, #5, #5        // w0 = Rn (mem_addr)
    bl .Lget_addr_reg
    mov x23, x0                 // x23 = mem_addr

    cmp w22, #0
    b.eq .Lldapr_8
    cmp w22, #1
    b.eq .Lldapr_16
    cmp w22, #2
    b.eq .Lldapr_32
    ldar x24, [x23]
    b .Lldapr_set_dest
.Lldapr_8:
    ldarb w24, [x23]
    b .Lldapr_set_dest
.Lldapr_16:
    ldarh w24, [x23]
    b .Lldapr_set_dest
.Lldapr_32:
    ldar w24, [x23]

.Lldapr_set_dest:
    and w0, w21, #0x1f          // w0 = Rt
    mov x1, x24
    bl .Lset_reg_val
    b .Ldone

.Ldone:
    ldp x19, x20, [sp]
    ldp x21, x22, [sp, #16]
    ldp x23, x24, [sp, #32]
    ldp x25, x26, [sp, #48]
    ldp x27, x28, [sp, #64]
    ldp x29, x30, [sp, #80]
    add sp, sp, #96
    ret

// ==========================================
// Helpers: Read/Write register in ucontext
// ==========================================
.Lget_addr_reg:
    // Base address register Rn: 31 is SP (at offset 0x1b0 = 0xb8 + 31*8)
    add x1, x19, #0xb8
    ldr x0, [x1, x0, lsl #3]
    ret

.Lget_reg_val:
    // Data register: 31 is XZR (value 0)
    cmp w0, #31
    b.eq .Lget_zr
    add x1, x19, #0xb8
    ldr x0, [x1, x0, lsl #3]
    ret
.Lget_zr:
    mov x0, #0
    ret

.Lset_reg_val:
    // Destination register: 31 is XZR (discard write)
    cmp w0, #31
    b.eq .Lset_done
    cmp w22, #3
    b.eq .Lset_64
    uxtw x1, w1                 // zero-extend 32-bit to 64-bit for Wn destination
.Lset_64:
    add x2, x19, #0xb8
    str x1, [x2, x0, lsl #3]
.Lset_done:
    ret

.data
err_prefix:
    .ascii "SIGILL at PC=0x\0"
err_ins:
    .ascii " ins=0x\0"
.align 8
sigill_act:
    .quad sigill_handler        // sa_handler / sa_sigaction
    .quad 0x00000004            // sa_flags = SA_SIGINFO (4)
    .quad 0                     // sa_restorer
    .quad 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // sa_mask
