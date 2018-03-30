package au.com.dius.pact.provider.junit.target

import au.com.dius.pact.model.DirectorySource
import au.com.dius.pact.model.Interaction
import au.com.dius.pact.model.PactBrokerSource
import au.com.dius.pact.model.PactSource
import au.com.dius.pact.provider.ConsumerInfo
import au.com.dius.pact.provider.PactVerification
import au.com.dius.pact.provider.ProviderInfo
import au.com.dius.pact.provider.ProviderVerifier
import au.com.dius.pact.provider.junit.Provider
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.util.function.Function
import java.util.function.Supplier

/**
 * Out-of-the-box implementation of [Target], that run [Interaction] against message pact and verify response
 * By default it will scan all packages for annotated methods, but a list of packages can be provided to reduce
 * the performance cost
 * @param packagesToScan List of JVM packages
 */
open class AmqpTarget @JvmOverloads constructor(val packagesToScan: List<String> = emptyList()) : BaseTarget() {

  private fun classPathUrls() = (ClassLoader.getSystemClassLoader() as URLClassLoader).urLs

  /**
   * {@inheritDoc}
   */
  override fun testInteraction(consumerName: String, interaction: Interaction, source: PactSource, verifier: ProviderVerifier) {
    val provider = getProviderInfo(source)
    val consumer = ConsumerInfo(consumerName)

    val failures = mutableMapOf<String, Any>()
    verifier.verifyResponseByInvokingProviderMethods(provider, consumer, interaction, interaction.description,
      failures)
    reportTestResult(failures.isEmpty(), verifier)

    try {
      if (failures.isNotEmpty()) {
        verifier.displayFailures(failures)
        throw getAssertionError(failures)
      }
    } finally {
      verifier.finialiseReports()
    }
  }

  override fun setupVerifier(interaction: Interaction, source: PactSource,
                             consumerName: String): ProviderVerifier {
    val provider = getProviderInfo(source)
    val consumer = ConsumerInfo(consumerName)
    val verifier = ProviderVerifier()
    verifier.projectClasspath = Supplier<Array<URL>> { this.classPathUrls() }
    val defaultProviderMethodInstance = verifier.providerMethodInstance
    verifier.providerMethodInstance = Function<Method, Any?> { m ->
      if (m.declaringClass == testTarget.javaClass) {
        testTarget
      } else {
        defaultProviderMethodInstance.apply(m)
      }
    }

    setupReporters(verifier, provider.name, interaction.description)

    verifier.initialiseReporters(provider)
    verifier.reportVerificationForConsumer(consumer, provider)

    if (!interaction.providerStates.isEmpty()) {
      for ((name) in interaction.providerStates) {
        verifier.reportStateForInteraction(name, provider, consumer, true)
      }
    }

    verifier.reportInteractionDescription(interaction)

    return verifier
  }

  override fun getProviderInfo(source: PactSource): ProviderInfo {
    val provider = testClass.getAnnotation(Provider::class.java)
    val providerInfo = ProviderInfo(provider.value)
    providerInfo.verificationType = PactVerification.ANNOTATED_METHOD
    providerInfo.packagesToScan = packagesToScan

    if (source is PactBrokerSource<*>) {
      val (_, _, pacts) = source
      providerInfo.consumers = pacts.entries.flatMap { e -> e.value.map { p -> ConsumerInfo(e.key.name) } }
    } else if (source is DirectorySource<*>) {
      val (_, pacts) = source
      providerInfo.consumers = pacts.entries.map { e -> ConsumerInfo(e.value.consumer.name) }
    }

    return providerInfo
  }
}
