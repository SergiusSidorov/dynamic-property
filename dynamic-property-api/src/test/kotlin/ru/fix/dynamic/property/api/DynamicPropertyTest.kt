package ru.fix.dynamic.property.api


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class DynamicPropertyTest {

    class MyService(poolSize: DynamicProperty<Int>) {
        private val poolSize: PropertySubscription<Int> = poolSize.callAndSubscribe(this) { oldValue, newValue ->
            println("poolSize changed from $oldValue to $newValue")
        }

        fun doWork() {
            println("doWork with poolSize: ${poolSize.get()}")
        }
    }

    @Test
    fun constant_property() {
        val property = DynamicProperty.of(122)
        assertEquals(122, property.get())
    }

    @Test
    fun atomic_property() {
        val property = AtomicProperty(122)
        assertEquals(122, property.get())


        val listenerAcceptedNewValue = AtomicReference<Int>()
        val listenerAcceptedOldValue = AtomicReference<Int>()

        val subscription = property.callAndSubscribe { old, new ->
            listenerAcceptedOldValue.set(old)
            listenerAcceptedNewValue.set(new)
        }

        property.set(123)

        assertEquals(123, property.get())
        assertEquals(122, listenerAcceptedOldValue.get())
        assertEquals(123, listenerAcceptedNewValue.get())
    }

    @Test
    fun mapped_property() {

        val stringProperty = AtomicProperty("159")

        val intProperty = stringProperty.map { str -> str.toInt() }

        assertEquals(159, intProperty.get())

        val captorOld = AtomicReference(0)
        val captorNew = AtomicReference(0)

        val subscription = intProperty.callAndSubscribe { old, new ->
            captorOld.set(old)
            captorNew.set(new)
        }

        stringProperty.set("305")

        assertEquals(305, captorNew.get())
        assertEquals(159, captorOld.get())
    }

    @Test
    fun `combine properties`() {
        val first = AtomicProperty("hello")
        val second = AtomicProperty("123")

        val combine = CombinedProperty(listOf(first, second)) { first.get() + second.get() }
        assertEquals("hello123", combine.get())

        first.set("hi")
        assertEquals("hi123", combine.get())

        second.set("42")
        assertEquals("hi42", combine.get())
    }

    @Test
    fun `delegated property`() {
        val property = DynamicProperty.delegated { 12 }
        assertEquals(12, property.get())
    }

    @Test
    fun `constant property of null`() {
        val property = DynamicProperty.of<String>(null)
        assertNull(property.get())
    }
}
