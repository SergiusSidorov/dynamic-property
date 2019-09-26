package ru.fix.dynamic.property.zk

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.fix.dynamic.property.jackson.JacksonDynamicPropertyMarshaller
import ru.fix.dynamic.property.source.DefaultValue
import ru.fix.dynamic.property.source.SourcedProperty
import ru.fix.zookeeper.testing.ZKTestingServer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit


class ZkDynamicPropertySourceTest {
    companion object {
        private val TEST_PROP_KEY = "test_prop_key"
        private val TEST_PROP_KEY_1 = "test_prop_key_1"
        private val PROPERTIES_LOCATION = "/zookeeper/p"
    }

    private lateinit var zkTestingServer: ZKTestingServer
    private lateinit var source: ZkDynamicPropertySource

    @BeforeEach
    fun beforeEach() {
        zkTestingServer = ZKTestingServer().start()
        source = ZkDynamicPropertySource(
                zkTestingServer.client,
                PROPERTIES_LOCATION,
                JacksonDynamicPropertyMarshaller(),
                Duration.of(1, ChronoUnit.MINUTES)
        )
    }

    @AfterEach
    fun afterEach() {
        source.close()
        zkTestingServer.close()
    }

    @Test
    fun `property exist in store, ignore default value`() {
        val slot = LinkedBlockingDeque<String>()

        setServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY", "some Value")

        val subscription = source.subscribeAndCallListener(
                TEST_PROP_KEY,
                String::class.java,
                DefaultValue.of("zzz")
        ) { slot.add(it) }

        assertEquals("some Value", slot.removeFirst())
        assertTrue(slot.isEmpty())
    }

    @Test
    fun `property exist in store, ignore default value, react on property update`() {
        setServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY", "some Value")

        val valueSlot = LinkedBlockingDeque<String>()

        val subscription = source.subscribeAndCallListener(
                TEST_PROP_KEY,
                String::class.java,
                DefaultValue.of("zzz")
        ) { value -> valueSlot.add(value)}

        assertEquals("some Value", valueSlot.removeFirst())

        changeServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY", "some Value 2")

        assertEquals("some Value 2", valueSlot.takeFirst())
    }


    @Test
    fun `start with default value and then listen for property creation and change`() {
        val valueSlot = LinkedBlockingDeque<String>()

        val subscription = source.subscribeAndCallListener(
                TEST_PROP_KEY_1,
                String::class.java,
                DefaultValue.of("zzz")
        ) { value ->
            valueSlot.add(value)
        }

        assertEquals("zzz", valueSlot.takeFirst())
        assertTrue(valueSlot.isEmpty())

        setServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY_1", "some Value")
        assertEquals("some Value", valueSlot.takeFirst())
        assertTrue(valueSlot.isEmpty())

        changeServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY_1", "some Value 2")
        assertEquals("some Value 2", valueSlot.takeFirst())
        assertTrue(valueSlot.isEmpty())
    }

    @Test
    fun `property removed from source, use default value`() {
        val slot = LinkedBlockingDeque<String>()

        val sub = source.subscribeAndCallListener(
                TEST_PROP_KEY_1,
                String::class.java,
                DefaultValue.of("default")
        ) { value ->
            slot.add(value)
        }

        assertEquals("default", slot.removeFirst())
        assertTrue(slot.isEmpty())

        setServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY_1", "some Value")
        assertEquals("some Value", slot.takeFirst())
        assertTrue(slot.isEmpty())

        removeServerProperty("$PROPERTIES_LOCATION/$TEST_PROP_KEY_1")
        assertEquals("default",  slot.takeFirst())
        assertTrue(slot.isEmpty())
    }

    @Test
    fun `read all properties`() {
        setServerProperty("$PROPERTIES_LOCATION/propName1", "some Value 1")
        setServerProperty("$PROPERTIES_LOCATION/propName2", "some Value 2")

        val childProperties = source.readAllProperties()
        assertNotNull(childProperties)
        assertEquals(2, childProperties.size)
        assertEquals("some Value 1", childProperties["propName1"])
        assertEquals("some Value 2", childProperties["propName2"])
    }

    @Test
    fun shouldNotFetchPropertiesIfNotPresent() {
        val allProperties = source.readAllProperties()
        assertNotNull(allProperties)
        assertEquals(allProperties.size, 0)
    }

    @Test
    fun `default value of one holder does not affect default value of another holder`() {

        val propertyHolder = SourcedProperty(
                source,
                "propName1",
                String::class.java,
                DefaultValue.of("defaultHolderValue"))

        val propertyHolder2 = SourcedProperty(
                source,
                "propName1",
                String::class.java,
                DefaultValue.of("defaultHolderValue2"))

        assertEquals("defaultHolderValue", propertyHolder.get())

        setServerProperty("$PROPERTIES_LOCATION/propName1", "some Value 2")

        await().atMost(10, TimeUnit.SECONDS).until {
            propertyHolder.get() == "some Value 2"
        }

        removeServerProperty("$PROPERTIES_LOCATION/propName1")

        await().atMost(10, TimeUnit.SECONDS).until {
            propertyHolder.get() == "defaultHolderValue"
        }
    }


    @Test
    fun shouldGetDefaultValueFromHolder() {
        val holder = SourcedProperty(
                source!!,
                "unknown.property",
                String::class.java,
                DefaultValue.of("default Value")
        )
        assertEquals("default Value", holder.get())
    }


    private fun setServerProperty(propertyKey: String, value: String) {
        val data = value.toByteArray(StandardCharsets.UTF_8)
        zkTestingServer.client
                .create()
                .creatingParentsIfNeeded()
                .forPath(propertyKey, data)

        await().atMost(10, TimeUnit.SECONDS).until {
            zkTestingServer.client
                    .getData()
                    .forPath(propertyKey).contentEquals(data)
        }
    }

    private fun changeServerProperty(propertyKey: String, value: String) {
        zkTestingServer.client.setData().forPath(propertyKey, value.toByteArray(StandardCharsets.UTF_8))
    }


    private fun removeServerProperty(propertyKey: String) {
        zkTestingServer.client.delete().deletingChildrenIfNeeded().forPath(propertyKey)
    }
}