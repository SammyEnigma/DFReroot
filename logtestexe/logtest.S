    // logtest.S - standalone logcat-via-socket test.
    //
    // A copy of the logdbg macro from app/src/main/jni/include.inc,
    // self-contained so it builds without the project tree. Raw syscalls
    // only, no libc: runs even where nothing else can be assumed.
    //
    // Build: ./build.sh   (needs the Android NDK)
    // Run (shell user): adb push logtest /data/local/tmp/
    //   adb shell chmod 755 /data/local/tmp/logtest
    //   adb shell /data/local/tmp/logtest
    //   adb logcat -s logtest
    //
    // The program prints one line to stdout (proves it ran) and sends one
    // logcat message with tag "logtest" (proves the socket mechanism).
    .arch armv8-a

    .equ AF_UNIX,              1
    .equ SOCK_DGRAM,           2
    .equ SOCK_CLOEXEC,   0x80000
    .equ SYS_write,           64
    .equ SYS_exit,            93
    .equ SYS_clock_gettime,  113
    .equ SYS_gettid,         178
    .equ SYS_socket,         198
    .equ SYS_close,           57
    .equ SYS_sendto,         206
    .equ LOG_ID_MAIN,          0
    .equ ANDROID_LOG_INFO,     4

    // load a 32-bit immediate using MOV
    .macro movl Wn, imm
        movz    \Wn,  \imm & 0xFFFF
        movk    \Wn, (\imm >> 16) & 0xFFFF, lsl 16
    .endm

    // Same body as logdbg in app/src/main/jni/include.inc.
    .macro logdbg msglabel, dwpath, taglbl
        stp x0, x1, [sp, #-16]!
        stp x2, x3, [sp, #-16]!
        stp x4, x5, [sp, #-16]!
        stp x6, x7, [sp, #-16]!
        stp x8, x9, [sp, #-16]!
        stp x26, x27, [sp, #-16]!
        stp x28, x29, [sp, #-16]!
        str x30, [sp, #-16]!

        adr x26, \msglabel

        mov x0, #AF_UNIX
        movl w1, (SOCK_DGRAM|SOCK_CLOEXEC)
        mov x2, xzr
        mov x8, #SYS_socket
        svc 0
        tbnz w0, #31, 2f
        mov x9, x0                    // fd

        sub sp, sp, #304              // datagram @+0, sockaddr @+256, timespec @+288
        mov x28, sp                   // datagram base (SP unusable in SUB-reg)

        // sockaddr_un: family + "/dev/socket/logdw" (18 bytes, addrlen 20)
        mov w5, #AF_UNIX
        strh w5, [sp, #256]
        adr x7, \dwpath
        add x6, sp, #258
        ldr x5, [x7], #8
        str x5, [x6], #8
        ldr x5, [x7], #8
        str x5, [x6], #8
        ldrh w5, [x7]
        strh w5, [x6]

        // header: id | tid | tv_sec | tv_nsec
        strb wzr, [sp]                // LOG_ID_MAIN
        mov x8, #SYS_gettid
        svc 0
        strh w0, [sp, #1]
        mov x0, xzr                   // CLOCK_REALTIME
        add x1, sp, #288
        mov x8, #SYS_clock_gettime
        svc 0
        ldr w8, [sp, #288]
        str w8, [sp, #3]
        ldr w8, [sp, #296]
        str w8, [sp, #7]

        // payload: prio | tag | msg
        mov w5, #ANDROID_LOG_INFO
        strb w5, [sp, #11]
        adr x7, \taglbl
        add x6, sp, #12
        ldr x5, [x7]                  // tag "logtest\0" is 8 bytes here
        str x5, [x6]
        add x6, x6, #8                // NOTE: must equal tag length incl. NUL
        mov x7, x26
        mov x27, xzr
1:
        ldrb w5, [x7], #1
        strb w5, [x6], #1
        add x27, x27, #1
        cbnz w5, 1b

        // sendto(fd, sp, x6-x28, 0, sp+256, 20); close(fd)
        mov x0, x9
        add x1, sp, #0
        sub x2, x6, x28
        mov x3, xzr
        add x4, sp, #256
        mov x5, #20
        mov x8, #SYS_sendto
        svc 0
        mov x0, x9
        mov x8, #SYS_close
        svc 0

        add sp, sp, #304
2:
        ldr x30, [sp], #16
        ldp x28, x29, [sp], #16
        ldp x26, x27, [sp], #16
        ldp x8, x9, [sp], #16
        ldp x6, x7, [sp], #16
        ldp x4, x5, [sp], #16
        ldp x2, x3, [sp], #16
        ldp x0, x1, [sp], #16
    .endm

    .text
    .globl _start
_start:
    logdbg test_msg, logdw_path, log_tag

    // stdout note (proves the binary itself ran, logcat or not)
    mov x0, #1
    adr x1, done_msg
    mov x2, #done_len
    mov x8, #SYS_write
    svc 0

    mov x0, #0
    mov x8, #SYS_exit
    svc 0

    .data
logdw_path:
    .asciz "/dev/socket/logdw"
log_tag:
    .asciz "logtest"
test_msg:
    .asciz "logtest: hello via logdw socket"
done_msg:
    .asciz "logtest: sent\n"
done_end:
    .equ done_len, done_end - done_msg
