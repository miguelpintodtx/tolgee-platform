package io.tolgee.ee.unit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.tolgee.configuration.tolgee.machineTranslation.LlmProviderInterface
import io.tolgee.dtos.LlmParams
import io.tolgee.ee.component.llm.OpenaiApiService
import io.tolgee.model.enums.LlmProviderPriority
import io.tolgee.model.enums.LlmProviderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestTemplate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

class OpenaiApiServiceTest {
  private lateinit var service: OpenaiApiService
  private val objectMapper = jacksonObjectMapper()
  private var capturedRequestBody: String? = null

  @BeforeEach
  fun setUp() {
    service = OpenaiApiService()
    capturedRequestBody = null
  }

  @Test
  fun `adds extraBody values at top level for openai`() {
    val config =
      createConfig(
        type = LlmProviderType.OPENAI,
        extraBody =
          mapOf(
            "chat_template_kwargs" to mapOf("enable_thinking" to false),
          ),
      )
    val params = createParams(shouldOutputJson = false)

    service.translate(params, config, createCapturingRestTemplate())

    val bodyMap = objectMapper.readValue<Map<String, Any>>(capturedRequestBody!!)
    @Suppress("UNCHECKED_CAST")
    val kwargs = bodyMap["chat_template_kwargs"] as Map<String, Any>
    assertThat(kwargs).containsEntry("enable_thinking", false)
  }

  @Test
  fun `does not add extraBody when not configured`() {
    val config = createConfig(type = LlmProviderType.OPENAI)
    val params = createParams(shouldOutputJson = false)

    service.translate(params, config, createCapturingRestTemplate())

    val bodyMap = objectMapper.readValue<Map<String, Any>>(capturedRequestBody!!)
    assertThat(bodyMap).doesNotContainKey("chat_template_kwargs")
  }

  @Test
  fun `does not apply extraBody for azure openai`() {
    val config =
      createConfig(
        type = LlmProviderType.OPENAI_AZURE,
        extraBody = mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false)),
      )
    val params = createParams(shouldOutputJson = false)

    service.translate(params, config, createCapturingRestTemplate())

    val bodyMap = objectMapper.readValue<Map<String, Any>>(capturedRequestBody!!)
    assertThat(bodyMap).doesNotContainKey("chat_template_kwargs")
  }

  @Test
  fun `ignores reserved extraBody keys`() {
    val config =
      createConfig(
        type = LlmProviderType.OPENAI,
        extraBody =
          mapOf(
            "model" to "overridden-model",
            "messages" to listOf(mapOf("role" to "system", "content" to "nope")),
            "max_completion_tokens" to 1,
            "max_tokens" to 2,
          ),
        model = "expected-model",
      )
    val params = createParams(shouldOutputJson = false)

    service.translate(params, config, createCapturingRestTemplate())

    val bodyMap = objectMapper.readValue<Map<String, Any>>(capturedRequestBody!!)
    assertThat(bodyMap["model"]).isEqualTo("expected-model")
    assertThat(bodyMap["max_completion_tokens"]).isEqualTo(1000)
    assertThat(bodyMap).doesNotContainKey("max_tokens")
    @Suppress("UNCHECKED_CAST")
    val messages = bodyMap["messages"] as List<Map<String, Any>>
    assertThat(messages).hasSize(1)
  }

  private fun createConfig(
    type: LlmProviderType,
    extraBody: Map<String, Any>? = null,
    model: String? = "gpt-4o-mini",
  ): LlmProviderInterface {
    return object : LlmProviderInterface {
      override var name = "test-openai"
      override var type = type
      override var priority: LlmProviderPriority? = LlmProviderPriority.HIGH
      override var apiKey: String? = "test-key"
      override var apiUrl: String? = "https://api.openai.com"
      override var model: String? = model
      override var format: String? = null
      override var deployment: String? = "test-deployment"
      override var reasoningEffort: String? = null
      override var extraBody: Map<String, Any>? = extraBody
      override var maxTokens: Long = 1000
      override var tokenPriceInCreditsInput: Double? = null
      override var tokenPriceInCreditsOutput: Double? = null
      override var attempts: List<Int>? = null
    }
  }

  private fun createParams(shouldOutputJson: Boolean): LlmParams {
    return LlmParams(
      messages =
        listOf(
          LlmParams.Companion.LlmMessage(
            type = LlmParams.Companion.LlmMessageType.TEXT,
            text = "Translate 'hello' to Czech",
          ),
        ),
      shouldOutputJson = shouldOutputJson,
      priority = LlmProviderPriority.HIGH,
    )
  }

  private fun createCapturingRestTemplate(): RestTemplate {
    val responseJson =
      """
      {"choices":[{"message":{"content":"result"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0,"accepted_prediction_tokens":0,"rejected_prediction_tokens":0}}}
      """.trimIndent()

    val factory =
      ClientHttpRequestFactory { uri, httpMethod ->
        CapturingClientHttpRequest(uri, httpMethod, responseJson) { body ->
          capturedRequestBody = body
        }
      }

    return RestTemplate(factory)
  }

  private class CapturingClientHttpRequest(
    private val uri: URI,
    private val httpMethod: HttpMethod,
    private val responseJson: String,
    private val onBody: (String) -> Unit,
  ) : ClientHttpRequest {
    private val outputStream = ByteArrayOutputStream()
    private val headers = HttpHeaders()

    override fun getMethod() = httpMethod

    override fun getURI() = uri

    override fun getHeaders() = headers

    override fun getBody(): OutputStream = outputStream

    override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()

    override fun execute(): ClientHttpResponse {
      onBody(outputStream.toString(Charsets.UTF_8))
      return StubClientHttpResponse(responseJson)
    }
  }

  private class StubClientHttpResponse(
    private val body: String,
  ) : ClientHttpResponse {
    private val headers =
      HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
      }

    override fun getStatusCode() = HttpStatus.OK

    override fun getHeaders() = headers

    override fun getBody(): InputStream = ByteArrayInputStream(body.toByteArray())

    override fun close() {}

    @Deprecated("Deprecated in Java")
    override fun getRawStatusCode() = 200

    @Deprecated("Deprecated in Java")
    override fun getStatusText() = "OK"
  }
}