// IS_APPLICABLE: true
// COMPILER_ARGUMENTS: -Xmulti-dollar-interpolation
// K2-ERROR: Unresolved reference 'foo'.
// K2-AFTER-ERROR: Unresolved reference 'foo'.

fun test() {
    "${'$'}${'$'}${'$'}${'$'}${'$'}${'$'}$foo<caret>"
}
