// ERROR: 'test2' overrides nothing
// ERROR: Too many arguments for public open fun test2(): Unit defined in Bar

interface Foo {
    fun test(arg: Int)
}

open class Bar : Foo {
    override fun test(arg: Int) {}
    open fun test(a: String) {}
    open fun test2() {}
}

class Baz(val foo: Foo) : Bar(), Foo by foo {
    override <caret>fun test2(arg: Int) = super.test2(arg)
}
