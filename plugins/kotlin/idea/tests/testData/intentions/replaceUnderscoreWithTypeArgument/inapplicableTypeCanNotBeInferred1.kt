// IS_APPLICABLE: false
// ERROR: Not enough information to infer type variable T
// ERROR: Not enough information to infer type variable T
// K2-ERROR: Cannot infer type for this parameter. Specify it explicitly.
// K2-ERROR: Cannot infer type for this parameter. Specify it explicitly.
// K2-ERROR: Not enough information to infer type argument for 'T'.
// K2-ERROR: Not enough information to infer type argument for 'T'.

class Some<T>
fun <T> foo(c: Some<T>) {}

fun test() {
    foo(Some<<caret>_>())
}