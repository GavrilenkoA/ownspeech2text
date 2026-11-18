package app.murmur.core.prompt

import app.murmur.core.context.CtxV2

/** WP-04 replaces these with the ports. Kept so that the app graph compiles in wave 1. */
class RenderPyPort(private val assets: PromptAssets) : PromptRenderer {
    override fun render(input: RenderInput): RenderedPrompt = TODO("WP-04: port prompts/render.py render_prompt + cache_prefix")
    override fun renderContext(ctx: CtxV2, ctx2: kotlinx.serialization.json.JsonObject?): String = TODO("WP-04: port render_context_v2")
    override fun cachePrefix(profile: PromptProfile, template: String, preferences: String?, systemOverride: String?): String =
        TODO("WP-04: port cache_prefix")
}

class GuardPyPort : OutputGuard {
    override fun precheck(transcript: String): Precheck = TODO("WP-04: port prompts/guard.py precheck")
    override fun guard(transcript: String, output: String, ctx: CtxV2?): GuardVerdict = TODO("WP-04: port prompts/guard.py guard")
}
