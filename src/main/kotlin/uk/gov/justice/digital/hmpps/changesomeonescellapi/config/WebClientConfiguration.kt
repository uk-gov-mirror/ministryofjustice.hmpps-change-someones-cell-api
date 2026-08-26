package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.web.context.annotation.RequestScope
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.SYSTEM_USERNAME
import uk.gov.justice.hmpps.kotlin.auth.authorisedWebClient
import uk.gov.justice.hmpps.kotlin.auth.healthWebClient
import uk.gov.justice.hmpps.kotlin.auth.usernameAwareTokenRequestOAuth2AuthorizedClientManager
import java.time.Duration

@Configuration
class WebClientConfiguration(
  @param:Value("\${hmpps-auth.url}") val hmppsAuthBaseUri: String,
  @param:Value("\${prison-api.url}") val prisonApiBaseUri: String,
  @param:Value("\${case-notes-api.url}") val caseNotesApiBaseUri: String,
  @param:Value("\${prisoner-search.url}") val prisonerSearchBaseUri: String,
  @param:Value("\${locations-inside-prison-api.url}") val locationsInsidePrisonApiBaseUri: String,
  @param:Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @param:Value("\${api.timeout:20s}") val timeout: Duration,
) {
  /**
   * Overrides the client manager autoconfigured by hmpps-kotlin-spring-boot-starter (its bean is
   * @ConditionalOnMissingBean, so simply declaring this one wins) with the username-aware variant.
   *
   * This is not a preference. Both downstreams this service exists to orchestrate reject a bare
   * system token:
   *
   *  - prison-api's cell move is @ProxyUser, so the NOMIS audit columns must record the real user
   *    rather than this service's client id.
   *  - offender-case-notes refuses "sync to nomis" case note types without a NOMIS user, and
   *    MOVED_CELL is one of them.
   *
   * The manager adds a `username` form parameter to the client-credentials token request, taken
   * from the inbound authentication, so HMPPS Auth mints a token carrying the end user. This is
   * the supported replacement for the CustomOAuth2ClientCredentialsGrantRequestEntityConverter
   * that whereabouts-api hand-rolled - do not reintroduce that.
   *
   * It must be request scoped. The library reads
   * `SecurityContextHolder.getContext().authentication!!.name` when the manager is *constructed*,
   * not per request, so a singleton would both fail to start (no authentication exists during
   * context refresh) and then pin every later call to whichever user happened to be first.
   *
   * proxyMode is NO deliberately. The only consumers are the request-scoped web clients below,
   * which are themselves instantiated on the request thread, so they can take the real instance.
   * A scoped proxy here would break: authorisedWebClient installs a
   * ServletOAuth2AuthorizedClientExchangeFilterFunction that calls the manager from a reactor
   * thread, where there is no bound request for the proxy to resolve against.
   */
  @Bean
  @RequestScope(proxyMode = ScopedProxyMode.NO)
  fun authorizedClientManager(
    clientRegistrationRepository: ClientRegistrationRepository,
    authorizedClientService: OAuth2AuthorizedClientService,
  ): OAuth2AuthorizedClientManager = usernameAwareTokenRequestOAuth2AuthorizedClientManager(
    clientRegistrationRepository,
    authorizedClientService,
    timeout,
  )

  @Bean
  fun hmppsAuthHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(hmppsAuthBaseUri, healthTimeout)

  // prison-api performs the NOMIS cell move itself (MAPA-278). Wired here so the bootstrap
  // proves the authenticated client and its health ping work before any business logic depends
  // on them.
  //
  // Request scoped so that it, and the client manager it captures, are built on the request
  // thread while the end user's authentication is still on the SecurityContextHolder. Singleton
  // consumers get an interface proxy and resolve through it per request.
  @Bean
  @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
  fun prisonApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = prisonApiBaseUri,
    timeout,
  )

  @Bean
  fun prisonApiHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonApiBaseUri, healthTimeout)

  // offender-case-notes records the MOVED_CELL case note (MAPA-278). Request scoped for the same
  // reason as prisonApiWebClient.
  @Bean
  @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
  fun caseNotesApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = caseNotesApiBaseUri,
    timeout,
  )

  @Bean
  fun caseNotesApiHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(caseNotesApiBaseUri, healthTimeout)

  // prisoner-search resolves a prisoner number to their current booking id and cell. It is the
  // go-to source for prisoner data - a near real-time projection of NOMIS - and using it keeps
  // bookingId, a NOMIS-only concept, out of our API contract.
  @Bean
  @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
  fun prisonerSearchWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = prisonerSearchBaseUri,
    timeout,
  )

  @Bean
  fun prisonerSearchHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(prisonerSearchBaseUri, healthTimeout)

  // locations-inside-prison resolves location keys to their UUIDs - the durable identity a key
  // does not provide, since keys can be renamed. A permanent dependency, so unlike whereabouts it
  // gets a health ping.
  @Bean
  @RequestScope(proxyMode = ScopedProxyMode.INTERFACES)
  fun locationsInsidePrisonApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = SYSTEM_USERNAME,
    url = locationsInsidePrisonApiBaseUri,
    timeout,
  )

  @Bean
  fun locationsInsidePrisonApiHealthWebClient(builder: WebClient.Builder): WebClient = builder.healthWebClient(locationsInsidePrisonApiBaseUri, healthTimeout)
}
