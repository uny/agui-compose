package dev.ynagai.agui.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Not an assertion: a measurement, printed, of what the settled-prefix scheme saves over
 * re-parsing the accumulated run on every token. Run with `--info` (or read the test report's
 * standard output) to see it. The numbers are what uny/agui-compose#21 quotes.
 */
class StreamingCostMeasurement {
    private val flavour = GFMFlavourDescriptor()

    private fun run(paragraphs: Int): String {
        val paragraph = "Some prose with **emphasis**, a [link](https://x.y) and `code` in it, long enough to wrap. ".repeat(3).trim()
        return List(paragraphs) { i ->
            when (i % 7) {
                3 -> "- item one\n- item **two**\n- item three"
                6 -> "```kotlin\nfun f$i() = $i\n\nval v$i = f$i()\n```"
                else -> paragraph
            }
        }.joinToString("\n\n")
    }

    @Test
    fun measure() {
        for (paragraphs in listOf(10, 40, 160)) {
            val run = run(paragraphs)
            val delta = 8
            val steps = (run.length + delta - 1) / delta

            // Warm up both paths once so the JIT is not what is being compared.
            repeat(2) {
                fromTheTop(run, delta)
                settled(run, delta)
            }

            val top = measureTime { fromTheTop(run, delta) }
            val document = StreamingMarkdownDocument(flavour)
            val prefix = measureTime { settled(run, delta, document) }
            println(
                "run=${run.length} chars, $paragraphs blocks, $steps steps of $delta chars: " +
                    "from the top ${top.inWholeMilliseconds} ms (${top / steps} per token), " +
                    "settled prefix ${prefix.inWholeMilliseconds} ms (${prefix / steps} per token, ${document.parses} parses, ${document.segments.size} segments)",
            )
        }
    }

    private fun fromTheTop(run: String, delta: Int) {
        var fed = 0
        while (fed < run.length) {
            fed = minOf(run.length, fed + delta)
            flavour.parse(run.substring(0, fed))
        }
    }

    private fun settled(run: String, delta: Int, document: StreamingMarkdownDocument = StreamingMarkdownDocument(flavour)) {
        var fed = 0
        while (fed < run.length) {
            fed = minOf(run.length, fed + delta)
            document.update(run.substring(0, fed))
        }
    }
}
