// IS_APPLICABLE: false
// ERROR: Property must be initialized or be abstract
// K2-ERROR: Property must be initialized or be abstract.
open class A {
    val <caret>foo: Int
}