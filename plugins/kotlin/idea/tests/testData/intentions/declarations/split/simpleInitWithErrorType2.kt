// PRIORITY: LOW
// ERROR: Unresolved reference: X
// SKIP_ERRORS_AFTER
// K2-ERROR: Syntax error: Incomplete code.
// K2-ERROR: Unresolved reference 'X'.

fun foo(n: Int) {
    <caret>val x = X<>()
}
