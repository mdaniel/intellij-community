// WITH_STDLIB
// K2-ERROR: Missing return statement.

class Foo {
    companion object {
        @JvmStatic
        fun main(args: Array<String>): <caret>Int {}
    }
}