package com.malinskiy.marathon.vendor.junit4

import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.TestParser
import com.malinskiy.marathon.log.MarathonLogging
import com.malinskiy.marathon.test.Test
import com.malinskiy.marathon.vendor.junit4.configuration.Junit4Configuration
import com.malinskiy.marathon.vendor.junit4.extensions.switch
import org.junit.platform.engine.discovery.ClassNameFilter
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.discovery.LauncherDiscoveryListeners
import org.junit.vintage.engine.VintageTestEngine
import java.io.File
import java.net.URLClassLoader

class JupiterTestParser : TestParser {
    private val logger = MarathonLogging.logger {}

    override fun extract(configuration: Configuration): List<Test> {
        val conf = configuration.vendorConfiguration as Junit4Configuration
        val discoveredTests = mutableListOf<Test>()

        conf.testBundles?.forEach { bundle ->
            logger.debug { "Parsing ${bundle.name}" }
            val cp = mutableListOf<File>().apply {
                bundle.testClasspath?.let { addAll(it) }
                bundle.applicationClasspath?.let { addAll(it) }
            }.toList()

            val classpathToScan = cp.map { it.toURI().toURL() }
                .toTypedArray()
            val classloader: ClassLoader = URLClassLoader.newInstance(
                classpathToScan,
                Thread.currentThread().contextClassLoader
            )

            val plan: TestPlan = classloader.switch {
                val launcherConfig = LauncherConfig.builder()
                    .addTestEngines(VintageTestEngine())
                    .enableTestEngineAutoRegistration(false)
                    .build()
                val launcher = LauncherFactory.create(launcherConfig)

                val discoveryRequest = LauncherDiscoveryRequestBuilder()
                    .selectors(
                        DiscoverySelectors.selectPackage(conf.testPackageRoot)
                    )
                    .listeners(LauncherDiscoveryListeners.logging())
                    .filters(
                        ClassNameFilter.includeClassNamePatterns(ClassNameFilter.STANDARD_INCLUDE_PATTERN)
                    )
                    .build()

                launcher.discover(discoveryRequest)
            }

            if (plan.containsTests()) {
                plan.roots.forEach { root: TestIdentifier ->
                    val tests = plan.getChildren(root)
                    tests.forEach { test: TestIdentifier ->
                        when {
                            test.isContainer -> {
                                plan.getChildren(test).forEach { method ->
                                    if (method.source.isPresent) {
                                        val source = method.source.get()
                                        when (source) {
                                            is MethodSource -> {
                                                discoveredTests.add(source.toTest())
                                            }
                                            is ClassSource -> {
                                                val testIdentifier = plan.getTestIdentifier(method.uniqueId)
                                                if (plan.getParent(testIdentifier).isPresent) {
                                                    val parent = plan.getParent(testIdentifier).get()
                                                    val classSource = parent.source.get() as ClassSource
                                                    discoveredTests.add(classSource.toTest(source, testIdentifier.displayName))
                                                } else {
                                                    logger.warn { "Unknown test ${method.uniqueId}" }
                                                }
                                            }
                                            else -> {
                                                logger.warn { "Unknown test ${method.uniqueId}" }
                                            }
                                        }
                                    } else if (method.isContainer) {
                                        //Most likely a parameterized test
                                        plan.getChildren(method).forEach { parameterizedTest ->
                                            if (parameterizedTest.source.isPresent) {
                                                val source = parameterizedTest.source.get()
                                                when (source) {
                                                    is MethodSource -> {
                                                        discoveredTests.add(source.toParameterizedTest(parameterizedTest))
                                                    }
                                                    else -> {
                                                        logger.warn { "Unknown test ${parameterizedTest.uniqueId}" }
                                                    }
                                                }
                                            } else {
                                                logger.warn { "Unknown test ${parameterizedTest.uniqueId}" }
                                            }
                                        }
                                    } else {
                                        logger.warn { "Unknown test ${method.uniqueId}" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return discoveredTests.toList()
    }
}

private fun ClassSource.toTest(child: ClassSource, methodName: String): Test {
    val clazz = className.substringAfterLast(".")
    val pkg = className.substringBeforeLast(".")
    return Test(pkg, clazz, methodName, emptyList())
}

private fun MethodSource.toTest(): Test {
    val clazz = className.substringAfterLast(".")
    val pkg = className.substringBeforeLast(".")
    return Test(pkg, clazz, methodName, emptyList())
}

private fun MethodSource.toParameterizedTest(parameterizedTest: TestIdentifier): Test {
    val clazz = className.substringAfterLast(".")
    val pkg = className.substringBeforeLast(".")
    return Test(pkg, clazz, parameterizedTest.displayName, emptyList())
}