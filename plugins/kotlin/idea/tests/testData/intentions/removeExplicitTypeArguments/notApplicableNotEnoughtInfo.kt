// IS_APPLICABLE: false
// ERROR: Unresolved reference: LinkedList
// K2-ERROR: Unresolved reference 'LinkedList'.
fun foo() {
    val x = bar<caret><String>()
}

fun <T> bar() : List<T> = LinkedList<T>();