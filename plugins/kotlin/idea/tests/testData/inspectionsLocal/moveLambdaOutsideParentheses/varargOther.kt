// ERROR: No value passed for parameter 'function'
// ERROR: Type mismatch: inferred type is () -> Unit but Int was expected
// K2-ERROR: Argument type mismatch: actual type is 'Function0<Unit>', but 'Int' was expected.
// K2-ERROR: No value passed for parameter 'function'.

fun foo(vararg x: Int, function: () -> Unit) {
}

fun main() {
    foo(0, <caret>{ })
}