// SKIP_ERRORS_BEFORE
// K2-AFTER-ERROR: One type argument expected for class Test<T> : Any.

class Test<T>
val x = Test::<caret>toString
