package dev.cwtf.hidandseek.data

import dev.cwtf.hidandseek.data.llm.LlmClient
import dev.cwtf.hidandseek.data.llm.LlmModel
import dev.cwtf.hidandseek.data.llm.ModelFilter
import dev.cwtf.hidandseek.data.llm.chooseDefaultModel
import dev.cwtf.hidandseek.data.llm.ModelsUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/models` payload handling.
 *
 * The endpoint is standard but the payloads are not, so these cover the real
 * shapes: OpenRouter's rich metadata, DeepSeek's bare list, OpenAI's mixture of
 * chat and non-chat models, and a local server's minimal response.
 */
class LlmClientTest {

    private val client = LlmClient()

    @Test
    fun `deepseek style minimal list parses`() {
        val models = client.parseModels(
            """
            {"object":"list","data":[
              {"id":"deepseek-chat","object":"model"},
              {"id":"deepseek-reasoner","object":"model"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models.map { it.id })
        assertNull(models[0].contextLength, "absent fields must stay absent, not become zero")
        assertNull(models[0].supportsTools)
    }

    @Test
    fun `openrouter style metadata is read including capabilities and pricing`() {
        val models = client.parseModels(
            """
            {"data":[{
              "id":"vendor/model-x",
              "name":"Model X",
              "context_length":128000,
              "pricing":{"prompt":"0.00000014","completion":"0.00000028"},
              "architecture":{"input_modalities":["text","image"]},
              "supported_parameters":["tools","temperature"]
            }]}
            """.trimIndent(),
        )

        val model = models.single()
        assertEquals("Model X", model.displayName)
        assertEquals(128000, model.contextLength)
        assertEquals(true, model.supportsTools)
        assertEquals(true, model.supportsVision)
        // Providers quote per token; the UI compares per million.
        assertEquals(0.14, model.promptPricePerM!!, 0.001)
        assertEquals(0.28, model.completionPricePerM!!, 0.001)
    }

    @Test
    fun `absent capability data stays unknown rather than false`() {
        val model = client.parseModels("""{"data":[{"id":"m"}]}""").single()
        assertNull(model.supportsTools, "unknown must not be reported as unsupported")
        assertNull(model.supportsVision)
    }

    @Test
    fun `text-only model is marked as lacking vision`() {
        val model = client.parseModels(
            """{"data":[{"id":"m","architecture":{"input_modalities":["text"]}}]}""",
        ).single()
        assertEquals(false, model.supportsVision)
    }

    @Test
    fun `free variants are flagged`() {
        val models = client.parseModels(
            """{"data":[{"id":"vendor/model:free"},{"id":"vendor/model"}]}""",
        )
        assertEquals(true, models[0].isFree)
        assertEquals(false, models[1].isFree)
    }

    @Test
    fun `a bare array is accepted`() {
        val models = client.parseModels("""[{"id":"a"},{"id":"b"}]""")
        assertEquals(2, models.size)
    }

    @Test
    fun `a models key is accepted for servers that use it`() {
        assertEquals(1, client.parseModels("""{"models":[{"id":"llama3"}]}""").size)
    }

    @Test
    fun `entries with no id are skipped rather than crashing the list`() {
        val models = client.parseModels("""{"data":[{"object":"model"},{"id":"good"}]}""")
        assertEquals(listOf("good"), models.map { it.id })
    }

    @Test
    fun `an unrecognised payload is reported as no model list`() {
        assertFailsWith<ModelsUnavailable> { client.parseModels("""{"error":"nope"}""") }
    }
}

class ModelFilterTest {

    private fun models(vararg ids: String) = ids.map { LlmModel(id = it) }

    @Test
    fun `first discovered chat model becomes the default`() {
        val discovered = models("text-embedding-3-small", "gpt-4o-mini", "gpt-4o")

        assertEquals("gpt-4o-mini", chooseDefaultModel("", discovered))
    }

    @Test
    fun `explicit default model is preserved after discovery`() {
        assertEquals("manual-model", chooseDefaultModel("manual-model", models("gpt-4o")))
    }

    @Test
    fun `non-chat models are filtered out by default`() {
        val filtered = ModelFilter.apply(
            models(
                "gpt-4o", "text-embedding-3-large", "whisper-1", "tts-1",
                "dall-e-3", "omni-moderation-latest", "deepseek-chat",
            ),
            showAll = false,
        )
        assertEquals(listOf("gpt-4o", "deepseek-chat"), filtered.map { it.id })
    }

    @Test
    fun `showing all keeps everything`() {
        val all = models("gpt-4o", "text-embedding-3-large")
        assertEquals(2, ModelFilter.apply(all, showAll = true).size)
    }

    @Test
    fun `the filter is a heuristic on ids, which is why it can be turned off`() {
        // A chat model whose name happens to contain a filtered word gets
        // caught. Documented rather than pretended away.
        assertTrue(!ModelFilter.isLikelyChatModel("some-vision-image-model"))
    }
}
