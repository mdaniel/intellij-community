// IS_APPLICABLE: false
// ERROR: Extension property cannot be initialized because it has no backing field
// K2-ERROR: Extension property cannot be initialized because it has no backing field.

class A

val A.a: Int = 0<caret>
