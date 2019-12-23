@file:JvmName("Assertions")
package net.corda.serialization.djvm

import net.corda.core.serialization.CordaSerializable
import org.junit.jupiter.api.Assertions.assertNull
import kotlin.reflect.KClass

fun assertNotCordaSerializable(klass: KClass<out Any>) {
    assertNotCordaSerializable(klass.java)
}

fun assertNotCordaSerializable(clazz: Class<*>) {
    assertNull(clazz.getAnnotation(CordaSerializable::class.java),
        "$clazz must NOT be annotated as @CordaSerializable!")
}
