.text
.globl FakeGameplayPoll
FakeGameplayPoll:
    subq $40, %rsp
    movq %rcx, %rax
    movl %edx, %ecx
    movq %r8, %rdx
    callq *%rax
.globl FakeGameplayReturn
FakeGameplayReturn:
    addq $40, %rsp
    retq
