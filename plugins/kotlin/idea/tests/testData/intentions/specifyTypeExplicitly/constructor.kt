// IS_APPLICABLE: false
// ERROR: Primary constructor call expected
// K2-ERROR: Primary constructor call expected.
class A(val x: Int) {
    constructor(x: String)<caret>
}
